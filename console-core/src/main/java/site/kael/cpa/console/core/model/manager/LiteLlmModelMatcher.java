package site.kael.cpa.console.core.model.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.model.model.LiteLlmModelMetadata;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LiteLlmModelMatcher {
    private final LiteLlmModelManager modelManager;
    public LiteLlmModelMatcher(LiteLlmModelManager modelManager) { this.modelManager = modelManager; }

    public String match(String modelName, String ownedBy) {
        List<LiteLlmModelMetadata> all = modelManager.list();
        Map<String, LiteLlmModelMetadata> exact = new HashMap<>();
        for (LiteLlmModelMetadata item : all) exact.put(item.modelId(), item);
        for (String candidate : candidates(modelName, ownedBy)) if (exact.containsKey(candidate)) return candidate;
        return null;
    }

    private List<String> candidates(String name, String ownedBy) {
        String raw = name == null ? "" : name.trim();
        String owner = normalize(ownedBy);
        String bare = raw.contains("/") ? raw.substring(raw.indexOf('/') + 1) : raw;
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (!raw.isBlank()) candidates.add(raw);
        if (!owner.isBlank() && !bare.isBlank()) candidates.add(owner + "/" + bare);
        if (!bare.isBlank()) candidates.add(bare);
        return List.copyOf(candidates);
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "codex", "openai" -> "openai";
            case "claude", "anthropic" -> "anthropic";
            case "google", "gemini", "aistudio" -> "gemini";
            case "vertex" -> "vertex_ai";
            default -> normalized;
        };
    }
}
