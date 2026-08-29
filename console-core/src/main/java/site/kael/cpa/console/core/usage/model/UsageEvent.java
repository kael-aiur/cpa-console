package site.kael.cpa.console.core.usage.model;

import java.time.Instant;

public record UsageEvent(
        Long id,
        String eventKey,
        String requestId,
        Instant timestamp,
        String source,
        String provider,
        String endpoint,
        String authType,
        String authIndex,
        String apiGroupKey,
        String apiKeyHash,
        String model,
        String modelAlias,
        String executorType,
        String serviceTier,
        String responseServiceTier,
        String reasoningEffort,
        boolean failed,
        Boolean generate,
        long latencyMs,
        Long ttftMs,
        long inputTokens,
        long outputTokens,
        long reasoningTokens,
        long cachedTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        long totalTokens,
        Long inboxId,
        Instant createdAt,
        Instant updatedAt
) {}
