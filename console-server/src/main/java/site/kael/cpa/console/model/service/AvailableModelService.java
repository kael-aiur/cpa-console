package site.kael.cpa.console.model.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import site.kael.cpa.console.core.model.model.AvailableModel;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;
import site.kael.cpa.console.core.model.manager.LiteLlmModelManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.model.dto.AvailableModelListResponse;
import site.kael.cpa.console.model.dto.AvailableModelResponse;
import site.kael.cpa.console.model.dto.CodexModelCatalogResponse;

@Service
public class AvailableModelService {
    private final AvailableModelManager availableModelManager;
    private final LiteLlmModelManager liteLlmModelManager;

    public AvailableModelService(AvailableModelManager availableModelManager, UserManager ignoredUserManager) {
        this(availableModelManager, ignoredUserManager, null);
    }

    @Autowired
    public AvailableModelService(AvailableModelManager availableModelManager, UserManager ignoredUserManager, LiteLlmModelManager liteLlmModelManager) {
        this.availableModelManager = availableModelManager;
        this.liteLlmModelManager = liteLlmModelManager;
    }

    public AvailableModelListResponse list(User user) {
        var models = availableModelManager.list(user == null ? "" : user.apiKeyHash());
        var responses = models.stream().map(AvailableModelResponse::from).toList();
        var tags = responses.stream().flatMap(model -> model.tags().stream()).distinct().sorted().toList();
        return new AvailableModelListResponse(responses, responses.size(), tags);
    }

    public CodexModelCatalogResponse codexModelCatalog(User user) {
        return CodexModelCatalogResponse.from(availableModelManager.list(user == null ? "" : user.apiKeyHash()), modelId -> modelId == null || liteLlmModelManager == null ? null : liteLlmModelManager.find(modelId).orElse(null));
    }
}
