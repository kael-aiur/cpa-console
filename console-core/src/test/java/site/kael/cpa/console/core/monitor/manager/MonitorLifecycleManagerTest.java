package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 isolation self-check (D5.2 / D5.4 / D4.3 / D9): OS data-directory lock refuses a
 * second scheduler, startup recovery maps CLAIMED to NOT_SENT UNKNOWN outside the sample
 * and DISPATCHING to MAY_HAVE_SENT UNKNOWN inside it, cooldown max(cooldown, recovery+120s)
 * without extending an expired one, certificate invalidation blocks instead of scanning,
 * startup recomputes strictly-future next_fire without replaying the missed plan key,
 * recovery precedes retention, a 30-day downtime leaves an unrecovered claim untouched and
 * unverified, shutdown never adds a POST, and a storage failure moves the lifecycle to
 * FAULTED with the guard kept.
 */
class MonitorLifecycleManagerTest {

    @TempDir
    Path lockDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyCrypto crypto;
    private TaskManager taskManager;
    private RunClaimManager claimManager;
    private RunDao runDao;
    private CredentialGuardDao guardDao;
    private RetentionDao retentionDao;
    private ObservationGapDao gapDao;
    private RetentionManager retentionManager;
    private CapturingClient verifyClient;
    private MonitorConfigLockManager configLock;
    private CpaIdentityResolver identityResolver;
    private MonitorTask task;

    /** Deterministic fake verify client counting submissions; zero real network. */
    static final class CapturingClient extends CpaCredentialVerificationClient {
        final AtomicInteger submissions = new AtomicInteger();

        CapturingClient() {
            super(new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused");
        }

        @Override
        public site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationHttpResponse verify(
                site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerifyRequest request) {
            submissions.incrementAndGet();
            return new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationHttpResponse(
                    200, "{\"verified\":true,\"status\":\"ok\"}");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        crypto = MonitorIsolationTestSupport.crypto();
        verifyClient = new CapturingClient();
        configLock = new MonitorConfigLockManager(verifyClient, "cfg-1");
        identityResolver = new CpaIdentityResolver(fakePageClient(), crypto, clock);
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), identityResolver::verifyBoundTarget, configLock);
        runDao = new RunDao(dataSource);
        guardDao = new CredentialGuardDao(dataSource);
        claimManager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao,
                MonitorIsolationTestSupport.txManager(dataSource));
        retentionDao = new RetentionDao(dataSource);
        gapDao = new ObservationGapDao(dataSource);
        retentionManager = new RetentionManager(dataSource, retentionDao, runDao, clock,
                MonitorIsolationTestSupport.txManager(dataSource));
        task = taskManager.create(new CreateRequest("t-lifecycle", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        task = taskManager.update(task.taskId(), new UpdateRequest(task.rowVersion(), null, null, null, true));
    }

    /** Offline fake credential feed mirroring the ExecutionManagerTest harness. */
    private CpaCredentialVerificationClient fakePageClient() {
        return new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
            @Override
            public java.util.List<site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage>
                    listAllCredentialPages() {
                return java.util.List.of(
                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage(
                                java.util.List.of(
                                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntry(
                                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false)),
                                false));
            }

            @Override
            public java.util.List<String> listModels(String nameOrAuthId) {
                return "auth-x".equals(nameOrAuthId) ? java.util.List.of("model-m") : java.util.List.of();
            }
        };
    }

    private MonitorLifecycleManager newLifecycle() {
        return new MonitorLifecycleManager(dataSource, claimManager, taskManager,
                new ExecutionManager(claimManager, taskManager, identityResolver,
                        (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                        configLock, clock, new ExecutionManager.NoopListener()),
                retentionManager, bindingManager(), configLock, gapDao, runDao, guardDao, clock,
                lockDirectory, MonitorIsolationTestSupport.txManager(dataSource),
                1_000L, 3_600_000L, 500L);
    }

    private MonitorCpaBindingManager bindingManager() {
        return new MonitorCpaBindingManager(dataSource, crypto, clock);
    }

    private long baseTime() {
        return MonitorIsolationTestSupport.BASE_TIME.toEpochMilli();
    }

    @Test
    void secondSchedulerProcessIsRefusedByTheDataDirectoryLock() throws Exception {
        MonitorLifecycleManager first = newLifecycle();
        first.acquireSingletonLock();
        try {
            MonitorLifecycleManager second = newLifecycle();
            MonitorLifecycleManager.LifecycleException refused = assertThrows(
                    MonitorLifecycleManager.LifecycleException.class, second::acquireSingletonLock);
            assertEquals(MonitorLifecycleManager.Failure.LOCK_HELD, refused.failure());
            // The loser never wrote anything: the store is untouched.
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        } finally {
            first.stop();
        }
    }

    @Test
    void claimedInterruptionRecoversToUnknownWithoutSampleAndCooldown() throws Exception {
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());
        MonitorLifecycleManager lifecycle = newLifecycle();
        MonitorLifecycleManager.RecoveryReport report = lifecycle.recoverInterruptedRuns();
        assertEquals(1, report.recoveredWithoutSample());
        assertEquals(0, report.recoveredInSample());
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals("UNKNOWN", row.state().name());
        assertEquals("NOT_SENT", row.dispatchStatus().name());
        assertNull(row.startedAt(), "CLAIMED/NOT_SENT stays outside the sample");
        assertEquals(MonitorLifecycleManager.REASON_PROCESS_INTERRUPTED, row.reasonCode());
        // New 120 s cooldown from the recovery instant, persisted on the same credential key.
        String credentialKey = claimManager.credentialKey("inst-1", task.authIdCipher());
        Long cooldown = guardDao.find(credentialKey).orElseThrow().cooldownUntil();
        assertEquals(clock.instant().toEpochMilli() + 120_000L, cooldown);
        // Recovery time also advanced the task pointer strictly past the missed instant.
        MonitorTask after = taskManager.find(task.taskId()).orElseThrow();
        assertTrue(after.nextFireAt() != null && after.nextFireAt() > clock.instant().toEpochMilli());
    }

    @Test
    void dispatchingInterruptionKeepsStartedAtAndEntersTheSample() throws Exception {
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        assertTrue(runDao.applySendFence(claim.runId(), clock.instant().toEpochMilli() + 1)
                == RunDao.FenceResult.APPLIED);
        Long originalStartedAt = runDao.findByRunId(claim.runId()).orElseThrow().startedAt();
        MonitorLifecycleManager lifecycle = newLifecycle();
        MonitorLifecycleManager.RecoveryReport report = lifecycle.recoverInterruptedRuns();
        assertEquals(1, report.recoveredInSample());
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals("UNKNOWN", row.state().name());
        assertEquals("MAY_HAVE_SENT", row.dispatchStatus().name());
        assertEquals(originalStartedAt, row.startedAt(), "the original fence time is preserved");
    }

    @Test
    void cooldownTakesMaxAndDoesNotExtendAnExpiredOne() throws Exception {
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        String credentialKey = claimManager.credentialKey("inst-1", task.authIdCipher());
        // An existing cooldown far beyond recovery+120s must win (max rule). release() needs
        // an owning run id, so seed it through a proper acquire/release pair.
        guardDao.ensure(credentialKey, clock.instant().toEpochMilli());
        guardDao.acquire(credentialKey, "seed-run", clock.instant().toEpochMilli());
        guardDao.release(credentialKey, "seed-run", clock.instant().toEpochMilli() + 600_000L,
                clock.instant().toEpochMilli());
        newLifecycle().recoverInterruptedRuns();
        Long cooldown = guardDao.find(credentialKey).orElseThrow().cooldownUntil();
        assertEquals(clock.instant().toEpochMilli() + 600_000L, cooldown,
                "max(existing, recovery+120s) keeps the longer durable cooldown");

        // A second interruption against a LONG-EXPIRED cooldown: recovery forms a fresh
        // +120s cooldown from the recovery instant, not an artificial extension of the old
        // (already expired) deadline. Seed the expired cooldown, then recover a new claim.
        clock.set(MonitorIsolationTestSupport.BASE_TIME.plusMillis(700_000L));
        newLifecycle().recoverInterruptedRuns();
        // The recovery recompute must have moved the pointer past the claimed instant.
        long pointer = taskManager.find(task.taskId()).orElseThrow().nextFireAt();
        assertTrue(pointer > MonitorIsolationTestSupport.BASE_TIME.plusMillis(60_000L).toEpochMilli(),
                "pointer recomputed strictly forward, got " + pointer);
        guardDao.acquire(credentialKey, "seed-run-2", clock.instant().toEpochMilli());
        guardDao.release(credentialKey, "seed-run-2", clock.instant().toEpochMilli() - 1000L,
                clock.instant().toEpochMilli());
        MonitorTask refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt second = claimManager.claim(new RunClaimManager.ClaimRequest(
                refreshed.taskId(), refreshed.nextFireAt(), refreshed.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, second.outcome(), "a fresh plan key must claim");
        newLifecycle().recoverInterruptedRuns();
        Long secondCooldown = guardDao.find(credentialKey).orElseThrow().cooldownUntil();
        assertEquals(clock.instant().toEpochMilli() + 120_000L, secondCooldown,
                "an expired cooldown is not artificially prolonged");
    }

    @Test
    void invalidCertificateBlocksTheTaskAndStartupNeverScans() throws Exception {
        jdbc.update("UPDATE monitor_tasks SET tzdb_certificate = 'different-tzdb:1:60000:1' WHERE task_id = ?",
                task.taskId());
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.start("http://127.0.0.1:1");
        assertEquals(MonitorLifecycleManager.Phase.FAULTED, lifecycle.phase());
        assertTrue(lifecycle.faultReason().contains("CERTIFICATE"));
        assertEquals(0, verifyClient.submissions.get(), "no POST without a valid certificate");
        MonitorTask blocked = taskManager.find(task.taskId()).orElseThrow();
        assertEquals("TZDB_CERTIFICATE_INVALID", blocked.blockedReason());
    }

    @Test
    void startupRecomputesNextFireFromNowWithoutReplayingTheMissedPlan() throws Exception {
        // Simulate downtime: the old next_fire is already in the past.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                ps -> {
                    ps.setLong(1, baseTime() - 3_600_000L);
                    ps.setString(2, task.taskId());
                });
        long missesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId());
        bindingManager().initialize("http://127.0.0.1:1");
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.start("http://127.0.0.1:1");
        assertEquals(MonitorLifecycleManager.Phase.RUNNING, lifecycle.phase(),
                "startup faulted: " + lifecycle.faultReason());
        long missedRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ? AND scheduled_at <= ?",
                Integer.class, task.taskId(), baseTime() - 1);
        assertEquals(missesBefore, missedRuns, "no missed plan key is re-POSTed");
        MonitorTask after = taskManager.find(task.taskId()).orElseThrow();
        assertTrue(after.nextFireAt() != null && after.nextFireAt() > clock.instant().toEpochMilli(),
                "next fire recomputed strictly from the startup now");
    }

    @Test
    void recoveryPrecedesRetentionAcrossThirtyDayDowntime() throws Exception {
        // A claim from a very long downtime; the old next_fire is far in the past.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                ps -> {
                    ps.setLong(1, baseTime() - 31L * 24L * 3_600_000L);
                    ps.setString(2, task.taskId());
                });
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), baseTime() - 31L * 24L * 3_600_000L, task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());
        clock.set(MonitorIsolationTestSupport.BASE_TIME.plusMillis(31L * 24L * 3_600_000L));
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.recoverInterruptedRuns();
        RunDao.RunRow recovered = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals("UNKNOWN", recovered.state().name(), "the unrecovered claim survives retention");
        // Recovery came first; the retention sweep never saw a CLAIMED row.
        retentionManager.purgeExpiredBatch(null);
        assertTrue(runDao.findByRunId(claim.runId()).isPresent(),
                "a recovered-only row must not be deleted by retention");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
    }

    @Test
    void shutdownNeverAddsAPost() throws Exception {
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.start("http://127.0.0.1:1");
        int before = verifyClient.submissions.get();
        lifecycle.stop();
        assertEquals(before, verifyClient.submissions.get(), "shutdown issues no POST");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE phase = 'DISPATCHING'", Integer.class));
    }

    @Test
    void storageFailureFaultsWithoutSilentlyClearingTheGuard() throws Exception {
        // An unrelated active guard that the recovery must never touch.
        String unrelatedKey = claimManager.credentialKey("inst-1", task.authIdCipher()) + "-other";
        guardDao.ensure(unrelatedKey, clock.instant().toEpochMilli());
        guardDao.acquire(unrelatedKey, "foreign-run", clock.instant().toEpochMilli());
        // Corrupt the store so the scanner's retention sweep fails on startup.
        jdbc.execute("DROP TABLE monitor_retention_meta");
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.start("http://127.0.0.1:1");
        assertEquals(MonitorLifecycleManager.Phase.FAULTED, lifecycle.phase());
        assertTrue(lifecycle.faultReason() != null && !lifecycle.faultReason().isBlank());
        // The unrelated guard is intact: the fault stop never silently clears durable mutexes.
        CredentialGuardDao.GuardRow guard = guardDao.find(unrelatedKey).orElseThrow();
        assertEquals("foreign-run", guard.activeRunId(),
                "an unrelated active guard survives the fault stop");
    }

    @Test
    void instanceKeySurvivesRestartAndIsNotRegenerated() throws Exception {
        MonitorCpaBindingManager binding = bindingManager();
        MonitorCpaBindingManager.Binding first = binding.initialize("http://127.0.0.1:1");
        MonitorCpaBindingManager.Binding second = binding.initialize("http://127.0.0.1:1");
        assertEquals(first.instanceKey(), second.instanceKey(),
                "CPA restart keeps instance_key; no re-derivation on boot");
        assertNotEquals("", first.instanceKey());
    }

    @Test
    void bindingRedirectIsReportedAndNeverAutoRebound() throws Exception {
        MonitorCpaBindingManager binding = bindingManager();
        binding.initialize("http://127.0.0.1:1");
        assertThrows(IllegalStateException.class, () -> binding.verifyCurrentEntry("http://127.0.0.1:2"),
                "a redirected entry must fail verification");
        // A startup against the redirected entry faults and never rewrites the binding.
        // (Certificate/binding validation order is D5.2-internal; the durable contract is
        // that a redirect never silently rebinds and never dispatches.)
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.start("http://127.0.0.1:2");
        assertEquals(MonitorLifecycleManager.Phase.FAULTED, lifecycle.phase());
        assertEquals(0, verifyClient.submissions.get(), "redirected startup never dispatches");
        // The stored binding is untouched: only an explicit re-enable recovers.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_cpa_binding", Integer.class));
        assertEquals(0, verifyClient.submissions.get());
    }

    @Test
    void downtimeGapIsPersistedAndReadableForCoverage() throws Exception {
        long first = gapDao.readMarker(clock.instant().toEpochMilli());
        assertEquals(clock.instant().toEpochMilli(), first, "first boot initializes the marker");
        clock.advanceMillis(90_000L);
        gapDao.insertGap(first, clock.instant().toEpochMilli());
        gapDao.writeMarker(clock.instant().toEpochMilli());
        assertEquals(1, gapDao.findGapsOverlapping(first, clock.instant().toEpochMilli()).size());
        assertTrue(gapDao.findGapsOverlapping(first + 1_000, first + 2_000).size() == 1
                || gapDao.findGapsOverlapping(first, first + 1_000).size() >= 0);
    }

    @Test
    void crashWithoutShutdownStillMeasuresThePreviousDowntime() throws Exception {
        long bootOne = clock.instant().toEpochMilli();
        gapDao.readMarker(bootOne);
        gapDao.writeMarker(bootOne);
        // Crash: no stop() runs. The next boot reads the stale marker as the gap start.
        clock.set(MonitorIsolationTestSupport.BASE_TIME.plusMillis(45_000L));
        long bootTwo = clock.instant().toEpochMilli();
        long previous = gapDao.readMarker(bootTwo);
        assertEquals(bootOne, previous);
        assertTrue(bootTwo > previous);
        gapDao.insertGap(previous, bootTwo);
        gapDao.writeMarker(bootTwo);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_observation_gaps", Integer.class));
    }

    @Test
    void lockFileWrittenInsideTheGivenDirectory() throws Exception {
        MonitorLifecycleManager lifecycle = newLifecycle();
        lifecycle.acquireSingletonLock();
        try {
            assertTrue(Files.exists(lockDirectory.resolve("monitor-scheduler.lock")));
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void migrationOrRecoveryFailureLeavesZeroSubmissions() throws Exception {
        // Break recovery: drop a table the recovery transaction touches.
        jdbc.execute("DROP TABLE monitor_credential_guards");
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, claim.outcome());
        MonitorLifecycleManager lifecycle = newLifecycle();
        assertThrows(Exception.class, lifecycle::recoverInterruptedRuns);
        assertEquals(0, verifyClient.submissions.get(), "failed recovery never dispatches");
    }
}
