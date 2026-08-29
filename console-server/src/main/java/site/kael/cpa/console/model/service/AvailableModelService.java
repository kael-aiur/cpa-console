package site.kael.cpa.console.model.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.model.dto.AvailableModelListResponse;
import site.kael.cpa.console.model.dto.AvailableModelResponse;

@Service
public class AvailableModelService {
    private final AvailableModelManager availableModelManager;
    private final UserManager userManager;

    public AvailableModelService(AvailableModelManager availableModelManager, UserManager userManager) {
        this.availableModelManager = availableModelManager;
        this.userManager = userManager;
    }

    public AvailableModelListResponse list(User user) {
        var models = availableModelManager.list(userManager.apiKey(user)).stream().map(AvailableModelResponse::from).toList();
        var tags = models.stream().flatMap(model -> model.tags().stream()).distinct().sorted().toList();
        return new AvailableModelListResponse(models, models.size(), tags);
    }
}
