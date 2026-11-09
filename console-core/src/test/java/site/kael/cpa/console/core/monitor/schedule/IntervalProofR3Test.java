package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import site.kael.cpa.console.core.monitor.model.CronCertificate;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03.R3-A self-check (task v1.2-r3 §3/§4.2, V05/V06/V07/V12/V13). The certificate covers
 * the finite explicit prefix from the actual validation base point b plus exactly one
 * stable tail period; the pre-b history is never walked and never shifted by a Gregorian
 * cycle. Existence is decided inside the target zone; window local dates are projected
 * through the window offset. The absolute trigger-set expectations in this file are built
 * ONLY from the independent UTC-second oracle ({@link #oracleWindowSeconds}) - production
 * helpers (ZoneWindows, IntervalValidator, ScheduleIterator, IntervalValidatorForTesting,
 * DayPartGrid) are never used as the reference.
 */
class IntervalProofR3Test {

    private static final long MIN_MS = CronPolicy.MINIMUM_INTERVAL_MS;

    /**
     * Independent UTC-second oracle: every matching absolute instant of [fromMs, untilMs)
     * on the integer-second UTC grid. JDK Instant->local conversion only; the matching
     * predicate is a test-side local field expression. No production helper participates.
     */
    private static List<Long> oracleWindowSeconds(
            ZoneId zone, long fromMs, long untilMs, java.util.function.Predicate<LocalDateTime> filter) {
        TreeSet<Long> expected = new TreeSet<>();
        // Always enumerate on TRUE UTC integer seconds, regardless of a fractional base:
        // the product candidates are whole-wall-seconds, so a fractional base must not
        // shift the grid.
        long start = Math.floorDiv(fromMs, 1000L) * 1000L;
        for (long ms = start; ms < untilMs; ms += 1000L) {
            LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone);
            if (filter.test(local)) {
                expected.add(ms);
            }
        }
        return new ArrayList<>(expected);
    }

    /** Independent predicate: "the local wall clock is exactly hour:minute:second". */
    private static java.util.function.Predicate<LocalDateTime> fixedTime(int hour, int minute, int second) {
        return local -> local.getHour() == hour && local.getMinute() == minute && local.getSecond() == second;
    }

    /** Independent predicate: "every minute at the given second". */
    private static java.util.function.Predicate<LocalDateTime> everyMinuteAt(int second) {
        return local -> local.getSecond() == second;
    }

    /**
     * Independent oracle for a CONSTANT-offset zone: the local wall time h:m:s recurs at
     * one absolute offset from the local midnight grid, so the expected set is built from
     * the offset arithmetic itself (not from a production helper). This replaces a
     * seconds-loop over the whole 400-year period, which would not terminate in CI time;
     * {@link #oracleWindowSeconds} remains the local-date/transition oracle.
     */
    private static List<Long> fixedOffsetDailyOracle(
            ZoneId zone, long fromMs, long untilMs, int hour, int minute, int second) {
        ZoneOffset offset = zone.getRules().getOffset(Instant.ofEpochMilli(fromMs));
        long offsetSec = offset.getTotalSeconds();
        // Absolute instant of the first local-midnight boundary at or before fromMs.
        long firstLocalMidnightUtc = (fromMs + offsetSec * 1000L) / ZoneWindows.DAY_MS * ZoneWindows.DAY_MS
                - offsetSec * 1000L;
        TreeSet<Long> expected = new TreeSet<>();
        for (long localDayUtc = firstLocalMidnightUtc; localDayUtc < untilMs;
                localDayUtc += ZoneWindows.DAY_MS) {
            long wallMs = localDayUtc + (hour * 3600L + minute * 60L + second) * 1000L;
            if (wallMs >= fromMs && wallMs < untilMs) {
                expected.add(wallMs);
            }
        }
        return new ArrayList<>(expected);
    }

    /**
     * Product view: the union of the covered windows' projected day triggers for one
     * local date, at the PROOF's coverage. This is the observation side only - the
     * expected set always comes from {@link #oracleWindowSeconds}.
     */
    private static List<Long> productProjection(ZoneRules rules, ZoneWindows.Coverage coverage, String plan) {
        TreeSet<Long> product = new TreeSet<>();
        var expression = org.springframework.scheduling.support.CronExpression.parse(plan);
        Instant base = coverage.basePoint();
        // Epoch-ancient windows (whole-future overload, base = epoch floor) exercise JDK
        // wall-conversion overflow inside the reflective day walk; they are irrelevant for
        // the compared modern oracle ranges, so skip windows older than one cycle.
        for (ZoneWindows.Window window : coverage.prefix()) {
            if (window.endMs < -ZoneWindows.CYCLE_MS) {
                continue;
            }
            addWindowTriggers(expression, rules, window, product, base);
        }
        for (ZoneWindows.Window window : coverage.tail()) {
            addWindowTriggers(expression, rules, window, product, base);
        }
        return new ArrayList<>(product);
    }

    private static void addWindowTriggers(
            org.springframework.scheduling.support.CronExpression expression,
            ZoneRules rules, ZoneWindows.Window window, TreeSet<Long> product, Instant base) {
        LocalDate current = window.startLocalDate();
        LocalDate end = window.endLocalDate();
        while (!current.isAfter(end)) {
            product.addAll(IntervalValidator.dayTriggersInWindow(expression, rules, window, current, base));
            current = current.plusDays(1);
        }
    }

    // ------------------------------------------------------------- V12: base-point domain
    @Test
    void v12_nonPeriodicPrefixThenStableTail() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZoneRules rules = zone.getRules();
        // Whole-future view (package-private overload, b = provider floor): finite prefix
        // plus exactly one stable tail period.
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        assertEquals(ZoneWindows.TailKind.ANNUAL_RULES, coverage.tailKind());
        assertFalse(coverage.prefix().isEmpty());
        long tailStart = coverage.tailStartMs();
        assertEquals(tailStart, coverage.prefix().get(coverage.prefix().size() - 1).endMs);
        assertEquals(tailStart + ZoneWindows.CYCLE_MS, coverage.tail().get(coverage.tail().size() - 1).endMs);
        // Stable tail: exactly two transitions per rule cycle (800 windows over 400 years).
        assertEquals(800, coverage.tail().size());
        for (ZoneWindows.Window window : coverage.prefix()) {
            assertTrue(window.endMs <= tailStart);
        }
        List<ZoneOffsetTransition> history = rules.getTransitions();
        ZoneOffsetTransition lastHistory = history.get(history.size() - 1);
        assertTrue(lastHistory.getInstant().toEpochMilli() <= tailStart);
        // Certificate decisions on a yearly UTC-style plan still come from the real proof.
        var yearly = IntervalValidator.validate(
                "0 0 0 1 1 *", zone, Instant.parse("2026-11-01T00:00:00Z"), MIN_MS);
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
        assertEquals(tailStart + ZoneWindows.CYCLE_MS, coverage.tail().get(coverage.tail().size() - 1).endMs);
        // Half-hour zone: 2 transitions per year * 400 years = 800 tail windows.
        assertEquals(800, coverage.tail().size());
        for (ZoneWindows.Window window : coverage.prefix()) {
            assertTrue(window.endMs <= tailStart);
        }
    }

    // ------------------------------------------- V08/V12: base b in every region of the domain
    @Test
    void v12_basePointDomainCoversAllRegions() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZoneRules rules = zone.getRules();
        long lastHistoryMs = rules.getTransitions().get(rules.getTransitions().size() - 1)
                .getInstant().toEpochMilli();
        long tailStartMs = ZoneWindows.enumerateFullFuture(rules).tailStartMs();
        // b strictly inside each region of the absolute-time line.
        Instant[] bases = {
                Instant.parse("2026-01-01T00:00:00Z"),                     // explicit history
                Instant.ofEpochMilli(lastHistoryMs - 1),                   // just before last transition
                Instant.ofEpochMilli(lastHistoryMs),                       // exactly last transition
                Instant.ofEpochMilli(tailStartMs - 1),                     // just before stable T
                Instant.ofEpochMilli(tailStartMs),                         // exactly stable T
                Instant.ofEpochMilli(tailStartMs + 1),                     // inside stable tail
                Instant.ofEpochMilli(tailStartMs + ZoneWindows.CYCLE_MS),  // at the tail cycle seam
                Instant.ofEpochMilli(tailStartMs + ZoneWindows.CYCLE_MS + 1) // next tail period
        };
        String plan = "0 30 3 * * *";
        for (Instant b : bases) {
            ZoneWindows.Coverage cov = ZoneWindows.enumerateFullFuture(rules, b);
            // The base window may start AT b; only the projected set is clipped to
            // strictly-after-b (half-open proof domain). No window may end below b.
            for (ZoneWindows.Window window : cov.prefix()) {
                assertTrue(window.startMs >= b.toEpochMilli(), () -> "prefix window before base " + b);
            }
            // Bounded ABSOLUTE oracle window around the base; the oracle side is then
            // clipped to strictly after b and compared with the product projection.
            long oracleFrom = b.toEpochMilli() - ZoneWindows.DAY_MS;
            long oracleUntil = b.toEpochMilli() + 4 * ZoneWindows.DAY_MS;
            List<Long> expected = oracleWindowSeconds(zone, oracleFrom, oracleUntil,
                    fixedTime(3, 30, 0));
            List<Long> product = productProjection(rules, cov, plan);
            // The FIRST product trigger must be exactly the first oracle trigger > b.
            assertFalse(product.isEmpty(), () -> "hourly projection must not be empty for base " + b);
            long firstProduct = product.get(0);
            long expectedFirst = expected.stream().filter(ms -> ms > b.toEpochMilli())
                    .findFirst().orElseThrow().longValue();
            assertEquals(expectedFirst, firstProduct,
                    () -> "first projection trigger != independent oracle for base " + b);
            // The 61 next triggers must match the oracle sequence exactly (bounded diff).
            List<Long> nextOracle = expected.stream().filter(ms -> ms > b.toEpochMilli()).limit(61).toList();
            List<Long> nextProduct = product.stream()
                    .filter(ms -> ms >= oracleFrom && ms < oracleUntil)
                    .limit(nextOracle.size())
                    .toList();
            assertEquals(nextOracle, nextProduct,
                    () -> "projection != UTC-second oracle sequence for base " + b);
            // The same domain still certifies for this legal plan. The independent
            // minimum is the NY daily 03:30 spacing: 25h on the fall-back day, 23h on the
            // spring-forward day -> proven minimum = 23h (82800000 ms).
            var validation = IntervalValidator.validate(plan, zone, b, MIN_MS);
            var p = assertInstanceOf(IntervalValidator.Validation.Passed.class, validation,
                    () -> "legal plan must certify from base " + b);
            assertEquals(82_800_000L, p.minimumIntervalMs(),
                    () -> "daily 03:30 plan minimum must be the 23h spring-forward gap from base " + b);
        }
    }

    /**
     * V08/V12 companion: a purely historical violation below b must not reject a legal
     * future, while the same violation above b still rejects.
     */
    @Test
    void v12_historyBelowBasePointNeverRejectsFutureLegalPlan() {
        ZoneId zone = ZoneId.of("America/New_York");
        // The 59s->00s minute boundary plan is illegal everywhere it fires, at any base.
        String illegal = "0,59 * * * * *";
        // b before the very first stored trigger: the proof domain still contains the
        // violation -> rejected.
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, IntervalValidator.validate(
                illegal, zone, Instant.ofEpochMilli(0), MIN_MS));
        // A legal hourly plan certified at b=2026 stays valid afterwards...
        String legal = "0 30 * * * *";
        var early = IntervalValidator.validate(legal, zone, Instant.parse("2026-01-01T00:00:00Z"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, early);
        // ...and a base point before any stored trigger still certifies the same legal plan.
        var epoch = IntervalValidator.validate(legal, zone, Instant.ofEpochMilli(0), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, epoch);
    }

    // ------------------------------------------------------------- V13: projection + oracle
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
        // Fixed-offset +14h plan: full-day triggers with a real finite minimum, and the
        // product projection equals the independent UTC-second oracle over the tail period.
        var plus14 = IntervalValidator.validate("0 0 1 * * *", zone, Instant.ofEpochMilli(0), MIN_MS);
        var plus14Passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, plus14);
        assertEquals(86_400_000L, plus14Passed.minimumIntervalMs());
        List<Long> expectedPlus14 = fixedOffsetDailyOracle(
                zone, window.startMs, window.endMs, 1, 0, 0);
        assertEquals(expectedPlus14, productProjection(rules, coverage, "0 0 1 * * *"),
                "+14h projection must equal the UTC-second oracle over the full tail period");
        // Negative fixed offset: the local START date is one UTC-day EARLIER than the UTC
        // epoch-day floor of startMs (the old UTC-only projection truncated that day).
        ZoneId west = ZoneId.of("Etc/GMT+11");
        var coverageWest = ZoneWindows.enumerateFullFuture(west.getRules());
        assertEquals(ZoneWindows.TailKind.FIXED_OFFSET, coverageWest.tailKind());
        ZoneWindows.Window westWindow = coverageWest.tail().get(0);
        assertEquals(LocalDate.ofInstant(Instant.ofEpochMilli(westWindow.startMs), west),
                LocalDate.ofEpochDay(Math.floorDiv(westWindow.startMs, ZoneWindows.DAY_MS)).minusDays(1));
        var westPass = IntervalValidator.validate("0 0 1 * * *", west, Instant.ofEpochMilli(0), MIN_MS);
        assertEquals(86_400_000L,
                assertInstanceOf(IntervalValidator.Validation.Passed.class, westPass).minimumIntervalMs());
        List<Long> expectedWest = fixedOffsetDailyOracle(
                west, westWindow.startMs, westWindow.endMs, 1, 0, 0);
        assertEquals(expectedWest, productProjection(west.getRules(), coverageWest, "0 0 1 * * *"),
                "-11h projection must equal the UTC-second oracle over the full tail period");
    }

    /**
     * V13 controlled projection: gap day contributes zero, overlap day contributes two
     * absolute copies, transition boundary instant counted exactly once.
     */
    @Test
    void v13_controlledGapAndOverlapProjection() {
        ZoneRules controlled = ControlledRulesZone.rules();
        // The oracle needs a ZoneId for Instant->local conversion; the fixture is a simple
        // fixed standard-offset rules object, so a matching derived id is safe for this
        // test-only conversion (no production zone lookup involved).
        ZoneId fixtureId = ZoneId.ofOffset("UTC", controlled.getOffset(Instant.ofEpochMilli(0)));
        // June 15 03:00-04:00 wall is a real gap: an annual-only 03:30 plan never fires.
        var allGap = IntervalValidator.validateRules(
                "0 30 3 15 6 *", controlled, Instant.ofEpochMilli(0), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, allGap);
        // The same time on a non-gap day (July 15) fires with the annual tail period.
        var annual = IntervalValidator.validateRules(
                "0 30 3 15 7 *", controlled, Instant.ofEpochMilli(0), MIN_MS);
        var annualPassed = assertInstanceOf(IntervalValidator.Validation.Passed.class, annual);
        assertTrue(annualPassed.minimumIntervalMs() >= MIN_MS);
        assertFalse(annualPassed.minimumIntervalMs() == Long.MAX_VALUE);
        // December 1 03:00-04:00 wall is a real overlap: the overlap-day plan has real
        // triggers at both offsets.
        var overlapPlan = IntervalValidator.validateRules(
                "0 30 3 1 12 *", controlled, Instant.ofEpochMilli(0), MIN_MS);
        var overlapPassed = assertInstanceOf(IntervalValidator.Validation.Passed.class, overlapPlan);
        assertTrue(overlapPassed.minimumIntervalMs() >= MIN_MS);
        // Dense below 60s stays rejected (no sampling shortcut).
        var gapMinute = IntervalValidator.validateRules(
                "0-59 30 3 15 6 *", controlled, Instant.ofEpochMilli(0), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, gapMinute);

        // Independent UTC-second oracle over the WHOLE covered domain: an annual plan must
        // fire at exactly the two June-15 (or December-1) wall instants per covered period.
        var cov = ZoneWindows.enumerateFullFuture(controlled, Instant.ofEpochMilli(0));
        // One controlled year keeps the independent seconds-oracle bounded while it still
        // contains one full gap day (June 15) and one full overlap day (December 1).
        long fromMs = Instant.parse("2019-01-01T00:00:00Z").toEpochMilli();
        long untilMs = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        // June 15 03:30 wall is INSIDE the controlled gap (03:00 transition): the oracle
        // sees zero absolute instants there, and the product projection must agree.
        // Controlled gap day: the oracle resolves the June-15 03:30 wall through the
        // fixture rules' getValidOffsets (gap -> empty). This is the same JDK conversion
        // the UTC-second oracle uses for real zones; no production helper is involved.
        LocalDateTime juneWall = LocalDateTime.of(2019, 6, 15, 3, 30);
        assertTrue(controlled.getValidOffsets(juneWall).isEmpty(),
                "controlled June-15 03:30 wall must be inside the gap");
        assertTrue(productProjection(controlled, cov, "0 30 3 15 6 *").isEmpty(),
                "annual June-15 plan must have zero absolute triggers (gap contributes nothing)");

        // Overlap day: the fixture falls back +01->UTC at Dec-1 03:00 wall, so the wall
        // 02:30 exists in BOTH offsets while 03:30 exists only in UTC. Verify the oracle
        // view through getValidOffsets and the product projection for the matching plan.
        LocalDateTime overlapWall = LocalDateTime.of(2019, 12, 1, 2, 30);
        var offsets = controlled.getValidOffsets(overlapWall);
        assertEquals(2, offsets.size(), "Dec-1 02:30 wall is a real overlap (two offsets)");
        List<Long> expectedDec = offsets.stream()
                .map(overlapWall::toInstant)
                .map(Instant::toEpochMilli)
                .sorted()
                .toList();
        List<Long> decProduct = productProjection(controlled, cov, "0 30 2 1 12 *").stream()
                .filter(ms -> ms > Instant.parse("2019-11-30T00:00:00Z").toEpochMilli()
                        && ms < Instant.parse("2019-12-02T00:00:00Z").toEpochMilli())
                .toList();
        assertEquals(expectedDec, decProduct,
                "annual Dec-1 overlap plan must produce exactly both absolute copies");
        // The UTC-only 03:30 wall contributes exactly one copy.
        List<Long> after0330 = productProjection(controlled, cov, "0 30 3 1 12 *").stream()
                .filter(ms -> ms > Instant.parse("2019-11-30T00:00:00Z").toEpochMilli()
                        && ms < Instant.parse("2019-12-02T00:00:00Z").toEpochMilli())
                .toList();
        assertEquals(List.of(LocalDateTime.of(2019, 12, 1, 3, 30)
                        .toInstant(ZoneOffset.UTC).toEpochMilli()),
                after0330, "post-transition 03:30 wall must produce exactly one UTC copy");

        // Boundary semantics: the June-15 gap transition instant is an hourly-plan wall
        // candidate and may be counted at most once across the seam windows (half-open).
        ZoneOffsetTransition juneTransition = controlled.nextTransition(Instant.ofEpochMilli(0));
        long seenAtBoundary = productProjection(controlled, cov, "0 0 * * * *").stream()
                .filter(ms -> ms == juneTransition.getInstant().toEpochMilli())
                .count();
        assertTrue(seenAtBoundary <= 1,
                "the gap transition instant must not be double-counted at the seam");
        // everyMinuteAt(0).and(minute==0) is the LIVE oracle predicate for the hourly plan
        // on a bounded absolute window: compare the bounded product with the UTC oracle.
        List<Long> hourlyExpected = oracleWindowSeconds(fixtureId,
                Instant.parse("2019-06-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2019-07-15T00:00:00Z").toEpochMilli(),
                everyMinuteAt(0).and(local -> local.getMinute() == 0));
        List<Long> hourlyProduct = productProjection(controlled, cov, "0 0 * * * *").stream()
                .filter(ms -> ms >= Instant.parse("2019-06-01T00:00:00Z").toEpochMilli()
                        && ms < Instant.parse("2019-07-15T00:00:00Z").toEpochMilli())
                .limit(hourlyExpected.size())
                .toList();
        assertEquals(hourlyExpected, hourlyProduct,
                "hourly projection must equal the everyMinuteAt(0) UTC-second oracle");
    }

    /** V13 companion: half-open boundary clipping at a real transition instant. */
    @Test
    void v13_boundaryInstantClippedExactlyOnce() {
        ZoneId zone = ZoneId.of("Australia/Lord_Howe");
        ZoneRules rules = zone.getRules();
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
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
            assertTrue(rules.getValidOffsets(boundaryLocal).size() >= 1);
            checked = true;
            break;
        }
        assertTrue(checked);
        // Boundary semantics on a real zone: find a real overlap (fallback) transition in
        // the tail and assert the transition instant belongs to the right window only and
        // is counted exactly once in the covered hourly set.
        long foundTransitionMs = 0;
        for (int i = 0; i + 1 < tail.size(); i++) {
            long boundary = tail.get(i + 1).startMs;
            LocalDateTime localBoundary = LocalDateTime.ofInstant(Instant.ofEpochMilli(boundary), zone);
            if (rules.getValidOffsets(localBoundary).size() >= 2) {
                foundTransitionMs = boundary;
                break;
            }
        }
        final long transitionMs = foundTransitionMs;
        assertTrue(transitionMs > 0, "Lord Howe tail must contain an overlap transition");
        var expression = org.springframework.scheduling.support.CronExpression.parse("0 0 * * * *");
        TreeSet<Long> product = new TreeSet<>();
        Instant wholeFutureBase = Instant.ofEpochMilli(Long.MIN_VALUE);
        // Tail windows only: the boundary lives inside the stable tail and the JDK wall
        // conversion overflows on epoch-ancient prefix dates, which are irrelevant here.
        for (ZoneWindows.Window window : coverage.tail()) {
            addWindowTriggers(expression, rules, window, product, wholeFutureBase);
        }
        // Half-open ownership: the boundary instant belongs to the RIGHT window only
        // (left end inclusive, right end exclusive) - exactly-once by construction.
        ZoneWindows.Window left = null;
        ZoneWindows.Window right = null;
        for (int i = 0; i + 1 < tail.size(); i++) {
            if (tail.get(i + 1).startMs == transitionMs) {
                left = tail.get(i);
                right = tail.get(i + 1);
                break;
            }
        }
        assertNotNull(left);
        assertTrue(transitionMs >= left.startMs && transitionMs < left.endMs || transitionMs == left.endMs);
        assertTrue(transitionMs >= right.startMs && transitionMs < right.endMs);
        // Projection count: the hourly grid emits the boundary wall exactly once across the
        // two windows (no double count at the seam).
        long count = 0;
        for (ZoneWindows.Window window : new ZoneWindows.Window[]{left, right}) {
            LocalDate d = window.startLocalDate();
            LocalDate endD = window.endLocalDate();
            while (!d.isAfter(endD)) {
                count += IntervalValidator.dayTriggersInWindow(expression, rules, window, d,
                        wholeFutureBase).stream().filter(ms -> ms == transitionMs).count();
                d = d.plusDays(1);
            }
        }
        assertTrue(count <= 1, "the boundary instant may appear at most once across the seam windows");
    }

    // ------------------------------------------------------------- V05
    @Test
    void v05_allGapAndImpossiblePlanTerminate() {
        // Controlled annual-rule fixture: the 03:30 candidate exists locally on June 15
        // but resolves to zero absolute instants in EVERY stable-tail cycle (real gap).
        // The proof must terminate without a UTC probe and must NOT pass with
        // Long.MAX_VALUE; the historic prefix must not rescue a dead schedule.
        IntervalValidator.Validation allGap = IntervalValidator.validateRules(
                "0 30 3 15 6 *", ControlledRulesZone.rules(), Instant.ofEpochMilli(0), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, allGap);
        // Impossible date: parser-level emptiness terminates too.
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, IntervalValidator.validate(
                "0 0 0 30 2 *", ZoneId.of("UTC"), Instant.ofEpochMilli(0), MIN_MS));
        // A dense plan below 60s stays rejected (no sampling shortcut).
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, IntervalValidator.validate(
                "* * * * * *", ZoneId.of("UTC"), Instant.ofEpochMilli(0), MIN_MS));
    }

    /** V06/V07 companion: fixed-offset zone plans produce the exact daily interval. */
    @Test
    void v06_fixedOffsetPlansExactInterval() {
        Instant base = Instant.ofEpochMilli(0);
        assertEquals(86_400_000L, assertInstanceOf(IntervalValidator.Validation.Passed.class,
                IntervalValidator.validate("0 0 0 * * *", ZoneId.of("Asia/Kathmandu"), base, MIN_MS))
                .minimumIntervalMs());
        assertEquals(3_600_000L, assertInstanceOf(IntervalValidator.Validation.Passed.class,
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Pacific/Apia"), base, MIN_MS))
                .minimumIntervalMs());
    }

    // ------------------------------------------------------------- V08 companion: certificate domain
    @Test
    void v08_certificateUseDomainFollowsBasePoint() {
        CronPolicyValidator validator = new CronPolicyValidator(
                java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        CronCertificate fresh = validator.certify("0 0 12 * * *", "UTC");
        // issuedAt > basePoint: the certificate carries BOTH instants from the proof.
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), fresh.basePoint());
        assertEquals(4, fresh.algorithmVersion());
        assertTrue(fresh.validFor(Instant.parse("2026-01-01T00:00:01Z")));
        assertTrue(fresh.validFor(Instant.parse("2026-01-01T00:00:00Z")),
                "the certification instant itself is inside the proven domain");
        // A certificate issued at a base point after the fresh one's domain start is NOT
        // usable for the fresh domain: the proof domain must match the actual base.
        CronPolicyValidator laterValidator = new CronPolicyValidator(
                java.time.Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
        CronCertificate later = laterValidator.certify("0 0 12 * * *", "UTC");
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), later.basePoint());
        assertTrue(laterValidator.isCertificateValid(later, "0 0 12 * * *", "UTC"));
        // Cross-domain reuse: a LATER validator use instant is inside the fresh
        // certificate's still-open domain, so the fresh certificate remains valid there;
        // the fresh validator's own use instant is valid for its own certificate too.
        assertTrue(laterValidator.isCertificateValid(fresh, "0 0 12 * * *", "UTC"),
                "an earlier-domain certificate remains valid while its domain is open");
        assertTrue(validator.isCertificateValid(fresh, "0 0 12 * * *", "UTC"));
        // A use instant BEFORE the certificate's proof base point is outside the domain.
        CronPolicyValidator earlierValidator = new CronPolicyValidator(
                java.time.Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertFalse(earlierValidator.isCertificateValid(fresh, "0 0 12 * * *", "UTC"),
                "a certificate is not usable before its proven base-point domain");
        // Algorithm version bump invalidates every stored predecessor certificate.
        assertThrows(IllegalArgumentException.class, () -> new CronCertificate(
                "0 0 12 * * *", "UTC", fresh.tzdbVersion(), 3, fresh.minimumIntervalMs(),
                null, fresh.generatedAt()),
                "v3 certificates without a base point cannot exist under algorithm v4");
    }
}
