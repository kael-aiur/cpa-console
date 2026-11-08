package site.kael.cpa.console.core.monitor.schedule;

import site.kael.cpa.console.core.monitor.model.CronCertificate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Dedicated D4 configuration validator: parses a plan, proves the finite explicit-history
 * prefix plus one stable-tail period (task v1.2-r3 T03.R3-A / P1-P7) and produces a
 * {@link CronCertificate} bound to the exact tzdb of the running JDK. Cached certificates
 * stay valid only while cron text, zone id, tzdb version and algorithm version are
 * unchanged; a tzdb upgrade or an algorithm rework invalidates every stored certificate
 * so tasks re-validate (or stay blocked until explicit recovery) on start.
 */
public final class CronPolicyValidator {

    private final Clock clock;
    private final String tzdbVersion;

    public CronPolicyValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tzdbVersion = resolveTzdbVersion();
    }

    /** Parses, validates and certifies a plan. Throws with a readable message on any failure. */
    public CronCertificate certify(String cronExpression, String zoneId) {
        CronPolicy.rejectUnsupportedShape(cronExpression);
        ZoneId zone = CronPolicy.validateZone(zoneId);
        IntervalValidator.Validation validation =
                IntervalValidator.validate(cronExpression.trim(), zone, CronPolicy.MINIMUM_INTERVAL_MS);
        if (validation instanceof IntervalValidator.Validation.Rejected rejected) {
            throw new IllegalArgumentException(rejected.reason());
        }
        IntervalValidator.Validation.Passed passed = (IntervalValidator.Validation.Passed) validation;
        return new CronCertificate(
                cronExpression.trim(),
                zone.getId(),
                tzdbVersion,
                CronCertificate.CURRENT_ALGORITHM_VERSION,
                passed.minimumIntervalMs(),
                clock.instant());
    }

    /** True when an existing certificate still covers the given plan under the current tzdb. */
    public boolean isCertificateValid(CronCertificate certificate, String cronExpression, String zoneId) {
        if (certificate == null) {
            return false;
        }
        try {
            return certificate.matches(cronExpression, CronPolicy.validateZone(zoneId), tzdbVersion);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** tzdb identity of the running JDK; changes invalidate all cached certificates. */
    public String tzdbVersion() {
        return tzdbVersion;
    }

    private static String resolveTzdbVersion() {
        try {
            Class<?> zoneVersion = Class.forName("sun.util.calendar.ZoneInfoFile");
            java.lang.reflect.Method method = zoneVersion.getMethod("getZoneVersion");
            Object version = method.invoke(null);
            if (version instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to the JDK build tag.
        }
        return "jdk-" + System.getProperty("java.version") + "-tzdata-unknown";
    }
}
