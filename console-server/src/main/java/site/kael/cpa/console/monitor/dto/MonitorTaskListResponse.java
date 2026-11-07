package site.kael.cpa.console.monitor.dto;

import java.util.List;

/** D7/T11 card list: includes disabled tasks; a stable snapshot for the whole request. */
public record MonitorTaskListResponse(List<MonitorTaskCardResponse> tasks, int total, long as_of, long data_revision) {
}
