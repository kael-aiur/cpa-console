package site.kael.cpa.console.monitor.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.monitor.dto.MonitorRunPageResponse;
import site.kael.cpa.console.monitor.dto.MonitorTaskListResponse;
import site.kael.cpa.console.monitor.service.MonitorService;

/**
 * T11 public monitoring endpoints. Both are already authenticated by SecurityConfig
 * (any authenticated user, including USER and ADMIN), remain GET-only and never trigger a
 * verify POST. The SecurityConfig also leaves CSRF enabled, so write attempts would fail.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService service;

    public MonitorController(MonitorService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public MonitorTaskListResponse tasks(
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end) {
        return service.taskCards(start, end);
    }

    @GetMapping("/tasks/{taskId}/runs")
    public MonitorRunPageResponse runs(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return service.runs(taskId, start, end, view, cursor, limit);
    }
}
