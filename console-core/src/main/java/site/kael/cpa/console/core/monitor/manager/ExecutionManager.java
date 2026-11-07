package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerifyRequest;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationHttpResponse;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.VerificationTransportException;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.dao.RunDao.RunRow;
import site.kael.cpa.console.core.monitor.model.DispatchStatus;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;
import site.kael.cpa.console.core.monitor.schedule.ScheduleIterator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T06 execution manager (D4.3 / D5.2–D5.4).
 * <p>
 * One tick receives the shared {@code now}, claims every due plan key through the accepted
 * T05 claim transaction, and dispatches each claim through the fixed lock order
 * {@code config read lock → per-task lock → credential guard CAS} with a bounded
 * {@link SynchronousQueue} pool of exactly two workers — no queue, no backlog: the third
 * concurrent execution attempt ends as NOT_SENT SKIPPED. The final dispatch fence re-checks
 * the task inside the same task lock that submits the captured immutable client, so a PATCH
 * that wins before the submit means zero POST and a submit that wins is allowed to finish.
 * Transport failure before a proven submission is recorded as NOT_SENT UNKNOWN without a
 * sample; an accepted single POST is always collected with one 120 s total deadline and
 * terminalized once through the T05 CAS. No retry, no backlog replay, no upstream stop promise.
 */
public final class ExecutionManager {

    /** Stable local skip/unknown reasons (D3.3 / D5.3). */
    public static final String REASON_TASK_SLOT_BUSY = "TASK_SLOT_BUSY";
    public static final String REASON_CREDENTIAL_BUSY = "CREDENTIAL_BUSY";
    public static final String REASON_CREDENTIAL_COOLDOWN = "CREDENTIAL_COOLDOWN";
    public static final String REASON_EXECUTOR_SATURATED = "EXECUTOR_SATURATED";
    public static final String REASON_TASK_DISABLED = "TASK_DISABLED_BY_PATCH";
    public static final String REASON_TASK_STATE_CHANGED = "TASK_STATE_CHANGED";
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_TRANSPORT = "TRANSPORT";
    public static final String REASON_TARGET_REJECTED = "TARGET_REJECTED";
    public static final String REASON_VERIFIED = "VERIFIED";
    public static final String REASON_UNKNOWN_RESPONSE = "UNKNOWN_RESPONSE";
    public static final String REASON_CONFIG_IDENTITY = "IDENTITY_UNSAFE";
    public static final String REASON_SUBMIT_REJECTED = "SUBMIT_REJECTED_NOT_SENT";
    public static final String REASON_STORAGE_UNHEALTHY = "STORAGE_UNHEALTHY";

    /** Single overall wait budget for one dispatched verification (D5.3 step 7). */
    public static final long TOTAL_WAIT_MS = 120_000L;
    /** Fixed local execution slot count (S6 / R08). */
    public static final int MAX_EXECUTION_SLOTS = 2;

    private final RunClaimManager claimManager;
    private final TaskManager taskManager;
    private final CpaIdentityResolver identityResolver;
    private final VerificationResponseClassifierFactory classifierFactory;
    private final MonitorConfigLockManager configLock;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final ExecutionListener listener;

    /** Pluggable classifier source so tests can fix responses without a real CPA. */
    public interface VerificationResponseClassifierFactory {
        VerificationResponseClassifier create(int httpStatus, String body, String capturedEndpoint);
    }

    /** Observable dispatch evidence for isolation assertions (no secrets, no bodies). */
    public interface ExecutionListener {
        void onSubmitted(String runId, String endpoint, String body);

        void onSkip(String runId, String reason);

        void onTerminal(String runId, RunTerminalType state, String reason);

        void onStorageFailure(String runId);
    }

    public static final class NoopListener implements ExecutionListener {
        @Override public void onSubmitted(String runId, String endpoint, String body) { }
        @Override public void onSkip(String runId, String reason) { }
        @Override public void onTerminal(String runId, RunTerminalType state, String reason) { }
        @Override public void onStorageFailure(String runId) { }
    }

    public ExecutionManager(
            RunClaimManager claimManager,
            TaskManager taskManager,
            CpaIdentityResolver identityResolver,
            VerificationResponseClassifierFactory classifierFactory,
            MonitorConfigLockManager configLock,
            Clock clock,
            ExecutionListener listener) {
        this.claimManager = Objects.requireNonNull(claimManager, "claimManager");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.classifierFactory = Objects.requireNonNull(classifierFactory, "classifierFactory");
        this.configLock = Objects.requireNonNull(configLock, "configLock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = listener == null ? new NoopListener() : listener;
        this.executor = new ThreadPoolExecutor(
                MAX_EXECUTION_SLOTS, MAX_EXECUTION_SLOTS, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Due-plan descriptor assembled by the T06 scanner from one tick snapshot. */
    public record DuePlan(String taskId, long scheduledAt, long expectedScheduleRevision) {
    }

    /** Result summary of one tick (identity-free). */
    public record TickSummary(int claimed, int dispatched, int skipped) {
    }

    /**
     * Claims every due plan at the shared tick instant. Already-taken plan keys are reported
     * and never re-POSTed; storage failures abort the tick before any dispatch work.
     */
    public TickSummary claimDuePlans(List<DuePlan> duePlans) throws java.sql.SQLException {
        Objects.requireNonNull(duePlans, "duePlans");
        int claimedCount = 0;
        int skippedCount = 0;
        for (DuePlan plan : duePlans) {
            RunClaimManager.ClaimReceipt receipt = claimManager.claim(new RunClaimManager.ClaimRequest(
                    plan.taskId(), plan.scheduledAt(), plan.expectedScheduleRevision()));
            switch (receipt.outcome()) {
                case CLAIMED -> claimedCount++;
                case DUPLICATE_PLAN_KEY, TASK_STATE_CHANGED -> skippedCount++;
            }
        }
        return new TickSummary(claimedCount, 0, skippedCount);
    }

    /**
     * One claim's full pre-check → fence → submit → collect path. Never throws for a lost
     * race: every loser terminates its own run in a short transaction and returns normally.
     * A storage failure inside the fence aborts with the unhealthy flag so the scheduler
     * (T07) can stop new dispatches; nothing is retried here.
     */
    public void dispatchClaimed(String runId) throws java.sql.SQLException {
        RunRow run = claimManager.runRow(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run " + runId));
        MonitorTask task = taskManager.requireForClaim(run.taskId());
        // Slot precheck: persisted first at claim time; a second active run for the same task
        // is a hard skip without any POST.
        if (claimManager.hasActiveRun(run.taskId(), runId)) {
            claimManager.skipClaim(runId, REASON_TASK_SLOT_BUSY);
            listener.onSkip(runId, REASON_TASK_SLOT_BUSY);
            return;
        }
        // Guard/cooldown precheck (non-authoritative): the CAS inside the fence decides.
        String credentialKey = claimManager.credentialKey(run.instanceKey(), run.authIdCipher());
        CredentialGuardDao.GuardRow guard = claimManager.guard(credentialKey).orElse(null);
        if (guard != null) {
            long nowMs = clock.instant().toEpochMilli();
            if (guard.activeRunId() != null && !guard.activeRunId().equals(runId)) {
                claimManager.skipClaim(runId, REASON_CREDENTIAL_BUSY);
                listener.onSkip(runId, REASON_CREDENTIAL_BUSY);
                return;
            }
            if (guard.cooldownUntil() != null && guard.cooldownUntil() > nowMs && guard.activeRunId() == null) {
                claimManager.skipClaim(runId, REASON_CREDENTIAL_COOLDOWN);
                listener.onSkip(runId, REASON_CREDENTIAL_COOLDOWN);
                return;
            }
        }
        boolean submitted;
        try {
            submitted = Boolean.TRUE.equals(executor.submit(() -> {
                dispatchWithSlot(runId, run, task, credentialKey);
                return Boolean.TRUE;
            }).get());
        } catch (RejectedExecutionException exception) {
            claimManager.skipClaim(runId, REASON_EXECUTOR_SATURATED);
            listener.onSkip(runId, REASON_EXECUTOR_SATURATED);
            return;
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof DispatchAborted aborted) {
                return; // The loser already recorded its own terminal state.
            }
            if (cause instanceof java.sql.SQLException || cause.getCause() instanceof java.sql.SQLException) {
                listener.onStorageFailure(runId);
                throw (java.sql.SQLException) (cause instanceof java.sql.SQLException
                        ? cause : cause.getCause());
            }
            claimManager.skipClaim(runId, REASON_STORAGE_UNHEALTHY);
            listener.onStorageFailure(runId);
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            claimManager.skipClaim(runId, REASON_STORAGE_UNHEALTHY);
            return;
        }
        if (!submitted) {
            // dispatchWithSlot communicates via exceptions/terminal writes; a null return
            // means the worker terminalized the run itself.
        }
    }

    /** Worker-side path with an acquired execution slot. */
    private void dispatchWithSlot(String runId, RunRow run, MonitorTask task, String credentialKey) {
        try {
            if (task.enabled() && task.blockedReason() == null) {
                performSendFence(runId, run, task, credentialKey);
            } else {
                claimManager.skipClaim(runId, REASON_TASK_DISABLED);
                listener.onSkip(runId, REASON_TASK_DISABLED);
            }
        } catch (DispatchAborted ignored) {
            // Loser recorded its own state; nothing further.
        } catch (java.sql.SQLException exception) {
            listener.onStorageFailure(runId);
            throw new StorageAborted(exception);
        } catch (RuntimeException exception) {
            listener.onStorageFailure(runId);
            throw new StorageAborted(exception);
        }
    }

    /**
     * Precheck, send fence and captured submit, executed inside the acquired slot. Losers
     * raise {@link DispatchAborted} after recording their own terminal state; winners return
     * normally after the one POST and its terminal transaction.
     */
    private void performSendFence(String runId, RunRow run, MonitorTask task, String credentialKey)
            throws java.sql.SQLException {
        // Read-only identity precheck: GET only, never a POST, and never a sample.
        CpaIdentityResolver.BoundTarget target;
        try {
            target = identityResolver.verifyBoundTarget(
                    run.instanceKey(),
                    claimManager.decryptAuthId(run.authIdCipher()),
                    claimManager.decryptAuthIndex(run.authIndexCipher()),
                    claimManager.decryptRouteModel(run.taskId()));
        } catch (CpaIdentityResolver.IdentityResolutionException exception) {
            // Explicit configuration problem: task blocked atomically with the terminal write.
            taskManager.markBlocked(run.taskId(), exception.code());
            claimManager.applyTerminal(new RunClaimManager.TerminalRequest(
                    runId, credentialKey, RunTerminalType.CONFIG_ERROR, DispatchStatus.NOT_SENT,
                    exception.code(), "blocked: " + exception.code(), null, null));
            listener.onTerminal(runId, RunTerminalType.CONFIG_ERROR, exception.code());
            throw new DispatchAborted();
        }
        // Fixed lock order: config read lock → task lock → guard CAS inside applySendFence.
        // The task lock stays held through the captured submit so a PATCH can never
        // interleave between the final re-check/fence and the send submission (D5.3 step 5);
        // after the submit linearizes, a later PATCH only affects future plans.
        MonitorConfigLockManager.Snapshot snapshot = configLock.snapshot();
        ReentrantLock taskLock = taskManager.taskLock(run.taskId());
        taskLock.lock();
        boolean fenceApplied;
        boolean transportReverted;
        try {
            MonitorTask current = claimManager.runTask(run.taskId());
            if (!current.enabled() || current.blockedReason() != null
                    || current.scheduleRevision() != run.scheduleRevision()) {
                // Stale claim: the PATCH already won; record the skip before any POST.
                claimManager.skipClaim(runId, REASON_TASK_STATE_CHANGED);
                listener.onSkip(runId, REASON_TASK_STATE_CHANGED);
                throw new DispatchAborted();
            }
            boolean patchedAway = !current.enabled()
                    || current.blockedReason() != null
                    || current.scheduleRevision() != run.scheduleRevision();
            fenceApplied = !patchedAway && claimManager.applySendFence(runId, credentialKey);
            transportReverted = false;
            if (fenceApplied) {
                String capturedEndpoint = "captured:" + snapshot.configVersion();
                String capturedBody = "verify " + target.authId() + "/" + target.routeModel();
                listener.onSubmitted(runId, capturedEndpoint, capturedBody);
                if (!submitVerifyPost(runId, run, target, snapshot)) {
                    if (claimManager.runRow(runId).orElseThrow().finishedAt() != null) {
                        // Proven pre-submission transport failure: the post path terminalized
                        // the run as NOT_SENT UNKNOWN with cooldown; no skip, no further POST.
                        transportReverted = true;
                    }
                    fenceApplied = false;
                }
            }
        } finally {
            taskLock.unlock();
        }
        if (!fenceApplied) {
            if (!transportReverted) {
                RunRow currentRow = claimManager.runRow(runId).orElseThrow();
                if (currentRow.phase() == site.kael.cpa.console.core.monitor.model.RunPhase.CLAIMED
                        && currentRow.dispatchStatus()
                            == site.kael.cpa.console.core.monitor.model.DispatchStatus.NOT_SENT) {
                    claimManager.skipClaim(runId, fenceLostReason(run.taskId(), run.scheduleRevision()));
                    listener.onSkip(runId, fenceLostReason(run.taskId(), run.scheduleRevision()));
                }
            }
            throw new DispatchAborted();
        }
    }

    /** Submits and terminalizes the single POST; caller already holds the task lock. */
    private boolean submitVerifyPost(
            String runId, RunRow run, CpaIdentityResolver.BoundTarget target,
            MonitorConfigLockManager.Snapshot snapshot) throws java.sql.SQLException {
        try {
            String authId = claimManager.decryptAuthId(run.authIdCipher());
            String authIndex = claimManager.decryptAuthIndex(run.authIndexCipher());
            String model = claimManager.decryptRouteModel(run.taskId());
            String credentialKey = credentialKey(run);
            VerificationHttpResponse response;
            try {
                response = snapshot.client().verify(new VerifyRequest(authId, authIndex, model));
            } catch (VerificationTransportException exception) {
                recordTransportUnknown(runId, credentialKey, exception.category());
                return false;
            }
            // One-shot terminal CAS: classify the single response and terminate once.
            VerificationResponseClassifier.Classification classification =
                    classifierFactory.create(response.statusCode(), response.body(), "").classify();
            RunTerminalType terminal = switch (classification.verdict()) {
                case SUCCESS -> RunTerminalType.SUCCESS;
                case FAILED -> RunTerminalType.FAILED;
                case CONFIG_ERROR -> RunTerminalType.CONFIG_ERROR;
                case UNKNOWN -> RunTerminalType.UNKNOWN;
            };
            if (terminal == RunTerminalType.CONFIG_ERROR) {
                taskManager.markBlocked(run.taskId(), classification.reason());
            }
            String summary = "monitor verify " + terminal + " via captured endpoint";
            claimManager.applyTerminal(new RunClaimManager.TerminalRequest(
                    runId, credentialKey, terminal, DispatchStatus.MAY_HAVE_SENT,
                    classification.reason(), summary,
                    response.statusCode(), classification.currentVerifiedRaw()));
            listener.onTerminal(runId, terminal, classification.reason());
            return true;
        } finally {
            // Lock released by the fence caller.
        }
    }

    private String credentialKey(RunRow run) {
        return claimManager.credentialKey(run.instanceKey(), run.authIdCipher());
    }

    /**
     * UNKNOWN for a transport failure before a proven submission: the fence intent is
     * reverted (started_at cleared) so the row terminalizes as NOT_SENT without a sample,
     * per D5.3 step 6's "明确未进入传输可清started" clause.
     */
    private void recordTransportUnknown(String runId, String credentialKey, String category)
            throws java.sql.SQLException {
        claimManager.revertSendFence(runId);
        claimManager.applyTerminal(new RunClaimManager.TerminalRequest(
                runId, credentialKey, RunTerminalType.UNKNOWN, DispatchStatus.NOT_SENT,
                category, null, null, null));
        listener.onTerminal(runId, RunTerminalType.UNKNOWN, category);
    }

    private String fenceLostReason(String taskId, long expectedRevision) {
        try {
            MonitorTask current = claimManager.runTask(taskId);
            if (!current.enabled()) {
                return REASON_TASK_DISABLED;
            }
            if (current.blockedReason() != null) {
                return REASON_TASK_STATE_CHANGED;
            }
            if (current.scheduleRevision() != expectedRevision) {
                return REASON_TASK_STATE_CHANGED;
            }
            return REASON_CREDENTIAL_BUSY;
        } catch (java.sql.SQLException exception) {
            return REASON_STORAGE_UNHEALTHY;
        }
    }

    /** Internal control flow marker: a loser already terminalized its own run. */
    private static final class DispatchAborted extends RuntimeException {
    }

    /** Internal control flow marker: the slot worker failed and released its slot. */
    private static final class StorageAborted extends RuntimeException {
        StorageAborted(Throwable cause) {
            super(cause);
        }
    }
}
