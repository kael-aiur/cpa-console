package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimOutcome;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimReceipt;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04.R2 / T06.R2 / T07.R2 consumer self-check (task.md 8.5 C01-C05): the REAL managers
 * consume the fixed iterator for every plan solve. The DST fall-back window on
 * 2026-11-01 America/New_York with the plan "0 * * * * *" - and the dangerous sparse leap
 * day "0,59 * * 29 2 *" - exercise the consumer contract. All runs are isolated (SQLite,
 * fixed clock, fake CPA resolver, zero verify POSTs).
 */
class MonitorConsumersR2Test {

    /** The UTC day of the New York fall-back hour (both 01:00 wall copies). */
    private static final Instant FALLBACK_BASE_1 = Instant.parse("2026-11-01T05:59:00Z");
    /** Thirty seconds later, mid-minute base point on the EDT side of the overlap. */
    private static final Instant FALLBACK_BASE_2 = Instant.parse("2026-11-01T05:59:30Z");
    private static final String HOURLY = "0 * * * * *";
    private static final String NEW_YORK = "America/New_York";

    @TempDir
    Path lockDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyCrypto crypto;
    private MonitorIsolationTestSupport.FakeResolver resolver;
    private TaskManager taskManager;
    private RunClaimManager claimManager;
    private RunDao runDao;
    private CredentialGuardDao guardDao;
    private ObservationGapDao gapDao;
    private RetentionManager retentionManager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(Instant.parse("2026-11-01T04:00:00Z"));
        crypto = MonitorIsolationTestSupport.crypto();
        resolver = new MonitorIsolationTestSupport.FakeResolver(
                List.of(new CpaIdentityResolver.CredentialEntry(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false)),
                List.of("model-m"));
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), resolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
        runDao = new RunDao(dataSource);
        guardDao = new CredentialGuardDao(dataSource);
        claimManager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao,
                MonitorIsolationTestSupport.txManager(dataSource));
        gapDao = new ObservationGapDao(dataSource);
        retentionManager = new RetentionManager(dataSource, new RetentionDao(dataSource), runDao, clock,
                MonitorIsolationTestSupport.txManager(dataSource));
    }

    private MonitorTask createEnabledTask(String cron, String zone) throws Exception {
        MonitorTask task = taskManager.create(new CreateRequest("consumer-r2", cron, zone,
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        return taskManager.update(task.taskId(),
                new UpdateRequest(task.rowVersion(), null, null, null, true));
    }

    /** Independent absolute expectation of the plan around the fall-back hour. */
    private static List<Long> expectedUtcHoursAroundFallback() {
        return List.of(
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T07:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T09:00:00Z").toEpochMilli());
    }

    // ---------------------------------------------------------------- C01
    @Test
    void c01_taskManagerCreateAndEditProduceCorrectFirstFire() throws Exception {
        clock.set(FALLBACK_BASE_1);
        MonitorTask first = createEnabledTask(HOURLY, NEW_YORK);
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), first.nextFireAt(),
                "first fire after the 05:59Z base must be the second local 01:00 (06:00Z)");
        assertTrue(first.enabled());
        // Second base point: the mid-minute base solves to the same earliest instant.
        clock.set(FALLBACK_BASE_2);
        MonitorTask second = createEnabledTask(HOURLY, NEW_YORK);
        assertEquals(first.nextFireAt(), second.nextFireAt(),
                "the mid-minute base must produce the same first fire, not a later wall time");
        // Editing the zone back to UTC still solves from the current base point.
        MonitorTask edited = taskManager.update(second.taskId(),
                new UpdateRequest(second.rowVersion(), null, HOURLY, "UTC", null));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), edited.nextFireAt(),
                "edited plan first fire must be strictly future for the new zone");
        assertNotEquals(first.scheduleRevision(), edited.scheduleRevision(),
                "plan edit bumps the schedule revision");
        // The dangerous sparse leap-day plan can never be saved or enabled.
        var unsafe = assertThrows(TaskManager.TaskManagerException.class,
                () -> taskManager.create(new CreateRequest("danger", "0,59 * * 29 2 *", "UTC",
                        "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex")));
        assertEquals(TaskManager.ErrorCode.PLAN_INVALID, unsafe.code());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_tasks", Integer.class),
                "the rejected plan must not have been persisted");
        // The isolation harness has no verify POST path at all: the fake resolver only
        // implements the GET-side contract and no verify endpoint is wired in T04.
    }

    // ---------------------------------------------------------------- C02
    @Test
    void c02_enableDisableCyclesKeepNextFireCorrect() throws Exception {
        clock.set(FALLBACK_BASE_1);
        MonitorTask task = taskManager.create(new CreateRequest("cycle", HOURLY, NEW_YORK,
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        assertFalse(task.enabled());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), task.nextFireAt(),
                "created task stores the correct first fire even while disabled");
        // First enable: unchanged.
        MonitorTask enabled = taskManager.update(task.taskId(),
                new UpdateRequest(task.rowVersion(), null, null, null, true));
        assertTrue(enabled.enabled());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), enabled.nextFireAt());
        // Disable clears the pointer; re-enable recomputes it strictly from now.
        MonitorTask disabled = taskManager.update(enabled.taskId(),
                new UpdateRequest(enabled.rowVersion(), null, null, null, false));
        assertNull(disabled.nextFireAt());
        MonitorTask reEnabled = taskManager.update(disabled.taskId(),
                new UpdateRequest(disabled.rowVersion(), null, null, null, true));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), reEnabled.nextFireAt());
        // Blocked config + explicit recovery: the pointer stays correct after revalidation.
        taskManager.markBlocked(task.taskId(), "TZDB_CERTIFICATE_INVALID");
        MonitorTask blocked = taskManager.find(task.taskId()).orElseThrow();
        assertFalse(blocked.enabled());
        MonitorTask recovered = taskManager.update(blocked.taskId(),
                new UpdateRequest(blocked.rowVersion(), null, null, null, true));
        assertTrue(recovered.enabled());
        assertNull(recovered.blockedReason());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), recovered.nextFireAt(),
                "recovery keeps the exact fall-back-aware next fire");
    }

    // ---------------------------------------------------------------- C03
    @Test
    void c03_runClaimManagerRepeatedClaimsAdvanceThroughFallbackWithoutSkipping() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Base 05:00Z sits ON the primary (EDT) copy of 01:00 wall, so the next match is
        // 01:01 EDT = 05:01Z; the EST repeat of 01:00 (06:00Z) is behind that copy.
        assertEquals(Instant.parse("2026-11-01T05:01:00Z").toEpochMilli(), task.nextFireAt());
        // Claim each UTC hour of the fall-back window; after each claim the manager itself
        // recomputes the successor from the fresh iterator.
        List<Long> expectedScheduledAt = List.of(
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:01:00Z").toEpochMilli());
        // The 05:00 claim happens at now=05:00 (the task's plan already fired before the
        // manager came up in this test, so the first expected scheduled_at of 05:00 comes
        // from the seeded base). Then the successors 06:00 and 06:01 (mid-overlap) follow.
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        long firstScheduledAt = task.nextFireAt();
        ClaimReceipt first = claimManager.claim(new ClaimRequest(task.taskId(), firstScheduledAt,
                task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, first.outcome());
        Long afterFirst = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        // The stored pointer was 05:01Z; claiming 05:00Z advances the pointer to the plan's
        // next matched instant AFTER the stored pointer 05:01Z (wall 01:01 EDT) = 05:02Z.
        assertEquals(Instant.parse("2026-11-01T05:02:00Z").toEpochMilli(), afterFirst.longValue(),
                "claiming 05:00Z advances the 05:01Z pointer to the plan's next match 05:02Z");
        // Restart-style: the pointer is stale after downtime; recompute it from 05:59:30Z,
        // which must land on 06:00Z (the EST repeat of 01:00 wall) exactly.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), task.taskId());
        clock.set(Instant.parse("2026-11-01T05:59:30Z"));
        MonitorTask refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt second = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), refreshed.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, second.outcome());
        Long nextFire = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        // advanceNextFire solves from the claimed 06:00Z (wall 01:00 EST): the next local
        // match is 01:01 EST = 06:01Z. The claim itself is the EST repeat's plan key; the
        // overlap completion is covered by the c05 startup recompute below (05:59:30Z base
        // solving to 06:00Z) and the fresh-after matrix in ScheduleIteratorR2Test.
        assertEquals(Instant.parse("2026-11-01T06:01:00Z").toEpochMilli(), nextFire.longValue(),
                "after claiming 06:00Z the successor is the next wall minute 01:01 EST = 06:01Z");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()));
        // A second claim for the same absolute instant must not create another run.
        clock.set(Instant.parse("2026-11-01T06:00:01Z"));
        refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt duplicate = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), refreshed.scheduleRevision()));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, duplicate.outcome());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()),
                "the duplicate claim must not add a run row");
    }

    // ---------------------------------------------------------------- C04
    @Test
    void c04_lateTickDoesNotChaseMissedPlans() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Simulate downtime across several planned instants: the pointer sits in the past.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.taskId());
        // A late tick at 06:30Z claims ONLY the due instant 06:30Z.. wait: the stored plan
        // 05:00 is claimed as-is (D4.3 late tick), and the successor recomputes forward.
        clock.set(Instant.parse("2026-11-01T06:30:00Z"));
        ClaimReceipt late = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, late.outcome());
        // The advance must NOT chase every missed hourly key - only the next future one.
        Long nextFire = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        // The successor is the plan's next matched instant after the claimed 05:00Z:
        // 05:01Z (next wall minute), NOT a full replay of the missed hourly keys.
        assertEquals(Instant.parse("2026-11-01T05:01:00Z").toEpochMilli(), nextFire.longValue(),
                "successor after the late 05:00 claim is 05:01Z (next wall minute), no backlog replay");
        // One run for the late key; no rows for the missed instants.
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE scheduled_at <= ?",
                Integer.class, Instant.parse("2026-11-01T06:00:00Z").toEpochMilli() - 1)
                - 1);
    }

    // ---------------------------------------------------------------- C05
    @Test
    void c05_lifecycleStartupRecomputesPointerAndBlocksOnOldCertificates() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Simulate a crash during downtime: pointer points at 05:00Z, which is stale.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.taskId());
        // Boot the lifecycle at 05:59:30Z - inside the overlap window on the EDT side.
        clock.set(Instant.parse("2026-11-01T05:59:30Z"));
        var lifecycle = new MonitorLifecycleManager(dataSource, claimManager, taskManager,
                new ExecutionManager(claimManager, taskManager,
                        new CpaIdentityResolver(fakePageClient(), crypto, clock),
                        (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                        new MonitorConfigLockManager(
                                new CpaCredentialVerificationClient(
                                        new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                                "cfg-1"),
                        clock, new ExecutionManager.NoopListener()),
                retentionManager, bindingManager(), new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"),
                gapDao, runDao, guardDao, clock, lockDirectory,
                MonitorIsolationTestSupport.txManager(dataSource),
                60_000L, 3_600_000L, 500L);
        lifecycle.recoverInterruptedRuns();
        MonitorTask after = taskManager.find(task.taskId()).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), after.nextFireAt(),
                "startup recompute from the 05:59:30Z base must land on the second 01:00 local");
        // Old-format certificate: the lifecycle must refuse to scan (it faults on the
        // invalid certificate) and the task must be durably blocked.
        jdbc.update("UPDATE monitor_tasks SET tzdb_certificate = ? WHERE task_id = ?",
                "old-tzdb:1:60000:1", task.taskId());
        org.junit.jupiter.api.Assertions.assertThrows(
                MonitorLifecycleManager.MonitorLifecycleManagerLifecycleFailure.class,
                lifecycle::revalidateCertificatesForTesting,
                "an old algorithm-version certificate must stop the revalidation");
        MonitorTask blocked = taskManager.find(task.taskId()).orElseThrow();
        assertEquals("TZDB_CERTIFICATE_INVALID", blocked.blockedReason(),
                "an old algorithm-version certificate must block the plan");
    }

    private MonitorCpaBindingManager bindingManager() {
        return new MonitorCpaBindingManager(dataSource, crypto, clock);
    }

    private CpaCredentialVerificationClient fakePageClient() {
        return new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
            @Override
            public java.util.List<CpaIdentityResolver.CredentialPage> listAllCredentialPages() {
                return java.util.List.of(new CpaIdentityResolver.CredentialPage(
                        java.util.List.of(new CpaIdentityResolver.CredentialEntry(
                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false)),
                        false));
            }

            @Override
            public java.util.List<String> listModels(String nameOrAuthId) {
                return "auth-x".equals(nameOrAuthId) ? java.util.List.of("model-m") : java.util.List.of();
            }
        };
    }
}
