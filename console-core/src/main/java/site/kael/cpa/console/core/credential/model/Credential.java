package site.kael.cpa.console.core.credential.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Credential(Long id, String referenceId, String name, String type, boolean enabled,
                         List<String> tags, String provider, String baseUrl, String projectId, long success, long failed,
                         List<Map<String, Object>> recentRequests, Instant createdAt, Instant updatedAt) {
}
