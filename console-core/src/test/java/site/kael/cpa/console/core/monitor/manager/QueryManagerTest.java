package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.dao.CredentialGuardDao;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport;
import site.kael.cpa.console.core.monitor.dao.MonitorIsolationTestSupport.MutableClock;
import site.kael.cpa.console.core.monitor.dao.ObservationGapDao;
import site.kael.cpa.console.core.monitor.dao.QueryDao;
import site.kael.cpa.console.core.monitor.dao.RetentionDao;
import site.kael.cpa.console.core.monitor.dao.RunDao;
import site.kael.cpa.console.core.monitor.manager.QueryManager.EventPage;
import site.kael.cpa.console.core.monitor.manager.QueryManager.EventItem;
import site.kael.cpa.console.core.monitor.manager.QueryManager.SnapshotChangedException;
import site.kael.cpa.console.core.monitor.manager.QueryManager.TaskCard;
import site.kael.cpa.console.core.monitor.manager.QueryManager.VerificationSummary;
import site.kael.cpa.console.core.monitor.manager.QueryManager.Coverage;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimOutcome;
import site.kael.cpa.console.core.monitor.manager.RunClaimManager.ClaimReceipt;
import site.kael.cpa.console.core.monitor.manager.TaskManager.CreateRequest;
import site.kael.cpa.console.core.monitor.manager.TaskManager.UpdateRequest;
import site.kael.cpa.console.core.monitor.model.MonitorTask;
import site.kael.cpa.console.core.monitor.model.QueryWindow;
import site.kael.cpa.console.core.monitor.model.SignedCursor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T09 isolation self-check (D6 / D9): the strict verification sample (8S/1F/1U + 2 skips
 * = 80%), zero-denominator null, start==end rejection, NOT_SENT UNKNOWN outside the sample,
 * the COALESCE event time with the sample flag, the separated latest verification and
 * latest scheduled event, partial and complete out-of-range coverage with the retention
 * exception, keyset pagination across same-millisecond runs, tampered-cursor rejection and
 * the 409 SNAPSHOT_CHANGED contract when the data revision moves between pages.
 */
class QueryManagerTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyCrypto crypto;
    private TaskManager taskManager;
    private RunClaimManager claimManager;
    private RunDao runDao;
    private RetentionDao retentionDao;
    private ObservationGapDao gapDao;
    private QueryDao queryDao;
    private QueryManager queryManager;
    private MonitorTask task;
    private long baseTime;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = MonitorIsolationTestSupport.freshDataSource();
        MonitorIsolationTestSupport.migrate(dataSource);
        jdbc = MonitorIsolationTestSupport.jdbc(dataSource);
        clock = new MutableClock(MonitorIsolationTestSupport.BASE_TIME);
        crypto = MonitorIsolationTestSupport.crypto();
        CpaIdentityResolver identityResolver = new CpaIdentityResolver(fakePageClient(), crypto, clock);
        taskManager = new TaskManager(dataSource, crypto, clock,
                MonitorIsolationTestSupport.cronValidator(clock), identityResolver::verifyBoundTarget,
                new MonitorConfigLockManager(
                        new CpaCredentialVerificationClient(
                                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), ""),
                        "cfg"));
        runDao = new RunDao(dataSource);
        CredentialGuardDao guardDao = new CredentialGuardDao(dataSource);
        claimManager = new RunClaimManager(dataSource, crypto, clock, taskManager, runDao, guardDao,
                MonitorIsolationTestSupport.txManager(dataSource));
        retentionDao = new RetentionDao(dataSource);
        gapDao = new ObservationGapDao(dataSource);
        queryDao = new QueryDao(dataSource);
        queryManager = new QueryManager(dataSource, queryDao, retentionDao, gapDao, taskManager, runDao,
                new SignedCursor("isolation-signing-secret"));
        task = taskManager.create(new CreateRequest("t-query", MonitorIsolationTestSupport.CRON,
                MonitorIsolationTestSupport.ZONE, "inst-1", "auth-x", "idx-y", "model-m", "model-m", "codex"));
        task = taskManager.update(task.taskId(), new UpdateRequest(task.rowVersion(), null, null, null, true));
        baseTime = MonitorIsolationTestSupport.BASE_TIME.toEpochMilli();
        // 004 stores collection_started_at from the first retention write; T09 coverage
        // treats anything before it as "not collected".
        retentionDao.ensureAndReadMeta(baseTime - 86_400_000L);
    }

    private CpaCredentialVerificationClient fakePageClient() {
        return new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:1", "cfg"), "unused") {
            @Override
            public java.util.List<site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage>
                    listAllCredentialPages() {
                return java.util.List.of(
                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage(
                                java.util.List.of(
                                        new site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail(
                                                "auth-x", "idx-y", "acct.json", "codex", "codex", false, false, false, null, "file")),
                                false));
            }

            @Override
            public java.util.List<String> listModels(String nameOrAuthId) {
                return "auth-x".equals(nameOrAuthId) ? java.util.List.of("model-m") : java.util.List.of();
            }
        };
    }

    private void insertRun(String runId, String state, String dispatch,
                           Long startedAt, long claimedAt, long scheduledAt, String reason) {
        jdbc.update("""
                INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision, task_safe_name,
                    task_model_display, cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot,
                    phase, state, dispatch_status, owns_task_slot, claimed_at, started_at, finished_at,
                    reason_code)
                VALUES (?, ?, ?, 1, 'name', 'model', 'inst', 'c1', 'c2', 'prov', 'TERMINAL', ?, ?, 0, ?, ?, ?, ?)
                """, ps -> {
            ps.setString(1, runId);
            ps.setString(2, task.taskId());
            ps.setLong(3, scheduledAt);
            ps.setString(4, state);
            ps.setString(5, dispatch);
            ps.setLong(6, claimedAt);
            if (startedAt == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, startedAt);
            }
            ps.setLong(8, (startedAt != null ? startedAt : claimedAt) + 1_000);
            ps.setString(9, reason);
        });
    }

    /** D6.1 verification sample: 8 SUCCESS + 1 FAILED + 1 UNKNOWN MAY_HAVE_SENT = 80%. */
    private void insertSampleSet() {
        int seq = 0;
        for (int i = 0; i < 8; i++) {
            insertRun("s-" + i, "SUCCESS", "MAY_HAVE_SENT",
                    baseTime - 3_000_000L + seq, baseTime - 3_000_000L + seq - 500,
                    baseTime - 3_000_000L + seq, "VERIFIED");
            seq += 1_000;
        }
        insertRun("f-1", "FAILED", "MAY_HAVE_SENT",
                baseTime - 3_000_000L + seq, baseTime - 3_000_000L + seq - 500,
                baseTime - 3_000_000L + seq, "TARGET_REJECTED");
        seq += 1_000;
        insertRun("u-1", "UNKNOWN", "MAY_HAVE_SENT",
                baseTime - 3_000_000L + seq, baseTime - 3_000_000L + seq - 500,
                baseTime - 3_000_000L + seq, "TRANSPORT");
        seq += 1_000;
        // NOT_SENT UNKNOWN: never entered the transport, stays outside the denominator.
        insertRun("u-notsent", "UNKNOWN", "NOT_SENT",
                null, baseTime - 3_000_000L + seq - 500, baseTime - 3_000_000L + seq,
                "PROCESS_INTERRUPTED");
        seq += 1_000;
        // Skips claim AFTER the last verification so the two "latest" views differ.
        insertRun("skip-1", "SKIPPED", "NOT_SENT",
                null, baseTime - 2_000_000L, baseTime - 2_000_000L, "CREDENTIAL_BUSY");
        insertRun("skip-2", "SKIPPED", "NOT_SENT",
                null, baseTime - 1_000_000L, baseTime - 1_000_000L, "TASK_SLOT_BUSY");
    }

    @Test
    void verificationSampleIsEightSuccessOneFailedOneUnknownFor80Percent() throws Exception {
        insertSampleSet();
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 4_000_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        List<TaskCard> cards = queryManager.taskCards(window, baseTime);
        assertEquals(1, cards.size());
        VerificationSummary summary = cards.get(0).verification();
        assertEquals(8, summary.successCount());
        assertEquals(1, summary.failedCount());
        assertEquals(1, summary.unknownCount());
        assertEquals(10, summary.sampleCount());
        assertEquals(new BigDecimal("80.00"), summary.ratio(), "8/10 = 80%");
    }

    @Test
    void zeroDenominatorRatioIsNullAndNotSentUnknownIsOutsideTheSample() throws Exception {
        insertRun("only-skip", "SKIPPED", "NOT_SENT", null, baseTime - 1000, baseTime - 1000, "TASK_SLOT_BUSY");
        insertRun("u-notsent", "UNKNOWN", "NOT_SENT", null, baseTime - 900, baseTime - 900, "PROCESS_INTERRUPTED");
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 100_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        VerificationSummary summary = queryManager.taskCards(window, baseTime).get(0).verification();
        assertEquals(0, summary.sampleCount());
        assertNull(summary.ratio(), "zero denominator is null, never 0%");
    }

    @Test
    void startEqualsEndIsRejectedAndPairedBoundsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 1000).toString(),
                Instant.ofEpochMilli(baseTime - 1000).toString()));
        assertThrows(IllegalArgumentException.class, () -> QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime).toString(),
                Instant.ofEpochMilli(baseTime - 1000).toString()));
        assertThrows(IllegalArgumentException.class, () -> QueryWindow.ofCustom("not-iso", "also-not-iso"));
    }

    @Test
    void eventsUseCoalesceTimeAndFlagSampleMembership() throws Exception {
        insertSampleSet();
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 4_000_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage page = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, 100, baseTime);
        long sampleEvents = page.items().stream().filter(EventItem::includedInSample).count();
        assertEquals(10, sampleEvents, "only S/F/U MAY_HAVE_SENT belong to the sample");
        assertTrue(page.items().stream().anyMatch(item -> "SKIPPED".equals(item.state())
                        && !item.includedInSample()),
                "skips are visible in the events view outside the sample");
        // The NOT_SENT UNKNOWN row must surface with claimed_at (not started, not finished)
        // as its event time, while sample rows use started_at.
        EventItem notSent = page.items().stream()
                .filter(item -> "u-notsent".equals(item.runId())).findFirst().orElseThrow();
        assertFalse(notSent.includedInSample());
        assertNull(notSent.startedAt());
        EventItem firstSample = page.items().stream()
                .filter(EventItem::includedInSample).findFirst().orElseThrow();
        assertEquals(firstSample.startedAt(), firstSample.eventAt(),
                "sample rows carry started_at as their event time");
        assertTrue(notSent.eventAt() != firstSample.eventAt(),
                "notSent=" + notSent.eventAt() + " firstSample=" + firstSample.eventAt()
                        + " firstSampleRun=" + firstSample.runId());
    }

    @Test
    void latestVerificationAndLatestScheduledEventAreSeparated() throws Exception {
        insertSampleSet();
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 4_000_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        TaskCard card = queryManager.taskCards(window, baseTime).get(0);
        assertNotNull(card.latestVerification());
        assertEquals("u-1", card.latestVerification().runId(),
                "latest real verification is the newest S/F/U, not a skip");
        assertNotNull(card.latestEvent());
        assertEquals("skip-2", card.latestEvent().runId(),
                "latest scheduled event is the newest event, not the newest verification");
    }

    @Test
    void partialOutOfRangeCoverageKeepsRetainedExceptionsAndIsIncomplete() throws Exception {
        insertSampleSet();
        // Insert one retained sample inside the loss interval: the long-running exception.
        insertRun("exception", "SUCCESS", "MAY_HAVE_SENT",
                baseTime - 40L * 86_400_000L, baseTime - 40L * 86_400_000L,
                baseTime - 40L * 86_400_000L, "VERIFIED");
        retentionDao.recordLosses(List.of(
                new site.kael.cpa.console.core.monitor.model.RetentionLoss(
                        baseTime - 40L * 86_400_000L, baseTime - 39L * 86_400_000L, false)),
                1);
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 41L * 86_400_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        List<TaskCard> cards = queryManager.taskCards(window, baseTime);
        Coverage coverage = cards.get(0).coverage();
        assertFalse(coverage.complete(), "a loss interval makes the window incomplete");
        assertEquals(1, coverage.lossIntervals().size());
        assertTrue(coverage.retainedSampleIntervals().size() >= 2,
                "the retained exception sample is reported as its own interval group");
        assertEquals(11, cards.get(0).verification().sampleCount(),
                "statistics count actual retained records, not the coverage metadata");
    }

    @Test
    void fullyOutOfRangeWindowReportsNoRetainedData() throws Exception {
        // One old sample deep outside collection+retention coverage, then request a window
        // before collection_started_at: no rows, no proven coverage → no retained data.
        insertRun("old", "SUCCESS", "MAY_HAVE_SENT",
                baseTime - 40L * 86_400_000L, baseTime - 40L * 86_400_000L,
                baseTime - 40L * 86_400_000L, "VERIFIED");
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 45L * 86_400_000L).toString(),
                Instant.ofEpochMilli(baseTime - 44L * 86_400_000L).toString());
        assertTrue(queryManager.noRetainedData(task.taskId(), window),
                "a pre-collection window with no retained rows is no-retained-data, not 0%");
        // The complete-window-with-zero-samples case stays distinguishable.
        QueryWindow recentEmpty = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 1_000_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        Coverage recent = queryManager.coverage(task.taskId(), recentEmpty, baseTime);
        assertTrue(recent.complete());
        assertTrue(queryManager.noRetainedData(task.taskId(), recentEmpty) == false,
                "a complete window with zero samples is 'no verification', not 'no data'");
    }

    @Test
    void completeWindowWithZeroSamplesIsNotNoRetainedData() throws Exception {
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 1000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        Coverage coverage = queryManager.coverage(task.taskId(), window, baseTime);
        assertTrue(coverage.complete());
        VerificationSummary summary = queryManager.taskCards(window, baseTime).get(0).verification();
        assertEquals(0, summary.sampleCount());
        assertFalse(queryManager.noRetainedData(task.taskId(), window),
                "a complete window with zero samples is 'no verification', not 'no retained data'");
    }

    @Test
    void observationGapIsReportedInsideTheCoverage() throws Exception {
        gapDao.insertGap(baseTime - 5_000, baseTime - 4_000);
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 10_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        Coverage coverage = queryManager.coverage(task.taskId(), window, baseTime);
        assertFalse(coverage.complete());
        assertEquals(1, coverage.observationGaps().size());
        assertEquals(baseTime - 5_000, coverage.observationGaps().get(0).startMs());
    }

    @Test
    void keysetPaginationIsStableAcrossSameMillisecondRuns() throws Exception {
        long shared = baseTime - 1_000;
        for (int i = 0; i < 5; i++) {
            insertRun("same-" + i, "SUCCESS", "MAY_HAVE_SENT", shared, shared - 1 - i,
                    shared - 10 - i, "VERIFIED");
        }
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(shared - 10_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage pageOne = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, 2, baseTime);
        assertEquals(2, pageOne.items().size());
        assertNotNull(pageOne.nextCursor());
        assertEquals(pageOne.items().get(0).eventAt(), pageOne.items().get(1).eventAt(),
                "same-millisecond rows share the sort key and break the tie by run_seq");
        EventPage pageTwo = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                pageOne.nextCursor(), 2, baseTime);
        assertEquals(2, pageTwo.items().size());
        assertNotEquals(pageOne.items().get(0).runId(), pageTwo.items().get(0).runId());
        org.junit.jupiter.api.Assertions.assertNotEquals(pageOne.items().get(1).runId(), pageTwo.items().get(0).runId(),
                "page two never repeats page one rows");
    }

    @Test
    void cursorKeepsAsOfAcrossRequestsWhenTransportClockAdvances() throws Exception {
        long shared = baseTime - 1_000;
        for (int i = 0; i < 3; i++) {
            insertRun("as-of-" + i, "SUCCESS", "MAY_HAVE_SENT", shared + i, shared + i - 1,
                    shared + i, "VERIFIED");
        }
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(shared - 10_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage pageOne = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, 1, baseTime);
        assertNotNull(pageOne.nextCursor());

        long laterAsOf = baseTime + 60_000;
        EventPage pageTwo = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                pageOne.nextCursor(), 1, laterAsOf);
        assertEquals(pageOne.asOfMs(), pageTwo.asOfMs());
        assertEquals(pageOne.asOfMs(), baseTime);
    }

    @Test
    void tamperedCursorIsRejectedWithoutSilentlyRestartingPageOne() {
        SignedCursor signer = new SignedCursor("isolation-signing-secret");
        assertThrows(IllegalArgumentException.class, () -> queryManager.eventsPage(
                task.taskId(),
                QueryWindow.ofCustom(Instant.ofEpochMilli(baseTime - 1000).toString(),
                        Instant.ofEpochMilli(baseTime).toString()),
                QueryManager.View.EVENTS,
                signer.encode(new SignedCursor.Payload(
                        task.taskId(), baseTime - 500, 1, 20, baseTime, baseTime - 1000, baseTime,
                        "EVENTS", 0, 0, "events"))
                        .substring(0, 20) + "tampered",
                20, baseTime));
    }

    @Test
    void crossQueryCursorIsRejectedAsSnapshotChanged() throws Exception {
        insertRun("cursor-seed", "SUCCESS", "MAY_HAVE_SENT",
                baseTime - 500, baseTime - 600, baseTime - 500, "VERIFIED");
        insertRun("cursor-seed-2", "SUCCESS", "MAY_HAVE_SENT",
                baseTime - 400, baseTime - 500, baseTime - 400, "VERIFIED");
        QueryWindow windowA = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 1000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage pageOne = queryManager.eventsPage(task.taskId(), windowA, QueryManager.View.EVENTS,
                null, 1, baseTime);
        org.junit.jupiter.api.Assertions.assertNotNull(pageOne.nextCursor(), "a page-one cursor exists");
        // A different window on the same token must be refused, not silently mixed.
        QueryWindow windowB = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 2000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        assertThrows(SnapshotChangedException.class, () -> queryManager.eventsPage(
                task.taskId(), windowB, QueryManager.View.EVENTS, pageOne.nextCursor(), 1, baseTime));
    }

    @Test
    void snapshotChangedWhenDataRevisionMovesBetweenPages() throws Exception {
        long shared = baseTime - 1_000;
        for (int i = 0; i < 3; i++) {
            insertRun("page-" + i, "SUCCESS", "MAY_HAVE_SENT", shared + i, shared + i - 1, shared + i, "VERIFIED");
        }
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(shared - 10_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage pageOne = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, 1, baseTime);
        assertNotNull(pageOne.nextCursor());
        // A write moves the shared revision between page requests.
        insertRun("new-run", "SUCCESS", "MAY_HAVE_SENT", baseTime - 500, baseTime - 600, baseTime - 500, "VERIFIED");
        jdbc.update("UPDATE monitor_data_revision SET revision = revision + 1 WHERE id = 1");
        assertThrows(SnapshotChangedException.class, () -> queryManager.eventsPage(
                task.taskId(), window, QueryManager.View.EVENTS, pageOne.nextCursor(), 1, baseTime),
                "a moved revision requires refresh, never a mixed page");
    }

    @Test
    void pageLimitsAreDefaultedAndCapped() throws Exception {
        long shared = baseTime - 1_000;
        for (int i = 0; i < 105; i++) {
            insertRun("many-" + i, "SUCCESS", "MAY_HAVE_SENT", shared + i, shared + i - 1, shared + i, "VERIFIED");
        }
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(shared - 10_000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage defaulted = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, null, baseTime);
        assertEquals(20, defaulted.items().size(), "default page size is 20");
        EventPage capped = queryManager.eventsPage(task.taskId(), window, QueryManager.View.EVENTS,
                null, 1000, baseTime);
        assertEquals(100, capped.items().size(), "the transport cap is 100");
    }

    @Test
    void verificationViewCountsMatchTheCardAggregates() throws Exception {
        insertSampleSet();
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 4_000_000L).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        EventPage page = queryManager.eventsPage(task.taskId(), window, QueryManager.View.VERIFICATION,
                null, 100, baseTime);
        long inSample = page.items().stream().filter(EventItem::includedInSample).count();
        assertEquals(10, inSample);
        TaskCard card = queryManager.taskCards(window, baseTime).get(0);
        assertEquals(10, card.verification().sampleCount(),
                "the events sample count and the card denominator agree");
    }

    @Test
    void disabledTasksRemainInTheCardList() throws Exception {
        taskManager.update(task.taskId(), new UpdateRequest(task.rowVersion(), null, null, null, false));
        QueryWindow window = QueryWindow.ofCustom(
                Instant.ofEpochMilli(baseTime - 1000).toString(),
                Instant.ofEpochMilli(baseTime).toString());
        List<TaskCard> cards = queryManager.taskCards(window, baseTime);
        assertEquals(1, cards.size());
        assertFalse(cards.get(0).enabled());
    }

}
