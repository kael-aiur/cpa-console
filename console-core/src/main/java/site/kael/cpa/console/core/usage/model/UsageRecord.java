package site.kael.cpa.console.core.usage.model;

import java.time.Instant;

public record UsageRecord(long id, String model, long inputTokens, long outputTokens, long cachedTokens,
                          Instant requestTime, long durationMs, int statusCode, boolean success) {}
