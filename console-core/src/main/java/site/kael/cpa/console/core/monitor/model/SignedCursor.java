package site.kael.cpa.console.core.monitor.model;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * Signed keyset cursor (D6.3): an HMAC-bound payload that carries the sort key plus the
 * query identity (task, window, view, as_of, retention and data revision) and the page
 * size. A tampered or cross-query cursor fails authentication and never silently mixes
 * pages from different snapshots.
 */
public final class SignedCursor {

    /** Decoded payload of one cursor. */
    public record Payload(
            String taskKey,
            long lastSortMs,
            long lastRunSeq,
            int limit,
            long asOfMs,
            long startMs,
            long endMs,
            String view,
            long retentionRevision,
            long dataRevision,
            String scope) {
    }

    private final byte[] secret;

    public SignedCursor(byte[] secret) {
        this.secret = Objects.requireNonNull(secret, "secret").clone();
        if (this.secret.length < 16) {
            throw new IllegalArgumentException("Cursor signing secret must be at least 16 bytes");
        }
    }

    public SignedCursor(String secretText) {
        this(sha256(secretText));
    }

    private static byte[] sha256(String secretText) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secretText.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Signs and encodes one page cursor. */
    public String encode(Payload payload) {
        String body = payload.taskKey() + "|" + payload.lastSortMs() + "|" + payload.lastRunSeq()
                + "|" + payload.limit() + "|" + payload.asOfMs() + "|" + payload.startMs()
                + "|" + payload.endMs() + "|" + payload.view() + "|" + payload.retentionRevision()
                + "|" + payload.dataRevision() + "|" + payload.scope();
        String signature = hmac(body);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (body + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and authenticates a cursor; {@code null} when the format is wrong or the
     * signature does not match (rejected, never treated as page one).
     */
    public Payload decode(String token) {
        Objects.requireNonNull(token, "token");
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int lastPipe = decoded.lastIndexOf('|');
        if (lastPipe <= 0) {
            return null;
        }
        String body = decoded.substring(0, lastPipe);
        String signature = decoded.substring(lastPipe + 1);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), hmac(body).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String[] parts = body.split("\\|", -1);
        if (parts.length != 11) {
            return null;
        }
        try {
            return new Payload(
                    parts[0],
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Integer.parseInt(parts[3]),
                    Long.parseLong(parts[4]),
                    Long.parseLong(parts[5]),
                    Long.parseLong(parts[6]),
                    parts[7],
                    Long.parseLong(parts[8]),
                    Long.parseLong(parts[9]),
                    parts[10]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cursor signing failed", exception);
        }
    }
}
