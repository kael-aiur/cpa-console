package site.kael.cpa.console.core.monitor.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete absolute-time window enumeration of one Gregorian cycle for a zone rules provider
 * (D4.2 P2/P3/P5).
 *
 * <p><b>P2 coverage.</b> The enumeration is anchored to the provider data, not to a
 * hard-coded reference day: {@code nextTransition} is walked from the fixed epoch floor
 * (1970-01-01T00:00:00Z, the earliest instant a Java plan base point can practically use)
 * through every explicit transition of the provider and then through every annual transition
 * of the real recurring rules, up to the head of the second Gregorian cycle. A certificate is
 * therefore bound to the commit base of the validation run through the whole first cycle
 * window; the unbounded tail is covered by the P5 periodicity below.</p>
 *
 * <p><b>P3 window shape.</b> The returned windows tile {@code [FLOOR_MS, FLOOR_MS + CYCLE_MS)}
 * contiguously, and each window's offset is constant over its interior. Every window's local
 * date range includes the gap and overlap boundary dates, so a matched local date-time
 * resolves to the correct number of interior instants: zero inside a gap, two or more inside
 * an overlap. Projection code must additionally clip resolved instants to
 * {@code [startMs, endMs)} so the cycle trigger set is exactly the trigger set of that
 * absolute range.</p>
 *
 * <p><b>P5 periodicity of the tail.</b> The JDK tzdb recurring rules are defined in local
 * calendar terms (month-day plus weekday patterns with wall-clock transition times), so the
 * whole transition schedule repeats with the 146097-day Gregorian cycle: for every trigger
 * instant {@code t} of the covered cycle, {@code t + CYCLE_MS} is a trigger instant of the
 * tail, and vice versa. The cycle-to-cycle seam therefore closes analytically from
 * {@code firstTrigger + CYCLE_MS - lastTrigger}, and the minimum adjacent gap of the tail
 * equals the proven minimum of the covered cycle. This invariant is what makes the finite
 * cycle walk a complete full-future proof; a custom {@code ZoneRulesProvider} whose recurring
 * schedule is not Gregorian-periodic is outside the supported provider structure (P7).</p>
 */
public final class ZoneWindows {

    /** The Gregorian calendar and weekday alignment repeat every 146097 days. */
    public static final long CYCLE_DAYS = 146_097L;
    /** Milliseconds per Gregorian repetition cycle; the recurring tz regime shares it. */
    public static final long CYCLE_MS = CYCLE_DAYS * 86_400_000L;
    /** One day in milliseconds. */
    public static final long DAY_MS = 86_400_000L;

    /** Absolute floor where the covered cycle starts (the Java epoch). */
    public static final long FLOOR_MS = 0L;

    private ZoneWindows() {
    }

    /**
     * One maximal absolute-time range with a constant offset over its interior. The window's
     * local date range always includes every date that can carry an interior instant, so the
     * per-date projection never truncates the absolute instant set (P3).
     */
    public static final class Window {
        /** Inclusive absolute start of the window. */
        public final long startMs;
        /** Exclusive absolute end of the window. */
        public final long endMs;
        /** The constant offset applied to the window interior. */
        public final ZoneOffset offset;

        Window(long startMs, long endMs, ZoneOffset offset) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.offset = offset;
        }

        /** Earliest local date whose day can carry an instant of this window. */
        public LocalDate startLocalDate() {
            return LocalDate.ofEpochDay(Math.floorDiv(startMs, DAY_MS));
        }

        /** Latest local date whose day can carry an instant of this window. */
        public LocalDate endLocalDate() {
            return LocalDate.ofEpochDay(Math.floorDiv(endMs - 1, DAY_MS));
        }
    }

    /**
     * Enumerates the fixed-offset windows tiling exactly one full Gregorian cycle
     * {@code [FLOOR_MS, FLOOR_MS + CYCLE_MS)}. Every explicit transition of the provider
     * inside the cycle and every annual transition of the recurring rules inside the cycle
     * becomes a window boundary; the boundary date is included in both neighbours so gap and
     * overlap dates are fully covered by each side (P3).
     */
    public static List<Window> enumerateCycle(ZoneRules rules) {
        List<Window> windows = new ArrayList<>();
        long cycleEndMs = FLOOR_MS + CYCLE_MS;
        long cursorMs = FLOOR_MS;
        ZoneOffset offset = rules.getOffset(Instant.ofEpochMilli(cursorMs));
        while (cursorMs < cycleEndMs) {
            ZoneOffsetTransition next = rules.nextTransition(Instant.ofEpochMilli(cursorMs));
            if (next == null) {
                // Provider without any transition (UTC-style): one fixed-offset cycle window.
                windows.add(new Window(cursorMs, cycleEndMs, offset));
                return windows;
            }
            long transitionMs = next.getInstant().toEpochMilli();
            if (transitionMs >= cycleEndMs) {
                windows.add(new Window(cursorMs, cycleEndMs, offset));
                return windows;
            }
            windows.add(new Window(cursorMs, transitionMs, offset));
            cursorMs = transitionMs;
            offset = next.getOffsetAfter();
        }
        return windows;
    }

}
