package site.kael.cpa.console.monitor.dto;

/**
 * D7/T11 public history row: safe display fields, result/schedule separation and the
 * sample flag only. No run-scoped identity, provider account, host or diagnostic text.
 */
public record MonitorRunResponse(
        String state,
        String dispatch_status,
        Long started_at,
        Long finished_at,
        Long duration_ms,
        String reason_code,
        long event_at,
        boolean included_in_sample) {
}
