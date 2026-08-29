package site.kael.cpa.console.core.model.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.cpa.model.CpaModel;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.model.model.AvailableModel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class AvailableModelManager {
    private final CredentialManager credentialManager;
    private final CpaApiKeyManager cpaApiKeyManager;

    public AvailableModelManager(CredentialManager credentialManager, CpaApiKeyManager cpaApiKeyManager) {
        this.credentialManager = credentialManager;
        this.cpaApiKeyManager = cpaApiKeyManager;
    }

    public List<AvailableModel> list(String apiKey) {
        Map<String, LinkedHashSet<String>> tagsByOwner = new LinkedHashMap<>();
        for (Credential credential : credentialManager.synchronizeAndFindAll()) {
            if (!credential.enabled()) continue;
            String owner = ownerKey(credential);
            if (owner.isBlank()) continue;
            tagsByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>())
                    .addAll(credential.tags() == null ? List.of() : credential.tags());
        }

        Map<String, LinkedHashSet<String>> modelTags = new LinkedHashMap<>();
        for (CpaModel model : cpaApiKeyManager.listModels(apiKey)) {
            String name = model.id() == null ? "" : model.id().trim();
            if (!name.isBlank()) {
                modelTags.computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                        .addAll(tagsByOwner.getOrDefault(normalize(model.ownedBy()), new LinkedHashSet<>()));
            }
        }
        return modelTags.entrySet().stream()
                .map(entry -> new AvailableModel(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private String ownerKey(Credential credential) {
        String provider = normalize(credential.provider());
        return provider.isBlank() ? normalize(credential.type()) : provider;
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "openai", "codex" -> "openai";
            case "claude", "anthropic" -> "anthropic";
            case "gemini", "aistudio", "vertex", "antigravity", "google" -> "antigravity";
            case "xai", "grok" -> "xai";
            default -> normalized;
        };
    }
}
