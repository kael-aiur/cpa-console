package site.kael.cpa.console.admin.monitor.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateModelsResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorRunPageResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskCreateRequest;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskUpdateRequest;
import site.kael.cpa.console.admin.monitor.service.AdminMonitorService;

import java.util.Map;

/**
 * T10 admin monitoring endpoints (all under /admin/monitor, D7 snake_case). The global
 * SecurityConfig already requires an authenticated ADMIN role for /admin/** and leaves CSRF
 * enabled, so POST/PATCH are never exempt from the CSRF check; this controller adds no
 * permission logic of its own.
 */
@RestController
@RequestMapping("/admin/monitor")
public class AdminMonitorController {

    private final AdminMonitorService service;
    private final ObjectMapper objectMapper;

    public AdminMonitorController(AdminMonitorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/credentials")
    public AdminCandidateListResponse credentials() {
        return service.candidates();
    }

    @GetMapping("/credentials/{candidateId}/models")
    public AdminCandidateModelsResponse candidateModels(@PathVariable("candidateId") String candidateId) {
        return service.candidateModels(candidateId);
    }

    @GetMapping("/tasks")
    public AdminMonitorTaskListResponse tasks() {
        return service.tasks();
    }

    @PostMapping("/tasks")
    public AdminMonitorTaskResponse createTask(@RequestBody Map<String, Object> body) {
        AdminMonitorTaskCreateRequest request = binding(body, AdminMonitorTaskCreateRequest.class);
        return service.createTask(request);
    }

    @PatchMapping("/tasks/{taskId}")
    public AdminMonitorTaskResponse updateTask(
            @PathVariable("taskId") String taskId,
            @RequestBody Map<String, Object> body) {
        AdminMonitorTaskUpdateRequest request = binding(body, AdminMonitorTaskUpdateRequest.class);
        return service.updateTask(taskId, request);
    }

    @GetMapping("/tasks/{taskId}/runs")
    public AdminMonitorRunPageResponse runs(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        service.requireTask(taskId);
        return service.runs(taskId, start, end, view, cursor, limit);
    }

    /** Parses the raw JSON object once, carrying the original keys for strict field checks. */
    private <T> T binding(Map<String, Object> body, Class<T> type) {
        if (body == null || body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_BODY");
        }
        try {
            return objectMapper.convertValue(body, type);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MALFORMED_BODY");
        }
    }
}
