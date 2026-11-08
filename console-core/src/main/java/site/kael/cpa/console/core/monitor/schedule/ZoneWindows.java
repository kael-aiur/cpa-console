package site.kael.cpa.console.core.monitor.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete absolute-time window enumeration for one zone rules provider, split into the
 * finite explicit history prefix and the stable recurring (or final fixed-offset) tail
 * (D4.2 P2/P3/P5). The historic window walk in one provider is NOT treated as a repeatable
 * 400-year period: historical transitions belong to the finite prefix, and only the stable
 * lastRules tail (or the final fixed offset) is closed analytically.
 *
 * <p><b>P2 - finite prefix plus stable tail.</b> {@link #enumerateFullFuture(ZoneRules)}
 * first walks the real provider transitions. JDK {@link ZoneRules} stores history in
 * {@code getTransitions()} and the recurring rules in {@code getTransitionRules()}; when the
 * rule list is non-empty, the finite prefix ends at the last stored transition and the tail
 * starts at the first transition generated from those annual rules. Both endpoints are read
 * from the provider itself (not from a calendar guess) and the code additionally verifies
 * that the last stored transition precedes the tail start. Providers without recurring
 * rules have no repeating tail; their full future is the finite final fixed-offset segment,
 * so {@link #tailKind} reports {@link TailKind#FIXED_OFFSET}.</p>
 *
 * <p><b>P3 - window shape.</b> Windows tile the covered absolute ranges contiguously with
 * one constant offset per interior. Local-date ranges are derived by projecting the window
 * boundaries through the window offset (not by UTC epoch-day arithmetic), so a positive
 * offset can extend the range before the UTC day and a negative offset can extend it after.
 * Projection code must additionally clip resolved instants to {@code [startMs, endMs)} so
 * gap dates contribute nothing and overlap dates contribute every absolute copy.</p>
 *
 * <p><b>P5 - stable-tail periodicity.</b> The JDK lastRules are local month/day/weekday
 * rules with a wall-clock time and whole-second offsets. Their generated instants therefore
 * repeat with the 146097-day Gregorian cycle, including the preceding offset state: the two
 * annual rule transitions recur at exactly +CYCLE_MS. For every trigger {@code t} of the
 * stable tail, {@code t + CYCLE_MS} is a trigger of the next tail period, and the offset
 * regime repeats with it. The cycle-to-cycle seam is therefore analytically closed from
 * {@code firstTailTrigger + CYCLE_MS - lastTailTrigger}. Historic prefix windows are never
 * shifted by CYCLE_MS. A custom provider that cannot support this structure is explicitly
 * unprovable and must not be certified (P7).</p>
 */
public final class ZoneWindows {

    /** The Gregorian calendar and weekday alignment repeat every 146097 days. */
    public static final long CYCLE_DAYS = 146_097L;
    /** Milliseconds per Gregorian repetition cycle; the stable recurring tz regime shares it. */
    public static final long CYCLE_MS = CYCLE_DAYS * 86_400_000L;
    /** One day in milliseconds. */
    public static final long DAY_MS = 86_400_000L;
    /** Absolute floor of the finite prefix walk (the Java epoch). */
    public static final long FLOOR_MS = 0L;

    /** Stable-tail classification used by the validator's analytic proof. */
    public enum TailKind {
        /** Annual lastRules repeat with the Gregorian cycle. */
        ANNUAL_RULES,
        /** No recurring rules: the future is one finite fixed-offset segment. */
        FIXED_OFFSET
    }

    /** One covered absolute range with a constant interior offset. */
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

        /** Earliest local date that can carry an interior instant of this window. */
        public LocalDate startLocalDate() {
            return LocalDate.ofInstant(Instant.ofEpochMilli(startMs), offset);
        }

        /** Latest local date that can carry an interior instant of this window. */
        public LocalDate endLocalDate() {
            return LocalDate.ofInstant(Instant.ofEpochMilli(endMs - 1), offset);
        }
    }

    /** Complete provider coverage: the finite prefix plus the stable-tail classification. */
    public static final class Coverage {
        private final List<Window> prefix;
        private final List<Window> tail;
        private final TailKind tailKind;

        Coverage(List<Window> prefix, List<Window> tail, TailKind tailKind) {
            this.prefix = List.copyOf(prefix);
            this.tail = List.copyOf(tail);
            this.tailKind = tailKind;
        }

        /** Explicit historical windows; never shifted into the tail. */
        public List<Window> prefix() {
            return prefix;
        }

        /** Stable-tail windows over one complete Gregorian period. */
        public List<Window> tail() {
            return tail;
        }

        public TailKind tailKind() {
            return tailKind;
        }

        /** Inclusive absolute start of the stable tail (equal to prefix end). */
        public long tailStartMs() {
            if (!prefix.isEmpty()) {
                return prefix.get(prefix.size() - 1).endMs;
            }
            return tail.isEmpty() ? FLOOR_MS : tail.get(0).startMs;
        }
    }

    private ZoneWindows() {
    }

    /**
     * Enumerates the complete finite prefix from the epoch floor through every stored
     * provider transition and the stable-tail windows generated from the provider's annual
     * rules over exactly one Gregorian period. For a provider without annual rules, the
     * last fixed-offset segment is the complete future and is returned as the tail with
     * {@link TailKind#FIXED_OFFSET}.
     */
    public static Coverage enumerateFullFuture(ZoneRules rules) {
        List<ZoneOffsetTransition> history = rules.getTransitions();
        List<ZoneOffsetTransitionRule> recurringRules = rules.getTransitionRules();
        List<Window> prefix = new ArrayList<>();
        List<Window> tail = new ArrayList<>();
        long cursorMs = FLOOR_MS;
        ZoneOffset offset = rules.getOffset(Instant.ofEpochMilli(cursorMs));
        for (ZoneOffsetTransition transition : history) {
            long transitionMs = transition.getInstant().toEpochMilli();
            if (transitionMs >= cursorMs) {
                prefix.add(new Window(cursorMs, transitionMs, offset));
                cursorMs = transitionMs;
                offset = transition.getOffsetAfter();
            }
        }
        if (recurringRules.isEmpty()) {
            // Fixed-offset tail: no further transitions ever occur. The covered tail range
            // is bounded to one finite Gregorian period from the prefix end so the day walk
            // terminates; because the offset never changes, calendar-periodicity extends
            // the same trigger set to the whole unbounded future.
            tail.add(new Window(cursorMs, cursorMs + CYCLE_MS, offset));
            return new Coverage(prefix, tail, TailKind.FIXED_OFFSET);
        }
        // The first annual-rule transition is generated directly from the rules. The JDK
        // returns it via nextTransition(lastHistoryInstant); this is also the stable tail T.
        ZoneOffsetTransition firstAnnual = rules.nextTransition(Instant.ofEpochMilli(cursorMs));
        if (firstAnnual == null || firstAnnual.getInstant().toEpochMilli() < cursorMs) {
            throw new IllegalStateException(
                    "zone rules provider does not expose the expected first annual transition");
        }
        long tailStartMs = firstAnnual.getInstant().toEpochMilli();
        if (tailStartMs > cursorMs) {
            prefix.add(new Window(cursorMs, tailStartMs, offset));
        }
        // Materialize exactly one stable Gregorian period [T, T+CYCLE_MS). The provider's
        // annual rules generate two transitions per rule cycle for the supported zones.
        long periodEndMs = tailStartMs + CYCLE_MS;
        long annualCursor = tailStartMs;
        ZoneOffset annualOffset = firstAnnual.getOffsetBefore();
        while (annualCursor < periodEndMs) {
            ZoneOffsetTransition next = rules.nextTransition(Instant.ofEpochMilli(annualCursor));
            if (next == null || next.getInstant().toEpochMilli() >= periodEndMs) {
                tail.add(new Window(annualCursor, periodEndMs, annualOffset));
                break;
            }
            long transitionMs = next.getInstant().toEpochMilli();
            tail.add(new Window(annualCursor, transitionMs, annualOffset));
            annualCursor = transitionMs;
            annualOffset = next.getOffsetAfter();
        }
        return new Coverage(prefix, tail, TailKind.ANNUAL_RULES);
    }

    /**
     * Legacy full-cycle view retained for package-level compatibility. It now delegates to
     * the split enumeration and concatenates the finite prefix with one stable tail period.
     */
    public static List<Window> enumerateCycle(ZoneRules rules) {
        Coverage coverage = enumerateFullFuture(rules);
        List<Window> all = new ArrayList<>(coverage.prefix());
        all.addAll(coverage.tail());
        return all;
    }

}
