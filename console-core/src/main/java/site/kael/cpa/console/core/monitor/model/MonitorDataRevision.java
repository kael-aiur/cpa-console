package site.kael.cpa.console.core.monitor.model;

/**
 * Tracks the monotonic revision counter for monitor query snapshots (D5.1 / D6.3).
 * Recognised writing transactions (claim, terminal state, edit, retention) increment
 * the single-row counter so paginated readers can detect cross-request changes.
 */
public final class MonitorDataRevision {

    private final long revision;

    public MonitorDataRevision(long revision) {
        this.revision = revision;
    }

    public long value() {
        return revision;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MonitorDataRevision that && that.revision == this.revision;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(revision);
    }

    @Override
    public String toString() {
        return "MonitorDataRevision[" + revision + "]";
    }
}
