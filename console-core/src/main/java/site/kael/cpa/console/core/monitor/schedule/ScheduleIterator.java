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
 */
public final class ScheduleIterator {

    private final CronExpression expression;
    private final ZoneId zone;
    private final ZoneRules rules;
    private final String cronText;
    private final Deque<Instant> pendingInstants = new ArrayDeque<>();
    private LocalDateTime lastLocalMatch;

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
            Instant pending = pendingInstants.peek();
            if (pending != null && pending.isAfter(after)) {
                return pendingInstants.poll();
            }
            if (pending != null && !pending.isAfter(after)) {
                pendingInstants.poll();
                continue;
            }
            if (!advanceToNextLocal(after)) {
                return null;
            }
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

    /** Advances the internal cursor to the next local match and queues all its absolute instants. */
    private boolean advanceToNextLocal(Instant after) {
        LocalDateTime searchFrom = lastLocalMatch != null
                ? lastLocalMatch
                : LocalDateTime.ofInstant(after, zone);
        int safety = 0;
        while (safety++ < 600) {
            LocalDateTime nextLocal = lastLocalMatch != null
                    ? expression.next(searchFrom)
                    : expression.next(searchFrom.minusNanos(1));
            if (nextLocal == null) {
                return false;
            }
            List<Instant> instants = resolveInstants(nextLocal);
            searchFrom = nextLocal;
            lastLocalMatch = nextLocal;
            if (instants.isEmpty()) {
                continue;
            }
            boolean queued = false;
            for (Instant instant : instants) {
                if (instant.isAfter(after)) {
                    pendingInstants.add(instant);
                    queued = true;
                }
            }
            if (queued) {
                return true;
            }
        }
        throw new IllegalStateException(
                "Unable to find a cron match within a bounded search window for: " + cronText);
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
