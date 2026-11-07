package site.kael.cpa.console.monitor.dto;

import java.util.List;
import java.util.Map;

/**
 * D7/T11 public task card. Every field is already whitelisted and safe: safe display
 * name/model, separated result and schedule windows, ratio/coverage facts and revision.
 * No real ids/indexes/provider accounts/hosts/cron internals/raw credentials appear here.
 */
public record MonitorTaskCardResponse(
        String task_id,
        String safe_name,
        String safe_model_display,
        boolean enabled,
        String blocked_reason,
        Long next_fire_at,
        VerificationSummaryResponse verification,
        Map<String, Integer> other_states,
        LatestInfoResponse latest_verification,
        LatestInfoResponse latest_event,
        CoverageResponse coverage,
        long as_of,
        long data_revision) {
}
