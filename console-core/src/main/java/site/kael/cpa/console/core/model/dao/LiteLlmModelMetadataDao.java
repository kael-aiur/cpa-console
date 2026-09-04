package site.kael.cpa.console.core.model.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.model.model.LiteLlmModelMetadata;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class LiteLlmModelMetadataDao {
    private final JdbcTemplate jdbc;

    public LiteLlmModelMetadataDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<LiteLlmModelMetadata> findAll() {
        return jdbc.query("SELECT model_id, provider, mode, max_input_tokens, max_output_tokens, max_tokens, metadata_json, synced_at FROM litellm_model_metadata ORDER BY model_id COLLATE NOCASE", this::map);
    }

    public java.util.Optional<LiteLlmModelMetadata> findById(String id) {
        return jdbc.query("SELECT model_id, provider, mode, max_input_tokens, max_output_tokens, max_tokens, metadata_json, synced_at FROM litellm_model_metadata WHERE model_id = ?", this::map, id).stream().findFirst();
    }

    public void replaceAll(List<LiteLlmModelMetadata> entries) {
        Instant now = Instant.now();
        jdbc.update("DELETE FROM litellm_model_metadata");
        for (LiteLlmModelMetadata entry : entries) {
            jdbc.update("INSERT INTO litellm_model_metadata (model_id, provider, mode, max_input_tokens, max_output_tokens, max_tokens, metadata_json, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    entry.modelId(), entry.provider(), entry.mode(), entry.maxInputTokens(), entry.maxOutputTokens(), entry.maxTokens(), entry.metadataJson(), Timestamp.from(now));
        }
    }

    private LiteLlmModelMetadata map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new LiteLlmModelMetadata(rs.getString("model_id"), rs.getString("provider"), rs.getString("mode"),
                nullableLong(rs, "max_input_tokens"), nullableLong(rs, "max_output_tokens"), nullableLong(rs, "max_tokens"), rs.getString("metadata_json"), rs.getTimestamp("synced_at").toInstant());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
