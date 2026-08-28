package site.kael.cpa.console.core.user.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.core.user.model.UserRole;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserDao {
    private final JdbcTemplate jdbcTemplate;

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByApiKeyHash(String apiKeyHash) {
        List<User> users = jdbcTemplate.query("""
                SELECT id, nickname, role, api_key_hash, api_key_ciphertext, enabled, created_at, updated_at
                FROM users WHERE api_key_hash = ? LIMIT 1
                """, this::mapUser, apiKeyHash);
        return users.stream().findFirst();
    }

    public List<User> findAll() {
        return jdbcTemplate.query("""
                SELECT id, nickname, role, api_key_hash, api_key_ciphertext, enabled, created_at, updated_at
                FROM users ORDER BY created_at DESC, id DESC
                """, this::mapUser);
    }

    public Optional<User> findById(long id) {
        List<User> users = jdbcTemplate.query("""
                SELECT id, nickname, role, api_key_hash, api_key_ciphertext, enabled, created_at, updated_at
                FROM users WHERE id = ? LIMIT 1
                """, this::mapUser, id);
        return users.stream().findFirst();
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    public User insert(String nickname, UserRole role, String apiKeyHash, String apiKeyCiphertext) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (nickname, role, api_key_hash, api_key_ciphertext, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, nickname);
            statement.setString(2, role.value());
            statement.setString(3, apiKeyHash);
            statement.setString(4, apiKeyCiphertext);
            statement.setBoolean(5, true);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("User insert did not return an id");
        return findById(key.longValue()).orElseThrow(() -> new IllegalStateException("Inserted user could not be loaded"));
    }

    public User update(long id, String nickname, UserRole role) {
        int updated = jdbcTemplate.update("""
                UPDATE users SET nickname = ?, role = ?, updated_at = ? WHERE id = ?
                """, nickname, role.value(), Timestamp.from(Instant.now()), id);
        if (updated == 0) {
            throw new site.kael.cpa.console.core.user.exception.UserNotFoundException(id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Updated user could not be loaded"));
    }

    public void delete(long id) {
        int deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        if (deleted == 0) {
            throw new site.kael.cpa.console.core.user.exception.UserNotFoundException(id);
        }
    }

    private User mapUser(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new User(
                resultSet.getLong("id"),
                resultSet.getString("nickname"),
                UserRole.fromValue(resultSet.getString("role")),
                resultSet.getString("api_key_hash"),
                resultSet.getString("api_key_ciphertext"),
                resultSet.getBoolean("enabled"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
