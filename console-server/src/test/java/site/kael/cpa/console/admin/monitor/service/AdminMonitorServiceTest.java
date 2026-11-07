package site.kael.cpa.console.admin.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateModelsResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskCreateRequest;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskUpdateRequest;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver;
import site.kael.cpa.console.core.monitor.manager.QueryManager;
import site.kael.cpa.console.core.monitor.manager.TaskManager;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntry;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10 service self-check: candidate opacity, unsupported exclusion, strict request field
 * contracts and zero-verify semantics via a counting fake client. No real CPA anywhere.
 */
class AdminMonitorServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final ApiKeyCrypto CRYPTO = new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg==");

    /** Counting client fake: every GET/verify attempt is visible in the counters. */
    private static final class CountingClient extends CpaCredentialVerificationClient {
        private final AtomicInteger verifyPosts = new AtomicInteger();
        private final AtomicInteger credentialGets = new AtomicInteger();
        private final AtomicInteger modelGets = new AtomicInteger();

        private CountingClient() {
            super(new CpaEndpoint("http://127.0.0.1:0", "initial"), "test-management-key");
        }

        @Override
        public List<CredentialPage> listAllCredentialPages() {
            credentialGets.incrementAndGet();
            CredentialEntry supported = new CredentialEntry(
                    "real-auth-id", "real-auth-index", "name.json", "codex", "codex",
                    false, false, false);
            CredentialEntry unsupported = new CredentialEntry(
                    "other-id", "other-index", "", "", "", false, false, false);
            return List.of(new CredentialPage(List.of(supported, unsupported), false));
        }

        @Override
        public List<String> listModels(String nameOrAuthId) {
            modelGets.incrementAndGet();
            return "real-auth-id".equals(nameOrAuthId) || "name.json".equals(nameOrAuthId)
                    ? List.of("gpt-5", "gpt-5-mini") : List.of();
        }

        @Override
        public VerificationHttpResponse verify(VerifyRequest request) {
            verifyPosts.incrementAndGet();
            throw new AssertionError("T10 must never trigger a verify POST");
        }
    }

    private AdminMonitorService service(CountingClient client) {
        return new AdminMonitorService(new CpaIdentityResolver(client, CRYPTO, CLOCK),
                org.mockito.Mockito.mock(TaskManager.class),
                org.mockito.Mockito.mock(QueryManager.class),
                CRYPTO, CLOCK);
    }

    @Test
    void candidatesAreOpaqueAndUnsupportedHaveNoModels() {
        CountingClient client = new CountingClient();
        AdminCandidateListResponse response = service(client).candidates();
        assertEquals(0, client.verifyPosts.get());
        assertTrue(client.credentialGets.get() >= 1);
        assertTrue(client.modelGets.get() >= 1);
        assertEquals(2, response.candidates().size());
        var supported = response.candidates().get(0);
        var unsupported = response.candidates().get(1);
        assertTrue(supported.supported());
        assertEquals(List.of("gpt-5", "gpt-5-mini"), supported.models());
        assertFalse(unsupported.supported());
        assertTrue(unsupported.unsupported_reason() != null);
        assertTrue(unsupported.models().isEmpty());
        Map<String, Map<String, String>> choices = response.model_choices();
        assertTrue(choices.containsKey(supported.candidate_id()));
        assertTrue(choices.get(supported.candidate_id()).size() == supported.models().size());
        assertTrue(!choices.containsKey(unsupported.candidate_id())
                || choices.get(unsupported.candidate_id()).isEmpty());
        String body = response.toString();
        assertFalse(body.contains("real-auth-id"), "candidate transport must not leak real id");
        assertFalse(body.contains("real-auth-index"), "candidate transport must not leak real index");
    }

    @Test
    void snakeCaseJsonCarriesNoIdentityFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountingClient client = new CountingClient();
        AdminCandidateListResponse response = service(client).candidates();
        String json = mapper.writeValueAsString(response);
        assertTrue(json.contains("candidate_id") && json.contains("unsupported_reason")
                && json.contains("model_choices"));
        assertTrue(json.contains("\"supported\":true") && json.contains("\"supported\":false"));
        for (String forbidden : List.of("auth_id", "authIndex", "auth_index",
                "route_model_cipher", "real-auth-id", "real-auth-index")) {
            assertFalse(json.contains(forbidden), "must not contain " + forbidden);
        }
        assertEquals(0, client.verifyPosts.get());
    }

    @Test
    void unsupportedCandidateModelsAreNotFound() {
        CountingClient client = new CountingClient();
        AdminMonitorService service = service(client);
        AdminCandidateListResponse listing = service.candidates();
        String supportedId = listing.candidates().get(0).candidate_id();
        String unsupportedId = listing.candidates().get(1).candidate_id();
        AdminCandidateModelsResponse models = service.candidateModels(supportedId);
        assertEquals(List.of("gpt-5", "gpt-5-mini"), models.models());
        assertEquals(models.models().size(), models.model_choices().size());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.candidateModels(unsupportedId));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getMessage().contains("CANDIDATE_NOT_FOUND"));
        assertThrows(ResponseStatusException.class, () -> service.candidateModels("missing"));
        assertEquals(0, client.verifyPosts.get());
    }

    @Test
    void createRequestStrictFieldContract() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("safe_name", "每日检查");
        ok.put("cron_expression", "0 0 9 * * *");
        ok.put("zone_id", "UTC");
        ok.put("instance_key", "opaque-1");
        ok.put("model_choice_id", "choice-1");
        // The service consumes the original key list captured by the controller layer.
        AdminMonitorTaskCreateRequest parsed = new AdminMonitorTaskCreateRequest(
                (String) ok.get("safe_name"), (String) ok.get("cron_expression"),
                (String) ok.get("zone_id"), (String) ok.get("instance_key"),
                (String) ok.get("model_choice_id"), List.copyOf(ok.keySet()));
        assertEquals(List.copyOf(ok.keySet()), parsed.rawKeys());
        assertTrue(AdminMonitorTaskCreateRequest.ALLOWED_KEYS.containsAll(parsed.rawKeys()));

        Map<String, Object> injected = new LinkedHashMap<>(ok);
        injected.put("route_model", "gpt-5");
        AdminMonitorTaskCreateRequest bad = new AdminMonitorTaskCreateRequest(
                null, null, null, null, null, List.copyOf(injected.keySet()));
        assertFalse(AdminMonitorTaskCreateRequest.ALLOWED_KEYS.containsAll(bad.rawKeys()));
    }

    @Test
    void updateRequestRequiresExpectedRevisionAndRejectsTargetFields() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("expected_revision", 0);
        ok.put("enabled", true);
        AdminMonitorTaskUpdateRequest parsed = new AdminMonitorTaskUpdateRequest(
                0L, null, null, null, true, List.copyOf(ok.keySet()));
        assertEquals(0L, parsed.expected_revision());
        assertTrue(AdminMonitorTaskUpdateRequest.ALLOWED_KEYS.containsAll(parsed.rawKeys()));
        assertTrue(parsed.enabled());

        Map<String, Object> targetInjection = new LinkedHashMap<>();
        targetInjection.put("expected_revision", 0);
        targetInjection.put("auth_id", "x");
        AdminMonitorTaskUpdateRequest bad = new AdminMonitorTaskUpdateRequest(
                0L, null, null, null, null, List.copyOf(targetInjection.keySet()));
        assertFalse(AdminMonitorTaskUpdateRequest.ALLOWED_KEYS.containsAll(bad.rawKeys()));
    }
}
