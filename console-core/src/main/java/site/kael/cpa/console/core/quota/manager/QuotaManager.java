package site.kael.cpa.console.core.quota.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QuotaManager {
    private final CredentialManager credentialManager;
    private final CpaApiKeyManager cpaApiKeyManager;

    public QuotaManager(CredentialManager credentialManager, CpaApiKeyManager cpaApiKeyManager) {
        this.credentialManager = credentialManager;
        this.cpaApiKeyManager = cpaApiKeyManager;
    }

    public List<Map<String, Object>> listProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();
        // Keep the quota provider list in lockstep with the admin credential list.
        // CredentialManager preserves local tags while synchronizing CPA metadata and
        // falls back to the last local snapshot when CPA is unavailable.
        for (Credential credential : credentialManager.synchronizeAndFindAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            String provider = identifyProvider(credential);
            item.put("id", credential.referenceId());
            item.put("auth_index", credential.referenceId());
            item.put("name", credential.name());
            item.put("provider", provider);
            item.put("type", provider);
            item.put("credential_type", credential.type());
            item.put("disabled", !credential.enabled());
            item.put("status", credential.enabled() ? "active" : "error");
            item.put("success", credential.success());
            item.put("failed", credential.failed());
            item.put("recent_requests", credential.recentRequests());
            item.put("tags", credential.tags());
            item.put("base_url", credential.baseUrl());
            item.put("source", "database");
            item.put("runtime_only", false);
            item.put("unavailable", false);
            item.put("account", "");
            item.put("account_type", credential.type());
            item.put("email", "");
            item.put("label", credential.name());
            item.put("size", 0);
            providers.add(item);
        }
        return providers;
    }

    public Map<String, Object> getQuota(String referenceId) {
        Credential credential = credentialManager.findByReferenceId(referenceId)
                .orElseThrow(() -> new IllegalArgumentException("credential not found: " + referenceId));
        if ("auth_file".equals(credential.type())) {
            if (supportsAuthFileQuota(credential.provider())) {
                return cpaApiKeyManager.getAuthFileQuota(referenceId, credential.provider(), credential.projectId());
            }
            return usageBasedQuota(identifyProvider(credential));
        }
        String provider = identifyProvider(credential);
        if ("zhipu".equals(provider)) {
            return cpaApiKeyManager.getZhipuQuota(referenceId, credential.baseUrl());
        }
        // OpenRouter is usage-based; do not probe it like finite-quota providers.
        return usageBasedQuota(provider);
    }

    private Map<String, Object> usageBasedQuota(String provider) {
        return Map.of(
                "provider", provider,
                "tierName", "按量计费",
                "windows", List.of()
        );
    }

    private boolean supportsAuthFileQuota(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "codex", "openai", "claude", "anthropic", "kimi", "antigravity", "gemini" -> true;
            default -> false;
        };
    }

    private String identifyProvider(Credential credential) {
        if ("auth_file".equals(credential.type())) return normalizeProvider(credential.provider());
        String host = host(credential.baseUrl());
        if (host.contains("moonshot") || host.contains("kimi")) return "kimi";
        if (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai")) return "openrouter";
        if (host.contains("bigmodel") || host.equals("api.z.ai") || host.endsWith(".api.z.ai")
                || host.contains("zhipu") || host.contains("glm")) return "zhipu";
        if (host.contains("anthropic")) return "anthropic";
        if (host.contains("openai") || host.contains("chatgpt")) return "codex";
        if (host.contains("x.ai") || host.contains("xai") || host.contains("grok")) return "grok";
        if (host.contains("googleapis") || host.contains("gemini")) return "gemini";
        if (host.contains("vertex")) return "vertex";
        return normalizeProvider(credential.provider());
    }

    private String normalizeProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase();
        return switch (value) {
            case "claude" -> "anthropic";
            case "openai" -> "codex";
            case "xai" -> "grok";
            default -> value.isBlank() ? "unknown" : value;
        };
    }

    private String host(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            String host = URI.create(value).getHost();
            return host == null ? value.toLowerCase() : host.toLowerCase();
        } catch (IllegalArgumentException exception) {
            return value.toLowerCase();
        }
    }
}
