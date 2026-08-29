package site.kael.cpa.console.core.usage.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.usage.model.UsageEventInbox;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class UsageEventInboxDao {
    private final JdbcTemplate jdbcTemplate;

    public UsageEventInboxDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String source, String rawJson, String messageHash, Instant receivedAt) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO usage_event_inbox
                    (source, message_hash, raw_event_json, status, attempt_count, last_error, received_at, created_at, updated_at)
                VALUES (?, ?, ?, 'pending', 0, '', ?, ?, ?)
                """, source, messageHash, rawJson, Timestamp.from(receivedAt), Timestamp.from(now), Timestamp.from(now));
    }

    public List<UsageEventInbox> findProcessable(int limit) {
        return jdbcTemplate.query("""
                SELECT id, source, message_hash, raw_event_json, status, attempt_count, last_error,
                       received_at, processed_at, created_at, updated_at
                FROM usage_event_inbox
                WHERE status IN ('pending', 'process_failed')
                ORDER BY id ASC LIMIT ?
                """, (rs, rowNum) -> new UsageEventInbox(
                rs.getLong("id"), rs.getString("source"), rs.getString("message_hash"),
                rs.getString("raw_event_json"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getString("last_error"), instant(rs.getTimestamp("received_at")),
                instant(rs.getTimestamp("processed_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))), limit);
    }

    public void markDecodeFailed(long id, String error) {
        markFailed(id, UsageEventInbox.DECODE_FAILED, error, false);
    }

    public void markProcessFailed(long id, String error) {
        markFailed(id, UsageEventInbox.PROCESS_FAILED, error, true);
    }

    private void markFailed(long id, String status, String error, boolean retryable) {
        String bounded = error == null ? "" : error.substring(0, Math.min(error.length(), 1024));
        String finalStatus = retryable ? "CASE WHEN attempt_count + 1 >= 5 THEN 'discarded' ELSE ? END" : "?";
        if (retryable) {
            jdbcTemplate.update("UPDATE usage_event_inbox SET status = " + finalStatus + ", attempt_count = attempt_count + 1, last_error = ?, updated_at = ? WHERE id = ?", status, bounded, Timestamp.from(Instant.now()), id);
        } else {
            jdbcTemplate.update("UPDATE usage_event_inbox SET status = ?, attempt_count = attempt_count + 1, last_error = ?, updated_at = ? WHERE id = ?", status, bounded, Timestamp.from(Instant.now()), id);
        }
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM usage_event_inbox WHERE id = ?", id);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
