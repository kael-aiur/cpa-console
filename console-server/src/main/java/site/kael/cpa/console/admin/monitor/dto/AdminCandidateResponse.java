package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;

/**
 * D7/T10 candidate list item. The candidate id is an opaque short-lived token with no
 * relationship to any real id/index/name; unsupported candidates carry no model choices.
 */
public record AdminCandidateResponse(
        String candidate_id,
        String sequence,
        String provider,
        boolean supported,
        String unsupported_reason,
        List<String> models) {

    /** Narrow transport view built from the T02 resolution result; identities stay behind. */
    public static AdminCandidateResponse from(
            String candidateId,
            String sequence,
            String provider,
            boolean supported,
            String unsupportedReason,
            List<String> models) {
        List<String> visibleModels = supported ? List.copyOf(models) : List.of();
        return new AdminCandidateResponse(
                candidateId,
                sequence,
                provider,
                supported,
                unsupportedReason,
                visibleModels);
    }
}
