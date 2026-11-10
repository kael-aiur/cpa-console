package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.FakeResolver;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.CronCertificate;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real TaskManager persistence path for R2-A-R2: create and plan-edit must store the v4
 * base point as durable data, while a legacy four-field row remains a non-v4 certificate
 * after a lifecycle-equivalent parse/validation round trip. Isolated SQLite, fixed Clock,
 * fake read-only identity resolver and zero verify POSTs.
 */
class TaskManagerCertificatePersistenceTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private FakeResolver resolver;
    private CronPolicyValidator validator;
    private TaskManager manager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(Instant.parse("2026-11-07T09:17:00Z"));
        resolver = new FakeResolver(
                List.of(new CpaIdentityResolver.CredentialEntryDetail(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                List.of("model-m"));
        validator = MonitorIsolationTestSupport.cronValidator(clock);
        manager = new TaskManager(dataSource, MonitorIsolationTestSupport.crypto(), clock,
                validator, resolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient(
                                new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.CpaEndpoint(
                                        "http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
    }

    private CreateRequest createRequest(String cron) {
        return new CreateRequest("cert-persistence", cron, "UTC",
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
    }

    @Test
    void createPersistsEveryV4ProofFieldAndTheyRoundTripThroughTheValidator() throws Exception {
        MonitorTask created = manager.create(createRequest("17 */2 * * * *"));
        String stored = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        String[] fields = stored.split(":", 5);
        assertEquals(5, fields.length, "v4 storage must append the actual base-point field");
        assertEquals(4, Integer.parseInt(fields[1]), "the persisted algorithm identity must be v4");
        assertEquals(String.valueOf(clock.instant().toEpochMilli()), fields[3],
                "the durable base-point field must equal the certification base point");

        CronCertificate reparsed = reparseForRestartValidation(created, stored);
        assertEquals(created.cronExpression(), reparsed.cronExpression());
        assertEquals(created.zoneId(), reparsed.zoneId());
        assertEquals(4, reparsed.algorithmVersion());
        assertEquals(clock.instant(), reparsed.basePoint());
        assertTrue(validator.isCertificateValid(reparsed, created.cronExpression(), created.zoneId()),
                "restart validation must recover the persisted v4 proof domain");
        assertTrue(reparsed.validFor(clock.instant()));
        assertTrue(reparsed.validFor(clock.instant().plusSeconds(60)));
        assertFalse(reparsed.validFor(clock.instant().minusSeconds(1)),
                "use before the persisted proof base point must fail");
    }

    @Test
    void editAndEnableConsumeThePersistedProofDomainOnRestart() throws Exception {
        MonitorTask created = manager.create(createRequest("0 * * * * *"));
        clock.set(MonitorIsolationTestSupport.BASE_TIME.plusSeconds(217));
        MonitorTask edited = manager.update(created.taskId(), new UpdateRequest(
                created.rowVersion(), null, "17 * * * * *", null, true));
        String stored = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        String[] fields = stored.split(":", 5);
        assertEquals(5, fields.length);
        assertEquals(String.valueOf(clock.instant().toEpochMilli()), fields[3],
                "plan edit must overwrite the old base point with the edit-time proof base");
        assertEquals("17 * * * * *", edited.cronExpression());

        CronCertificate reparsed = reparseForRestartValidation(edited, stored);
        assertTrue(validator.isCertificateValid(reparsed, edited.cronExpression(), edited.zoneId()),
                "the enabled task's persisted v4 proof domain must survive restart");
    }

    @Test
    void legacyFourFieldRowRemainsExplicitlyNonV4OnRestartValidation() throws Exception {
        MonitorTask created = manager.create(createRequest("0 * * * * *"));
        String legacyStorage = validator.tzdbVersion() + ":1:60000:"
                + clock.instant().toEpochMilli();
        jdbc.update("UPDATE monitor_tasks SET tzdb_certificate = ? WHERE task_id = ?",
                legacyStorage, created.taskId());
        MonitorTask legacyTask = manager.find(created.taskId()).orElseThrow();
        String[] legacyFields = legacyTask.tzdbCertificate().split(":", 5);
        assertEquals(4, legacyFields.length, "the seeded row must retain the legacy shape");

        CronCertificate reparsed = reparseForRestartValidation(legacyTask, legacyStorage);
        assertEquals(1, reparsed.algorithmVersion(),
                "the legacy row must preserve its historical algorithm identity");
        assertFalse(validator.isCertificateValid(reparsed, legacyTask.cronExpression(),
                        legacyTask.zoneId()),
                "legacy algorithm 1 must not be accepted as the current v4 proof");
        assertFalse(reparsed.validFor(Instant.ofEpochMilli(-1L)),
                "the legacy epoch floor is a half-open domain start, not a pre-1970 pass");
    }

    /**
     * Mirrors MonitorLifecycleManager's actual restart-consumer decision rather than
     * bypassing it: parse storage with its algorithm field, then call the real validator.
     */
    private static CronCertificate reparseForRestartValidation(MonitorTask task, String stored) {
        String[] fields = stored.split(":", 5);
        if (fields.length != 5) {
            return new CronCertificate(task.cronExpression(), task.zoneId(), fields[0],
                    Integer.parseInt(fields[1]), Long.parseLong(fields[2]),
                    Instant.ofEpochMilli(0L), Instant.ofEpochMilli(Long.parseLong(fields[3])));
        }
        return new CronCertificate(task.cronExpression(), task.zoneId(), fields[0],
                Integer.parseInt(fields[1]), Long.parseLong(fields[2]),
                Instant.ofEpochMilli(Long.parseLong(fields[3])),
                Instant.ofEpochMilli(Long.parseLong(fields[4])));
    }
}
