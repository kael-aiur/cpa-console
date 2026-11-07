package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;
import java.util.Map;

/** D7/T10 candidate list: every item is opaque; unsupported candidates are listed but not selectable. */
public record AdminCandidateListResponse(
        List<AdminCandidateResponse> candidates,
        Map<String, Map<String, String>> model_choices) {
}
