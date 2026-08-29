package site.kael.cpa.console.core.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.usage.model.UsageEvent;
import site.kael.cpa.console.core.usage.model.UsageEventInbox;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public class UsageEventParser {
    private final ObjectMapper objectMapper;
    private final ApiKeyCrypto apiKeyCrypto;

    public UsageEventParser(ObjectMapper objectMapper, ApiKeyCrypto apiKeyCrypto) {
        this.objectMapper = objectMapper;
        this.apiKeyCrypto = apiKeyCrypto;
    }

    ObjectMapper objectMapper() { return objectMapper; }

    public UsageEvent parse(UsageEventInbox inbox) throws IOException {
        JsonNode root = objectMapper.readTree(inbox.rawEventJson());
        String requestId = text(root, "request_id");
        if (requestId.isBlank()) throw new IllegalArgumentException("request_id is required");
        String provider = text(root, "provider");
        String endpoint = text(root, "endpoint");
        String apiKey = text(root, "api_key");
        String apiKeyHash = apiKey.isBlank() ? null : apiKeyCrypto.hash(apiKey);
        String model = first(text(root, "model"), "unknown");
        String apiGroupKey = first(provider, endpoint, "unknown");
        JsonNode tokens = root.path("tokens");
        Instant timestamp = parseInstant(root.path("timestamp"), inbox.receivedAt());
        Instant now = Instant.now();
        return new UsageEvent(null, requestId, requestId, timestamp, inbox.source(), provider, endpoint,
                normalizeAuthType(text(root, "auth_type")), text(root, "auth_index"), apiGroupKey, apiKeyHash,
                model, nullable(text(root, "alias")), text(root, "executor_type"), text(root, "service_tier"),
                text(root, "response_service_tier"), text(root, "reasoning_effort"), root.path("failed").asBoolean(false),
                optionalBoolean(root, "generate"), Math.max(root.path("latency_ms").asLong(0), 0), optionalLong(root, "ttft_ms"),
                token(tokens, "input_tokens"), token(tokens, "output_tokens"), token(tokens, "reasoning_tokens"),
                token(tokens, "cached_tokens"), token(tokens, "cache_read_tokens"), token(tokens, "cache_creation_tokens"),
                token(tokens, "total_tokens"), inbox.id(), now, now);
    }

    private static long token(JsonNode node, String field) { return Math.max(node.path(field).asLong(0), 0); }
    private static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return ""; }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private static String normalizeAuthType(String value) { return "api_key".equalsIgnoreCase(value) ? "apikey" : value; }
    private static Boolean optionalBoolean(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asBoolean() : null; }
    private static Long optionalLong(JsonNode node, String field) { return node.hasNonNull(field) ? Math.max(node.path(field).asLong(0), 0) : null; }

    private static Instant parseInstant(JsonNode value, Instant fallback) {
        if (value.isNumber()) return Instant.ofEpochMilli(value.asLong() < 10_000_000_000L ? value.asLong() * 1000 : value.asLong());
        String text = value.asText("").trim();
        if (!text.isBlank()) {
            try { return Instant.parse(text); } catch (DateTimeParseException ignored) { }
        }
        return fallback;
    }
}
