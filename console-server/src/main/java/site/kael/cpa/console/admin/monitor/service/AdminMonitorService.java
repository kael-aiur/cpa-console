package site.kael.cpa.console.admin.monitor.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateModelsResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorRunPageResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorRunResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskCreateRequest;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskUpdateRequest;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver;
import site.kael.cpa.console.core.monitor.manager.QueryManager;
import site.kael.cpa.console.core.monitor.manager.TaskManager;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.QueryWindow;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * T10 admin monitoring orchestration: live candidates, task CRUD and admin history over the
 * T09 QueryManager. Every read path issues GETs only — never a verify POST — and every
 * response passes through the D7 whitelisted admin DTOs. The service stays in the server
 * layer and never calls CPA directly; candidate identities stay inside backend memory.
 */
@Service
public class AdminMonitorService {

    /** Maximum number of simultaneously cached opaque candidates per listing. */
    static final int MAX_CANDIDATES = 200;

    private final CpaIdentityResolver identityResolver;
    private final TaskManager taskManager;
    private final QueryManager queryManager;
    private final ApiKeyCrypto crypto;
    private final Clock clock;

    /** Short-lived opaque candidate cache; replaced wholesale on every fresh listing. */
    private volatile List<CachedCandidate> cachedCandidates = List.of();

    private record CachedCandidate(
            CpaIdentityResolver.Candidate candidate,
            Map<String, String> modelChoiceIds,
            long createdAtMs) {
    }

    public AdminMonitorService(
            CpaIdentityResolver identityResolver,
            TaskManager taskManager,
            QueryManager queryManager,
            ApiKeyCrypto crypto,
            Clock clock) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.queryManager = Objects.requireNonNull(queryManager, "queryManager");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** D7 GET credentials: full live re-resolution; candidates from earlier listings expire. */
    public AdminCandidateListResponse candidates() {
        CpaIdentityResolver.Resolution resolution;
        try {
            resolution = identityResolver.listCandidates();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CPA_UNAVAILABLE");
        }
        List<CachedCandidate> fresh = new ArrayList<>();
        Map<String, Map<String, String>> choices = new LinkedHashMap<>();
        List<AdminCandidateResponse> visible = new ArrayList<>();
        for (CpaIdentityResolver.Candidate candidate : resolution.candidates()) {
            if (fresh.size() >= MAX_CANDIDATES) {
                break;
            }
            boolean supported = candidate.support() == CpaIdentityResolver.Support.SUPPORTED;
            Map<String, String> choiceIds = supported
                    ? candidate.modelChoiceIds() : Map.of();
            fresh.add(new CachedCandidate(candidate, choiceIds, clock.instant().toEpochMilli()));
            choices.put(candidate.candidateId(), choiceIds);
            visible.add(AdminCandidateResponse.from(
                    candidate.candidateId(),
                    candidate.safeSequence(),
                    safeProvider(candidate),
                    supported,
                    candidate.unsupportedReason() == null ? null : candidate.unsupportedReason().name(),
                    candidate.models()));
        }
        this.cachedCandidates = List.copyOf(fresh);
        return new AdminCandidateListResponse(List.copyOf(visible), Map.copyOf(choices));
    }

    /** D7 GET credentials/{candidate_id}/models: only a still-valid supported candidate answers. */
    public AdminCandidateModelsResponse candidateModels(String candidateId) {
        CachedCandidate cached = findCandidate(candidateId);
        if (cached == null
                || cached.candidate().support() != CpaIdentityResolver.Support.SUPPORTED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND");
        }
        return new AdminCandidateModelsResponse(
                candidateId,
                List.copyOf(cached.candidate().models()),
                Map.copyOf(cached.modelChoiceIds()));
    }

    /** D7 GET tasks: the complete admin task list including disabled and blocked rows. */
    public AdminMonitorTaskListResponse tasks() {
        List<MonitorTask> tasks;
        try {
            tasks = taskManager.listAll();
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        List<AdminMonitorTaskResponse> visible = new ArrayList<>(tasks.size());
        for (MonitorTask task : tasks) {
            visible.add(toTaskResponse(task));
        }
        return new AdminMonitorTaskListResponse(List.copyOf(visible), visible.size());
    }

    /**
     * D7 POST tasks: creation defaults to disabled (only false or omitted accepted); the
     * immutable target is fixed from the still-valid chosen model choice and no verify
     * POST is ever issued.
     */
    public AdminMonitorTaskResponse createTask(AdminMonitorTaskCreateRequest request) {
        validateCreate(request);
        CachedCandidate cached = findCandidate(request.instance_key());
        if (cached == null || cached.candidate().support() != CpaIdentityResolver.Support.SUPPORTED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND");
        }
        String routeModel = cached.modelChoiceIds().get(request.model_choice_id());
        if (routeModel == null || routeModel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MODEL_CHOICE_NOT_FOUND");
        }
        TaskManager.CreateRequest create = new TaskManager.CreateRequest(
                request.safe_name().trim(),
                request.cron_expression().trim(),
                request.zone_id().trim(),
                cached.candidate().authId(),
                cached.candidate().authIndex(),
                routeModel,
                safeModelDisplay(cached.candidate(), routeModel),
                safeProvider(cached.candidate()),
                null);
        MonitorTask created;
        try {
            created = taskManager.create(create);
        } catch (TaskManager.TaskManagerException exception) {
            throw mapTaskManagerException(exception);
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        // The candidate set is spent once used; the next listing re-resolves live state.
        this.cachedCandidates = List.of();
        return toTaskResponse(created);
    }

    /**
     * D7 PATCH tasks/{task_id}: expected_revision required, unknown/immutable fields and
     * empty bodies are rejected with local 400 codes; a stale revision is a local 409 that
     * overwrites nothing.
     */
    public AdminMonitorTaskResponse updateTask(String taskId, AdminMonitorTaskUpdateRequest request) {
        validateUpdate(request);
        TaskManager.UpdateRequest update = new TaskManager.UpdateRequest(
                request.expected_revision(),
                request.safe_name() == null ? null : request.safe_name().trim(),
                request.cron_expression() == null ? null : request.cron_expression().trim(),
                request.zone_id() == null ? null : request.zone_id().trim(),
                request.enabled());
        MonitorTask updated;
        try {
            updated = taskManager.update(taskId, update);
        } catch (TaskManager.TaskManagerException exception) {
            throw mapTaskManagerException(exception);
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        return toTaskResponse(updated);
    }

    /** D7 GET tasks/{task_id}/runs: the signed-cursor admin history page over T09 evidence. */
    public AdminMonitorRunPageResponse runs(
            String taskId,
            String startIso,
            String endIso,
            String view,
            String cursor,
            Integer limit) {
        QueryWindow window;
        try {
            window = startIso != null && endIso != null
                    ? QueryWindow.ofCustom(startIso, endIso)
                    : QueryWindow.defaultWindow(clock.instant().toEpochMilli());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW");
        }
        QueryManager.View resolvedView = "events".equalsIgnoreCase(view)
                ? QueryManager.View.EVENTS : QueryManager.View.VERIFICATION;
        QueryManager.EventPage page;
        try {
            page = queryManager.eventsPage(taskId, window, resolvedView, cursor, limit,
                    clock.instant().toEpochMilli());
        } catch (QueryManager.SnapshotChangedException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SNAPSHOT_CHANGED");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        MonitorTask task;
        try {
            task = taskManager.find(taskId).orElse(null);
        } catch (SQLException exception) {
            task = null;
        }
        String safeName = task != null ? task.safeName() : "";
        String safeModel = task != null ? task.safeModelDisplay() : "";
        List<AdminMonitorRunResponse> runs = new ArrayList<>(page.items().size());
        for (QueryManager.EventItem item : page.items()) {
            runs.add(new AdminMonitorRunResponse(
                    item.runId(),
                    item.taskId(),
                    safeName,
                    safeModel,
                    item.startedAt() != null ? "TERMINAL_OR_DISPATCHED" : "CLAIMED",
                    item.state(),
                    item.dispatchStatus(),
                    null,
                    item.startedAt(),
                    item.finishedAt(),
                    null,
                    item.reasonCode(),
                    null,
                    null,
                    item.eventAt(),
                    item.includedInSample()));
        }
        return new AdminMonitorRunPageResponse(
                taskId,
                List.copyOf(runs),
                page.nextCursor(),
                page.dataRevision() == null ? 0 : page.dataRevision().value(),
                page.asOfMs(),
                page.window().startMs(),
                page.window().endMs(),
                resolvedView.name().toLowerCase(Locale.ROOT),
                page.snapshotChanged());
    }

    /** 404 for unknown task ids with a fixed local code and no parameter echo. */
    public MonitorTask requireTask(String taskId) {
        try {
            return taskManager.find(taskId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
    }

    private CachedCandidate findCandidate(String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            return null;
        }
        for (CachedCandidate cached : cachedCandidates) {
            if (candidateId.equals(cached.candidate().candidateId())) {
                return cached;
            }
        }
        return null;
    }

    private void validateCreate(AdminMonitorTaskCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_BODY");
        }
        for (String key : request.rawKeys()) {
            if (!AdminMonitorTaskCreateRequest.ALLOWED_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
            }
        }
        if (isBlank(request.safe_name()) || isBlank(request.cron_expression())
                || isBlank(request.instance_key()) || isBlank(request.model_choice_id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_FIELD");
        }
    }

    private void validateUpdate(AdminMonitorTaskUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_BODY");
        }
        for (String key : request.rawKeys()) {
            if (!AdminMonitorTaskUpdateRequest.ALLOWED_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
            }
        }
        if (request.expected_revision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EXPECTED_REVISION_REQUIRED");
        }
        boolean hasEditable = request.safe_name() != null || request.cron_expression() != null
                || request.zone_id() != null || request.enabled() != null;
        if (!hasEditable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_BODY");
        }
    }

    private ResponseStatusException mapTaskManagerException(TaskManager.TaskManagerException exception) {
        return switch (exception.code()) {
            case TASK_NOT_FOUND -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND");
            case IMMUTABLE_FIELD_REJECTED -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMMUTABLE_FIELD");
            case REVISION_CONFLICT -> new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_CONFLICT");
            case CANDIDATE_EXPIRED -> new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_EXPIRED");
            case IDENTITY_INVALID -> new ResponseStatusException(HttpStatus.CONFLICT, "IDENTITY_INVALID");
            case PLAN_INVALID -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PLAN_INVALID");
        };
    }

    private AdminMonitorTaskResponse toTaskResponse(MonitorTask task) {
        return new AdminMonitorTaskResponse(
                task.taskId(),
                task.safeName(),
                task.safeModelDisplay(),
                task.instanceKey(),
                task.providerSnapshot(),
                task.enabled(),
                task.blockedReason(),
                task.scheduleRevision(),
                task.rowVersion(),
                task.cronExpression(),
                task.zoneId(),
                task.nextFireAt(),
                task.createdAt(),
                task.updatedAt());
    }

    private String safeProvider(CpaIdentityResolver.Candidate candidate) {
        String provider = candidate.provider() == null ? "" : candidate.provider().trim();
        return provider.isEmpty() ? "unknown" : provider;
    }

    private String safeModelDisplay(CpaIdentityResolver.Candidate candidate, String routeModel) {
        return safeProvider(candidate) + " " + routeModel;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
