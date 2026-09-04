package site.kael.cpa.console.admin.model.dto;

import site.kael.cpa.console.core.model.model.LiteLlmModelMetadata;

public record AdminLiteLlmMetadataResponse(String model_id, String provider, String mode, Long max_input_tokens,
                                           Long max_output_tokens, Long max_tokens, String metadata_json,
                                           java.time.Instant synced_at) {
    public static AdminLiteLlmMetadataResponse from(LiteLlmModelMetadata value) {
        return new AdminLiteLlmMetadataResponse(value.modelId(), value.provider(), value.mode(), value.maxInputTokens(),
                value.maxOutputTokens(), value.maxTokens(), value.metadataJson(), value.syncedAt());
    }
}
