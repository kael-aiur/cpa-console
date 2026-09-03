package site.kael.cpa.console.model.service;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;
import site.kael.cpa.console.core.model.model.AvailableModel;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.core.user.model.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvailableModelServiceTest {
    @Test
    void returnsTheCachedModelListForTheSameUser() {
        AtomicInteger loads = new AtomicInteger();
        AvailableModelManager modelManager = new AvailableModelManager(null, (CpaApiKeyManager) null) {
            @Override
            public List<AvailableModel> list(String apiKey) {
                loads.incrementAndGet();
                return List.of(new AvailableModel("gpt-5", List.of("openai")));
            }
        };
        UserManager userManager = new UserManager(null, null) {
            @Override
            public String apiKey(User user) {
                return "user-key";
            }
        };
        AvailableModelService service = new AvailableModelService(modelManager, userManager);
        User user = new User(1L, "管理员", UserRole.ADMIN, "same-user", "", true, Instant.now(), Instant.now());

        service.list(user);
        var cached = service.list(user);

        assertEquals(1, loads.get());
        assertEquals(List.of("gpt-5"), cached.models().stream().map(model -> model.name()).toList());
    }
}
