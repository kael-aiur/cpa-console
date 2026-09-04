package site.kael.cpa.console.admin.model.dto;
import site.kael.cpa.console.core.model.model.AvailableModel;
public record AdminAvailableModelResponse(long id, String name, String owned_by, String litellm_model_id, java.util.List<String> tags) {
    public static AdminAvailableModelResponse from(AvailableModel value) { return new AdminAvailableModelResponse(value.id(), value.name(), value.ownedBy(), value.litellmModelId(), value.tags()); }
}
