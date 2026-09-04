package site.kael.cpa.console.core.model.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.model.model.LiteLlmSyncConfig;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class LiteLlmSyncConfigDao {
    private final JdbcTemplate jdbc;
    public LiteLlmSyncConfigDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public LiteLlmSyncConfig get() {
        return jdbc.queryForObject("SELECT id, url, proxy_enabled, proxy_host, proxy_port, updated_at FROM litellm_sync_config WHERE id = 1", (rs, row) ->
                new LiteLlmSyncConfig(rs.getLong("id"), rs.getString("url"), rs.getBoolean("proxy_enabled"), rs.getString("proxy_host"), rs.getInt("proxy_port"), rs.getTimestamp("updated_at").toInstant()));
    }
    public LiteLlmSyncConfig update(String url, boolean proxyEnabled, String proxyHost, int proxyPort) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("LiteLLM metadata URL is required");
        if (proxyEnabled && (proxyHost == null || proxyHost.isBlank() || proxyPort <= 0 || proxyPort > 65535)) throw new IllegalArgumentException("Proxy host and port are invalid");
        jdbc.update("UPDATE litellm_sync_config SET url = ?, proxy_enabled = ?, proxy_host = ?, proxy_port = ?, updated_at = ? WHERE id = 1", url.trim(), proxyEnabled, proxyHost == null ? "" : proxyHost.trim(), proxyPort, Timestamp.from(Instant.now()));
        return get();
    }
}
