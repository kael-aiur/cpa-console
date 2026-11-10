package site.kael.cpa.console.core.monitor.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.database.DatabaseMigrationRunner;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver;
import site.kael.cpa.console.core.monitor.manager.TaskManager;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolation harness for the T04/T05 self-check: a fresh SQLite file per test, the real
 * migration runner, a fixed clock, a fake in-memory CPA feed and a real HmacSHA256 crypto.
 * No network, no production database, no real credential material.
 */
public final class MonitorIsolationTestSupport {

    public static final Instant BASE_TIME = Instant.parse("2026-11-07T00:00:00Z");

    /** Fixed clock that can be advanced deterministically (no sleeps). */
    public static final class MutableClock extends Clock {
        private volatile Instant instant;

        public MutableClock(Instant start) {
            this.instant = start;
        }

        public void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        public void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Fake candidate feed standing in for the live CPA view; fully offline, zero HTTP. */
    public static final class FakeResolver {
        private List<CpaIdentityResolver.CredentialEntryDetail> entries;
        private final List<String> modelList = new java.util.ArrayList<>();
        public int boundTargetCalls;

        public FakeResolver(List<CpaIdentityResolver.CredentialEntryDetail> entries, List<String> models) {
            this.entries = entries;
            this.modelList.addAll(models);
        }

        public void setEntries(List<CpaIdentityResolver.CredentialEntryDetail> entries) {
            this.entries = entries;
        }

        public void setModels(List<String> models) {
            this.modelList.clear();
            this.modelList.addAll(models);
        }

        /** Mirrors CpaIdentityResolver.verifyBoundTarget with live id+index+model matching. */
        public CpaIdentityResolver.BoundTarget verifyBoundTarget(
                String instanceKey, String authId, String authIndex, String routeModel) {
            boundTargetCalls++;
            if (authId == null || authId.isBlank() || authIndex == null || authIndex.isBlank()) {
                throw new CpaIdentityResolver.IdentityResolutionException("IDENTITY_UNSAFE", "blank identity");
            }
            if (routeModel == null || routeModel.isBlank()) {
                throw new CpaIdentityResolver.IdentityResolutionException("MODEL_INVALID", "blank model");
            }
            boolean idOnly = false;
            boolean indexOnly = false;
            for (CpaIdentityResolver.CredentialEntryDetail entry : entries) {
                boolean idMatch = authId.equals(entry.authId());
                boolean indexMatch = authIndex.equals(entry.authIndex());
                if (idMatch && indexMatch) {
                    if (!modelList.contains(routeModel)) {
                        throw new CpaIdentityResolver.IdentityResolutionException("MODEL_INVALID", "model not registered");
                    }
                    return new CpaIdentityResolver.BoundTarget(instanceKey, authId, authIndex, routeModel, "fake-provider");
                }
                if (idMatch) {
                    idOnly = true;
                }
                if (indexMatch) {
                    indexOnly = true;
                }
            }
            if (idOnly != indexOnly) {
                throw new CpaIdentityResolver.IdentityResolutionException("IDENTITY_UNSAFE", "id/index mismatch");
            }
            throw new CpaIdentityResolver.IdentityResolutionException("IDENTITY_MISSING", "credential not present");
        }
    }

    /** Crypto with a fixed local AES key; no real secret material. */
    public static final class OfflineCrypto extends ApiKeyCrypto {
        public OfflineCrypto() {
            super(Base64.getEncoder().encodeToString(new byte[16]));
        }
    }

    private MonitorIsolationTestSupport() {
    }

    public static DriverManagerDataSource rawDataSource() throws Exception {
        return new DriverManagerDataSource("jdbc:sqlite:" + Files.createTempFile("monitor-t45-", ".db"));
    }

    /**
     * Transaction-aware wrapper so DAOs can keep using {@code DataSourceUtils.getConnection}
     * and still receive the transaction-bound connection instead of a fresh closed-on-return
     * SQLite connection (DriverManagerDataSource opens a new physical connection per call).
     */
    public static DataSource freshDataSource() throws Exception {
        return new TransactionAwareDataSourceProxy(rawDataSource());
    }

    /** The raw target behind the proxy, for direct JdbcTemplate reads in assertions. */
    public static javax.sql.DataSource rawOf(DataSource proxy) {
        return ((TransactionAwareDataSourceProxy) proxy).getTargetDataSource();
    }

    public static void migrate(DataSource dataSource) {
        new DatabaseMigrationRunner(dataSource).migrate();
    }

    public static JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(rawOf(dataSource));
    }

    public static DataSourceTransactionManager txManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    public static ApiKeyCrypto crypto() {
        return new OfflineCrypto();
    }

    public static CronPolicyValidator cronValidator(MutableClock clock) {
        return new CronPolicyValidator(clock);
    }

    /** Standard test plan: every minute, UTC. */
    public static final String CRON = "0 * * * * *";
    public static final String ZONE = "UTC";
}
