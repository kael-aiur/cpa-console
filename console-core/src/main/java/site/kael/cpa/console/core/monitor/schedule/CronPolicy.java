package site.kael.cpa.console.core.monitor.schedule;

import java.time.DateTimeException;
import java.time.ZoneId;

/** Shared D4 cron policy constants and entry checks used by the iterator and the validator. */
public final class CronPolicy {

    /** Six fields: second minute hour day-of-month month day-of-week. */
    public static final int FIELD_COUNT = 6;

    /** Any two consecutive triggers must be at least this far apart in absolute time. */
    public static final long MINIMUM_INTERVAL_MS = 60_000L;

    private CronPolicy() {
    }

    /** Rejects the documented D4 shapes: five fields, extra year field, Quartz macros. */
    public static void rejectUnsupportedShape(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron expression must not be empty");
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("@")) {
            throw new IllegalArgumentException("Quartz @ macros are not supported: " + describe(expression));
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "Cron expression must have exactly " + FIELD_COUNT + " fields (found " + fields.length + ")");
        }
    }

    /** Zone validation: only resolvable IANA ids are accepted; UTC is the documented default. */
    public static ZoneId validateZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("Zone id must not be empty");
        }
        try {
            ZoneId parsed = ZoneId.of(zoneId.trim());
            if (parsed.getId().startsWith("+") || parsed.getId().startsWith("-")) {
                throw new IllegalArgumentException("Only IANA zone ids are allowed, got: " + zoneId);
            }
            return parsed;
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Unknown zone id: " + zoneId, exception);
        }
    }

    private static String describe(String expression) {
        return expression.length() <= 64 ? expression : expression.substring(0, 61) + "...";
    }
}
