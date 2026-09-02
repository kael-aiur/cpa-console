package site.kael.cpa.console.core.auth.model;

import java.time.Instant;

public record PersistentLoginToken(
        String selector,
        long userId,
        String tokenHash,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt
) {
}
