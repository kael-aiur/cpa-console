package site.kael.cpa.console.core.monitor.model;

/**
 * Persistent monitoring task row (D5.1). The logical target fields (instance key, auth id
 * cipher, auth index cipher, route model cipher, provider snapshot) are immutable after
 * creation; safe display fields and schedule fields are the only mutable surface.
 */
public record MonitorTask(
        String taskId,
        String safeName,
        String routeModelCipher,
        String safeModelDisplay,
        String instanceKey,
        String authIdCipher,
        String authIndexCipher,
        String providerSnapshot,
        boolean enabled,
        String blockedReason,
        long scheduleRevision,
        long rowVersion,
        String cronExpression,
        String zoneId,
        String tzdbCertificate,
        Long nextFireAt,
        long createdAt,
        long updatedAt) {

    public MonitorTask {
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(safeName, "safeName");
        java.util.Objects.requireNonNull(instanceKey, "instanceKey");
        java.util.Objects.requireNonNull(cronExpression, "cronExpression");
        java.util.Objects.requireNonNull(zoneId, "zoneId");
        if (scheduleRevision < 1) {
            throw new IllegalArgumentException("scheduleRevision must be >= 1");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must be >= 0");
        }
    }
}
