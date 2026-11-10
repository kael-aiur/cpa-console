package site.kael.cpa.console.core.cpa.client;

import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal v8 management client restricted to the three operations allowed by D3.2:
 * paginated {@code GET /v8/management/credentials}, {@code GET /v8/management/credentials/models}
 * and a single {@code POST /v8/management/credentials/verify} whose JSON body carries exactly
 * {@code auth_id}, {@code auth_index} and {@code model}. No retries, no cross-origin redirects,
 * no fallback endpoints and no v0/v1 downgrade paths.
 */
public class CpaCredentialVerificationClient {

    private final CpaEndpoint endpoint;
    private final String managementKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Immutable call context captured from one binding verification (D5.1a). */
    public record CpaEndpoint(String normalizedEndpoint, String configVersion) {
        public CpaEndpoint {
            Objects.requireNonNull(normalizedEndpoint, "normalizedEndpoint");
            Objects.requireNonNull(configVersion, "configVersion");
        }

        public URI resolve(String pathWithQuery) {
            return URI.create(normalizedEndpoint + pathWithQuery);
        }
    }

    public CpaCredentialVerificationClient(CpaEndpoint endpoint, String managementKey) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.managementKey = managementKey == null ? "" : managementKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Lists every credential page until the API reports no more pages. */
    public List<CredentialPage> listAllCredentialPages() {
        List<CredentialPage> pages = new ArrayList<>();
        int page = 1;
        final int pageSize = 100;
        while (true) {
            String query = "/v8/management/credentials?page=" + page + "&page_size=" + pageSize;
            JsonNode root = getJson(query);
            pages.add(parseCredentialPage(root));
            JsonNode pagination = root.path("has_more");
            if (!pagination.isBoolean() || !pagination.asBoolean()) {
                return pages;
            }
            page++;
            if (page > 1000) {
                throw new IllegalStateException("Credential pagination did not terminate");
            }
        }
    }

    /** Queries the registered route models for one auth id or FileName, strictly encoded. */
    public List<String> listModels(String nameOrAuthId) {
        String encoded = URLEncoder.encode(
                Objects.requireNonNull(nameOrAuthId, "nameOrAuthId"), StandardCharsets.UTF_8);
        JsonNode root = getJson("/v8/management/credentials/models?name=" + encoded);
        List<String> models = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) {
                models.add(id);
            }
        }
        return List.copyOf(models);
    }

    /**
     * Submits the single allowed verification POST. The JSON body contains exactly
     * auth_id, auth_index and model; the HTTP response is returned untouched for
     * {@link site.kael.cpa.console.core.monitor.manager.VerificationResponseClassifier}.
     */
    public VerificationHttpResponse verify(VerifyRequest request) {
        Objects.requireNonNull(request, "request");
        String body = "{\"auth_id\":" + quote(request.authId())
                + ",\"auth_index\":" + quote(request.authIndex())
                + ",\"model\":" + quote(request.model()) + "}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(endpoint.resolve("/v8/management/credentials/verify"))
                .timeout(Duration.ofSeconds(120))
                .header("X-Management-Key", managementKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return new VerificationHttpResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VerificationTransportException("TIMEOUT", exception);
        } catch (IOException exception) {
            throw new VerificationTransportException("TRANSPORT", exception);
        }
    }

    /** Restricted request payload; every field is a trusted backend snapshot, never user input. */
    public record VerifyRequest(String authId, String authIndex, String model) {
        public VerifyRequest {
            if (authId == null || authId.isBlank()) throw new IllegalArgumentException("authId must not be blank");
            if (authIndex == null || authIndex.isBlank()) throw new IllegalArgumentException("authIndex must not be blank");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
        }
    }

    /** Raw verification response: status line plus body; never logged or persisted verbatim. */
    public record VerificationHttpResponse(int statusCode, String body) {
    }

    /** Transport-level failure before or during the single allowed POST. */
    public static final class VerificationTransportException extends RuntimeException {
        private final String category;

        public VerificationTransportException(String category, Throwable cause) {
            super(cause);
            this.category = category;
        }

        public String category() {
            return category;
        }
    }

    private JsonNode getJson(String pathWithQuery) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint.resolve(pathWithQuery))
                .timeout(Duration.ofSeconds(30))
                .header("X-Management-Key", managementKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("CPA credential read failed with HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CPA credential read was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("CPA credential read failed", exception);
        }
    }

    private CredentialPage parseCredentialPage(JsonNode root) {
        List<CredentialEntryDetail> entries = new ArrayList<>();
        for (JsonNode file : root.path("files")) {
            String authId = textOrNull(file, "id");
            String authIndex = textOrNull(file, "auth_index");
            if (authId == null || authIndex == null) {
                continue;
            }
            boolean sourceFile = "file".equals(textOrNull(file, "source"));
            String path = textOrNull(file, "path");
            boolean diskDegraded = file.hasNonNull("path") && path != null && file.path("size").isMissingNode();
            String label = textOrNull(file, "label");
            if (label == null || isEmailLike(label)) {
                label = textOrNull(file, "account");
            }
            if (label == null || isEmailLike(label)) {
                label = textOrNull(file, "note");
            }
            entries.add(new CredentialEntryDetail(
                    authId,
                    authIndex,
                    textOrNull(file, "name"),
                    textOrNull(file, "provider"),
                    textOrNull(file, "type"),
                    file.path("disabled").asBoolean(false),
                    file.path("unavailable").asBoolean(false),
                    diskDegraded || (!sourceFile && path != null),
                    safeLabel(label),
                    sourceFile ? "file" : "memory"));
        }
        return new CredentialPage(entries, root.path("has_more").asBoolean(false));
    }

    /** A label is shown only after it cannot look like an email or a long secret-like value. */
    private static boolean isEmailLike(String value) {
        return value.indexOf('@') >= 0;
    }

    /** Keeps short, single-line, non-email labels; everything else is dropped before transport. */
    private static String safeLabel(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 64 || isEmailLike(trimmed)
                || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return null;
        }
        return trimmed;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
