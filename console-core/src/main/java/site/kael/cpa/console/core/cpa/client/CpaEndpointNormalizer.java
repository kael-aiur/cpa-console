package site.kael.cpa.console.core.cpa.client;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Strict normalization of the configured CPA base URL for monitoring binding (D5.1a).
 * <p>
 * The normalized entry keeps scheme, host, effective port and the base path, drops one
 * trailing slash and merges duplicate slashes inside the path. Userinfo, query and fragment
 * are rejected outright, and an empty path defaults to "/". The normalized endpoint is the
 * only stable identity basis; it is never re-derived from mutable configuration at call time.
 */
public final class CpaEndpointNormalizer {

    private CpaEndpointNormalizer() {
    }

    /** Returns the normalized endpoint, or a rejection reason when the entry is not bindable. */
    public static Result normalize(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return Result.rejected("Base URL must not be empty");
        }
        URI raw;
        try {
            raw = new URI(rawBaseUrl.trim());
        } catch (URISyntaxException exception) {
            return Result.rejected("Base URL is not a valid URI");
        }
        String scheme = raw.getScheme() == null ? "" : raw.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Result.rejected("Base URL scheme must be http or https");
        }
        if (raw.getHost() == null || raw.getHost().isBlank()) {
            return Result.rejected("Base URL host must not be empty");
        }
        if (raw.getUserInfo() != null) {
            return Result.rejected("Base URL must not contain userinfo");
        }
        if (raw.getQuery() != null) {
            return Result.rejected("Base URL must not contain a query");
        }
        if (raw.getFragment() != null) {
            return Result.rejected("Base URL must not contain a fragment");
        }
        int port = raw.getPort();
        if (port == -1) {
            port = scheme.equals("https") ? 443 : 80;
        }
        String rawPath = raw.getRawPath() == null ? "" : raw.getRawPath();
        String path = rawPath.isEmpty() ? "" : rawPath.replaceAll("/+", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder endpoint = new StringBuilder(scheme).append("://").append(raw.getHost().toLowerCase());
        boolean defaultPort = (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        if (!defaultPort) {
            endpoint.append(':').append(port);
        }
        endpoint.append(path);
        return Result.accepted(endpoint.toString());
    }

    /** Normalization outcome: either the canonical endpoint text or a stable rejection reason. */
    public record Result(String endpoint, String rejectionReason) {

        static Result accepted(String endpoint) {
            return new Result(endpoint, null);
        }

        static Result rejected(String reason) {
            return new Result(null, reason);
        }

        public boolean accepted() {
            return rejectionReason == null;
        }
    }
}
