package site.kael.cpa.console.core.auth.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.auth.dao.PersistentLoginTokenDao;
import site.kael.cpa.console.core.auth.model.NewPersistentLoginToken;
import site.kael.cpa.console.core.auth.model.PersistentLoginToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Creates and validates opaque database-backed login tokens that survive process restarts. */
@Component
public class PersistentLoginTokenManager {
    private static final int SELECTOR_BYTES = 16;
    private static final int SECRET_BYTES = 32;

    private final PersistentLoginTokenDao dao;
    private final Duration tokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public PersistentLoginTokenManager(PersistentLoginTokenDao dao, Duration tokenTtl) {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Persistent login token TTL must be positive");
        }
        this.dao = dao;
        this.tokenTtl = tokenTtl;
    }

    public NewPersistentLoginToken create(long userId) {
        dao.deleteExpired(Instant.now());

        byte[] selectorBytes = new byte[SELECTOR_BYTES];
        byte[] secretBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(selectorBytes);
        secureRandom.nextBytes(secretBytes);
        String selector = encode(selectorBytes);
        String secret = encode(secretBytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        dao.insert(new PersistentLoginToken(selector, userId, hash(secret), expiresAt, now, now));
        return new NewPersistentLoginToken(selector, secret, expiresAt);
    }

    public Optional<Long> validate(String cookieValue) {
        String selector = selector(cookieValue);
        if (selector == null) return Optional.empty();
        String secret = secret(cookieValue);
        if (secret == null) return Optional.empty();

        return dao.findBySelector(selector)
                .filter(token -> matches(secret, token.tokenHash()))
                .map(token -> {
                    if (token.expiresAt().isBefore(Instant.now())) {
                        dao.deleteBySelector(token.selector());
                        return Optional.<Long>empty();
                    }
                    dao.updateLastUsed(token.selector(), Instant.now());
                    return Optional.of(token.userId());
                })
                .orElseGet(Optional::empty);
    }

    public void revoke(String cookieValue) {
        String selector = selector(cookieValue);
        if (selector != null) dao.deleteBySelector(selector);
    }

    public void revokeAllForUser(long userId) {
        dao.deleteByUserId(userId);
    }

    private boolean matches(String secret, String expectedHash) {
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String selector(String cookieValue) {
        return part(cookieValue, 0);
    }

    private String secret(String cookieValue) {
        return part(cookieValue, 1);
    }

    private String part(String cookieValue, int index) {
        if (cookieValue == null) return null;
        String[] parts = cookieValue.split("\\.", -1);
        if (parts.length != 2 || parts[index].isBlank()) return null;
        return parts[index];
    }
}
