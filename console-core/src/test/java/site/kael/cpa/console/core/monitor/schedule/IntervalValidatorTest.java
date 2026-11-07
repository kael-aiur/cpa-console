package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalValidatorTest {

    private static final long MIN_MS = CronPolicy.MINIMUM_INTERVAL_MS;

    @Test
    void passesEveryTwoHourSeventeen() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("17 */2 * * * *", ZoneId.of("UTC"), MIN_MS);
        IntervalValidator.Validation.Passed passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, validation);
        assertEquals(120_000L, passed.minimumIntervalMs());
    }

    @Test
    void rejectsMinutePairZeroAndFiftyNine() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0,59 * * * * *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    @Test
    void rejectsImpossibleDate() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0 0 0 30 2 *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    /** B-CRON-1 regression: 59→00 across the minute boundary is only 1 s apart on Wednesdays. */
    @Test
    void rejectsWeekdayMinuteBoundaryGapBelowSixtySeconds() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0,59 * * * * WED", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    /** B-CRON-2 regression: 29 February is rare but valid, so it must not be rejected. */
    @Test
    void certifiesLeapDayPlan() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0 0 0 29 2 *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, validation);
    }

    @Test
    void dstTransitionsPassForHourlyCron() {
        IntervalValidator.Validation ny =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("America/New_York"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, ny);
        IntervalValidator.Validation lordHowe =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Australia/Lord_Howe"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, lordHowe);
        IntervalValidator.Validation apia =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Pacific/Apia"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, apia);
    }

    @Test
    void halfHourZoneStillPassesHourlyCron() {
        IntervalValidator.Validation validation = IntervalValidator.validate(
                "0 30 * * * *", ZoneId.of("Australia/Lord_Howe"), MIN_MS);
        IntervalValidator.Validation.Passed passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, validation);
        assertTrue(passed.minimumIntervalMs() >= MIN_MS);
    }

    @Test
    void denseCronRejected() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("* * * * * *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    @Test
    void quartzSyntaxStillValidated() {
        IntervalValidator.Validation lastDay =
                IntervalValidator.validate("0 0 0 L * *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, lastDay);
        IntervalValidator.Validation nthDow =
                IntervalValidator.validate("0 0 0 ? * 5#2", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, nthDow);
        IntervalValidator.Validation nearestWeekday =
                IntervalValidator.validate("0 0 0 1W * *", ZoneId.of("UTC"), MIN_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, nearestWeekday);
    }

    /**
     * Small exhaustive oracle: 24 simulated hours on a spring-forward day compared against
     * a naive reference iterator, proving the day-level trigger enumeration is correct.
     */
    @Test
    void oracleExhaustiveSmallRangeMatches() {
        ZoneId zone = ZoneId.of("America/New_York");
        // Exhaustive oracle on the spring-forward day: dayTriggers output must equal the
        // filtered output of the independent ScheduleIterator for the same local date.
        java.time.LocalDate gapDate = java.time.LocalDate.of(2026, 3, 8);
        ScheduleIterator gapIterator = ScheduleIterator.of("0 * * * * *", zone.getId());
        List<Long> gapExpected = oracleLocalDateTriggers(gapIterator, zone, gapDate);
        List<Long> gapActual = IntervalValidatorForTesting.dayTriggers(
                org.springframework.scheduling.support.CronExpression.parse("0 * * * * *"),
                zone.getRules(), gapDate);
        assertEquals(gapExpected, gapActual,
                "dayTriggers must match the independent iterator oracle on the gap day");
        // The overlap day must carry both offset copies (01:00-01:59 local appears twice).
        java.time.LocalDate overlapDate = java.time.LocalDate.of(2026, 11, 1);
        List<Long> overlap = IntervalValidatorForTesting.dayTriggers(
                org.springframework.scheduling.support.CronExpression.parse("0 * * * * *"),
                zone.getRules(), overlapDate);
        assertEquals(1500, overlap.size(),
                "overlap day should carry 1440 normal + 60 duplicate overlap members: got "
                        + overlap.size());
        assertEquals(java.time.Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), overlap.get(0));
        assertEquals(java.time.Instant.parse("2026-11-01T04:59:00Z").toEpochMilli(), overlap.get(59));
    }

    /** Independent oracle: enumerates all absolute triggers of the given local date via the iterator. */
    private static List<Long> oracleLocalDateTriggers(
            ScheduleIterator iterator, ZoneId zone, java.time.LocalDate date) {
        java.time.Instant start = date.minusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.time.Instant end = date.plusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.util.TreeMap<Long, Boolean> allTriggers = new java.util.TreeMap<>();
        java.time.Instant cursor = start;
        java.time.Instant next = iterator.next(cursor);
        while (next != null && next.isBefore(end)) {
            allTriggers.put(next.toEpochMilli(), Boolean.TRUE);
            cursor = next;
            next = iterator.next(cursor);
        }
        return allTriggers.keySet().stream()
                .filter(ms -> java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(ms), zone).toLocalDate().equals(date))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void calendarPollutionAcrossCronFieldsIsDetected() {
        // Each case encodes whether any pair of triggers is closer than 60 s.
        // 0,59 in the minute field within the same hour = 59→60 = 1s apart (unless the hour
        // is bounded so 59 doesn't precede 00 of the next hour).
        Map<String, Boolean> expectations = Map.of(
                "0,59 * * * * MON", false,
                "0,59 * * * * 1", false,
                "0 59,0 * * * *", true,
                "0 59 * * * *", true,
                "0,59 12 * * * *", false,
                "*/30 * * * * *", false);
        for (Map.Entry<String, Boolean> expectation : expectations.entrySet()) {
            IntervalValidator.Validation validation =
                    IntervalValidator.validate(expectation.getKey(), ZoneId.of("UTC"), MIN_MS);
            if (expectation.getValue()) {
                assertInstanceOf(IntervalValidator.Validation.Passed.class, validation,
                        "expected PASS for: " + expectation.getKey());
            } else {
                assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation,
                        "expected REJECT for: " + expectation.getKey());
            }
        }
    }
}
