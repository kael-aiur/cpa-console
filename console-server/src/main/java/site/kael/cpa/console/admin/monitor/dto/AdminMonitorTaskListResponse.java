package site.kael.cpa.console.admin.monitor.dto;

import java.util.List;

/** D7/T10 full admin task list; disabled and blocked tasks stay visible. */
public record AdminMonitorTaskListResponse(List<AdminMonitorTaskResponse> tasks, int total) {
}
