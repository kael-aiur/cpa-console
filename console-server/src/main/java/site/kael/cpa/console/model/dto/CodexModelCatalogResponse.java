package site.kael.cpa.console.model.dto;

import site.kael.cpa.console.core.model.model.AvailableModel;
import site.kael.cpa.console.core.model.model.LiteLlmModelMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CodexModelCatalogResponse(List<Map<String, Object>> models) {
    private static final int FALLBACK_CONTEXT_WINDOW = 1_000_000;
    private static final List<String> INPUT_MODALITIES = List.of("text", "image");
    private static final List<Map<String, String>> REASONING_LEVELS = List.of(
            reasoningLevel("low", "轻度推理"), reasoningLevel("medium", "平衡速度与深度"), reasoningLevel("high", "深度推理"));

    public static CodexModelCatalogResponse from(List<AvailableModel> availableModels, java.util.function.Function<String, LiteLlmModelMetadata> metadataLookup) {
        return new CodexModelCatalogResponse(availableModels.stream().map(model -> model(model, metadataLookup.apply(model.litellmModelId()))).toList());
    }

    public static CodexModelCatalogResponse from(List<AvailableModel> availableModels) {
        return from(availableModels, ignored -> null);
    }

    private static Map<String, Object> model(AvailableModel availableModel, LiteLlmModelMetadata metadata) {
        String name = availableModel.name();
        int contextWindow = metadata == null || metadata.contextWindow() == null ? FALLBACK_CONTEXT_WINDOW : Math.toIntExact(metadata.contextWindow());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("shell_type", "default"); model.put("visibility", "list"); model.put("supported_in_api", true); model.put("priority", 0);
        model.put("availability_nux", null); model.put("upgrade", null); model.put("base_instructions", ""); model.put("supports_reasoning_summary_parameter", true);
        model.put("support_verbosity", false); model.put("default_verbosity", null); model.put("apply_patch_tool_type", null); model.put("truncation_policy", Map.of("mode", "tokens", "limit", 10_000));
        model.put("experimental_supported_tools", List.of()); model.put("default_reasoning_summary", "none"); model.put("slug", name); model.put("display_name", name); model.put("description", name);
        model.put("context_window", contextWindow); model.put("max_context_window", contextWindow); model.put("effective_context_window_percent", 95); model.put("input_modalities", INPUT_MODALITIES);
        model.put("default_reasoning_level", "medium"); model.put("supported_reasoning_levels", REASONING_LEVELS); model.put("supports_parallel_tool_calls", true); model.put("supports_image_detail_original", false);
        model.put("supports_reasoning_summaries", false);
        return model;
    }
    private static Map<String, String> reasoningLevel(String effort, String description) { Map<String, String> level = new LinkedHashMap<>(); level.put("effort", effort); level.put("description", description); return level; }
}
