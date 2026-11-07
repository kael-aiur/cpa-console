package site.kael.cpa.console.admin.monitor.dto;

/**
 * D7/T10 admin task view: the task id is a random UUID generated at creation and the safe
 * display fields are the already-validated public names. The instance key is the durable
 * logical binding reference the admin UI may show for the fixed target; real ids/indexes,
 * endpoint URLs and cipher material never appear here.
 */
public record AdminMonitorTaskResponse(
        String task_id,
        String safe_name,
        String safe_model_display,
        String instance_key,
        String provider,
        boolean enabled,
        String blocked_reason,
        long schedule_revision,
        long row_version,
        String cron_expression,
        String zone_id,
        Long next_fire_at,
        long created_at,
        long updated_at) {
}
