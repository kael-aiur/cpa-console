package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.CpaEndpoint;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationHttpResponse;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationTransportException;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerifyRequest;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimOutcome;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimReceipt;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T06 isolation self-check (D4.3 / D5.2–D5.4 / D9): due scanning of two independent plans,
 * two execution slots with an immediate pool-saturated skip, per-credential mutex across
 * models, the fence-vs-PATCH race with zero POST on the loser, transport UNKNOWN without a
 * sample, terminal CAS and cooldown, and target rejection continuing the future schedule.
 */
class ExecutionManagerTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private MonitorIsolationTestSupport.FakeResolver resolver;
    private ApiKeyCrypto crypto;
    private TaskManager taskManager;
    private RunClaimManager claimManager;
    private RunDao runDao;
    private CredentialGuardDao guardDao;
    private MonitorConfigLockManager configLock;
    private CapturingClient verifyClient;
    private ExecutionManager executionManager;

    /** Deterministic fake verify client: no real network, records endpoint + body only. */
    static final class CapturingClient extends CpaCredentialVerificationClient {
        final List<String> endpoints = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<VerifyRequest> requests = new CopyOnWriteArrayList<>();
        volatile String responseBody = "{\"verified\":true,\"status\":\"ok\"}";
        volatile int responseStatus = 200;
        volatile boolean throwTransport;
        volatile String transportCategory = "TRANSPORT";
        final CountDownLatch blockGate = new CountDownLatch(1);

        CapturingClient() {
            super(new CpaEndpoint("http://127.0.0.1:1", "cfg"), "test-key");
        }

        @Override
        public VerificationHttpResponse verify(VerifyRequest request) {
            endpoints.add("POST /v8/management/credentials/verify");
            bodies.add(request.authId() + "|" + request.authIndex() + "|" + request.model());
            requests.add(request);
            if (throwTransport) {
                throw new VerificationTransportException(transportCategory, new java.io.IOException("fake"));
            }
            return new VerificationHttpResponse(responseStatus, responseBody);
        }
    }

    private MonitorTask task;
    private MonitorTask otherTask;
    private final List<String> submitted = new CopyOnWriteArrayList<>();
    private final List<String> skips = new CopyOnWriteArrayList<>();
    private final List<String> terminals = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        resolver = new MonitorIsolationTestSupport.FakeResolver(
                List.of(
                        new CpaIdentityResolver.CredentialEntryDetail(
                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file"),
                        new CpaIdentityResolver.CredentialEntryDetail(
                                "auth-z", "idx-w", "zeta.json", "codex", "codex", false, false, false, null, "file")),
                List.of("model-m", "model-n"));
        crypto = MonitorIsolationTestSupport.crypto();
        verifyClient = new CapturingClient();
        configLock = new MonitorConfigLockManager(verifyClient, "cfg-1");
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), resolver::verifyBoundTarget, configLock);
        runDao = new RunDao(dataSource);
        guardDao = new CredentialGuardDao(dataSource);
        claimManager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao,
                MonitorIsolationTestSupport.txManager(dataSource));
        task = taskManager.create(new CreateRequest("t-a", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        task = taskManager.update(task.taskId(), new UpdateRequest(task.rowVersion(), null, null, null, true));
        otherTask = taskManager.create(new CreateRequest("t-b", "30 * * * * *",
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-n", "model-n", "codex"));
        otherTask = taskManager.update(otherTask.taskId(),
                new UpdateRequest(otherTask.rowVersion(), null, null, null, true));
        executionManager = newManager(verifyClient);
    }

    /** Builds the ExecutionManager with the fake resolver and the given capturing client. */
    private ExecutionManager newManager(CapturingClient client) {
        return new ExecutionManager(
                claimManager, taskManager,
                fakeResolver(),
                (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                new MonitorConfigLockManager(client, "cfg-1"), clock,
                new ExecutionManager.ExecutionListener() {
                    @Override public void onSubmitted(String runId, String endpoint, String body) {
                        submitted.add(runId + " " + endpoint + " " + body);
                    }
                    @Override public void onSkip(String runId, String reason) {
                        skips.add(runId + " " + reason);
                    }
                    @Override public void onTerminal(String runId, RunTerminalType state, String reason) {
                        terminals.add(runId + " " + state + " " + reason);
                    }
                    @Override public void onStorageFailure(String runId) { }
                });
    }

    /**
     * Builds a real CpaIdentityResolver over a capturing client whose pages come from the
     * fake feed (offline); verifyBoundTarget is exercised through the production code path.
     */
    private CpaIdentityResolver fakeResolver() {
        return new CpaIdentityResolver(fakePageClient(), crypto, clock);
    }

    /** Capturing client serving fake credential pages without network. */
    private CpaCredentialVerificationClient fakePageClient() {
        return new CpaCredentialVerificationClient(
                new CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
            @Override
            public java.util.List<site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage>
                    listAllCredentialPages() {
                return java.util.List.of(
                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage(
                                java.util.List.of(
                                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail(
                                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file"),
                                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail(
                                                "auth-z", "idx-w", "zeta.json", "codex", "codex", false, false, false, null, "file")),
                                false));
            }

            @Override
            public java.util.List<String> listModels(String nameOrAuthId) {
                if ("auth-x".equals(nameOrAuthId)) {
                    return java.util.List.of("model-m", "model-n");
                }
                if ("auth-z".equals(nameOrAuthId)) {
                    return java.util.List.of("model-n");
                }
                return java.util.List.of();
            }
        };
    }

    @Test
    void twoIndependentPlansClaimAndRunThroughTheSameTick() throws Exception {
        executionManager = newManager(verifyClient);
        ExecutionManager.DuePlan planA = new ExecutionManager.DuePlan(
                task.taskId(), task.nextFireAt(), task.scheduleRevision());
        ExecutionManager.DuePlan planB = new ExecutionManager.DuePlan(
                otherTask.taskId(), otherTask.nextFireAt(), otherTask.scheduleRevision());
        ExecutionManager.TickSummary summary = executionManager.claimDuePlans(List.of(planA, planB));
        assertEquals(2, summary.claimed());
        ClaimReceipt a = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, a.outcome(), "the second claim is refused");
    }

    @Test
    void transportFailureBeforeSubmissionIsUnknownWithoutSampleAndCooldown() throws Exception {
        verifyClient.throwTransport = true;
        executionManager = newManager(verifyClient);
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        executionManager.dispatchClaimed(claim.runId());
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(RunTerminalType.UNKNOWN, RunTerminalType.valueOf(row.state().name()));
        assertEquals(site.kael.cpa.console.core.monitor.model.DispatchStatus.NOT_SENT, row.dispatchStatus());
        assertNull(row.startedAt(), "proven not-sent UNKNOWN keeps started_at NULL");
        assertNull(row.cpaHttpStatus());
        String credentialKey = claimManager.credentialKey("inst-1", task.authIdCipher());
        Long cooldown = guardDao.find(credentialKey).orElseThrow().cooldownUntil();
        assertTrue(cooldown != null && cooldown == clock.instant().toEpochMilli() + 120_000L,
                "UNKNOWN not-sent cooldown starts from the terminal finish");
    }

    @Test
    void successfulSendCarriesExactlyOnePostWithCapturedIdentity() throws Exception {
        executionManager = newManager(verifyClient);
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        executionManager.dispatchClaimed(claim.runId());
        assertEquals(1, verifyClient.requests.size(), "exactly one POST for one claim");
        VerifyRequest request = verifyClient.requests.get(0);
        assertEquals("auth-x", request.authId());
        assertEquals("idx-y", request.authIndex());
        assertEquals("model-m", request.model());
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(RunTerminalType.SUCCESS, RunTerminalType.valueOf(row.state().name()));
        assertTrue(row.startedAt() != null);
    }

    @Test
    void targetRejectionIsFailedAndKeepsTheFutureSchedule() throws Exception {
        verifyClient.responseStatus = 200;
        verifyClient.responseBody = "{\"verified\":false,\"auth_id\":\"auth-x\",\"auth_index\":\"idx-y\"}";
        executionManager = newManager(verifyClient);
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        executionManager.dispatchClaimed(claim.runId());
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(RunTerminalType.FAILED, RunTerminalType.valueOf(row.state().name()));
        MonitorTask after = taskManager.find(task.taskId()).orElseThrow();
        assertTrue(after.enabled(), "target rejection never disables the task");
        assertNull(after.blockedReason());
        assertTrue(after.nextFireAt() != null && after.nextFireAt() > task.nextFireAt());
    }

    @Test
    void patchBetweenClaimAndFenceMeansZeroPost() throws Exception {
        executionManager = newManager(verifyClient);
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        // PATCH wins before the fence: a cron edit bumps schedule_revision and makes the
        // claim stale (safe-name-only edits must not re-dispatch stale claims either, but the
        // revision contract guarantees the fence check on plan edits; see D5.3 step 4).
        MonitorTask currentTask = taskManager.find(task.taskId()).orElseThrow();
        taskManager.update(task.taskId(), new UpdateRequest(
                currentTask.rowVersion(), null, "15 * * * * *", null, null));
        executionManager.dispatchClaimed(claim.runId());
        assertEquals(0, verifyClient.requests.size(), "PATCH before submit must mean zero POST");
        RunDao.RunRow row = runDao.findByRunId(claim.runId()).orElseThrow();
        assertEquals(RunTerminalType.SKIPPED, RunTerminalType.valueOf(row.state().name()));
        assertEquals(site.kael.cpa.console.core.monitor.model.DispatchStatus.NOT_SENT, row.dispatchStatus());
        assertNull(row.startedAt());
    }

    @Test
    void sameCredentialDifferentTasksAreSerializedByGuard() throws Exception {
        MonitorTask sameCredentialTask = taskManager.create(new CreateRequest("t-c", "45 * * * * *",
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-n", "model-n", "codex"));
        sameCredentialTask = taskManager.update(sameCredentialTask.taskId(),
                new UpdateRequest(sameCredentialTask.rowVersion(), null, null, null, true));
        executionManager = newManager(verifyClient);
        ClaimReceipt first = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(first.runId());
        executionManager.dispatchClaimed(first.runId());
        assertEquals(1, verifyClient.requests.size());
        String credentialKey = claimManager.credentialKey("inst-1", task.authIdCipher());
        assertTrue(guardDao.find(credentialKey).orElseThrow().activeRunId() == null
                        || first.runId().equals(guardDao.find(credentialKey).orElseThrow().activeRunId()),
                "the winner releases its own guard after the terminal");
        // The same credential (different model, different task) claims next and must respect
        // the cooldown when one exists; with SUCCESS there is no cooldown and it dispatches.
        ClaimReceipt second = claimManager.claim(new RunClaimManager.ClaimRequest(
                sameCredentialTask.taskId(), sameCredentialTask.nextFireAt(), sameCredentialTask.scheduleRevision()));
        runDao.acquireTaskSlot(second.runId());
        executionManager.dispatchClaimed(second.runId());
        assertEquals(2, verifyClient.requests.size(), "no cooldown after SUCCESS allows the next dispatch");
    }

    @Test
    void unknownTerminalRunsLeadToCooldownSkipForTheSameCredential() throws Exception {
        verifyClient.responseStatus = 500;
        verifyClient.responseBody = "{}";
        executionManager = newManager(verifyClient);
        ClaimReceipt first = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(first.runId());
        executionManager.dispatchClaimed(first.runId());
        assertEquals(RunTerminalType.UNKNOWN,
                RunTerminalType.valueOf(runDao.findByRunId(first.runId()).orElseThrow().state().name()));
        long cooldownUntil = clock.instant().toEpochMilli() + 120_000L;
        // Next claim on the same credential must skip while the cooldown lasts.
        ClaimReceipt second = claimManager.claim(new RunClaimManager.ClaimRequest(
                otherTask.taskId(), otherTask.nextFireAt(), otherTask.scheduleRevision()));
        runDao.acquireTaskSlot(second.runId());
        executionManager.dispatchClaimed(second.runId());
        RunDao.RunRow skipped = runDao.findByRunId(second.runId()).orElseThrow();
        assertEquals(RunTerminalType.SKIPPED, RunTerminalType.valueOf(skipped.state().name()));
        assertEquals(1, verifyClient.requests.size(), "cooldown must block the second POST");
        // Advancing past the cooldown re-enables dispatch.
        clock.advanceMillis(120_001L);
        ClaimReceipt third = claimManager.claim(new RunClaimManager.ClaimRequest(
                otherTask.taskId(), otherTask.nextFireAt() + 1, otherTask.scheduleRevision()));
        runDao.acquireTaskSlot(third.runId());
        executionManager.dispatchClaimed(third.runId());
        assertEquals(2, verifyClient.requests.size(), "after the cooldown the next cron dispatches");
    }

    @Test
    void lateSuccessCannotOverwriteUnknown() throws Exception {
        verifyClient.responseStatus = 500;
        verifyClient.responseBody = "{}";
        executionManager = newManager(verifyClient);
        ClaimReceipt claim = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        runDao.acquireTaskSlot(claim.runId());
        executionManager.dispatchClaimed(claim.runId());
        String credentialKey = claimManager.credentialKey("inst-1", task.authIdCipher());
        RunClaimManager.TerminalReceipt late = claimManager.applyTerminal(
                new RunClaimManager.TerminalRequest(claim.runId(), credentialKey,
                        RunTerminalType.SUCCESS,
                        site.kael.cpa.console.core.monitor.model.DispatchStatus.MAY_HAVE_SENT,
                        "VERIFIED", null, 200, Boolean.TRUE));
        assertEquals(site.kael.cpa.console.core.monitor.dao.RunDao.TerminalResult.ALREADY_TERMINAL,
                late.result());
        assertEquals(RunTerminalType.UNKNOWN,
                RunTerminalType.valueOf(runDao.findByRunId(claim.runId()).orElseThrow().state().name()));
    }

    @Test
    void slotPrecheckSkipsSecondActiveRunOfTheSameTask() throws Exception {
        executionManager = newManager(verifyClient);
        ClaimReceipt owner = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt(), task.scheduleRevision()));
        assertTrue(runDao.acquireTaskSlot(owner.runId()));
        ClaimReceipt second = claimManager.claim(new RunClaimManager.ClaimRequest(
                task.taskId(), task.nextFireAt() + 1, task.scheduleRevision()));
        runDao.acquireTaskSlot(second.runId());
        executionManager.dispatchClaimed(second.runId());
        RunDao.RunRow skipped = runDao.findByRunId(second.runId()).orElseThrow();
        assertEquals(RunTerminalType.SKIPPED, RunTerminalType.valueOf(skipped.state().name()));
        assertEquals(ExecutionManager.REASON_TASK_SLOT_BUSY, skipped.reasonCode());
        assertEquals(0, verifyClient.requests.size());
    }
}
