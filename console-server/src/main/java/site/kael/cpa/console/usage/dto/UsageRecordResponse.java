package site.kael.cpa.console.usage.dto;

import site.kael.cpa.console.core.usage.model.UsageRecord;
import java.time.Instant;

public record UsageRecordResponse(long id, String model, long input_tokens, long output_tokens, long cached_tokens,
                                  Instant request_time, long duration_ms, int status_code, boolean success) {
    public static UsageRecordResponse from(UsageRecord value) {
        return new UsageRecordResponse(value.id(), value.model(), value.inputTokens(), value.outputTokens(), value.cachedTokens(), value.requestTime(), value.durationMs(), value.statusCode(), value.success());
    }
}
