package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.List;

/** Test helper exposing the package-private window-aware day-trigger enumeration. */
public final class IntervalValidatorForTesting {

    private IntervalValidatorForTesting() {
    }

    /** Package-private hook: one local date's absolute triggers inside a cycle window. */
    public static List<Long> dayTriggers(
            CronExpression expression, ZoneRules rules, ZoneWindows.Window window, LocalDate date) {
        return IntervalValidator.dayTriggersInWindow(expression, rules, window, date,
                java.time.Instant.ofEpochMilli(Long.MIN_VALUE));
    }

    /**
     * Convenience overload: the absolute triggers of the local date inside every cycle
     * window whose range overlaps the date's UTC span, merged and sorted. A transition
     * date lives in two windows; the merge keeps each absolute copy exactly once.
     */
    public static List<Long> dayTriggers(CronExpression expression, ZoneId zone, LocalDate date) {
        ZoneRules rules = zone.getRules();
        long dayStartMs = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        long dayEndMs = dayStartMs + 86_400_000L;
        List<Long> merged = new java.util.ArrayList<>();
        for (ZoneWindows.Window window : ZoneWindows.enumerateCycle(rules)) {
            if (window.endMs > dayStartMs && window.startMs < dayEndMs) {
                merged.addAll(IntervalValidator.dayTriggersInWindow(expression, rules, window, date,
                    java.time.Instant.ofEpochMilli(Long.MIN_VALUE)));
            }
        }
        java.util.Collections.sort(merged);
        return merged;
    }
}
