package site.kael.cpa.console.core.monitor.manager;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

/**
 * Strict D3.3 classifier for one v8 verify response.
 * <p>
 * Only a complete, size-bounded JSON object is accepted. Duplicate keys, trailing objects and
 * oversized payloads are rejected outright. SUCCESS requires the current top-level
 * {@code verified} field to be the strict boolean {@code true} with no contradiction from
 * {@code status}/{@code error}; historical bits such as {@code inference_verified} or
 * {@code credential.inference_verified} are explicitly ignored. A strict current
 * {@code verified == false} is FAILED, management middleware 401/403 is CONFIG_ERROR, an
 * exactly-404 v8 route is CONFIG_ERROR (ROUTE_UNAVAILABLE) only under an explicit, trusted
 * probe, everything else conservative UNKNOWN.
 */
public final class VerificationResponseClassifier {

    /** Maximum accepted JSON body size; anything larger is rejected, not truncated. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final int httpStatus;
    private final String body;
    private final boolean managementMiddlewareProbe;

    public VerificationResponseClassifier(int httpStatus, String body) {
        this(httpStatus, body, false);
    }

    /** Allows a trusted route-availability probe result for exact-404 classification. */
    public VerificationResponseClassifier(int httpStatus, String body, boolean managementMiddlewareProbe) {
        this.httpStatus = httpStatus;
        this.body = body;
        this.managementMiddlewareProbe = managementMiddlewareProbe;
    }

    /** Verdict of one verification attempt. */
    public enum Verdict { SUCCESS, FAILED, CONFIG_ERROR, UNKNOWN }

    /** Classified outcome with a stable local reason enum name, never an upstream message. */
    public record Classification(Verdict verdict, String reason, Boolean currentVerifiedRaw) {
    }

    public Classification classify() {
        if (body != null && body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return new Classification(Verdict.UNKNOWN, "INVALID_RESPONSE", null);
        }
        if (httpStatus == 401 || httpStatus == 403) {
            D3Body parsed = parse();
            if (parsed == null || !parsed.hasCurrentVerifiedFalse()) {
                return new Classification(Verdict.CONFIG_ERROR, "MANAGEMENT_AUTH", null);
            }
            return new Classification(Verdict.FAILED, "TARGET_REJECTED", Boolean.FALSE);
        }
        if (httpStatus == 404) {
            if (managementMiddlewareProbe) {
                return new Classification(Verdict.CONFIG_ERROR, "ROUTE_UNAVAILABLE", null);
            }
            return new Classification(Verdict.UNKNOWN, "ROUTE_UNKNOWN", null);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            D3Body parsed = parse();
            if (parsed != null && parsed.hasCurrentVerifiedFalse()) {
                if (parsed.hasTargetRejectionContext()) {
                    return new Classification(Verdict.FAILED, "TARGET_REJECTED", Boolean.FALSE);
                }
                return new Classification(Verdict.FAILED, "CPA_SERVICE", Boolean.FALSE);
            }
            return new Classification(Verdict.UNKNOWN, "TRANSPORT", null);
        }
        D3Body parsed = parse();
        if (parsed == null) {
            return new Classification(Verdict.UNKNOWN, "INVALID_RESPONSE", null);
        }
        Boolean verified = parsed.currentVerified;
        if (verified == null) {
            return new Classification(Verdict.UNKNOWN, "INVALID_RESPONSE", null);
        }
        if (verified) {
            if (parsed.hasContradiction()) {
                return new Classification(Verdict.UNKNOWN, "CONTRADICTORY_RESPONSE", true);
            }
            return new Classification(Verdict.SUCCESS, "VERIFIED", true);
        }
        if (parsed.hasTargetRejectionContext()) {
            return new Classification(Verdict.FAILED, "TARGET_REJECTED", Boolean.FALSE);
        }
        return new Classification(Verdict.FAILED, "INFERENCE_FAILED", Boolean.FALSE);
    }

    /** True when a valid D3.3 response classifies as SUCCESS (strict current true, no contradiction). */
    public static boolean isSuccess(int httpStatus, String body) {
        return new VerificationResponseClassifier(httpStatus, body).classify().verdict() == Verdict.SUCCESS;
    }

    private D3Body parse() {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonFactory factory = new JsonFactory();
        try (JsonParser parser = factory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            Boolean verified = null;
            String status = null;
            String error = null;
            String authId = null;
            String authIndex = null;
            String model = null;
            boolean sawCredentialVerified = false;
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_OBJECT) {
                    break;
                }
                if (token != JsonToken.FIELD_NAME) {
                    return null;
                }
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (!valueToken.isScalarValue() && valueToken != JsonToken.START_OBJECT) {
                    return null;
                }
                switch (field) {
                    case "verified" -> {
                        if (parser.currentToken() != JsonToken.VALUE_TRUE
                                && parser.currentToken() != JsonToken.VALUE_FALSE) {
                            return null;
                        }
                        verified = parser.getBooleanValue();
                    }
                    case "status" -> status = parser.getValueAsString();
                    case "error" -> error = parser.getValueAsString();
                    case "auth_id" -> authId = parser.getValueAsString();
                    case "auth_index" -> authIndex = parser.getValueAsString();
                    case "model" -> model = parser.getValueAsString();
                    case "credential" -> {
                        if (parser.currentToken() != JsonToken.START_OBJECT) {
                            return null;
                        }
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String credentialField = parser.currentName();
                            parser.nextToken();
                            if ("inference_verified".equals(credentialField)) {
                                sawCredentialVerified = true;
                            }
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                return null;
            }
            return new D3Body(verified, status, error, authId, authIndex, model, sawCredentialVerified);
        } catch (IOException exception) {
            return null;
        }
    }

    private record D3Body(
            Boolean currentVerified,
            String status,
            String error,
            String authId,
            String authIndex,
            String model,
            boolean credentialVerifiedPresent) {

        boolean hasCurrentVerifiedFalse() {
            return Boolean.FALSE.equals(currentVerified);
        }

        boolean hasTargetRejectionContext() {
            return (authId != null || authIndex != null || model != null)
                    || (credentialVerifiedPresent && !"ok".equalsIgnoreCase(status));
        }

        boolean hasContradiction() {
            if (!"ok".equalsIgnoreCase(status == null ? "" : status)) {
                return true;
            }
            return error != null && !error.isBlank();
        }
    }

}
