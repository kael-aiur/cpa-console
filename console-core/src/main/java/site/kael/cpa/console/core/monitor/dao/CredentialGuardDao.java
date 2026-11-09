package site.kael.cpa.console.core.monitor.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

/**
 * T05 credential guard DAO (D5.1 / D5.3): the durable per-credential mutex keyed by the
 * HMAC of instance + real logical auth id (never model, provider or physical identity), plus
 * the persistent 120 s post-UNKNOWN cooldown. Claim and release are CAS updates; the caller
 * releases only the guard it holds.
 */
public final class CredentialGuardDao {

    /** Guard row as persisted. credential_key is the HMAC and is never decoded. */
    public record GuardRow(String credentialKey, String activeRunId, Long cooldownUntil, long updatedAt) {
    }

    /** Outcome of an acquire attempt. */
    public enum AcquireResult { ACQUIRED, BUSY, COOLDOWN }

    private final DataSource dataSource;

    public CredentialGuardDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Conditional CAS acquire: active_run_id must be NULL and cooldown must have passed.
     * Resource prechecks never replace this atomic check (D5.3 step 5).
     */
    public AcquireResult acquire(String credentialKey, String runId, long nowMs) throws SQLException {
        String sql = "UPDATE monitor_credential_guards SET active_run_id = ?, updated_at = ? "
                + "WHERE credential_key = ? AND active_run_id IS NULL "
                + "AND (cooldown_until IS NULL OR cooldown_until <= ?)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setLong(2, nowMs);
            statement.setString(3, credentialKey);
            statement.setLong(4, nowMs);
            int updated = statement.executeUpdate();
            if (updated == 1) {
                return AcquireResult.ACQUIRED;
            }
            Optional<GuardRow> existing = find(credentialKey);
            if (existing.isPresent()) {
                Long cooldownUntil = existing.get().cooldownUntil();
                if (cooldownUntil != null && cooldownUntil > nowMs && existing.get().activeRunId() == null) {
                    return AcquireResult.COOLDOWN;
                }
            }
            return AcquireResult.BUSY;
        }
    }

    /** Ensures a guard row exists (idempotent) so cooldown can be written before any acquire. */
    public void ensure(String credentialKey, long nowMs) throws SQLException {
        String sql = "INSERT INTO monitor_credential_guards (credential_key, active_run_id, cooldown_until, updated_at) "
                + "VALUES (?, NULL, NULL, ?) ON CONFLICT (credential_key) DO NOTHING";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, credentialKey);
            statement.setLong(2, nowMs);
            statement.executeUpdate();
        }
    }

    /** Loads the guard row if present. */
    public Optional<GuardRow> find(String credentialKey) throws SQLException {
        String sql = "SELECT credential_key, active_run_id, cooldown_until, updated_at "
                + "FROM monitor_credential_guards WHERE credential_key = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, credentialKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new GuardRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getObject(3) == null ? null : resultSet.getLong(3),
                        resultSet.getLong(4)));
            }
        }
    }

    /**
     * Releases the guard only when this exact run holds it; a run that lost the CAS or a
     * foreign release is refused. cooldownUntil optionally persists a new cooldown window.
     */
    public boolean release(String credentialKey, String runId, Long cooldownUntil, long nowMs) throws SQLException {
        String sql = "UPDATE monitor_credential_guards SET active_run_id = NULL, "
                + "cooldown_until = ?, updated_at = ? "
                + "WHERE credential_key = ? AND active_run_id = ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (cooldownUntil != null) {
                statement.setLong(1, cooldownUntil);
            } else {
                statement.setNull(1, java.sql.Types.BIGINT);
            }
            statement.setLong(2, nowMs);
            statement.setString(3, credentialKey);
            statement.setString(4, runId);
            return statement.executeUpdate() == 1;
        }
    }

    /** True when this run currently holds the guard (used before release attempts). */
    public boolean heldBy(String credentialKey, String runId) throws SQLException {
        Optional<GuardRow> row = find(credentialKey);
        return row.isPresent() && runId.equals(row.get().activeRunId());
    }

    /** Clears an expired cooldown without touching the active run (recovery helper for T07). */
    public boolean clearExpiredCooldown(String credentialKey, long nowMs) throws SQLException {
        String sql = "UPDATE monitor_credential_guards SET cooldown_until = NULL, updated_at = ? "
                + "WHERE credential_key = ? AND cooldown_until IS NOT NULL AND cooldown_until <= ?";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMs);
            statement.setString(2, credentialKey);
            statement.setLong(3, nowMs);
            return statement.executeUpdate() == 1;
        }
    }
}
