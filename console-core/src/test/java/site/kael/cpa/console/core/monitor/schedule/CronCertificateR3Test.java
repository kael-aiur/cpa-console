package site.kael.cpa.console.core.monitor.schedule;

import org.junit.jupiter.api.Test;

import site.kael.cpa.console.core.monitor.model.CronCertificate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03.R3-A V08 self-check (task v1.2-r3 §4.2): the certificate algorithm rework MUST
 * invalidate earlier certificates and re-certify with the new proof identity. The current
 * algorithm version is the split prefix/stable-tail proof; version 2 whole-cycle-shifted
 * the historic window and version 1 sampled fixed windows - both are stale.
 */
class CronCertificateR3Test {

    @Test
    void v08_v2InvalidationAndProofIdentity() {
        assertEquals(3, CronCertificate.CURRENT_ALGORITHM_VERSION,
                "R3-A proof rework must bump the algorithm identity");
        CronPolicyValidator validator = new CronPolicyValidator(Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        CronCertificate fresh = validator.certify("0 0 12 * * *", "UTC");
        assertEquals(3, fresh.algorithmVersion());
        assertTrue(validator.isCertificateValid(fresh, "0 0 12 * * *", "UTC"));
        // A version-2 certificate (whole 1970-2370 cycle shift) must be invalid.
        CronCertificate v2 = new CronCertificate("0 0 12 * * *", "UTC",
                fresh.tzdbVersion(), 2, fresh.minimumIntervalMs(), Instant.parse("2025-01-01T00:00:00Z"));
        assertFalse(validator.isCertificateValid(v2, "0 0 12 * * *", "UTC"));
        // A version-1 sampled certificate must be invalid too.
        CronCertificate v1 = new CronCertificate("0 0 12 * * *", "UTC",
                fresh.tzdbVersion(), 1, fresh.minimumIntervalMs(), Instant.parse("2024-01-01T00:00:00Z"));
        assertFalse(validator.isCertificateValid(v1, "0 0 12 * * *", "UTC"));
        // A tzdb change invalidates the certificate even under the same algorithm version.
        CronCertificate otherTzdb = new CronCertificate("0 0 12 * * *", "UTC",
                "different-tzdb", CronCertificate.CURRENT_ALGORITHM_VERSION,
                fresh.minimumIntervalMs(), Instant.parse("2026-01-01T00:00:00Z"));
        assertFalse(validator.isCertificateValid(otherTzdb, "0 0 12 * * *", "UTC"));
        // A plan change invalidates the certificate.
        assertFalse(validator.isCertificateValid(fresh, "0 0 13 * * *", "UTC"));
        // A zone change invalidates the certificate.
        assertFalse(validator.isCertificateValid(fresh, "0 0 12 * * *", "Asia/Kathmandu"));
    }
}
