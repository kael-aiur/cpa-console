package site.kael.cpa.console.core.credential.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import site.kael.cpa.console.core.credential.model.CpaCredential;
import site.kael.cpa.console.core.credential.model.Credential;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class CredentialDao {
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> REQUESTS_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CredentialDao(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureColumns();
    }

    public java.util.Optional<Credential> findByReferenceId(String referenceId) {
        List<Credential> credentials = jdbcTemplate.query("""
                SELECT id, reference_id, name, credential_type, enabled, provider, base_url, project_id,
                       success, failed, recent_requests, tags, created_at, updated_at
                FROM credentials WHERE reference_id = ? LIMIT 1
                """, this::mapCredential, referenceId);
        return credentials.stream().findFirst();
    }

    public List<Credential> findAll() {
        return jdbcTemplate.query("""
                SELECT id, reference_id, name, credential_type, enabled, provider, base_url, project_id,
                       success, failed, recent_requests, tags, created_at, updated_at
                FROM credentials ORDER BY name COLLATE NOCASE, id
                """, this::mapCredential);
    }

    public List<Credential> synchronize(List<CpaCredential> remoteCredentials) {
        Instant now = Instant.now();
        for (CpaCredential remote : remoteCredentials) {
            jdbcTemplate.update("""
                    INSERT INTO credentials (reference_id, name, credential_type, enabled, provider, base_url, project_id,
                                             success, failed, recent_requests, tags, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', ?, ?)
                    ON CONFLICT(reference_id) DO UPDATE SET
                        name = excluded.name,
                        credential_type = excluded.credential_type,
                        enabled = excluded.enabled,
                        provider = excluded.provider,
                        base_url = excluded.base_url,
                        success = CASE WHEN excluded.success = 0 AND excluded.failed = 0 AND excluded.recent_requests = '[]' THEN credentials.success ELSE excluded.success END,
                        failed = CASE WHEN excluded.success = 0 AND excluded.failed = 0 AND excluded.recent_requests = '[]' THEN credentials.failed ELSE excluded.failed END,
                        recent_requests = CASE WHEN excluded.success = 0 AND excluded.failed = 0 AND excluded.recent_requests = '[]' THEN credentials.recent_requests ELSE excluded.recent_requests END,
                        updated_at = excluded.updated_at
                    """, remote.referenceId(), remote.name(), remote.type(), remote.enabled(), remote.provider(),
                    remote.baseUrl(), remote.projectId(), remote.success(), remote.failed(), writeJson(remote.recentRequests()),
                    Timestamp.from(now), Timestamp.from(now));
        }
        if (remoteCredentials.isEmpty()) {
            jdbcTemplate.update("DELETE FROM credentials");
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(remoteCredentials.size(), "?"));
            jdbcTemplate.update("DELETE FROM credentials WHERE reference_id NOT IN (" + placeholders + ")",
                    remoteCredentials.stream().map(CpaCredential::referenceId).toArray());
        }
        return findAll();
    }

    public Credential updateTags(long id, List<String> tags) {
        int count = jdbcTemplate.update("UPDATE credentials SET tags = ?, updated_at = ? WHERE id = ?",
                writeJson(tags == null ? List.of() : tags), Timestamp.from(Instant.now()), id);
        if (count == 0) throw new IllegalArgumentException("Credential not found: " + id);
        return jdbcTemplate.queryForObject("""
                SELECT id, reference_id, name, credential_type, enabled, provider, base_url, project_id,
                       success, failed, recent_requests, tags, created_at, updated_at
                FROM credentials WHERE id = ?
                """, this::mapCredential, id);
    }

    private void ensureColumns() {
        for (String sql : List.of(
                "ALTER TABLE credentials ADD COLUMN provider TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE credentials ADD COLUMN base_url TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE credentials ADD COLUMN project_id TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE credentials ADD COLUMN success INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE credentials ADD COLUMN failed INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE credentials ADD COLUMN recent_requests TEXT NOT NULL DEFAULT '[]'")) {
            try {
                jdbcTemplate.execute(sql);
            } catch (org.springframework.dao.DataAccessException ignored) {
                // Existing installations already have the column.
            }
        }
    }

    private Credential mapCredential(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Credential(rs.getLong("id"), rs.getString("reference_id"), rs.getString("name"),
                rs.getString("credential_type"), rs.getBoolean("enabled"), readList(rs.getString("tags"), TAGS_TYPE),
                rs.getString("provider"), rs.getString("base_url"), rs.getString("project_id"), rs.getLong("success"), rs.getLong("failed"),
                readList(rs.getString("recent_requests"), REQUESTS_TYPE), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            return List.copyOf(objectMapper.readValue(json, type));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize credential data", exception);
        }
    }
}
