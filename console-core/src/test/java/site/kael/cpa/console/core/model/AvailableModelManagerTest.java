package site.kael.cpa.console.core.model;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.cpa.model.CpaModel;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvailableModelManagerTest {
    @Test
    void mergesModelsAndCredentialTagsByCredential() {
        Instant now = Instant.now();
        Credential first = new Credential(1L, "auth-1", "凭证一", "auth_file", true, List.of("主账号"), "openai", "", "", 0, 0, List.of(), now, now);
        Credential second = new Credential(2L, "auth-2", "凭证二", "auth_file", true, List.of("备用账号"), "openai", "", "", 0, 0, List.of(), now, now);
        Credential disabled = new Credential(3L, "auth-3", "停用凭证", "auth_file", false, List.of("停用"), "openai", "", "", 0, 0, List.of(), now, now);
        CredentialManager credentials = new CredentialManager(null, null) {
            @Override
            public synchronized List<Credential> synchronizeAndFindAll() {
                return List.of(first, second, disabled);
            }
        };
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO) {
            @Override
            public List<CpaModel> listModels(String apiKey) {
                return List.of(new CpaModel("gpt-5", "openai"), new CpaModel("claude-4", "anthropic"));
            }
        };

        var models = new AvailableModelManager(credentials, cpa).list("user-key");

        assertEquals(2, models.size());
        assertEquals(List.of("主账号", "备用账号"), models.get(0).tags());
        assertEquals(List.of(), models.get(1).tags());
    }
}
