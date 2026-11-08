package site.kael.cpa.console.core.monitor.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Evidence bundle proving that a cron expression satisfies the D4 minimum-interval contract
 * against a concrete tzdb. A certificate binds the exact cron text, zone id, tzdb version tag,
 * algorithm version and generation instant; any change to those inputs invalidates it.
 * <p>Algorithm version 2 (2026-11-08, NATI-149 / task.md 8.3 P1-P7) replaced the sampled
 * window walk with the complete matched-set proof: full 146097-day Gregorian cycle coverage,
 * all explicit and annual transitions, the cycle-to-cycle seam from the last non-empty
 * trigger to the first non-empty trigger of the next cycle, and an existence decision that
 * is derived from the same proof (no Long.MAX_VALUE fallback). Certificates issued by
 * algorithm version 1 sampled fixed windows and MUST be treated as invalid; bumping this
 * constant invalidates every stored v1 certificate on load (see {@code matches}).
 */
public record CronCertificate(
        String cronExpression,
        String zoneId,
        String tzdbVersion,
        int algorithmVersion,
        long minimumIntervalMs,
        Instant generatedAt) {

    public static final int CURRENT_ALGORITHM_VERSION = 2;

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
