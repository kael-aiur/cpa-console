package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.BoundTarget;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntry;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.IdentityResolutionException;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.Resolution;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.Support;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.UnsupportedReason;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-C2/I-C4 self-check: ambiguous identities and models produce no fallback POST, binding
 * requires id AND index simultaneously and FileName ambiguity is rejected.
 */
class CpaIdentityResolverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneId.of("UTC"));

    @Test
    void missingIdentityProducesZeroPostsAndUnsupportedCandidate() {
        CountingClient client = new CountingClient(List.of(
                page(entry(null, "idx-a", "same-name.json", true))));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(0, client.modelQueries);
        assertEquals(0, client.verifyPosts);
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void ambiguousNameAndDiskDegradedAreFilteredBeforeModelLookup() {
        CountingClient client = new CountingClient(List.of(
                page(entry("id-a", "idx-a", "dup.json", false),
                     entry("id-b", "idx-b", "dup.json", false)),
                page(new CredentialEntry("id-c", "idx-c", "disk.json", "codex", "codex",
                        false, false, true))));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(0, client.modelQueries);
        assertEquals(0, client.verifyPosts);
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void modelAmbiguityBetweenIdAndFileNameIsRejected() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntry("id-a", "idx-a", "name.json", "codex", "codex",
                        false, false, false))));
        client.modelsById.put("id-a", List.of("m1"));
        client.modelsByName.put("name.json", List.of("m-other"));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(2, client.modelQueries);
        assertEquals(0, client.verifyPosts);
        assertTrue(resolution.candidates().stream().allMatch(c -> c.support() == Support.UNSUPPORTED));
        assertTrue(resolution.candidates().stream()
                .allMatch(c -> c.unsupportedReason() == UnsupportedReason.MODEL_LOOKUP_FAILED));
    }

    @Test
    void boundTargetRequiresIdAndIndexToMatchSimultaneously() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntry("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false))));
        client.modelsById.put("id-a", List.of("m1"));
        client.modelsByName.put("a.json", List.of("m1"));
        CpaIdentityResolver resolver = new CpaIdentityResolver(client, crypto(), CLOCK);
        BoundTarget ok = resolver.verifyBoundTarget("inst", "id-a", "idx-a", "m1");
        assertEquals("m1", ok.routeModel());
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("inst", "id-a", "idx-wrong", "m1"));
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("inst", "id-wrong", "idx-a", "m1"));
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("inst", "id-a", "idx-a", "m-missing"));
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("inst", "id-a", "idx-a", "  "));
        assertEquals(0, client.verifyPosts);
    }

    @Test
    void differentInstanceOrIdentityNeverSubstitutes() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntry("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false))));
        client.modelsById.put("id-a", List.of("m1"));
        client.modelsByName.put("a.json", List.of("m1"));
        CpaIdentityResolver resolver = new CpaIdentityResolver(client, crypto(), CLOCK);
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("other-inst", "id-wrong", "idx-a", "m1"));
        assertThrows(IdentityResolutionException.class,
                () -> resolver.verifyBoundTarget("inst", "id-wrong", "idx-wrong", "m1"));
        assertEquals(0, client.verifyPosts);
    }

    @Test
    void safeCandidateExposesOnlyOpaqueModelChoiceIds() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntry("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false))));
        client.modelsById.put("id-a", List.of("m1", "m2"));
        client.modelsByName.put("a.json", List.of("m1", "m2"));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(1, resolution.candidates().size());
        var candidate = resolution.candidates().getFirst();
        assertEquals(Support.SUPPORTED, candidate.support());
        assertTrue(candidate.models().containsAll(List.of("m1", "m2")));
        for (String choiceId : candidate.modelChoiceIds().keySet()) {
            assertFalse(choiceId.contains("m1") || choiceId.contains("m2")
                    || choiceId.contains("id-a") || choiceId.contains("idx-a"));
        }
        assertEquals(0, client.verifyPosts);
    }

    private ApiKeyCrypto crypto() {
        return new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg==");
    }

    private CredentialPage page(CredentialEntry... entries) {
        return new CredentialPage(List.of(entries), false);
    }

    private CredentialEntry entry(String id, String index, String name, boolean healthy) {
        return new CredentialEntry(id, index, name, "codex", "codex",
                false, false, !healthy);
    }

    private static final class CountingClient extends CpaCredentialVerificationClient {
        private final List<CredentialPage> pages;
        private final java.util.Map<String, List<String>> modelsById = new java.util.HashMap<>();
        private final java.util.Map<String, List<String>> modelsByName = new java.util.HashMap<>();
        private int modelQueries;
        private int verifyPosts;
        private final java.util.Set<String> seenQueries = new java.util.HashSet<>();

        CountingClient(List<CredentialPage> pages) {
            super(new CpaCredentialVerificationClient.CpaEndpoint("http://localhost", "test"), "key");
            this.pages = pages;
        }

        @Override
        public List<CredentialPage> listAllCredentialPages() {
            return pages;
        }

        @Override
        public List<String> listModels(String nameOrAuthId) {
            if (!seenQueries.add(nameOrAuthId)) {
                // Same key already queried; only the first call matters for POST-guard assertions.
                return byKey(nameOrAuthId);
            }
            modelQueries++;
            return byKey(nameOrAuthId);
        }

        private List<String> byKey(String key) {
            List<String> byId = modelsById.get(key);
            if (byId != null) {
                return byId;
            }
            List<String> byName = modelsByName.get(key);
            return byName == null ? List.of() : byName;
        }

        @Override
        public VerificationHttpResponse verify(VerifyRequest request) {
            verifyPosts++;
            return new VerificationHttpResponse(200, "{\"verified\":true}");
        }
    }
}
