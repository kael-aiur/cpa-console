package site.kael.cpa.console.core.monitor.dao;

import site.kael.cpa.console.core.monitor.model.DispatchStatus;
import site.kael.cpa.console.core.monitor.model.MonitorDataRevision;
import site.kael.cpa.console.core.monitor.model.RunPhase;
import site.kael.cpa.console.core.monitor.model.RunState;
import site.kael.cpa.console.core.monitor.model.RunTerminalType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

/**
 * T05 run state machine DAO (D5.1 / D5.3). Every mutation is a narrow CAS-style update;
 * no method accepts arbitrary SQL state transitions. The plan key UNIQUE(task_id,
 * scheduled_at) stays revision-free so editing a cron back to the same absolute instant
 * cannot create a second verification attempt for the same plan.
 */
public final class RunDao {

    /** A run record as persisted; ciphers stay restricted and are never decoded here. */
    public record RunRow(
            long runSeq,
            String runId,
            String taskId,
            long scheduledAt,
            long scheduleRevision,
            String taskSafeName,
            String taskModelDisplay,
            String instanceKey,
            String authIdCipher,
            String authIndexCipher,
            String providerSnapshot,
            RunPhase phase,
            RunState state,
            DispatchStatus dispatchStatus,
            boolean ownsTaskSlot,
            long claimedAt,
            Long startedAt,
            Long finishedAt,
            Long durationMs,
            String reasonCode,
            String safeAdminSummary,
            Integer cpaHttpStatus,
            Boolean currentVerified) {
    }

    /** Insert result of the atomic claim attempt. */
    public enum ClaimResult { INSERTED, DUPLICATE_PLAN_KEY }

    /** Terminal CAS outcome. */
    public enum TerminalResult { APPLIED, ALREADY_TERMINAL, NOT_OWNER }

    /** Send-fence CAS outcome. */
    public enum FenceResult { APPLIED, NOT_IN_CLAIMED_STATE }

    private final DataSource dataSource;

    public RunDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Atomically inserts a claimed run for the plan key. A duplicate (task_id, scheduled_at)
     * is reported, never silently overwritten, regardless of which schedule_revision each
     * caller carried.
     */
    public ClaimResult insertClaim(ClaimInsert insert) throws SQLException {
        String sql = "INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, "
                + "task_safe_name, task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, "
                + "provider_snapshot, phase, state, dispatch_status, owns_task_slot, claimed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', 'PENDING', 'NOT_SENT', 0, ?)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, insert.runId());
            statement.setString(2, insert.taskId());
            statement.setLong(3, insert.scheduledAt());
            statement.setLong(4, insert.scheduleRevision());
            statement.setString(5, insert.taskSafeName());
            statement.setString(6, insert.taskModelDisplay());
            statement.setString(7, insert.instanceKey());
            statement.setString(8, insert.authIdCipher());
            statement.setString(9, insert.authIndexCipher());
            statement.setString(10, insert.providerSnapshot());
            statement.setLong(11, insert.claimedAt());
            try {
                statement.executeUpdate();
                return ClaimResult.INSERTED;
            } catch (SQLException exception) {
                if (isUniqueViolation(exception)) {
                    return ClaimResult.DUPLICATE_PLAN_KEY;
                }
                throw exception;
            }
        }
    }

    /** Claim insert payload; identity fields come from the task snapshot, never user input. */
    public record ClaimInsert(
            String runId,
            String taskId,
            long scheduledAt,
            long scheduleRevision,
            String taskSafeName,
            String taskModelDisplay,
            String instanceKey,
            String authIdCipher,
            String authIndexCipher,
            String providerSnapshot,
            long claimedAt) {
    }

    /**
     * Conditionally takes the per-task slot: the row must still be CLAIMED/PENDING/NOT_SENT
     * and currently own no slot. A row that did not get the slot can still terminate as a
     * SKIPPED record without disturbing the active owner.
     */
    public boolean acquireTaskSlot(String runId) throws SQLException {
        String sql = "UPDATE monitor_runs SET owns_task_slot = 1 "
                + "WHERE run_id = ? AND phase = 'CLAIMED' AND state = 'PENDING' "
                + "AND dispatch_status = 'NOT_SENT' AND owns_task_slot = 0";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try {
                return statement.executeUpdate() == 1;
            } catch (SQLException exception) {
                // The partial unique index enforces a single owner at the storage layer;
                // a constraint failure means another run owns the slot.
                if (exception.getMessage() != null
                        && exception.getMessage().toUpperCase().contains("UNIQUE")) {
                    return false;
                }
                throw exception;
            }
        }
    }

    /** Releases the task slot only when this run actually owns it. */
    public boolean releaseTaskSlot(String runId) throws SQLException {
        String sql = "UPDATE monitor_runs SET owns_task_slot = 0 WHERE run_id = ? AND owns_task_slot = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Terminalizes a non-terminal claim as SKIPPED: NOT_SENT, started_at NULL, slot released.
     * Fails when the run is no longer CLAIMED/PENDING (the state machine forbids a skip after
     * the send fence).
     */
    public boolean skipClaim(String runId, String reasonCode, long finishedAt) throws SQLException {
        // A row that never won the slot can also be skipped (D5.1: 未取槽记录仍可终结为
        // SKIPPED); owns_task_slot=0 releases the slot only when this run actually owned it.
        String sql = "UPDATE monitor_runs SET phase = 'TERMINAL', state = 'SKIPPED', "
                + "reason_code = ?, finished_at = ?, duration_ms = ?, owns_task_slot = 0 "
                + "WHERE run_id = ? AND phase = 'CLAIMED' AND state = 'PENDING'";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reasonCode);
            statement.setLong(2, finishedAt);
            statement.setLong(3, Math.max(0, finishedAt - readClaimedAt(runId)));
            statement.setString(4, runId);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Send fence CAS: CLAIMED → DISPATCHING with MAY_HAVE_SENT and started_at. This is the
     * durable intent barrier: once applied, the caller may submit the HTTP send; a PATCH
     * that loses this CAS cannot produce a second POST.
     */
    public FenceResult applySendFence(String runId, long startedAt) throws SQLException {
        // D5.3 step 6: the fence persists the durable send intent. 004 constrains
        // state='PENDING' to phase='CLAIMED', so an in-flight run stays CLAIMED/PENDING with
        // dispatch_status flipping to MAY_HAVE_SENT plus started_at; phase=DISPATCHING is
        // applied together with the verdict by the terminal CAS.
        String sql = "UPDATE monitor_runs SET dispatch_status = 'MAY_HAVE_SENT', "
                + "started_at = ? WHERE run_id = ? AND phase = 'CLAIMED' AND state = 'PENDING' "
                + "AND dispatch_status = 'NOT_SENT' AND started_at IS NULL";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startedAt);
            statement.setString(2, runId);
            int updated = statement.executeUpdate();
            return updated == 1 ? FenceResult.APPLIED : FenceResult.NOT_IN_CLAIMED_STATE;
        }
    }

    /**
     * Reverts a send fence after a proven pre-submission failure (D5.3 step 6): clears
     * started_at and restores NOT_SENT while still CLAIMED/PENDING. The task slot stays
     * held so the slot owner keeps its own execution budget during the same attempt.
     */
    public boolean revertSendFence(String runId) throws SQLException {
        String sql = "UPDATE monitor_runs SET dispatch_status = 'NOT_SENT', started_at = NULL "
                + "WHERE run_id = ? AND phase = 'CLAIMED' AND state = 'PENDING' "
                + "AND dispatch_status = 'MAY_HAVE_SENT' AND started_at IS NOT NULL";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            return statement.executeUpdate() == 1;
        }
    }

    /** Loads a run by its public id. */
    public Optional<RunRow> findByRunId(String runId) throws SQLException {
        String sql = "SELECT run_seq, run_id, task_id, scheduled_at, schedule_revision, task_safe_name, "
                + "task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot, "
                + "phase, state, dispatch_status, owns_task_slot, claimed_at, started_at, finished_at, "
                + "duration_ms, reason_code, safe_admin_summary, cpa_http_status, current_verified "
                + "FROM monitor_runs WHERE run_id = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRun(resultSet));
            }
        }
    }

    /** Active (non-terminal) run for a task, if any. */
    public Optional<RunRow> findActiveByTask(String taskId) throws SQLException {
        String sql = "SELECT run_seq, run_id, task_id, scheduled_at, schedule_revision, task_safe_name, "
                + "task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot, "
                + "phase, state, dispatch_status, owns_task_slot, claimed_at, started_at, finished_at, "
                + "duration_ms, reason_code, safe_admin_summary, cpa_http_status, current_verified "
                + "FROM monitor_runs WHERE task_id = ? AND phase != 'TERMINAL' LIMIT 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRun(resultSet));
            }
        }
    }

    /**
     * One-shot terminal CAS (D5.3 step 7): from CLAIMED or DISPATCHING with finished_at NULL.
     * The first caller wins; later callbacks, including a late success after UNKNOWN, find
     * the row terminal and are refused. The caller releases only its own guard.
     */
    public TerminalResult applyTerminal(TerminalWrite write) throws SQLException {
        String sql = "UPDATE monitor_runs SET phase = 'TERMINAL', state = ?, dispatch_status = ?, "
                + "finished_at = ?, duration_ms = ?, reason_code = COALESCE(?, reason_code), "
                + "safe_admin_summary = COALESCE(?, safe_admin_summary), "
                + "cpa_http_status = COALESCE(?, cpa_http_status), current_verified = ?, owns_task_slot = 0 "
                + "WHERE run_id = ? AND phase IN ('CLAIMED', 'DISPATCHING') AND finished_at IS NULL";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, write.state().name());
            statement.setString(2, write.dispatchStatus().name());
            statement.setLong(3, write.finishedAt());
            if (write.durationMs() != null) {
                statement.setLong(4, Math.max(0, write.durationMs()));
            } else {
                statement.setNull(4, Types.BIGINT);
            }
            statement.setString(5, write.reasonCode());
            statement.setString(6, write.safeAdminSummary());
            if (write.cpaHttpStatus() != null) {
                statement.setInt(7, write.cpaHttpStatus());
            } else {
                statement.setNull(7, Types.INTEGER);
            }
            if (write.currentVerified() != null) {
                statement.setInt(8, write.currentVerified() ? 1 : 0);
            } else {
                statement.setNull(8, Types.SMALLINT);
            }
            statement.setString(9, write.runId());
            int updated = statement.executeUpdate();
            if (updated == 1) {
                return TerminalResult.APPLIED;
            }
            // Distinguish "already terminal" from "not the owner of the slot/guard".
            Optional<RunRow> existing = findByRunId(write.runId());
            if (existing.isEmpty()) {
                return TerminalResult.NOT_OWNER;
            }
            return existing.get().phase() == RunPhase.TERMINAL
                    ? TerminalResult.ALREADY_TERMINAL : TerminalResult.NOT_OWNER;
        }
    }

    /** Terminal write payload; current_verified stays NULL for non-strict evidence. */
    public record TerminalWrite(
            String runId,
            RunTerminalType state,
            DispatchStatus dispatchStatus,
            long finishedAt,
            Long durationMs,
            String reasonCode,
            String safeAdminSummary,
            Integer cpaHttpStatus,
            Boolean currentVerified) {

        public TerminalWrite {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(dispatchStatus, "dispatchStatus");
        }
    }

    /** Current shared monitor data revision (D6.3). */
    public long currentDataRevision() throws SQLException {
        String sql = "SELECT revision FROM monitor_data_revision WHERE id = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** Increments the shared monitor data revision; call inside the same write transaction. */
    public void bumpDataRevision(Connection connection) throws SQLException {
        String sql = "INSERT INTO monitor_data_revision (id, revision) VALUES (1, 1) "
                + "ON CONFLICT (id) DO UPDATE SET revision = revision + 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private long readClaimedAt(String runId) throws SQLException {
        Optional<RunRow> row = findByRunId(runId);
        return row.map(RunRow::claimedAt).orElse(0L);
    }

    private RunRow mapRun(ResultSet resultSet) throws SQLException {
        Long finishedAt = resultSet.getObject("finished_at") == null ? null : resultSet.getLong("finished_at");
        Long startedAt = resultSet.getObject("started_at") == null ? null : resultSet.getLong("started_at");
        Long durationMs = resultSet.getObject("duration_ms") == null ? null : resultSet.getLong("duration_ms");
        Integer httpStatus = resultSet.getObject("cpa_http_status") == null ? null : resultSet.getInt("cpa_http_status");
        Integer verified = resultSet.getObject("current_verified") == null ? null : resultSet.getInt("current_verified");
        return new RunRow(
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
                DispatchStatus.valueOf(resultSet.getString("dispatch_status")),
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

    private static boolean isUniqueViolation(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toUpperCase();
        return message.contains("UNIQUE") || message.contains("CONSTRAINT") || exception.getSQLState() != null;
    }
}
