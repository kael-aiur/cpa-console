package site.kael.cpa.console.core.credential.model;

import java.util.List;
import java.util.Map;

public record CpaCredential(String referenceId, String name, String type, boolean enabled,
                            String provider, String baseUrl, String projectId, long success, long failed,
                            List<Map<String, Object>> recentRequests) {
    public CpaCredential(String referenceId, String name, String type, boolean enabled) {
        this(referenceId, name, type, enabled, providerForType(type), "", "", 0, 0, List.of());
    }

    private static String providerForType(String type) {
        return "auth_file".equals(type) ? "" : type;
    }
}
