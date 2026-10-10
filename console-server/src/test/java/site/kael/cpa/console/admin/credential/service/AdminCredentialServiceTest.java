package site.kael.cpa.console.admin.credential.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.credential.exception.CredentialResetException;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.exception.CpaUnavailableException;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.quota.manager.QuotaManager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCredentialServiceTest {
    private final CredentialManager credentials = mock(CredentialManager.class);
    private final CpaApiKeyManager cpa = mock(CpaApiKeyManager.class);
    private final QuotaManager quota = new QuotaManager(credentials, cpa);
    private final AdminCredentialService service = new AdminCredentialService(credentials, quota, cpa);

    private Credential credential(String type, String provider) {
        return new Credential(1L, "auth-index", "account.json", type, true, List.of("tag"),
                provider, "", "", 0, 0, List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    @BeforeEach
    void setUp() {
        when(credentials.findById(1L)).thenReturn(Optional.of(credential("auth_file", "codex")));
    }

    @Test
    void resetsQuotaThenCooldownWithTheCpaIndex() {
        var result = service.resetQuota(1L);
        var order = inOrder(cpa);
        order.verify(cpa).resetCredentialQuota("auth-index");
        order.verify(cpa).resetCredentialCooldown("auth-index");
        assertEquals("ok", result.status());
        assertEquals("completed", result.quota_reset());
        assertEquals("completed", result.cooldown_reset());
    }

    @Test
    void recognizesOpenaiAliasAndNormalizesListAndTagResponses() {
        var credential = credential("auth_file", " OPENAI ");
        when(credentials.findById(1L)).thenReturn(Optional.of(credential));
        when(credentials.synchronizeAndFindAll()).thenReturn(List.of(credential));
        when(credentials.updateTags(1L, List.of("tag"))).thenReturn(credential);
        assertEquals("codex", service.list().credentials().getFirst().provider());
        assertEquals("codex", service.updateTags(1L, List.of("tag", " tag ")).provider());
        assertEquals("ok", service.resetQuota(1L).status());
    }

    @Test
    void rejectsOtherOauthProvidersAndAllApiKeys() {
        for (var credential : List.of(credential("auth_file", "claude"), credential("auth_file", "kimi"),
                credential("apikey", "codex"))) {
            when(credentials.findById(1L)).thenReturn(Optional.of(credential));
            assertThrows(IllegalArgumentException.class, () -> service.resetQuota(1L));
        }
        verifyNoInteractions(cpa);
    }

    @Test
    void missingCredentialReturns404BeforeAnyCpaCall() {
        when(credentials.findById(1L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.resetQuota(1L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getQuota(1L)).getStatusCode().value());
        verifyNoInteractions(cpa);
    }

    @Test
    void quotaFailureDoesNotClearCooldown() {
        doThrow(new CpaManagementException("unsupported")).when(cpa).resetCredentialQuota("auth-index");
        var response = assertThrows(CredentialResetException.class, () -> service.resetQuota(1L)).response();
        assertEquals("failed", response.quota_reset());
        assertEquals("not_attempted", response.cooldown_reset());
        verify(cpa, never()).resetCredentialCooldown(anyString());
    }

    @Test
    void lostQuotaResponseIsUnknownAndIsNotRetried() {
        doThrow(new CpaUnavailableException(new IOException("timeout"))).when(cpa).resetCredentialQuota("auth-index");
        var response = assertThrows(CredentialResetException.class, () -> service.resetQuota(1L)).response();
        assertEquals("unknown", response.quota_reset());
        verify(cpa, times(1)).resetCredentialQuota("auth-index");
        verify(cpa, never()).resetCredentialCooldown(anyString());
    }

    @Test
    void cooldownFailurePreservesCompletedQuotaStage() {
        doThrow(new CpaManagementException("failed")).when(cpa).resetCredentialCooldown("auth-index");
        var response = assertThrows(CredentialResetException.class, () -> service.resetQuota(1L)).response();
        assertEquals("partial_success", response.status());
        assertEquals("completed", response.quota_reset());
        assertEquals("failed", response.cooldown_reset());
    }

    @Test
    void delegatesQuotaQueryToExistingQuotaManager() {
        when(credentials.findByReferenceId("auth-index")).thenReturn(Optional.of(credential("auth_file", "codex")));
        var result = Map.<String, Object>of("provider", "codex", "windows", List.of());
        when(cpa.getAuthFileQuota("auth-index", "codex", "")).thenReturn(result);
        assertEquals(result, service.getQuota(1L).quota());
    }
}
