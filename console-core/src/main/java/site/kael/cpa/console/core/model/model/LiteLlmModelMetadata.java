package site.kael.cpa.console.core.model.model;

import java.time.Instant;

public record LiteLlmModelMetadata(
        String modelId,
        String provider,
        String mode,
        Long maxInputTokens,
        Long maxOutputTokens,
        Long maxTokens,
        String metadataJson,
        Instant syncedAt
) {
    public Long contextWindow() {
        return maxInputTokens != null ? maxInputTokens : maxTokens;
    }
}
