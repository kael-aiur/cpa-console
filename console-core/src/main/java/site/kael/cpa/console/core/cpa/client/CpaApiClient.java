package site.kael.cpa.console.core.cpa.client;

import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.exception.CpaUnavailableException;
import site.kael.cpa.console.core.cpa.exception.InvalidCpaApiKeyException;
import site.kael.cpa.console.core.credential.model.CpaCredential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public class CpaApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpaApiClient.class);

    private final HttpClient httpClient;
    private final URI modelsUri;
    private final URI managementApiKeysUri;
    private final String managementKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CpaApiClient(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, "");
    }

    public CpaApiClient(String baseUrl, Duration timeout, String managementKey) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelsUri = URI.create(normalized + "/v1/models");
        this.managementApiKeysUri = URI.create(normalized + "/v0/management/api-keys");
        this.managementKey = managementKey == null ? "" : managementKey.trim();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public void validateApiKey(String apiKey, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(modelsUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new InvalidCpaApiKeyException();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CpaUnavailableException(new IOException("CPA returned HTTP " + response.statusCode()));
            }
        } catch (InvalidCpaApiKeyException | CpaUnavailableException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CpaUnavailableException(exception);
        } catch (IOException exception) {
            throw new CpaUnavailableException(exception);
        }
    }


    public List<CpaCredential> listCredentials(Duration timeout) {
        if (managementKey.isBlank()) {
            throw new CpaManagementException("CPA management key is not configured");
        }
        List<CpaCredential> credentials = new ArrayList<>();
        JsonNode apiKeyUsage = loadApiKeyUsage(timeout);
        JsonNode authFiles = getManagementJson("/auth-files", timeout);
        JsonNode files = authFiles.path("files");
        if (files.isArray()) {
            for (JsonNode file : files) {
                String referenceId = firstText(file, "auth_index", "id", "name");
                // Auth-file names are managed by CPA and should be shown unchanged.
                String name = firstText(file, "name", "label", "id");
                if (!referenceId.isBlank()) {
                    credentials.add(new CpaCredential(referenceId, name, "auth_file", !file.path("disabled").asBoolean(false),
                            firstText(file, "provider", "type"), firstText(file, "base-url", "base_url"),
                            firstText(file, "project_id", "projectId"), file.path("success").asLong(0), file.path("failed").asLong(0),
                            objectMapper.convertValue(file.path("recent_requests"), List.class)));
                }
            }
        }

        String[][] providers = {
                {"/gemini-api-key", "gemini-api-key"},
                {"/interactions-api-key", "interactions-api-key"},
                {"/claude-api-key", "claude-api-key"},
                {"/codex-api-key", "codex-api-key"},
                {"/xai-api-key", "xai-api-key"},
                {"/vertex-api-key", "vertex-api-key"},
                {"/openai-compatibility", "openai-compatibility"}
        };
        for (String[] provider : providers) {
            JsonNode root = getManagementJson(provider[0], timeout);
            JsonNode entries = root.path(provider[1]);
            if (!entries.isArray()) continue;
            for (JsonNode entry : entries) {
                JsonNode nested = entry.path("api-key-entries");
                if ("openai-compatibility".equals(provider[1]) && nested.isArray() && nested.size() > 0) {
                    for (JsonNode apiKeyEntry : nested) {
                        addProviderCredential(credentials, provider[1], entry, apiKeyEntry, apiKeyUsage);
                    }
                } else {
                    addProviderCredential(credentials, provider[1], entry, entry, apiKeyUsage);
                }
            }
        }
        return credentials;
    }

    private void addProviderCredential(List<CpaCredential> credentials, String provider, JsonNode parent, JsonNode entry, JsonNode apiKeyUsage) {
        String referenceId = firstText(entry, "auth-index", "auth_index", "id");
        String apiKey = firstText(entry, "api-key", "api_key");
        String serviceDomain = serviceDomain(parent, entry);
        String name = serviceDomain + (apiKey.isBlank() ? "" : "(" + maskApiKey(apiKey) + ")");
        if (referenceId.isBlank()) {
            String identity = firstText(entry, "api-key", "name", "id") + "|" + firstText(parent, "base-url", "base_url");
            referenceId = provider + ":" + identity;
        }
        if (name.isBlank()) name = providerName(provider);
        String baseUrl = firstNonBlank(firstText(entry, "base-url", "base_url", "endpoint"), firstText(parent, "base-url", "base_url"));
        JsonNode usage = findApiKeyUsage(apiKeyUsage, provider, baseUrl, apiKey);
        credentials.add(new CpaCredential(referenceId, name, "apikey", !parent.path("disabled").asBoolean(false) && !entry.path("disabled").asBoolean(false),
                providerName(provider), baseUrl, "", usage.path("success").asLong(0), usage.path("failed").asLong(0),
                usage.isObject() ? objectMapper.convertValue(usage.path("recent_requests"), List.class) : List.of()));
    }

    private String providerName(String provider) {
        return switch (provider) {
            case "gemini-api-key" -> "Gemini";
            case "interactions-api-key" -> "Google Interactions";
            case "claude-api-key" -> "Anthropic";
            case "codex-api-key" -> "OpenAI";
            case "xai-api-key" -> "xAI";
            case "vertex-api-key" -> "Vertex";
            case "openai-compatibility" -> "OpenAI Compatible";
            case "kimi" -> "Kimi";
            case "claude", "anthropic" -> "Anthropic";
            case "codex", "openai" -> "OpenAI";
            case "gemini", "aistudio", "antigravity" -> "Google";
            case "xai", "grok" -> "xAI";
            case "vertex" -> "Vertex";
            default -> "AI Provider";
        };
    }

    private String serviceDomain(JsonNode parent, JsonNode entry) {
        String baseUrl = firstText(entry, "base-url", "base_url");
        if (baseUrl.isBlank()) baseUrl = firstText(parent, "base-url", "base_url");
        if (!baseUrl.isBlank()) {
            try {
                String host = URI.create(baseUrl).getHost();
                if (host != null && !host.isBlank()) return host;
            } catch (IllegalArgumentException ignored) {
                // Fall back to the provider endpoint label for malformed CPA URLs.
            }
        }
        return providerName(firstText(parent, "provider", "type"));
    }

    private String maskApiKey(String apiKey) {
        if (apiKey.length() <= 6) return "***";
        return apiKey.substring(0, 2) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    public List<java.util.Map<String, Object>> listQuotaProviders(Duration timeout) {
        JsonNode root = getManagementJson("/auth-files", timeout);
        List<java.util.Map<String, Object>> providers = new ArrayList<>();
        JsonNode files = root.path("files");
        if (!files.isArray()) return providers;
        for (JsonNode file : files) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> item = objectMapper.convertValue(file, java.util.Map.class);
            item.remove("quota");
            item.remove("model_quotas");
            item.putIfAbsent("tags", List.of());
            providers.add(item);
        }
        return providers;
    }

    public java.util.Map<String, Object> getQuota(String referenceId, Duration timeout) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new CpaManagementException("credential reference id is required");
        }
        String encoded = URLEncoder.encode(referenceId, StandardCharsets.UTF_8);
        JsonNode root = getManagementJson("/auth-files?auth_index=" + encoded, timeout);
        JsonNode files = root.path("files");
        if (!files.isArray() || files.isEmpty()) {
            throw new CpaManagementException("credential not found: " + referenceId);
        }
        JsonNode quota = files.get(0).path("quota");
        if (quota.isMissingNode() || quota.isNull() || !quota.isObject() || quota.size() == 0) {
            throw new CpaManagementException("CPA auth-file quota is empty; use provider quota probe");
        }
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = objectMapper.convertValue(quota, java.util.Map.class);
        return result;
    }

    public Map<String, Object> getAuthFileQuota(String referenceId, String provider, String projectId, Duration timeout) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        String url;
        String method = "GET";
        String data = "";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer $TOKEN$");
        headers.put("Accept", "application/json");
        if (normalized.equals("codex") || normalized.equals("openai")) {
            url = "https://chatgpt.com/backend-api/wham/usage";
        } else if (normalized.equals("claude") || normalized.equals("anthropic")) {
            url = "https://api.anthropic.com/api/oauth/usage";
        } else if (normalized.equals("kimi")) {
            url = "https://api.kimi.com/coding/v1/usages";
        } else if (normalized.equals("antigravity")) {
            url = "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary";
            method = "POST";
            data = "{\"project\":\"" + (projectId == null ? "" : projectId) + "\"}";
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "antigravity/cli/1.0.13 (aidev_client; os_type=darwin; arch=arm64)");
        } else if (normalized.equals("gemini")) {
            url = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota";
            method = "POST";
            data = "{}";
            headers.put("Content-Type", "application/json");
        } else {
            throw new CpaManagementException("quota lookup is not supported for auth-file provider: " + provider);
        }
        JsonNode body = callManagementApi(referenceId, method, url, headers, data, timeout);
        return switch (normalized) {
            case "codex", "openai" -> parseCodexQuota(body);
            case "claude", "anthropic" -> parseAnthropicQuota(body);
            case "kimi" -> parseKimiQuota(body);
            case "antigravity" -> parseAntigravityQuota(body);
            case "gemini" -> parseAntigravityQuota(body);
            default -> Map.of();
        };
    }

    private JsonNode loadApiKeyUsage(Duration timeout) {
        try {
            return getManagementJson("/api-key-usage", timeout);
        } catch (CpaManagementException exception) {
            // Usage statistics are optional enrichment; do not hide the credential list
            // when this endpoint is unavailable.
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode findApiKeyUsage(JsonNode root, String provider, String baseUrl, String apiKey) {
        if (root == null || !root.isObject() || apiKey.isBlank()) return objectMapper.createObjectNode();
        String expected = normalizeBaseUrl(baseUrl) + "|" + apiKey;
        List<String> providerNames = List.of(provider.substring(0, provider.indexOf("-api-key") > 0 ? provider.indexOf("-api-key") : provider.length()), provider);
        for (String providerName : providerNames) {
            JsonNode bucket = root.path(providerName);
            JsonNode match = findUsageInBucket(bucket, expected, baseUrl, apiKey);
            if (match.isObject()) return match;
        }
        Iterator<JsonNode> buckets = root.elements();
        while (buckets.hasNext()) {
            JsonNode match = findUsageInBucket(buckets.next(), expected, baseUrl, apiKey);
            if (match.isObject()) return match;
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode findUsageInBucket(JsonNode bucket, String expected, String baseUrl, String apiKey) {
        if (bucket == null || !bucket.isObject()) return objectMapper.createObjectNode();
        JsonNode direct = bucket.path(expected);
        if (direct.isObject()) return direct;
        Iterator<Map.Entry<String, JsonNode>> fields = bucket.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String[] parts = field.getKey().split("\\|", 2);
            if (parts.length == 2 && apiKey.equals(parts[1]) && normalizeBaseUrl(baseUrl).equals(normalizeBaseUrl(parts[0]))) {
                return field.getValue();
            }
        }
        return objectMapper.createObjectNode();
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    public Map<String, Object> getZhipuQuota(String referenceId, String baseUrl, Duration timeout) {
        String host = baseUrl != null && baseUrl.toLowerCase().contains("z.ai")
                ? "https://api.z.ai" : "https://open.bigmodel.cn";
        JsonNode upstream = callManagementApi(referenceId, "GET", host + "/api/monitor/usage/quota/limit",
                Map.of("Authorization", "$TOKEN$", "Content-Type", "application/json", "Accept", "application/json",
                        "Accept-Language", "en-US,en"), "", timeout);
        LOGGER.info("Zhipu quota upstream response: {}", upstream.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "zhipu");
        result.put("tierName", upstream.path("data").path("level").asText("Zhipu"));
        result.put("windows", parseZhipuWindows(upstream.path("data").path("limits")));
        return result;
    }

    private List<Map<String, Object>> parseZhipuWindows(JsonNode limits) {
        List<Map<String, Object>> windows = new ArrayList<>();
        if (!limits.isArray()) return windows;
        JsonNode fiveHour = null;
        JsonNode weekly = null;
        List<JsonNode> tokenUnknown = new ArrayList<>();
        List<JsonNode> creditFallback = new ArrayList<>();
        boolean hasTokens = false;
        for (JsonNode limit : limits) {
            String type = limit.path("type").asText("").toUpperCase();
            if ("TIME_LIMIT".equals(type)) {
                // TIME_LIMIT is a separate request/tool quota, not the weekly token window.
                windows.add(toZhipuTimeWindow(limit));
            } else if ("TOKENS_LIMIT".equals(type)) {
                hasTokens = true;
                int unit = limit.path("unit").asInt(0);
                if (unit == 3 && fiveHour == null) fiveHour = limit;
                else if (unit == 6 && weekly == null) weekly = limit;
                else tokenUnknown.add(limit);
            } else if ("CREDIT_LIMIT".equals(type)) {
                creditFallback.add(limit);
            }
        }
        if (!hasTokens) tokenUnknown.addAll(creditFallback);
        tokenUnknown.sort((left, right) -> Boolean.compare(hasReset(left), hasReset(right)) != 0
                ? Boolean.compare(hasReset(left), hasReset(right))
                : Long.compare(resetMillis(left), resetMillis(right)));
        for (JsonNode candidate : tokenUnknown) {
            if (fiveHour == null) fiveHour = candidate;
            else if (weekly == null) weekly = candidate;
        }
        if (fiveHour != null) windows.add(toZhipuWindow("5 小时窗口", fiveHour));
        if (weekly != null) windows.add(toZhipuWindow("每周窗口", weekly));
        return windows;
    }

    private Map<String, Object> toZhipuTimeWindow(JsonNode limit) {
        Map<String, Object> window = new LinkedHashMap<>();
        double total = limit.path("usage").asDouble(0);
        double remaining = limit.path("remaining").asDouble(0);
        double remainingPercent = total > 0 ? remaining / total * 100 : 0;
        window.put("label", "请求额度");
        window.put("remainingPercent", Math.max(0, Math.min(100, remainingPercent)));
        window.put("resetAt", resetAt(limit.path("nextResetTime")));
        return window;
    }

    private Map<String, Object> toZhipuWindow(String label, JsonNode limit) {
        Map<String, Object> window = new LinkedHashMap<>();
        double used = limit.path("percentage").asDouble(0);
        window.put("label", label);
        window.put("remainingPercent", Math.max(0, Math.min(100, 100 - used)));
        window.put("resetAt", resetAt(limit.path("nextResetTime")));
        return window;
    }

    private boolean hasReset(JsonNode node) {
        return resetMillis(node) > 0;
    }

    private long resetMillis(JsonNode node) {
        return node.path("nextResetTime").isNumber() ? node.path("nextResetTime").asLong(0) : 0;
    }

    private Map<String, Object> parseCodexQuota(JsonNode body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "codex");
        result.put("tierName", body.path("plan_type").asText("Codex"));
        List<Map<String, Object>> windows = new ArrayList<>();
        addPercentWindow(windows, "5 小时窗口", body.path("rate_limit").path("primary_window"));
        addPercentWindow(windows, "每周窗口", body.path("rate_limit").path("secondary_window"));
        result.put("windows", windows);
        return result;
    }

    private Map<String, Object> parseAnthropicQuota(JsonNode body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "anthropic");
        result.put("tierName", "Anthropic");
        List<Map<String, Object>> windows = new ArrayList<>();
        addUtilizationWindow(windows, "5 小时窗口", body.path("five_hour"));
        addUtilizationWindow(windows, "每周窗口", body.path("seven_day"));
        result.put("windows", windows);
        return result;
    }

    private Map<String, Object> parseAntigravityQuota(JsonNode body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "antigravity");
        result.put("tierName", body.path("currentTier").path("name").asText("Antigravity"));
        List<Map<String, Object>> windows = new ArrayList<>();
        JsonNode groups = body.path("groups");
        if (groups.isArray()) for (JsonNode group : groups) {
            JsonNode buckets = group.path("buckets");
            if (!buckets.isArray()) continue;
            for (JsonNode bucket : buckets) {
                Map<String, Object> window = new LinkedHashMap<>();
                window.put("label", bucket.path("displayName").asText(bucket.path("window").asText("额度窗口")));
                window.put("remainingPercent", Math.max(0, Math.min(100, bucket.path("remainingFraction").asDouble(0) * 100)));
                window.put("resetAt", bucket.path("resetTime").asText(""));
                windows.add(window);
            }
        }
        result.put("windows", windows);
        return result;
    }

    private Map<String, Object> parseKimiQuota(JsonNode body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "kimi");
        result.put("tierName", "Kimi");
        result.put("windows", List.of());
        return result;
    }

    private void addPercentWindow(List<Map<String, Object>> windows, String label, JsonNode node) {
        if (!node.isObject()) return;
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("label", label);
        window.put("remainingPercent", Math.max(0, Math.min(100, 100 - node.path("used_percent").asDouble(0))));
        window.put("resetAt", resetAt(node.path("reset_at")));
        windows.add(window);
    }

    private void addUtilizationWindow(List<Map<String, Object>> windows, String label, JsonNode node) {
        if (!node.isObject()) return;
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("label", label);
        window.put("remainingPercent", Math.max(0, Math.min(100, 100 - node.path("utilization").asDouble(0))));
        window.put("resetAt", firstText(node, "resets_at", "reset_at"));
        windows.add(window);
    }

    private JsonNode callManagementApi(String authIndex, String method, String url, Map<String, String> headers, String data, Duration timeout) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("auth_index", authIndex);
            payload.put("method", method);
            payload.put("url", url);
            payload.put("header", headers);
            payload.put("data", data);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(managementApiKeysUri.toString().replace("/api-keys", "/api-call")))
                    .timeout(timeout).header("X-Management-Key", managementKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CpaManagementException("CPA management API call failed with HTTP " + response.statusCode());
            }
            JsonNode result = objectMapper.readTree(response.body());
            int statusCode = result.path("status_code").asInt(500);
            if (statusCode < 200 || statusCode >= 300) {
                throw new CpaManagementException("Zhipu quota request failed with HTTP " + statusCode);
            }
            return objectMapper.readTree(result.path("body").asText("{}"));
        } catch (CpaManagementException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CpaManagementException("CPA management API call failed", exception);
        }
    }

    private String resetAt(JsonNode value) {
        if (value.isNumber()) {
            try { return java.time.Instant.ofEpochMilli(value.asLong()).toString(); } catch (RuntimeException ignored) { }
        }
        return value.asText("");
    }

    private JsonNode getManagementJson(String path, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(managementApiKeysUri.toString().replace("/api-keys", path)))
                .timeout(timeout).header("X-Management-Key", managementKey).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CpaManagementException("CPA credential list request failed with HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (CpaManagementException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CpaManagementException("CPA credential list request failed", exception);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /**
     * Adds a newly generated client API key through CPA's management API.
     * CPA's PATCH contract appends the new value when the old value is absent.
     */
    public String createApiKey(Duration timeout) {
        if (managementKey.isBlank()) {
            throw new CpaManagementException("CPA management key is not configured");
        }
        String apiKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
        String absentOldValue = "__cpa_console_absent_" + UUID.randomUUID();
        String body = "{\"old\":\"" + absentOldValue + "\",\"new\":\"" + apiKey + "\"}";
        HttpRequest request = HttpRequest.newBuilder(managementApiKeysUri)
                .timeout(timeout)
                .header("X-Management-Key", managementKey)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CpaManagementException("CPA API key creation failed with HTTP " + response.statusCode());
            }
            return apiKey;
        } catch (CpaManagementException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CpaManagementException("CPA API key creation was interrupted", exception);
        } catch (IOException exception) {
            throw new CpaManagementException("CPA API key creation failed", exception);
        }
    }
}
