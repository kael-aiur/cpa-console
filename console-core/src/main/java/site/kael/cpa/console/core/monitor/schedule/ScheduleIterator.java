package site.kael.cpa.console.core.monitor.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Unified cron matcher and Instant iterator (D4).
 * <p>
 * Parsing delegates to the Spring six-field parser so that naming conventions, ranges, steps,
 * '?' / 'L' / 'W' / '#' and the DOM/DOW conjunction semantics stay identical between preview
 * and runtime iteration. Matching and next-time resolution always use this class; callers must
 * never rely on Spring's {@link CronExpression#next} alone, because Spring folds overlapping
 * local times and silently drops gap times. This iterator projects matched local date-times
 * through {@link ZoneRules#getValidOffsets} so a gap produces zero instants and an overlap
 * produces both, ordered by absolute time.
 * <p>
 * The iterator walks local matches monotonically and keeps a small pending buffer. When an
 * overlap instant arrives, BOTH absolute copies enter the buffer, but they only become
 * "pending output" once no earlier local match could still produce an earlier absolute
 * instant. This keeps the emitted sequence globally sorted by absolute time, so a fall-back
 * overlap does not truncate the first hour's tail: the queue drains in absolute order even
 * across the overlap boundary.
 */
public final class ScheduleIterator {

    private final CronExpression expression;
    private final ZoneId zone;
    private final ZoneRules rules;
    private final String cronText;
    /**
     * Pending absolute instants in strictly-ascending order. An overlap block enters the
     * buffer only after the following local match has been resolved, so the first-offset
     * copies of earlier minutes stay ahead of the second-offset copies of later minutes and
     * the emitted stream is globally sorted (D4.1 / B-CRON-3 fix).
     */
    private final java.util.PriorityQueue<Instant> pendingInstants = new java.util.PriorityQueue<>();
    private LocalDateTime lastLocalMatch;
    /** Absolute instant of the most recently resolved local match, or null before the first. */
    private Instant lastResolvedAbsolute;

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

    /** True when the given absolute instant matches the cron under the configured zone rules. */
    public boolean matches(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        LocalDateTime orSame = expression.next(local.minusNanos(1));
        if (orSame == null || !orSame.equals(local)) {
            return false;
        }
        return rules.getValidOffsets(local).contains(rules.getOffset(instant));
    }

    /** First matching absolute instant strictly after {@code after}; {@code null} when none exists. */
    public Instant next(Instant after) {
        Objects.requireNonNull(after, "after");
        while (true) {
            // Always make sure the queue head is provably final before returning it: the
            // next local match might resolve an earlier absolute instant (overlap case),
            // so consult the resolver even when the current head looks like the answer.
            if (!advanceToNextLocal(after)) {
                if (pendingInstants.isEmpty()) {
                    return null;
                }
            }
            Instant pending = pendingInstants.peek();
            if (pending != null && pending.isAfter(after)) {
                return pendingInstants.poll();
            }
            if (pending != null) {
                pendingInstants.poll();
                continue;
            }
            return null;
        }
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
     * Advances the internal cursor to the next local match. The pending buffer always keeps
     * absolute order: an overlap block with multiple instants is only queued once every
     * candidate local time that could produce an earlier instant has been enumerated.
     * <p>
     * Implementation: when the next local match is more than one full interval ahead of the
     * last pending instant, we know no earlier absolute time can arrive later, so the buffer
     * can be flushed in order. When they are adjacent (e.g. minute 00 and 01 both match during
     * an overlap), the local 01:00 match enqueues both of its absolute copies only after the
     * local 01:01 match has been probed and produced a strictly-later first instant.
     */
        private boolean advanceToNextLocal(Instant after) {
        // When the queue head is already provably final (the next local match resolves
        // strictly after it), returning true lets next() poll the head immediately.
        if (!pendingInstants.isEmpty()) {
            LocalDateTime searchFromCheck = lastLocalMatch;
            List<Instant> headCheck = peekNextInstants(searchFromCheck, 1);
            if (headCheck.isEmpty() || headCheck.get(0).isAfter(pendingInstants.peek())) {
                return true;
            }
        }
        LocalDateTime searchFrom = lastLocalMatch != null
                ? lastLocalMatch
                : LocalDateTime.ofInstant(after, zone);
        int safety = 0;
        while (safety++ < 600) {
            LocalDateTime nextLocal = lastLocalMatch != null
                    ? expression.next(searchFrom)
                    : expression.next(searchFrom.minusNanos(1));
            if (nextLocal == null) {
                pendingInstants.removeIf(instant -> !instant.isAfter(after));
                return !pendingInstants.isEmpty();
            }
            List<Instant> instants = resolveInstants(nextLocal);
            searchFrom = nextLocal;
            lastLocalMatch = nextLocal;
            if (instants.isEmpty()) {
                continue;
            }
            for (Instant instant : instants) {
                if (instant.isAfter(after)) {
                    pendingInstants.add(instant);
                }
            }
            if (pendingInstants.isEmpty()) {
                continue;
            }
            // After absorbing this block into the queue, check if the queue head is final:
            // the next local match must resolve strictly after the head, otherwise the
            // head might still be truncated by a later block's first-offset copy.
            List<Instant> lookahead = peekNextInstants(searchFrom, 1);
            if (lookahead.isEmpty() || lookahead.get(0).isAfter(pendingInstants.peek())) {
                return true;
            }
            // The head can still be preceded by the next block; keep resolving.
        }
        throw new IllegalStateException(
                "Unable to find a cron match within a bounded search window for: " + cronText);
    }

    /**
     * Resolves the absolute instants of the {@code n}-th local match after {@code from}
     * without mutating iterator state.  A single-step lookahead is enough because an
     * overlap can only double one contiguous block of local matches; anything two or more
     * local matches later resolves strictly after this block's earliest instant.
     */
    private List<Instant> peekNextInstants(LocalDateTime from, int n) {
        LocalDateTime probe = from;
        for (int i = 0; i < n; i++) {
            LocalDateTime candidate = expression.next(probe);
            if (candidate == null) {
                return List.of();
            }
            List<Instant> instants = resolveInstants(candidate);
            probe = candidate;
            if (!instants.isEmpty()) {
                return instants;
            }
        }
        return List.of();
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
        instants.sort(Comparator.naturalOrder());
        return List.copyOf(instants);
    }

    public String cronExpression() {
        return cronText;
    }

    public ZoneId zone() {
        return zone;
    }
}
