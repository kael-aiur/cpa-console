package site.kael.cpa.console.core.monitor.manager;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.dao.RunDao.ClaimInsert;
import site.kael.cpa.console.core.monitor.dao.RunDao.ClaimResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.FenceResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.TerminalResult;
import site.kael.cpa.console.core.monitor.dao.RunDao.TerminalWrite;
import site.kael.cpa.console.core.monitor.model.DispatchStatus;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;
import site.kael.cpa.console.core.monitor.schedule.ScheduleIterator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * T05 persistent claim / slot / guard / terminal-CAS manager (D5.1–D5.3).
 * <p>
 * Every operation here is a short transaction with no network inside. The claim transaction
 * inserts the plan-key run and conditionally advances next_fire_at atomically: either both
 * happen or neither. The terminal transaction applies the one-shot CAS and releases this
 * run's slot and guard, persisting the 120 s cooldown for UNKNOWN in the same commit.
 */
public final class RunClaimManager {

    /** Atomic claim outcome. */
    public enum ClaimOutcome {
        /** Run inserted and next_fire advanced to the computed successor. */
        CLAIMED,
        /** Plan key already exists (same absolute instant regardless of revision). */
        DUPLICATE_PLAN_KEY,
        /** Task row/revision guard did not match; nothing was written. */
        TASK_STATE_CHANGED
    }

    /** Claim request assembled by the scanner (T06) from the enabled task snapshot. */
    public record ClaimRequest(String taskId, long scheduledAt, long expectedScheduleRevision) {
    }

    /** Claim result with the created run id for later fence/terminal calls. */
    public record ClaimReceipt(ClaimOutcome outcome, String runId, Long nextFireAt) {
        public static ClaimReceipt of(ClaimOutcome outcome) {
            return new ClaimReceipt(outcome, null, null);
        }
    }

    /** Terminal request: verdict + dispatch evidence; currentVerified only for strict evidence. */
    public record TerminalRequest(
            String runId,
            String credentialKey,
            RunTerminalType state,
            DispatchStatus dispatchStatus,
            String reasonCode,
            String safeAdminSummary,
            Integer cpaHttpStatus,
            Boolean currentVerified) {

        public TerminalRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(dispatchStatus, "dispatchStatus");
        }
    }

    /** Guard release report: whether this run owned the guard and the slot. */
    public record TerminalReceipt(TerminalResult result, boolean guardReleased) {
    }

    /** Stability window for the post-UNKNOWN cooldown (D5.4 / R08). */
    public static final long UNKNOWN_COOLDOWN_MS = 120_000L;

    private final DataSource dataSource;
    private final ApiKeyCrypto crypto;
    private final Clock clock;
    private final TaskManager taskManager;
    private final RunDao runDao;
    private final CredentialGuardDao guardDao;
    private final TransactionTemplate transactions;

    public RunClaimManager(
            DataSource dataSource,
            ApiKeyCrypto crypto,
            Clock clock,
            TaskManager taskManager,
            RunDao runDao,
            CredentialGuardDao guardDao,
            PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.runDao = Objects.requireNonNull(runDao, "runDao");
        this.guardDao = Objects.requireNonNull(guardDao, "guardDao");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * The transaction-bound connection while a Spring transaction is active (the standard
     * DataSourceUtils path); outside a transaction this is the caller's plain connection.
     */
    private Connection writeConnection() throws SQLException {
        return DataSourceUtils.getConnection(dataSource);
    }

    /**
     * Claim transaction: INSERT the plan-key run plus the conditional next_fire advance in
     * one commit. The plan key carries no revision; a duplicate absolute instant is refused
     * even when the cron was edited back to the same time.
     */
    public ClaimReceipt claim(ClaimRequest request) {
        Objects.requireNonNull(request, "request");
        ClaimReceipt receipt = transactions.execute(status -> {
            try {
                return doClaim(request);
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Claim transaction failed", exception);
            }
        });
        return receipt != null ? receipt : ClaimReceipt.of(ClaimOutcome.TASK_STATE_CHANGED);
    }

    private ClaimReceipt doClaim(ClaimRequest request) throws SQLException {
        MonitorTask task = taskManager.requireForClaim(request.taskId());
        if (!task.enabled() || task.blockedReason() != null) {
            return ClaimReceipt.of(ClaimOutcome.TASK_STATE_CHANGED);
        }
        if (task.scheduleRevision() != request.expectedScheduleRevision()) {
            return ClaimReceipt.of(ClaimOutcome.TASK_STATE_CHANGED);
        }
        long now = clock.instant().toEpochMilli();
        ClaimInsert insert = new ClaimInsert(
                UUID.randomUUID().toString(),
                task.taskId(),
                request.scheduledAt(),
                task.scheduleRevision(),
                task.safeName(),
                task.safeModelDisplay(),
                task.instanceKey(),
                task.authIdCipher(),
                task.authIndexCipher(),
                task.providerSnapshot(),
                now);
        ClaimResult result = runDao.insertClaim(insert);
        if (result == ClaimResult.DUPLICATE_PLAN_KEY) {
            return ClaimReceipt.of(ClaimOutcome.DUPLICATE_PLAN_KEY);
        }
        Long advanced = advanceNextFire(task.taskId(), request.scheduledAt(), task.scheduleRevision());
        runDao.bumpDataRevision(writeConnection());
        return new ClaimReceipt(ClaimOutcome.CLAIMED, insert.runId(), advanced);
    }

    /**
     * Send fence: CLAIMED → DISPATCHING/MAY_HAVE_SENT with started_at, plus the credential
     * guard CAS, in one transaction. If the guard CAS loses, the fence rolls back so the
     * caller records a NOT_SENT SKIPPED in a separate short transaction instead of sending.
     */
    public boolean applySendFence(String runId, String credentialKey) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(credentialKey, "credentialKey");
        Boolean applied = transactions.execute(status -> {
            try {
                long now = clock.instant().toEpochMilli();
                FenceResult fence = runDao.applySendFence(runId, now);
                if (fence != FenceResult.APPLIED) {
                    status.setRollbackOnly();
                    return false;
                }
                guardDao.ensure(credentialKey, now);
                CredentialGuardDao.AcquireResult guard = guardDao.acquire(credentialKey, runId, now);
                if (guard != CredentialGuardDao.AcquireResult.ACQUIRED) {
                    status.setRollbackOnly();
                    return false;
                }
                runDao.bumpDataRevision(writeConnection());
                return true;
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Send fence transaction failed", exception);
            }
        });
        return Boolean.TRUE.equals(applied);
    }

    /**
     * Terminal transaction: one-shot CAS to the terminal state, release of this run's own
     * task slot and guard, and the 120 s cooldown persisted for UNKNOWN — all or nothing.
     * Late callbacks find the row terminal and cannot overwrite it.
     */
    public TerminalReceipt applyTerminal(TerminalRequest request) {
        Objects.requireNonNull(request, "request");
        TerminalReceipt receipt = transactions.execute(status -> {
            try {
                long now = clock.instant().toEpochMilli();
                TerminalWrite write = new TerminalWrite(
                        request.runId(),
                        request.state(),
                        request.dispatchStatus(),
                        now,
                        null,
                        request.reasonCode(),
                        request.safeAdminSummary(),
                        request.cpaHttpStatus(),
                        request.currentVerified());
                TerminalResult result = runDao.applyTerminal(write);
                boolean guardReleased = false;
                if (result == TerminalResult.APPLIED && request.credentialKey() != null) {
                    Long cooldownUntil = request.state() == RunTerminalType.UNKNOWN
                            ? now + UNKNOWN_COOLDOWN_MS : null;
                    guardReleased = guardDao.release(request.credentialKey(), request.runId(), cooldownUntil, now);
                    if (!guardReleased) {
                        // This run does not own the guard; refuse to release someone else's.
                        status.setRollbackOnly();
                        return new TerminalReceipt(TerminalResult.NOT_OWNER, false);
                    }
                }
                if (result == TerminalResult.APPLIED) {
                    runDao.bumpDataRevision(writeConnection());
                }
                return new TerminalReceipt(result, guardReleased);
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Terminal transaction failed", exception);
            }
        });
        return receipt != null ? receipt : new TerminalReceipt(TerminalResult.NOT_OWNER, false);
    }

    /**
     * Skip path for a claim that never got the send fence: NOT_SENT SKIPPED with NULL
     * started_at in one short transaction, releasing this run's own slot.
     */
    public boolean skipClaim(String runId, String reasonCode) {
        Objects.requireNonNull(runId, "runId");
        Boolean skipped = transactions.execute(status -> {
            try {
                boolean applied = runDao.skipClaim(runId, reasonCode, clock.instant().toEpochMilli());
                if (!applied) {
                    status.setRollbackOnly();
                    return false;
                }
                runDao.bumpDataRevision(writeConnection());
                return true;
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Skip transaction failed", exception);
            }
        });
        return Boolean.TRUE.equals(skipped);
    }

    /** Releases a task slot for a claim that never reached the fence. */
    public boolean releaseTaskSlot(String runId) throws SQLException {
        return runDao.releaseTaskSlot(runId);
    }

    /**
     * Credential guard HMAC over instance + real logical auth id (stable across same-id
     * physical rebuilds). The auth id cipher is decrypted only in memory for this HMAC and
     * is never logged, returned or persisted in plain text.
     */
    public String credentialKey(String instanceKey, String authIdCipher) {
        Objects.requireNonNull(instanceKey, "instanceKey");
        Objects.requireNonNull(authIdCipher, "authIdCipher");
        String realAuthId = crypto.decrypt(authIdCipher);
        return crypto.hash("monitor-guard:" + instanceKey + ":" + realAuthId);
    }

    /**
     * Conditional next_fire advance: only when the task row still carries the expected
     * revision and its current next_fire_at still equals the instant being claimed. This
     * prevents a second claim from advancing the pointer twice for one plan.
     */
    private Long advanceNextFire(String taskId, long scheduledAt, long expectedRevision) throws SQLException {
        Instant claimed = Instant.ofEpochMilli(scheduledAt);
        MonitorTask task = taskManager.requireForClaim(taskId);
        Instant successor = ScheduleIterator.of(task.cronExpression(), task.zoneId()).next(claimed);
        String sql = "UPDATE monitor_tasks SET next_fire_at = ? "
                + "WHERE task_id = ? AND schedule_revision = ? AND next_fire_at = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (successor != null) {
                statement.setLong(1, successor.toEpochMilli());
            } else {
                statement.setNull(1, java.sql.Types.BIGINT);
            }
            statement.setString(2, taskId);
            statement.setLong(3, expectedRevision);
            statement.setLong(4, scheduledAt);
            int updated = statement.executeUpdate();
            return updated == 1 && successor != null ? successor.toEpochMilli() : null;
        }
    }
}
