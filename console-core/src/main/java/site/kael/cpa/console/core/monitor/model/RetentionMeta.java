package site.kael.cpa.console.core.monitor.model;

/**
 * Singleton retention metadata row (D5.1 / D6.2): collection starting point, cleanup revision
 * and the time of the last completed purge batch.
 */
public record RetentionMeta(long collectionStartedAt, long retentionRevision, Long lastCleanupAt) {
}
