package site.kael.cpa.console.core.usage.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.usage.model.UsageRecord;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UsageQueryDao {
    private final JdbcTemplate jdbcTemplate;

    public UsageQueryDao(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public UsageSummary summary(String apiKeyHash, Instant start, Instant end) {
        QueryFilter filter = filter(apiKeyHash, start, end);
        Long requests = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_events" + filter.sql(), Long.class, filter.args());
        Long tokens = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_events" + filter.sql(), Long.class, filter.args());
        Long duration = jdbcTemplate.queryForObject("SELECT COALESCE(AVG(latency_ms), 0) FROM usage_events" + filter.sql(), Long.class, filter.args());
        Map<String, Long> tokenDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT model, COALESCE(SUM(input_tokens + output_tokens), 0) value FROM usage_events" + filter.sql() + " GROUP BY model ORDER BY value DESC, model",
                (RowCallbackHandler) rs -> tokenDistribution.put(rs.getString("model"), rs.getLong("value")), filter.args());
        Map<String, Long> requestDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT model, COUNT(*) value FROM usage_events" + filter.sql() + " GROUP BY model ORDER BY value DESC, model",
                (RowCallbackHandler) rs -> requestDistribution.put(rs.getString("model"), rs.getLong("value")), filter.args());
        Map<String, Long> userTokenDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT COALESCE(NULLIF(u.nickname, ''), '未知用户') label, COALESCE(SUM(e.input_tokens + e.output_tokens), 0) value FROM usage_events e LEFT JOIN users u ON u.api_key_hash = e.api_key_hash" + filterForAlias(filter, "e") + " GROUP BY label ORDER BY value DESC, label",
                (RowCallbackHandler) rs -> userTokenDistribution.put(rs.getString("label"), rs.getLong("value")), filter.args());
        Map<String, Long> userRequestDistribution = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT COALESCE(NULLIF(u.nickname, ''), '未知用户') label, COUNT(*) value FROM usage_events e LEFT JOIN users u ON u.api_key_hash = e.api_key_hash" + filterForAlias(filter, "e") + " GROUP BY label ORDER BY value DESC, label",
                (RowCallbackHandler) rs -> userRequestDistribution.put(rs.getString("label"), rs.getLong("value")), filter.args());
        return new UsageSummary(requests == null ? 0 : requests, tokens == null ? 0 : tokens,
                duration == null ? 0 : duration, tokenDistribution, requestDistribution, userTokenDistribution, userRequestDistribution);
    }

    public UsageRecordsPage records(String apiKeyHash, Instant start, Instant end, int page, int pageSize) {
        QueryFilter filter = filter(apiKeyHash, start, end);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        Long totalValue = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_events" + filter.sql(), Long.class, filter.args());
        long total = totalValue == null ? 0 : totalValue;
        int totalPages = Math.max(1, (int) Math.ceil((double) total / safePageSize));
        int safePage = Math.max(1, Math.min(page, totalPages));
        int offset = (safePage - 1) * safePageSize;
        List<UsageRecord> records = jdbcTemplate.query("""
                SELECT e.id, e.model, COALESCE(NULLIF(u.nickname, ''), '未知用户') request_user,
                       e.input_tokens, e.output_tokens, e.cached_tokens, e.cache_read_tokens,
                       e.timestamp, e.latency_ms, e.failed
                FROM usage_events e
                LEFT JOIN users u ON u.api_key_hash = e.api_key_hash
                """ + filterForAlias(filter, "e") + " ORDER BY e.timestamp DESC, e.id DESC LIMIT ? OFFSET ?", (rs, row) -> {
            boolean success = !rs.getBoolean("failed");
            long cached = rs.getLong("cached_tokens") + rs.getLong("cache_read_tokens");
            return new UsageRecord(rs.getLong("id"), rs.getString("model"), rs.getString("request_user"), rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"), cached, rs.getTimestamp("timestamp").toInstant(),
                    Math.max(rs.getLong("latency_ms"), 0), success ? 200 : 500, success);
        }, concat(filter.args(), safePageSize, offset));
        return new UsageRecordsPage(records, safePage, safePageSize, total, totalPages);
    }

    private static String filterForAlias(QueryFilter filter, String alias) {
        return filter.sql().replace(" WHERE ", " WHERE ").replace("api_key_hash", alias + ".api_key_hash").replace("timestamp", alias + ".timestamp");
    }

    private static QueryFilter filter(String apiKeyHash, Instant start, Instant end) {
        if (apiKeyHash == null || apiKeyHash.isBlank()) {
            return new QueryFilter(" WHERE timestamp >= ? AND timestamp <= ?", new Object[]{Timestamp.from(start), Timestamp.from(end)});
        }
        return new QueryFilter(" WHERE api_key_hash = ? AND timestamp >= ? AND timestamp <= ?",
                new Object[]{apiKeyHash, Timestamp.from(start), Timestamp.from(end)});
    }

    private static Object[] concat(Object[] values, Object... suffix) {
        Object[] result = Arrays.copyOf(values, values.length + suffix.length);
        System.arraycopy(suffix, 0, result, values.length, suffix.length);
        return result;
    }

    private record QueryFilter(String sql, Object[] args) {}
}
