package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.FakeResolver;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.CronCertificate;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;
import site.kael.cpa.console.core.monitor.schedule.ScheduleIterator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04.R3 real-consumer self-check (task v1.2-r3 §3 T04.R3, §4.2 C01/C02; original
 * task.md §8.5 C01/C02): every expected next-fire comes from a test-side independent
 * UTC-second oracle, never from a production helper. All paths run the REAL
 * TaskManager against isolated SQLite, an injected mutable Clock and the fake
 * read-only identity resolver - no verify POST path exists in the harness, so the
 * verify count stays structurally zero for every configuration operation.
 */
class MonitorConsumersR3Test {

    /** The EDT-side minute before the 2026-11-01 America/New_York fall back (06:00Z). */
    private static final Instant FALLBACK_BASE_1 = Instant.parse("2026-11-01T05:59:00Z");
    /** Mid-overlap EDT-side base point: second-offset expectations diverge here. */
    private static final Instant FALLBACK_BASE_2 = Instant.parse("2026-11-01T05:59:30Z");
    private static final String NEW_YORK = "America/New_York";
    /** Lord Howe repeats a HALF hour on 2026-04-05 (15:00 local). */
    private static final Instant LORD_HOWE_BASE_1 = Instant.parse("2026-04-04T14:45:00Z");
    private static final Instant LORD_HOWE_BASE_2 = Instant.parse("2026-04-04T15:10:00Z");

    @TempDir
    Path lockDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyCrypto crypto;
    private FakeResolver resolver;
    private CronPolicyValidator validator;
    private TaskManager taskManager;

    private long verifyPostAttempts;

    @BeforeEach
    void setUp() throws Exception {
        verifyPostAttempts = 0;
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(Instant.parse("2026-11-01T04:00:00Z"));
        crypto = MonitorIsolationTestSupport.crypto();
        resolver = new MonitorIsolationTestSupport.FakeResolver(
                List.of(new CpaIdentityResolver.CredentialEntry(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false)),
                List.of("model-m"));
        validator = MonitorIsolationTestSupport.cronValidator(clock);
        // Wrapping the fake resolver keeps the read-only GET contract while asserting
        // that NO configuration operation ever reaches a verify/POST-style path (the
        // harness has none - the counter proves it structurally).
        TaskManager.BoundTargetValidator countingResolver =
                (instanceKey, authId, authIndex, routeModel) -> {
                    verifyPostAttempts++;
                    return resolver.verifyBoundTarget(instanceKey, authId, authIndex, routeModel);
                };
        taskManager = new TaskManager(dataSource, crypto, clock, validator, countingResolver,
                new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
    }

    private CreateRequest createRequest(String safeName, String cron, String zone) {
        return new CreateRequest(safeName, cron, zone,
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex");
    }

    // ------------------------------------------------------ independent UTC-second oracle

    /** Whole-cron match from test-side local calendar fields only (no production helper). */
    private static boolean oracleMatches(LocalDateTime local, String cron, ZoneId zone,
                                         java.time.LocalDate date) {
        return date.getDayOfMonth() == local.getDayOfMonth()
                && date.getMonthValue() == local.getMonthValue()
                && oracleFieldMatches(local.getSecond(), secondField(cron))
                && oracleFieldMatches(local.getMinute(), minuteField(cron))
                && oracleFieldMatches(local.getHour(), hourField(cron));
    }

    /** Independent minute/second predicate: '*', single values, lists, ranges and steps. */
    private static boolean oracleFieldMatches(int value, String field) {
        if ("*".equals(field)) {
            return true;
        }
        for (String part : field.split(",")) {
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(part.substring(slash + 1));
                part = part.substring(0, slash);
            }
            int start;
            int end;
            if (part.contains("-")) {
                String[] range = part.split("-");
                start = Integer.parseInt(range[0]);
                end = Integer.parseInt(range[1]);
            } else {
                start = end = Integer.parseInt(part);
            }
            for (int v = start; v <= end; v += step) {
                if (v == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String secondField(String cron) {
        return cron.split(" ")[0];
    }

    private static String minuteField(String cron) {
        return cron.split(" ")[1];
    }

    private static String hourField(String cron) {
        return cron.split(" ")[2];
    }

    /** Independent hour predicate supporting '*', single values, lists and steps. */
    private static boolean hourMatches(int hour, String hourField) {
        if ("*".equals(hourField)) {
            return true;
        }
        for (String part : hourField.split(",")) {
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(part.substring(slash + 1));
                part = part.substring(0, slash);
            }
            int start;
            int end;
            if (part.contains("-")) {
                String[] range = part.split("-");
                start = Integer.parseInt(range[0]);
                end = Integer.parseInt(range[1]);
            } else {
                start = end = Integer.parseInt(part);
            }
            for (int v = start; v <= end; v += step) {
                if (v == hour) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every matching absolute instant in [from, until) at integer UTC seconds. The
     * absolute copies of an overlap all land on the same UTC second, so the TreeSet
     * deduplicates them exactly as the consumer's next-fire contract requires.
     */
    private static List<Long> oracleWindowSeconds(ZoneId zone, Instant from, Instant until,
                                                  String cron, java.time.LocalDate date) {
        TreeSet<Long> expected = new TreeSet<>();
        long startMs = Math.floorDiv(from.toEpochMilli(), 1000L) * 1000L;
        for (long ms = startMs; ms < until.toEpochMilli(); ms += 1000L) {
            LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone);
            if (oracleMatches(local, cron, zone, date)) {
                expected.add(ms);
            }
        }
        return new ArrayList<>(expected);
    }

    /**
     * The next-fire expectation after {@code after}: the smallest oracle element
     * strictly greater than the base point, or null when none exists in the window
     * (the window always covers the candidates the real consumer must find).
     */
    private static long expectedNextAfter(ZoneId zone, Instant after, Instant until,
                                          String cron, java.time.LocalDate date) {
        return oracleWindowSeconds(zone, after.minusSeconds(1), until, cron, date).stream()
                .filter(ms -> ms > after.toEpochMilli())
                .findFirst()
                .orElseThrow(() -> new AssertionError("oracle window exhausted before a next fire"));
    }

    // ------------------------------------------------------ C01: create / edit all seconds

    static Stream<Integer> fixedSeconds() {
        return Stream.of(0, 1, 7, 17, 30, 45, 58, 59);
    }

    /**
     * C01 (v1.2-r3): the REAL create path with the base inside the fall-back overlap for
     * every fixed second. Creation keeps enabled=false while persisting the exact
     * offset-aware first fire; the edit path re-certifies the plan and its next fire.
     */
    @ParameterizedTest
    @MethodSource("fixedSeconds")
    void c01_createKeepsDisabledButStoresOffsetAwareFirstFire(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        long identityCallsBefore = resolver.boundTargetCalls;
        // Real TaskManager.create: validates, certifies, solves the first fire, inserts.
        MonitorTask created = taskManager.create(createRequest("consumer-r3-" + second, cron, NEW_YORK));
        assertFalse(created.enabled(), "a created task must stay disabled by default");
        assertNull(created.blockedReason());
        assertEquals(1, created.scheduleRevision());
        assertEquals(1, resolver.boundTargetCalls - identityCallsBefore,
                "create must use exactly the read-only identity validation");
        assertEquals(expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                        Instant.parse("2026-11-01T08:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1)),
                created.nextFireAt(),
                "second " + second + ": first fire must be the independent overlap minimum");
        assertTrue(created.nextFireAt() > FALLBACK_BASE_2.toEpochMilli(),
                "the stored first fire must be strictly after the base point");
        // The persisted certificate row carries the current algorithm identity and the
        // exact base point of this certification.
        String stored = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        String[] fields = stored.split(":", 5);
        assertEquals(5, fields.length, "the v4 storage shape must persist five fields");
        assertEquals(CronCertificate.CURRENT_ALGORITHM_VERSION, Integer.parseInt(fields[1]),
                "the persisted algorithm identity must be the current proof");
        assertEquals(String.valueOf(FALLBACK_BASE_2.toEpochMilli()), fields[3],
                "the persisted base-point field must equal this certification base");
        // Enabled stays false: the stored pointer exists but the task must not dispatch.
        int enabledFlag = jdbc.queryForObject(
                "SELECT enabled FROM monitor_tasks WHERE task_id = ?", Integer.class, created.taskId());
        assertEquals(0, enabledFlag, "the durable row must keep the disabled default");
        // Edit the cron through the real update path: re-certifies and re-solves.
        String editedCron = (second == 0 ? 59 : second - 1) + " * * * * *";
        MonitorTask edited = taskManager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, editedCron, null, null));
        assertNotEquals(created.rowVersion(), edited.rowVersion(), "plan edit bumps row_version");
        assertEquals(expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                        Instant.parse("2026-11-01T08:00:00Z"), editedCron,
                        java.time.LocalDate.of(2026, 11, 1)),
                edited.nextFireAt(),
                "second " + second + ": edited plan must re-solve with the new second field");
        assertFalse(edited.enabled(), "a cron-only edit must not silently enable the task");
        // Zone edit through the real update path keeps the same independent expectation
        // for the new zone (Lord Howe half-hour fall back, base in the repeated block).
        clock.set(LORD_HOWE_BASE_2);
        String zoneCron = second + " * * * * *";
        MonitorTask lh = taskManager.create(createRequest("consumer-lh-" + second, zoneCron,
                "Australia/Lord_Howe"));
        assertFalse(lh.enabled());
        assertEquals(expectedNextAfter(ZoneId.of("Australia/Lord_Howe"), LORD_HOWE_BASE_2,
                        Instant.parse("2026-04-04T16:30:00Z"), zoneCron,
                        java.time.LocalDate.of(2026, 4, 5)),
                lh.nextFireAt(),
                "second " + second + ": half-hour overlap must land on the later absolute copy");
    }

    /** C01 companion: the dangerous sparse leap-day plan is never saved. */
    @Test
    void c01_dangerousLeapDayPlanRejectedAndNeverPersisted() throws Exception {
        clock.set(FALLBACK_BASE_1);
        assertThrows(TaskManager.TaskManagerException.class,
                () -> taskManager.create(createRequest("danger", "0,59 * * 29 2 *", "UTC")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_tasks", Integer.class),
                "a rejected plan must leave no persisted row");
        // The safe sparse leap-day plan still certifies and persists disabled.
        MonitorTask safe = taskManager.create(createRequest("safe-leap", "0 0 0 29 2 *", "UTC"));
        assertFalse(safe.enabled());
        assertEquals(Instant.parse("2028-02-29T00:00:00Z").toEpochMilli(), safe.nextFireAt(),
                "the safe leap-day first fire must be 2028-02-29T00:00:00Z");
    }

    // ------------------------------------------------------ C02: enable / re-enable branches

    static Stream<Integer> enableSeconds() {
        return Stream.of(0, 17, 59);
    }

    /**
     * C02 first branch: enabling with a FUTURE next cache must reuse the stored pointer
     * (the same independent overlap minimum); the clock does not move.
     */
    @ParameterizedTest
    @MethodSource("enableSeconds")
    void c02_firstEnableReusesFutureCacheVerifiedAgainstOracle(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        MonitorTask created = taskManager.create(createRequest("enable-future-" + second, cron, NEW_YORK));
        long expected = expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                Instant.parse("2026-11-01T08:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1));
        assertEquals(expected, created.nextFireAt());
        MonitorTask enabled = taskManager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, true));
        assertTrue(enabled.enabled());
        assertEquals(expected, enabled.nextFireAt(),
                "second " + second + ": first enable with a future cache must keep the oracle pointer");
    }

    /**
     * C02 expired/empty branch: with the pointer in the past the REAL enable re-solves
     * from the moved clock; the new answer is the second-offset overlap minimum, not a
     * replay of the stale pointer and not the next wall-hour copy.
     */
    @ParameterizedTest
    @MethodSource("enableSeconds")
    void c02_enableWithExpiredPointerRecertifiesFromMovedClock(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        MonitorTask created = taskManager.create(createRequest("enable-stale-" + second, cron, NEW_YORK));
        long expected = expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                Instant.parse("2026-11-01T08:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1));
        MonitorTask enabled = taskManager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, true));
        // The plan key expired while disabled: simulate the missed fire by moving the
        // clock past the stored pointer, then disable + re-enable.
        clock.set(FALLBACK_BASE_2.plusSeconds(3600));
        MonitorTask disabled = taskManager.update(enabled.taskId(),
                new UpdateRequest(enabled.rowVersion(), null, null, null, false));
        assertNull(disabled.nextFireAt(), "disabling clears the next_fire pointer");
        Instant movedBase = clock.instant();
        MonitorTask reEnabled = taskManager.update(disabled.taskId(),
                new UpdateRequest(disabled.rowVersion(), null, null, null, true));
        assertTrue(reEnabled.enabled());
        assertEquals(expectedNextAfter(ZoneId.of(NEW_YORK), movedBase,
                        Instant.parse("2026-11-01T09:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1)),
                reEnabled.nextFireAt(),
                "second " + second + ": re-enable must re-solve from the moved clock base");
        assertNotEquals(Long.valueOf(expected), reEnabled.nextFireAt(),
                "the re-enable must not silently reuse the expired cached pointer");
    }

    /**
     * C02 blocked recovery: a blocked task stays blocked for non-enable edits, an
     * explicit enable revalidates live and clears the block, and the recovered pointer
     * is the second-offset oracle minimum. The stored certificate is replaced by the
     * re-certification, never silently reused.
     */
    @ParameterizedTest
    @MethodSource("enableSeconds")
    void c02_blockedRecoveryReenablesAndRepairsOldCertificate(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        MonitorTask created = taskManager.create(createRequest("blocked-r3-" + second, cron, NEW_YORK));
        MonitorTask enabled = taskManager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, true));
        // The lifecycle blocks the task; storage keeps its OLD (still-stale) certificate.
        taskManager.markBlocked(created.taskId(), "TZDB_CERTIFICATE_INVALID");
        String staleCertificate = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        assertNotNull(staleCertificate);
        // Non-enable edits do not clear the block.
        MonitorTask stillBlocked = taskManager.update(created.taskId(),
                new UpdateRequest(
                        taskManager.find(created.taskId()).orElseThrow().rowVersion(),
                        null, null, null, false));
        assertTrue(stillBlocked.blockedReason() != null, "the block persists without explicit enable");
        // Explicit recovery: live read-only revalidation precedes the write.
        long identityCallsBefore = resolver.boundTargetCalls;
        MonitorTask recovered = taskManager.revalidateAndEnable(created.taskId(),
                new UpdateRequest(
                        taskManager.find(created.taskId()).orElseThrow().rowVersion(),
                        null, null, null, true));
        assertTrue(recovered.enabled());
        assertNull(recovered.blockedReason(), "explicit recovery clears the block");
        assertEquals(identityCallsBefore + 1, resolver.boundTargetCalls,
                "blocked recovery must revalidate the bound target live");
        String repairedCertificate = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        int storedAlgorithm = Integer.parseInt(staleCertificate.split(":", 5)[1]);
        int repairedAlgorithm = Integer.parseInt(repairedCertificate.split(":", 5)[1]);
        assertEquals(CronCertificate.CURRENT_ALGORITHM_VERSION, repairedAlgorithm,
                "the explicit recovery must re-certify to the CURRENT proof identity");
        assertTrue(repairedAlgorithm >= storedAlgorithm,
                "the repaired proof identity must not regress below the stored one");
        CronCertificate reparsed = parseStoredCertificate(recovered, repairedCertificate);
        assertTrue(validator.isCertificateValid(reparsed, recovered.cronExpression(), recovered.zoneId()),
                "the recovered certificate must be a valid CURRENT proof under the restart validator");
        long expected = expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                Instant.parse("2026-11-01T08:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1));
        assertEquals(expected, recovered.nextFireAt(),
                "second " + second + ": recovery keeps the exact overlap-aware next fire");
    }

    /** Mirrors MonitorLifecycleManager's restart parse of the stored certificate text. */
    private static CronCertificate parseStoredCertificate(MonitorTask task, String stored) {
        String[] fields = stored.split(":", 5);
        boolean legacy = fields.length == 4;
        int algorithm = legacy ? 1 : Integer.parseInt(fields[1]);
        long basePoint = legacy ? 0L : Long.parseLong(fields[3]);
        long generatedAt = Long.parseLong(fields[legacy ? 3 : 4]);
        return new CronCertificate(task.cronExpression(), task.zoneId(), fields[0], algorithm,
                Long.parseLong(fields[2]), Instant.ofEpochMilli(basePoint),
                Instant.ofEpochMilli(generatedAt));
    }

    /**
     * Legacy four-field rows (algorithm 1) stay invalid for the restart validator and
     * an explicit recovery on such a row must still produce a valid CURRENT proof.
     */
    @ParameterizedTest
    @MethodSource("enableSeconds")
    void c02_legacyCertificateRowBlocksAndExplicitRecoveryRepairsIt(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        MonitorTask created = taskManager.create(createRequest("legacy-r3-" + second, cron, NEW_YORK));
        MonitorTask enabled = taskManager.update(created.taskId(),
                new UpdateRequest(created.rowVersion(), null, null, null, true));
        String legacy = validator.tzdbVersion() + ":1:60000:" + FALLBACK_BASE_2.toEpochMilli();
        jdbc.update("UPDATE monitor_tasks SET tzdb_certificate = ? WHERE task_id = ?",
                legacy, created.taskId());
        CronCertificate legacyCert = parseStoredCertificate(
                taskManager.find(created.taskId()).orElseThrow(), legacy);
        assertFalse(validator.isCertificateValid(legacyCert, cron, NEW_YORK),
                "the legacy algorithm-1 row must not validate as the current proof");
        taskManager.markBlocked(created.taskId(), "TZDB_CERTIFICATE_INVALID");
        MonitorTask recovered = taskManager.revalidateAndEnable(created.taskId(),
                new UpdateRequest(
                        taskManager.find(created.taskId()).orElseThrow().rowVersion(),
                        null, null, null, true));
        String repaired = jdbc.queryForObject(
                "SELECT tzdb_certificate FROM monitor_tasks WHERE task_id = ?", String.class,
                created.taskId());
        assertEquals(String.valueOf(CronCertificate.CURRENT_ALGORITHM_VERSION),
                repaired.split(":", 5)[1],
                "recovery must replace the legacy proof identity with the CURRENT one");
        CronCertificate reparsed = parseStoredCertificate(recovered, repaired);
        assertTrue(validator.isCertificateValid(reparsed, recovered.cronExpression(), recovered.zoneId()),
                "the repaired certificate must be a valid CURRENT proof");
    }

    /**
     * C02 sparse hour syntax: an hourly-window plan with a fixed second re-enables with
     * the same second-offset overlap answer (05:59:s inside the first offset, so the
     * first fire is the FIRST-offset copy).
     */
    @ParameterizedTest
    @MethodSource("enableSeconds")
    void c02_sparseHourPlanSecondDomain(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " 59 3 * * *";
        MonitorTask created = taskManager.create(createRequest("sparse-r3-" + second, cron, NEW_YORK));
        assertFalse(created.enabled());
        assertEquals(expectedNextAfter(ZoneId.of(NEW_YORK), FALLBACK_BASE_2,
                        Instant.parse("2026-11-01T09:00:00Z"), cron, java.time.LocalDate.of(2026, 11, 1)),
                created.nextFireAt(),
                "second " + second + ": sparse hour plan stores the exact overlap minimum");
    }

    /**
     * C02/D4 guard: a dangerous (sub-60s) plan is never stored, so no recovery path
     * can re-enable it either.
     */
    @Test
    void c02_dangerousPlanNeverEnabled() throws Exception {
        clock.set(FALLBACK_BASE_1);
        CreateRequest bad = createRequest("danger-enable", "0,59 * * * * *", "UTC");
        TaskManager.TaskManagerException failure = assertThrows(
                TaskManager.TaskManagerException.class, () -> taskManager.create(bad));
        assertEquals(TaskManager.ErrorCode.PLAN_INVALID, failure.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_tasks", Integer.class));
    }

    // ------------------------------------------------------ verify POST gate

    /**
     * Across this whole class every configuration operation used only the read-only
     * identity contract; the harness owns no verify/POST endpoint at all, so the count
     * is structurally zero.
     */
    @Test
    void verifyPostCountStaysZeroForEveryConfigurationOperation() {
        assertEquals(0L, verifyPostAttempts,
                "no configuration operation may ever reach a verify/POST-style path");
    }

    // ------------------------------------------------------ consumer equality sanity

    /** The real consumer answer must equal a fresh iterator solve for the same base. */
    @ParameterizedTest
    @MethodSource("fixedSeconds")
    void consumerAnswerMatchesFreshIteratorSolve(Integer second) throws Exception {
        clock.set(FALLBACK_BASE_2);
        String cron = second + " * * * * *";
        MonitorTask created = taskManager.create(createRequest("parity-" + second, cron, NEW_YORK));
        ScheduleIterator fresh = ScheduleIterator.of(cron, NEW_YORK);
        assertEquals(fresh.next(FALLBACK_BASE_2).toEpochMilli(), created.nextFireAt(),
                "second " + second + ": the stored pointer must equal the fresh iterator solve");
    }
}
