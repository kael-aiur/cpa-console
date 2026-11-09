package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified cron matcher and history-independent Instant iterator (D4, task.md 8.4 / T03.R3-B).
 *
 * <p>Parsing delegates to the Spring six-field parser so that naming conventions, ranges,
 * steps, '?' / 'L' / 'W' / '#' and the DOM/DOW conjunction semantics stay identical between
 * preview and runtime iteration. Matching and next-time resolution always use this class;
 * callers must never rely on Spring's {@link CronExpression#next} alone, because Spring
 * folds overlapping local times and silently drops gap times.</p>
 *
 * <p><b>Complete earliest-future candidate set.</b> {@link #next(Instant)} returns</p>
 * <pre>
 * min { local.toInstant(offset)
 *       | cronMatches(local), offset in zoneRules.getValidOffsets(local),
 *         local.toInstant(offset) &gt; after }
 * </pre>
 * <p>or null exactly when that set is empty.</p>
 *
 * <p>The forward walk enumerates matching local times strictly after wall(after). That is
 * sufficient for ordinary wall-clock sequences, but not for the overlap block containing
 * wall(after): when the base point is on the first absolute copy, second copies of EARLIER
 * wall times can still be strictly after after (for example New York 17 * * * * * after
 * 05:59:30Z must return the 01:00:17 EST copy at 06:00:17Z, not the next wall candidate at
 * 07:00:17Z). Therefore the transition that produced this overlap is read from the real
 * ZoneRules: its offset pair gives the block's absolute size and its instant gives the
 * block's first local wall time. Every matching local time from that transition-derived
 * block start through wall(after) is enumerated, resolved through all valid offsets, and
 * compared by absolute Instant with the forward set. Gap local times naturally produce no
 * copies; overlap local times contribute every absolute copy; seconds, non-hour/half-hour
 * deltas, cross-midnight transitions and nanosecond base points are handled by the same
 * candidate model. There is no 120/600/7200 cap, no wall-time alignment, no history and no
 * guessed non-existence: the forward walk ending only proves absence after the full overlap
 * completion has also been empty.</p>
 */
public final class ScheduleIterator {

    private final CronExpression expression;
    private final ZoneId zone;
    private final ZoneRules rules;
    private final String cronText;

    private ScheduleIterator(CronExpression expression, ZoneId zone, String cronText) {
        this.expression = expression;
        this.zone = zone;
        this.rules = zone.getRules();
        this.cronText = cronText;
    }

    /** Parses and validates the cron expression and zone according to the D4 grammar. */
    public static ScheduleIterator of(String cronExpression, String zoneId) {
        CronPolicy.rejectUnsupportedShape(cronExpression);
        ZoneId zone = CronPolicy.validateZone(zoneId);
        try {
            return new ScheduleIterator(CronExpression.parse(cronExpression.trim()), zone, cronExpression.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid cron expression: " + exception.getMessage(), exception);
        }
    }

    /**
     * True when the given absolute instant matches the cron under the configured zone rules.
     * Both absolute copies of an overlap match; instants inside a gap never do.
     */
    public boolean matches(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        if (!expressionMatches(local)) {
            return false;
        }
        return rules.getValidOffsets(local).contains(rules.getOffset(instant));
    }

    /**
     * First matching absolute instant strictly after {@code after}, or {@code null} when the
     * complete candidate space is exhausted. The result never depends on iterator history:
     * the same {@code after} always produces the same answer from the same instance, from a
     * fresh one, and inside a batch walk (task.md 8.4.3).
     */
    public Instant next(Instant after) {
        Objects.requireNonNull(after, "after");
        LocalDateTime wall = LocalDateTime.ofInstant(after, zone);
        Instant backfill = earliestOverlapBackfill(wall, after);
        Instant forward = earliestForward(wall, after);
        if (backfill == null) {
            return forward;
        }
        if (forward == null || backfill.isBefore(forward)) {
            return backfill;
        }
        return forward;
    }

    /** Collects the next {@code count} matching absolute instants strictly after {@code after}. */
    public List<Instant> next(Instant after, int count) {
        if (count <= 0) {
            return List.of();
        }
        List<Instant> result = new ArrayList<>(count);
        Instant cursor = after;
        for (int i = 0; i < count; i++) {
            Instant next = next(cursor);
            if (next == null) {
                break;
            }
            result.add(next);
            cursor = next;
        }
        return List.copyOf(result);
    }

    /**
     * Earliest absolute candidate from matching wall times in the overlap block at or before
     * wall(after). The block bounds come from the real transition that maps the block's two
     * extreme offsets onto each other; no fixed two-hour/minute-grid approximation is used.
     */
    private Instant earliestOverlapBackfill(LocalDateTime wall, Instant after) {
        List<ZoneOffset> offsets = rules.getValidOffsets(wall);
        if (offsets.size() < 2) {
            return null;
        }
        ZoneOffset firstOffset = offsets.get(0);
        ZoneOffset lastOffset = offsets.get(offsets.size() - 1);
        // A one-nanosecond guard is required when wall(after)'s late copy is exactly the
        // transition instant; previousTransition must still report that transition.
        ZoneOffsetTransition transition = rules.previousTransition(
                wall.toInstant(lastOffset).plusNanos(1));
        if (transition == null
                || !transition.getOffsetBefore().equals(firstOffset)
                || !transition.getOffsetAfter().equals(lastOffset)) {
            return null;
        }
        LocalDateTime blockStart = LocalDateTime.ofInstant(transition.getInstant(), lastOffset);
        if (blockStart.isAfter(wall)) {
            return null;
        }
        Instant earliest = null;
        LocalDateTime cursor = blockStart.minusNanos(1);
        while (true) {
            LocalDateTime candidateWall = expression.next(cursor);
            if (candidateWall == null || candidateWall.isAfter(wall)) {
                break;
            }
            Instant candidate = earliestCopyAfter(candidateWall, after);
            if (candidate != null && (earliest == null || candidate.isBefore(earliest))) {
                earliest = candidate;
            }
            if (candidateWall.equals(wall)) {
                break;
            }
            cursor = candidateWall;
        }
        return earliest;
    }

    /**
     * Earliest absolute candidate from matching wall times strictly after wall(after), or
     * null when Spring's complete local future is exhausted. Each local time is resolved
     * through every valid offset; gaps produce none.
     */
    private Instant earliestForward(LocalDateTime wall, Instant after) {
        LocalDateTime cursor = wall;
        while (true) {
            LocalDateTime candidateWall = expression.next(cursor);
            if (candidateWall == null) {
                return null;
            }
            Instant candidate = earliestCopyAfter(candidateWall, after);
            if (candidate != null) {
                return candidate;
            }
            cursor = candidateWall;
        }
    }

    /** The earliest copy of one local time strictly after the base point, or null. */
    private Instant earliestCopyAfter(LocalDateTime local, Instant after) {
        Instant earliest = null;
        for (ZoneOffset offset : rules.getValidOffsets(local)) {
            Instant candidate = local.toInstant(offset);
            if (candidate.isAfter(after) && (earliest == null || candidate.isBefore(earliest))) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    /** Local-time match check shared by {@link #matches} and candidate resolution. */
    private boolean expressionMatches(LocalDateTime local) {
        LocalDateTime orSame = expression.next(local.minusNanos(1));
        return orSame != null && orSame.equals(local);
    }

    public String cronExpression() {
        return cronText;
    }

    public ZoneId zone() {
        return zone;
    }
}
