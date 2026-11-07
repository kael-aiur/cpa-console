package site.kael.cpa.console.core.monitor.dao;

import site.kael.cpa.console.core.monitor.model.RetentionLoss;
import site.kael.cpa.console.core.monitor.model.RetentionMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

/**
 * T08 retention evidence DAO (D5.1 / D6.2). Writes loss intervals, collection metadata and
 * the shared monitor data revision inside the caller's cleanup transaction; there is no
 * separate commit path that could advance a watermark without durable run deletion.
 */
public final class RetentionDao {

    /** Deleting a finished run counts toward the batch limit. */
    public enum DeleteResult { DELETED, SKIPPED }

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final DataSource dataSource;

    public RetentionDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Ensures the singleton metadata row exists before any purge decision. */
    public void ensureMeta(long nowMs) throws SQLException {
        String sql = "INSERT INTO monitor_retention_meta "
                + "(id, collection_started_at, retention_revision, last_cleanup_at) "
                + "SELECT 1, ?, 0, NULL WHERE NOT EXISTS (SELECT 1 FROM monitor_retention_meta)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMs);
            statement.executeUpdate();
        }
    }

    /** Loads collection metadata or initializes it on first persistence. */
    public RetentionMeta ensureAndReadMeta(long nowMs) throws SQLException {
        ensureMeta(nowMs);
        String sql = "SELECT collection_started_at, retention_revision, last_cleanup_at "
                + "FROM monitor_retention_meta WHERE id = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Retention metadata row disappeared during read");
            }
            return new RetentionMeta(
                    resultSet.getLong("collection_started_at"),
                    resultSet.getLong("retention_revision"),
                    resultSet.getObject("last_cleanup_at") == null
                            ? null : resultSet.getLong("last_cleanup_at"));
        }
    }

    /** Transactional metadata read helper (ensure-on-demand inside a caller transaction). */
    public RetentionMeta ensureAndReadMetaTransactional(Connection connection, long nowMs) throws SQLException {
        String insertSql = "INSERT INTO monitor_retention_meta "
                + "(id, collection_started_at, retention_revision, last_cleanup_at) "
                + "SELECT 1, ?, 0, NULL WHERE NOT EXISTS (SELECT 1 FROM monitor_retention_meta)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setLong(1, nowMs);
            statement.executeUpdate();
        }
        String sql = "SELECT collection_started_at, retention_revision, last_cleanup_at "
                + "FROM monitor_retention_meta WHERE id = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Retention metadata row disappeared during transactional read");
            }
            return new RetentionMeta(
                    resultSet.getLong("collection_started_at"),
                    resultSet.getLong("retention_revision"),
                    resultSet.getObject("last_cleanup_at") == null
                            ? null : resultSet.getLong("last_cleanup_at"));
        }
    }

    /** Reads the current metadata without creating a row (query-side contract). */
    public Optional<RetentionMeta> findMeta() throws SQLException {
        String sql = "SELECT collection_started_at, retention_revision, last_cleanup_at "
                + "FROM monitor_retention_meta WHERE id = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new RetentionMeta(
                    resultSet.getLong("collection_started_at"),
                    resultSet.getLong("retention_revision"),
                    resultSet.getObject("last_cleanup_at") == null
                            ? null : resultSet.getLong("last_cleanup_at")));
        }
    }

    /**
     * One CAS-style batch delete of exactly the D6.2 predicates. No non-recovered active run
     * and no run guarded by another active execution can leave the database. The selected
     * started_at values are returned in the same transaction that performs the DELETE.
     */
    public PurgeBatch deleteExpiredBatch(long cleanupNowMs, long retentionMs, int requestedBatchSize)
            throws SQLException {
        String sql = "DELETE FROM monitor_runs WHERE run_seq IN ("
                + "SELECT run_seq FROM monitor_runs AS runs "
                + "WHERE finished_at IS NOT NULL AND finished_at < ? "
                + "AND state NOT IN ('CLAIMED_STATE','PENDING','RUNNING_STATE') "
                + "AND phase = 'TERMINAL' "
                + "AND NOT EXISTS (SELECT 1 FROM monitor_credential_guards AS guards "
                + "    WHERE guards.active_run_id = runs.run_id) "
                + "ORDER BY finished_at, run_seq LIMIT ?) RETURNING started_at";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cleanupNowMs - retentionMs);
            int batchSize = normalizeBatchSize(requestedBatchSize);
            statement.setInt(2, batchSize);
            List<Long> startedAtValues = new ArrayList<>();
            boolean hadMore = false;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (startedAtValues.size() < batchSize) {
                        startedAtValues.add(resultSet.getLong(1));
                    } else {
                        hadMore = true;
                        break;
                    }
                }
            }
            return new PurgeBatch(startedAtValues, hadMore);
        }
    }

    /** Payload returned by one transactional purge batch. */
    public record PurgeBatch(List<Long> startedAtValues, boolean moreExpiredRunsExist) {
        public PurgeBatch {
            startedAtValues = List.copyOf(startedAtValues);
        }
    }

    /**
     * Merges overlapping/adjacent millisecond intervals and stores them with the supplied
     * retention revision. Adjacent loss points stay merged; every interval is half-open.
     */
    public void recordLosses(List<RetentionLoss> losses, long retentionRevision) throws SQLException {
        Objects.requireNonNull(losses, "losses");
        if (losses.isEmpty()) {
            return;
        }
        List<RetentionLoss> merged = mergeAdjacent(losses);
        String sql = "INSERT INTO monitor_retention_losses "
                + "(interval_start, interval_end, precision, created_at, retention_revision) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (RetentionLoss interval : merged) {
                statement.setString(3, interval.conservative() ? "conservative" : "exact");
                statement.setLong(1, interval.start());
                statement.setLong(2, interval.end());
                statement.setLong(4, interval.start());
                statement.setLong(5, retentionRevision);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Loss rows sorted by interval start; never carries identity or response evidence. */
    public List<RetentionLoss> listLosses() throws SQLException {
        String sql = "SELECT interval_start, interval_end, precision FROM monitor_retention_losses "
                + "ORDER BY interval_start, interval_end";
        List<RetentionLoss> losses = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                losses.add(new RetentionLoss(
                        resultSet.getLong(1), resultSet.getLong(2),
                        "conservative".equals(resultSet.getString(3))));
            }
        }
        return List.copyOf(losses);
    }

    /** Advances the cleanup watermark and time in the same transaction as deletion/losses. */
    public void markCleanup(long retentionRevision, long cleanupAtMs) throws SQLException {
        String sql = "UPDATE monitor_retention_meta SET retention_revision = ?, last_cleanup_at = ? "
                + "WHERE id = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, retentionRevision);
            statement.setLong(2, cleanupAtMs);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Retention metadata row is missing");
            }
        }
    }

    /** Transactional retention watermark increase for callers with their own transaction. */
    public void bumpRetentionRevision(Connection connection, long cleanupAtMs) throws SQLException {
        String sql = "UPDATE monitor_retention_meta SET retention_revision = retention_revision + 1, "
                + "last_cleanup_at = ? WHERE id = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cleanupAtMs);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Retention metadata row is missing");
            }
        }
    }

    /** Loss rows eligible for conservative compaction, oldest first. */
    public List<RetentionLoss> listOldestLosses(int limit) throws SQLException {
        String sql = "SELECT interval_start, interval_end, precision FROM monitor_retention_losses "
                + "ORDER BY loss_id LIMIT ?";
        List<RetentionLoss> losses = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, normalizeBatchSize(limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    losses.add(new RetentionLoss(
                            resultSet.getLong(1), resultSet.getLong(2),
                            "conservative".equals(resultSet.getString(3))));
                    if (losses.size() >= normalizeBatchSize(limit)) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(losses);
    }

    /** Replaces the selected loss rows with one conservative envelope. */
    public void replaceLosses(
            List<RetentionLoss> obsolete, RetentionLoss conservative, long retentionRevision)
            throws SQLException {
        Objects.requireNonNull(obsolete, "obsolete");
        Objects.requireNonNull(conservative, "conservative");
        if (obsolete.isEmpty()) {
            return;
        }
        String deleteSql = "DELETE FROM monitor_retention_losses WHERE interval_start = ? AND interval_end = ? "
                + "AND precision = ?";
        String insertSql = "INSERT INTO monitor_retention_losses "
                + "(interval_start, interval_end, precision, created_at, retention_revision) "
                + "VALUES (?, ?, 'conservative', ?, ?)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement deleteStatement = connection.prepareStatement(deleteSql);
             PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
            for (RetentionLoss interval : obsolete) {
                deleteStatement.setLong(1, interval.start());
                deleteStatement.setLong(2, interval.end());
                deleteStatement.setString(3, interval.conservative() ? "conservative" : "exact");
                deleteStatement.addBatch();
            }
            deleteStatement.executeBatch();
            insertStatement.setLong(1, conservative.start());
            insertStatement.setLong(2, conservative.end());
            insertStatement.setLong(3, conservative.start());
            insertStatement.setLong(4, retentionRevision);
            insertStatement.executeUpdate();
        }
    }

    /**
     * Merges adjacent/overlap millisecond intervals and returns the list sorted by start.
     * Conservative and exact intervals do not collapse precision, except that an exact interval
     * already covers a conservative interval during compaction.
     */
    private static List<RetentionLoss> mergeAdjacent(List<RetentionLoss> input) {
        List<RetentionLoss> sorted = new ArrayList<>(input);
        sorted.sort(java.util.Comparator.comparingLong(RetentionLoss::start)
                .thenComparingLong(RetentionLoss::end));
        LinkedHashMap<Long, RetentionLoss> byStart = new LinkedHashMap<>();
        for (RetentionLoss interval : sorted) {
            RetentionLoss previous = byStart.get(interval.start());
            if (previous == null
                    || (!interval.conservative() && previous.conservative())
                    || (interval.conservative() == previous.conservative()
                        && interval.end() > previous.end())) {
                byStart.put(interval.start(), interval);
            }
        }
        Map<Long, RetentionLoss> merged = new LinkedHashMap<>();
        RetentionLoss current = null;
        for (RetentionLoss interval : byStart.values()) {
            if (current == null) {
                current = interval;
                merged.put(current.start(), current);
                continue;
            }
            boolean samePrecision = interval.conservative() == current.conservative();
            boolean overlapOrAdjacent = interval.start() <= current.end();
            if (samePrecision && overlapOrAdjacent) {
                if (interval.end() > current.end()) {
                    current = new RetentionLoss(current.start(), interval.end(), current.conservative());
                    merged.put(current.start(), current);
                }
            } else {
                current = interval;
                merged.put(current.start(), current);
            }
        }
        return List.copyOf(merged.values());
    }

    private static int normalizeBatchSize(int requested) {
        return Math.max(1, Math.min(500, requested <= 0 ? DEFAULT_BATCH_SIZE : requested));
    }
}
