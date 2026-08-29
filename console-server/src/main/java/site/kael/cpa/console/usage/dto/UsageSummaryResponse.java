package site.kael.cpa.console.usage.dto;

import site.kael.cpa.console.core.usage.model.UsageSummary;
import java.util.Map;

public record UsageSummaryResponse(long total_requests, long total_tokens, long average_duration_ms,
                                   Map<String, Long> model_token_distribution,
                                   Map<String, Long> model_request_distribution,
                                   Map<String, Long> user_token_distribution,
                                   Map<String, Long> user_request_distribution) {
    public static UsageSummaryResponse from(UsageSummary value) {
        return new UsageSummaryResponse(value.totalRequests(), value.totalTokens(), value.averageDurationMs(), value.modelTokenDistribution(), value.modelRequestDistribution(), value.userTokenDistribution(), value.userRequestDistribution());
    }
}
