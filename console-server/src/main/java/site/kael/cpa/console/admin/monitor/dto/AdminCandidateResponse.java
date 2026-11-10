package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;

/**
 * D7/T10 candidate list item. The candidate id is an opaque short-lived token with no
 * relationship to any real id/index/name; `display_name` is a bounded safe label or
 * provider/sequence fallback, never an email/path/key/raw internal id. Unsupported
 * candidates carry no model choices.
 */
public record AdminCandidateResponse(
        String candidate_id,
        String sequence,
        String display_name,
        String provider,
        boolean supported,
        String unsupported_reason,
        List<String> models) {

    /** Narrow transport view built from the T02 resolution result; identities stay behind. */
    public static AdminCandidateResponse from(
            String candidateId,
            String sequence,
            String displayName,
            String provider,
            boolean supported,
            String unsupportedReason,
            List<String> models) {
        List<String> visibleModels = supported ? List.copyOf(models) : List.of();
        return new AdminCandidateResponse(
                candidateId,
                sequence,
                safeDisplayName(displayName, provider, sequence),
                provider,
                supported,
                unsupportedReason,
                visibleModels);
    }

    /** Final transport guard: bounded, single-line and never an email or provider repeat. */
    private static String safeDisplayName(String displayName, String provider, String sequence) {
        String safe = displayName == null ? "" : displayName.trim();
        String safeProvider = provider == null ? "" : provider.trim();
        if (safe.isBlank() || safe.length() > 64
                || safe.indexOf('@') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            safe = safeProvider.isBlank() ? "unknown" : safeProvider;
        }
        if (safe.equals(safeProvider)) {
            return safe + " #" + sequence;
        }
        return safe;
    }
}
