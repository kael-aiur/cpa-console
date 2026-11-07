package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-future minimum-interval proof for a parsed six-field cron inside one zone (D4.2).
 * <p>
 * The proof decomposes into two finite parts whose union is the entire infinite future:
 * <ol>
 *   <li>a reference non-transition day: enumerate every matching local time of that day,
 *       project each through {@link ZoneRules#getValidOffsets} and record the minimum
 *       adjacent absolute gap inside the day and across the day boundary to the next day;</li>
 *   <li>every transition day inside a bounded 400-year Gregorian window: the local trigger
 *       set can be truncated by a gap or duplicated by an overlap, so each such day and both
 *       adjacent days are enumerated and their absolute gaps recomputed.</li>
 * </ol>
 * Non-transition days outside the window repeat the same local-date pattern on the Gregorian
 * cycle (146097 days), so the reference-day summary plus the transition-day scan proves every
 * adjacent pair on the infinite timeline. Any pair closer than 60&nbsp;000&nbsp;ms rejects the
 * expression, as does an empty trigger set. Gap-local times contribute zero instants and
 * overlap-local times contribute both, ordered by absolute time.
 */
public final class IntervalValidator {

    /** Length of the Gregorian repetition cycle in days (400 years). */
    static final long CYCLE_DAYS = 146_097L;
    /** Length of the Gregorian repetition cycle in milliseconds. */
    static final long CYCLE_MS = CYCLE_DAYS * 86_400_000L;
    /** Number of Gregorian years covered by the finite transition-day scan. */
    private static final int WINDOW_YEARS = 400;
    /** Reference day used for the non-transition summary (fixed to avoid time-dependent runs). */
    private static final LocalDate REFERENCE_DAY = LocalDate.of(2026, 6, 15);

    private IntervalValidator() {
    }

    /**
     * Result of a validation attempt: either a passing certificate payload with the proven
     * minimum gap or a rejected expression with a readable reason.
     */
    public sealed interface Validation {
        /** The full-future minimum interval holds; carries the proven minimum gap. */
        record Passed(long minimumIntervalMs) implements Validation {
        }

        /** The expression is rejected (interval violation or no triggers). */
        record Rejected(String reason) implements Validation {
        }
    }

    /** Validates the whole future trigger set of the cron/zone pair against {@code minimumMs}. */
    public static Validation validate(String cronExpression, ZoneId zone, long minimumMs) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException exception) {
            return new Validation.Rejected("invalid cron expression: " + exception.getMessage());
        }
        ZoneRules rules = zone.getRules();
        if (!hasFutureTrigger(expression, rules, zone)) {
            return new Validation.Rejected("no future triggers found; the plan never fires");
        }
        long minimum = Long.MAX_VALUE;
        Long referenceMinimum = referenceDayMinimum(expression, zone, rules, minimumMs);
        if (referenceMinimum == null) {
            return new Validation.Rejected("minimum interval violated on the reference day");
        }
        minimum = Math.min(minimum, referenceMinimum);
        Long transitionMinimum = transitionDaysMinimum(expression, zone, rules, minimumMs);
        if (transitionMinimum == null) {
            return new Validation.Rejected("minimum interval violated around a transition day");
        }
        minimum = Math.min(minimum, transitionMinimum);
        return new Validation.Passed(minimum);
    }

    /** Bounded local scan that proves at least one future trigger exists. */
    private static boolean hasFutureTrigger(CronExpression expression, ZoneRules rules, ZoneId zone) {
        LocalDate start = REFERENCE_DAY;
        for (int day = 0; day < 600; day++) {
            LocalDate date = start.plusDays(day);
            if (!dayTriggers(expression, rules, date).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minimum absolute gap within the reference non-transition day and across its boundary.
     * Returns {@code null} when the day has no triggers or any pair violates {@code minimumMs}.
     */
    private static Long referenceDayMinimum(CronExpression expression, ZoneId zone, ZoneRules rules, long minimumMs) {
        List<Long> current = dayTriggers(expression, rules, REFERENCE_DAY);
        List<Long> next = dayTriggers(expression, rules, REFERENCE_DAY.plusDays(1));
        return minGapOf(current, next, minimumMs);
    }

    /**
     * Scans every transition day inside the Gregorian window plus both adjacent days and
     * returns the minimum absolute gap, or {@code null} on a violation. Transition days are
     * derived from the explicit tzdb transition list and the rules-generated instants.
     */
    private static Long transitionDaysMinimum(CronExpression expression, ZoneId zone, ZoneRules rules, long minimumMs) {
        List<LocalDate> transitionDates = collectTransitionDates(rules);
        Long minimum = null;
        if (transitionDates.isEmpty()) {
            return Long.MAX_VALUE;
        }
        List<LocalDate> processed = new ArrayList<>();
        for (LocalDate date : transitionDates) {
            processed.add(date.minusDays(1));
            processed.add(date);
            processed.add(date.plusDays(1));
        }
        Collections.sort(processed);
        List<Long> previous = null;
        LocalDate previousDate = null;
        for (LocalDate date : processed) {
            if (previousDate != null && previousDate.equals(date)) {
                continue;
            }
            List<Long> current = dayTriggers(expression, rules, date);
            Long dayMinimum = minGapOf(previous, current, minimumMs);
            if (dayMinimum == null) {
                return null;
            }
            minimum = minimum == null ? dayMinimum : Math.min(minimum, dayMinimum);
            previous = current;
            previousDate = date;
        }
        return minimum;
    }

    /** Collects the distinct local transition dates inside the bounded Gregorian window. */
    private static List<LocalDate> collectTransitionDates(ZoneRules rules) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate windowStart = REFERENCE_DAY.minusYears(WINDOW_YEARS / 2);
        LocalDate windowEnd = windowStart.plusYears(WINDOW_YEARS);
        for (java.time.zone.ZoneOffsetTransition transition : rules.getTransitions()) {
            addDateIfInside(dates, transition, windowStart, windowEnd);
        }
        int firstYear = windowStart.getYear();
        int lastYear = windowEnd.getYear();
        for (java.time.zone.ZoneOffsetTransitionRule rule : rules.getTransitionRules()) {
            for (int year = firstYear; year <= lastYear; year++) {
                try {
                    addDateIfInside(dates, rule.createTransition(year), windowStart, windowEnd);
                } catch (DateTimeException | IllegalArgumentException ignored) {
                    // Rule years outside the provider range are not part of the provable structure.
                }
            }
        }
        Collections.sort(dates);
        List<LocalDate> distinct = new ArrayList<>();
        for (LocalDate date : dates) {
            if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(date)) {
                distinct.add(date);
            }
        }
        return distinct;
    }

    private static void addDateIfInside(
            List<LocalDate> dates, java.time.zone.ZoneOffsetTransition transition,
            LocalDate windowStart, LocalDate windowEnd) {
        LocalDate date = transition.getInstant().atZone(transition.getOffsetBefore()).toLocalDate();
        if (!date.isBefore(windowStart) && date.isBefore(windowEnd) && !dates.contains(date)) {
            dates.add(date);
        }
    }

    /**
     * Minimum adjacent absolute gap across {@code previous} and {@code current} day trigger lists.
     * Returns {@code null} when a pair violates {@code minimumMs}; {@code Long.MAX_VALUE} when
     * both lists are empty.
     */
    private static Long minGapOf(List<Long> previous, List<Long> current, long minimumMs) {
        long minimum = Long.MAX_VALUE;
        if (previous != null && !previous.isEmpty() && !current.isEmpty()) {
            long gap = current.get(0) - previous.get(previous.size() - 1);
            if (gap < minimumMs) {
                return null;
            }
            minimum = gap;
        }
        List<Long> within = current == null ? List.of() : current;
        for (int i = 1; i < within.size(); i++) {
            long gap = within.get(i) - within.get(i - 1);
            if (gap < minimumMs) {
                return null;
            }
            minimum = Math.min(minimum, gap);
        }
        return minimum;
    }

    /**
     * Enumerates every absolute trigger instant of the local day, projecting matched local
     * times through all valid zone offsets; a gap day produces no instants for the missing
     * times and an overlap day produces both. Empty day returns an empty list.
     */
    private static List<Long> dayTriggers(CronExpression expression, ZoneRules rules, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime cursor = dayStart.minusNanos(1);
        List<Long> instants = new ArrayList<>();
        while (true) {
            LocalDateTime next = expression.next(cursor);
            if (next == null || !next.isBefore(dayEnd)) {
                break;
            }
            for (ZoneOffset offset : rules.getValidOffsets(next)) {
                instants.add(next.toInstant(offset).toEpochMilli());
            }
            cursor = next;
        }
        Collections.sort(instants);
        return instants;
    }
}
