package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Real-time credential-to-model resolution over the v8 management API (D3.1 / T02).
 * <p>
 * The resolver never accepts local snapshots or cached lists. Each call enumerates every
 * page of {@code GET /v8/management/credentials}, deduplicates by stable (id, index) pair
 * and rejects: missing auth id/index, ambiguous names, duplicate identity pairs, disk-degraded
 * sources and unusable provider snapshots. Model lookup uses only the verified auth id with
 * strict query encoding and rejects ambiguous FileName matches, empty model lists and models
 * that are not exact registered members. Short-lived opaque candidate ids are stable within
 * one resolution result only and never encode real identities.
 */
public final class CpaIdentityResolver {

    /** Whether a candidate can be used as a fixed verification target. */
    public enum Support {
        SUPPORTED, UNSUPPORTED
    }

    /** Why a credential was excluded from the candidate set. */
    public enum UnsupportedReason {
        MISSING_ID, MISSING_INDEX, AMBIGUOUS_NAME, DUPLICATE_IDENTITY,
        DISK_DEGRADED, INVALID_PROVIDER, MODEL_LOOKUP_FAILED
    }

    /** Resolved, model-checked candidate. Real identity fields stay restricted. */
    public record Candidate(
            String candidateId,
            String safeSequence,
            String provider,
            Support support,
            UnsupportedReason unsupportedReason,
            String authId,
            String authIndex,
            List<String> models,
            Map<String, String> modelChoiceIds) {

        public Candidate {
            models = models == null ? List.of() : List.copyOf(models);
            modelChoiceIds = modelChoiceIds == null
                    ? Map.of() : Map.copyOf(modelChoiceIds);
        }
    }

    /** Complete real-time resolution result; identities are restricted to backend memory. */
    public record Resolution(List<Candidate> candidates) {
        public Resolution {
            candidates = List.copyOf(candidates);
        }

        public Candidate byModelChoiceId(String modelChoiceId) {
            for (Candidate candidate : candidates) {
                if (candidate.modelChoiceIds().containsKey(modelChoiceId)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private final CpaCredentialVerificationClient client;
    private final ApiKeyCrypto crypto;
    private final Clock clock;
    private int candidateSequence;

    public CpaIdentityResolver(
            CpaCredentialVerificationClient client, ApiKeyCrypto crypto, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Lists every active credential with full pagination, deduplication and model verification. */
    public Resolution listCandidates() {
        List<CredentialPage> pages = client.listAllCredentialPages();
        if (pages.isEmpty()) {
            return new Resolution(List.of());
        }
        Map<String, MergedCredential> merged = new LinkedHashMap<>();
        for (CredentialPage page : pages) {
            for (CredentialEntry entry : page.entries()) {
                if (entry.disabled() || entry.unavailable() || entry.diskDegraded()) {
                    continue;
                }
                mergeEntry(merged, entry);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (MergedCredential mergedCredential : merged.values()) {
            if (mergedCredential.names.size() > 1) {
                continue;
            }
            Candidate candidate = buildCandidate(mergedCredential);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return new Resolution(candidates);
    }

    /** Re-reads live state and returns the bound target only when id AND index both match. */
    public BoundTarget verifyBoundTarget(
            String instanceKey, String authId, String authIndex, String routeModel) {
        if (authId == null || authId.isBlank() || authIndex == null || authIndex.isBlank()) {
            throw new IdentityResolutionException("IDENTITY_UNSAFE", "Bound auth id and index must not be blank");
        }
        if (routeModel == null || routeModel.isBlank()) {
            throw new IdentityResolutionException("MODEL_INVALID", "Route model must not be blank");
        }
        for (CredentialPage page : client.listAllCredentialPages()) {
            for (CredentialEntry entry : page.entries()) {
                if (entry.disabled() || entry.unavailable()) {
                    continue;
                }
                boolean idMatches = authId.equals(entry.authId());
                boolean indexMatches = authIndex.equals(entry.authIndex());
                if (idMatches && indexMatches) {
                    checkModels(entry, routeModel);
                    return new BoundTarget(instanceKey, entry.authId(), entry.authIndex(), routeModel, safeProvider(entry));
                }
                if (idMatches != indexMatches) {
                    throw new IdentityResolutionException(
                            "IDENTITY_UNSAFE", "Bound auth id and index do not match the same live credential");
                }
            }
        }
        throw new IdentityResolutionException("IDENTITY_MISSING", "Bound credential is not present in the live view");
    }

    /** Bound target confirmed by a live id+index lookup with a non-empty exact route model. */
    public record BoundTarget(
            String instanceKey, String authId, String authIndex, String routeModel, String provider) {
    }

    /** Configuration-level identity failure; callers map it to CONFIG_ERROR, never to UNKNOWN. */
    public static final class IdentityResolutionException extends RuntimeException {
        private final String code;

        public IdentityResolutionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private void mergeEntry(Map<String, MergedCredential> merged, CredentialEntry entry) {
        if (entry.authId() == null || entry.authId().isBlank()) {
            return;
        }
        if (entry.authIndex() == null || entry.authIndex().isBlank()) {
            return;
        }
        String identityKey = entry.authId() + "\n" + entry.authIndex();
        MergedCredential existing = merged.get(identityKey);
        if (existing == null) {
            merged.put(identityKey, MergedCredential.from(entry));
            return;
        }
        if (!existing.provider.equals(safeProvider(entry))) {
            return;
        }
        existing.names.add(entry.name());
    }

    private Candidate buildCandidate(MergedCredential merged) {
        candidateSequence++;
        String candidateId = crypto.hash("monitor-candidate:" + clock.instant() + ":" + candidateSequence);
        String safeSequence = String.valueOf(candidateSequence);
        String provider = merged.provider;
        if (provider.isBlank()) {
            return new Candidate(candidateId, safeSequence, "", Support.UNSUPPORTED,
                    UnsupportedReason.INVALID_PROVIDER, merged.authId, merged.authIndex, List.of(), Map.of());
        }
        if (merged.names.size() > 1) {
            return new Candidate(candidateId, safeSequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.AMBIGUOUS_NAME, merged.authId, merged.authIndex, List.of(), Map.of());
        }
        List<String> models;
        try {
            models = fetchModelsWithAmbiguityCheck(merged);
        } catch (IdentityResolutionException exception) {
            return new Candidate(candidateId, safeSequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.MODEL_LOOKUP_FAILED, merged.authId, merged.authIndex, List.of(), Map.of());
        }
        if (models.isEmpty()) {
            return new Candidate(candidateId, safeSequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.MODEL_LOOKUP_FAILED, merged.authId, merged.authIndex, List.of(), Map.of());
        }
        Map<String, String> choiceIds = new LinkedHashMap<>();
        for (String model : models) {
            choiceIds.put(crypto.hash("model-choice:" + clock.instant() + ":" + candidateSequence + ":" + model), model);
        }
        return new Candidate(candidateId, safeSequence, provider, Support.SUPPORTED, null,
                merged.authId, merged.authIndex, models, choiceIds);
    }

    /** Fetches models and rejects FileName-based ambiguity for the resolved auth id. */
    private List<String> fetchModelsWithAmbiguityCheck(MergedCredential merged) {
        List<String> models = client.listModels(merged.authId);
        String name = merged.names.isEmpty() ? "" : merged.names.iterator().next();
        List<String> byName = name.isBlank() ? List.of() : client.listModels(name);
        if (!byName.isEmpty() && !byName.equals(models)) {
            throw new IdentityResolutionException(
                    "MODEL_AMBIGUOUS", "Model query is ambiguous between id and FileName");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String model : models) {
            if (model != null && !model.isBlank()) {
                unique.add(model);
            }
        }
        return List.copyOf(unique);
    }

    private void checkModels(CredentialEntry entry, String routeModel) {
        String provider = safeProvider(entry);
        if (provider.isBlank()) {
            throw new IdentityResolutionException("IDENTITY_UNSAFE", "Live credential provider snapshot is unusable");
        }
        String name = entry.name() == null ? "" : entry.name();
        List<String> byId = client.listModels(entry.authId());
        List<String> byName = name.isBlank() ? byId : client.listModels(name);
        if (!byName.isEmpty() && !byName.equals(byId)) {
            throw new IdentityResolutionException("MODEL_AMBIGUOUS", "Model query is ambiguous");
        }
        if (byId.isEmpty()) {
            throw new IdentityResolutionException("MODEL_INVALID", "Credential has no registered route models");
        }
        for (String model : byId) {
            if (routeModel.equals(model)) {
                return;
            }
        }
        throw new IdentityResolutionException("MODEL_INVALID", "Route model is not registered for this credential");
    }

    private String safeProvider(CredentialEntry entry) {
        String type = entry.provider() == null ? "" : entry.provider().trim();
        String provider = entry.type() == null ? "" : entry.type().trim();
        return !provider.isBlank() ? provider : type;
    }

    private static final class MergedCredential {
        private final String authId;
        private final String authIndex;
        private final String provider;
        private final Set<String> names = new LinkedHashSet<>();

        private MergedCredential(String authId, String authIndex, String provider) {
            this.authId = authId;
            this.authIndex = authIndex;
            this.provider = provider;
        }

        static MergedCredential from(CredentialEntry entry) {
            MergedCredential created = new MergedCredential(entry.authId(), entry.authIndex(), safeProviderStatic(entry));
            if (entry.name() != null && !entry.name().isBlank()) {
                created.names.add(entry.name());
            }
            return created;
        }

        private static String safeProviderStatic(CredentialEntry entry) {
            String provider = entry.provider() == null ? "" : entry.provider().trim();
            String type = entry.type() == null ? "" : entry.type().trim();
            return !provider.isBlank() ? provider : type;
        }
    }

    /** v8 credential entry restricted to fields needed for identity resolution. */
    public record CredentialEntry(
            String authId, String authIndex, String name, String provider, String type,
            boolean disabled, boolean unavailable, boolean diskDegraded) {
    }

    /** One page of the live credentials list. */
    public record CredentialPage(List<CredentialEntry> entries, boolean hasMore) {
        public CredentialPage {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
