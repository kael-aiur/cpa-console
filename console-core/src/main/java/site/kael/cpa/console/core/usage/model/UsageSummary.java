package site.kael.cpa.console.core.usage.model;

import java.util.Map;

public record UsageSummary(long totalRequests, long totalTokens, long averageDurationMs,
                           Map<String, Long> modelTokenDistribution,
                           Map<String, Long> modelRequestDistribution) {}
