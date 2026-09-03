package site.kael.cpa.console.admin.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

@Service
public class AdminChatService {
    private final CpaApiKeyManager cpaApiKeyManager;
    private final UserManager userManager;
    private final ObjectMapper objectMapper;

    public AdminChatService(CpaApiKeyManager cpaApiKeyManager, UserManager userManager, ObjectMapper objectMapper) {
        this.cpaApiKeyManager = cpaApiKeyManager;
        this.userManager = userManager;
        this.objectMapper = objectMapper;
    }

    public InputStream openResponseStream(User user, JsonNode requestBody) {
        validateRequest(requestBody);
        ObjectNode normalized = requestBody.deepCopy();
        normalizeTextInput(normalized);
        normalized.put("stream", true);
        HttpResponse<InputStream> response = cpaApiKeyManager.createResponseStream(userManager.apiKey(user), normalized);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String details = readError(response.body());
            throw new CpaManagementException("CPA Responses request failed with HTTP " + response.statusCode()
                    + (details.isBlank() ? "" : ": " + details));
        }
        return response.body();
    }

    private void validateRequest(JsonNode requestBody) {
        if (requestBody == null || !requestBody.isObject()) throw new IllegalArgumentException("请求体必须为 JSON 对象");
        if (requestBody.path("model").asText("").isBlank()) throw new IllegalArgumentException("model 不能为空");
        JsonNode input = requestBody.get("input");
        if (input == null || input.isNull() || (input.isTextual() && input.asText().isBlank())) {
            throw new IllegalArgumentException("input 不能为空");
        }
        if (!input.isTextual() && !input.isArray()) throw new IllegalArgumentException("当前仅支持文本 input");
    }

    private void normalizeTextInput(ObjectNode requestBody) {
        JsonNode input = requestBody.get("input");
        if (input == null || !input.isTextual()) return;

        ArrayNode inputItems = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "input_text");
        text.put("text", input.asText());
        inputItems.add(message);
        requestBody.set("input", inputItems);
    }

    private String readError(InputStream body) {
        if (body == null) return "";
        try (body) {
            JsonNode error = objectMapper.readTree(body);
            if (error == null) return "";
            return error.path("error").path("message").asText(error.path("message").asText(""));
        } catch (IOException ignored) {
            return "";
        }
    }
}
