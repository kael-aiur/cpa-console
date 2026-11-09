package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.QueryDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.model.SignedCursor;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * T07 bean wiring for the monitor lifecycle and T09 query stack (D2). The injected Clock
 * is the single time authority for scheduling and recovery; query signing derives its
 * HMAC secret from the configured console encryption key material without exposing it.
 */
@Configuration
public class MonitorBeans {

    @Bean
    public Clock monitorClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MonitorCpaBindingManager monitorCpaBindingManager(DataSource dataSource, ApiKeyCrypto crypto, Clock clock) {
        return new MonitorCpaBindingManager(dataSource, crypto, clock);
    }

    @Bean
    public MonitorConfigLockManager monitorConfigLockManager(
            @Value("${cpa.base-url:${CPA_BASE_URL:http://127.0.0.1:8317}}") String baseUrl,
            @Value("${cpa.console.config-version:${CPA_CONSOLE_CONFIG_VERSION:initial}}") String configVersion,
            @Value("${cpa.management-key:${CPA_MANAGEMENT_KEY:}}") String managementKey) {
        return new MonitorConfigLockManager(
                new CpaCredentialVerificationClient(
                        new CpaCredentialVerificationClient.CpaEndpoint(baseUrl, configVersion), managementKey),
                configVersion);
    }

    @Bean
    public TaskManager monitorTaskManager(
            DataSource dataSource,
            ApiKeyCrypto crypto,
            Clock clock,
            CronPolicyValidator cronPolicyValidator,
            CpaIdentityResolver identityResolver,
            MonitorConfigLockManager configLock) {
        return new TaskManager(dataSource, crypto, clock, cronPolicyValidator,
                identityResolver::verifyBoundTarget, configLock);
    }

    @Bean
    public CronPolicyValidator monitorCronPolicyValidator(Clock clock) {
        return new CronPolicyValidator(clock);
    }

    @Bean
    public RunDao monitorRunDao(DataSource dataSource) {
        return new RunDao(dataSource);
    }

    @Bean
    public CredentialGuardDao monitorCredentialGuardDao(DataSource dataSource) {
        return new CredentialGuardDao(dataSource);
    }

    @Bean
    public RetentionDao monitorRetentionDao(DataSource dataSource) {
        return new RetentionDao(dataSource);
    }

    @Bean
    public ObservationGapDao monitorObservationGapDao(DataSource dataSource) {
        return new ObservationGapDao(dataSource);
    }

    @Bean
    public QueryDao monitorQueryDao(DataSource dataSource) {
        return new QueryDao(dataSource);
    }

    @Bean
    public RunClaimManager monitorRunClaimManager(
            DataSource dataSource,
            ApiKeyCrypto crypto,
            Clock clock,
            TaskManager taskManager,
            RunDao runDao,
            CredentialGuardDao guardDao,
            PlatformTransactionManager transactionManager) {
        return new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao, transactionManager);
    }

    @Bean
    public ExecutionManager monitorExecutionManager(
            RunClaimManager claimManager,
            TaskManager taskManager,
            CpaIdentityResolver identityResolver,
            MonitorConfigLockManager configLock,
            Clock clock) {
        return new ExecutionManager(
                claimManager, taskManager, identityResolver,
                (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                configLock, clock, new ExecutionManager.NoopListener());
    }

    @Bean
    public RetentionManager monitorRetentionManager(
            DataSource dataSource,
            RetentionDao retentionDao,
            RunDao runDao,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new RetentionManager(dataSource, retentionDao, runDao, clock, transactionManager);
    }

    @Bean
    public QueryManager monitorQueryManager(
            DataSource dataSource,
            QueryDao queryDao,
            RetentionDao retentionDao,
            ObservationGapDao gapDao,
            TaskManager taskManager,
            RunDao runDao,
            @Value("${cpa.console.api-key-encryption-key:${CPA_CONSOLE_API_KEY_ENCRYPTION_KEY:}}") String encryptionKey) {
        String signingMaterial = encryptionKey.isBlank()
                ? "monitor-cursor-default-secret" : "monitor-cursor:" + encryptionKey;
        return new QueryManager(dataSource, queryDao, retentionDao, gapDao, taskManager, runDao,
                new SignedCursor(signingMaterial));
    }

    @Bean
    public MonitorLifecycleManager monitorLifecycleManager(
            DataSource dataSource,
            RunClaimManager runClaimManager,
            TaskManager taskManager,
            ExecutionManager executionManager,
            RetentionManager retentionManager,
            MonitorCpaBindingManager bindingManager,
            MonitorConfigLockManager configLock,
            ObservationGapDao gapDao,
            RunDao runDao,
            CredentialGuardDao guardDao,
            Clock clock,
            @Value("${cpa.console.data-dir:${CPA_CONSOLE_DATA_DIR:.data}}") String dataDirectory,
            PlatformTransactionManager transactionManager,
            @Value("${cpa.console.monitor-tick-ms:${CPA_CONSOLE_MONITOR_TICK_MS:1000}}") long tickIntervalMs,
            @Value("${cpa.console.monitor-retention-ms:${CPA_CONSOLE_MONITOR_RETENTION_MS:3600000}}") long retentionIntervalMs,
            @Value("${cpa.console.monitor-shutdown-drain-ms:${CPA_CONSOLE_MONITOR_SHUTDOWN_DRAIN_MS:5000}}") long shutdownDrainMs) {
        return new MonitorLifecycleManager(
                dataSource, runClaimManager, taskManager, executionManager, retentionManager,
                bindingManager, configLock, gapDao, runDao, guardDao, clock,
                Path.of(dataDirectory).toAbsolutePath().normalize(),
                transactionManager, tickIntervalMs, retentionIntervalMs, shutdownDrainMs);
    }
}
