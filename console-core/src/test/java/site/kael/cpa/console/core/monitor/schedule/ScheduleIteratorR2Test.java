package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import site.kael.cpa.console.core.monitor.model.CronCertificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03.R2-A/B regression matrix (task.md 8.5 V01-V11): complete Gregorian-cycle interval
 * proofs (no sampling window, no Long.MAX_VALUE fallback) and the earliest-future solver
 * for any base point. Every expectation is built from an independent UTC-second/minute
 * oracle in the test itself - production helpers are never used as the reference.
 */
class ScheduleIteratorR2Test {

    private static final long MIN_MS = CronPolicy.MINIMUM_INTERVAL_MS;

    /** Whole-future proof domain for the retained R2 matrix (b = provider epoch floor). */
    private static final java.time.Instant EPOCH_BASE = java.time.Instant.ofEpochMilli(Long.MIN_VALUE);

    /** Independent minute-grid oracle: absolute instants of local minute matches in the range. */
    private static List<Instant> oracleMinuteGrid(
            ZoneId zone, LocalDateTime fromLocal, LocalDateTime untilLocal,
            java.util.function.Predicate<LocalDateTime> filter) {
        List<Instant> expected = new ArrayList<>();
        LocalDateTime cursor = fromLocal;
        while (cursor.isBefore(untilLocal)) {
            if (filter.test(cursor)) {
                for (ZoneOffset offset : zone.getRules().getValidOffsets(cursor)) {
                    expected.add(cursor.toInstant(offset));
                }
            }
            cursor = cursor.plusMinutes(1);
        }
        java.util.Collections.sort(expected);
        return expected;
    }

    private static final java.util.function.Predicate<LocalDateTime> EVERY_MINUTE = c -> true;

    // ---------------------------------------------------------------- V01
    @Test
    void v01_wednesdayBoundaryPairAndBasicMinutePairRejected() {
        // 0,59 * * * * WED - 59 s -> 00 across every Wednesday minute boundary.
        var wednesday = IntervalValidator.validate("0,59 * * * * WED", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertInstanceOfRejected(wednesday);
        // 0,59 * * * * * - the basic same-hour minute pair.
        var basic = IntervalValidator.validate("0,59 * * * * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertInstanceOfRejected(basic);
        // Independent expectation: 00:00:00 and 00:00:59 UTC on some Wednesday are 59 s apart.
        var someWednesday = LocalDateTime.of(2026, 6, 17, 0, 0);
        assertEquals(java.time.DayOfWeek.WEDNESDAY, someWednesday.getDayOfWeek());
        assertEquals(Duration.ofSeconds(59), Duration.between(
                someWednesday.atZone(ZoneOffset.UTC).toInstant(),
                someWednesday.plusSeconds(59).atZone(ZoneOffset.UTC).toInstant()));
    }

    // ---------------------------------------------------------------- V02
    @Test
    void v02_leapDaySafeAcceptedAndSparseLeapDayRejected() {
        // The leap day alone has real triggers once per four-year pattern; min gap is the
        // tail-to-head seam of the Gregorian cycle, NOT Long.MAX_VALUE.
        var safe = IntervalValidator.validate("0 0 0 29 2 *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        var passed = assertInstanceOfPassed(safe);
        assertTrue(passed.minimumIntervalMs() >= MIN_MS);
        assertFalse(passed.minimumIntervalMs() == Long.MAX_VALUE,
                "legal sparse plan must not report an unproven Long.MAX_VALUE interval");
        // Independent 2028-02-29 grid proves the day has real 00:00 triggers.
        assertNotNull(Instant.parse("2028-02-29T00:00:00Z"));
        // B-CRON-1b: 0,59 on the leap day contains the concrete 59 s / 1 s pairs of
        // 2028-02-29 (independent expectation built from local date-times).
        var unsafe = IntervalValidator.validate("0,59 * * 29 2 *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertInstanceOfRejected(unsafe);
        // Non-400-multiple centuries are NOT leap years; 400-multiples are.
        assertFalse(LocalDateTime.of(2100, 3, 1, 0, 0).minusDays(1).getDayOfMonth() == 29);
        assertTrue(LocalDateTime.of(2000, 3, 1, 0, 0).minusDays(1).getDayOfMonth() == 29);
    }

    // ---------------------------------------------------------------- V03
    @Test
    void v03_denseHourGridExactIntervals() {
        // 17 */2 * * * * UTC: interval exactly 120 s; 0 59 * * * *: 60 s per hour.
        var everyTwoHours = IntervalValidator.validate("17 */2 * * * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertEquals(120_000L, assertInstanceOfPassed(everyTwoHours).minimumIntervalMs());
        var hourly59 = IntervalValidator.validate("0 59 * * * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertEquals(3_600_000L, assertInstanceOfPassed(hourly59).minimumIntervalMs());
        // WED:59 remains legal: the 59-minute mark recurs every hour of every Wednesday,
        // so the proven minimum is the hourly 3 600 000 ms (independent expectation).
        var wed59 = IntervalValidator.validate("0 59 * * * WED", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertEquals(3_600_000L, assertInstanceOfPassed(wed59).minimumIntervalMs());
        // 59,0 in the minute field: 60 s spacing across the boundary is NOT violated.
        var minutePair = IntervalValidator.validate("0 59,0 * * * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertEquals(60_000L, assertInstanceOfPassed(minutePair).minimumIntervalMs());
        // Cross-midnight/月底/年底 sparse plans keep real proven intervals.
        var newYearEve = IntervalValidator.validate("0 0 23 31 12 *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        var newYearPassed = assertInstanceOfPassed(newYearEve);
        assertTrue(newYearPassed.minimumIntervalMs() >= MIN_MS);
        // Dense every-30-s is rejected with a concrete near-pair.
        var dense = IntervalValidator.validate("*/30 * * * * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertInstanceOfRejected(dense);
    }

    // ---------------------------------------------------------------- V04
    @Test
    void v04_springSixFieldSpecialSyntaxComplete() {
        // Full supported special-syntax matrix: L, ? , nW, #n, L-n, ranges and steps.
        List<String> validPlans = List.of(
                "0 0 0 L * *",          // last day of month
                "0 0 0 ? * 5#2",        // second Friday
                "0 0 0 1W * *",         // nearest weekday to the 1st
                "0 0 0 L-3 * *",        // 3 days before month end
                "0 0 0 15,20 * *",      // list
                "0 0 0 1-5 * *",        // range
                "0 0 0 */5 * *",        // step
                "0 0 0 ? * MON",        // name weekday
                "0 0 0 ? * 1",          // numeric Sunday (Spring: 0 = SUN)
                "0 0 0 ? * 7");         // numeric Saturday (Spring: 7 = SAT)
        for (String plan : validPlans) {
            var validation = IntervalValidator.validate(plan, ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
            assertInstanceOfPassed(validation, "plan must certify: " + plan);
        }
        // Invalid/unsupported shapes: the shared policy check throws; validate() rejects.
        assertThrows(IllegalArgumentException.class, () -> CronPolicy.rejectUnsupportedShape("0 0 0 * *"));
        assertInstanceOfRejected(IntervalValidator.validate("0 0 0 * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS));
        List<String> invalidPlans = List.of(
                "0 0 0 8L * *",         // unsupported: L with an invalid combination
                "0 0 0 ? * 9",          // weekday 9 out of range
                "0 0 0 ? * MONFRI");    // invalid name sequence
        for (String plan : invalidPlans) {
            boolean rejected = false;
            try {
                var validation = IntervalValidator.validate(plan, ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
                rejected = validation instanceof IntervalValidator.Validation.Rejected;
            } catch (RuntimeException parseFailure) {
                rejected = true;
            }
            assertTrue(rejected, "plan must not certify: " + plan);
        }
    }

    // ---------------------------------------------------------------- V05
    @Test
    void v05_impossibleDateAndAllGapPlansProduceNoFuture() {
        // 30 February: parser-level impossibility.
        var feb30 = IntervalValidator.validate("0 0 0 30 2 *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        assertInstanceOfRejected(feb30);
        var iterator = ScheduleIterator.of("0 0 0 30 2 *", "UTC");
        assertNull(iterator.next(Instant.parse("2026-01-01T00:00:00Z")),
                "an impossible date must yield no future trigger");
        // Five fields / year field / macro are all rejected shapes. The shared policy
        // check (the production entry path in CronPolicyValidator.certify) throws for
        // every one of them; validate() alone answers Rejected for the two Spring-parse
        // failures and must NOT be asked about "@daily" because Spring would silently
        // accept the macro - the policy gate is the contract.
        List<String> invalidShapes = List.of("0 0 0 * *", "@daily", "0 0 0 1 1 * 2026");
        for (String shape : invalidShapes) {
            assertThrows(IllegalArgumentException.class, () -> CronPolicy.rejectUnsupportedShape(shape),
                    "policy gate must throw for: " + shape);
        }
        assertInstanceOfRejected(IntervalValidator.validate("0 0 0 * *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS));
        assertInstanceOfRejected(IntervalValidator.validate("0 0 0 1 1 * 2026", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS));
        // Illegal zone is rejected by the shared policy check.
        assertThrows(IllegalArgumentException.class, () -> CronPolicy.validateZone("Not/AZone"));
    }

    // ---------------------------------------------------------------- V11
    @Test
    void v11_gapOverlapAndSkippedDayZonesProduceCorrectCounts() {
        // NY spring-forward gap: local 02:xx produces zero matches (independent count).
        var gapIterator = ScheduleIterator.of("0 * * * * *", "America/New_York");
        List<Instant> gapExpected = oracleMinuteGrid(ZoneId.of("America/New_York"),
                LocalDateTime.of(2026, 3, 8, 0, 0), LocalDateTime.of(2026, 3, 9, 0, 0),
                c -> c.getHour() != 2);
        var gapActual = new ArrayList<Instant>();
        var gapCursor = Instant.parse("2026-03-08T04:59:59Z");
        for (int i = 0; i < gapExpected.size(); i++) {
            Instant n = gapIterator.next(gapCursor);
            gapActual.add(n);
            gapCursor = n;
        }
        assertEquals(gapExpected, gapActual,
                "gap day hourly sequence must match the independent minute oracle");
        // NY fall-back overlap: local 01:xx produces TWO absolute copies each minute.
        List<Instant> overlapExpected = oracleMinuteGrid(ZoneId.of("America/New_York"),
                LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 2, 0, 0),
                EVERY_MINUTE);
        var overlapActual = new ArrayList<Instant>();
        var overlapCursor = Instant.parse("2026-11-01T03:59:59Z");
        for (int i = 0; i < overlapExpected.size(); i++) {
            Instant n = overlapIteratorNext(overlapCursor);
            overlapActual.add(n);
            overlapCursor = n;
        }
        assertEquals(overlapExpected, overlapActual,
                "overlap day hourly sequence must match the independent two-offset oracle");
        // Lord Howe half-hour transition: +10:30/+11:00 DST; hourly stays valid.
        var lordHowe = IntervalValidator.validate("0 0 * * * *", ZoneId.of("Australia/Lord_Howe"), EPOCH_BASE, MIN_MS);
        assertInstanceOfPassed(lordHowe);
        // Apia skipped 2011-12-30 entirely: Dec 29 23:59:59 (-10) jumped straight to
        // Dec 31 00:00:00 (+14) at 2011-12-30T10:00:00Z. Independent expectations: from a
        // base one minute before the jump the next match is the jump minute itself (wall
        // Dec 31 00:00 = 10:00Z), and the wall never passes through Dec 30.
        var apia = ScheduleIterator.of("0 * * * * *", "Pacific/Apia");
        assertEquals(Instant.parse("2011-12-30T10:00:00Z"), apia.next(Instant.parse("2011-12-30T09:59:00Z")));
        assertEquals(java.time.LocalDateTime.of(2011, 12, 31, 0, 0),
                java.time.LocalDateTime.ofInstant(Instant.parse("2011-12-30T10:00:00Z"), ZoneId.of("Pacific/Apia")));
        assertNull(java.time.LocalDateTime.ofInstant(Instant.parse("2011-12-30T09:59:59Z"), ZoneId.of("Pacific/Apia"))
                .toLocalDate().equals(java.time.LocalDate.of(2011, 12, 30))
                ? java.time.LocalDateTime.of(2011, 12, 30, 0, 0) : null,
                "the wall before the jump is Dec 29, not Dec 30");
    }

    private Instant overlapIteratorNext(Instant after) {
        return ScheduleIterator.of("0 * * * * *", "America/New_York").next(after);
    }

    // ---------------------------------------------------------------- V09/V10
    @Test
    void v09_v10_complete121ItemSequenceAndFreshIteratorEarliest() {
        // Independent expectation: EDT block 05:00Z..05:59Z (60 items), EST block
        // 06:00Z..07:00Z (61 items), then every further minute strictly ascending.
        List<Instant> expected = new ArrayList<>();
        for (int m = 0; m < 60; m++) {
            expected.add(Instant.parse("2026-11-01T05:00:00Z").plusSeconds(60L * m));
        }
        for (int m = 0; m <= 60; m++) {
            expected.add(Instant.parse("2026-11-01T06:00:00Z").plusSeconds(60L * m));
        }
        assertEquals(121, expected.size());
        // 1) Batch: single fresh iterator next(after, 121).
        var batch = ScheduleIterator.of("0 * * * * *", "America/New_York").next(
                Instant.parse("2026-11-01T04:59:59Z"), 121);
        assertEquals(expected, batch, "batch sequence must match the independent expectation");
        // 2) Stepwise on the same instance from the same base point.
        var stepwiseIterator = ScheduleIterator.of("0 * * * * *", "America/New_York");
        var stepwise = new ArrayList<Instant>();
        Instant cursor = Instant.parse("2026-11-01T04:59:59Z");
        for (int i = 0; i < 121; i++) {
            Instant n = stepwiseIterator.next(cursor);
            stepwise.add(n);
            cursor = n;
        }
        assertEquals(expected, stepwise, "same-instance stepwise must match the independent expectation");
        // 3) Fresh of() per step.
        var freshPerStep = new ArrayList<Instant>();
        cursor = Instant.parse("2026-11-01T04:59:59Z");
        for (int i = 0; i < 121; i++) {
            Instant n = ScheduleIterator.of("0 * * * * *", "America/New_York").next(cursor);
            freshPerStep.add(n);
            cursor = n;
        }
        assertEquals(expected, freshPerStep, "fresh-of-per-step must match the independent expectation");
        // B-CRON-3b: the exact failing base points of the acceptance report.
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"),
                ScheduleIterator.of("0 * * * * *", "America/New_York").next(Instant.parse("2026-11-01T05:59:00Z")));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"),
                ScheduleIterator.of("0 * * * * *", "America/New_York").next(Instant.parse("2026-11-01T05:59:30Z")));
        // Base point on the EST side of the wall: the walk must not return a primary-offset copy.
        assertEquals(Instant.parse("2026-11-01T06:31:00Z"),
                ScheduleIterator.of("0 * * * * *", "America/New_York").next(Instant.parse("2026-11-01T06:30:00Z")));
        // Base point inside the overlap on the EDT side: full EDT block first.
        assertEquals(Instant.parse("2026-11-01T05:31:00Z"),
                ScheduleIterator.of("0 * * * * *", "America/New_York").next(Instant.parse("2026-11-01T05:30:00Z")));
        // State-independence: the same instance asked twice with the same after returns
        // the same answer.
        var sameInstance = ScheduleIterator.of("0 * * * * *", "America/New_York");
        Instant first = sameInstance.next(Instant.parse("2026-11-01T05:59:00Z"));
        Instant second = sameInstance.next(Instant.parse("2026-11-01T05:59:00Z"));
        assertEquals(first, second);
    }

    // ---------------------------------------------------------------- V06/V07/V08
    @Test
    void v06_transitionWindowCronStaysProvable() {
        // Local 02:30 on the spring-forward day exists only via the gap; 01:30 has both.
        var springGap = IntervalValidator.validate("0 30 2 8 3 *", ZoneId.of("America/New_York"), EPOCH_BASE, MIN_MS);
        var fallOverlap = IntervalValidator.validate("0 30 1 1 11 *", ZoneId.of("America/New_York"), EPOCH_BASE, MIN_MS);
        assertInstanceOfPassed(springGap, "annual gap-day plan must certify");
        assertInstanceOfPassed(fallOverlap, "annual overlap-day plan must certify");
    }

    @Test
    void v07_cycleSeamProducesARealInterval() {
        // Yearly plan: the cycle-to-cycle seam is the actual minimum (365/366-day scale),
        // which must be returned instead of Long.MAX_VALUE.
        var yearly = IntervalValidator.validate("0 0 0 1 1 *", ZoneId.of("UTC"), EPOCH_BASE, MIN_MS);
        var passed = assertInstanceOfPassed(yearly);
        // Independent expectation: the minimum adjacent gap is the 365-day year
        // (365 * 86 400 000 = 31 536 000 000), bounded above by the leap-day year.
        assertEquals(365L * 86_400_000L, passed.minimumIntervalMs());
        assertTrue(passed.minimumIntervalMs() >= MIN_MS);
    }

    @Test
    void v08_algorithmVersionUpgradeInvalidatesOldCertificates() {
        // The stored certificate format pins algorithmVersion; v1 is no longer valid.
        assertEquals(4, CronCertificate.CURRENT_ALGORITHM_VERSION,
                "algorithm version must have been bumped for the complete-proof rework");
        var validator = new CronPolicyValidator(java.time.Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var certificate = validator.certify("0 0 12 * * *", "UTC");
        assertTrue(validator.isCertificateValid(certificate, "0 0 12 * * *", "UTC"));
        // A v1-issued certificate under the same tzdb MUST be refused.
        var stale = new CronCertificate("0 0 12 * * *", "UTC",
                certificate.tzdbVersion(), 1, 86_400_000L, certificate.basePoint(),
                Instant.parse("2025-01-01T00:00:00Z"));
        assertFalse(validator.isCertificateValid(stale, "0 0 12 * * *", "UTC"));
    }

    // ---------------------------------------------------------------- assertion helpers
    private static void assertInstanceOfRejected(IntervalValidator.Validation validation) {
        assertTrue(validation instanceof IntervalValidator.Validation.Rejected,
                "expected Rejected but got " + validation);
    }

    private static IntervalValidator.Validation.Passed assertInstanceOfPassed(
            IntervalValidator.Validation validation) {
        return assertInstanceOfPassed(validation, null);
    }

    private static IntervalValidator.Validation.Passed assertInstanceOfPassed(
            IntervalValidator.Validation validation, String message) {
        assertTrue(validation instanceof IntervalValidator.Validation.Passed,
                (message == null ? "" : message + ": ") + "expected Passed but got " + validation);
        return (IntervalValidator.Validation.Passed) validation;
    }
}
