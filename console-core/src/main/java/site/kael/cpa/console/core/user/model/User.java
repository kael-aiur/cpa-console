package site.kael.cpa.console.core.user.model;

import java.time.Instant;

public record User(
        Long id,
        String nickname,
        UserRole role,
        String apiKeyHash,
        String apiKeyCiphertext,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
