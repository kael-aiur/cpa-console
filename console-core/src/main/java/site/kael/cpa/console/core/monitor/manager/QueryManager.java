package site.kael.cpa.console.core.monitor.manager;

import org.springframework.jdbc.datasource.DataSourceUtils;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.QueryDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.model.MonitorDataRevision;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.QueryWindow;
import site.kael.cpa.console.core.monitor.model.RetentionLoss;
import site.kael.cpa.console.core.monitor.model.RetentionMeta;
import site.kael.cpa.console.core.monitor.model.SignedCursor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * T09 query manager (D5.1 / D6 / D7 internal model): one capture of the shared as_of and
 * revision, window validation, the strict verification sample, per-state counts, the
 * separated latest verification and latest scheduled event, coverage facts and the signed
 * keyset cursor. Queries only monitor evidence — never CPA usage counters, never a verify
 * POST. DTO whitelisting and role filtering stay in T10/T11; this manager never returns
 * identity fields.
 */
public final class QueryManager {

    /** Query views (D6.1): strict sample or all events. */
    public enum View { VERIFICATION, EVENTS }

    /** Card result for one task with window statistics and coverage. */
    public record TaskCard(
            String taskId,
            String safeName,
            String safeModelDisplay,
            boolean enabled,
            String blockedReason,
            Long nextFireAt,
            VerificationSummary verification,
            Map<String, Integer> otherStates,
            LatestInfo latestVerification,
            LatestInfo latestEvent,
            Coverage coverage) {
    }

    /** S/F/U counts with ratio and the D6.1 null-denominator rule. */
    public record VerificationSummary(
            int successCount,
            int failedCount,
            int unknownCount,
            int sampleCount,
            BigDecimal ratio) {

        public static VerificationSummary of(int success, int failed, int unknown) {
            int sample = success + failed + unknown;
            if (sample == 0) {
                return new VerificationSummary(success, failed, unknown, 0, null);
            }
            return new VerificationSummary(success, failed, unknown, sample,
                    BigDecimal.valueOf(success)
                            .divide(BigDecimal.valueOf(sample), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
        }
    }

    /** Latest verification or event evidence; latest verification distinguishes
     * "no retained verification record" from "never verified" through the caller. */
    public record LatestInfo(String runId, String state, long startedAt, Long finishedAt) {
    }

    /** Coverage facts per D6.2; never presents a min/max envelope over a hole. */
    public record Coverage(
            long requestedStart,
            long requestedEnd,
            long asOf,
            long collectionStartedAt,
            long retentionRevision,
            List<Interval> completeIntervals,
            List<LossInterval> lossIntervals,
            List<Interval> retainedSampleIntervals,
            List<Interval> observationGaps,
            boolean complete) {
    }

    /** Half-open interval in milliseconds. */
    public record Interval(long startMs, long endMs) {
    }

    /** Loss interval with its precision flag. */
    public record LossInterval(long startMs, long endMs, boolean conservative) {
    }

    /** One event page row; the sample flag follows D6.1 exactly. */
    public record EventItem(
            String runId,
            String taskId,
            String state,
            String dispatchStatus,
            Long scheduledAt,
            Long startedAt,
            Long finishedAt,
            Long durationMs,
            String reasonCode,
            long eventAt,
            boolean includedInSample) {
    }

    /** Page one or subsequent results, bound to the captured snapshot. */
    public record EventPage(
            String taskId,
            List<EventItem> items,
            String nextCursor,
            MonitorDataRevision dataRevision,
            long asOfMs,
            QueryWindow window,
            Coverage coverage,
            boolean snapshotChanged) {
    }

    /** Snapshot-changed contract (D6.3): the caller must refresh page one. */
    public static final class SnapshotChangedException extends RuntimeException {
        public SnapshotChangedException(String message) {
            super(message);
        }
    }

    public static final long DEFAULT_AS_OF_BACK_MS = 24L * 60L * 60L * 1000L;

    private final DataSource dataSource;
    private final QueryDao queryDao;
    private final RetentionDao retentionDao;
    private final ObservationGapDao gapDao;
    private final TaskManager taskManager;
    private final RunDao runDao;
    private final SignedCursor cursorSigner;

    public QueryManager(
            DataSource dataSource,
            QueryDao queryDao,
            RetentionDao retentionDao,
            ObservationGapDao gapDao,
            TaskManager taskManager,
            RunDao runDao,
            SignedCursor cursorSigner) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.queryDao = Objects.requireNonNull(queryDao, "queryDao");
        this.retentionDao = Objects.requireNonNull(retentionDao, "retentionDao");
        this.gapDao = Objects.requireNonNull(gapDao, "gapDao");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.runDao = Objects.requireNonNull(runDao, "runDao");
        this.cursorSigner = Objects.requireNonNull(cursorSigner, "cursorSigner");
    }

    /**
     * One as_of capture for every task card: window stats, other-state counts, the two
     * separated latest records and coverage. Disabled tasks remain in the card list.
     */
    public List<TaskCard> taskCards(QueryWindow window, long asOfMs) throws SQLException {
        List<MonitorTask> tasks = taskManager.listAll();
        Map<String, QueryDao.VerificationCounts> counts =
                queryDao.verificationCountsByTask(window.startMs(), window.endMs());
        Map<String, Map<String, Integer>> others =
                queryDao.otherStateCountsByTask(window.startMs(), window.endMs());
        List<TaskCard> cards = new ArrayList<>(tasks.size());
        for (MonitorTask task : tasks) {
            QueryDao.VerificationCounts count = counts.getOrDefault(
                    task.taskId(), new QueryDao.VerificationCounts(0, 0, 0));
            QueryDao.OptionalLatest latestVerification = queryDao.latestVerification(task.taskId());
            QueryDao.OptionalLatest latestEvent = queryDao.latestEvent(task.taskId());
            cards.add(new TaskCard(
                    task.taskId(),
                    task.safeName(),
                    task.safeModelDisplay(),
                    task.enabled(),
                    task.blockedReason(),
                    task.nextFireAt(),
                    VerificationSummary.of(count.successCount(), count.failedCount(), count.unknownCount()),
                    others.getOrDefault(task.taskId(), Map.of()),
                    latestVerification == null ? null : new LatestInfo(
                            latestVerification.runId(), latestVerification.state(),
                            latestVerification.startedAt(), latestVerification.finishedAt()),
                    latestEvent == null ? null : new LatestInfo(
                            latestEvent.runId(), latestEvent.state(),
                            latestEvent.startedAt(), latestEvent.finishedAt()),
                    coverage(task.taskId(), window, asOfMs)));
        }
        return List.copyOf(cards);
    }

    /** Per-task coverage assembly; shared across the card and the events page. */
    public Coverage coverage(String taskId, QueryWindow window, long asOfMs) throws SQLException {
        RetentionMeta meta = retentionDao.findMeta().orElse(null);
        long collectionStart = meta != null ? meta.collectionStartedAt() : 0L;
        long retentionRevision = meta != null ? meta.retentionRevision() : 0L;
        List<RetentionLoss> losses =
                queryDao.lossesOverlapping(window.startMs(), window.endMs());
        List<ObservationGapDao.Gap> gaps =
                gapDao.findGapsOverlapping(window.startMs(), window.endMs());
        List<QueryDao.RetainedSample> samples =
                queryDao.retainedSamples(window.startMs(), window.endMs());
        boolean taskExisted = taskManager.find(taskId).isPresent();
        // Collection start is the global first-persistence fact (D6.2): anything before it
        // is "not collected", not deleted.
        boolean windowStartsBeforeCollection = window.startMs() < collectionStart;
        boolean hasHole = !losses.isEmpty() || !gaps.isEmpty();
        boolean complete = taskExisted && !windowStartsBeforeCollection && !hasHole;
        return new Coverage(
                window.startMs(),
                window.endMs(),
                asOfMs,
                collectionStart,
                retentionRevision,
                complete ? List.of(new Interval(window.startMs(), window.endMs())) : List.of(),
                losses.stream().map(loss -> new LossInterval(loss.start(), loss.end(), loss.conservative()))
                        .toList(),
                retainedIntervals(samples),
                gaps.stream().map(gap -> new Interval(gap.startMs(), gap.endMs())).toList(),
                complete);
    }

    /** Merges actually retained sample started_at values into half-open intervals. */
    private List<Interval> retainedIntervals(List<QueryDao.RetainedSample> samples) {
        List<Interval> intervals = new ArrayList<>();
        Long currentStart = null;
        Long currentEnd = null;
        for (QueryDao.RetainedSample sample : samples) {
            long point = sample.startedAt();
            if (currentStart == null) {
                currentStart = point;
                currentEnd = point + 1;
                continue;
            }
            if (point <= currentEnd) {
                currentEnd = Math.max(currentEnd, point + 1);
            } else {
                intervals.add(new Interval(currentStart, currentEnd));
                currentStart = point;
                currentEnd = point + 1;
            }
        }
        if (currentStart != null) {
            intervals.add(new Interval(currentStart, currentEnd));
        }
        return List.copyOf(intervals);
    }

    /**
     * Page one of the events view for one task. The cursor carries the window, view,
     * as_of, retention and data revision, so a changed snapshot on later pages raises
     * {@link SnapshotChangedException} instead of silently mixing evidence.
     */
    public EventPage eventsPage(
            String taskId,
            QueryWindow window,
            View view,
            String cursorToken,
            Integer requestedLimit,
            long asOfMs) throws SQLException {
        SignedCursor.Payload payload = cursorToken == null ? null : cursorSigner.decode(cursorToken);
        if (cursorToken != null && payload == null) {
            throw new IllegalArgumentException("Cursor is invalid or tampered");
        }
        if (payload != null) {
            if (payload.dataRevision() != currentDataRevision()
                    || payload.retentionRevision() != currentRetentionRevision()
                    || payload.asOfMs() != asOfMs
                    || payload.startMs() != window.startMs()
                    || payload.endMs() != window.endMs()
                    || !payload.view().equals(view.name())
                    || !payload.taskKey().equals(taskId)) {
                throw new SnapshotChangedException(
                        "Monitoring data changed; refresh page one (SNAPSHOT_CHANGED)");
            }
        }
        int limit = payload != null ? payload.limit()
                : requestedLimit == null ? QueryDao.DEFAULT_PAGE_SIZE : requestedLimit;
        Long afterMs = payload != null ? payload.lastSortMs() : null;
        long afterSeq = payload != null ? payload.lastRunSeq() : -1L;
        List<QueryDao.EventRow> rows = queryDao.eventsPage(
                taskId, window.startMs(), window.endMs(), afterMs, afterSeq, limit + 1);
        boolean more = rows.size() > limit;
        List<QueryDao.EventRow> page = more ? rows.subList(0, limit) : rows;
        List<EventItem> items = new ArrayList<>(page.size());
        for (QueryDao.EventRow row : page) {
            items.add(new EventItem(
                    row.runId(), taskId, row.state(), row.dispatchStatus(),
                    null, row.startedAt(), row.finishedAt(), null,
                    row.reasonCode(), row.eventAt(), row.includedInSample()));
        }
        String nextCursor = null;
        if (more && !page.isEmpty()) {
            QueryDao.EventRow last = page.get(page.size() - 1);
            nextCursor = cursorSigner.encode(new SignedCursor.Payload(
                    taskId, last.eventAt(), last.runSeq(), limit, asOfMs,
                    window.startMs(), window.endMs(), view.name(),
                    currentRetentionRevision(), currentDataRevision(), "events"));
        }
        return new EventPage(taskId, List.copyOf(items), nextCursor,
                new MonitorDataRevision(currentDataRevision()), asOfMs, window,
                coverage(taskId, window, asOfMs), false);
    }

    /** Current shared data revision; call inside the same as_of capture. */
    public long currentDataRevision() throws SQLException {
        return runDao.currentDataRevision();
    }

    /** Current retention revision; null-safe when metadata has never been written. */
    public long currentRetentionRevision() throws SQLException {
        Optional<RetentionMeta> meta = retentionDao.findMeta();
        return meta.map(RetentionMeta::retentionRevision).orElse(0L);
    }

    /** D6.2 "no retained data" distinction: the window is fully outside retained evidence. */
    public boolean noRetainedData(String taskId, QueryWindow window) throws SQLException {
        QueryDao.VerificationCounts counts = queryDao.verificationCountsByTask(
                window.startMs(), window.endMs()).getOrDefault(
                taskId, new QueryDao.VerificationCounts(0, 0, 0));
        Map<String, Integer> samples = queryDao.sampleCountsByTask(window.startMs(), window.endMs());
        boolean noSample = counts.sampleCount() == 0;
        boolean noRetainedRows = samples.getOrDefault(taskId, 0) == 0;
        Coverage coverage = coverage(taskId, window, coverageAsOf());
        return noSample && noRetainedRows && !coverage.complete();
    }

    private long coverageAsOf() throws SQLException {
        return clockFallback();
    }

    private long clockFallback() {
        return System.currentTimeMillis();
    }

    /** Static factory used by tests to fix the as_of capture explicitly. */
    public static QueryWindow windowOrThrow(String startIso, String endIso) {
        return QueryWindow.ofCustom(startIso, endIso);
    }
}
