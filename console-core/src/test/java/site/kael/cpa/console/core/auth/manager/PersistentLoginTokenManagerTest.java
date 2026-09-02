package site.kael.cpa.console.core.auth.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.auth.dao.PersistentLoginTokenDao;
import site.kael.cpa.console.core.auth.model.NewPersistentLoginToken;
import site.kael.cpa.console.core.auth.model.PersistentLoginToken;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentLoginTokenManagerTest {
    @Test
    void createsSelectorAndSecretAndPersistsOnlyHash() {
        RecordingDao dao = new RecordingDao();
        PersistentLoginTokenManager manager = new PersistentLoginTokenManager(dao, Duration.ofDays(7));

        NewPersistentLoginToken token = manager.create(42L);

        assertTrue(token.selector().matches("[A-Za-z0-9_-]{22}"));
        assertTrue(token.secret().matches("[A-Za-z0-9_-]{43}"));
        PersistentLoginToken stored = dao.tokens.get(token.selector());
        assertNotNull(stored);
        assertEquals(42L, stored.userId());
        assertEquals(token.selector(), stored.selector());
        assertNotEquals(token.secret(), stored.tokenHash());
        assertTrue(stored.expiresAt().isAfter(Instant.now().plus(Duration.ofDays(6))));
    }

    @Test
    void validatesCookieAndUpdatesLastUsedAt() {
        RecordingDao dao = new RecordingDao();
        PersistentLoginTokenManager manager = new PersistentLoginTokenManager(dao, Duration.ofDays(7));
        NewPersistentLoginToken token = manager.create(42L);

        assertEquals(Optional.of(42L), manager.validate(token.selector() + "." + token.secret()));
        assertTrue(dao.lastUsedUpdates.containsKey(token.selector()));
    }

    @Test
    void rejectsWrongSecret() {
        RecordingDao dao = new RecordingDao();
        PersistentLoginTokenManager manager = new PersistentLoginTokenManager(dao, Duration.ofDays(7));
        NewPersistentLoginToken token = manager.create(42L);

        assertEquals(Optional.empty(), manager.validate(token.selector() + ".wrong-secret"));
        assertNotNull(dao.tokens.get(token.selector()));
    }

    @Test
    void deletesExpiredTokenDuringValidation() {
        RecordingDao dao = new RecordingDao();
        PersistentLoginTokenManager manager = new PersistentLoginTokenManager(dao, Duration.ofDays(7));
        NewPersistentLoginToken token = manager.create(42L);
        dao.tokens.put(token.selector(), new PersistentLoginToken(
                token.selector(), 42L, dao.tokens.get(token.selector()).tokenHash(),
                Instant.now().minusSeconds(1), Instant.now().minusSeconds(2), Instant.now().minusSeconds(3)));

        assertEquals(Optional.empty(), manager.validate(token.selector() + "." + token.secret()));
        assertFalse(dao.tokens.containsKey(token.selector()));
    }

    @Test
    void revokesTokenBySelector() {
        RecordingDao dao = new RecordingDao();
        PersistentLoginTokenManager manager = new PersistentLoginTokenManager(dao, Duration.ofDays(7));
        NewPersistentLoginToken token = manager.create(42L);

        manager.revoke(token.selector() + "." + token.secret());

        assertFalse(dao.tokens.containsKey(token.selector()));
    }

    private static final class RecordingDao extends PersistentLoginTokenDao {
        private final Map<String, PersistentLoginToken> tokens = new HashMap<>();
        private final Map<String, Instant> lastUsedUpdates = new HashMap<>();

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }

        private RecordingDao() {
            super(null);
        }

        @Override
        public void insert(PersistentLoginToken token) {
            tokens.put(token.selector(), token);
        }

        @Override
        public Optional<PersistentLoginToken> findBySelector(String selector) {
            return Optional.ofNullable(tokens.get(selector));
        }

        @Override
        public void updateLastUsed(String selector, Instant lastUsedAt) {
            lastUsedUpdates.put(selector, lastUsedAt);
            PersistentLoginToken token = tokens.get(selector);
            if (token != null) {
                tokens.put(selector, new PersistentLoginToken(token.selector(), token.userId(), token.tokenHash(),
                        token.expiresAt(), lastUsedAt, token.createdAt()));
            }
        }

        @Override
        public void deleteBySelector(String selector) {
            tokens.remove(selector);
        }
    }
}
