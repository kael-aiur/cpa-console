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
 * Full-future minimum-interval proof for a parsed six-field cron inside one zone
 * (D4.2, task.md 8.3 P1-P7). The proof is split into the finite explicit-history
 * prefix and exactly one stable-tail period; the historic prefix is never shifted by a
 * calendar cycle and only the stable tail is closed analytically.
 *
 * <p><b>P1 - complete matched date set.</b> The parser-level match predicate (second /
 * minute / hour day-part sets plus the date predicate of day-of-month / month /
 * day-of-week, including '?', 'L', 'L-n', 'LW', 'nW', '#n' and list / range / step forms)
 * is evaluated for every local date in the covered finite prefix and tail windows. For
 * every matched date the complete day-part instant set is built from the parsed numeric
 * sets, so the day trigger grid is the full Cartesian product, never a sampled
 * subsequence.</p>
 *
 * <p><b>P2 - finite prefix plus stable tail.</b> The proof covers all future explicit
 * history transitions and the complete first stable period of the annual lastRules tail
 * (or the final fixed-offset segment when no recurring rules exist). The prefix-to-tail
 * seam is evaluated separately. A certificate is issued only for that provider
 * identity; there is no fixed reference day that could fall behind a commit base.</p>
 *
 * <p><b>P3 - absolute fixed-offset segmentation.</b> {@link ZoneWindows} tiles the
 * covered ranges contiguously along the provider's real transitions; each window has one
 * constant interior offset and its local date range is projected through that offset.
 * A matched day instant is projected once per valid window offset and clipped to the
 * window interior, so gap dates contribute zero instants, overlap dates contribute every
 * absolute copy, and every instant appears exactly once in the covered set.</p>
 *
 * <p><b>P4 - all adjacent pairs.</b> The proof compares every adjacent absolute pair:
 * inside one day, across days inside one window, across empty days/windows with the
 * preserved carry, and across the prefix-to-tail boundary. No anchor day is sampled.</p>
 *
 * <p><b>P5 - stable-tail cycle seam.</b> Only the stable annual-rules tail is closed
 * with {@code lastTrigger + CYCLE_MS - firstTrigger}: its local rule definition, offset
 * state and trigger set repeat with the 146097-day Gregorian cycle. Historical prefix
 * windows are finite and are never repeated.</p>
 *
 * <p><b>P6 - existence and minimum from one source.</b> The prefix and complete stable
 * period decide existence, first/last and the real minimum. An empty stable period
 * proves that the whole stable future is empty; the finite prefix then decides whether
 * any future trigger exists. {@code Long.MAX_VALUE} is never reported as an unproven
 * pass; an uncomputable minimum is rejected instead.</p>
 *
 * <p><b>P7 - certificate issuance and invalidation.</b> A certificate is issued only
 * after all prefix/tail seams and the minimum are computed; an algorithm change bumps
 * {@link CronCertificate#CURRENT_ALGORITHM_VERSION} and invalidates every stored
 * certificate issued by a predecessor. Validation never uses the two verify slots,
 * never mutates stored plans, and never falls back to a "looks fine" shortcut.</p>
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
        return validateRules(cronExpression, zone.getRules(), minimumMs);
    }

    /**
     * Rule-level entry used by controlled provider fixtures (task V05/V13). It is
     * package-private test observability, not a public business API: the same P1-P7
     * proof runs against the supplied {@link ZoneRules} identity.
     */
    static Validation validateRules(String cronExpression, ZoneRules rules, long minimumMs) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException exception) {
            return new Validation.Rejected("invalid cron expression: " + exception.getMessage());
        }
        long[] dayParts = CronPolicy.dayPartBitsets(cronExpression);
        DayPartGrid grid = dayParts == null ? null : new DayPartGrid(dayParts[0], dayParts[1], dayParts[2]);
        return validateInternal(expression, rules, minimumMs, grid);
    }

    /**
     * P1-P7 proof walk over the finite explicit-history prefix plus exactly one stable tail
     * period. The finite prefix and the analytic tail seam are evaluated separately; the
     * historic prefix is never shifted by a calendar cycle.
     */
    private static Validation validateInternal(
            CronExpression expression, ZoneRules rules, long minimumMs, DayPartGrid grid) {
        ZoneWindows.Coverage coverage = ZoneWindows.enumerateFullFuture(rules);
        WindowSummary prefix = scanWindowList(expression, rules, coverage.prefix(), minimumMs, grid);
        if (prefix.rejection != null) {
            return new Validation.Rejected(prefix.rejection);
        }
        WindowSummary tail = scanWindowList(expression, rules, coverage.tail(), minimumMs, grid);
        if (tail.rejection != null) {
            return new Validation.Rejected(tail.rejection);
        }
        long minimum = Long.MAX_VALUE;
        long firstTrigger = Long.MIN_VALUE;
        long lastTrigger = Long.MIN_VALUE;
        boolean anyTrigger = prefix.anyTrigger || tail.anyTrigger;
        if (prefix.anyTrigger) {
            minimum = prefix.minimum;
            firstTrigger = prefix.firstMs;
            lastTrigger = prefix.lastMs;
        }
        if (tail.anyTrigger) {
            if (!prefix.anyTrigger) {
                minimum = tail.minimum;
                firstTrigger = tail.firstMs;
                lastTrigger = tail.lastMs;
            } else {
                // Prefix -> tail seam: the last prefix trigger to the first tail trigger.
                long seam = tail.firstMs - prefix.lastMs;
                if (seam < minimumMs) {
                    return new Validation.Rejected(describeViolation(seam, prefix.lastMs));
                }
                minimum = Math.min(minimum, Math.min(prefix.minimum, Math.min(tail.minimum, seam)));
                lastTrigger = tail.lastMs;
            }
        }
        if (coverage.tailKind() == ZoneWindows.TailKind.ANNUAL_RULES && tail.anyTrigger) {
            // P5 stable-tail cycle seam: tail last non-empty trigger -> first trigger of
            // the next tail period (the same trigger shifted by exactly CYCLE_MS).
            long seam = tail.firstMs + ZoneWindows.CYCLE_MS - tail.lastMs;
            if (seam < minimumMs) {
                return new Validation.Rejected("minimum interval violated at the stable-tail cycle seam near "
                        + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(tail.lastMs), ZoneId.of("UTC"))
                        + "Z (" + seam + "ms)");
            }
            minimum = Math.min(minimum, seam);
        }
        // P6: the stable tail decides existence for the unbounded future. An empty tail
        // over one complete period proves that the whole stable future is empty - any
        // prefix triggers are finite history, never a future schedule. The fixed-offset
        // tail never empties on a matched plan (single window covers the whole future).
        if (coverage.tailKind() == ZoneWindows.TailKind.ANNUAL_RULES && !tail.anyTrigger) {
            return new Validation.Rejected("no future triggers found; the plan never fires");
        }
        if (!anyTrigger) {
            return new Validation.Rejected("no future triggers found; the plan never fires");
        }
        if (minimum == Long.MAX_VALUE) {
            // Single-trigger stable period: the cycle seam is the actual minimum and was
            // evaluated above. Fixed-offset single trigger without a cycle seam is finite
            // and is also represented by a real trigger-pair analysis below; reaching this
            // branch means the proof did not compute a finite minimum.
            return new Validation.Rejected("minimum interval could not be proven");
        }
        return new Validation.Passed(minimum);
    }

    /** Scans the given contiguous window list as one proof segment, preserving first/last/min. */
    private static WindowSummary scanWindowList(
            CronExpression expression, ZoneRules rules, List<ZoneWindows.Window> windows, long minimumMs,
            DayPartGrid grid) {
        WindowSummary summary = new WindowSummary();
        if (windows.isEmpty()) {
            return summary;
        }
        long minimum = Long.MAX_VALUE;
        long firstTrigger = Long.MIN_VALUE;
        long lastTrigger = Long.MIN_VALUE;
        boolean anyTrigger = false;
        String rejection = null;
        long carryLastMs = Long.MIN_VALUE;
        List<Long> prevWall = null;
        DayBounds prevBounds = null;
        LocalDate prevDate = null;
        long runLength = 0;
        long runInDayMin = Long.MAX_VALUE;
        long runHeadCarry = Long.MIN_VALUE;
        for (ZoneWindows.Window window : windows) {
            LocalDate current = window.startLocalDate();
            LocalDate end = window.endLocalDate();
            while (!current.isAfter(end) && rejection == null) {
                List<Long> wall;
                if (grid != null) {
                    wall = grid.dayWallListWithDateGate(expression, rules, window, current);
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
            runLength = 0;
            runInDayMin = Long.MAX_VALUE;
            prevWall = null;
            prevBounds = null;
        }
        summary.minimum = minimum;
        summary.firstMs = firstTrigger;
        summary.lastMs = lastTrigger;
        summary.anyTrigger = anyTrigger;
        summary.rejection = rejection;
        return summary;
    }

    /** Segment-local first/last/minimum summary used to close prefix, tail and cycle seams. */
    private static final class WindowSummary {
        private long minimum = Long.MAX_VALUE;
        private long firstMs = Long.MIN_VALUE;
        private long lastMs = Long.MIN_VALUE;
        private boolean anyTrigger;
        private String rejection;
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
                CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
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
            // The date matches; the complete time-of-day set applies (clipped, gap-aware).
            return dayWallListInWindowFast(rules, window, date);
        }

        /**
         * Closed-form grid resolution with run-length reuse: the grid's wall list is
         * IDENTICAL for every interior day of one window, so only the window's first and
         * last dates (where the interior clip can bite) need the O(|grid|) filter; all
         * other days share one immutable list instance. Equality still holds by
         * construction, so the run-length proof stays sound.
         */
        List<Long> dayWallListInWindowFast(ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
            if (isEmpty()) {
                return List.of();
            }
            long dayStartMs = date.atStartOfDay().toInstant(window.offset).toEpochMilli();
            // The complete-grid fast path is sound only when the WHOLE day lies inside the
            // constant-offset window; a window boundary (transition) inside the day means
            // gap/overlap walls exist and the filtered clip path must decide per wall.
            boolean fullyInside = dayStartMs >= window.startMs
                    && dayStartMs + ZoneWindows.DAY_MS <= window.endMs;
            if (fullyInside) {
                // Interior day (no interior transition in a constant-offset window): the
                // complete grid applies unclipped and every wall is a valid local time.
                if (interiorList == null) {
                    interiorList = wallList();
                }
                return interiorList;
            }
            return dayWallListInWindow(rules, window, date);
        }

        private List<Long> interiorList;

        /**
         * The wall-grid members of `date` that resolve to the window interior, as
         * ms-from-day-start. Closed form over the bit sets: for interior dates (no clip)
         * this is the full product; for the two boundary dates the members are filtered
         * by their absolute time against [startMs, endMs). O(|h|*|m|*|s|) worst case.
         */
        List<Long> dayWallListInWindow(ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
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
                            // P3: drop gap walls whose only mapping is the skipped range.
                            LocalDateTime candidate = date.atStartOfDay().plusNanos(wallMs * 1_000_000L);
                            if (!rules.getValidOffsets(candidate).contains(window.offset)) {
                                continue;
                            }
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
            // P3: gap walls contribute nothing in the before/after windows alike.
            if (rules.getValidOffsets(next).contains(window.offset)) {
                long instantMs = next.toInstant(window.offset).toEpochMilli();
                if (instantMs >= window.startMs && instantMs < window.endMs) {
                    wallMs.add(java.time.temporal.ChronoUnit.MILLIS.between(dayStart, next));
                }
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
                // P3: a wall candidate is a real trigger only when the window offset is one
                // of its valid offsets; gap walls contribute no candidate in any window.
                if (rules.getValidOffsets(next).contains(window.offset)) {
                    long instantMs = next.toInstant(window.offset).toEpochMilli();
                    if (instantMs >= window.startMs && instantMs < window.endMs) {
                        found.add(instantMs);
                    }
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
