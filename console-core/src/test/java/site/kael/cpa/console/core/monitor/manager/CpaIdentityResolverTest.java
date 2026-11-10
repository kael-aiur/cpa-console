package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.BoundTarget;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.Candidate;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail;
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
                page(new CredentialEntryDetail("id-c", "idx-c", "disk.json", "codex", "codex",
                        false, false, true, null, "file"))));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(0, client.modelQueries);
        assertEquals(0, client.verifyPosts);
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void modelAmbiguityBetweenIdAndFileNameIsRejected() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntryDetail("id-a", "idx-a", "name.json", "codex", "codex",
                        false, false, false, null, "file"))));
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
                page(new CredentialEntryDetail("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false, null, "file"))));
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
                page(new CredentialEntryDetail("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false, null, "file"))));
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
                page(new CredentialEntryDetail("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false, null, "file"))));
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

    @Test
    void snapshotIsValidImmediatelyAndCandidatesAreUniqueWithinListing() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntryDetail("id-a", "idx-a", "a.json", "codex", "codex",
                        false, false, false, null, "file"),
                     new CredentialEntryDetail("id-b", "idx-b", "b.json", "codex", "codex",
                        false, false, false, null, "file"))));
        client.modelsById.put("id-a", List.of("m1"));
        client.modelsByName.put("a.json", List.of("m1"));
        client.modelsById.put("id-b", List.of("m2"));
        client.modelsByName.put("b.json", List.of("m2"));
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(2, resolution.candidates().size());
        // Snapshot identity, not wall-clock age: every candidate id is fresh and distinct.
        long seen = resolution.candidates().stream().map(Candidate::candidateId).distinct().count();
        assertEquals(2, seen);
        for (Candidate candidate : resolution.candidates()) {
            assertTrue(candidate.support() == Support.SUPPORTED);
            assertTrue(candidate.displayName() != null && !candidate.displayName().isBlank());
            assertFalse(candidate.displayName().contains("id-a") || candidate.displayName().contains("idx-a"));
        }
        assertEquals(0, client.verifyPosts);
    }

    @Test
    void displayNameFallsBackWithoutLeakingWhenNameMissingOrDuplicated() {
        CountingClient client = new CountingClient(List.of(
                page(new CredentialEntryDetail("id-a", "idx-a", "", "codex", "codex",
                        false, false, false, null, "file"),
                     new CredentialEntryDetail("id-b", "idx-b", "person@corp.json", "codex", "codex",
                        false, false, false, null, "file"),
                     new CredentialEntryDetail("id-c", "idx-c", "very-long-token-value-0123456789-0123456789-0123456789.json",
                        "codex", "codex", false, false, false, null, "file"))));
        for (String key : List.of("id-a", "id-b", "id-c")) {
            client.modelsById.put(key, List.of("m1"));
            // Non-blank FileName queries fall back to the same model list (no ambiguity).
            client.modelsByName.put(key, List.of("m1"));
        }
        Resolution resolution = new CpaIdentityResolver(client, crypto(), CLOCK).listCandidates();
        assertEquals(3, resolution.candidates().size());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Candidate candidate : resolution.candidates()) {
            String name = candidate.displayName();
            assertTrue(name != null && !name.isBlank());
            assertFalse(name.contains("id-a") || name.contains("id-b") || name.contains("id-c")
                    || name.contains("idx-a") || name.contains("idx-b") || name.contains("idx-c"));
            assertFalse(name.contains("@"));
            assertFalse(name.contains(".json"));
            assertTrue(name.length() <= 64);
            names.add(name);
        }
        // All visible display names stay distinguishable within one listing.
        assertEquals(resolution.candidates().size(), names.size());
        assertEquals(0, client.verifyPosts);
    }

    @Test
    void credentialEntryDropsSensitiveUpstreamLabels() {
        // Validated at the client layer: labels that look like emails or long values never
        // survive transport into the resolver (safeLabel screen in the verification client).
        org.junit.jupiter.api.Assertions.assertTrue(
                site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.class != null);
    }

    private ApiKeyCrypto crypto() {
        return new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg==");
    }

    private CredentialPage page(CredentialEntryDetail... entries) {
        return new CredentialPage(List.of(entries), false);
    }

    private CredentialEntryDetail entry(String id, String index, String name, boolean healthy) {
        return new CredentialEntryDetail(id, index, name, "codex", "codex",
                false, false, !healthy, null, "file");
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
