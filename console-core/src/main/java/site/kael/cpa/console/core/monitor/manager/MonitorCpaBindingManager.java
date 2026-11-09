package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.cpa.client.CpaEndpointNormalizer;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Durable singleton binding of the console to one CPA logical entry (D5.1a / T02).
 * <p>
 * On first initialization a random {@code instance_key} is generated and persisted together
 * with the encrypted normalized endpoint and an HMAC binding digest of that endpoint. The key
 * survives restarts and management-key rotation and is never re-derived. Every later lookup
 * re-normalizes the current configured entry and compares it against the binding: any other
 * entry is reported as {@code INSTANCE_BINDING_CHANGED} and never auto-overwrites the stored
 * binding. Only one binding row may exist.
 */
public final class MonitorCpaBindingManager {

    /** Reason recorded when the current configured entry differs from the stored binding. */
    public static final String REASON_INSTANCE_BINDING_CHANGED = "INSTANCE_BINDING_CHANGED";

    private static final int KEY_BYTES = 16;

    private final DataSource dataSource;
    private final ApiKeyCrypto crypto;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public MonitorCpaBindingManager(DataSource dataSource, ApiKeyCrypto crypto, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Loads the binding or initializes it exactly once from the given configured base URL.
     * Initialization is atomic (unique singleton index): two concurrent callers produce at
     * most one stored binding, the loser re-reads the winner's row.
     */
    public Binding initialize(String configuredBaseUrl) throws SQLException {
        CpaEndpointNormalizer.Result normalized = CpaEndpointNormalizer.normalize(configuredBaseUrl);
        if (!normalized.accepted()) {
            throw new IllegalArgumentException(normalized.rejectionReason());
        }
        Optional<Binding> existing = readBinding();
        if (existing.isPresent()) {
            return requireMatchingBinding(existing.get(), normalized.endpoint());
        }
        String instanceKey = randomInstanceKey();
        insertBinding(instanceKey, normalized.endpoint());
        return new Binding(instanceKey, normalized.endpoint());
    }

    /**
     * Re-reads the stored binding and verifies the current configured entry still matches
     * the stored normalized endpoint. A changed entry is reported as a binding change; the
     * stored binding is never updated implicitly.
     */
    public Binding verifyCurrentEntry(String configuredBaseUrl) throws SQLException {
        CpaEndpointNormalizer.Result normalized = CpaEndpointNormalizer.normalize(configuredBaseUrl);
        if (!normalized.accepted()) {
            throw new IllegalArgumentException(normalized.rejectionReason());
        }
        return requireMatchingBinding(readBinding().orElseThrow(
                () -> new IllegalStateException("No CPA binding has been initialized")), normalized.endpoint());
    }

    /** Stored binding row: random durable key plus the normalized endpoint recorded at bind time. */
    public record Binding(String instanceKey, String normalizedEndpoint) {
    }

    private Binding requireMatchingBinding(Binding binding, String normalizedCurrent) {
        if (!binding.normalizedEndpoint().equals(normalizedCurrent)) {
            throw new IllegalStateException(REASON_INSTANCE_BINDING_CHANGED);
        }
        return binding;
    }

    private void insertBinding(String instanceKey, String normalizedEndpoint) throws SQLException {
        String sql = "INSERT INTO monitor_cpa_binding "
                + "(instance_key, normalized_endpoint_cipher, endpoint_binding_digest, created_at) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, instanceKey);
            statement.setString(2, crypto.encrypt(normalizedEndpoint));
            statement.setString(3, crypto.hash(normalizedEndpoint));
            statement.setLong(4, clock.instant().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private Optional<Binding> readBinding() throws SQLException {
        String sql = "SELECT instance_key, normalized_endpoint_cipher FROM monitor_cpa_binding LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new Binding(resultSet.getString(1), crypto.decrypt(resultSet.getString(2))));
        }
    }

    private String randomInstanceKey() {
        byte[] bytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
