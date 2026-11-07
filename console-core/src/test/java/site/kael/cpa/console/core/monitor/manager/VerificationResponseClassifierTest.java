package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.monitor.manager.VerificationResponseClassifier.Classification;
import site.kael.cpa.console.core.monitor.manager.VerificationResponseClassifier.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D3.3 matrix: strict top-level boolean, history bits ignored, conservative unknowns. */
class VerificationResponseClassifierTest {

    private Classification c(int status, String body) {
        return new VerificationResponseClassifier(status, body).classify();
    }

    @Test
    void strictTopLevelTrueWithoutContradictionIsSuccess() {
        assertEquals(Verdict.SUCCESS, c(200, "{\"verified\":true,\"status\":\"ok\"}").verdict());
        assertEquals("VERIFIED", c(200, "{\"verified\":true,\"status\":\"ok\"}").reason());
    }

    @Test
    void historicalTrueNeverDecidesThisAttempt() {
        assertEquals(Verdict.UNKNOWN, c(200, "{\"inference_verified\":true}").verdict());
        assertEquals(Verdict.UNKNOWN, c(200,
                "{\"credential\":{\"inference_verified\":true}}").verdict());
        assertEquals(Verdict.FAILED, c(200,
                "{\"verified\":false,\"credential\":{\"inference_verified\":true}}").verdict());
    }

    @Test
    void strictFalseIsFailedNotUnknown() {
        Classification failed = c(200, "{\"verified\":false,\"status\":\"ok\"}");
        assertEquals(Verdict.FAILED, failed.verdict());
        assertEquals("INFERENCE_FAILED", failed.reason());
        Classification rejected = c(200,
                "{\"verified\":false,\"status\":\"error\",\"auth_id\":\"x\"}");
        assertEquals(Verdict.FAILED, rejected.verdict());
        assertEquals("TARGET_REJECTED", rejected.reason());
    }

    @Test
    void stringTrueNullAndMissingAreUnknown() {
        assertEquals(Verdict.UNKNOWN, c(200, "{\"verified\":\"true\"}").verdict());
        assertEquals(Verdict.UNKNOWN, c(200, "{\"verified\":null}").verdict());
        assertEquals(Verdict.UNKNOWN, c(200, "{\"status\":\"ok\"}").verdict());
    }

    @Test
    void contradictionBetweenTrueAndErrorIsUnknown() {
        assertEquals(Verdict.UNKNOWN, c(200,
                "{\"verified\":true,\"status\":\"error\"}").verdict());
        assertEquals(Verdict.UNKNOWN, c(200,
                "{\"verified\":true,\"error\":\"boom\"}").verdict());
    }

    @Test
    void duplicateKeysAndTrailingObjectsAreRejected() {
        assertEquals(Verdict.UNKNOWN, c(200,
                "{\"verified\":false,\"verified\":true}").verdict());
        assertEquals(Verdict.UNKNOWN, c(200,
                "{\"verified\":true}{\"verified\":true}").verdict());
    }

    @Test
    void htmlEmptyAndOversizedBodiesAreUnknown() {
        assertEquals(Verdict.UNKNOWN, c(200, "<html>busy</html>").verdict());
        assertEquals(Verdict.UNKNOWN, c(200, "  ").verdict());
        assertEquals(Verdict.UNKNOWN, c(200, "{\"verified\":true,\"pad\":\""
                + "x".repeat(VerificationResponseClassifier.MAX_RESPONSE_BYTES + 10) + "\"}").verdict());
    }

    @Test
    void managementAuthIsConfigErrorWhileTargetRejectedFalseIsFailed() {
        assertEquals(Verdict.CONFIG_ERROR, c(401, "{\"error\":\"missing management key\"}").verdict());
        assertEquals(Verdict.CONFIG_ERROR, c(403, "{\"error\":\"remote management disabled\"}").verdict());
        assertEquals("MANAGEMENT_AUTH", c(401, "{}").reason());
        Classification rejected = c(401,
                "{\"verified\":false,\"status\":\"error\",\"auth_index\":\"idx\"}");
        assertEquals(Verdict.FAILED, rejected.verdict());
        assertEquals("TARGET_REJECTED", rejected.reason());
    }

    @Test
    void exactRoute404IsConfiguredOnlyThroughExplicitProbe() {
        assertEquals(Verdict.CONFIG_ERROR,
                new VerificationResponseClassifier(404, "{\"error\":\"Not Found\"}", true).classify().verdict());
        assertEquals(Verdict.UNKNOWN,
                new VerificationResponseClassifier(404, "{\"error\":\"Not Found\"}", false).classify().verdict());
    }

    @Test
    void unknown5xxAndServiceErrorsStayConservative() {
        assertEquals(Verdict.UNKNOWN, c(500, "upstream exploded").verdict());
        assertEquals(Verdict.UNKNOWN, c(502, "{\"error\":\"bad gateway\"}").verdict());
        assertEquals(Verdict.FAILED, c(503,
                "{\"verified\":false,\"status\":\"error\",\"model\":\"m\"}").verdict());
    }
}
