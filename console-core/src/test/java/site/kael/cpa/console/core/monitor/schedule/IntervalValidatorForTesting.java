package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.List;

/** Test helper exposing the package-private day-trigger enumeration of the validator. */
public final class IntervalValidatorForTesting {

    private IntervalValidatorForTesting() {
    }

    public static List<Long> dayTriggers(CronExpression expression, ZoneRules rules, LocalDate date) {
        return IntervalValidator.dayTriggers(expression, rules, date);
    }

    /** Convenience overload resolving the rules from the zone. */
    public static List<Long> dayTriggers(CronExpression expression, ZoneId zone, LocalDate date) {
        return IntervalValidator.dayTriggers(expression, zone.getRules(), date);
    }
}
