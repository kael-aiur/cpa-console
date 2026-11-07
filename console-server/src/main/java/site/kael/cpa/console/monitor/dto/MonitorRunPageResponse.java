package site.kael.cpa.console.monitor.dto;

import java.util.List;

/**
 * D7/T11 public history page carrying the signed cursor and snapshot facts so the client
 * can keep window/as_of/view on card-to-history navigation and refresh on 409.
 */
public record MonitorRunPageResponse(
        String safe_name,
        String safe_model_display,
        List<MonitorRunResponse> runs,
        String next_cursor,
        long data_revision,
        long as_of,
        long start,
        long end,
        String view,
        CoverageResponse coverage,
        boolean snapshot_changed) {
}
