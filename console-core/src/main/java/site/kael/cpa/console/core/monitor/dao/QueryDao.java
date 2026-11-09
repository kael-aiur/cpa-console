package site.kael.cpa.console.core.monitor.dao;

import org.springframework.jdbc.datasource.DataSourceUtils;
import site.kael.cpa.console.core.monitor.model.RetentionLoss;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T09 read-only query DAO (D6.1–D6.3). Every SQL statement is parameterized and hits the
 * 004 indexes; callers own transaction boundaries and never keep one open across pages.
 * The verification sample is exactly {@code started_at >= start AND started_at < end}
 * with {@code dispatch_status = 'MAY_HAVE_SENT'} and states SUCCESS/FAILED/UNKNOWN; events
 * use {@code COALESCE(started_at, claimed_at)} and flag whether they belong to the sample.
 */
public final class QueryDao {

    /** Public paging limit bounds (D6.3). */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final DataSource dataSource;

    public QueryDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Per-task verification counts inside the strict started_at window. */
    public Map<String, VerificationCounts> verificationCountsByTask(long startMs, long endMs) throws SQLException {
        String sql = "SELECT task_id, "
                + "SUM(CASE WHEN state = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count, "
                + "SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failed_count, "
                + "SUM(CASE WHEN state = 'UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count "
                + "FROM monitor_runs WHERE started_at >= ? AND started_at < ? "
                + "AND dispatch_status = 'MAY_HAVE_SENT' "
                + "AND state IN ('SUCCESS', 'FAILED', 'UNKNOWN') GROUP BY task_id";
        Map<String, VerificationCounts> byTask = new LinkedHashMap<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byTask.put(resultSet.getString("task_id"), new VerificationCounts(
                            resultSet.getInt("success_count"),
                            resultSet.getInt("failed_count"),
                            resultSet.getInt("unknown_count")));
                }
            }
        }
        return byTask;
    }

    /** All non-sample run states counted separately per task (D6.1). */
    public Map<String, Map<String, Integer>> otherStateCountsByTask(long startMs, long endMs) throws SQLException {
        String sql = "SELECT task_id, state, COUNT(*) AS state_count FROM monitor_runs "
                + "WHERE COALESCE(started_at, claimed_at) >= ? AND COALESCE(started_at, claimed_at) < ? "
                + "AND NOT (dispatch_status = 'MAY_HAVE_SENT' AND started_at IS NOT NULL "
                + "AND state IN ('SUCCESS', 'FAILED', 'UNKNOWN')) "
                + "GROUP BY task_id, state";
        Map<String, Map<String, Integer>> byTask = new LinkedHashMap<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byTask.computeIfAbsent(resultSet.getString("task_id"), id -> new LinkedHashMap<>())
                            .put(resultSet.getString("state"), resultSet.getInt("state_count"));
                }
            }
        }
        return byTask;
    }

    /**
     * Events page keyed on {@code COALESCE(started_at, claimed_at), run_seq}; a null
     * {@code afterMs} starts page one at the window start.
     */
    public List<EventRow> eventsPage(
            String taskId, long startMs, long endMs, Long afterMs, long afterRunSeq, int limit) throws SQLException {
        String sql = "SELECT run_id, state, dispatch_status, started_at, finished_at, claimed_at, "
                + "reason_code, run_seq FROM monitor_runs WHERE task_id = ? "
                + "AND COALESCE(started_at, claimed_at) >= ? AND COALESCE(started_at, claimed_at) < ? "
                + "AND (COALESCE(started_at, claimed_at) > ? "
                + "OR (COALESCE(started_at, claimed_at) = ? AND run_seq > ?)) "
                + "ORDER BY COALESCE(started_at, claimed_at), run_seq LIMIT ?";
        List<EventRow> rows = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setLong(2, startMs);
            statement.setLong(3, endMs);
            statement.setLong(4, afterMs == null ? startMs - 1 : afterMs);
            statement.setLong(5, afterMs == null ? startMs - 1 : afterMs);
            statement.setLong(6, afterMs == null ? -1 : afterRunSeq);
            statement.setInt(7, clampLimit(limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String state = resultSet.getString("state");
                    String dispatch = resultSet.getString("dispatch_status");
                    Long startedAt = resultSet.getObject("started_at") == null
                            ? null : resultSet.getLong("started_at");
                    boolean inSample = startedAt != null
                            && "MAY_HAVE_SENT".equals(dispatch)
                            && ("SUCCESS".equals(state) || "FAILED".equals(state) || "UNKNOWN".equals(state));
                    rows.add(new EventRow(
                            resultSet.getString("run_id"),
                            state,
                            dispatch,
                            startedAt,
                            resultSet.getObject("finished_at") == null ? null : resultSet.getLong("finished_at"),
                            resultSet.getLong("claimed_at"),
                            resultSet.getString("reason_code"),
                            resultSet.getLong("run_seq"),
                            inSample));
                }
            }
        }
        return rows;
    }

    /** Most recent real verification per task: started, MAY_HAVE_SENT, S/F/U only. */
    public OptionalLatest latestVerification(String taskId) throws SQLException {
        String sql = "SELECT run_id, state, started_at, finished_at FROM monitor_runs "
                + "WHERE task_id = ? AND started_at IS NOT NULL AND dispatch_status = 'MAY_HAVE_SENT' "
                + "AND state IN ('SUCCESS', 'FAILED', 'UNKNOWN') "
                + "ORDER BY started_at DESC, run_seq DESC LIMIT 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OptionalLatest(
                        resultSet.getString("run_id"),
                        resultSet.getString("state"),
                        resultSet.getLong("started_at"),
                        resultSet.getObject("finished_at") == null ? null : resultSet.getLong("finished_at"));
            }
        }
    }

    /** Most recent scheduled or blocked event per task on the shared event time. */
    public OptionalLatest latestEvent(String taskId) throws SQLException {
        String sql = "SELECT run_id, state, COALESCE(started_at, claimed_at) AS event_at, finished_at "
                + "FROM monitor_runs WHERE task_id = ? "
                + "ORDER BY COALESCE(started_at, claimed_at) DESC, run_seq DESC LIMIT 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OptionalLatest(
                        resultSet.getString("run_id"),
                        resultSet.getString("state"),
                        resultSet.getLong("event_at"),
                        resultSet.getObject("finished_at") == null ? null : resultSet.getLong("finished_at"));
            }
        }
    }

    /** Exact loss rows overlapping the window, read-only. */
    public List<RetentionLoss> lossesOverlapping(long startMs, long endMs) throws SQLException {
        String sql = "SELECT interval_start, interval_end, precision FROM monitor_retention_losses "
                + "WHERE interval_end > ? AND interval_start < ? ORDER BY interval_start, interval_end";
        List<RetentionLoss> losses = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    losses.add(new site.kael.cpa.console.core.monitor.model.RetentionLoss(
                            resultSet.getLong(1),
                            resultSet.getLong(2),
                            "conservative".equals(resultSet.getString(3))));
                }
            }
        }
        return losses;
    }

    /** Runs actually retained inside the window, for retained_sample_intervals. */
    public List<RetainedSample> retainedSamples(long startMs, long endMs) throws SQLException {
        String sql = "SELECT task_id, started_at FROM monitor_runs WHERE started_at >= ? AND started_at < ? "
                + "AND dispatch_status = 'MAY_HAVE_SENT' AND state IN ('SUCCESS', 'FAILED', 'UNKNOWN') "
                + "ORDER BY started_at, run_seq";
        List<RetainedSample> samples = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    samples.add(new RetainedSample(
                            resultSet.getString("task_id"), resultSet.getLong("started_at")));
                }
            }
        }
        return samples;
    }

    /** Runs actually retained per task, used for D6.2 sample/exception distinction. */
    public Map<String, Integer> sampleCountsByTask(long startMs, long endMs) throws SQLException {
        String sql = "SELECT task_id, COUNT(*) AS sample_count FROM monitor_runs "
                + "WHERE started_at >= ? AND started_at < ? AND dispatch_status = 'MAY_HAVE_SENT' "
                + "AND state IN ('SUCCESS', 'FAILED', 'UNKNOWN') GROUP BY task_id";
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(resultSet.getString("task_id"), resultSet.getInt("sample_count"));
                }
            }
        }
        return counts;
    }

    public record VerificationCounts(int successCount, int failedCount, int unknownCount) {
        public int sampleCount() {
            return successCount + failedCount + unknownCount;
        }
    }

    /** One event row with the D6.1 sample flag; carries no identity or response body. */
    public record EventRow(
            String runId,
            String state,
            String dispatchStatus,
            Long startedAt,
            Long finishedAt,
            long claimedAt,
            String reasonCode,
            long runSeq,
            boolean includedInSample) {

        public long eventAt() {
            return startedAt != null ? startedAt : claimedAt;
        }
    }

    /** Latest verification or event row per task; {@code eventAt} uses the same COALESCE. */
    public record OptionalLatest(
            String runId, String state, long startedAt, Long finishedAt) {
    }

    /** A single retained started_at value (identity-free scope here). */
    public record RetainedSample(String taskId, long startedAt) {
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
