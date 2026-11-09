package site.kael.cpa.console.core.monitor.schedule;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * Test-only controlled ZoneRules fixture (task v1.2-r3 V05/V13/V06): one anchor history
 * transition at the epoch (UTC -> +01:00) plus two annual lastRules - a gap back to UTC
 * at December 1 03:00 wall and the next gap to +01:00 at June 15 03:00 wall. The
 * controlled gap date never moves, so an annual-only-in-gap plan proves the empty-tail
 * rejection and projection clipping without depending on real tzdb transition-date drift.
 */
final class ControlledRulesZone {

    private ControlledRulesZone() {
    }

    /** The controlled {@link ZoneRules}: gap June 15 03:00 wall, overlap December 1 03:00 wall. */
    static ZoneRules rules() {
        ZoneOffset standard = ZoneOffset.UTC;
        ZoneOffset daylight = ZoneOffset.ofHours(1);
        return ZoneRules.of(standard, daylight, List.of(), List.of(
                ZoneOffsetTransition.of(LocalDateTime.of(1970, 1, 1, 0, 0), standard, daylight)),
                List.of(
                        ZoneOffsetTransitionRule.of(Month.JUNE, 15, null,
                                LocalTime.of(3, 0), false, ZoneOffsetTransitionRule.TimeDefinition.WALL,
                                standard, standard, daylight),
                        ZoneOffsetTransitionRule.of(Month.DECEMBER, 1, null,
                                LocalTime.of(3, 0), false, ZoneOffsetTransitionRule.TimeDefinition.WALL,
                                standard, daylight, standard)));
    }
}
