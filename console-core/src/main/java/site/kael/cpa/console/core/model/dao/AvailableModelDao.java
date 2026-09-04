package site.kael.cpa.console.core.model.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.cpa.model.CpaModel;
import site.kael.cpa.console.core.model.model.AvailableModel;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Repository
public class AvailableModelDao {
    private static final TypeReference<List<String>> TAGS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AvailableModelDao(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<AvailableModel> findAll() {
        return jdbc.query("SELECT id, name, owned_by, tags, litellm_model_id FROM available_models ORDER BY name COLLATE NOCASE, id", this::map);
    }

    public List<AvailableModel> synchronize(List<CpaModel> remote, java.util.function.BiFunction<String, String, String> automaticMapping) {
        Instant now = Instant.now();
        for (CpaModel model : remote) {
            String name = model.id() == null ? "" : model.id().trim();
            if (name.isBlank()) continue;
            String ownedBy = model.ownedBy() == null ? "" : model.ownedBy().trim();
            String existing = jdbc.query("SELECT litellm_model_id FROM available_models WHERE name = ?", rs -> rs.next() ? rs.getString(1) : null, name);
            String mapped = existing == null || existing.isBlank() ? automaticMapping.apply(name, ownedBy) : existing;
            jdbc.update("""
                    INSERT INTO available_models (name, owned_by, tags, litellm_model_id, discovered_at, created_at, updated_at)
                    VALUES (?, ?, '[]', ?, ?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET owned_by = excluded.owned_by, discovered_at = excluded.discovered_at,
                        litellm_model_id = CASE WHEN available_models.litellm_model_id IS NULL OR available_models.litellm_model_id = '' THEN excluded.litellm_model_id ELSE available_models.litellm_model_id END,
                        updated_at = excluded.updated_at
                    """, name, ownedBy, mapped, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        if (remote.isEmpty()) jdbc.update("DELETE FROM available_models");
        else {
            String placeholders = String.join(",", java.util.Collections.nCopies(remote.size(), "?"));
            jdbc.update("DELETE FROM available_models WHERE name NOT IN (" + placeholders + ")", remote.stream().map(CpaModel::id).toArray());
        }
        return findAll();
    }

    public AvailableModel updateLiteLlmModelId(long id, String modelId) {
        String normalized = modelId == null || modelId.isBlank() ? null : modelId.trim();
        int count = jdbc.update("UPDATE available_models SET litellm_model_id = ?, updated_at = ? WHERE id = ?", normalized, Timestamp.from(Instant.now()), id);
        if (count == 0) throw new IllegalArgumentException("Available model not found: " + id);
        return jdbc.queryForObject("SELECT id, name, owned_by, tags, litellm_model_id FROM available_models WHERE id = ?", this::map, id);
    }

    private AvailableModel map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            return new AvailableModel(rs.getLong("id"), rs.getString("name"), rs.getString("owned_by"),
                    List.copyOf(new LinkedHashSet<>(mapper.readValue(rs.getString("tags"), TAGS))), rs.getString("litellm_model_id"));
        } catch (Exception exception) {
            return new AvailableModel(rs.getLong("id"), rs.getString("name"), rs.getString("owned_by"), List.of(), rs.getString("litellm_model_id"));
        }
    }
}
