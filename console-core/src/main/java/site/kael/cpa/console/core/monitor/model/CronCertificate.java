package site.kael.cpa.console.core.monitor.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Evidence bundle proving that a cron expression satisfies the D4 minimum-interval contract
 * against a concrete tzdb. A certificate binds the exact cron text, zone id, tzdb version tag,
 * algorithm version and generation instant; any change to those inputs invalidates it.
 */
public record CronCertificate(
        String cronExpression,
        String zoneId,
        String tzdbVersion,
        int algorithmVersion,
        long minimumIntervalMs,
        Instant generatedAt) {

    public static final int CURRENT_ALGORITHM_VERSION = 1;

    public CronCertificate {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("cronExpression must not be blank");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId must not be blank");
        }
        if (tzdbVersion == null || tzdbVersion.isBlank()) {
            throw new IllegalArgumentException("tzdbVersion must not be blank");
        }
        if (minimumIntervalMs < 0) {
            throw new IllegalArgumentException("minimumIntervalMs must not be negative");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt must not be null");
        }
    }

    /** True when this certificate still binds the requested cron/zone/tzdb combination. */
    public boolean matches(String cronExpression, ZoneId zoneId, String tzdbVersion) {
        return this.cronExpression.equals(cronExpression)
                && ZoneId.of(this.zoneId).equals(zoneId)
                && this.tzdbVersion.equals(tzdbVersion)
                && this.algorithmVersion == CURRENT_ALGORITHM_VERSION;
    }
}
