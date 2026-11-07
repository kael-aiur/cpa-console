package site.kael.cpa.console.admin.monitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Set;

/**
 * D7/T10 PATCH request. Only safe-name/cron/zone/enabled plus the required
 * expected_revision are recognized; the service layer rejects any other key before the
 * domain object sees it, so the immutable target can never be changed through this API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminMonitorTaskUpdateRequest(
        Long expected_revision,
        String safe_name,
        String cron_expression,
        String zone_id,
        Boolean enabled,
        List<String> rawKeys) {

    /** Transport keys this contract accepts for updates. */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "expected_revision", "safe_name", "cron_expression", "zone_id", "enabled");

    public AdminMonitorTaskUpdateRequest {
        if (rawKeys == null) {
            rawKeys = List.of();
        }
    }

    public List<String> rawKeys() {
        return rawKeys == null ? List.of() : rawKeys;
    }

    /** Collects the keys actually present on the incoming JSON object. */
    public static List<String> readKeys(java.util.Map<String, Object> source) {
        return source == null ? List.of() : List.copyOf(source.keySet());
    }
}
