package site.kael.cpa.console.core.model.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.model.dao.LiteLlmModelMetadataDao;
import site.kael.cpa.console.core.model.dao.LiteLlmSyncConfigDao;
import site.kael.cpa.console.core.model.model.LiteLlmModelMetadata;
import site.kael.cpa.console.core.model.model.LiteLlmSyncConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class LiteLlmModelManager {
    private final LiteLlmModelMetadataDao metadataDao;
    private final LiteLlmSyncConfigDao configDao;
    private final ObjectMapper objectMapper;

    public LiteLlmModelManager(LiteLlmModelMetadataDao metadataDao, LiteLlmSyncConfigDao configDao, ObjectMapper objectMapper) {
        this.metadataDao = metadataDao;
        this.configDao = configDao;
        this.objectMapper = objectMapper;
    }

    public List<LiteLlmModelMetadata> list() { return metadataDao.findAll(); }
    public java.util.Optional<LiteLlmModelMetadata> find(String modelId) { return metadataDao.findById(modelId); }
    public LiteLlmSyncConfig config() { return configDao.get(); }
    public LiteLlmSyncConfig updateConfig(String url, boolean proxyEnabled, String proxyHost, int proxyPort) { return configDao.update(url, proxyEnabled, proxyHost, proxyPort); }

    public synchronized int synchronize() {
        LiteLlmSyncConfig config = configDao.get();
        try {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
            if (config.proxyEnabled()) builder.proxy(ProxySelector.of(new InetSocketAddress(config.proxyHost(), config.proxyPort())));
            HttpResponse<String> response = builder.build().send(HttpRequest.newBuilder(URI.create(config.url())).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isObject()) throw new IOException("metadata response must be a JSON object");
            List<LiteLlmModelMetadata> entries = new ArrayList<>();
            root.fields().forEachRemaining(field -> {
                if (field.getKey().equals("sample_spec") || field.getKey().equals("fallback_generalizations") || !field.getValue().isObject()) return;
                JsonNode value = field.getValue();
                entries.add(new LiteLlmModelMetadata(field.getKey(), text(value, "litellm_provider"), text(value, "mode"), number(value, "max_input_tokens"), number(value, "max_output_tokens"), number(value, "max_tokens"), value.toString(), java.time.Instant.now()));
            });
            metadataDao.replaceAll(entries);
            return entries.size();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LiteLLM metadata synchronization interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("LiteLLM metadata synchronization failed: " + exception.getMessage(), exception);
        }
    }

    private String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private Long number(JsonNode node, String field) { return node.path(field).canConvertToLong() ? node.path(field).asLong() : null; }
}
