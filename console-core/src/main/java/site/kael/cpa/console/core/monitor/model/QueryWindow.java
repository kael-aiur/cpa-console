package site.kael.cpa.console.core.monitor.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Validated query window (D6.1): paired absolute ISO-8601 instants with a strict
 * {@code start < end}. Default is [as_of − 24h, as_of); custom windows carry no additional
 * business cap. All comparisons stay in UTC epoch milliseconds with a half-open upper bound.
 */
public record QueryWindow(long startMs, long endMs, boolean custom) {

    public QueryWindow {
        if (endMs <= startMs) {
            throw new IllegalArgumentException("Window end must be strictly after start");
        }
    }

    /** Default 24 h window ending at {@code asOfMs}. */
    public static QueryWindow defaultWindow(long asOfMs) {
        return new QueryWindow(asOfMs - 24L * 60L * 60L * 1000L, asOfMs, false);
    }

    /**
     * Parses a custom window from paired ISO-8601 absolute instants; both must parse,
     * start must be strictly before end, and no extra business maximum is applied.
     */
    public static QueryWindow ofCustom(String startIso, String endIso) {
        Objects.requireNonNull(startIso, "start");
        Objects.requireNonNull(endIso, "end");
        Instant start;
        Instant end;
        try {
            start = Instant.parse(startIso.trim());
            end = Instant.parse(endIso.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Window bounds must be valid ISO-8601 instants", exception);
        }
        return new QueryWindow(start.toEpochMilli(), end.toEpochMilli(), true);
    }
}
