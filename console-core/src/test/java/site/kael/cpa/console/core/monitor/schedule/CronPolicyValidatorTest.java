package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import site.kael.cpa.console.core.monitor.model.CronCertificate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronPolicyValidatorTest {

    private final CronPolicyValidator validator =
            new CronPolicyValidator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void certifiesValidPlan() {
        CronCertificate certificate = validator.certify("17 */2 * * * *", "UTC");
        assertNotNull(certificate);
        assertEquals("UTC", certificate.zoneId());
        assertEquals("17 */2 * * * *", certificate.cronExpression());
        assertTrue(certificate.minimumIntervalMs() >= CronPolicy.MINIMUM_INTERVAL_MS);
        assertTrue(validator.isCertificateValid(certificate, "17 */2 * * * *", "UTC"));
    }

    @Test
    void rejectsInvalidInterval() {
        assertThrows(IllegalArgumentException.class, () -> validator.certify("0,59 * * * * *", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> validator.certify("* * * * * *", "UTC"));
    }

    @Test
    void rejectsUnsupportedShapes() {
        assertThrows(IllegalArgumentException.class, () -> validator.certify("0 0 0 * *", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> validator.certify("@hourly", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> validator.certify("0 0 0 * * * 2026", "UTC"));
    }

    @Test
    void rejectsIllegalZone() {
        assertThrows(IllegalArgumentException.class, () -> validator.certify("0 0 0 * * *", "Mars/Olympus"));
    }

    @Test
    void certificateInvalidAfterTzdbChange() {
        CronCertificate certificate = validator.certify("0 0 12 * * *", "UTC");
        assertTrue(validator.isCertificateValid(certificate, "0 0 12 * * *", "UTC"));
        CronCertificate other = new CronCertificate(
                "0 0 12 * * *", "UTC", "different-tzdb", CronCertificate.CURRENT_ALGORITHM_VERSION,
                86_400_000L, Instant.parse("2026-01-01T00:00:00Z"));
        assertFalse(validator.isCertificateValid(other, "0 0 12 * * *", "UTC"));
    }

    @Test
    void nullCertificateInvalid() {
        assertFalse(validator.isCertificateValid(null, "0 0 0 * * *", "UTC"));
    }

    @Test
    void defaultZoneHandledExplicitly() {
        // Only IANA ids are accepted; UTC must be passed explicitly, never defaulted silently.
        assertNotNull(validator.certify("0 0 0 * * *", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> validator.certify("0 0 0 * * *", null));
    }
}
