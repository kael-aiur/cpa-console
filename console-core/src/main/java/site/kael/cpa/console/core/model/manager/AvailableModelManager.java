package site.kael.cpa.console.core.model.manager;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.cpa.model.CpaModel;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.model.dao.AvailableModelDao;
import site.kael.cpa.console.core.model.model.AvailableModel;
import site.kael.cpa.console.core.user.manager.UserManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AvailableModelManager {
    private final AvailableModelDao dao;
    private final CredentialManager credentialManager;
    private final CpaApiKeyManager cpaApiKeyManager;
    private final UserManager userManager;
    private final LiteLlmModelMatcher matcher;

    @Autowired
    public AvailableModelManager(AvailableModelDao dao, CredentialManager credentialManager, CpaApiKeyManager cpaApiKeyManager, UserManager userManager, LiteLlmModelMatcher matcher) {
        this.dao = dao; this.credentialManager = credentialManager; this.cpaApiKeyManager = cpaApiKeyManager; this.userManager = userManager; this.matcher = matcher;
    }

    public AvailableModelManager(CredentialManager credentialManager, CpaApiKeyManager cpaApiKeyManager) {
        this(null, credentialManager, cpaApiKeyManager, null, null);
    }

    public List<AvailableModel> list() { return withTags(dao.findAll()); }
    public List<AvailableModel> list(String ignoredApiKey) {
        if (dao != null) return list();
        Map<String, LinkedHashSet<String>> tagsByOwner = new LinkedHashMap<>();
        for (Credential credential : (dao == null ? credentialManager.synchronizeAndFindAll() : credentialManager.findAllLocal())) {
            if (!credential.enabled()) continue;
            String owner = normalize(credential.provider());
            if (owner.isBlank()) owner = normalize(credential.type());
            tagsByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).addAll(credential.tags() == null ? List.of() : credential.tags());
        }
        Map<String, LinkedHashSet<String>> names = new LinkedHashMap<>();
        for (CpaModel model : cpaApiKeyManager.listModels(ignoredApiKey)) {
            if (model.id() == null || model.id().isBlank()) continue;
            names.computeIfAbsent(model.id().trim(), ignored -> new LinkedHashSet<>()).addAll(tagsByOwner.getOrDefault(normalize(model.ownedBy()), new LinkedHashSet<>()));
        }
        return names.entrySet().stream().map(e -> new AvailableModel(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    public synchronized int synchronize() {
        var user = userManager.findAll().stream().filter(u -> u.enabled()).findFirst().orElse(null);
        if (user == null) return dao.findAll().size();
        return dao.synchronize(cpaApiKeyManager.listModels(userManager.apiKey(user)), matcher::match).size();
    }

    public AvailableModel updateLiteLlmModelId(long id, String modelId) { return dao.updateLiteLlmModelId(id, modelId); }

    private List<AvailableModel> withTags(List<AvailableModel> models) {
        Map<String, LinkedHashSet<String>> tagsByOwner = new LinkedHashMap<>();
        for (Credential credential : (dao == null ? credentialManager.synchronizeAndFindAll() : credentialManager.findAllLocal())) {
            if (!credential.enabled()) continue;
            String owner = normalize(credential.provider());
            if (owner.isBlank()) owner = normalize(credential.type());
            tagsByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).addAll(credential.tags() == null ? List.of() : credential.tags());
        }
        return models.stream().map(model -> new AvailableModel(model.id(), model.name(), model.ownedBy(),
                List.copyOf(tagsByOwner.getOrDefault(normalize(model.ownedBy()), new LinkedHashSet<>(model.tags() == null ? List.of() : model.tags()))), model.litellmModelId())).toList();
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai", "codex" -> "openai";
            case "claude", "anthropic" -> "anthropic";
            case "gemini", "aistudio", "vertex", "antigravity", "google" -> "antigravity";
            case "xai", "grok" -> "xai";
            default -> normalized;
        };
    }
}
