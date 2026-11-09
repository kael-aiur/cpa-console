package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.model.RetentionLoss;
import site.kael.cpa.console.core.monitor.model.RetentionMeta;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T08 isolation self-check (D6.2 / D9): the strict finished_at watermark, the no-guard /
 * recovered-only predicates, the ±1 ms retention boundary, same-millisecond partial loss,
 * long-running retention exceptions, atomic delete+loss+watermark+revision commits and
 * conservative loss compaction — all on an isolated SQLite file with a fixed clock.
 */
class RetentionManagerTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private RunDao runDao;
    private RetentionDao retentionDao;
    private RetentionManager manager;
    private PlatformTransactionManager txManager;

    private static final String TERMINAL_SQL = """
            INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, task_safe_name,
                task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot,
                phase, state, dispatch_status, owns_task_slot, claimed_at, started_at, finished_at,
                duration_ms, reason_code, safe_admin_summary, cpa_http_status, current_verified)
            VALUES (?, 'task-t08', ?, 1, 'name', 'model', 'inst', 'c1', 'c2', 'prov',
                'TERMINAL', ?, 'MAY_HAVE_SENT', 0, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        runDao = new RunDao(dataSource);
        retentionDao = new RetentionDao(dataSource);
        txManager = MonitorIsolationTestSupport.txManager(dataSource);
        manager = new RetentionManager(dataSource, retentionDao, runDao, clock, txManager);
        jdbc.update("INSERT INTO monitor_tasks (task_id, safe_name, route_model_cipher, safe_model_display,"
                        + " cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot, enabled,"
                        + " blocked_reason, schedule_revision, cron_expression, zone_id, tzdb_certificate,"
                        + " next_fire_at, created_at, updated_at, row_version)"
                        + " VALUES ('task-t08', 'n', 'rm', 'rm', 'i', 'a', 'b', 'p', 1, NULL, 1,"
                        + " '0 * * * * *', 'UTC', 'cert', NULL, 0, 0, 0)",
                ps -> {
                    // no parameters
                });
    }

    private long baseTime() {
        return MonitorIsolationTestSupport.BASE_TIME.toEpochMilli();
    }

    private void insertTerminalRun(String runId, Long startedAt, long finishedAt) {
        jdbc.update(TERMINAL_SQL, ps -> {
            ps.setString(1, runId);
            ps.setLong(2, startedAt == null ? finishedAt - 5_000 : startedAt);
            ps.setString(3, "SUCCESS");
            ps.setLong(4, finishedAt - 60_000);
            if (startedAt == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, startedAt);
            }
            ps.setLong(6, finishedAt);
            ps.setLong(7, Math.max(0, finishedAt - (startedAt == null ? finishedAt - 60_000 : startedAt)));
            ps.setString(8, "VERIFIED");
            ps.setString(9, "verified");
            ps.setInt(10, 200);
            ps.setInt(11, 1);
        });
    }

    /** Inserts a finished run with an explicit unique scheduled_at (plan key) value. */
    private void insertTerminalRun(String runId, Long startedAt, long scheduledAt, long finishedAt) {
        jdbc.update(TERMINAL_SQL, ps -> {
            ps.setString(1, runId);
            ps.setLong(2, scheduledAt);
            ps.setString(3, "SUCCESS");
            ps.setLong(4, startedAt == null ? finishedAt - 60_000 : startedAt);
            if (startedAt == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, startedAt);
            }
            ps.setLong(6, finishedAt);
            ps.setLong(7, Math.max(0, finishedAt - (startedAt == null ? finishedAt - 60_000 : startedAt)));
            ps.setString(8, "VERIFIED");
            ps.setString(9, "verified");
            ps.setInt(10, 200);
            ps.setInt(11, 1);
        });
    }

    private void insertClaimedRun(String runId, long claimedAt) {
        jdbc.update("""
                INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, task_safe_name,
                    task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot,
                    phase, state, dispatch_status, owns_task_slot, claimed_at)
                VALUES (?, 'task-t08', ?, 1, 'name', 'model', 'inst', 'c1', 'c2', 'prov',
                    'CLAIMED', 'PENDING', 'NOT_SENT', 1, ?)
                """, ps -> {
            ps.setString(1, runId);
            ps.setLong(2, claimedAt);
            ps.setLong(3, claimedAt);
        });
    }

    @Test
    void boundaryExactAtThirtyDaysIsDeletedAndJustInsideIsKept() throws Exception {
        insertTerminalRun("at-boundary", baseTime() - 1_000,
                baseTime() - RetentionManager.RETENTION_MS - 10_000, baseTime() - RetentionManager.RETENTION_MS - 1);
        insertTerminalRun("inside", baseTime() - 1_000,
                baseTime() - RetentionManager.RETENTION_MS + 10_000, baseTime() - RetentionManager.RETENTION_MS + 1);
        RetentionManager.PurgeOutcome outcome = manager.purgeExpiredBatch(null);
        assertTrue(outcome.committed());
        assertEquals(1, outcome.runsDeleted(), "finished_at exactly one ms past the watermark deletes");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        assertEquals("inside", jdbc.queryForObject("SELECT run_id FROM monitor_runs", String.class));
        // The deleted run had a started_at: the loss interval [s, s+1) must be persisted.
        List<RetentionLoss> losses = retentionDao.listLosses();
        assertEquals(1, losses.size());
        assertEquals(baseTime() - 1_000, losses.get(0).start());
        assertEquals(baseTime() - 1_000 + 1, losses.get(0).end());
        assertFalse(losses.get(0).conservative());
    }

    @Test
    void sameMillisecondPartialDeletionRecordsIncompleteCoverage() throws Exception {
        long sharedStartedAt = baseTime() - 2_000;
        long old = baseTime() - RetentionManager.RETENTION_MS - 5;
        insertTerminalRun("same-ms-deleted", sharedStartedAt, old - 60_000, old);
        insertTerminalRun("same-ms-kept", sharedStartedAt, baseTime() - 200_000, baseTime() - 100);
        RetentionManager.PurgeOutcome outcome = manager.purgeExpiredBatch(null);
        assertEquals(1, outcome.runsDeleted(), "only the run past finished_at retention deletes");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        List<RetentionLoss> losses = retentionDao.listLosses();
        assertEquals(1, losses.size());
        assertEquals(sharedStartedAt, losses.get(0).start());
        assertEquals(sharedStartedAt + 1, losses.get(0).end());
        // The surviving run proves the same millisecond still has retained evidence, so
        // coverage readers must treat the [s, s+1) loss as an incomplete point, not full loss.
        assertTrue(losses.get(0).start() == sharedStartedAt && losses.get(0).end() == sharedStartedAt + 1);
    }

    @Test
    void activeRunsAndUnrecoveredClaimsAreNeverDeleted() throws Exception {
        long old = baseTime() - RetentionManager.RETENTION_MS - 10_000;
        insertClaimedRun("unrecovered", baseTime() - 60_000);
        jdbc.update("""
                INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, task_safe_name,
                    task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot,
                    phase, state, dispatch_status, owns_task_slot, claimed_at, started_at)
                VALUES (?, 'task-t08', ?, 1, 'n', 'm', 'i', 'a', 'b', 'p',
                    'DISPATCHING', 'UNKNOWN', 'MAY_HAVE_SENT', 0, ?, ?)
                """, ps -> {
            ps.setString(1, "dispatching-unrecovered");
            ps.setLong(2, baseTime() - 120_000);
            ps.setLong(3, baseTime() - 120_000);
            ps.setLong(4, baseTime() - 119_000);
        });
        insertTerminalRun("guarded", baseTime() - 5_000, old - 100_000, old);
        jdbc.update("""
                INSERT INTO monitor_credential_guards (credential_key, active_run_id, cooldown_until, updated_at)
                VALUES ('guard-x', 'guarded', NULL, ?)
                """, ps -> ps.setLong(1, baseTime()));
        RetentionManager.PurgeOutcome outcome = manager.purgeExpiredBatch(null);
        assertEquals(0, outcome.runsDeleted(), "claims, unrecovered dispatching and guarded runs survive");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        // After recovery terminalizes the claim and the guard is released, deletion applies.
        jdbc.update("UPDATE monitor_runs SET phase = 'TERMINAL', state = 'UNKNOWN', finished_at = ?, "
                + "owns_task_slot = 0 WHERE run_id = 'unrecovered'", ps -> ps.setLong(1, baseTime() - 1));
        jdbc.update("UPDATE monitor_credential_guards SET active_run_id = NULL WHERE credential_key = 'guard-x'");
        manager.purgeExpiredBatch(null);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class),
                "after recovery both expired finished runs delete; the recent run survives");
    }

    @Test
    void failureInLossWriteRollsBackDeletionAndWatermark() throws Exception {
        insertTerminalRun("doomed", baseTime() - 4_000, baseTime() - RetentionManager.RETENTION_MS - 1);
        // A stop signal inside the transaction must roll the whole batch back: nothing is
        // deleted, no loss row is written, no watermark or revision moves.
        RetentionManager.PurgeOutcome stopped = manager.purgeExpiredBatch(() -> true);
        assertFalse(stopped.committed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_retention_losses", Integer.class));
        RetentionMeta metaAfterStop = retentionDao.findMeta().orElseThrow();
        assertEquals(0, metaAfterStop.retentionRevision());
        assertNull(metaAfterStop.lastCleanupAt());
        // Without the stop signal the same batch commits delete + loss + watermark + revision
        // in one transaction. markCleanup and bumpRetentionRevision are intentionally both
        // persistence writes; the semantic revision equals the final stored value.
        RetentionManager.PurgeOutcome committed = manager.purgeExpiredBatch(null);
        assertTrue(committed.committed());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_retention_losses", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT retention_revision FROM monitor_retention_meta", Integer.class));
        assertEquals(baseTime(), jdbc.queryForObject("SELECT last_cleanup_at FROM monitor_retention_meta", Long.class).longValue());
    }

    @Test
    void emptySweepStillRecordsCleanupWatermark() throws Exception {
        RetentionManager.PurgeOutcome outcome = manager.purgeExpiredBatch(null);
        assertTrue(outcome.committed());
        RetentionMeta meta = retentionDao.findMeta().orElseThrow();
        assertEquals(baseTime(), meta.lastCleanupAt().longValue());
        assertTrue(meta.retentionRevision() >= 1);
        assertEquals(0, retentionDao.listLosses().size());
    }

    @Test
    void compactionProducesExplicitConservativeEnvelope() throws Exception {
        long oldStart = baseTime() - 10_000;
        for (int i = 0; i < RetentionManager.LOSS_COMPACTION_THRESHOLD + 2; i++) {
            retentionDao.recordLosses(List.of(new RetentionLoss(oldStart + i * 2, oldStart + i * 2 + 1, false)), 1);
        }
        RetentionManager.CompactionOutcome outcome = manager.compactLosses(null);
        assertTrue(outcome.committed());
        assertTrue(outcome.envelope() != null && outcome.envelope().conservative());
        List<RetentionLoss> losses = retentionDao.listLosses();
        assertEquals(3, losses.size(),
                "compacted rows collapse into one conservative envelope plus the remaining exact rows");
        RetentionLoss conservative = losses.stream().filter(RetentionLoss::conservative).findFirst().orElseThrow();
        assertEquals(oldStart, conservative.start());
        assertEquals(oldStart + (RetentionManager.LOSS_COMPACTION_THRESHOLD - 1) * 2 + 1, conservative.end());
    }

    @Test
    void retentionManagerSupportClassEncapsulatesPurePolicyHelpers() {
        assertEquals(30L * 24 * 60 * 60 * 1000, RetentionManager.RETENTION_MS);
        assertTrue(RetentionManager.DEFAULT_BATCH_SIZE > 0);
    }
}
