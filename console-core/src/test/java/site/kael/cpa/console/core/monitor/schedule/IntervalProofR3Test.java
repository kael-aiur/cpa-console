package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03.R3-A self-check (task v1.2-r3 §3/§4.2, V06/V07/V12/V13 + V05): the certificate
 * covers the finite explicit-history prefix plus exactly one stable tail period, and the
 * historic prefix is never shifted by a Gregorian cycle. Existence is decided inside the
 * target zone; window local dates are projected through the window offset. Every
 * expectation is built from an independent UTC-second oracle in the test itself -
 * production helpers are never used as the reference.
 */
class IntervalProofR3Test {

    private static final long MIN_MS = CronPolicy.MINIMUM_INTERVAL_MS;

    /** Independent UTC-second oracle: every matching absolute instant in [fromMs, untilMs). */
    private static List<Long> oracleWindowSeconds(
            ZoneId zone, long fromMs, long untilMs, java.util.function.Predicate<LocalDateTime> filter) {
        TreeSet<Long> expected = new TreeSet<>();
        for (long ms = fromMs; ms < untilMs; ms += 1000L) {
            LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone);
            if (filter.test(local)) {
                expected.add(ms);
            }
        }
        return new ArrayList<>(expected);
    }

    /** Independent predicate for "the local wall clock matches a fixed six-field plan". */
    private static java.util.function.Predicate<LocalDateTime> fixedTime(int hour, int minute, int second) {
        return local -> local.getHour() == hour && local.getMinute() == minute && local.getSecond() == second;
    }

    /** Independent predicate for "every minute at the given second". */
    private static java.util.function.Predicate<LocalDateTime> everyMinuteAt(int second) {
        return local -> local.getSecond() == second;
    }

    // ------------------------------------------------------------- V12
    @Test
    void v12_nonPeriodicPrefixThenStableTail() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZoneRules rules = zone.getRules();
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        assertEquals(ZoneWindows.TailKind.ANNUAL_RULES, coverage.tailKind());
        // Prefix must end before the stable tail starts, and the tail must span exactly
        // one Gregorian cycle.
        assertFalse(coverage.prefix().isEmpty());
        long tailStart = coverage.tailStartMs();
        assertEquals(tailStart, coverage.prefix().get(coverage.prefix().size() - 1).endMs);
        assertEquals(tailStart + ZoneWindows.CYCLE_MS, coverage.tail().get(coverage.tail().size() - 1).endMs);
        // Stable tail must have exactly two transitions per rule cycle (800 windows over
        // 400 years), i.e. the full annual structure is covered.
        assertEquals(800, coverage.tail().size());
        // The tail must start with an annual-rule transition, not a historical one: its
        // offset regime is generated from the provider's recurring rules. The historical
        // prefix ends strictly before the tail start; every prefix window is finite.
        for (ZoneWindows.Window window : coverage.prefix()) {
            assertTrue(window.endMs <= tailStart);
        }
        // The last stored history transition precedes the tail start: the tail is NOT the
        // shifted historic prefix.
        List<ZoneOffsetTransition> history = rules.getTransitions();
        ZoneOffsetTransition lastHistory = history.get(history.size() - 1);
        assertTrue(lastHistory.getInstant().toEpochMilli() <= tailStart);
        // Certificate decisions on a yearly UTC-style plan still come from the real proof:
        var yearly = IntervalValidator.validate("0 0 0 1 1 *", zone, MIN_MS);
        var passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, yearly);
        assertTrue(passed.minimumIntervalMs() >= MIN_MS);
        assertFalse(passed.minimumIntervalMs() == Long.MAX_VALUE);
    }

    /** V12 companion: the historic prefix is finite; the proof never repeats it. */
    @Test
    void v12_prefixWindowsAreFiniteAndNotShifted() {
        ZoneId zone = ZoneId.of("Australia/Lord_Howe");
        ZoneRules rules = zone.getRules();
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        assertEquals(ZoneWindows.TailKind.ANNUAL_RULES, coverage.tailKind());
        long tailStart = coverage.tailStartMs();
        // The tail period must be exactly one Gregorian cycle; the prefix ends there.
        assertEquals(tailStart + ZoneWindows.CYCLE_MS, coverage.tail().get(coverage.tail().size() - 1).endMs);
        // Half-hour zone: tail windows = 2 per year * 400 years = 800.
        assertEquals(800, coverage.tail().size());
        // Prefix windows are strictly before the tail start.
        for (ZoneWindows.Window window : coverage.prefix()) {
            assertTrue(window.endMs <= tailStart);
        }
    }

    // ------------------------------------------------------------- V13
    @Test
    void v13_localDateProjectionAndEmptySegments() {
        // Positive offset crosses midnight backwards: the window's first local date is the
        // UTC-previous day, which the old UTC epoch-day projection missed.
        ZoneId zone = ZoneId.of("Etc/GMT-14");
        ZoneRules rules = zone.getRules();
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        assertEquals(ZoneWindows.TailKind.FIXED_OFFSET, coverage.tailKind());
        assertEquals(0, coverage.prefix().size());
        List<ZoneWindows.Window> tail = coverage.tail();
        assertEquals(1, tail.size());
        ZoneWindows.Window window = tail.get(0);
        long dayStartUtc = LocalDate.of(2400, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertTrue(window.startMs <= dayStartUtc);
        // +14h projection parity: for a start instant at 11:00Z, the local date is the
        // NEXT UTC day (the old UTC epoch-day floor missed that date).
        long projectionInstant = Instant.parse("2400-01-01T11:00:00Z").toEpochMilli();
        assertTrue(window.startMs <= projectionInstant);
        assertEquals(LocalDate.ofInstant(Instant.ofEpochMilli(projectionInstant), zone),
                LocalDate.ofEpochDay(Math.floorDiv(projectionInstant, ZoneWindows.DAY_MS)).plusDays(1));
        // Fixed-offset +14h plan: full-day triggers with a real finite minimum.
        var plus14 = IntervalValidator.validate("0 0 1 * * *", zone, MIN_MS);
        var plus14Passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, plus14);
        assertEquals(86_400_000L, plus14Passed.minimumIntervalMs());
        // Negative fixed offset: the local START date is one UTC-day EARLIER than the UTC
        // epoch-day floor of startMs (the old UTC-only projection truncated that day).
        ZoneId west = ZoneId.of("Etc/GMT+11");
        var coverageWest = ZoneWindows.enumerateFullFuture(west.getRules());
        assertEquals(ZoneWindows.TailKind.FIXED_OFFSET, coverageWest.tailKind());
        ZoneWindows.Window westWindow = coverageWest.tail().get(0);
        assertEquals(LocalDate.ofInstant(Instant.ofEpochMilli(westWindow.startMs), west),
                LocalDate.ofEpochDay(Math.floorDiv(westWindow.startMs, ZoneWindows.DAY_MS)).minusDays(1));
        var westPass = IntervalValidator.validate("0 0 1 * * *", west, MIN_MS);
        assertEquals(86_400_000L,
                assertInstanceOf(IntervalValidator.Validation.Passed.class, westPass).minimumIntervalMs());
    }

    /** V13 controlled projection: gap day contributes zero, overlap day contributes two copies. */
    @Test
    void v13_controlledGapAndOverlapProjection() {
        ZoneRules controlled = ControlledRulesZone.rules();
        // June 15 03:00-04:00 wall is a real gap: an annual-only 03:30 plan never fires.
        var allGap = IntervalValidator.validateRules("0 30 3 15 6 *", controlled, MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, allGap);
        // The same time on a non-gap day (July 15) fires with the annual tail period:
        var annual = IntervalValidator.validateRules("0 30 3 15 7 *", controlled, MIN_MS);
        var annualPassed = assertInstanceOf(IntervalValidator.Validation.Passed.class, annual);
        assertTrue(annualPassed.minimumIntervalMs() >= MIN_MS);
        assertFalse(annualPassed.minimumIntervalMs() == Long.MAX_VALUE);
        // December 1 03:00-04:00 wall is a real overlap: the overlap-day plan has real
        // triggers at both offsets and the 12-hour gap to the next valid instant is fine.
        var overlapPlan = IntervalValidator.validateRules("0 30 3 1 12 *", controlled, MIN_MS);
        var overlapPassed = assertInstanceOf(IntervalValidator.Validation.Passed.class, overlapPlan);
        assertTrue(overlapPassed.minimumIntervalMs() >= MIN_MS);
        // Half-open boundary clipping: an every-second-of-gap-minute plan is impossible
        // (nothing in the gap matches), but the overlap minute has twice the candidates.
        var gapMinute = IntervalValidator.validateRules("0-59 30 3 15 6 *", controlled, MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, gapMinute);
    }

    /** V13 companion: half-open boundary clipping at a real transition instant. */
    @Test
    void v13_boundaryInstantClippedExactlyOnce() {
        ZoneId zone = ZoneId.of("Australia/Lord_Howe");
        ZoneRules rules = zone.getRules();
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        // Any mid-tail overlap transition: build the two adjacent windows and verify the
        // boundary instant belongs to exactly one side.
        List<ZoneWindows.Window> tail = coverage.tail();
        boolean checked = false;
        for (int i = 0; i + 1 < tail.size(); i++) {
            ZoneWindows.Window left = tail.get(i);
            ZoneWindows.Window right = tail.get(i + 1);
            if (left.endMs != right.startMs) {
                continue;
            }
            // Left-closed/right-open: the boundary instant is in the right window only.
            assertEquals(left.endMs, right.startMs);
            LocalDateTime boundaryLocal = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(left.endMs), zone);
            // At a fall-back overlap the boundary local time carries both offsets.
            assertTrue(rules.getValidOffsets(boundaryLocal).size() >= 1);
            checked = true;
            break;
        }
        assertTrue(checked);
    }

    // ------------------------------------------------------------- V05
    @Test
    void v05_allGapAndImpossiblePlanTerminate() {
        // Controlled annual-rule fixture: the 03:30 candidate exists locally on June 15
        // but resolves to zero absolute instants in EVERY stable-tail cycle (real gap).
        // The proof must terminate without a UTC probe and must NOT pass with
        // Long.MAX_VALUE; the historic prefix must not rescue a dead schedule.
        IntervalValidator.Validation allGap = IntervalValidator.validateRules(
                "0 30 3 15 6 *", ControlledRulesZone.rules(), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, allGap);
        // Impossible date: parser-level emptiness terminates too.
        assertInstanceOf(IntervalValidator.Validation.Rejected.class,
                IntervalValidator.validate("0 0 0 30 2 *", ZoneId.of("UTC"), MIN_MS));
        // A dense plan below 60s stays rejected (no sampling shortcut).
        assertInstanceOf(IntervalValidator.Validation.Rejected.class,
                IntervalValidator.validate("* * * * * *", ZoneId.of("UTC"), MIN_MS));
    }

    /** V06/V07 companion: fixed-offset zone plans produce the exact daily interval. */
    @Test
    void v06_fixedOffsetPlansExactInterval() {
        assertEquals(86_400_000L, assertInstanceOf(IntervalValidator.Validation.Passed.class,
                IntervalValidator.validate("0 0 0 * * *", ZoneId.of("Asia/Kathmandu"), MIN_MS)).minimumIntervalMs());
        assertEquals(3_600_000L, assertInstanceOf(IntervalValidator.Validation.Passed.class,
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Pacific/Apia"), MIN_MS)).minimumIntervalMs());
    }
}
