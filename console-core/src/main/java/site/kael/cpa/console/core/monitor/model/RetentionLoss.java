package site.kael.cpa.console.core.monitor.model;

/**
 * Identity-free evidence-loss interval produced when finished runs are purged (D5.1 / D6.2).
 * The half-open millisecond interval [start, end) marks started_at values whose runs no longer exist.
 */
public record RetentionLoss(long start, long end, boolean conservative) {

    public RetentionLoss {
        if (end <= start) {
            throw new IllegalArgumentException("Retention loss interval must be non-empty");
        }
    }
}
