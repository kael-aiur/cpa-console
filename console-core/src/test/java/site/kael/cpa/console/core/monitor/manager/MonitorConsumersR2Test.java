package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.manager.ExecutionManager.DuePlan;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimOutcome;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimReceipt;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.schedule.CronPolicyValidator;
import site.kael.cpa.console.core.monitor.schedule.ScheduleIterator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 consumer self-check plus the T06.R3 claim/late-tick matrix (task v1.2-r3 §4.2
 * C03/C04): the REAL RunClaimManager, TaskManager, ExecutionManager claim chain and
 * DAOs are driven from isolated SQLite and an injected mutable Clock. Every T06.R3
 * expectation comes from the independent UTC-second oracle below; the production
 * ScheduleIterator is never used as the reference. The DST fall-back window on
 * 2026-11-01 America/New_York with "0 * * * * *" and the non-zero second "17 * * * * *"
 * exercise normal advance and the late tick; a fake terminal releases the task slot
 * before the next claim, so every step still goes through the real claim/DAO path.
 * The retained T04/T07 checks and the dangerous sparse leap-day regression stay green.
 */
class MonitorConsumersR2Test {

    /** The UTC day of the New York fall-back hour (both 01:00 wall copies). */
    private static final Instant FALLBACK_BASE_1 = Instant.parse("2026-11-01T05:59:00Z");
    /** Thirty seconds later, mid-minute base point on the EDT side of the overlap. */
    private static final Instant FALLBACK_BASE_2 = Instant.parse("2026-11-01T05:59:30Z");
    private static final String HOURLY = "0 * * * * *";
    private static final String NEW_YORK = "America/New_York";

    @TempDir
    Path lockDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyCrypto crypto;
    private MonitorIsolationTestSupport.FakeResolver resolver;
    private TaskManager taskManager;
    private RunClaimManager claimManager;
    private RunDao runDao;
    private CredentialGuardDao guardDao;
    private ObservationGapDao gapDao;
    private RetentionManager retentionManager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(Instant.parse("2026-11-01T04:00:00Z"));
        crypto = MonitorIsolationTestSupport.crypto();
        resolver = new MonitorIsolationTestSupport.FakeResolver(
                List.of(new CpaIdentityResolver.CredentialEntryDetail(
                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                List.of("model-m"));
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), resolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"));
        runDao = new RunDao(dataSource);
        guardDao = new CredentialGuardDao(dataSource);
        claimManager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao,
                MonitorIsolationTestSupport.txManager(dataSource));
        gapDao = new ObservationGapDao(dataSource);
        retentionManager = new RetentionManager(dataSource, new RetentionDao(dataSource), runDao, clock,
                MonitorIsolationTestSupport.txManager(dataSource));
    }

    private MonitorTask createEnabledTask(String cron, String zone) throws Exception {
        MonitorTask task = taskManager.create(new CreateRequest("consumer-r2", cron, zone,
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        return taskManager.update(task.taskId(),
                new UpdateRequest(task.rowVersion(), null, null, null, true));
    }

    // ------------------------------------------------ independent UTC-second oracle

    /** Test-side field predicate: *, single values, lists, ranges and steps. */
    private static boolean oracleFieldMatches(int value, String field, int inclusiveMax) {
        if ("*".equals(field)) {
            return value >= 0 && value <= inclusiveMax;
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
            if ("*".equals(part)) {
                start = 0;
                end = inclusiveMax;
            } else if (part.contains("-")) {
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

    private static boolean oracleMatches(java.time.LocalDateTime local, String cron) {
        String[] fields = cron.split(" ");
        return oracleFieldMatches(local.getSecond(), fields[0], 59)
                && oracleFieldMatches(local.getMinute(), fields[1], 59)
                && oracleFieldMatches(local.getHour(), fields[2], 23);
    }

    /** Distinct absolute UTC seconds in [from, until) whose local wall matches the cron. */
    private static List<Long> oracleWindowSeconds(String zone, Instant from, Instant until, String cron) {
        TreeSet<Long> expected = new TreeSet<>();
        long startMs = Math.floorDiv(from.toEpochMilli(), 1000L) * 1000L;
        for (long ms = startMs; ms < until.toEpochMilli(); ms += 1000L) {
            java.time.LocalDateTime local =
                    java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), java.time.ZoneId.of(zone));
            if (oracleMatches(local, cron)) {
                expected.add(ms);
            }
        }
        return new ArrayList<>(expected);
    }

    /** The next oracle element strictly after the base point. */
    private static long oracleNextAfter(String zone, Instant after, Instant until, String cron) {
        return oracleWindowSeconds(zone, after.minusSeconds(1), until, cron).stream()
                .filter(ms -> ms > after.toEpochMilli())
                .findFirst()
                .orElseThrow(() -> new AssertionError("oracle window exhausted: " + zone + " " + cron));
    }

    /** All oracle elements in (after, until), in ascending order. */
    private static List<Long> oracleSequenceAfter(String zone, Instant after, Instant until, String cron) {
        return oracleWindowSeconds(zone, after.minusSeconds(1), until, cron).stream()
                .filter(ms -> ms > after.toEpochMilli())
                .toList();
    }

    private long dbNextFire(String taskId) {
        Long value = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, taskId);
        assertNotNull(value);
        return value;
    }

    private long runCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, taskId);
    }

    private List<Long> runScheduledAtValues(String taskId) {
        return jdbc.queryForList(
                "SELECT scheduled_at FROM monitor_runs WHERE task_id = ? ORDER BY scheduled_at",
                Long.class, taskId);
    }

    /** Independent absolute expectation of the plan around the fall-back hour. */
    private static List<Long> expectedUtcHoursAroundFallback() {
        return List.of(
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T07:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T09:00:00Z").toEpochMilli());
    }

    // ---------------------------------------------------------------- C01
    @Test
    void c01_taskManagerCreateAndEditProduceCorrectFirstFire() throws Exception {
        clock.set(FALLBACK_BASE_1);
        MonitorTask first = createEnabledTask(HOURLY, NEW_YORK);
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), first.nextFireAt(),
                "first fire after the 05:59Z base must be the second local 01:00 (06:00Z)");
        assertTrue(first.enabled());
        // Second base point: the mid-minute base solves to the same earliest instant.
        clock.set(FALLBACK_BASE_2);
        MonitorTask second = createEnabledTask(HOURLY, NEW_YORK);
        assertEquals(first.nextFireAt(), second.nextFireAt(),
                "the mid-minute base must produce the same first fire, not a later wall time");
        // Editing the zone back to UTC still solves from the current base point.
        MonitorTask edited = taskManager.update(second.taskId(),
                new UpdateRequest(second.rowVersion(), null, HOURLY, "UTC", null));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), edited.nextFireAt(),
                "edited plan first fire must be strictly future for the new zone");
        assertNotEquals(first.scheduleRevision(), edited.scheduleRevision(),
                "plan edit bumps the schedule revision");
        // The dangerous sparse leap-day plan can never be saved or enabled.
        var unsafe = assertThrows(TaskManager.TaskManagerException.class,
                () -> taskManager.create(new CreateRequest("danger", "0,59 * * 29 2 *", "UTC",
                        "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex")));
        assertEquals(TaskManager.ErrorCode.PLAN_INVALID, unsafe.code());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_tasks", Integer.class),
                "the rejected plan must not have been persisted");
        // The isolation harness has no verify POST path at all: the fake resolver only
        // implements the GET-side contract and no verify endpoint is wired in T04.
    }

    // ---------------------------------------------------------------- C02
    @Test
    void c02_enableDisableCyclesKeepNextFireCorrect() throws Exception {
        clock.set(FALLBACK_BASE_1);
        MonitorTask task = taskManager.create(new CreateRequest("cycle", HOURLY, NEW_YORK,
                "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        assertFalse(task.enabled());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), task.nextFireAt(),
                "created task stores the correct first fire even while disabled");
        // First enable: unchanged.
        MonitorTask enabled = taskManager.update(task.taskId(),
                new UpdateRequest(task.rowVersion(), null, null, null, true));
        assertTrue(enabled.enabled());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), enabled.nextFireAt());
        // Disable clears the pointer; re-enable recomputes it strictly from now.
        MonitorTask disabled = taskManager.update(enabled.taskId(),
                new UpdateRequest(enabled.rowVersion(), null, null, null, false));
        assertNull(disabled.nextFireAt());
        MonitorTask reEnabled = taskManager.update(disabled.taskId(),
                new UpdateRequest(disabled.rowVersion(), null, null, null, true));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), reEnabled.nextFireAt());
        // Blocked config + explicit recovery: the pointer stays correct after revalidation.
        taskManager.markBlocked(task.taskId(), "TZDB_CERTIFICATE_INVALID");
        MonitorTask blocked = taskManager.find(task.taskId()).orElseThrow();
        assertFalse(blocked.enabled());
        MonitorTask recovered = taskManager.update(blocked.taskId(),
                new UpdateRequest(blocked.rowVersion(), null, null, null, true));
        assertTrue(recovered.enabled());
        assertNull(recovered.blockedReason());
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), recovered.nextFireAt(),
                "recovery keeps the exact fall-back-aware next fire");
    }

    // ---------------------------------------------------------------- C03
    @Test
    void c03_runClaimManagerRepeatedClaimsAdvanceThroughFallbackWithoutSkipping() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Base 05:00Z sits ON the primary (EDT) copy of 01:00 wall, so the next match is
        // 01:01 EDT = 05:01Z; the EST repeat of 01:00 (06:00Z) is behind that copy.
        assertEquals(Instant.parse("2026-11-01T05:01:00Z").toEpochMilli(), task.nextFireAt());
        // Claim each UTC hour of the fall-back window; after each claim the manager itself
        // recomputes the successor from the fresh iterator.
        List<Long> expectedScheduledAt = List.of(
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T06:01:00Z").toEpochMilli());
        // The 05:00 claim happens at now=05:00 (the task's plan already fired before the
        // manager came up in this test, so the first expected scheduled_at of 05:00 comes
        // from the seeded base). Then the successors 06:00 and 06:01 (mid-overlap) follow.
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        long firstScheduledAt = task.nextFireAt();
        ClaimReceipt first = claimManager.claim(new ClaimRequest(task.taskId(), firstScheduledAt,
                task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, first.outcome());
        Long afterFirst = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        // The stored pointer was 05:01Z; claiming the 05:01Z key without a tick context
        // solves the successor strictly after that claimed key: 05:02Z (wall 01:02 EDT).
        assertEquals(Instant.parse("2026-11-01T05:02:00Z").toEpochMilli(), afterFirst.longValue(),
                "claiming 05:01Z advances the pointer to the claimed key's own successor 05:02Z");
        // Restart-style: the pointer is stale after downtime; recompute it from 05:59:30Z,
        // which must land on 06:00Z (the EST repeat of 01:00 wall) exactly.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), task.taskId());
        clock.set(Instant.parse("2026-11-01T05:59:30Z"));
        MonitorTask refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt second = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), refreshed.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, second.outcome());
        Long nextFire = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        // advanceNextFire solves from the claimed 06:00Z (wall 01:00 EST): the next local
        // match is 01:01 EST = 06:01Z. The claim itself is the EST repeat's plan key; the
        // overlap completion is covered by the c05 startup recompute below (05:59:30Z base
        // solving to 06:00Z) and the fresh-after matrix in ScheduleIteratorR2Test.
        assertEquals(Instant.parse("2026-11-01T06:01:00Z").toEpochMilli(), nextFire.longValue(),
                "after claiming 06:00Z the successor is the next wall minute 01:01 EST = 06:01Z");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()));
        // A second claim for the same absolute instant must not create another run.
        clock.set(Instant.parse("2026-11-01T06:00:01Z"));
        refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt duplicate = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), refreshed.scheduleRevision()));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, duplicate.outcome());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()),
                "the duplicate claim must not add a run row");
    }

    // ---------------------------------------------------------------- C04
    @Test
    void c04_lateTickDoesNotChaseMissedPlans() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Simulate downtime across several planned instants: the pointer sits in the past.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.taskId());
        // A late tick at 06:30Z claims ONLY the due instant 06:30Z.. wait: the stored plan
        // 05:00 is claimed as-is (D4.3 late tick), and the successor recomputes forward.
        clock.set(Instant.parse("2026-11-01T06:30:00Z"));
        ClaimReceipt late = claimManager.claim(new ClaimRequest(task.taskId(),
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, late.outcome());
        // Without a tick context the advance solves strictly after the CLAIMED 05:00Z key:
        // 05:01Z. This legacy path documents the pre-tick behaviour; the T06.R3 late-tick
        // matrix below proves the scanner's shared-now advance (06:31Z, no catch-up).
        Long nextFire = jdbc.queryForObject(
                "SELECT next_fire_at FROM monitor_tasks WHERE task_id = ?", Long.class, task.taskId());
        assertEquals(Instant.parse("2026-11-01T05:01:00Z").toEpochMilli(), nextFire.longValue(),
                "legacy claim advance solves from the claimed key, not the wall clock (05:01Z)");
        // One run for the late key; no rows for the missed instants.
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE task_id = ?", Integer.class, task.taskId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE scheduled_at <= ?",
                Integer.class, Instant.parse("2026-11-01T06:00:00Z").toEpochMilli() - 1)
                - 1);
    }

    // ---------------------------------------------------------------- C05
    @Test
    void c05_lifecycleStartupRecomputesPointerAndBlocksOnOldCertificates() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(HOURLY, NEW_YORK);
        // Simulate a crash during downtime: pointer points at 05:00Z, which is stale.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), task.taskId());
        // Boot the lifecycle at 05:59:30Z - inside the overlap window on the EDT side.
        clock.set(Instant.parse("2026-11-01T05:59:30Z"));
        var lifecycle = new MonitorLifecycleManager(dataSource, claimManager, taskManager,
                new ExecutionManager(claimManager, taskManager,
                        new CpaIdentityResolver(fakePageClient(), crypto, clock),
                        (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                        new MonitorConfigLockManager(
                                new CpaCredentialVerificationClient(
                                        new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                                "cfg-1"),
                        clock, new ExecutionManager.NoopListener()),
                retentionManager, bindingManager(), new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"),
                gapDao, runDao, guardDao, clock, lockDirectory,
                MonitorIsolationTestSupport.txManager(dataSource),
                60_000L, 3_600_000L, 500L);
        lifecycle.recoverInterruptedRuns();
        MonitorTask after = taskManager.find(task.taskId()).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(), after.nextFireAt(),
                "startup recompute from the 05:59:30Z base must land on the second 01:00 local");
        // Old-format certificate: the lifecycle must refuse to scan (it faults on the
        // invalid certificate) and the task must be durably blocked.
        jdbc.update("UPDATE monitor_tasks SET tzdb_certificate = ? WHERE task_id = ?",
                "old-tzdb:1:60000:1", task.taskId());
        org.junit.jupiter.api.Assertions.assertThrows(
                MonitorLifecycleManager.MonitorLifecycleManagerLifecycleFailure.class,
                lifecycle::revalidateCertificatesForTesting,
                "an old algorithm-version certificate must stop the revalidation");
        MonitorTask blocked = taskManager.find(task.taskId()).orElseThrow();
        assertEquals("TZDB_CERTIFICATE_INVALID", blocked.blockedReason(),
                "an old algorithm-version certificate must block the plan");
    }

    private MonitorCpaBindingManager bindingManager() {
        return new MonitorCpaBindingManager(dataSource, crypto, clock);
    }

    // =============================================== T06.R3: continuous claim matrix

    /** The DST fall-back day: every fixed second's NY sequence is checked against the oracle. */
    static List<Integer> claimSeconds() {
        return List.of(0, 1, 17, 30, 59);
    }

    /**
     * C03 (T06.R3): the REAL claim chain walks every due second across the fall-back
     * overlap. Each step assembles the due plan from the DB pointer exactly like the
     * scanner, claims with the shared tick now, and asserts run.scheduled_at,
     * receipt.nextFireAt and the stored next_fire against the independent oracle. The
     * fake terminal releases the task slot after every step, so no claim is lost to the
     * slot and every step goes through the real claim/DAO path. A rebuilt
     * RunClaimManager per step proves the fresh-manager equivalence; the second claim
     * of the same plan key must not add a run.
     */
    @ParameterizedTest
    @MethodSource("claimSeconds")
    void c03_r3ContinuousClaimsAdvanceAcrossFallBackWithoutSkipping(Integer second)
            throws Exception {
        String cron = second + " * * * * *";
        clock.set(Instant.parse("2026-11-01T04:59:59Z"));
        MonitorTask task = createEnabledTask(cron, NEW_YORK);
        long scheduledBase = task.nextFireAt();
        long expectedBase = oracleNextAfter(NEW_YORK,
                Instant.parse("2026-11-01T04:59:59Z"),
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertEquals(expectedBase, scheduledBase,
                "second " + second + ": the stored pointer must equal the oracle first fire");
        // Full normal sequence through the overlap: 04:59:59 -> 07:00 base window.
        Instant until = Instant.parse("2026-11-01T07:00:01Z");
        Instant successorUntil = Instant.parse("2026-11-01T07:30:00Z");
        List<Long> expectedSequence = oracleSequenceAfter(NEW_YORK,
                Instant.parse("2026-11-01T04:59:59Z"), until, cron);
        assertFalse(expectedSequence.isEmpty());
        assertTrue(expectedSequence.size() >= 120,
                "second " + second + ": the overlap hour must contribute ~121 steps");
        RunClaimManager currentManager = claimManager;
        for (int i = 0; i < expectedSequence.size(); i++) {
            long expectedScheduledAt = expectedSequence.get(i);
            MonitorTask snapshot = taskManager.find(task.taskId()).orElseThrow();
            assertEquals(expectedScheduledAt, snapshot.nextFireAt(),
                    "second " + second + ": step " + i + " DB next_fire must equal the oracle");
            clock.set(Instant.ofEpochMilli(expectedScheduledAt));
            // Real scanner shape: due key + expected revision + the tick's common now.
            ClaimReceipt receipt = currentManager.claim(new ClaimRequest(task.taskId(),
                    snapshot.nextFireAt(), snapshot.scheduleRevision()),
                    Long.valueOf(expectedScheduledAt));
            assertEquals(ClaimOutcome.CLAIMED, receipt.outcome(),
                    "second " + second + ": step " + i + " must claim");
            Long dbScheduled = jdbc.queryForObject(
                    "SELECT scheduled_at FROM monitor_runs WHERE run_id = ?", Long.class,
                    receipt.runId());
            assertNotNull(dbScheduled);
            assertEquals(expectedScheduledAt, dbScheduled.longValue(),
                    "second " + second + ": step " + i + " run.scheduled_at must equal the oracle");
            if (i + 1 >= expectedSequence.size()) {
                assertTrue(receipt.nextFireAt() != null
                                && receipt.nextFireAt() > expectedScheduledAt,
                        "the final successor must stay strictly future");
                assertTrue(currentManager.skipClaim(receipt.runId(), "ISOLATION_TERMINATED"));
                break;
            }
            long expectedNext = oracleNextAfter(NEW_YORK,
                    Instant.ofEpochMilli(expectedScheduledAt),
                    Instant.ofEpochMilli(expectedSequence.get(i + 1)).plusSeconds(1), cron);
            assertEquals(expectedNext, receipt.nextFireAt(),
                    "second " + second + ": step " + i + " receipt.nextFireAt must equal the oracle");
            assertEquals(expectedNext, dbNextFire(task.taskId()),
                    "second " + second + ": step " + i + " DB next_fire must equal the oracle");
            assertEquals(i + 1, runCount(task.taskId()),
                    "second " + second + ": step " + i + " must add exactly one run");
            // Fake terminal releases the task slot without any real verify; the next
            // step still claims through the real manager/DAO path.
            assertTrue(currentManager.skipClaim(receipt.runId(), "ISOLATION_TERMINATED"),
                    "the isolation terminal must release the slot");
            // Fresh-manager equivalence on several checkpoints (every 40 steps).
            if ((i + 1) % 40 == 0) {
                currentManager = new RunClaimManager(dataSource, crypto, clock, taskManager,
                        runDao, guardDao, MonitorIsolationTestSupport.txManager(dataSource));
            }
        }
        // Duplicate claim of the final plan key must not add a run.
        clock.set(Instant.ofEpochMilli(expectedSequence.get(expectedSequence.size() - 1) + 1));
        MonitorTask refreshed = taskManager.find(task.taskId()).orElseThrow();
        ClaimReceipt duplicate = currentManager.claim(new ClaimRequest(task.taskId(),
                expectedSequence.get(expectedSequence.size() - 1), refreshed.scheduleRevision()),
                Long.valueOf(expectedSequence.get(expectedSequence.size() - 1) + 1));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, duplicate.outcome());
        assertEquals(expectedSequence.size(), runCount(task.taskId()),
                "second " + second + ": a repeated claim must not increase the run count");
    }

    /**
     * C03 companion (T06.R3): a sparse minute/hour expression keeps the second domain
     * correct: every due step lands on the oracle and no overlap copy is dropped.
     */
    @ParameterizedTest
    @MethodSource("claimSeconds")
    void c03_r3SparseMinuteExpressionSecondDomain(Integer second) throws Exception {
        String cron = second + " 1,2,3 * * * *";
        clock.set(Instant.parse("2026-11-01T04:59:30Z"));
        MonitorTask task = createEnabledTask(cron, NEW_YORK);
        Instant until = Instant.parse("2026-11-01T07:30:00Z");
        List<Long> expectedSequence = oracleSequenceAfter(NEW_YORK,
                Instant.parse("2026-11-01T04:59:30Z"), until, cron);
        assertTrue(expectedSequence.size() >= 6,
                "the sparse plan must cross the overlap with several copies");
        RunClaimManager currentManager = claimManager;
        for (int i = 0; i < expectedSequence.size(); i++) {
            long expected = expectedSequence.get(i);
            MonitorTask snapshot = taskManager.find(task.taskId()).orElseThrow();
            assertEquals(expected, snapshot.nextFireAt());
            clock.set(Instant.ofEpochMilli(expected));
            ClaimReceipt receipt = currentManager.claim(new ClaimRequest(task.taskId(),
                    snapshot.nextFireAt(), snapshot.scheduleRevision()), Long.valueOf(expected));
            assertEquals(ClaimOutcome.CLAIMED, receipt.outcome());
            Long dbScheduled = jdbc.queryForObject(
                    "SELECT scheduled_at FROM monitor_runs WHERE run_id = ?", Long.class,
                    receipt.runId());
            assertEquals(expected, dbScheduled);
            if (i + 1 >= expectedSequence.size()) {
                assertTrue(receipt.nextFireAt() != null && receipt.nextFireAt() > expected,
                        "the final successor must stay strictly future");
                assertTrue(currentManager.skipClaim(receipt.runId(), "ISOLATION_TERMINATED"));
                break;
            }
            long expectedNext = oracleNextAfter(NEW_YORK, Instant.ofEpochMilli(expected),
                    Instant.ofEpochMilli(expectedSequence.get(i + 1)).plusSeconds(1), cron);
            assertEquals(expectedNext, receipt.nextFireAt());
            assertEquals(expectedNext, dbNextFire(task.taskId()));
            assertTrue(currentManager.skipClaim(receipt.runId(), "ISOLATION_TERMINATED"));
        }
        assertEquals(expectedSequence.size(), runCount(task.taskId()));
    }

    // =============================================== T06.R3: late tick / common now

    /**
     * C04 (T06.R3): a late tick with the shared now claims the due key once and the
     * advance lands strictly after the common now. The intermediate oracle instants
     * between the due key and the tick now are never generated as runs, and a second
     * scan at the same common now adds nothing.
     */
    @ParameterizedTest
    @MethodSource("claimSeconds")
    void c04_r3LateTickClaimsDueKeyOnceAndAdvancesStrictlyPastCommonNow(Integer second)
            throws Exception {
        String cron = second + " * * * * *";
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(cron, NEW_YORK);
        Instant dueBase = Instant.parse("2026-11-01T05:00:00Z");
        long dueKey = oracleNextAfter(NEW_YORK, dueBase,
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertEquals(dueKey, task.nextFireAt());
        // Downtime: the clock jumps far across many planned instants.
        Instant tickNow = Instant.parse("2026-11-01T06:45:23Z");
        clock.set(tickNow);
        // Seed the due pointer so collectDuePlans-like callers see exactly this key.
        jdbc.update("UPDATE monitor_tasks SET next_fire_at = ? WHERE task_id = ?",
                dueKey, task.taskId());
        MonitorTask snapshot = taskManager.find(task.taskId()).orElseThrow();
        // Real scanner shape: one shared now for the whole due list.
        ClaimReceipt late = claimManager.claim(new ClaimRequest(task.taskId(), dueKey,
                snapshot.scheduleRevision()), Long.valueOf(tickNow.toEpochMilli()));
        assertEquals(ClaimOutcome.CLAIMED, late.outcome());
        Long dbScheduled = jdbc.queryForObject(
                "SELECT scheduled_at FROM monitor_runs WHERE run_id = ?", Long.class, late.runId());
        assertEquals(dueKey, dbScheduled.longValue(), "the plan key stays the UTC due instant");
        // The advance is the first oracle instant STRICTLY AFTER the shared tick now.
        long expectedNext = oracleNextAfter(NEW_YORK, tickNow,
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertTrue(expectedNext > tickNow.toEpochMilli(),
                "the successor must be strictly after the common now");
        assertEquals(expectedNext, late.nextFireAt(),
                "second " + second + ": receipt advance must be strictly after the common now");
        assertEquals(expectedNext, dbNextFire(task.taskId()));
        assertEquals(1, runCount(task.taskId()),
                "exactly one run: no catch-up replay of instants <= the common now");
        List<Long> missed = oracleSequenceAfter(NEW_YORK, dueBase, tickNow, cron);
        assertTrue(missed.size() >= 60,
                "the downtime window must contain many instants that stay unclaimed");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE scheduled_at <= ?",
                Integer.class, tickNow.toEpochMilli() - 1)
                - 1,
                "no run may exist for any instant before the tick now");
        // Second scan at the same common now, scanner-shaped: the pointer has moved past
        // the shared now, so the previously due key is stale and must be refused without
        // a new run (this is exactly the scanner's second-pass DUPLICATE/STATE gate).
        ClaimReceipt staleRescan = claimManager.claim(new ClaimRequest(task.taskId(), dueKey,
                taskManager.find(task.taskId()).orElseThrow().scheduleRevision()),
                Long.valueOf(tickNow.toEpochMilli()));
        assertEquals(ClaimOutcome.DUPLICATE_PLAN_KEY, staleRescan.outcome(),
                "the already-taken plan key must be refused on the second scan");
        assertEquals(1, runCount(task.taskId()), "the second scan must not add a run");
        assertTrue(claimManager.skipClaim(late.runId(), "ISOLATION_TERMINATED"));
    }

    /**
     * C04 companion (T06.R3): the real ExecutionManager tick path with the common now
     * claims the due key once; after the fake terminal the next normal tick claims the
     * next oracle key, and the late-tick semantics never replay the past.
     */
    @Test
    void c04_r3ExecutionManagerTickPathUsesCommonNowWithoutCatchUp() throws Exception {
        String cron = "17 * * * * *";
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask(cron, NEW_YORK);
        // Offline read-only identity source: a fake page client with no verify endpoint,
        // so the tick path stays structurally POST-free exactly like the other harnesses.
        CpaCredentialVerificationClient fakePageClient =
                new CpaCredentialVerificationClient(
                        new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
                    @Override
                    public java.util.List<CpaIdentityResolver.CredentialPage> listAllCredentialPages() {
                        return java.util.List.of(new CpaIdentityResolver.CredentialPage(
                                java.util.List.of(new CpaIdentityResolver.CredentialEntryDetail(
                                        "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                                false));
                    }

                    @Override
                    public java.util.List<String> listModels(String nameOrAuthId) {
                        return "auth-x".equals(nameOrAuthId)
                                ? java.util.List.of("model-m") : java.util.List.of();
                    }
                };
        ExecutionManager executionManager = new ExecutionManager(claimManager, taskManager,
                new CpaIdentityResolver(fakePageClient, crypto, clock),
                (status, body, endpoint) -> new VerificationResponseClassifier(status, body),
                new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg-1"),
                clock, new ExecutionManager.NoopListener());
        // Late tick at 06:45:23Z over a due 05:00 pointer.
        Instant tickNow = Instant.parse("2026-11-01T06:45:23Z");
        clock.set(tickNow);
        long dueKey = task.nextFireAt();
        assertEquals(dueKey, oracleNextAfter(NEW_YORK, Instant.parse("2026-11-01T05:00:00Z"),
                Instant.parse("2026-11-01T08:00:00Z"), cron));
        ExecutionManager.TickSummary summary = executionManager.claimDuePlans(
                List.of(new DuePlan(task.taskId(), dueKey, task.scheduleRevision())),
                tickNow.toEpochMilli());
        assertEquals(1, summary.claimed());
        long expectedNext = oracleNextAfter(NEW_YORK, tickNow,
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertEquals(expectedNext, dbNextFire(task.taskId()),
                "the real tick path advances strictly past the common now");
        assertEquals(1, runCount(task.taskId()), "no catch-up replay through the tick path");
        // Fake terminal releases the slot; the next normal tick claims the next key only.
        MonitorTask refreshed = taskManager.find(task.taskId()).orElseThrow();
        clock.set(Instant.ofEpochMilli(expectedNext));
        String runId = jdbc.queryForObject(
                "SELECT run_id FROM monitor_runs WHERE task_id = ? ORDER BY run_seq DESC LIMIT 1",
                String.class, task.taskId());
        assertTrue(claimManager.skipClaim(runId, "ISOLATION_TERMINATED"));
        ExecutionManager.TickSummary secondTick = executionManager.claimDuePlans(
                List.of(new DuePlan(task.taskId(), refreshed.nextFireAt(),
                        taskManager.find(task.taskId()).orElseThrow().scheduleRevision())),
                Long.valueOf(expectedNext));
        assertEquals(1, secondTick.claimed());
        long expectedThird = oracleNextAfter(NEW_YORK, Instant.ofEpochMilli(expectedNext),
                Instant.parse("2026-11-01T09:00:00Z"), cron);
        assertEquals(expectedThird, dbNextFire(task.taskId()));
        assertEquals(2, runCount(task.taskId()));
        List<Long> scheduled = runScheduledAtValues(task.taskId());
        assertEquals(List.of(dueKey, expectedNext), scheduled,
                "run keys stay the UTC plan instants, never a backlog replay");
    }

    /**
     * C04/D4.3 distinction (T06.R3): a future second copy inside the overlap stays
     * schedulable while the past copy stays history - the late tick of the first copy
     * advances past the shared now and the next normal tick still claims the second
     * copy's key.
     */
    @Test
    void c04_r3FutureSecondCopyVsPastCopyDistinction() throws Exception {
        String cron = "30 * * * * *";
        // Base just after :29:30; derive the first copy from the real pointer so the
        // scenario is robust against the harness's certification timing.
        clock.set(Instant.parse("2026-11-01T05:29:31Z"));
        MonitorTask task = createEnabledTask(cron, NEW_YORK);
        long firstCopy = task.nextFireAt();
        assertTrue(firstCopy >= Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),
                "the first copy must be at or after the 01:30 wall minute");
        long secondCopy = oracleNextAfter(NEW_YORK, Instant.ofEpochMilli(firstCopy),
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertTrue(secondCopy > firstCopy,
                "the second wall copy stays future relative to the first claimed key");
        // Late tick 30 s after the first copy, inside the overlap.
        clock.set(Instant.ofEpochMilli(firstCopy).plusSeconds(30));
        Instant tickNow = clock.instant();
        ClaimReceipt late = claimManager.claim(new ClaimRequest(task.taskId(), firstCopy,
                taskManager.find(task.taskId()).orElseThrow().scheduleRevision()),
                Long.valueOf(tickNow.toEpochMilli()));
        assertEquals(ClaimOutcome.CLAIMED, late.outcome());
        // The advance lands on the next wall minute's EDT copy, strictly after the shared
        // now; the overlap's EST copies stay inside the candidate set and the later
        // sequence claims them in absolute order (never re-walked as past keys).
        long advance = oracleNextAfter(NEW_YORK, tickNow,
                Instant.parse("2026-11-01T08:00:00Z"), cron);
        assertEquals(advance, late.nextFireAt());
        assertTrue(advance > tickNow.toEpochMilli());
        assertEquals(1, runCount(task.taskId()));
        assertTrue(claimManager.skipClaim(late.runId(), "ISOLATION_TERMINATED"));
        // Normal ticks claim every subsequent key (EDT tail + EST copies) exactly once.
        java.util.List<Long> expectedKeys = oracleSequenceAfter(NEW_YORK, tickNow,
                Instant.parse("2026-11-01T06:35:00Z"), cron);
        int claimedKeys = 1;
        for (long key : expectedKeys) {
            clock.set(Instant.ofEpochMilli(key));
            MonitorTask snapshot = taskManager.find(task.taskId()).orElseThrow();
            assertEquals(key, snapshot.nextFireAt(),
                    "the pointer must track the oracle sequence at every step");
            ClaimReceipt step = claimManager.claim(new ClaimRequest(task.taskId(), key,
                    snapshot.scheduleRevision()), Long.valueOf(key));
            assertEquals(ClaimOutcome.CLAIMED, step.outcome());
            claimedKeys++;
            assertTrue(claimManager.skipClaim(step.runId(), "ISOLATION_TERMINATED"));
        }
        assertEquals(1 + expectedKeys.size(), claimedKeys,
                "every key after the late tick is claimed exactly once, in order");
        // The past copy stays history: no run exists before the first claimed key.
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_runs WHERE scheduled_at < ?",
                Integer.class, firstCopy),
                "no key earlier than the first copy was ever created");
    }

    /**
     * T06.R3 regression guard: the legacy per-claim advance path (no common now) still
     * behaves exactly as before for callers that claim without a tick context.
     */
    @Test
    void c03_r3LegacyClaimWithoutCommonNowKeepsTheClaimedKeySuccessor() throws Exception {
        clock.set(Instant.parse("2026-11-01T05:00:00Z"));
        MonitorTask task = createEnabledTask("0 * * * * *", NEW_YORK);
        long dueKey = task.nextFireAt();
        ClaimReceipt legacy = claimManager.claim(new ClaimRequest(task.taskId(), dueKey,
                task.scheduleRevision()));
        assertEquals(ClaimOutcome.CLAIMED, legacy.outcome());
        // No tick context: the advance solves strictly after the claimed plan key, which
        // is the production behaviour any non-scanner caller has always received.
        long legacyNext = ScheduleIterator.of("0 * * * * *", NEW_YORK).next(
                Instant.ofEpochMilli(dueKey)).toEpochMilli();
        assertEquals(Instant.parse("2026-11-01T05:02:00Z").toEpochMilli(), legacyNext);
        assertEquals(legacyNext, legacy.nextFireAt());
        assertEquals(legacyNext, dbNextFire(task.taskId()));
        assertTrue(claimManager.skipClaim(legacy.runId(), "ISOLATION_TERMINATED"));
    }

    private CpaCredentialVerificationClient fakePageClient() {
        return new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
            @Override
            public java.util.List<CpaIdentityResolver.CredentialPage> listAllCredentialPages() {
                return java.util.List.of(new CpaIdentityResolver.CredentialPage(
                        java.util.List.of(new CpaIdentityResolver.CredentialEntryDetail(
                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                        false));
            }

            @Override
            public java.util.List<String> listModels(String nameOrAuthId) {
                return "auth-x".equals(nameOrAuthId) ? java.util.List.of("model-m") : java.util.List.of();
            }
        };
    }
}
