package site.kael.cpa.console.monitor.dto;

import java.math.BigDecimal;

/** D7/T11 strict-sample result window: S/F/U counts plus ratio (null when zero denominator). */
public record VerificationSummaryResponse(
        int success_count,
        int failed_count,
        int unknown_count,
        int sample_count,
        BigDecimal ratio) {
}
