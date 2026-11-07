package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;

/**
 * D7/T10 admin history page, carrying the same signed cursor and snapshot metadata as T09.
 * A changed data snapshot must be refreshed from page one (409 SNAPSHOT_CHANGED), never
 * mixed silently.
 */
public record AdminMonitorRunPageResponse(
        String task_id,
        List<AdminMonitorRunResponse> runs,
        String next_cursor,
        long data_revision,
        long as_of,
        long start,
        long end,
        String view,
        boolean snapshot_changed) {
}
