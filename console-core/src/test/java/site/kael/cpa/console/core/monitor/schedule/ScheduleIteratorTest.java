package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleIteratorTest {

    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void parsesSupportedSixFieldSyntax() {
        ScheduleIterator iterator = ScheduleIterator.of("17 */2 * * * *", "UTC");
        Instant first = iterator.next(UTC_CLOCK.instant());
        assertNotNull(first);
        LocalDateTime local = LocalDateTime.ofInstant(first, ZoneOffset.UTC);
        assertEquals(17, local.getSecond());
        assertEquals(0, local.getMinute());
        assertTrue(local.getHour() % 2 == 0, "hour must be on the */2 grid: " + local.getHour());
    }

    @Test
    void rejectsFiveFieldsAndMacros() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 * *", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("@daily", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 1 1 * 2026", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("", "UTC"));
    }

    @Test
    void rejectsIllegalZone() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 * * *", "Not/AZone"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 * * *", "+05:00"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 * * *", ""));
        assertThrows(IllegalArgumentException.class, () -> ScheduleIterator.of("0 0 0 * * *", null));
    }

    @Test
    void rejectsMinimumIntervalViolationViaValidator() {
        // Seconds 0 and 59 give a 1 s adjacent gap; only the certificate validator rejects the plan.
        assertThrows(IllegalArgumentException.class,
                () -> new CronPolicyValidator(java.time.Clock.systemUTC()).certify("0,59 * * * * *", "UTC"));
    }

    @Test
    void gapLocalTimeProducesZeroMatches() {
        // America/New_York 2026 spring forward: 02:00 -> 03:00 local missing.
        ScheduleIterator hourly = ScheduleIterator.of("0 0 2 * * *", "America/New_York");
        Instant before = Instant.parse("2026-03-08T05:00:00Z"); // 00:00 EST
        Instant next = hourly.next(before);
        assertNotNull(next);
        // Next 02:00 must be March 9, not March 8 (gap skipped).
        LocalDateTime local = LocalDateTime.ofInstant(next, ZoneId.of("America/New_York"));
        assertEquals(2, local.getHour());
        assertEquals(9, local.getDayOfMonth());
        assertEquals(3, local.getMonthValue());
    }

    @Test
    void overlapLocalTimeProducesTwoSortedMatches() {
        // America/New_York 2026 fall back: 01:30 occurs twice (EDT and EST).
        ScheduleIterator halfPastOne = ScheduleIterator.of("0 30 1 * * *", "America/New_York");
        Instant before = Instant.parse("2026-10-31T12:00:00Z");
        List<Instant> triggers = halfPastOne.next(before, 4);
        assertEquals(4, triggers.size());
        for (int i = 1; i < triggers.size(); i++) {
            assertTrue(triggers.get(i).isAfter(triggers.get(i - 1)), "must be sorted");
        }
        // The first pair should be the two occurrences of 01:30 on November 1.
        LocalDateTime edt = LocalDateTime.ofInstant(triggers.get(0), ZoneOffset.ofHours(-4));
        LocalDateTime est = LocalDateTime.ofInstant(triggers.get(1), ZoneOffset.ofHours(-5));
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), edt);
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), est);
    }

    @Test
    void fiftyNineAndSixtySecondsRejected() {
        // 0 and 59 in the minute field gives a 60 s gap, which is allowed; the second field pair
        // 0,59 would give a 1 s gap but the *minute* field is what matters here.
        ScheduleIterator ok = ScheduleIterator.of("0 0 * * * *", "UTC");
        assertNotNull(ok.next(UTC_CLOCK.instant()));
        assertThrows(IllegalArgumentException.class,
                () -> new CronPolicyValidator(java.time.Clock.systemUTC()).certify("0,59 * * * * *", "UTC"));
    }

    @Test
    void fixedClockAdvanceIsStrictlyIncreasing() {
        ScheduleIterator everyMinute = ScheduleIterator.of("0 * * * * *", "UTC");
        Instant cursor = Instant.parse("2026-01-01T00:00:00Z");
        Instant previous = null;
        for (int i = 0; i < 10; i++) {
            Instant next = everyMinute.next(cursor);
            assertNotNull(next);
            assertTrue(next.isAfter(cursor));
            if (previous != null) {
                assertTrue(Duration.between(previous, next).toMillis() >= CronPolicy.MINIMUM_INTERVAL_MS);
            }
            previous = next;
            cursor = next;
        }
    }

    @Test
    void matchesRecognisesExactSecond() {
        ScheduleIterator everySecondSeventeen = ScheduleIterator.of("17 * * * * *", "UTC");
        Instant matching = Instant.parse("2026-06-01T00:00:17Z");
        Instant notMatching = Instant.parse("2026-06-01T00:00:18Z");
        assertTrue(everySecondSeventeen.matches(matching));
        assertFalse(everySecondSeventeen.matches(notMatching));
    }

    @Test
    void secondOfFebruaryThirtiethHasNoNext() {
        // Feb 30 never exists: the cron "0 0 0 30 2 *" never fires.
        ScheduleIterator impossible = ScheduleIterator.of("0 0 0 30 2 *", "UTC");
        assertNull(impossible.next(UTC_CLOCK.instant()));
    }
}
