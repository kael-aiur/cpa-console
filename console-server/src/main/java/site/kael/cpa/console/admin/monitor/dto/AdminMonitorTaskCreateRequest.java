package site.kael.cpa.console.admin.monitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.Set;

/**
 * D7/T10 create request. Jackson forwards every recognized key to
 * {@link #recognizedKeys()}; the service layer rejects anything else before the manager
 * sees it, so unknown or immutable-target fields can never slip through.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminMonitorTaskCreateRequest(
        String safe_name,
        String cron_expression,
        String zone_id,
        String instance_key,
        String model_choice_id,
        java.util.List<String> rawKeys) {

    /** Transport keys this contract accepts for creation. */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "safe_name", "cron_expression", "zone_id", "instance_key", "model_choice_id");

    /** Guard against Jackson producing a null record (missing body). */
    public AdminMonitorTaskCreateRequest {
        if (rawKeys == null) {
            rawKeys = java.util.List.of();
        }
    }

    public java.util.List<String> rawKeys() {
        return rawKeys == null ? java.util.List.of() : rawKeys;
    }

    /** Collects the keys actually present on the incoming JSON object. */
    public static java.util.List<String> readKeys(Map<String, Object> source) {
        return source == null ? java.util.List.of() : java.util.List.copyOf(source.keySet());
    }
}
