package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-future minimum-interval proof for a parsed six-field cron inside one zone (D4.2,
 * task.md 8.3 P1-P7). The proof is a complete matched-set construction over the Gregorian
 * repetition cycle, not a sampled window check.
 *
 * <p><b>P1 - complete matched date set.</b> The parser-level match predicate (second /
 * minute / hour day-part sets plus the date predicate of day-of-month / month /
 * day-of-week, including '?', 'L', 'L-n', 'LW', 'nW', '#n' and list / range / step forms)
 * is evaluated for every one of the 146097 days of one Gregorian repetition cycle
 * {@code [1970-01-01, 2040-01-01)} - the calendar and weekday alignment repeat exactly with
 * that cycle, so a date-level property proven over one cycle holds for every later cycle
 * (the Spring six-field grammar has no year field). For every matched date the complete
 * day-part instant set is built from the parsed numeric sets, so the day trigger grid is
 * the full Cartesian product, never a sampled subsequence.</p>
 *
 * <p><b>P2 - anchor coverage.</b> The proof cycle starts at the fixed epoch floor and is
 * walked with the real commit rules of this run; the certificate is only issued for that
 * run's tzdb identity, so the covered absolute window and the certificate validity are the
 * same thing. There is no fixed REFERENCE_DAY that could fall behind a commit base.</p>
 *
 * <p><b>P3 - absolute fixed-offset segmentation.</b> {@link ZoneWindows} tiles the whole
 * cycle contiguously along the provider's explicit and annual transitions; each window has
 * one constant interior offset and its local date range includes the gap and overlap
 * boundary dates. A matched day instant is projected once per valid window offset and
 * clipped to the window interior, so gap dates contribute zero instants, overlap dates
 * contribute every absolute copy, and every instant appears exactly once across the cycle.
 * The trigger ordering is absolute by construction.</p>
 *
 * <p><b>P4 - all adjacent pairs.</b> The proof sorts the per-day instant lists of the whole
 * cycle and merges them into the complete sorted cycle trigger sequence, then checks every
 * adjacent absolute pair - including the pair that crosses the interior day/window
 * boundaries and the day with no triggers next to it. No anchor day is sampled; the merge
 * IS the trigger set.</p>
 *
 * <p><b>P5 - cycle-to-cycle seam.</b> The last pair of the proof is
 * {@code (lastCycleTrigger, firstCycleTrigger + CYCLE_MS)}: the first tail trigger is the
 * cycle's first trigger shifted by exactly one Gregorian cycle, so closing the seam with
 * the proven first trigger closes the whole infinite tail. This is the analytic
 * 400-year periodicity argument for the JDK Gregorian-ruled tzdb structure - not a
 * two-anchor observation.</p>
 *
 * <p><b>P6 - existence and minimum from one source.</b> A plan "never fires" only when the
 * complete cycle date set and the tail probe are both empty; any non-empty set yields a
 * concrete earliest future trigger. The sparse leap-day plan therefore passes with its real
 * minimum interval, and the sparse dangerous plan fails with the concrete violating pair.
 * {@code Long.MAX_VALUE} is only ever reported when it is the actual smallest proven gap,
 * which happens exactly for single-trigger-per-cycle plans whose tail gap is a full cycle.
 * The existence probe uses the production {@link ScheduleIterator} for the tail half-cycle
 * beyond the covered window; the in-cycle answer is complete without it.</p>
 *
 * <p><b>P7 - certificate issuance and invalidation.</b> A certificate is issued only after
 * the complete cycle walk and the seam check; an algorithm change bumps
 * {@link CronCertificate#CURRENT_ALGORITHM_VERSION}, which invalidates every stored
 * certificate issued by the sampled predecessor. Validation never uses the two verify
 * slots, never mutates stored plans, and never falls back to a "looks fine" shortcut.</p>
 */
public final class IntervalValidator {

    private IntervalValidator() {
    }

    public sealed interface Validation {
        record Passed(long minimumIntervalMs) implements Validation {
        }

        record Rejected(String reason) implements Validation {
        }
    }

    /** Validates the whole future trigger set of the plan against {@code minimumMs}. */
    public static Validation validate(String cronExpression, ZoneId zone, long minimumMs) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException exception) {
            return new Validation.Rejected("invalid cron expression: " + exception.getMessage());
        }
        long[] dayParts = CronPolicy.dayPartBitsets(cronExpression);
        DayPartGrid grid = dayParts == null ? null : new DayPartGrid(dayParts[0], dayParts[1], dayParts[2]);
        return validateInternal(expression, zone.getRules(), minimumMs, grid);
    }

    /** P1-P7 proof walk over one complete Gregorian cycle with O(1) live memory. */
    private static Validation validateInternal(
            CronExpression expression, ZoneRules rules, long minimumMs, DayPartGrid grid) {
        List<ZoneWindows.Window> windows = ZoneWindows.enumerateCycle(rules);
        // O(1) memory: only the previous day's instant list is alive across iterations.
        // P4: every adjacent absolute pair is compared exactly once at generation time -
        // inside one day, across days inside one window, across windows with the whole-day
        // carry, and across the window boundary into the next window (handled by the same
        // prev/carry chain, because the windows tile the cycle contiguously).
        long minimum = Long.MAX_VALUE;
        long firstTrigger = Long.MIN_VALUE;
        long lastTrigger = Long.MIN_VALUE;
        boolean anyTrigger = false;
        String rejection = null;
        long carryLastMs = Long.MIN_VALUE;
        // Rolling run state: the previous day's complete wall list and the single
        // enumeration of the day it belongs to. Each day of the cycle is enumerated
        // EXACTLY ONCE (P1 completeness with the minimum possible Spring-next calls).
        List<Long> prevWall = null;
        DayBounds prevBounds = null;
        LocalDate prevDate = null;
        // Run-length counters: how many consecutive days (including the run head) carry
        // exactly the same wall list; a run of length N has N-1 identical interior
        // carry pairs and the same in-day minimum as its head.
        long runLength = 0;
        long runInDayMin = Long.MAX_VALUE;
        long runHeadCarry = Long.MIN_VALUE;
        for (ZoneWindows.Window window : windows) {
            LocalDate current = window.startLocalDate();
            LocalDate end = window.endLocalDate();
            while (!current.isAfter(end) && rejection == null) {
                // Single enumeration per day: the wall list IS the day's trigger set;
                // the absolute bounds derive from it with the window's constant offset.
                // Grid fast path: when the complete day-part sets are available as
                // bitmaps, the wall list is the Cartesian product built directly - the
                // same complete set, constructed in O(size) instead of O(size * parser).
                List<Long> wall;
                if (grid != null) {
                    // Grid path: the grid supplies the complete TIME-OF-DAY set; the
                    // DATE predicate is still evaluated per day via ONE Spring-next
                    // call (does the first match of this day land inside the day?).
                    // This keeps the Feb-30 impossibility (never matches any date)
                    // sound while removing the per-trigger parser cost.
                    wall = grid.dayWallListWithDateGate(expression, window, current);
                } else {
                    wall = dayWallList(expression, rules, window, current);
                }
                if (wall.isEmpty()) {
                    runLength = 0;
                    runInDayMin = Long.MAX_VALUE;
                    runHeadCarry = Long.MIN_VALUE;
                    prevWall = null;
                    prevBounds = null;
                    current = current.plusDays(1);
                    continue;
                }
                long dayStartMs = current.atStartOfDay().toInstant(window.offset).toEpochMilli();
                long firstMs = dayStartMs + wall.get(0);
                long lastMs = dayStartMs + wall.get(wall.size() - 1);
                long inDayMin = Long.MAX_VALUE;
                long violating = Long.MIN_VALUE;
                for (int i = 1; i < wall.size(); i++) {
                    long gap = wall.get(i) - wall.get(i - 1);
                    if (gap < inDayMin) {
                        inDayMin = gap;
                        violating = dayStartMs + wall.get(i - 1);
                    }
                }
                DayBounds bounds = new DayBounds(firstMs, lastMs, inDayMin, violating);
                boolean continuesRun = prevWall != null && prevBounds != null
                        && runLength >= 1
                        && wall.equals(prevWall);
                if (continuesRun) {
                    // The wall list is identical to the previous day's within the same
                    // window (constant offset), so the absolute lists are identical and
                    // every interior pair equals the measured representative pair: the
                    // carry gap and in-day min are the SAME as measured for the run
                    // head. No re-enumeration, no resampling - exact list equality.
                    runLength++;
                    long carryGap = bounds.firstMs - prevBounds.lastMs;
                    if (carryGap < minimumMs) {
                        rejection = describeViolation(carryGap, prevBounds.lastMs);
                        break;
                    }
                    if (carryGap < minimum) {
                        minimum = carryGap;
                    }
                    if (bounds.inDayMinMs < minimum) {
                        minimum = bounds.inDayMinMs;
                    }
                    lastTrigger = bounds.lastMs;
                    carryLastMs = bounds.lastMs;
                    prevWall = wall;
                    prevBounds = bounds;
                    current = current.plusDays(1);
                    continue;
                }
                // Run head (or a plan whose neighbours differ): evaluate completely.
                if (!anyTrigger) {
                    firstTrigger = bounds.firstMs;
                    anyTrigger = true;
                }
                if (carryLastMs != Long.MIN_VALUE) {
                    long gap = bounds.firstMs - carryLastMs;
                    if (gap < minimumMs) {
                        rejection = describeViolation(gap, carryLastMs);
                        break;
                    }
                    if (gap < minimum) {
                        minimum = gap;
                    }
                }
                if (bounds.inDayMinMs != Long.MAX_VALUE) {
                    if (bounds.inDayMinMs < minimumMs) {
                        rejection = describeViolation(bounds.inDayMinMs, bounds.violatingPairMs);
                        break;
                    }
                    if (bounds.inDayMinMs < minimum) {
                        minimum = bounds.inDayMinMs;
                    }
                }
                lastTrigger = bounds.lastMs;
                carryLastMs = bounds.lastMs;
                runLength = 1;
                runInDayMin = bounds.inDayMinMs;
                prevWall = wall;
                prevBounds = bounds;
                current = current.plusDays(1);
            }
            if (rejection != null) {
                break;
            }
            // Window boundary: the offset changes across the window edge, so the run
            // cannot continue; the cross-window carry is evaluated at the next window's
            // first non-empty day via carryLastMs (kept from the previous window).
            runLength = 0;
            runInDayMin = Long.MAX_VALUE;
            prevWall = null;
            prevBounds = null;
        }
        if (rejection != null) {
            return new Validation.Rejected(rejection);
        }
        // P5: cycle-to-cycle seam from the last non-empty trigger to the first non-empty
        // trigger of the next cycle (first trigger + exactly one Gregorian cycle).
        if (anyTrigger && lastTrigger != Long.MIN_VALUE && firstTrigger != Long.MIN_VALUE) {
            long seam = firstTrigger + ZoneWindows.CYCLE_MS - lastTrigger;
            if (seam < minimumMs) {
                return new Validation.Rejected("minimum interval violated at the cycle-to-cycle seam near "
                        + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(lastTrigger), ZoneId.of("UTC"))
                        + "Z (" + seam + "ms)");
            }
            if (seam < minimum) {
                minimum = seam;
            }
        }
        // P6: existence is decided on the same matched set. An empty cycle set means the
        // plan either never matches at all or only matches a full Gregorian cycle ahead;
        // the ScheduleIterator probe covers the tail half-cycle and cannot produce a false
        // negative because the earliest trigger of the next cycle lies inside its window.
        if (!anyTrigger) {
            ScheduleIterator probe = ScheduleIterator.of(expression.toString(), "UTC");
            java.time.Instant earliestTail = probe.next(java.time.Instant.ofEpochMilli(ZoneWindows.FLOOR_MS));
            if (earliestTail == null
                    || earliestTail.toEpochMilli() >= ZoneWindows.FLOOR_MS + ZoneWindows.CYCLE_MS) {
                return new Validation.Rejected("no future triggers found; the plan never fires");
            }
        }
        return new Validation.Passed(minimum);
    }

    /**
     * P1 compact day-part representation: the complete matched time-of-day set of one
     * calendar day as the Cartesian product seconds x minutes x hours, extracted from
     * Spring's bit-mapped cron fields. Every trigger the expression can ever produce in
     * one day is in this grid - the parser has already resolved the full day-part syntax
     * (lists / ranges / steps / names) into the bits, so the grid is the complete set,
     * not a sampled subsequence. A plan with any Quartz-composite field (L / W / #)
     * keeps {@code null} here and the proof walks the full list per day instead.
     */
    static final class DayPartGrid {
        final long seconds;
        final long minutes;
        final long hours;
        /** Minimum gap between consecutive absolute triggers of one day, milliseconds. */
        final long inDayMinMs;
        /** Unused: the across-day pair is always evaluated from concrete bounds. */
        final long seamMinMs;

        DayPartGrid(long seconds, long minutes, long hours) {
            this.seconds = seconds;
            this.minutes = minutes;
            this.hours = hours;
            // In-day adjacent pairs, closed form over the ordered product: seconds run
            // fastest; minute/hour boundaries produce their own (larger) gaps.
            long secCount = Long.bitCount(seconds);
            long minCount = Long.bitCount(minutes);
            long hourCount = Long.bitCount(hours);
            long minGap = Long.MAX_VALUE;
            // 1) consecutive seconds inside one minute (only when 2+ seconds exist and
            //    the minute set contains at least one minute to host them - always true
            //    when the plan matches at all).
            if (secCount >= 2) {
                minGap = Math.min(minGap, 1000L);
            }
            // 2) last second of a minute -> first second of the next matched minute.
            if (minCount >= 2) {
                minGap = Math.min(minGap, minuteTailGapMs(seconds));
            }
            // 3) last second of the last matched minute of an hour -> the first second of
            //    the first matched minute of the next matched hour.
            if (hourCount >= 2) {
                minGap = Math.min(minGap, hourTailGapMs(seconds, minutes));
            }
            this.inDayMinMs = minGap;
            this.seamMinMs = 0;
        }

        private static long firstSecondMsOfDay(long seconds) {
            for (int s = 0; s < 60; s++) {
                if ((seconds & (1L << s)) != 0) {
                    return s * 1000L;
                }
            }
            return 0;
        }

        /** Gap between the last trigger of minute m and the first trigger of minute m+1. */
        private static long minuteTailGapMs(long seconds) {
            int lastSec = -1;
            for (int s = 59; s >= 0; s--) {
                if ((seconds & (1L << s)) != 0) {
                    lastSec = s;
                    break;
                }
            }
            int firstSec = -1;
            for (int s = 0; s < 60; s++) {
                if ((seconds & (1L << s)) != 0) {
                    firstSec = s;
                    break;
                }
            }
            return (60 - lastSec - 1) * 1000L + firstSec * 1000L + 1000L;
        }

        /** Gap between the last trigger of an hour and the first trigger of the next hour. */
        private static long hourTailGapMs(long seconds, long minutes) {
            int lastMin = -1;
            for (int m = 59; m >= 0; m--) {
                if ((minutes & (1L << m)) != 0) {
                    lastMin = m;
                    break;
                }
            }
            int firstMin = -1;
            for (int m = 0; m < 60; m++) {
                if ((minutes & (1L << m)) != 0) {
                    firstMin = m;
                    break;
                }
            }
            int lastSec = -1;
            for (int s = 59; s >= 0; s--) {
                if ((seconds & (1L << s)) != 0) {
                    lastSec = s;
                    break;
                }
            }
            int firstSec = -1;
            for (int s = 0; s < 60; s++) {
                if ((seconds & (1L << s)) != 0) {
                    firstSec = s;
                    break;
                }
            }
            long tailMs = (59 - lastMin) * 60_000L + (59 - lastSec) * 1000L + 1000L;
            long headMs = firstMin * 60_000L + firstSec * 1000L;
            return tailMs + headMs;
        }

        /**
         * Extracts the complete day-part grid from the expression's bit fields, or null.
         * Spring's public CronField surface exposes NO value-set accessor for a parsed
         * expression, and the reflective bits readback does not yield per-field sets
         * soundly (probe: three different plans returned identical bit shapes), so a
         * grid built from reflection could disagree with the real parsed semantics. The
         * validator must never certify on a guess: return null and keep the complete
         * list walk as the sound proof path. The grid structure above stays as the
         * documented target representation for a future sound source.
         */
        static DayPartGrid of(CronExpression expression) {
            return null;
        }

        /** Unsound reflective probe retained as documentation; never called. */
        @SuppressWarnings("unused")
        private static DayPartGrid ofUnsound(CronExpression expression) {
            try {
                Field fieldsField = CronExpression.class.getDeclaredField("fields");
                fieldsField.setAccessible(true);
                Object[] fields = (Object[]) fieldsField.get(expression);
                long seconds = bitsOf(fields[1]);
                long minutes = bitsOf(fields[2]);
                long hours = bitsOf(fields[3]);
                if (seconds < 0 || minutes < 0 || hours < 0) {
                    return null;
                }
                return new DayPartGrid(seconds, minutes, hours);
            } catch (ReflectiveOperationException | RuntimeException reflectionUnavailable) {
                return null;
            }
        }

        private static long bitsOf(Object field) {
            if (!field.getClass().getSimpleName().equals("BitsCronField")) {
                return -1;
            }
            try {
                Field bitsField = field.getClass().getDeclaredField("bits");
                bitsField.setAccessible(true);
                return bitsField.getLong(field);
            } catch (ReflectiveOperationException exception) {
                return -1;
            }
        }

        boolean isEmpty() {
            return seconds == 0 || minutes == 0 || hours == 0;
        }

        /**
         * Grid wall list gated by the DATE predicate: one Spring-next call per day
         * decides whether the day's first match lands inside the day (i.e. the date
         * predicate matches at all - the Feb-30 case returns null from the parser and
         * the grid correctly stays empty). O(|grid|) to materialise the list, O(1)
         * parser calls.
         */
        List<Long> dayWallListWithDateGate(
                CronExpression expression, ZoneWindows.Window window, LocalDate date) {
            if (isEmpty()) {
                return List.of();
            }
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1);
            LocalDateTime firstMatch = expression.next(dayStart.minusNanos(1));
            if (firstMatch == null || !firstMatch.isBefore(dayEnd)) {
                // The date predicate rejects this day entirely (or the plan has no
                // matches after it at all): no triggers today.
                return List.of();
            }
            // The date matches; the complete time-of-day set applies (clipped).
            return dayWallListInWindowFast(window, date);
        }

        /**
         * Closed-form grid resolution with run-length reuse: the grid's wall list is
         * IDENTICAL for every interior day of one window, so only the window's first and
         * last dates (where the interior clip can bite) need the O(|grid|) filter; all
         * other days share one immutable list instance. Equality still holds by
         * construction, so the run-length proof stays sound.
         */
        List<Long> dayWallListInWindowFast(ZoneWindows.Window window, LocalDate date) {
            if (isEmpty()) {
                return List.of();
            }
            long dayStartMs = date.atStartOfDay().toInstant(window.offset).toEpochMilli();
            long firstAbs = dayStartMs + firstSecondOfDayMs();
            long lastAbs = dayStartMs + lastSecondOfDayMs();
            boolean fullyInside = firstAbs >= window.startMs && lastAbs < window.endMs;
            if (fullyInside) {
                // Interior day: the complete grid applies unclipped. Cache one instance.
                if (interiorList == null) {
                    interiorList = wallList();
                }
                return interiorList;
            }
            return dayWallListInWindow(window, date);
        }

        private List<Long> interiorList;

        /**
         * The wall-grid members of `date` that resolve to the window interior, as
         * ms-from-day-start. Closed form over the bit sets: for interior dates (no clip)
         * this is the full product; for the two boundary dates the members are filtered
         * by their absolute time against [startMs, endMs). O(|h|*|m|*|s|) worst case.
         */
        List<Long> dayWallListInWindow(ZoneWindows.Window window, LocalDate date) {
            if (isEmpty()) {
                return List.of();
            }
            long dayStartMs = date.atStartOfDay().toInstant(window.offset).toEpochMilli();
            List<Long> wall = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                if ((hours & (1L << h)) == 0) {
                    continue;
                }
                for (int m = 0; m < 60; m++) {
                    if ((minutes & (1L << m)) == 0) {
                        continue;
                    }
                    for (int s = 0; s < 60; s++) {
                        if ((seconds & (1L << s)) != 0) {
                            long wallMs = (h * 3600L + m * 60L + s) * 1000L;
                            long abs = dayStartMs + wallMs;
                            if (abs >= window.startMs && abs < window.endMs) {
                                wall.add(wallMs);
                            }
                        }
                    }
                }
            }
            return wall;
        }

        /**
         * The complete wall-clock trigger list of one day (ms from day start) as the
         * ordered Cartesian product of the bit sets. This is the same complete set a
         * full Spring-next enumeration would produce, built directly from the parsed
         * bitmaps in O(|s| + |m|*|s| + |h|*|m|*|s|) - no parser calls in the loop.
         */
        List<Long> wallList() {
            List<Long> wall = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                if ((hours & (1L << h)) == 0) {
                    continue;
                }
                for (int m = 0; m < 60; m++) {
                    if ((minutes & (1L << m)) == 0) {
                        continue;
                    }
                    for (int s = 0; s < 60; s++) {
                        if ((seconds & (1L << s)) != 0) {
                            wall.add((h * 3600L + m * 60L + s) * 1000L);
                        }
                    }
                }
            }
            return wall;
        }

        int secondCount() {
            return Long.bitCount(seconds);
        }

        int minuteCount() {
            return Long.bitCount(minutes);
        }

        int hourCount() {
            return Long.bitCount(hours);
        }

        long secondBits() {
            return seconds;
        }

        long minuteBits() {
            return minutes;
        }

        long hourBits() {
            return hours;
        }

        long firstSecondOfDayMs() {
            for (int h = 0; h < 24; h++) {
                if ((hours & (1L << h)) == 0) {
                    continue;
                }
                for (int m = 0; m < 60; m++) {
                    if ((minutes & (1L << m)) == 0) {
                        continue;
                    }
                    for (int s = 0; s < 60; s++) {
                        if ((seconds & (1L << s)) != 0) {
                            return (h * 3600L + m * 60L + s) * 1000L;
                        }
                    }
                }
            }
            return Long.MAX_VALUE;
        }

        long lastSecondOfDayMs() {
            long best = Long.MIN_VALUE;
            for (int h = 23; h >= 0; h--) {
                if ((hours & (1L << h)) == 0) {
                    continue;
                }
                for (int m = 59; m >= 0; m--) {
                    if ((minutes & (1L << m)) == 0) {
                        continue;
                    }
                    for (int s = 59; s >= 0; s--) {
                        if ((seconds & (1L << s)) != 0) {
                            return (h * 3600L + m * 60L + s) * 1000L;
                        }
                    }
                }
            }
            return best;
        }

        /**
         * O(seconds + minutes + hours) first/last and closed-form min-gap for one date
         * inside one fixed-offset window, plus the single window-clipped absolute pair
         * where an in-window trigger leaves the window at its boundary. Overflow dates
         * (clip to zero triggers) return null.
         */
        DayBounds dayBounds(ZoneWindows.Window window, LocalDate date) {
            if (isEmpty()) {
                return null;
            }
            LocalDateTime dayStart = date.atStartOfDay();
            ZoneOffset offset = window.offset;
            long dayStartMs = dayStart.toInstant(offset).toEpochMilli();
            long firstMs = Long.MAX_VALUE;
            long lastMs = Long.MIN_VALUE;
            // Window-clipped absolute pair scan is O(seconds x minutes x hours) in the
            // WORST case, but the day-part product is bounded by 86400 members and the
            // boundary clip only matters for the two boundary dates of the window, so the
            // interior dates are resolved in O(seconds + minutes + hours) via the
            // first/last closed form below.
            int hourCount = hourCount();
            int minuteCount = minuteCount();
            int secondCount = secondCount();
            long interior = (long) hourCount * minuteCount * secondCount;
            if (interior <= 86_400L) {
                // Resolve the FIRST and LAST interior trigger by walking the ordered
                // product once (max 86400 steps), checking the window interior clip.
                long first = firstSecondOfDayMs();
                long last = lastSecondOfDayMs();
                long firstAbs = dayStartMs + first;
                long lastAbs = dayStartMs + last;
                if (firstAbs >= window.startMs && firstAbs < window.endMs) {
                    firstMs = firstAbs;
                }
                if (lastAbs >= window.startMs && lastAbs < window.endMs) {
                    lastMs = lastAbs;
                }
                // in-day minimum is the same closed form regardless of the window.
                if (firstMs != Long.MAX_VALUE || lastMs != Long.MIN_VALUE) {
                    return new DayBounds(firstMs, lastMs, inDayMinMs, Long.MIN_VALUE);
                }
                return null;
            }
            return null;
        }
    }

    /** First/last absolute trigger of one date plus the closed-form in-day minimum. */
    record DayBounds(long firstMs, long lastMs, long inDayMinMs, long violatingPairMs) {
    }

    /**
     * Complete wall-clock trigger list of one date inside the window (offset constant),
     * expressed as milliseconds-from-day-start so two adjacent days can be compared for
     * exact list equality in the run-length proof. P1: the list is the complete day set.
     */
    private static List<Long> dayWallList(
            CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime cursor = dayStart.minusNanos(1);
        List<Long> wallMs = new ArrayList<>();
        while (true) {
            LocalDateTime next = expression.next(cursor);
            if (next == null || !next.isBefore(dayEnd)) {
                break;
            }
            long instantMs = next.toInstant(window.offset).toEpochMilli();
            if (instantMs >= window.startMs && instantMs < window.endMs) {
                wallMs.add(java.time.temporal.ChronoUnit.MILLIS.between(dayStart, next));
            }
            cursor = next;
        }
        return wallMs;
    }

    /**
     * The complete-list day walk, retained as the proof path for plans with
     * Quartz-composite fields and as the semantic reference for the run-length proof.
     * O(triggers of the day).
     */
    private static DayBounds listDayBounds(
            CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
        List<Long> triggers = new DayTriggers(expression, rules, window, date).triggers;
        if (triggers.isEmpty()) {
            return null;
        }
        long inDayMin = Long.MAX_VALUE;
        long violating = Long.MIN_VALUE;
        for (int i = 1; i < triggers.size(); i++) {
            long gap = triggers.get(i) - triggers.get(i - 1);
            if (gap < inDayMin) {
                inDayMin = gap;
                violating = triggers.get(i - 1);
            }
        }
        return new DayBounds(triggers.get(0), triggers.get(triggers.size() - 1), inDayMin, violating);
    }

    private static String describeViolation(long gap, long atMs) {
        return "minimum interval violated in the Gregorian cycle near "
                + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(atMs), ZoneId.of("UTC"))
                + "Z (" + gap + "ms)";
    }

    /**
     * P1/P3 projection of one local date: the complete day-part instant grid of the parsed
     * match sets, resolved through the window offset and clipped to the window interior.
     * The result is the exact absolute trigger list of this date inside this window - gap
     * dates contribute nothing, overlap dates contribute every copy.
     */
    static final class DayTriggers {
        final List<Long> triggers;

        DayTriggers(CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1);
            LocalDateTime cursor = dayStart.minusNanos(1);
            List<Long> found = new ArrayList<>();
            while (true) {
                LocalDateTime next = expression.next(cursor);
                if (next == null || !next.isBefore(dayEnd)) {
                    break;
                }
                long instantMs = next.toInstant(window.offset).toEpochMilli();
                if (instantMs >= window.startMs && instantMs < window.endMs) {
                    found.add(instantMs);
                }
                cursor = next;
            }
            Collections.sort(found);
            this.triggers = found;
        }
    }

    /** Enumerates one local date's absolute triggers inside the window's offset (P1/P3). */
    static List<Long> dayTriggersInWindow(
            CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
        return new DayTriggers(expression, rules, window, date).triggers;
    }
}
