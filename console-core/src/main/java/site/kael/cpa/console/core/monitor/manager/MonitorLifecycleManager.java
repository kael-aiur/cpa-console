package site.kael.cpa.console.core.monitor.manager;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.RunPhase;
import site.kael.cpa.console.core.monitor.model.RunState;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T07 single-instance lifecycle (D5.2 / D5.4 / D4.3).
 * <p>
 * The owning process takes the OS file lock on the data directory before any scheduling
 * work; a second scheduler instance is refused at {@link #acquireSingletonLock} and never
 * reaches the store. Startup order is strictly migration-complete (already done by
 * {@code DatabaseConfig}) → file lock → durable recovery of old CLAIMED/DISPATCHING runs
 * and their guards in one transaction per run with the recovery cooldown — and the
 * strictly-recomputed future next_fire — committed atomically → tzdb certificate
 * re-validation → only then does the scanner loop start. Any recovery, certificate or
 * storage failure moves the manager to {@code FAULTED} and stops all new claims without
 * silently clearing guards. Shutdown reverses the order: stop claiming, cancel unsubmitted
 * work, then give already-submitted calls a bounded drain window; shutdown never adds a
 * POST. Downtime is recorded as identity-free half-open observation gaps (T09 coverage).
 */
public final class MonitorLifecycleManager {

    /** Lifecycle phases visible to isolation tests; POST evidence never flows through here. */
    public enum Phase { NEW, RECOVERING, RUNNING, STOPPING, FAULTED }

    /** Failure groups reported by {@link LifecycleException}; every group stops scheduling. */
    public enum Failure { LOCK_HELD, MIGRATION, RECOVERY, CERTIFICATE }

    /** Recovery outcome of one previously non-terminal run (D5.4). */
    public enum RecoveryOutcome { UNKNOWN_WITHOUT_SAMPLE, UNKNOWN_IN_SAMPLE, ALREADY_GONE }

    private final DataSource dataSource;
    private final RunClaimManager claimManager;
    private final TaskManager taskManager;
    private final ExecutionManager executionManager;
    private final RetentionManager retentionManager;
    private final MonitorCpaBindingManager bindingManager;
    private final MonitorConfigLockManager configLock;
    private final ObservationGapDao gapDao;
    private final RunDao runDao;
    private final CredentialGuardDao guardDao;
    private final Clock clock;
    private final Path lockDirectory;
    private final TransactionTemplate transactions;
    private final long tickIntervalMs;
    private final long retentionIntervalMs;
    private final long shutdownDrainMs;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.NEW);
    private final AtomicReference<String> faultReason = new AtomicReference<>(null);
    private final Object scannerLock = new Object();
    private Thread scannerThread;
    private volatile FileLock dataLock;
    private volatile RandomAccessFile lockChannel;
    private volatile boolean scannerStarted;
    private volatile boolean shutdownRequested;

    public MonitorLifecycleManager(
            DataSource dataSource,
            RunClaimManager claimManager,
            TaskManager taskManager,
            ExecutionManager executionManager,
            RetentionManager retentionManager,
            MonitorCpaBindingManager bindingManager,
            MonitorConfigLockManager configLock,
            ObservationGapDao gapDao,
            RunDao runDao,
            CredentialGuardDao guardDao,
            Clock clock,
            Path lockDirectory,
            PlatformTransactionManager transactionManager,
            long tickIntervalMs,
            long retentionIntervalMs,
            long shutdownDrainMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.claimManager = Objects.requireNonNull(claimManager, "claimManager");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.executionManager = Objects.requireNonNull(executionManager, "executionManager");
        this.retentionManager = Objects.requireNonNull(retentionManager, "retentionManager");
        this.bindingManager = Objects.requireNonNull(bindingManager, "bindingManager");
        this.configLock = Objects.requireNonNull(configLock, "configLock");
        this.gapDao = Objects.requireNonNull(gapDao, "gapDao");
        this.runDao = Objects.requireNonNull(runDao, "runDao");
        this.guardDao = Objects.requireNonNull(guardDao, "guardDao");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockDirectory = Objects.requireNonNull(lockDirectory, "lockDirectory");
        this.transactions =
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.tickIntervalMs = tickIntervalMs;
        this.retentionIntervalMs = retentionIntervalMs;
        this.shutdownDrainMs = shutdownDrainMs;
    }

    /** Stable local reason recorded on recovered UNKNOWN runs (D5.4). */
    public static final String REASON_PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED";

    /** Recovery report per non-terminal run, for the T07 isolation self-check. */
    public record RecoveryReport(int recoveredWithoutSample, int recoveredInSample) {
    }

    /** Raised when lifecycle startup fails; the scheduler never starts afterwards. */
    public static final class LifecycleException extends RuntimeException {
        private final Failure failure;

        LifecycleException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        LifecycleException(Failure failure, String message) {
            this(failure, message, null);
        }

        public Failure failure() {
            return failure;
        }
    }

    public Phase phase() {
        return phase.get();
    }

    /** Fault reason of the safe stop; null while the lifecycle is healthy. */
    public String faultReason() {
        return faultReason.get();
    }

    /** Durable marker row used to measure the previous downtime across a crash. */
    public record RecoveryMarker(long lastShutdownOrRecoveryMs) {
    }

    /**
     * The process-wide data-directory OS lock. A second scheduler process calling this
     * while the first still holds the lock fails with {@link Failure#LOCK_HELD} before any
     * database work. Held until {@link #stop()} releases it.
     */
    public void acquireSingletonLock() {
        if (phase.get() != Phase.NEW) {
            throw new LifecycleException(Failure.LOCK_HELD, "Lifecycle lock already attempted");
        }
        try {
            Files.createDirectories(lockDirectory);
            this.lockChannel = new RandomAccessFile(
                    lockDirectory.resolve("monitor-scheduler.lock").toFile(), "rw");
            try {
                this.dataLock = lockChannel.getChannel().tryLock();
            } catch (java.nio.channels.OverlappingFileLockException exception) {
                // Same-JVM second instance: reported exactly like a foreign process lock.
                this.dataLock = null;
                throw new LifecycleException(
                        Failure.LOCK_HELD,
                        "Another scheduler instance owns the data directory lock", exception);
            }
            if (this.dataLock == null) {
                throw new LifecycleException(
                        Failure.LOCK_HELD,
                        "Another scheduler process owns the data directory lock");
            }
        } catch (IOException exception) {
            throw new LifecycleException(Failure.LOCK_HELD, "Data directory lock failed", exception);
        }
    }

    /**
     * Full startup: singleton lock, durable recovery, certificate re-validation, binding
     * check and scanner start. Recovery strictly precedes retention; downtime is measured
     * from the previous durable marker and persisted before the first scan.
     */
    public void start(String configuredBaseUrl) {
        if (phase.get() == Phase.RUNNING) {
            return;
        }
        if (phase.get() == Phase.FAULTED) {
            throw new LifecycleException(Failure.RECOVERY, "Lifecycle already faulted: " + faultReason.get());
        }
        acquireSingletonLock();
        phase.set(Phase.RECOVERING);
        try {
            recoverInterruptedRuns();
        } catch (SQLException | RuntimeException exception) {
            fault(Failure.RECOVERY, "Startup recovery failed", exception);
            return;
        }
        try {
            revalidateCertificates();
        } catch (SQLException exception) {
            fault(Failure.CERTIFICATE, "Plan certificate re-validation failed", exception);
            return;
        } catch (MonitorLifecycleManagerLifecycleFailure exception) {
            fault(Failure.CERTIFICATE, "Plan certificate re-validation failed", exception);
            return;
        } catch (RuntimeException exception) {
            fault(Failure.CERTIFICATE, "Plan certificate re-validation failed", exception);
            return;
        }
        try {
            // D5.1a: the current configured entry must still match the durable binding;
            // a redirect is reported and must be re-enabled explicitly (never auto-rebound).
            bindingManager.verifyCurrentEntry(configuredBaseUrl);
        } catch (SQLException | RuntimeException exception) {
            fault(Failure.RECOVERY, "CPA binding verification failed", exception);
            return;
        }
        long nowMs = clock.instant().toEpochMilli();
        try {
            persistDowntimeMarkerAndGap(nowMs);
        } catch (SQLException exception) {
            fault(Failure.RECOVERY, "Downtime metadata persistence failed", exception);
            return;
        }
        startScanner();
        phase.set(Phase.RUNNING);
    }

    /**
     * Recovers every old CLAIMED or DISPATCHING run to UNKNOWN at the actual recovery
     * instant. CLAIMED/NOT_SENT keeps started_at NULL and stays outside the sample;
     * DISPATCHING/MAY_HAVE_SENT keeps its original started_at and enters the denominator.
     * The 120 s recovery cooldown takes max(existing_cooldown, recovery+120s) per credential,
     * an already-expired cooldown is not extended, and the terminal write, guard cooldown,
     * task-slot release and next-fire recompute commit in one transaction (D5.4).
     */
    public RecoveryReport recoverInterruptedRuns() throws SQLException {
        List<RunDao.RunRow> active = listNonTerminalRuns();
        int withoutSample = 0;
        int inSample = 0;
        if (active.isEmpty()) {
            // Even with nothing to recover, a new boot recomputes every enabled task's
            // strictly-future pointer from the current instant (D5.4 startup rule).
            for (MonitorTask task : taskManager.listAll()) {
                recomputeFutureNextFire(task.taskId(), clock.instant().toEpochMilli());
            }
            return new RecoveryReport(0, 0);
        }
        for (RunDao.RunRow run : active) {
            if (run.phase() == RunPhase.TERMINAL) {
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> {
                    try {
                        recoverOne(run);
                    } catch (SQLException exception) {
                        status.setRollbackOnly();
                        throw new IllegalStateException("Recovery write failed", exception);
                    }
                });
            } catch (IllegalStateException exception) {
                if (exception.getCause() instanceof SQLException) {
                    throw (SQLException) exception.getCause();
                }
                throw exception;
            }
            if (run.phase() == RunPhase.DISPATCHING
                    || run.dispatchStatus() == site.kael.cpa.console.core.monitor.model.DispatchStatus.MAY_HAVE_SENT) {
                inSample++;
            } else {
                withoutSample++;
            }
        }
        return new RecoveryReport(withoutSample, inSample);
    }

    /** One-run recovery path executed inside the caller's transaction. */
    private void recoverOne(RunDao.RunRow run) throws SQLException {
        long nowMs = clock.instant().toEpochMilli();
        RunPhase originalPhase = run.phase();
        boolean mayHaveSent = run.dispatchStatus()
                == site.kael.cpa.console.core.monitor.model.DispatchStatus.MAY_HAVE_SENT;
        // One-shot CAS from the non-terminal phase; a row already terminalized concurrently
        // keeps its own verdict and is not overwritten (D5.3 step 7 discipline).
        RunDao.TerminalResult result = runDao.applyTerminal(new RunDao.TerminalWrite(
                run.runId(), RunTerminalType.UNKNOWN,
                mayHaveSent ? site.kael.cpa.console.core.monitor.model.DispatchStatus.MAY_HAVE_SENT
                        : site.kael.cpa.console.core.monitor.model.DispatchStatus.NOT_SENT,
                nowMs, null, REASON_PROCESS_INTERRUPTED, null, null, null));
        if (result == RunDao.TerminalResult.NOT_OWNER || result == RunDao.TerminalResult.ALREADY_TERMINAL) {
            return;
        }
        String credentialKey = claimManager.credentialKey(run.instanceKey(), run.authIdCipher());
        // max(existing, recovery+120s): an unexpired cooldown continues, an expired one is
        // not extended (the guard row may not exist for a run that never reached the fence).
        // An unrelated active run keeps its guard: this recovery must not clear it.
        guardDao.ensure(credentialKey, nowMs);
        Optional<CredentialGuardDao.GuardRow> guard = guardDao.find(credentialKey);
        long newCooldown = nowMs + RunClaimManager.UNKNOWN_COOLDOWN_MS;
        Long effective = guard.isPresent() && guard.get().cooldownUntil() != null
                && guard.get().cooldownUntil() > newCooldown ? guard.get().cooldownUntil() : newCooldown;
        boolean heldByThisRun = guard.isPresent() && run.runId().equals(guard.get().activeRunId());
        String updateSql = heldByThisRun || (guard.isPresent() && guard.get().activeRunId() == null)
                ? "UPDATE monitor_credential_guards SET active_run_id = NULL, cooldown_until = ?, "
                        + "updated_at = ? WHERE credential_key = ?"
                : "UPDATE monitor_credential_guards SET cooldown_until = ?, updated_at = ? "
                        + "WHERE credential_key = ? AND active_run_id IS NOT NULL";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setLong(1, effective);
            statement.setLong(2, nowMs);
            statement.setString(3, credentialKey);
            statement.executeUpdate();
        }
        // D5.4: expired next_fire recomputes strictly from the startup instant; due-or-past
        // plans never replay. Plan-key uniqueness guarantees the old instant cannot re-POST.
        recomputeFutureNextFire(run.taskId(), nowMs);
        runDao.bumpDataRevision(DataSourceUtils.getConnection(dataSource));
    }

    /** Strictly-future next_fire for every enabled task, recomputed from the startup now. */
    private void revalidateCertificates() throws SQLException {
        CronPolicyValidator validator = new CronPolicyValidator(clock);
        List<MonitorTask> tasks = taskManager.listAll();
        for (MonitorTask task : tasks) {
            boolean certificateValid = validator.isCertificateValid(
                    parseCertificate(task.tzdbCertificate(), task),
                    task.cronExpression(), task.zoneId());
            if (!certificateValid) {
                // D5.2: a tzdb change invalidates the plan. Block the task durably and stop
                // startup — the scheduler never scans with a stale certificate; explicit
                // re-enable re-certifies and recomputes next.
                taskManager.markBlocked(task.taskId(), "TZDB_CERTIFICATE_INVALID");
                throw new MonitorLifecycleManagerLifecycleFailure(
                        "Task " + task.taskId() + " certificate invalid under tzdb "
                                + validator.tzdbVersion());
            }
            recomputeFutureNextFire(task.taskId(), clock.instant().toEpochMilli());
        }
    }

    /** Internal marker so start() can classify certificate failures precisely. */
    static final class MonitorLifecycleManagerLifecycleFailure extends RuntimeException {
        MonitorLifecycleManagerLifecycleFailure(String message) {
            super(message);
        }
    }

    /** Recomputes next_fire strictly from {@code nowMs}; past instants are never replayed. */
    private void recomputeFutureNextFire(String taskId, long nowMs) throws SQLException {
        Optional<MonitorTask> current = taskManager.find(taskId);
        if (current.isEmpty()) {
            return;
        }
        MonitorTask task = current.get();
        if (!task.enabled() || task.blockedReason() != null) {
            return;
        }
        try {
            Instant next = site.kael.cpa.console.core.monitor.schedule.ScheduleIterator
                    .of(task.cronExpression(), task.zoneId())
                    .next(Instant.ofEpochMilli(nowMs));
            String sql = "UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?";
            try (Connection connection = DataSourceUtils.getConnection(dataSource);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                if (next != null) {
                    statement.setLong(1, next.toEpochMilli());
                } else {
                    statement.setNull(1, java.sql.Types.BIGINT);
                }
                statement.setString(2, taskId);
                statement.executeUpdate();
            }
        } catch (IllegalArgumentException ignored) {
            // Unparsable plan text is already blocked through the certificate path; scanning
            // must not throw here and silently skip the rest of the recovery.
        }
    }

    /** Package-private revalidation hook for the T07.R2 certificate-failure self-check. */
    void revalidateCertificatesForTesting() throws SQLException {
        revalidateCertificates();
    }

    /** Parses the stored certificate text back into its bound components. */
    private site.kael.cpa.console.core.monitor.model.CronCertificate parseCertificate(
            String stored, MonitorTask task) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String[] parts = stored.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            // Legacy stored certificates (v3 and earlier) have no base-point field; they
            // are whole-future domain certificates, so parse them with the epoch-minimum
            // base point. Algorithm version 4 re-issues with the real certification instant.
            return new site.kael.cpa.console.core.monitor.model.CronCertificate(
                    task.cronExpression(), task.zoneId(), parts[0], Integer.parseInt(parts[1]),
                    Long.parseLong(parts[2]), Instant.ofEpochMilli(Long.MIN_VALUE),
                    Instant.ofEpochMilli(Long.parseLong(parts[3])));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Persists the previous downtime as a half-open observation gap from the last durable
     * marker to the current instant, then records the new marker. The crash-window case is
     * covered because the marker is written before every first scan after recovery.
     */
    private void persistDowntimeMarkerAndGap(long nowMs) throws SQLException {
        transactions.executeWithoutResult(status -> {
            try {
                long previous = gapDao.readMarker(nowMs);
                if (nowMs > previous) {
                    gapDao.insertGap(previous, nowMs);
                }
                gapDao.writeMarker(nowMs);
            } catch (SQLException exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Gap persistence failed", exception);
            }
        });
    }

    private void startScanner() {
        synchronized (scannerLock) {
            if (scannerStarted) {
                return;
            }
            scannerStarted = true;
            scannerThread = new Thread(this::scannerLoop, "monitor-scheduler");
            scannerThread.setDaemon(true);
            scannerThread.start();
        }
    }

    /**
     * Single scheduler loop: one shared now per tick, due claims, bounded executor with
     * no queue, low-frequency retention strictly after recovery. Every storage failure
     * triggers the fault stop; the scanner never retries a POST (D5.2).
     */
    private void scannerLoop() {
        long lastRetention = 0L;
        while (!shutdownRequested && Phase.RUNNING == phase.get()) {
            long nowMs = clock.instant().toEpochMilli();
            try {
                List<ExecutionManager.DuePlan> due = collectDuePlans(nowMs);
                if (!due.isEmpty()) {
                    executionManager.claimDuePlans(due);
                }
                List<String> claimed = listClaimedRunIds();
                for (String runId : claimed) {
                    if (shutdownRequested) {
                        break;
                    }
                    executionManager.dispatchClaimed(runId);
                }
                if (nowMs - lastRetention >= retentionIntervalMs) {
                    retentionManager.purgeExpiredBatch(this::stoppingOrFaulted);
                    lastRetention = nowMs;
                }
            } catch (SQLException | RuntimeException exception) {
                // Safe stop: guards stay, scans stop, in-flight work drains at stop().
                fault(Failure.RECOVERY, "Scheduler storage failure", exception);
                return;
            }
            sleepTick();
        }
    }

    private List<ExecutionManager.DuePlan> collectDuePlans(long nowMs) throws SQLException {
        List<MonitorTask> tasks = taskManager.listAll();
        List<ExecutionManager.DuePlan> due = new ArrayList<>();
        for (MonitorTask task : tasks) {
            if (!task.enabled() || task.blockedReason() != null || task.nextFireAt() == null) {
                continue;
            }
            if (task.nextFireAt() <= nowMs) {
                due.add(new ExecutionManager.DuePlan(
                        task.taskId(), task.nextFireAt(), task.scheduleRevision()));
            }
        }
        return due;
    }

    /** Claimed run ids whose fence has not yet flipped the dispatch status. */
    private List<String> listClaimedRunIds() throws SQLException {
        String sql = "SELECT run_id FROM monitor_runs WHERE phase = 'CLAIMED' AND state = 'PENDING' "
                + "AND dispatch_status = 'NOT_SENT' ORDER BY run_seq";
        List<String> ids = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
        }
        return ids;
    }

    private boolean stoppingOrFaulted() {
        return shutdownRequested || phase.get() == Phase.FAULTED;
    }

    private void sleepTick() {
        try {
            Thread.sleep(tickIntervalMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Fault stop: keep guards, stop scans, record the reason once. */
    private void fault(Failure failure, String message, Throwable cause) {
        faultReason.compareAndSet(null, failure.name() + ": " + message
                + (cause == null ? "" : " / " + cause.getClass().getSimpleName()));
        phase.set(Phase.FAULTED);
    }

    /**
     * Graceful shutdown: stop claiming first, cancel unsubmitted work, then bound the
     * already-submitted calls' drain. Never issues a new POST; guards and cooldowns stay.
     */
    public void stop() {
        shutdownRequested = true;
        Phase current = phase.get();
        if (current == Phase.STOPPING || current == Phase.NEW || current == Phase.FAULTED) {
            releaseLock();
            return;
        }
        phase.set(Phase.STOPPING);
        synchronized (scannerLock) {
            Thread thread = scannerThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(shutdownDrainMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        releaseLock();
    }

    private void releaseLock() {
        try {
            if (dataLock != null) {
                dataLock.release();
                dataLock = null;
            }
        } catch (IOException ignored) {
            // The JVM closing the channel releases the OS lock anyway.
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
                lockChannel = null;
            }
        } catch (IOException ignored) {
            // Same as above.
        }
    }

    /** Non-terminal run rows for recovery, oldest first (stable by run_seq). */
    private List<RunDao.RunRow> listNonTerminalRuns() throws SQLException {
        String sql = "SELECT run_seq, run_id, task_id, scheduled_at, schedule_revision, task_safe_name, "
                + "task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot, "
                + "phase, state, dispatch_status, owns_task_slot, claimed_at, started_at, finished_at, "
                + "duration_ms, reason_code, safe_admin_summary, cpa_http_status, current_verified "
                + "FROM monitor_runs WHERE phase != 'TERMINAL' ORDER BY run_seq";
        List<RunDao.RunRow> rows = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(mapRun(resultSet));
            }
        }
        return rows;
    }

    /** Minimal row mapper mirroring {@link RunDao#mapRun(ResultSet)} without exposing it. */
    private RunDao.RunRow mapRun(ResultSet resultSet) throws SQLException {
        Long startedAt = resultSet.getObject("started_at") == null ? null : resultSet.getLong("started_at");
        Long finishedAt = resultSet.getObject("finished_at") == null ? null : resultSet.getLong("finished_at");
        Long durationMs = resultSet.getObject("duration_ms") == null ? null : resultSet.getLong("duration_ms");
        Integer httpStatus = resultSet.getObject("cpa_http_status") == null
                ? null : resultSet.getInt("cpa_http_status");
        Integer verified = resultSet.getObject("current_verified") == null
                ? null : resultSet.getInt("current_verified");
        return new RunDao.RunRow(
                resultSet.getLong("run_seq"),
                resultSet.getString("run_id"),
                resultSet.getString("task_id"),
                resultSet.getLong("scheduled_at"),
                resultSet.getLong("schedule_revision"),
                resultSet.getString("task_safe_name"),
                resultSet.getString("task_model_display"),
                resultSet.getString("cpa_instance_key"),
                resultSet.getString("auth_id_cipher"),
                resultSet.getString("auth_index_cipher"),
                resultSet.getString("provider_snapshot"),
                RunPhase.valueOf(resultSet.getString("phase")),
                RunState.valueOf(resultSet.getString("state")),
                site.kael.cpa.console.core.monitor.model.DispatchStatus.valueOf(
                        resultSet.getString("dispatch_status")),
                resultSet.getInt("owns_task_slot") == 1,
                resultSet.getLong("claimed_at"),
                startedAt,
                finishedAt,
                durationMs,
                resultSet.getString("reason_code"),
                resultSet.getString("safe_admin_summary"),
                httpStatus,
                verified == null ? null : verified == 1);
    }
}
