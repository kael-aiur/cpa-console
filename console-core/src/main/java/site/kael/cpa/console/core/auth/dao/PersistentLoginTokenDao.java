package site.kael.cpa.console.core.auth.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.auth.model.PersistentLoginToken;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PersistentLoginTokenDao {
    private final JdbcTemplate jdbcTemplate;

    public PersistentLoginTokenDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(PersistentLoginToken token) {
        jdbcTemplate.update("""
                INSERT INTO persistent_login_tokens (selector, user_id, token_hash, expires_at, last_used_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                token.selector(), token.userId(), token.tokenHash(),
                Timestamp.from(token.expiresAt()), Timestamp.from(token.lastUsedAt()), Timestamp.from(token.createdAt()));
    }

    public Optional<PersistentLoginToken> findBySelector(String selector) {
        List<PersistentLoginToken> tokens = jdbcTemplate.query("""
                SELECT selector, user_id, token_hash, expires_at, last_used_at, created_at
                FROM persistent_login_tokens WHERE selector = ? LIMIT 1
                """, this::mapToken, selector);
        return tokens.stream().findFirst();
    }

    public void updateLastUsed(String selector, Instant lastUsedAt) {
        jdbcTemplate.update("UPDATE persistent_login_tokens SET last_used_at = ? WHERE selector = ?",
                Timestamp.from(lastUsedAt), selector);
    }

    public void deleteBySelector(String selector) {
        jdbcTemplate.update("DELETE FROM persistent_login_tokens WHERE selector = ?", selector);
    }

    public void deleteByUserId(long userId) {
        jdbcTemplate.update("DELETE FROM persistent_login_tokens WHERE user_id = ?", userId);
    }

    public int deleteExpired(Instant now) {
        return jdbcTemplate.update("DELETE FROM persistent_login_tokens WHERE expires_at < ?", Timestamp.from(now));
    }

    private PersistentLoginToken mapToken(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PersistentLoginToken(
                resultSet.getString("selector"),
                resultSet.getLong("user_id"),
                resultSet.getString("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("last_used_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
