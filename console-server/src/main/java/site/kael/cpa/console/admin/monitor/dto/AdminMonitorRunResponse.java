package site.kael.cpa.console.admin.monitor.dto;

/**
 * D7/T10 admin history row. The administrative summary is the already-safe local template
 * stored by T06, bounded to 512 characters by schema; the HTTP status is the only verbatim
 * upstream fact exposed (an integer). Raw request/response bodies and identities never enter.
 */
public record AdminMonitorRunResponse(
        String run_id,
        String task_id,
        String safe_name,
        String safe_model_display,
        String phase,
        String state,
        String dispatch_status,
        Long scheduled_at,
        Long started_at,
        Long finished_at,
        Long duration_ms,
        String reason_code,
        String safe_admin_summary,
        Integer cpa_http_status,
        long event_at,
        boolean included_in_sample) {
}
