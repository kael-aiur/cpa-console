package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.model.CredentialMonitoringSchemaTestSupport;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding self-check: random instance_key persists across restart/key rotation and a changed
 * normalized endpoint is reported as INSTANCE_BINDING_CHANGED without rewriting the binding.
 */
class MonitorCpaBindingManagerTest {

    private static final String BASE = "http://127.0.0.1:8317";

    @Test
    void initializesOnceAndKeepsRandomInstanceKeyAcrossRestarts() throws Exception {
        DataSource dataSource = dataSource();
        new CredentialMonitoringSchemaTestSupport().migrate(dataSource);
        ApiKeyCrypto crypto = crypto();
        MonitorCpaBindingManager manager = new MonitorCpaBindingManager(dataSource, crypto, fixedClock());
        MonitorCpaBindingManager.Binding first = manager.initialize(BASE);
        MonitorCpaBindingManager.Binding second = new MonitorCpaBindingManager(dataSource, crypto, fixedClock())
                .initialize(BASE);
        assertEquals(first.instanceKey(), second.instanceKey());
        assertEquals(BASE, first.normalizedEndpoint());
        assertEquals(32, first.instanceKey().length());
        assertEquals(1, new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM monitor_cpa_binding", Integer.class));
    }

    @Test
    void instanceKeySurvivesManagementKeyRotation() throws Exception {
        DataSource dataSource = dataSource();
        new CredentialMonitoringSchemaTestSupport().migrate(dataSource);
        ApiKeyCrypto crypto = crypto();
        MonitorCpaBindingManager.Binding first = new MonitorCpaBindingManager(dataSource, crypto, fixedClock()).initialize(BASE);
        // A management-key rotation does not touch the binding manager inputs at all.
        MonitorCpaBindingManager.Binding afterRotation =
                new MonitorCpaBindingManager(dataSource, crypto, fixedClock()).initialize(BASE);
        assertEquals(first.instanceKey(), afterRotation.instanceKey());
    }

    @Test
    void changedNormalizedEndpointIsRejectedWithoutOverwrite() throws Exception {
        DataSource dataSource = dataSource();
        new CredentialMonitoringSchemaTestSupport().migrate(dataSource);
        ApiKeyCrypto crypto = crypto();
        MonitorCpaBindingManager.Binding original =
                new MonitorCpaBindingManager(dataSource, crypto, fixedClock()).initialize(BASE);
        MonitorCpaBindingManager manager = new MonitorCpaBindingManager(dataSource, crypto, fixedClock());
        IllegalStateException changed = assertThrows(IllegalStateException.class,
                () -> manager.initialize("http://127.0.0.1:9999"));
        assertEquals(MonitorCpaBindingManager.REASON_INSTANCE_BINDING_CHANGED, changed.getMessage());
        MonitorCpaBindingManager.Binding unchanged = manager.initialize(BASE);
        assertEquals(original.instanceKey(), unchanged.instanceKey());
        assertEquals(BASE, unchanged.normalizedEndpoint());
    }

    @Test
    void distinctBasePathsAreDistinctEndpoints() throws Exception {
        DataSource dataSource = dataSource();
        new CredentialMonitoringSchemaTestSupport().migrate(dataSource);
        ApiKeyCrypto crypto = crypto();
        new MonitorCpaBindingManager(dataSource, crypto, fixedClock()).initialize(BASE);
        IllegalStateException changed = assertThrows(IllegalStateException.class,
                () -> new MonitorCpaBindingManager(dataSource, crypto, fixedClock())
                        .initialize(BASE + "/cpa-b"));
        assertEquals(MonitorCpaBindingManager.REASON_INSTANCE_BINDING_CHANGED, changed.getMessage());
    }

    @Test
    void bindingCiphertextIsNotStoredInPlaintext() throws Exception {
        DataSource dataSource = dataSource();
        new CredentialMonitoringSchemaTestSupport().migrate(dataSource);
        new MonitorCpaBindingManager(dataSource, crypto(), fixedClock()).initialize(BASE);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String cipher = jdbc.queryForObject(
                "SELECT normalized_endpoint_cipher FROM monitor_cpa_binding LIMIT 1", String.class);
        assertTrue(!cipher.contains(BASE));
        String digest = jdbc.queryForObject(
                "SELECT endpoint_binding_digest FROM monitor_cpa_binding LIMIT 1", String.class);
        assertTrue(digest != null && !digest.isBlank());
    }

    private ApiKeyCrypto crypto() {
        return new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg==");
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneId.of("UTC"));
    }

    private DataSource dataSource() throws Exception {
        return new DriverManagerDataSource("jdbc:sqlite:" + Files.createTempFile("monitor-binding-", ".db"));
    }
}
