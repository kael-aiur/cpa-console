package site.kael.cpa.console.core.monitor.manager;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * D7 safe-name/safe-model leak guard (T02).
 * <p>
 * A task name or public model display is rejected when it looks like an email, URL, file path,
 * credential header, key/token literal or contains control characters, or when it matches any
 * sensitive field observed on the current candidate. Anything uncertain is rejected; callers
 * must ask for a neutral name rather than relax the rules.
 */
public final class SafeNameValidator {

    private SafeNameValidator() {
    }

    /** True when the value is safe to display and matches no sensitive candidate field. */
    public static boolean isSafe(String value, List<String> sensitiveFields) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return false;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.matches(".*[\\s(\"';<>=].*") && !trimmed.equals(trimmed.strip())) {
            return false;
        }
        if (lower.contains("@")) {
            return false;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")) {
            return false;
        }
        if (lower.startsWith("/") || lower.startsWith("./") || lower.startsWith("../") || lower.contains("../")) {
            return false;
        }
        if (lower.contains(".json") || lower.endsWith(".json")) {
            return false;
        }
        if (lower.startsWith("authorization") || lower.startsWith("x-management-key") || lower.startsWith("bearer ")) {
            return false;
        }
        if (lower.contains("api-key") || lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("credential") || lower.contains("key=") || lower.contains("auth_token")) {
            return false;
        }
        if (sensitiveFields != null) {
            for (String sensitive : sensitiveFields) {
                if (sensitive == null || sensitive.isBlank()) {
                    continue;
                }
                String normalized = sensitive.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (lower.contains(normalized) || normalized.contains(lower)) {
                    return false;
                }
            }
        }
        return true;
    }
}
