package site.kael.cpa.console.model.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.core.model.model.AvailableModel;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.model.dto.AvailableModelListResponse;
import site.kael.cpa.console.model.dto.AvailableModelResponse;
import site.kael.cpa.console.model.dto.CodexModelCatalogResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AvailableModelService {
    private static final Duration MODEL_CACHE_TTL = Duration.ofMinutes(10);

    private final AvailableModelManager availableModelManager;
    private final UserManager userManager;
    private final Map<String, CachedModelList> modelCache = new ConcurrentHashMap<>();

    public AvailableModelService(AvailableModelManager availableModelManager, UserManager userManager) {
        this.availableModelManager = availableModelManager;
        this.userManager = userManager;
    }

    public AvailableModelListResponse list(User user) {
        String cacheKey = user.apiKeyHash();
        CachedModelList cached = modelCache.get(cacheKey);
        if (cached != null && !cached.expired()) return cached.response();

        var availableModels = availableModels(user);
        var models = availableModels.stream().map(AvailableModelResponse::from).toList();
        var tags = models.stream().flatMap(model -> model.tags().stream()).distinct().sorted().toList();
        AvailableModelListResponse response = new AvailableModelListResponse(models, models.size(), tags);
        modelCache.put(cacheKey, new CachedModelList(response));
        return response;
    }

    public CodexModelCatalogResponse codexModelCatalog(User user) {
        return CodexModelCatalogResponse.from(availableModels(user));
    }

    private java.util.List<AvailableModel> availableModels(User user) {
        return availableModelManager.list(userManager.apiKey(user));
    }

    private record CachedModelList(AvailableModelListResponse response, long expiresAt) {
        private CachedModelList(AvailableModelListResponse response) {
            this(response, System.currentTimeMillis() + MODEL_CACHE_TTL.toMillis());
        }

        private boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
