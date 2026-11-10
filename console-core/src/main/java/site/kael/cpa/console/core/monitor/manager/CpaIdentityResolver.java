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
public class CpaIdentityResolver {

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
            String sequence,
            String provider,
            Support support,
            UnsupportedReason unsupportedReason,
            String displayName,
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
        int sequence = 0;
        for (CredentialPage page : pages) {
            for (CredentialEntryDetail entry : page.entries()) {
                if (entry.disabled() || entry.unavailable() || entry.diskDegraded()) {
                    continue;
                }
                sequence++;
                mergeEntry(merged, entry, sequence);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        java.util.Set<String> usedNames = new LinkedHashSet<>();
        for (MergedCredential mergedCredential : merged.values()) {
            Candidate candidate = buildCandidate(mergedCredential, usedNames);
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
            for (CredentialEntryDetail entry : page.entries()) {
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

    private void mergeEntry(
            Map<String, MergedCredential> merged, CredentialEntryDetail entry, int sequence) {
        if (entry.authId() == null || entry.authId().isBlank()) {
            return;
        }
        if (entry.authIndex() == null || entry.authIndex().isBlank()) {
            return;
        }
        String identityKey = entry.authId() + "\n" + entry.authIndex();
        if (!merged.containsKey(identityKey)) {
            merged.put(identityKey, MergedCredential.from(entry, sequence));
        }
    }

    /**
     * Builds one candidate per merged credential. The display name is a safe, stable,
     * unique-within-listing label; safe-ambiguous identities stay visible but unsupported
     * and keep their per-entry model ambiguity check.
     */
    private Candidate buildCandidate(MergedCredential merged, java.util.Set<String> usedNames) {
        candidateSequence++;
        String candidateId = crypto.hash("monitor-candidate:" + clock.instant() + ":" + candidateSequence);
        String sequence = String.valueOf(candidateSequence);
        String provider = merged.provider;
        String displayName = safeDisplayName(merged, usedNames);
        if (provider.isBlank()) {
            return new Candidate(candidateId, sequence, "", Support.UNSUPPORTED,
                    UnsupportedReason.INVALID_PROVIDER, displayName, merged.authId, merged.authIndex,
                    List.of(), Map.of());
        }
        if (merged.safeAmbiguous()) {
            return new Candidate(candidateId, sequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.AMBIGUOUS_NAME, displayName, merged.authId, merged.authIndex,
                    List.of(), Map.of());
        }
        CredentialEntryDetail entry = merged.primaryEntry();
        List<String> models;
        try {
            models = fetchModelsWithAmbiguityCheck(entry);
        } catch (IdentityResolutionException exception) {
            return new Candidate(candidateId, sequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.MODEL_LOOKUP_FAILED, displayName, merged.authId, merged.authIndex,
                    List.of(), Map.of());
        }
        if (models.isEmpty()) {
            return new Candidate(candidateId, sequence, provider, Support.UNSUPPORTED,
                    UnsupportedReason.MODEL_LOOKUP_FAILED, displayName, merged.authId, merged.authIndex,
                    List.of(), Map.of());
        }
        if (models.size() == 1) {
            String model = models.get(0);
            String choiceId = crypto.hash("model-choice:" + clock.instant() + ":" + candidateSequence + ":" + model);
            return new Candidate(candidateId, sequence, provider, Support.SUPPORTED, null,
                    displayName, merged.authId, merged.authIndex, models, Map.of(choiceId, model));
        }
        Map<String, String> choiceIds = new LinkedHashMap<>();
        for (String model : models) {
            choiceIds.put(crypto.hash("model-choice:" + clock.instant() + ":" + candidateSequence
                    + ":" + model), model);
        }
        return new Candidate(candidateId, sequence, provider, Support.SUPPORTED, null,
                displayName, merged.authId, merged.authIndex, models, choiceIds);
    }

    /** Fetches models and rejects FileName-based ambiguity for the resolved auth id. */
    private List<String> fetchModelsWithAmbiguityCheck(CredentialEntryDetail entry) {
        List<String> models = client.listModels(entry.authId());
        String name = entry.name() == null ? "" : entry.name();
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

    /**
     * Produces the admin-facing display name: a safe upstream label when available and
     * unique, otherwise a bounded provider + sequence fallback. It never embeds the auth
     * id/index, a FileName, a path, an email or any key-like material.
     */
    private String safeDisplayName(MergedCredential merged, java.util.Set<String> usedNames) {
        String safeName = safeDisplayNameCandidate(merged.primaryEntry().name());
        if (!safeName.isBlank() && usedNames.add(safeName)) {
            return safeName;
        }
        String base = safeName.isBlank() ? safeProviderText(merged.provider) : safeName;
        String prefix = base.isBlank() ? "unknown" : base;
        String unique = prefix + " #" + merged.sequence;
        int suffix = merged.sequence;
        while (!usedNames.add(unique)) {
            suffix++;
            unique = prefix + " #" + suffix;
        }
        return unique;
    }

    /** A safe upstream name: short, single-line and never email-like or secret-like. */
    private static String safeDisplayNameCandidate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > 64
                || trimmed.indexOf('@') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return "";
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json") || lower.contains(".json")
                || lower.startsWith("/") || lower.contains("://")
                || lower.contains("api-key") || lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("credential") || lower.contains("auth_token")) {
            return "";
        }
        return trimmed;
    }

    private static String safeProviderText(String provider) {
        if (provider == null) {
            return "";
        }
        String trimmed = provider.trim();
        if (trimmed.isBlank() || trimmed.length() > 64
                || trimmed.indexOf('@') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return "";
        }
        return trimmed;
    }

    private void checkModels(CredentialEntryDetail entry, String routeModel) {
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

    private static String safeProvider(CredentialEntryDetail entry) {
        String type = entry.provider() == null ? "" : entry.provider().trim();
        String provider = entry.type() == null ? "" : entry.type().trim();
        return !provider.isBlank() ? provider : type;
    }

    /** One credential identity plus restricted details and its position in this live listing. */
    private static final class MergedCredential {
        private final String authId;
        private final String authIndex;
        private final String provider;
        private final CredentialEntryDetail primaryEntry;
        private final int sequence;

        private MergedCredential(
                String authId, String authIndex, String provider,
                CredentialEntryDetail primaryEntry, int sequence) {
            this.authId = authId;
            this.authIndex = authIndex;
            this.provider = provider;
            this.primaryEntry = primaryEntry;
            this.sequence = sequence;
        }

        CredentialEntryDetail primaryEntry() {
            return primaryEntry;
        }

        boolean safeAmbiguous() {
            return sequence == 1 && !"file".equals(primaryEntry.source());
        }

        static MergedCredential from(CredentialEntryDetail entry, int sequence) {
            return new MergedCredential(
                    entry.authId(), entry.authIndex(), safeProviderStatic(entry), entry, sequence);
        }

        private static String safeProviderStatic(CredentialEntryDetail entry) {
            String provider = entry.provider() == null ? "" : entry.provider().trim();
            String type = entry.type() == null ? "" : entry.type().trim();
            return !provider.isBlank() ? provider : type;
        }
    }

    /**
     * Restricted v8 entry detail. Only pre-screened non-sensitive fields are retained for
     * display logic; email/path/key-like values are never carried into this resolver.
     */
    public record CredentialEntryDetail(
            String authId, String authIndex, String name, String provider, String type,
            boolean disabled, boolean unavailable, boolean diskDegraded, String safeLabel,
            String source) {
    }

    /** One page of the live credentials list with restricted per-entry detail. */
    public record CredentialPage(List<CredentialEntryDetail> entries, boolean hasMore) {
        public CredentialPage {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
