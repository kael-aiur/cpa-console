package site.kael.cpa.console.monitor.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.core.monitor.manager.QueryManager;
import site.kael.cpa.console.core.monitor.manager.TaskManager;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.QueryWindow;
import site.kael.cpa.console.monitor.dto.CoverageIntervalResponse;
import site.kael.cpa.console.monitor.dto.CoverageLossResponse;
import site.kael.cpa.console.monitor.dto.CoverageResponse;
import site.kael.cpa.console.monitor.dto.LatestInfoResponse;
import site.kael.cpa.console.monitor.dto.MonitorRunPageResponse;
import site.kael.cpa.console.monitor.dto.MonitorRunResponse;
import site.kael.cpa.console.monitor.dto.MonitorTaskCardResponse;
import site.kael.cpa.console.monitor.dto.MonitorTaskListResponse;
import site.kael.cpa.console.monitor.dto.VerificationSummaryResponse;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * T11 login-user read-only monitoring API over the T09 QueryManager. It exposes only the
 * D7 public whitelist through DTOs, includes disabled tasks and never triggers a verify
 * POST. The service lives in the server layer and does not touch CPA directly.
 */
@Service
public class MonitorService {

    private final QueryManager queryManager;
    private final TaskManager taskManager;
    private final Clock clock;

    public MonitorService(QueryManager queryManager, TaskManager taskManager, Clock clock) {
        this.queryManager = Objects.requireNonNull(queryManager, "queryManager");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** D7 GET /api/monitor/tasks: all task cards at one captured as_of. */
    public MonitorTaskListResponse taskCards(String startIso, String endIso) {
        QueryWindow window = window(startIso, endIso);
        long asOf = clock.instant().toEpochMilli();
        List<QueryManager.TaskCard> cards;
        long dataRevision;
        try {
            cards = queryManager.taskCards(window, asOf);
            dataRevision = queryManager.currentDataRevision();
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        List<MonitorTaskCardResponse> visible = new ArrayList<>(cards.size());
        for (QueryManager.TaskCard card : cards) {
            visible.add(new MonitorTaskCardResponse(
                    card.taskId(),
                    card.safeName(),
                    card.safeModelDisplay(),
                    card.enabled(),
                    card.blockedReason(),
                    card.nextFireAt(),
                    new VerificationSummaryResponse(
                            card.verification().successCount(),
                            card.verification().failedCount(),
                            card.verification().unknownCount(),
                            card.verification().sampleCount(),
                            card.verification().ratio()),
                    new TreeMap<>(card.otherStates()),
                    card.latestVerification() == null ? null : new LatestInfoResponse(
                            card.latestVerification().state(),
                            card.latestVerification().startedAt(),
                            card.latestVerification().finishedAt()),
                    card.latestEvent() == null ? null : new LatestInfoResponse(
                            card.latestEvent().state(),
                            card.latestEvent().startedAt(),
                            card.latestEvent().finishedAt()),
                    coverage(card.coverage()),
                    asOf,
                    dataRevision));
        }
        return new MonitorTaskListResponse(List.copyOf(visible), visible.size(), asOf, dataRevision);
    }

    /** D7 GET /api/monitor/tasks/{task_id}/runs: signed-cursor page over the shared snapshot. */
    public MonitorRunPageResponse runs(
            String taskId,
            String startIso,
            String endIso,
            String view,
            String cursor,
            Integer limit) {
        QueryWindow window = window(startIso, endIso);
        QueryManager.View resolvedView = "events".equalsIgnoreCase(view)
                ? QueryManager.View.EVENTS : QueryManager.View.VERIFICATION;
        long asOf = clock.instant().toEpochMilli();
        MonitorTask task;
        try {
            task = taskManager.find(taskId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
            QueryManager.EventPage page =
                    queryManager.eventsPage(taskId, window, resolvedView, cursor, limit, asOf);
            List<MonitorRunResponse> runs = new ArrayList<>(page.items().size());
            for (QueryManager.EventItem item : page.items()) {
                runs.add(new MonitorRunResponse(
                        item.state(),
                        item.dispatchStatus(),
                        item.startedAt(),
                        item.finishedAt(),
                        null,
                        item.reasonCode(),
                        item.eventAt(),
                        item.includedInSample()));
            }
            return new MonitorRunPageResponse(
                    task.safeName(),
                    task.safeModelDisplay(),
                    List.copyOf(runs),
                    page.nextCursor(),
                    page.dataRevision().value(),
                    page.asOfMs(),
                    page.window().startMs(),
                    page.window().endMs(),
                    resolvedView.name().toLowerCase(Locale.ROOT),
                    coverage(page.coverage()),
                    page.snapshotChanged());
        } catch (QueryManager.SnapshotChangedException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SNAPSHOT_CHANGED");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
    }

    private QueryWindow window(String startIso, String endIso) {
        try {
            return startIso != null && endIso != null
                    ? QueryWindow.ofCustom(startIso, endIso)
                    : QueryWindow.defaultWindow(clock.instant().toEpochMilli());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW");
        }
    }

    private CoverageResponse coverage(QueryManager.Coverage coverage) {
        return new CoverageResponse(
                coverage.requestedStart(),
                coverage.requestedEnd(),
                coverage.asOf(),
                coverage.collectionStartedAt(),
                coverage.retentionRevision(),
                coverage.completeIntervals().stream()
                        .map(i -> new CoverageIntervalResponse(i.startMs(), i.endMs())).toList(),
                coverage.lossIntervals().stream()
                        .map(i -> new CoverageLossResponse(i.startMs(), i.endMs(), i.conservative())).toList(),
                coverage.retainedSampleIntervals().stream()
                        .map(i -> new CoverageIntervalResponse(i.startMs(), i.endMs())).toList(),
                coverage.observationGaps().stream()
                        .map(i -> new CoverageIntervalResponse(i.startMs(), i.endMs())).toList(),
                coverage.complete());
    }
}
