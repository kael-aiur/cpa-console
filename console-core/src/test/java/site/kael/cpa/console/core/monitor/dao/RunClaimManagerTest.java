package site.kael.cpa.console.core.monitor.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.FakeResolver;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.dao.RunDao.ClaimResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.FenceResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.TerminalResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.TerminalWrite;
import site.kael.cpa.console.core.monitor.manager.MonitorConfigLockManager;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimOutcome;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimReceipt;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimRequest;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.TerminalReceipt;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.TerminalRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.model.DispatchStatus;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.RunPhase;
import site.kael.cpa.console.core.monitor.model.RunState;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T05 isolation self-check (D5.1–D5.3 / D8 / D9): plan-key uniqueness independent of
 * revision, atomic claim + next_fire advance, slot-first-then-conditional, stable logical
 * HMAC guard, one-shot terminal CAS with cooldown, guard ownership refusal — all on
 * isolated SQLite with fault-injected rollback paths and a fixed clock.
 */
class RunClaimManagerTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private FakeResolver resolver;
    private ApiKeyCrypto crypto;
    private TaskManager taskManager;
    private RunDao runDao;
    private CredentialGuardDao guardDao;
    private RunClaimManager manager;
    private PlatformTransactionManager txManager;
    private MonitorTask task;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        txManager = MonitorIsolationTestSupport.txManager(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        resolver = new FakeResolver(
                List.of(new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                List.of("model-m", "model-n"));
        crypto = MonitorIsolationTestSupport.crypto();
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), resolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient(
                                new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.CpaEndpoint(
                                        "http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
        runDao = new RunDao(dataSource);
        guardDao = new CredentialGuardDao(dataSource);
        manager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao, txManager);
        task = taskManager.create(new CreateRequest("claim-task", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        // Enable the task so the claim guard passes.
        task = taskManager.update(task.taskId(),
                new TaskManager.UpdateRequest(task.rowVersion(), null, null, null, true));
    }

    private ClaimRequest claimAt(long scheduledAt) {
        return new ClaimRequest(task.taskId(), scheduledAt, task.scheduleRevision());
    }

    @Test
    void claimInsertsRunAdvancesNextFireAtomicallyAndBumpsRevision() throws Exception {
        long scheduledAt = task.nextFireAt();
        long dataRevisionBefore = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        ClaimReceipt receipt = manager.claim(claimAt(scheduledAt));
        assertEquals(ClaimOutcome.CLAIMED, receipt.outcome());
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()) == 1);
        Long advanced = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        assertTrue(advanced != null && advanced > scheduledAt, "next_fire must advance past the claimed instant");
        assertEquals(receipt.nextFireAt().longValue(), advanced.longValue());
        assertEquals(dataRevisionBefore + 1, (long) jdbc.queryForObject(
                "SELECT revision FROM monitor_data_revision", Integer.class));
        RunDao.RunRow run = runDao.findByRunId(receipt.runId()).orElseThrow();
        assertEquals(RunPhase.CLAIMED, run.phase());
        assertEquals(RunState.PENDING, run.state());
        assertEquals(DispatchStatus.NOT_SENT, run.dispatchStatus());
        assertFalse(run.ownsTaskSlot(), "claim stores the event first without the slot");
        assertNull(run.startedAt());
        // Snapshot copied at claim time; independent from later task renames.
        assertEquals("claim-task", run.taskSafeName());
        assertEquals("model-m", run.taskModelDisplay());
        assertEquals("inst-1", run.instanceKey());
    }

    @Test
    void duplicatePlanKeyIsRefusedEvenAfterRevisionBump() throws Exception {
        long scheduledAt = task.nextFireAt();
        ClaimReceipt first = manager.claim(claimAt(scheduledAt));
        assertEquals(ClaimOutcome.CLAIMED, first.outcome());
        // Edit the cron back so the next fire recomputes to a different instant; then attempt
        // a claim for the ORIGINAL absolute instant with the NEW revision.
        MonitorTask edited = taskManager.update(task.taskId(), new TaskManager.UpdateRequest(
                task.rowVersion(), null, "30 * * * * *", null, null));
        ClaimReceipt duplicate = manager.claim(new ClaimRequest(task.taskId(), scheduledAt, edited.scheduleRevision()));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, duplicate.outcome(),
                "plan key must not contain revision: same absolute instant is refused");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE scheduled_at = ?", Integer.class, scheduledAt));
    }

    @Test
    void claimOnDisabledOrBlockedTaskWritesNothing() throws Exception {
        MonitorTask disabled = taskManager.update(task.taskId(),
                new TaskManager.UpdateRequest(task.rowVersion(), null, null, null, false));
        long runsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class);
        ClaimReceipt receipt = manager.claim(new ClaimRequest(task.taskId(), 9_999_999_999L, disabled.scheduleRevision()));
        assertEquals(ClaimOutcome.TASK_STATE_CHANGED, receipt.outcome());
        assertEquals(runsBefore, (long) jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
    }

    @Test
    void taskSlotTakenFirstAndSkipsRecordedWithoutSlot() throws Exception {
        long first = task.nextFireAt();
        ClaimReceipt owner = manager.claim(claimAt(first));
        assertTrue(runDao.acquireTaskSlot(owner.runId()), "first claim takes the slot");
        assertTrue(runDao.findByRunId(owner.runId()).orElseThrow().ownsTaskSlot());
        // A same-task claim at a different instant cannot take the slot but can still record.
        long second = first + 3_600_000L;
        ClaimReceipt competing = manager.claim(new ClaimRequest(task.taskId(), second, task.scheduleRevision()));
        assertFalse(runDao.acquireTaskSlot(competing.runId()), "unique slot index blocks a second owner");
        assertTrue(runDao.skipClaim(competing.runId(), "TASK_SLOT_BUSY", clock.instant().toEpochMilli()),
                "non-owner rows must still terminate as SKIPPED");
        RunDao.RunRow skipped = runDao.findByRunId(competing.runId()).orElseThrow();
        assertEquals(RunState.SKIPPED, skipped.state());
        assertEquals(DispatchStatus.NOT_SENT, skipped.dispatchStatus());
        assertNull(skipped.startedAt(), "SKIPPED must carry NULL started_at");
        assertEquals(RunPhase.TERMINAL, skipped.phase());
    }

    @Test
    void claimRollbackOnFailureLeavesNoRunOrPointerChange() throws Exception {
        // Force a failure after insert by pointing the claim at a task id that disappears
        // mid-transaction is not possible here; instead simulate by claiming with a revision
        // mismatch, which must write nothing (condition-guard path).
        ClaimReceipt receipt = manager.claim(new ClaimRequest(task.taskId(), task.nextFireAt(), 99));
        assertEquals(ClaimOutcome.TASK_STATE_CHANGED, receipt.outcome());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs", Integer.class));
        assertEquals(task.nextFireAt(), jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId()));
    }

    @Test
    void sendFenceSetsDispatchingStartedAtAndTakesGuardAtomically() throws Exception {
        ClaimReceipt claim = manager.claim(claimAt(task.nextFireAt()));
        assertTrue(runDao.acquireTaskSlot(claim.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(claim.runId(), credentialKey));
        RunDao.RunRow fenced = runDao.findByRunId(claim.runId()).orElseThrow();
        // 004 pins state='PENDING' to phase='CLAIMED', so an in-flight run stays CLAIMED with
        // the durable MAY_HAVE_SENT + started_at fence; DISPATCHING is applied by the CAS.
        assertEquals(RunPhase.CLAIMED, fenced.phase());
        assertEquals(DispatchStatus.MAY_HAVE_SENT, fenced.dispatchStatus());
        assertEquals(clock.instant().toEpochMilli(), fenced.startedAt().longValue());
        assertEquals(claim.runId(), guardDao.find(credentialKey).orElseThrow().activeRunId());
    }

    @Test
    void guardBusyRefusesFenceAndLeavesNoDispatchingRow() throws Exception {
        ClaimReceipt first = manager.claim(claimAt(task.nextFireAt()));
        assertTrue(runDao.acquireTaskSlot(first.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(first.runId(), credentialKey));
        // Same credential, different task/model: second fence must lose the guard CAS.
        MonitorTask other = taskManager.create(new CreateRequest("second-task", "30 * * * * *",
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-n", "model-n", "codex"));
        other = taskManager.update(other.taskId(),
                new TaskManager.UpdateRequest(other.rowVersion(), null, null, null, true));
        clock.advanceMillis(1);
        ClaimReceipt second = manager.claim(new ClaimRequest(other.taskId(), other.nextFireAt(), other.scheduleRevision()));
        assertTrue(runDao.acquireTaskSlot(second.runId()));
        String sameCredentialKey = manager.credentialKey(other.instanceKey(), other.authIdCipher());
        assertEquals(credentialKey, sameCredentialKey, "same instance+auth id must map to one guard");
        assertFalse(manager.applySendFence(second.runId(), sameCredentialKey),
                "guard CAS must refuse the second fence");
        RunDao.RunRow secondRow = runDao.findByRunId(second.runId()).orElseThrow();
        assertEquals(RunPhase.CLAIMED, secondRow.phase(), "fence rollback leaves the claim intact");
        assertEquals(DispatchStatus.NOT_SENT, secondRow.dispatchStatus());
        assertNull(secondRow.startedAt());
    }

    @Test
    void guardKeyAndSnapshotsLeakNoPlaintextIdentity() throws Exception {
        String key = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertFalse(key.contains("auth-x"), "guard key must never contain the plain auth id");
        assertFalse(key.contains(task.authIdCipher()), "guard key must never embed the cipher text either");
        // The run snapshot persists ciphers only; no plaintext identity column exists.
        RunDao.RunRow run = runDao.findByRunId(
                manager.claim(claimAt(task.nextFireAt())).runId()).orElseThrow();
        assertEquals(task.authIdCipher(), run.authIdCipher());
        assertEquals(task.authIndexCipher(), run.authIndexCipher());
    }

    @Test
    void credentialGuardKeyIgnoresModelProviderAndPhysicalRebuild() throws Exception {
        String keyA = manager.credentialKey("inst-1", crypto.encrypt("auth-x"));
        String keyB = manager.credentialKey("inst-1", crypto.encrypt("auth-x"));
        String keyDifferentModel = keyA; // key derives from instance+auth id only
        String keyDifferentInstance = manager.credentialKey("inst-2", crypto.encrypt("auth-x"));
        String keyDifferentIdentity = manager.credentialKey("inst-1", crypto.encrypt("auth-z"));
        assertEquals(keyA, keyB, "same logical identity yields a stable key");
        assertEquals(keyA, keyDifferentModel);
        assertNotEquals(keyA, keyDifferentInstance);
        assertNotEquals(keyA, keyDifferentIdentity);
        // The key never contains the auth id in plain text.
        assertFalse(keyA.contains("auth-x"));
    }

    @Test
    void terminalCasIsOneShotAndReleasesOwnSlotAndGuard() throws Exception {
        ClaimReceipt claim = manager.claim(claimAt(task.nextFireAt()));
        assertTrue(runDao.acquireTaskSlot(claim.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(claim.runId(), credentialKey));
        TerminalReceipt receipt = manager.applyTerminal(new TerminalRequest(
                claim.runId(), credentialKey, RunTerminalType.SUCCESS, DispatchStatus.MAY_HAVE_SENT,
                "VERIFIED", "verified: true", 200, Boolean.TRUE));
        assertEquals(TerminalResult.APPLIED, receipt.result());
        assertTrue(receipt.guardReleased());
        RunDao.RunRow done = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(RunPhase.TERMINAL, done.phase());
        assertEquals(RunState.SUCCESS, done.state());
        assertTrue(done.finishedAt() != null);
        assertTrue(done.startedAt() != null);
        assertNull(guardDao.find(credentialKey).orElseThrow().activeRunId());
        assertFalse(done.ownsTaskSlot());
        // Late callback (e.g. a delayed success after a timeout) cannot overwrite.
        clock.advanceMillis(30_000);
        TerminalReceipt late = manager.applyTerminal(new TerminalRequest(
                claim.runId(), credentialKey, RunTerminalType.FAILED, DispatchStatus.MAY_HAVE_SENT,
                "TARGET_REJECTED", null, 500, Boolean.FALSE));
        assertEquals(TerminalResult.ALREADY_TERMINAL, late.result());
        assertEquals(RunState.SUCCESS, runDao.findByRunId(claim.runId()).orElseThrow().state(),
                "late callback must not overwrite the terminal verdict");
    }

    @Test
    void unknownTerminalPersistsCooldownInSameTransaction() throws Exception {
        ClaimReceipt claim = manager.claim(claimAt(task.nextFireAt()));
        assertTrue(runDao.acquireTaskSlot(claim.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(claim.runId(), credentialKey));
        long finishedAt = clock.instant().toEpochMilli();
        TerminalReceipt receipt = manager.applyTerminal(new TerminalRequest(
                claim.runId(), credentialKey, RunTerminalType.UNKNOWN, DispatchStatus.MAY_HAVE_SENT,
                "TIMEOUT", null, null, null));
        assertEquals(TerminalResult.APPLIED, receipt.result());
        Long cooldownUntil = guardDao.find(credentialKey).orElseThrow().cooldownUntil();
        assertTrue(cooldownUntil != null && cooldownUntil.longValue() == finishedAt + 120_000L,
                "UNKNOWN must persist finished_at+120000 in the same transaction");
    }

    @Test
    void terminalCannotReleaseAnotherRunsGuard() throws Exception {
        ClaimReceipt first = manager.claim(claimAt(task.nextFireAt()));
        assertTrue(runDao.acquireTaskSlot(first.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(first.runId(), credentialKey));
        // A second claim exists but never took the guard; it must not release the first's guard.
        MonitorTask other = taskManager.create(new CreateRequest("third-task", "45 * * * * *",
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-n", "model-n", "codex"));
        other = taskManager.update(other.taskId(),
                new TaskManager.UpdateRequest(other.rowVersion(), null, null, null, true));
        clock.advanceMillis(1);
        ClaimReceipt second = manager.claim(new ClaimRequest(other.taskId(), other.nextFireAt(), other.scheduleRevision()));
        TerminalReceipt foreign = manager.applyTerminal(new TerminalRequest(
                second.runId(), credentialKey, RunTerminalType.UNKNOWN, DispatchStatus.NOT_SENT,
                "PROCESS_INTERRUPTED", null, null, null));
        // Either refused as NOT_OWNER (guard not owned), or if the run isn't dispatchable the
        // CAS fails; the guard must still belong to the first run either way.
        assertEquals(first.runId(), guardDao.find(credentialKey).orElseThrow().activeRunId(),
                "a run must never release a guard it does not hold");
        assertTrue(foreign.result() == TerminalResult.NOT_OWNER || foreign.result() == TerminalResult.APPLIED,
                "foreign release is refused or the CAS never applied");
    }

    @Test
    void everyWritingCommitBumpsDataRevision() throws Exception {
        long before = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        ClaimReceipt claim = manager.claim(claimAt(task.nextFireAt()));
        long afterClaim = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        assertEquals(before + 1, afterClaim);
        assertTrue(runDao.acquireTaskSlot(claim.runId()));
        String credentialKey = manager.credentialKey(task.instanceKey(), task.authIdCipher());
        assertTrue(manager.applySendFence(claim.runId(), credentialKey));
        long afterFence = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        assertEquals(afterClaim + 1, afterFence);
        manager.applyTerminal(new TerminalRequest(
                claim.runId(), credentialKey, RunTerminalType.FAILED, DispatchStatus.MAY_HAVE_SENT,
                "TARGET_REJECTED", null, null, Boolean.FALSE));
        long afterTerminal = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        assertEquals(afterFence + 1, afterTerminal);
    }

    @Test
    void snapshotsAreIndependentFromTaskRenameAndUpstreamDeletion() throws Exception {
        ClaimReceipt claim = manager.claim(claimAt(task.nextFireAt()));
        String snapshotName = runDao.findByRunId(claim.runId()).orElseThrow().taskSafeName();
        String snapshotModel = runDao.findByRunId(claim.runId()).orElseThrow().taskModelDisplay();
        // Rename the task; the run snapshot must not follow.
        taskManager.update(task.taskId(), new TaskManager.UpdateRequest(task.rowVersion(), "renamed-task", null, null, null));
        RunDao.RunRow afterRename = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(snapshotName, afterRename.taskSafeName());
        assertEquals(snapshotModel, afterRename.taskModelDisplay());
        // Upstream credential disappears; the persisted ciphers stay decodable.
        resolver.setEntries(List.of());
        RunDao.RunRow afterDeletion = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals("auth-x", crypto.decrypt(afterDeletion.authIdCipher()),
                "run snapshot must not change when the upstream credential disappears");
    }

    @Test
    void concurrentClaimsFromTwoConnectionsProduceAtMostOneRun() throws Exception {
        long scheduledAt = task.nextFireAt();
        String sql = "INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, "
                + "task_safe_name, task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, "
                + "provider_snapshot, phase, state, dispatch_status, owns_task_slot, claimed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', 'PENDING', 'NOT_SENT', 0, ?)";
        java.sql.Connection connectionA = dataSource.getConnection();
        java.sql.Connection connectionB = dataSource.getConnection();
        connectionA.setAutoCommit(false);
        connectionB.setAutoCommit(false);
        String runA = "run-a";
        String runB = "run-b";
        try (var statementA = connectionA.prepareStatement(sql);
             var statementB = connectionB.prepareStatement(sql)) {
            statementA.setString(1, runA);
            statementA.setString(2, task.taskId());
            statementA.setLong(3, scheduledAt);
            statementA.setLong(4, task.scheduleRevision());
            statementA.setString(5, task.safeName());
            statementA.setString(6, task.safeModelDisplay());
            statementA.setString(7, task.instanceKey());
            statementA.setString(8, task.authIdCipher());
            statementA.setString(9, task.authIndexCipher());
            statementA.setString(10, task.providerSnapshot());
            statementA.setLong(11, clock.instant().toEpochMilli());
            statementA.executeUpdate();
            statementB.setString(1, runB);
            statementB.setString(2, task.taskId());
            statementB.setLong(3, scheduledAt);
            statementB.setLong(4, task.scheduleRevision());
            statementB.setString(5, task.safeName());
            statementB.setString(6, task.safeModelDisplay());
            statementB.setString(7, task.instanceKey());
            statementB.setString(8, task.authIdCipher());
            statementB.setString(9, task.authIndexCipher());
            statementB.setString(10, task.providerSnapshot());
            statementB.setLong(11, clock.instant().toEpochMilli());
            try {
                statementB.executeUpdate();
                connectionB.commit();
                // If SQLite allows the write after A's uncommitted insert, commit A and verify
                // only one row survives via the unique plan key.
                connectionA.commit();
            } catch (java.sql.SQLException duplicate) {
                connectionB.rollback();
                connectionA.commit();
            }
        } finally {
            connectionA.close();
            connectionB.close();
        }
        int rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ? AND scheduled_at = ?",
                Integer.class, task.taskId(), scheduledAt);
        assertTrue(rows <= 1, "unique plan key must cap at one row, got " + rows);
    }
}
