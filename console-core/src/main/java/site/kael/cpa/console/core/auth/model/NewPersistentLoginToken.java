package site.kael.cpa.console.core.auth.model;

import java.time.Instant;

/** Selector and one-time plaintext returned to the web layer; only the hash is persisted. */
public record NewPersistentLoginToken(
        String selector,
        String secret,
        Instant expiresAt
) {
}
