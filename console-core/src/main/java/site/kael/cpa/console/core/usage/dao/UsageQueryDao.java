package site.kael.cpa.console.core.usage.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.usage.model.UsageRecord;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UsageQueryDao {
    private final JdbcTemplate jdbcTemplate;

    public UsageQueryDao(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public UsageSummary summary(String apiKeyHash, Instant start, Instant end) {
        String filter = " WHERE api_key_hash = ? AND timestamp >= ? AND timestamp <= ?";
        Long requests = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_events" + filter,
                Long.class, apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        Long tokens = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_events" + filter,
                Long.class, apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        Long duration = jdbcTemplate.queryForObject("SELECT COALESCE(AVG(latency_ms), 0) FROM usage_events" + filter,
                Long.class, apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        Map<String, Long> tokenDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT model, COALESCE(SUM(input_tokens + output_tokens), 0) value FROM usage_events" + filter + " GROUP BY model ORDER BY value DESC, model",
                (RowCallbackHandler) rs -> tokenDistribution.put(rs.getString("model"), rs.getLong("value")), apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        Map<String, Long> requestDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT model, COUNT(*) value FROM usage_events" + filter + " GROUP BY model ORDER BY value DESC, model",
                (RowCallbackHandler) rs -> requestDistribution.put(rs.getString("model"), rs.getLong("value")), apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        return new UsageSummary(requests == null ? 0 : requests, tokens == null ? 0 : tokens,
                duration == null ? 0 : duration, tokenDistribution, requestDistribution);
    }

    public UsageRecordsPage records(String apiKeyHash, Instant start, Instant end, int page, int pageSize) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_events WHERE api_key_hash = ? AND timestamp >= ? AND timestamp <= ?",
                Long.class, apiKeyHash, Timestamp.from(start), Timestamp.from(end));
        int totalPages = Math.max(1, (int) Math.ceil((double) total / safePageSize));
        int safePage = Math.max(1, Math.min(page, totalPages));
        int offset = (safePage - 1) * safePageSize;
        List<UsageRecord> records = jdbcTemplate.query("""
                SELECT id, model, input_tokens, output_tokens, cached_tokens, cache_read_tokens,
                       timestamp, latency_ms, failed
                FROM usage_events
                WHERE api_key_hash = ? AND timestamp >= ? AND timestamp <= ?
                ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> {
            boolean success = !rs.getBoolean("failed");
            long cached = rs.getLong("cached_tokens") + rs.getLong("cache_read_tokens");
            return new UsageRecord(rs.getLong("id"), rs.getString("model"), rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"), cached, rs.getTimestamp("timestamp").toInstant(),
                    Math.max(rs.getLong("latency_ms"), 0), success ? 200 : 500, success);
        }, apiKeyHash, Timestamp.from(start), Timestamp.from(end), safePageSize, offset);
        return new UsageRecordsPage(records, safePage, safePageSize, total, totalPages);
    }
}
