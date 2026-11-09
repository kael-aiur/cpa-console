package site.kael.cpa.console.core.monitor.manager;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao.PurgeBatch;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.model.RetentionLoss;
import site.kael.cpa.console.core.monitor.model.RetentionMeta;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * T08 finished_at retention with identity-free loss evidence (D6.2).
 * <p>
 * Only finished runs (phase=TERMINAL, finished_at non-null) older than the strict
 * {@code finished_at < cleanup_now - retention} watermark and without an active guard
 * reference are deleted. Every millisecond {@code started_at} actually removed is persisted as
 * a half-open loss interval in the same transaction as the DELETE, watermark and shared monitor
 * data revision. A failed batch therefore rolls back all coverage evidence together and never
 * advances a pointer without deletion. This manager never starts itself; lifecycle wiring stays
 * in T07 and implementation of this code grants no authorization for production cleanup.
 */
public final class RetentionManager {

    /** Fixed 30 day retention window (S6/Q3). */
    public static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    /** When loss rows exceed this count the next sweep compacts into a conservative envelope. */
    public static final int LOSS_COMPACTION_THRESHOLD = 64;
    /** Max runs per transactional batch. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    private final RetentionDao retentionDao;
    private final RunDao runDao;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RetentionManager(
            javax.sql.DataSource dataSource,
            RetentionDao retentionDao, RunDao runDao, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.retentionDao = Objects.requireNonNull(retentionDao, "retentionDao");
        this.runDao = Objects.requireNonNull(runDao, "runDao");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    private final javax.sql.DataSource dataSource;

    /**
     * Reports whether any further eligible expired run exists. The probe DELETE runs in the
     * caller's transaction when one is active; direct calls are a diagnostic hook only.
     */
    public boolean hasExpiredRuns(BooleanSupplier stopSignal, long cleanupNowMs) throws SQLException {
        if (stopSignal != null && stopSignal.getAsBoolean()) {
            return false;
        }
        PurgeBatch probe = retentionDao.deleteExpiredBatch(cleanupNowMs, RETENTION_MS, DEFAULT_BATCH_SIZE);
        return !probe.startedAtValues().isEmpty() || probe.moreExpiredRunsExist();
    }

    /**
     * One transactional purge batch at the caller-supplied cleanup instant. On success the
     * run DELETE, started-at loss rows, watermark and data revision commit together. On
     * failure everything rolls back and the exception carries only a stable local reason.
     */
    public PurgeOutcome purgeExpiredBatch(BooleanSupplier stopSignal) throws SQLException {
        long nowMs = clock.instant().toEpochMilli();
        RetentionMeta meta = retentionDao.ensureAndReadMeta(nowMs);
        PurgeOutcome outcome = transactions.execute(status -> {
            try {
                if (stopSignal != null && stopSignal.getAsBoolean()) {
                    return new PurgeOutcome(0, 0, false, meta);
                }
                RetentionMeta txMeta =
                        retentionDao.ensureAndReadMetaTransactional(txConnection(), nowMs);
                PurgeBatch batch = retentionDao.deleteExpiredBatch(nowMs, RETENTION_MS, DEFAULT_BATCH_SIZE);
                if (batch.startedAtValues().isEmpty() && !batch.moreExpiredRunsExist()) {
                    retentionDao.markCleanup(txMeta.retentionRevision() + 1, nowMs);
                    return new PurgeOutcome(0, 0, true, meta);
                }
                List<RetentionLoss> losses = lossIntervals(batch.startedAtValues());
                if (!losses.isEmpty()) {
                    retentionDao.recordLosses(losses, txMeta.retentionRevision() + 1);
                }
                retentionDao.markCleanup(txMeta.retentionRevision() + 1, nowMs);
                retentionDao.bumpRetentionRevision(txConnection(), nowMs);
                runDao.bumpDataRevision(txConnection());
                return new PurgeOutcome(
                        batch.startedAtValues().size(), losses.size(), true, meta);
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new RetentionSweepException("Retention batch failed", exception);
            }
        });
        return outcome != null ? outcome : new PurgeOutcome(0, 0, false, meta);
    }

    /**
     * Compacts the oldest exact loss rows into one explicitly conservative envelope when the
     * count exceeds {@link #LOSS_COMPACTION_THRESHOLD}. Coverage readers must never present
     * the resulting envelope as exact full coverage.
     */
    public CompactionOutcome compactLosses(BooleanSupplier stopSignal) throws SQLException {
        long nowMs = clock.instant().toEpochMilli();
        List<RetentionLoss> oldest = retentionDao.listOldestLosses(LOSS_COMPACTION_THRESHOLD + 1);
        if (oldest.size() <= LOSS_COMPACTION_THRESHOLD) {
            return CompactionOutcome.none();
        }
        List<RetentionLoss> selected = List.copyOf(oldest.subList(0, LOSS_COMPACTION_THRESHOLD));
        RetentionLoss conservative = new RetentionLoss(
                selected.get(0).start(), selected.get(selected.size() - 1).end(), true);
        Boolean committed = transactions.execute(status -> {
            try {
                if (stopSignal != null && stopSignal.getAsBoolean()) {
                    return Boolean.FALSE;
                }
                RetentionMeta meta =
                        retentionDao.ensureAndReadMetaTransactional(txConnection(), nowMs);
                retentionDao.replaceLosses(selected, conservative, meta.retentionRevision() + 1);
                retentionDao.markCleanup(meta.retentionRevision() + 1, nowMs);
                retentionDao.bumpRetentionRevision(txConnection(), nowMs);
                runDao.bumpDataRevision(txConnection());
                return Boolean.TRUE;
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new RetentionSweepException("Loss compaction failed", exception);
            }
        });
        return Boolean.TRUE.equals(committed)
                ? new CompactionOutcome(LOSS_COMPACTION_THRESHOLD, conservative, true)
                : CompactionOutcome.none();
    }

    /** Result of one purge batch (identity-free). */
    public record PurgeOutcome(
            int runsDeleted, int lossIntervalsRecorded, boolean committed, RetentionMeta metaBefore) {
    }

    /** Result of one conservative compaction. */
    public record CompactionOutcome(int mergedIntervals, RetentionLoss envelope, boolean committed) {
        public static CompactionOutcome none() {
            return new CompactionOutcome(0, null, false);
        }
    }

    /** Storage failure inside the retention transaction; callers must stop new dispatches. */
    public static final class RetentionSweepException extends RuntimeException {
        public RetentionSweepException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private List<RetentionLoss> lossIntervals(List<Long> startedAtValues) {
        List<RetentionLoss> intervals = new ArrayList<>();
        for (Long startedAt : startedAtValues) {
            if (startedAt != null) {
                intervals.add(new RetentionLoss(startedAt, startedAt + 1, false));
            }
        }
        return intervals;
    }

    private Connection txConnection() throws SQLException {
        return DataSourceUtils.getConnection(dataSource);
    }
}
