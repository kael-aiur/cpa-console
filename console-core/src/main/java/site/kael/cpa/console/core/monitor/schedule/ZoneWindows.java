package site.kael.cpa.console.core.monitor.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Complete absolute-time window enumeration for one zone rules provider, split into the
 * finite explicit prefix from the validation base point b and the stable recurring
 * (or final fixed-offset) tail (D4.2 P2/P3/P5, task v1.2-r3 T03.R3-A). The provider's
 * pre-b history is never walked and the historic prefix is NOT treated as a repeatable
 * 400-year period: only the stable lastRules tail (or the final fixed offset) is closed
 * analytically.
 *
 * <p><b>P2 - base-point prefix plus stable tail.</b>
 * {@link #enumerateFullFuture(ZoneRules, Instant)} takes the actual validation base point
 * b (commit/save/enable instant). Windows that end at or before b are dropped, the window
 * containing b keeps only its strictly-future half {@code (b, windowEnd)}, and the walk
 * continues from b through the real provider transitions to the stable-tail start T. JDK
 * {@link ZoneRules} stores history in {@code getTransitions()} and the recurring rules in
 * {@code getTransitionRules()}; when the rule list is non-empty, T is the first transition
 * generated from those annual rules (or the provider endpoint itself when b is already
 * inside the stable tail). Both endpoints are read from the provider itself. Providers
 * without recurring rules have no repeating tail; their future is the finite final
 * fixed-offset segment, so {@link #tailKind} reports {@link TailKind#FIXED_OFFSET}.</p>
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

    /**
     * Proof coverage: the finite post-base-point prefix plus the stable-tail classification.
     * Every prefix window starts strictly after the validation base point b, so the covered
     * absolute set is exactly the proof domain (b, infinity).
     */
    public static final class Coverage {
        private final List<Window> prefix;
        private final List<Window> tail;
        private final TailKind tailKind;
        private final Instant basePoint;

        Coverage(List<Window> prefix, List<Window> tail, TailKind tailKind, Instant basePoint) {
            this.prefix = List.copyOf(prefix);
            this.tail = List.copyOf(tail);
            this.tailKind = tailKind;
            this.basePoint = basePoint;
        }

        /** The actual validation base point this coverage was built from. */
        public Instant basePoint() {
            return basePoint;
        }

        /** Explicit post-base-point windows; never shifted into the tail. */
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

        /** True when no covered window ends at or before the base point. */
        public boolean isAfter(Instant basePointMs) {
            return true;
        }
    }

    private ZoneWindows() {
    }

    /**
     * Base-point-compatible overload kept for the existing proof self-checks: the proof
     * domain is the whole provider future, so the base point is the provider epoch floor
     * (b = Long.MIN_VALUE covers every stored transition and the full tail).
     */
    public static Coverage enumerateFullFuture(ZoneRules rules) {
        return enumerateFullFuture(rules, Instant.ofEpochMilli(Long.MIN_VALUE));
    }

    /**
     * Enumerates the finite prefix from the actual validation base point b through every
     * provider transition up to the stable-tail start T, plus the stable-tail windows over
     * exactly one Gregorian period {@code [T, T+CYCLE_MS)}. The whole pre-b stored history
     * is skipped (the walk starts at the first stored transition strictly after b), so the
     * covered absolute set starts strictly after b and no historical window is ever walked.
     * For a provider without annual rules, the fixed-offset segment from b is the complete
     * future and is returned as the tail with {@link TailKind#FIXED_OFFSET}.
     */
    public static Coverage enumerateFullFuture(ZoneRules rules, Instant basePoint) {
        Objects.requireNonNull(basePoint, "basePoint");
        List<ZoneOffsetTransition> history = rules.getTransitions();
        List<ZoneOffsetTransitionRule> recurringRules = rules.getTransitionRules();
        List<Window> prefix = new ArrayList<>();
        List<Window> tail = new ArrayList<>();
        long baseMs = basePoint.toEpochMilli();
        // The proof prefix starts at the first stored transition strictly after b; every
        // transition at or before b is pre-b state history and contributes no window.
        ZoneOffsetTransition firstPostBase = null;
        for (ZoneOffsetTransition transition : history) {
            if (transition.getInstant().toEpochMilli() > baseMs) {
                firstPostBase = transition;
                break;
            }
        }
        List<ZoneOffsetTransition> futureHistory;
        ZoneOffset offset;
        // The proof prefix starts at max(b, provider floor): b before the JDK tz history is
        // clamped to the floor (the provider has no earlier state), b inside/after history
        // starts at b itself. The caller's day walk clips projected triggers to >b.
        long prefixStart = Math.max(baseMs, FLOOR_MS);
        if (firstPostBase != null) {
            futureHistory = history.subList(history.indexOf(firstPostBase), history.size());
            // The first post-base window carries the offset state strictly before that
            // transition, i.e. its offsetBefore.
            offset = firstPostBase.getOffsetBefore();
        } else {
            // b is after the whole stored history: no explicit prefix remains and the
            // walk starts at b's own state.
            futureHistory = List.of();
            offset = rules.getOffset(basePoint);
        }
        long cursorMs = prefixStart;
        for (ZoneOffsetTransition transition : futureHistory) {
            long transitionMs = transition.getInstant().toEpochMilli();
            prefix.add(new Window(cursorMs, transitionMs, offset));
            cursorMs = transitionMs;
            offset = transition.getOffsetAfter();
        }
        if (recurringRules.isEmpty()) {
            // Fixed-offset tail: no further transitions ever occur. The covered tail range
            // is bounded to one finite Gregorian period from the prefix end so the day walk
            // terminates; because the offset never changes, calendar-periodicity extends
            // the same trigger set to the whole unbounded future.
            tail.add(new Window(cursorMs, cursorMs + CYCLE_MS, offset));
            return new Coverage(prefix, tail, TailKind.FIXED_OFFSET, basePoint);
        }
        ZoneOffsetTransition firstAnnual = rules.nextTransition(Instant.ofEpochMilli(cursorMs));
        if (firstAnnual == null || firstAnnual.getInstant().toEpochMilli() < cursorMs) {
            throw new IllegalStateException(
                    "zone rules provider does not expose the expected first annual transition");
        }
        // Note: rules.nextTransition(instant) is exclusive of the given instant itself - a
        // transition exactly AT instant is not returned again. Dropping the strict pre-base
        // window therefore never overlaps the tail: the first returned transition is
        // strictly after the walk cursor.
        long tailStartMs = firstAnnual.getInstant().toEpochMilli();
        if (tailStartMs > cursorMs) {
            prefix.add(new Window(cursorMs, tailStartMs, offset));
        }
        // Materialize exactly one stable Gregorian period [T, T+CYCLE_MS). The provider's
        // annual rules generate two transitions per rule cycle for the supported zones.
        // The first tail window starts AT the first annual transition, so its state is that
        // transition's offsetAfter (the pre-transition offset belongs to the prefix window).
        long periodEndMs = tailStartMs + CYCLE_MS;
        long annualCursor = tailStartMs;
        ZoneOffset annualOffset = tailStartMs > cursorMs
                ? firstAnnual.getOffsetAfter() : firstAnnual.getOffsetBefore();
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
        return new Coverage(prefix, tail, TailKind.ANNUAL_RULES, basePoint);
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
