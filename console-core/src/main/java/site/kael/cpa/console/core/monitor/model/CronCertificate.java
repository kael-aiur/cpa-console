package site.kael.cpa.console.core.monitor.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Evidence bundle proving that a cron expression satisfies the D4 minimum-interval contract
 * against a concrete tzdb. A certificate binds the exact cron text, zone id, tzdb version tag,
 * algorithm version, generation instant and the proof's validation base point. Any change to
 * those inputs invalidates it.
 *
 * <p>Algorithm version 3 (2026-11-08, NATI-154 / task v1.2-r3 T03.R3-A) restructured the
 * proof into the finite explicit prefix plus one stable lastRules (or final fixed-offset)
 * tail period: the historical prefix is never shifted by a Gregorian cycle, window local
 * dates are projected through each window offset, existence is derived inside the target
 * zone (no UTC probe), and no unproven {@code Long.MAX_VALUE} minimum is ever certified.
 * Algorithm version 4 (2026-11-08, NATI-155 / task v1.2-r3 R2-A-R) additionally binds the
 * proof domain to the actual validation base point: the proof covers
 * {@code (basePoint, stable tail period end)} and generatedAt records the issuing clock
 * instant. Certificates issued by algorithm versions 1-3 sampled, whole-cycle-shifted, or
 * did not bind the base-point domain and MUST be treated as invalid; bumping this constant
 * invalidates every stored earlier certificate on load (see {@code matches}).</p>
 */
public record CronCertificate(
        String cronExpression,
        String zoneId,
        String tzdbVersion,
        int algorithmVersion,
        long minimumIntervalMs,
        Instant basePoint,
        Instant generatedAt) {

    public static final int CURRENT_ALGORITHM_VERSION = 4;

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
        if (basePoint == null) {
            throw new IllegalArgumentException("basePoint must be the validated proof domain start");
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

    /**
     * True only when a use instant lies in this certificate's proven domain: at or after
     * the base point that participated in the proof and before the end of the covered
     * stable-tail period. A caller must not reuse a certificate for a use instant whose
     * own base point has moved before the proven domain start.
     */
    public boolean validFor(Instant useInstant) {
        if (useInstant == null || useInstant.isBefore(basePoint)) {
            return false;
        }
        // The upper bound is one stable-tail period; guard the legacy epoch-minimum base
        // point (whole-future domain) against long overflow.
        long baseMs = basePoint.toEpochMilli();
        long upperMs = baseMs > Long.MIN_VALUE + ZoneWindowsCoverageTailBound.CYCLE_MS
                ? baseMs + ZoneWindowsCoverageTailBound.CYCLE_MS : Long.MAX_VALUE;
        return useInstant.toEpochMilli() < upperMs;
    }

    /** Analytic upper bound of one certified stable-tail proof period (146097 days). */
    static final class ZoneWindowsCoverageTailBound {
        private ZoneWindowsCoverageTailBound() {
        }

        /** Same Gregorian-period bound as {@code ZoneWindows.CYCLE_MS}. */
        static final long CYCLE_MS = 146_097L * 86_400_000L;
    }
}
