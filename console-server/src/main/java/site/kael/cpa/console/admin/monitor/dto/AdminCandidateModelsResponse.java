package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;
import java.util.Map;

/** D7/T10 model list for one still-valid candidate; expired candidates are 404, never guessed. */
public record AdminCandidateModelsResponse(
        String candidate_id,
        List<String> models,
        Map<String, String> model_choices) {
}
