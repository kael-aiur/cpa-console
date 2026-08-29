package site.kael.cpa.console.core.usage.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.usage.model.UsageEvent;

import java.sql.Timestamp;

@Repository
public class UsageEventDao {
    private final JdbcTemplate jdbcTemplate;

    public UsageEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UsageEvent event) {
        jdbcTemplate.update("""
                INSERT INTO usage_events
                (event_key, request_id, timestamp, source, provider, endpoint, auth_type, auth_index, api_group_key,
                 api_key_hash, model, model_alias, executor_type, service_tier, response_service_tier, reasoning_effort,
                 failed, generate, latency_ms, ttft_ms, input_tokens, output_tokens, reasoning_tokens, cached_tokens,
                 cache_read_tokens, cache_creation_tokens, total_tokens, inbox_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventKey(), event.requestId(), Timestamp.from(event.timestamp()), event.source(), event.provider(),
                event.endpoint(), event.authType(), event.authIndex(), event.apiGroupKey(), event.apiKeyHash(), event.model(),
                event.modelAlias(), event.executorType(), event.serviceTier(), event.responseServiceTier(), event.reasoningEffort(),
                event.failed(), event.generate(), event.latencyMs(), event.ttftMs(), event.inputTokens(), event.outputTokens(),
                event.reasoningTokens(), event.cachedTokens(), event.cacheReadTokens(), event.cacheCreationTokens(),
                event.totalTokens(), event.inboxId(), Timestamp.from(event.createdAt()), Timestamp.from(event.updatedAt()));
    }
}
