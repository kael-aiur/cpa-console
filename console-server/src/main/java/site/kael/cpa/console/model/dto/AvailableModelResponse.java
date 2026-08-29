package site.kael.cpa.console.model.dto;

import site.kael.cpa.console.core.model.model.AvailableModel;

import java.util.List;

public record AvailableModelResponse(String name, List<String> tags) {
    public static AvailableModelResponse from(AvailableModel model) {
        return new AvailableModelResponse(model.name(), model.tags());
    }
}
