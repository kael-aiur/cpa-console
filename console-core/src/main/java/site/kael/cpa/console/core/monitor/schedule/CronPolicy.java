package site.kael.cpa.console.core.monitor.schedule;

import java.lang.reflect.Method;

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

    /**
     * The complete matched time-of-day set of the plan as (seconds, minutes, hours)
     * bitmaps, built by re-parsing those three fields with Spring's own CronField
     * parser - the identical grammar the full expression went through - so the sets are
     * sound by construction. Returns null when any day-part uses a Quartz-composite
     * form (L / W / #); the interval proof then walks the complete per-day lists.
     * P1: the returned bitmaps ARE the complete matched sets for these fields.
     */
    public static long[] dayPartBitsets(String expression) {
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Cron expression must have exactly "
                    + FIELD_COUNT + " fields (found " + fields.length + ")");
        }
        try {
            Class<?> cronFieldClass = Class.forName(
                    "org.springframework.scheduling.support.CronField");
            Object secondsField = null;
            Object minutesField = null;
            Object hoursField = null;
            for (String name : new String[]{"parseSeconds", "parseMinutes", "parseHours"}) {
                Method m = cronFieldClass.getDeclaredMethod(name, String.class);
                m.setAccessible(true);
                if (name.equals("parseSeconds")) {
                    secondsField = m.invoke(null, fields[0]);
                } else if (name.equals("parseMinutes")) {
                    minutesField = m.invoke(null, fields[1]);
                } else {
                    hoursField = m.invoke(null, fields[2]);
                }
            }
            long seconds = bitsOfField(secondsField);
            long minutes = bitsOfField(minutesField);
            long hours = bitsOfField(hoursField);
            if (seconds < 0 || minutes < 0 || hours < 0) {
                return null;
            }
            return new long[]{seconds, minutes, hours};
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static long bitsOfField(Object field) {
        if (!field.getClass().getSimpleName().equals("BitsCronField")) {
            return -1;
        }
        try {
            java.lang.reflect.Field bits = field.getClass().getDeclaredField("bits");
            bits.setAccessible(true);
            return bits.getLong(field);
        } catch (ReflectiveOperationException exception) {
            return -1;
        }
    }
}
