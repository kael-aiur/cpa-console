package site.kael.cpa.console.core.cpa.manager;

import site.kael.cpa.console.core.cpa.client.CpaApiClient;
import site.kael.cpa.console.core.cpa.model.CpaModel;
import site.kael.cpa.console.core.credential.model.CpaCredential;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.Map;

import java.time.Duration;

public class CpaApiKeyManager {
    private final CpaApiClient client;
    private final Duration timeout;

    public CpaApiKeyManager(CpaApiClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    public void validate(String apiKey) {
        client.validateApiKey(apiKey, timeout);
    }

    public String create() {
        return client.createApiKey(timeout);
    }

    public HttpResponse<InputStream> createResponseStream(String apiKey, JsonNode requestBody) {
        return client.createResponseStream(apiKey, requestBody, timeout);
    }

    public List<CpaCredential> listCredentials() {
        return client.listCredentials(timeout);
    }

    public List<CpaModel> listModels(String apiKey) {
        return client.listModels(apiKey, timeout);
    }

    public List<Map<String, Object>> listQuotaProviders() {
        return client.listQuotaProviders(timeout);
    }

    public Map<String, Object> getQuota(String referenceId) {
        return client.getQuota(referenceId, timeout);
    }

    public Map<String, Object> getAuthFileQuota(String referenceId, String provider, String projectId) {
        return client.getAuthFileQuota(referenceId, provider, projectId, timeout);
    }

    public Map<String, Object> getZhipuQuota(String referenceId, String baseUrl) {
        return client.getZhipuQuota(referenceId, baseUrl, timeout);
    }
}
