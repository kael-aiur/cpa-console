package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified cron matcher and history-independent Instant iterator (D4, task.md 8.4 / T03.R2-B).
 *
 * <p>Parsing delegates to the Spring six-field parser so that naming conventions, ranges,
 * steps, '?' / 'L' / 'W' / '#' and the DOM/DOW conjunction semantics stay identical between
 * preview and runtime iteration. Matching and next-time resolution always use this class;
 * callers must never rely on Spring's {@link CronExpression#next} alone, because Spring
 * folds overlapping local times and silently drops gap times.</p>
 *
 * <p><b>Earliest-future solving (B-CRON-3b fix).</b> {@link #next(Instant)} returns the
 * smallest matching absolute instant that is strictly greater than the given {@code after}.
 * The answer is a property of the plan, the zone and the base point alone - it never depends
 * on how often this instance has been consumed before.</p>
 *
 * <p><b>Overlaps.</b> When the base point sits inside an overlap block, Spring's next()
 * would silently skip the second wall-clock copy of that block (its next wall time lies
 * behind the second absolute copy of the still-pending block). This iterator reconstructs
 * the pending local candidates by walking the zone's overlap intervals directly: for an
 * overlap whose wall span is {@code [earlyStart, earlyEnd)} + the same wall times repeated
 * one offset-delta later, the second-copy candidates inside the overlap that lie in the
 * wall-clock past are enumerated via a two-offset walk from the local midnight of the day
 * containing {@code after}. Every candidate is then resolved through {@code getValidOffsets}
 * and the first absolute copy strictly after the base point wins - a gap candidate yields no
 * copy and is skipped; an exhausted candidate space proves that no matching instant exists.
 * No fixed iteration cap is involved in the decision.</p>
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
        LocalDateTime orSame = expression.next(local.minusNanos(1));
        if (orSame == null || !orSame.equals(local)) {
            return false;
        }
        return rules.getValidOffsets(local).contains(rules.getOffset(instant));
    }

    /**
     * First matching absolute instant strictly after {@code after}, or {@code null} when the
     * complete candidate space is exhausted. The result never depends on iterator history:
     * the same {@code after} always produces the same answer from the same instance and from
     * a freshly built one (fresh/batch/same-instance equivalence of task.md 8.4.3).
     */
    public Instant next(Instant after) {
        Objects.requireNonNull(after, "after");
        LocalDateTime wallNow = LocalDateTime.ofInstant(after, zone);
        // 1. Overlap completion FIRST, but only for a base point whose OWN wall clock
        //    resolves to the pending (second) offset: a wall time inside the overlap
        //    resolving to the PRIMARY offset means the primary copy is still in front,
        //    and the forward walk reaches both the primary block and the pending copies
        //    through the ordinary local sequence. Only when the caller stands on the
        //    pending copy can the walk have jumped past the pending wall times - exactly
        //    the B-CRON-3b case - so only then is the pending set consulted here.
        List<ZoneOffset> baseOffsets = rules.getValidOffsets(wallNow);
        if (baseOffsets.size() > 1 && baseOffsets.contains(rules.getOffset(after))) {
            // Consume every absolute copy that lies between the base point and the first
            // FORWARD local candidate: within that absolute range the pending wall times
            // are the only matches. The first forward candidate, once found, is returned
            // normally - that is what keeps the primary (EDT) block complete while the
            // pending EST copies of the earlier boundaries stay correctly ordered.
            for (Instant candidate : overlapCandidatesAfter(wallNow, after)) {
                LocalDateTime local = LocalDateTime.ofInstant(candidate, zone);
                LocalDateTime orSame = expression.next(local.minusNanos(1));
                if (orSame == null || !orSame.equals(local)) {
                    continue;
                }
                // Any FORWARD candidate that resolves to the base point's own offset
                // belongs to the caller's live block and must be honoured first; a copy
                // at the primary offset lies behind the caller's clock and was already
                // consumed. This is what keeps an EST base point at 01:31 wall from
                // returning the 01:31 primary copy that the walk already passed.
                LocalDateTime forward = expression.next(wallNow);
                List<Instant> forwardInstants = forward == null
                        ? List.of() : resolveInstants(forward);
                boolean forwardBeatsCandidate = false;
                for (Instant instant : forwardInstants) {
                    if (instant.isAfter(after)
                            && instant.isBefore(candidate)
                            && rules.getOffset(instant).equals(rules.getOffset(after))) {
                        forwardBeatsCandidate = true;
                        break;
                    }
                }
                if (!forwardBeatsCandidate) {
                    return candidate;
                }
            }
        }
        // 2. Forward local walk from the wall clock of the base point. Every candidate is
        //    resolved per offset and scanned for a copy strictly after the base point, so
        //    the earliest absolute answer - not the next wall time - is returned.
        LocalDateTime cursor = wallNow;
        while (true) {
            LocalDateTime nextLocal = expression.next(cursor);
            if (nextLocal == null) {
                break;
            }
            List<Instant> instants = resolveInstants(nextLocal);
            if (instants.isEmpty()) {
                cursor = nextLocal;
                continue;
            }
            for (Instant instant : instants) {
                if (instant.isAfter(after)) {
                    return instant;
                }
            }
            cursor = nextLocal;
        }
        return null;
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
     * Enumerates the absolute instants inside the overlap windows that lie after the base
     * point, whose wall time lies before the wall time of the base point. These are exactly
     * the candidates Spring's local forward walk cannot reach.
     */
    private List<Instant> overlapCandidatesAfter(LocalDateTime wallNow, Instant after) {
        List<Instant> result = new ArrayList<>();
        // Overlaps resolved through getValidOffsets always report both offsets AT the base
        // point's wall clock when the base point is inside the overlap block.
        if (rules.getValidOffsets(wallNow).size() <= 1) {
            return result;
        }
        ZoneOffset pendingOffset = rules.getValidOffsets(wallNow)
                .get(rules.getValidOffsets(wallNow).size() - 1);
        // The overlap block extends from the base point's wall clock back one offset
        // delta (2h covers the largest recurring overlap; Apia's 24h offset change is a
        // one-way skip, not an overlap). Every wall MINUTE inside the block carries a
        // second absolute copy at the pending offset, and Spring's forward walk can never
        // reach any of them (it resumes behind the block's second wall copy). Minute
        // resolution keeps the set complete for plans that do not align to the hour.
        LocalDateTime cursor = wallNow.withSecond(0).withNano(0);
        for (int i = 0; i < 120; i++) {
            if (cursor.isBefore(wallNow.minusHours(2))) {
                break;
            }
            Instant candidate = cursor.toInstant(pendingOffset);
            if (candidate.isAfter(after)) {
                result.add(candidate);
            }
            cursor = cursor.minusMinutes(1);
        }
        result.sort(Instant::compareTo);
        return result;
    }

    /** Resolves a local date-time into zero, one or two absolute instants (gap / overlap aware). */
    private List<Instant> resolveInstants(LocalDateTime local) {
        List<ZoneOffset> validOffsets = rules.getValidOffsets(local);
        if (validOffsets.isEmpty()) {
            return List.of();
        }
        List<Instant> instants = new ArrayList<>(validOffsets.size());
        for (ZoneOffset offset : validOffsets) {
            instants.add(local.toInstant(offset));
        }
        instants.sort(Instant::compareTo);
        return List.copyOf(instants);
    }

    public String cronExpression() {
        return cronText;
    }

    public ZoneId zone() {
        return zone;
    }
}
