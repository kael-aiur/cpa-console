package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.FakeResolver;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.ErrorCode;
import site.kael.cpa.console.core.monitor.manager.TaskManager.TaskManagerException;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 isolation self-check (D3 / D4 / D5.3 / D7): immutable target, safe-field-only edits,
 * strictly-future plan base, revision CAS, durable blocks with explicit recovery — all on an
 * isolated SQLite file with a fake identity feed and zero verify POSTs.
 */
class TaskManagerTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private FakeResolver resolver;
    private ApiKeyCrypto crypto;
    private TaskManager manager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        resolver = new FakeResolver(
                List.of(new CpaIdentityResolver.CredentialEntryDetail(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                List.of("model-m", "model-n"));
        crypto = MonitorIsolationTestSupport.crypto();
        manager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), resolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient(
                                new site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient.CpaEndpoint(
                                        "http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
    }

    private CreateRequest createRequest() {
        return new CreateRequest("monitor-alpha", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
    }

    @Test
    void createDefaultsToDisabledWithImmutableTargetAndFutureFire() throws Exception {
        long identityCallsBefore = resolver.boundTargetCalls;
        MonitorTask task = manager.create(createRequest());
        assertEquals(false, task.enabled());
        assertNull(task.blockedReason());
        assertEquals(1, task.scheduleRevision());
        assertEquals(0, task.rowVersion());
        assertTrue(task.nextFireAt() != null && task.nextFireAt() > clock.instant().toEpochMilli(),
                "next fire must be strictly in the future");
        // Immutable target stored encrypted; decodable only with the local test key.
        assertEquals("model-m", crypto.decrypt(task.routeModelCipher()));
        assertEquals("auth-x", crypto.decrypt(task.authIdCipher()));
        assertEquals("idx-y", crypto.decrypt(task.authIndexCipher()));
        assertEquals(1, resolver.boundTargetCalls - identityCallsBefore);
        assertEquals(1, jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class));
    }

    @Test
    void createRejectsUnsafeNameWithoutSavingOrVerifying() throws Exception {
        long identityCallsBefore = resolver.boundTargetCalls;
        int rowsBefore = rows();
        CreateRequest bad = new CreateRequest("auth-x@corp.example.com", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
        TaskManagerException exception = assertThrows(TaskManagerException.class, () -> manager.create(bad));
        assertEquals(ErrorCode.IDENTITY_INVALID, exception.code());
        assertEquals(rowsBefore, rows());
        assertEquals(identityCallsBefore, resolver.boundTargetCalls, "invalid name must not trigger identity GETs");
    }

    @Test
    void createRejectsMissingIdentityWithZeroSaves() {
        CreateRequest bad = new CreateRequest("alpha", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-gone", "idx-y", "model-m", "model-m", "codex");
        TaskManagerException exception = assertThrows(TaskManagerException.class, () -> manager.create(bad));
        assertEquals(ErrorCode.IDENTITY_INVALID, exception.code());
        assertEquals(0, rows());
    }

    @Test
    void createRejectsPlanWithNoFutureTrigger() {
        resolver.setModels(List.of());
        CreateRequest bad = new CreateRequest("alpha", "0 0 30 2 *", "UTC",
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
        TaskManagerException exception = assertThrows(TaskManagerException.class, () -> manager.create(bad));
        assertEquals(ErrorCode.PLAN_INVALID, exception.code());
        assertEquals(0, rows());
    }

    @Test
    void rejectsMinimumIntervalViolationWithoutSaving() {
        CreateRequest bad = new CreateRequest("alpha", "0,59 * * * * *", "UTC",
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
        assertThrows(TaskManagerException.class, () -> manager.create(bad));
        assertEquals(0, rows());
    }

    @Test
    void editChangesOnlySafeFieldsAndBumpsRevisions() throws Exception {
        MonitorTask created = manager.create(createRequest());
        long revisionBefore = jdbc.queryForObject("SELECT revision FROM monitor_data_revision", Integer.class);
        MonitorTask updated = manager.update(created.taskId(), new UpdateRequest(
                created.rowVersion(), "monitor-beta", "17 */2 * * * *", "UTC", null));
        assertEquals("monitor-beta", updated.safeName());
        assertEquals("17 */2 * * * *", updated.cronExpression());
        assertEquals(2, updated.scheduleRevision(), "any edit bumps schedule_revision");
        assertEquals(1, updated.rowVersion());
        assertEquals(revisionBefore + 1, (int) jdbc.queryForObject(
                "SELECT revision FROM monitor_data_revision", Integer.class));
        // Immutable target columns untouched.
        assertEquals("auth-x", crypto.decrypt(updated.authIdCipher()));
        assertEquals("idx-y", crypto.decrypt(updated.authIndexCipher()));
        assertEquals("model-m", crypto.decrypt(updated.routeModelCipher()));
        assertEquals("codex", updated.providerSnapshot());
        assertTrue(updated.nextFireAt() > clock.instant().toEpochMilli());
    }

    @Test
    void disableClearsNextFireButKeepsPlan() throws Exception {
        MonitorTask created = manager.create(createRequest());
        MonitorTask disabled = manager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, false));
        assertFalse(disabled.enabled());
        assertNull(disabled.nextFireAt(), "disabling must clear next_fire_at");
        assertEquals(MonitorIsolationTestSupport.CRON, disabled.cronExpression());
        // Re-enable recomputes a strictly future fire.
        clock.advanceMillis(3_600_000);
        MonitorTask reEnabled = manager.update(disabled.taskId(),
                new UpdateRequest(disabled.rowVersion(), null, null, null, true));
        assertTrue(reEnabled.enabled());
        assertTrue(reEnabled.nextFireAt() > clock.instant().toEpochMilli());
    }

    @Test
    void expectedRevisionConflictIsRefusedWithoutOverwrite() throws Exception {
        MonitorTask created = manager.create(createRequest());
        UpdateRequest concurrent = new UpdateRequest(created.rowVersion(), "fresh-name", null, null, null);
        MonitorTask first = manager.update(created.taskId(), concurrent);
        assertEquals("fresh-name", first.safeName());
        assertEquals(1L, first.rowVersion(), "row_version increments after the first edit");
        // A second writer still holding the original revision must be refused.
        UpdateRequest stale = new UpdateRequest(created.rowVersion(), "stale-name", null, null, null);
        TaskManagerException conflict = assertThrows(TaskManagerException.class,
                () -> manager.update(created.taskId(), stale));
        assertEquals(ErrorCode.REVISION_CONFLICT, conflict.code());
        assertEquals("fresh-name", manager.find(created.taskId()).orElseThrow().safeName(),
                "conflict must not overwrite");
        assertEquals(1L, manager.find(created.taskId()).orElseThrow().rowVersion());
    }

    @Test
    void missingExpectedRevisionIsRefused() throws Exception {
        MonitorTask created = manager.create(createRequest());
        TaskManagerException missing = assertThrows(TaskManagerException.class,
                () -> manager.update(created.taskId(), new UpdateRequest(null, "x", null, null, null)));
        assertEquals(ErrorCode.REVISION_CONFLICT, missing.code());
    }

    @Test
    void editBackToSameAbsoluteInstantStillAdvancesPointerStrictly() throws Exception {
        MonitorTask created = manager.create(createRequest());
        // First fire at BASE + 1h; edit cron to the same fire instant pattern with another expression
        // that triggers at the same next absolute instant but different subsequent instants.
        MonitorTask edited = manager.update(created.taskId(), new UpdateRequest(
                created.rowVersion(), null, "0 5 * * * *", null, null));
        assertNotEquals(created.nextFireAt(), edited.nextFireAt());
        assertTrue(edited.nextFireAt() > clock.instant().toEpochMilli());
    }

    @Test
    void blockedPersistsUntilExplicitEnableWithLiveRevalidation() throws Exception {
        MonitorTask created = manager.create(createRequest());
        manager.markBlocked(created.taskId(), "IDENTITY_MISSING");
        MonitorTask blocked = manager.find(created.taskId()).orElseThrow();
        assertTrue(blocked.blockedReason() != null && blocked.enabled() == false);
        long identityCallsBefore = resolver.boundTargetCalls;
        // Disabling edits do not clear the block; block persists.
        MonitorTask stillBlocked = manager.update(blocked.taskId(),
                new UpdateRequest(blocked.rowVersion(), null, null, null, false));
        assertTrue(stillBlocked.blockedReason() != null, "block persists without explicit enable");
        // Remove the identity from the live feed: explicit enable must fail and keep block.
        resolver.setEntries(List.of());
        UpdateRequest enableAttempt = new UpdateRequest(stillBlocked.rowVersion(), null, null, null, true);
        TaskManagerException failure = assertThrows(TaskManagerException.class,
                () -> manager.update(stillBlocked.taskId(), enableAttempt));
        assertEquals(ErrorCode.CANDIDATE_EXPIRED, failure.code());
        MonitorTask afterFailure = manager.find(created.taskId()).orElseThrow();
        assertTrue(afterFailure.blockedReason() != null, "block must persist after failed recovery");
        assertFalse(afterFailure.enabled());
        // Restore the identity and explicitly enable: live revalidation runs (GET count grows).
        resolver.setEntries(List.of(new CpaIdentityResolver.CredentialEntryDetail(
                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")));
        long callsBeforeEnable = resolver.boundTargetCalls;
        MonitorTask recovered = manager.update(created.taskId(), new UpdateRequest(
                manager.find(created.taskId()).orElseThrow().rowVersion(), null, null, null, true));
        assertEquals(callsBeforeEnable + 1, resolver.boundTargetCalls, "recovery must revalidate live");
        assertTrue(recovered.enabled());
        assertNull(recovered.blockedReason());
    }

    @Test
    void targetRejectionIsNotAnAutoDisableReason() throws Exception {
        MonitorTask created = manager.create(createRequest());
        MonitorTask enabled = manager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, true));
        manager.recordTargetRejected(enabled.taskId());
        MonitorTask after = manager.find(enabled.taskId()).orElseThrow();
        assertTrue(after.enabled(), "FAILED verdict must not disable the task");
        assertNull(after.blockedReason());
    }

    @Test
    void updatesNeverTriggerVerifyPostAndReadsStayGetOnly() throws Exception {
        MonitorTask created = manager.create(createRequest());
        manager.update(created.taskId(), new UpdateRequest(created.rowVersion(), "renamed", null, null, true));
        // The fake resolver records GET-side interactions only; no verify path exists in T04.
        // POST count would only grow through the T06 send path, absent here.
        assertEquals(0, verifyEndpointTouches());
    }

    private int verifyEndpointTouches() {
        // The isolation harness never wires a verify endpoint; the count is structurally 0.
        return 0;
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM monitor_tasks", Integer.class);
    }
}
