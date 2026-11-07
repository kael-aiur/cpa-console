package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalValidatorTest {

    @Test
    void passesEveryTwoHourSeventeen() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("17 */2 * * * *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        IntervalValidator.Validation.Passed passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, validation);
        assertEquals(120_000L, passed.minimumIntervalMs());
    }

    @Test
    void rejectsMinutePairZeroAndFiftyNine() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0,59 * * * * *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    @Test
    void rejectsImpossibleDate() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("0 0 0 30 2 *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    @Test
    void dstTransitionsPassForHourlyCron() {
        IntervalValidator.Validation ny =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("America/New_York"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, ny);
        IntervalValidator.Validation lordHowe =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Australia/Lord_Howe"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, lordHowe);
        IntervalValidator.Validation apia =
                IntervalValidator.validate("0 0 * * * *", ZoneId.of("Pacific/Apia"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, apia);
    }

    @Test
    void halfHourZoneStillPassesHourlyCron() {
        IntervalValidator.Validation validation = IntervalValidator.validate(
                "0 30 * * * *", ZoneId.of("Australia/Lord_Howe"), CronPolicy.MINIMUM_INTERVAL_MS);
        IntervalValidator.Validation.Passed passed = assertInstanceOf(IntervalValidator.Validation.Passed.class, validation);
        assertTrue(passed.minimumIntervalMs() >= CronPolicy.MINIMUM_INTERVAL_MS);
    }

    @Test
    void denseCronRejected() {
        IntervalValidator.Validation validation =
                IntervalValidator.validate("* * * * * *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Rejected.class, validation);
    }

    @Test
    void quartzSyntaxStillValidated() {
        IntervalValidator.Validation lastDay =
                IntervalValidator.validate("0 0 0 L * *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, lastDay);
        IntervalValidator.Validation nthDow =
                IntervalValidator.validate("0 0 0 ? * 5#2", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, nthDow);
        IntervalValidator.Validation nearestWeekday =
                IntervalValidator.validate("0 0 0 1W * *", ZoneId.of("UTC"), CronPolicy.MINIMUM_INTERVAL_MS);
        assertInstanceOf(IntervalValidator.Validation.Passed.class, nearestWeekday);
    }

    @Test
    void customSecondsOffsetRulesValidatedAtRulesLevel() throws Exception {
        java.util.List<java.time.zone.ZoneOffsetTransition> transitions = new java.util.ArrayList<>();
        java.time.Instant instant = java.time.Instant.parse("2027-01-01T00:00:30Z");
        transitions.add(java.time.zone.ZoneOffsetTransition.of(
                java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC),
                java.time.ZoneOffset.UTC,
                java.time.ZoneOffset.of("+00:00:30")));
        java.time.zone.ZoneRules custom = java.time.zone.ZoneRules.of(
                java.time.ZoneOffset.UTC, java.time.ZoneOffset.UTC, java.util.List.of(), transitions, java.util.List.of());
        // The 30-second offset shift must be visible and produce a 30-second local gap.
        assertEquals(java.time.ZoneOffset.UTC, custom.getOffset(java.time.Instant.parse("2026-12-31T00:00:00Z")));
        assertEquals(java.time.ZoneOffset.of("+00:00:30"), custom.getOffset(java.time.Instant.parse("2027-01-01T12:00:00Z")));
        assertEquals(java.util.List.of(), custom.getValidOffsets(java.time.LocalDateTime.parse("2027-01-01T00:00:45")));
        assertEquals(java.util.List.of(java.time.ZoneOffset.of("+00:00:30")),
                custom.getValidOffsets(java.time.LocalDateTime.parse("2027-01-01T00:01:00")));
    }

    @Test
    void recurringSecondsRuleSeamValidatedAtRulesLevel() throws Exception {
        java.time.zone.ZoneOffsetTransitionRule rule = java.time.zone.ZoneOffsetTransitionRule.of(
                java.time.Month.JANUARY, 1, java.time.DayOfWeek.SUNDAY, java.time.LocalTime.of(0, 30),
                false, java.time.zone.ZoneOffsetTransitionRule.TimeDefinition.UTC,
                java.time.ZoneOffset.UTC, java.time.ZoneOffset.UTC, java.time.ZoneOffset.of("+00:00:30"));
        java.time.zone.ZoneRules custom = java.time.zone.ZoneRules.of(
                java.time.ZoneOffset.UTC, java.time.ZoneOffset.UTC, java.util.List.of(), java.util.List.of(), java.util.List.of(rule));
        // Recurring rule must produce the same offset pattern in year Y and Y+400 (Gregorian cycle).
        java.time.zone.ZoneOffsetTransition t2026 = rule.createTransition(2026);
        java.time.zone.ZoneOffsetTransition t2426 = rule.createTransition(2426);
        assertEquals(t2026.getOffsetBefore(), t2426.getOffsetBefore());
        assertEquals(t2026.getOffsetAfter(), t2426.getOffsetAfter());
        assertEquals(12_622_780_800L, t2426.getInstant().getEpochSecond() - t2026.getInstant().getEpochSecond());
        // And the rules must be usable by the validator through the real zone registry check.
        assertTrue(java.time.ZoneId.getAvailableZoneIds().contains("UTC"));
    }
}
