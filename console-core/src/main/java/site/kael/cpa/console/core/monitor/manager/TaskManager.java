package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.model.CronCertificate;
import site.kael.cpa.console.core.monitor.model.MonitorDataRevision;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;
import site.kael.cpa.console.core.monitor.schedule.ScheduleIterator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

/**
 * T04 task configuration domain (D3 / D4 / D5.3 / D7): creates monitoring tasks with a fixed,
 * immutable logical target and edits only the safe fields (name, cron, zone, enabled).
 * <p>
 * Contract highlights:
 * <ul>
 *   <li>Creation and enabling perform live read-only validation of the bound logical identity
 *       and model through {@link CpaIdentityResolver#verifyBoundTarget}; the resolver issues
 *       GETs only and never a verify POST.</li>
 *   <li>The immutable target (instance key, auth id, auth index, route model, provider
 *       snapshot) is written exactly once at creation; edit requests cannot carry or change
 *       it and updates never rewrite those columns.</li>
 *   <li>A submitted or edited plan must have a strictly-future first trigger; disabling clears
 *       next_fire_at. Every accepted edit increments both schedule_revision and the shared
 *       monitor data revision inside one transaction.</li>
 *   <li>An expected-revision conflict fails without overwriting anything
 *       (compare-and-set on row_version).</li>
 *   <li>CONFIG_ERROR blocks persist until an explicit re-enable with live read-only
 *       revalidation; a target rejection is recorded per run and never auto-disables a
 *       task.</li>
 * </ul>
 * All edits take {@link MonitorConfigLockManager} inside the same order PATCH and the T06
 * send fence share, so an old claim can never race a patch into a second dispatch.
 */
public final class TaskManager {

    /** Failure modes reported through {@link TaskManagerException}. */
    public enum ErrorCode {
        /** Task id does not exist. */
        TASK_NOT_FOUND,
        /** The request carried immutable-target fields or an unknown/empty body. */
        IMMUTABLE_FIELD_REJECTED,
        /** row_version-based expected_revision did not match; nothing was changed. */
        REVISION_CONFLICT,
        /** Stored candidate data could not be decoded; the target must be re-selected. */
        CANDIDATE_EXPIRED,
        /** Live id+index+model validation failed (identity missing/ambiguous/model invalid). */
        IDENTITY_INVALID,
        /** Cron or zone failed D4 validation; the plan is not saved. */
        PLAN_INVALID
    }

    /** Domain failure carrying a stable local code, never upstream text. */
    public static final class TaskManagerException extends RuntimeException {
        private final ErrorCode code;

        TaskManagerException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }

    /** Request to create a task from a live candidate bound by the caller. */
    public record CreateRequest(
            String safeName,
            String cronExpression,
            String zoneId,
            String instanceKey,
            String authId,
            String authIndex,
            String routeModel,
            String safeModelDisplay,
            String providerSnapshot) {

        public CreateRequest {
            Objects.requireNonNull(safeName, "safeName");
            Objects.requireNonNull(cronExpression, "cronExpression");
            Objects.requireNonNull(zoneId, "zoneId");
            Objects.requireNonNull(instanceKey, "instanceKey");
            Objects.requireNonNull(authId, "authId");
            Objects.requireNonNull(authIndex, "authIndex");
            Objects.requireNonNull(routeModel, "routeModel");
        }
    }

    /**
     * PATCH payload. Only these four fields are recognised; the transport layer must reject
     * any other field (instance/auth/model) before it reaches this domain object, so an
     * immutable-target change is structurally impossible here.
     */
    public record UpdateRequest(
            Long expectedRevision,
            String safeName,
            String cronExpression,
            String zoneId,
            Boolean enabled) {

        public boolean isEmpty() {
            return safeName == null && cronExpression == null && zoneId == null && enabled == null;
        }
    }

    /** Read-side safe snapshot. Ciphers are only ever decrypted for outbound calls, never here. */
    public record TaskSnapshot(
            String taskId,
            String safeName,
            String safeModelDisplay,
            String providerSnapshot,
            boolean enabled,
            String blockedReason,
            long scheduleRevision,
            long rowVersion,
            String cronExpression,
            String zoneId,
            String tzdbCertificate,
            Long nextFireAt,
            long createdAt,
            long updatedAt) {
    }

    /** Narrow live-validation contract consumed from T02 (same id+index+model GETs). */
    public interface BoundTargetValidator {
        CpaIdentityResolver.BoundTarget verifyBoundTarget(
                String instanceKey, String authId, String authIndex, String routeModel);
    }

    private final DataSource dataSource;
    private final ApiKeyCrypto crypto;
    private final Clock clock;
    private final CronPolicyValidator cronValidator;
    private final BoundTargetValidator identityResolver;
    private final MonitorConfigLockManager configLock;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> taskLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public TaskManager(
            DataSource dataSource,
            ApiKeyCrypto crypto,
            Clock clock,
            CronPolicyValidator cronValidator,
            BoundTargetValidator identityResolver,
            MonitorConfigLockManager configLock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cronValidator = Objects.requireNonNull(cronValidator, "cronValidator");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.configLock = Objects.requireNonNull(configLock, "configLock");
    }

    /**
     * Creates a new task, default-disabled, with the immutable target fixed for its lifetime.
     * Performs live read-only identity/model validation and full-future cron certification;
     * an invalid plan or identity saves nothing.
     */
    public MonitorTask create(CreateRequest request) throws SQLException {
        Objects.requireNonNull(request, "request");
        if (request.safeName() == null || !SafeNameValidator.isSafe(request.safeName(), null)) {
            throw new TaskManagerException(ErrorCode.IDENTITY_INVALID, "Task name is not a safe display name");
        }
        // Cheap local plan certification first; a syntactically invalid plan never touches
        // the live identity feed (D4: invalid plans are not saved and no GET is spent).
        CronCertificate certificate;
        try {
            certificate = cronValidator.certify(request.cronExpression(), request.zoneId());
        } catch (IllegalArgumentException exception) {
            throw new TaskManagerException(ErrorCode.PLAN_INVALID, exception.getMessage());
        }
        // Live read-only validation of identity + model (GET only, verify count stays 0).
        try {
            identityResolver.verifyBoundTarget(
                    request.instanceKey(), request.authId(), request.authIndex(), request.routeModel());
        } catch (CpaIdentityResolver.IdentityResolutionException exception) {
            throw new TaskManagerException(ErrorCode.IDENTITY_INVALID, exception.getMessage());
        }
        Instant now = clock.instant();
        Instant firstFire = ScheduleIterator.of(certificate.cronExpression(), certificate.zoneId()).next(now);
        if (firstFire == null || !firstFire.isAfter(now)) {
            throw new TaskManagerException(ErrorCode.PLAN_INVALID, "Plan has no strictly-future trigger");
        }
        String taskId = UUID.randomUUID().toString();
        insertTask(request, taskId, certificate, firstFire.toEpochMilli(), now.toEpochMilli());
        bumpDataRevision();
        return readTask(taskId).orElseThrow(() -> new IllegalStateException("Task vanished after insert"));
    }

    /**
     * Applies a safe-field edit under the shared config lock. The expected revision is a
     * compare-and-set on row_version: a stale or concurrent edit fails without overwriting.
     * Plan/zone edits re-certify and recompute the strictly-future next fire; disabling clears
     * next_fire_at. Any accepted edit bumps schedule_revision and the data revision.
     */
    public MonitorTask update(String taskId, UpdateRequest request) throws SQLException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(request, "request");
        if (request.isEmpty()) {
            throw new TaskManagerException(ErrorCode.IMMUTABLE_FIELD_REJECTED, "Empty update body");
        }
        if (request.expectedRevision() == null) {
            throw new TaskManagerException(ErrorCode.REVISION_CONFLICT, "expected_revision is required for updates");
        }
        return configLock.readLocked(() -> {
            try {
                return applyUpdate(taskId, request);
            } catch (SQLException exception) {
                throw new IllegalStateException("Task update failed", exception);
            }
        });
    }

    /**
     * Explicit re-enable of a blocked task: live read-only revalidation of the immutable
     * target precedes the write. Blocked recovery cannot happen implicitly.
     */
    public MonitorTask revalidateAndEnable(String taskId, UpdateRequest request) throws SQLException {
        if (request.enabled() == null || !request.enabled()) {
            throw new TaskManagerException(ErrorCode.IDENTITY_INVALID, "Blocked recovery must explicitly enable");
        }
        return update(taskId, request);
    }

    /** Read-only task lookup for API surfaces. */
    public Optional<MonitorTask> find(String taskId) throws SQLException {
        return readTask(taskId);
    }

    /**
     * Per-task edit lock shared by PATCH and the T06 final fence. The lock object is
     * intentionally process-local; SQLite remains the persistence authority and the unique
     * slot/guard constraints remain the durable race guards.
     */
    public java.util.concurrent.locks.ReentrantLock taskLock(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return taskLocks.computeIfAbsent(taskId, id -> new java.util.concurrent.locks.ReentrantLock());
    }

    /** All tasks ordered by creation; safe display fields only. */
    public List<MonitorTask> listAll() throws SQLException {
        String sql = "SELECT task_id, safe_name, route_model_cipher, safe_model_display, cpa_instance_key, "
                + "auth_id_cipher, auth_index_cipher, provider_snapshot, enabled, blocked_reason, schedule_revision, "
                + "row_version, cron_expression, zone_id, tzdb_certificate, next_fire_at, created_at, updated_at "
                + "FROM monitor_tasks ORDER BY created_at, task_id";
        List<MonitorTask> tasks = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                tasks.add(mapTask(resultSet));
            }
        }
        return List.copyOf(tasks);
    }

    /**
     * Records a configuration failure on the task. The block persists until an explicit
     * re-enable revalidates; the reason is a stable local code, never upstream text.
     */
    public void markBlocked(String taskId, String blockedReason) throws SQLException {
        Objects.requireNonNull(blockedReason, "blockedReason");
        String sql = "UPDATE monitor_tasks SET blocked_reason = ?, enabled = 0, updated_at = ?, "
                + "row_version = row_version + 1 WHERE task_id = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, blockedReason);
            statement.setLong(2, clock.instant().toEpochMilli());
            statement.setString(3, taskId);
            statement.executeUpdate();
        }
        bumpDataRevision();
    }

    /**
     * Marks a target rejection for bookkeeping. A FAILED verdict is never a reason to
     * auto-disable: the task keeps its schedule and runs again at the next cron time.
     */
    public void recordTargetRejected(String taskId) throws SQLException {
        String sql = "UPDATE monitor_tasks SET updated_at = updated_at, enabled = enabled WHERE task_id = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    /** Loads the current snapshot for a run-phase claim (T05). */
    public MonitorTask requireForClaim(String taskId) throws SQLException {
        return readTask(taskId).orElseThrow(
                () -> new TaskManagerException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
    }

    private MonitorTask applyUpdate(String taskId, UpdateRequest request) throws SQLException {
        MonitorTask existing = readTask(taskId)
                .orElseThrow(() -> new TaskManagerException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
        if (request.expectedRevision() != existing.rowVersion()) {
            throw new TaskManagerException(ErrorCode.REVISION_CONFLICT, "Task was modified concurrently");
        }
        String cron = request.cronExpression() != null ? request.cronExpression().trim() : existing.cronExpression();
        String zone = request.zoneId() != null ? request.zoneId().trim() : existing.zoneId();
        Long nextFire;
        CronCertificate certificate = null;
        if (request.cronExpression() != null || request.zoneId() != null) {
            try {
                certificate = cronValidator.certify(cron, zone);
            } catch (IllegalArgumentException exception) {
                throw new TaskManagerException(ErrorCode.PLAN_INVALID, exception.getMessage());
            }
            Instant now = clock.instant();
            Instant firstFire = ScheduleIterator.of(certificate.cronExpression(), certificate.zoneId()).next(now);
            if (firstFire == null || !firstFire.isAfter(now)) {
                throw new TaskManagerException(ErrorCode.PLAN_INVALID, "Plan has no strictly-future trigger");
            }
            nextFire = firstFire.toEpochMilli();
        } else {
            Instant now = clock.instant();
            if (request.enabled() != null && !request.enabled()) {
                nextFire = null;
            } else {
                nextFire = existing.nextFireAt();
                if (request.enabled() != null && request.enabled()
                        && (nextFire == null || nextFire <= now.toEpochMilli())) {
                    Instant firstFire = ScheduleIterator.of(existing.cronExpression(), existing.zoneId()).next(now);
                    if (firstFire == null || !firstFire.isAfter(now)) {
                        throw new TaskManagerException(ErrorCode.PLAN_INVALID, "Plan has no strictly-future trigger");
                    }
                    nextFire = firstFire.toEpochMilli();
                }
            }
        }
        String name = request.safeName() != null ? request.safeName().trim() : existing.safeName();
        if (!name.equals(existing.safeName()) && !SafeNameValidator.isSafe(name, null)) {
            throw new TaskManagerException(ErrorCode.IDENTITY_INVALID, "Task name is not a safe display name");
        }
        boolean enabled = request.enabled() != null ? request.enabled() : existing.enabled();
        // Re-enabling (blocked recovery) requires live read-only revalidation of the immutable
        // target. Ciphers decode only in memory here, never into logs or DTOs.
        if (enabled && !existing.enabled()) {
            try {
                identityResolver.verifyBoundTarget(
                        existing.instanceKey(),
                        crypto.decrypt(existing.authIdCipher()),
                        crypto.decrypt(existing.authIndexCipher()),
                        crypto.decrypt(existing.routeModelCipher()));
            } catch (CpaIdentityResolver.IdentityResolutionException
                    | IllegalArgumentException | IllegalStateException exception) {
                throw new TaskManagerException(ErrorCode.CANDIDATE_EXPIRED,
                        "Bound target must be revalidated before enabling");
            }
        }
        applyUpdateRow(existing, request, name, enabled, certificate, nextFire,
                request.cronExpression() != null || request.zoneId() != null);
        bumpDataRevision();
        return readTask(taskId).orElseThrow(() -> new IllegalStateException("Task vanished after update"));
    }

    private void applyUpdateRow(
            MonitorTask existing, UpdateRequest request, String name, boolean enabled,
            CronCertificate certificate, Long nextFire, boolean planChanged) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE monitor_tasks SET safe_name = ?, enabled = ?, ")
                .append("blocked_reason = CASE WHEN ? = 1 THEN NULL ELSE blocked_reason END, ")
                .append("next_fire_at = ?, updated_at = ?, row_version = row_version + 1, ")
                .append("schedule_revision = schedule_revision + ?")
                .append(certificate != null ? ", cron_expression = ?, zone_id = ?, tzdb_certificate = ?" : "")
                .append(" WHERE task_id = ? AND row_version = ?");
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, name);
            statement.setInt(index++, enabled ? 1 : 0);
            statement.setInt(index++, request.enabled() != null && request.enabled() ? 1 : 0);
            if (nextFire != null) {
                statement.setLong(index++, nextFire);
            } else {
                statement.setNull(index++, java.sql.Types.BIGINT);
            }
            statement.setLong(index++, clock.instant().toEpochMilli());
            statement.setLong(index++, planChanged ? 1 : 0);
            if (certificate != null) {
                statement.setString(index++, certificate.cronExpression());
                statement.setString(index++, certificate.zoneId());
                statement.setString(index++, certificateStorage(certificate));
            }
            statement.setString(index++, existing.taskId());
            statement.setLong(index++, existing.rowVersion());
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new TaskManagerException(ErrorCode.REVISION_CONFLICT, "Task was modified concurrently");
            }
        }
    }

    private void insertTask(CreateRequest request, String taskId, CronCertificate certificate,
                            long firstFireMs, long nowMs) throws SQLException {
        String sql = "INSERT INTO monitor_tasks (task_id, safe_name, route_model_cipher, safe_model_display, "
                + "cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot, enabled, blocked_reason, "
                + "schedule_revision, cron_expression, zone_id, tzdb_certificate, next_fire_at, created_at, updated_at, row_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, 1, ?, ?, ?, ?, ?, ?, 0)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setString(2, request.safeName().trim());
            statement.setString(3, crypto.encrypt(request.routeModel()));
            statement.setString(4, request.safeModelDisplay());
            statement.setString(5, request.instanceKey());
            statement.setString(6, crypto.encrypt(request.authId()));
            statement.setString(7, crypto.encrypt(request.authIndex()));
            statement.setString(8, request.providerSnapshot() == null || request.providerSnapshot().isBlank()
                    ? "unknown" : request.providerSnapshot());
            statement.setString(9, certificate.cronExpression());
            statement.setString(10, certificate.zoneId());
            statement.setString(11, certificateStorage(certificate));
            statement.setLong(12, firstFireMs);
            statement.setLong(13, nowMs);
            statement.setLong(14, nowMs);
            statement.executeUpdate();
        }
    }

    /**
     * Stable certificate storage text binding the cron/zone/tzdb/algorithm identity, the
     * proof-domain base point and the generation time. The current v4 payload has five
     * colon-separated fields; earlier four-field rows are deliberately left in place as
     * legacy certificates and cannot be accepted as v4 proofs on load.
     */
    private static String certificateStorage(CronCertificate certificate) {
        return certificate.tzdbVersion() + ":" + certificate.algorithmVersion()
                + ":" + certificate.minimumIntervalMs()
                + ":" + certificate.basePoint().toEpochMilli()
                + ":" + certificate.generatedAt().toEpochMilli();
    }

    private Optional<MonitorTask> readTask(String taskId) throws SQLException {
        String sql = "SELECT task_id, safe_name, route_model_cipher, safe_model_display, cpa_instance_key, "
                + "auth_id_cipher, auth_index_cipher, provider_snapshot, enabled, blocked_reason, schedule_revision, "
                + "row_version, cron_expression, zone_id, tzdb_certificate, next_fire_at, created_at, updated_at "
                + "FROM monitor_tasks WHERE task_id = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapTask(resultSet));
            }
        }
    }

    private MonitorTask mapTask(ResultSet resultSet) throws SQLException {
        return new MonitorTask(
                resultSet.getString("task_id"),
                resultSet.getString("safe_name"),
                resultSet.getString("route_model_cipher"),
                resultSet.getString("safe_model_display"),
                resultSet.getString("cpa_instance_key"),
                resultSet.getString("auth_id_cipher"),
                resultSet.getString("auth_index_cipher"),
                resultSet.getString("provider_snapshot"),
                resultSet.getInt("enabled") == 1,
                resultSet.getString("blocked_reason"),
                resultSet.getLong("schedule_revision"),
                resultSet.getLong("row_version"),
                resultSet.getString("cron_expression"),
                resultSet.getString("zone_id"),
                resultSet.getString("tzdb_certificate"),
                resultSet.getObject("next_fire_at") == null ? null : resultSet.getLong("next_fire_at"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    /** Increments the shared monitor data revision (D5.1 / D6.3) inside the caller's write. */
    private void bumpDataRevision() throws SQLException {
        String sql = "INSERT INTO monitor_data_revision (id, revision) VALUES (1, 1) "
                + "ON CONFLICT (id) DO UPDATE SET revision = revision + 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
