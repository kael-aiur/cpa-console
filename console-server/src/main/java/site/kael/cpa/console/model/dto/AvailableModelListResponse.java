package site.kael.cpa.console.model.dto;

import java.util.List;

public record AvailableModelListResponse(List<AvailableModelResponse> models, int total, List<String> tags) {
}
