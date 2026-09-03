package site.kael.cpa.console.admin.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import site.kael.cpa.console.admin.chat.service.AdminChatService;
import site.kael.cpa.console.auth.security.ConsolePrincipal;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/admin/v1")
public class AdminChatController {
    private final AdminChatService service;
    private final ObjectMapper objectMapper;

    public AdminChatController(AdminChatService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/responses", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> responses(Authentication authentication, @RequestBody JsonNode requestBody) {
        InputStream upstream = service.openResponseStream(((ConsolePrincipal) authentication.getPrincipal()).user(), requestBody);
        StreamingResponseBody body = output -> {
            try (upstream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = upstream.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException exception) {
                // The client may close the connection while the model is still streaming.
                if (!isClientDisconnect(exception)) {
                    try {
                        output.write(("event: response.failed\ndata: " + objectMapper.writeValueAsString(java.util.Map.of(
                                "type", "response.failed",
                                "error", java.util.Map.of("message", "模型响应流中断")
                        )) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException ignored) {
                        // The client may have disconnected while reporting the failure.
                    }
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @ExceptionHandler(CpaManagementException.class)
    public ResponseEntity<String> cpaError(CpaManagementException exception) {
        return responseError(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> requestError(IllegalArgumentException exception) {
        return responseError(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    private ResponseEntity<String> responseError(HttpStatus status, String message) {
        try {
            String data = objectMapper.writeValueAsString(java.util.Map.of(
                    "type", "response.failed",
                    "error", java.util.Map.of("message", message == null ? "模型响应失败" : message)
            ));
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noCache())
                    .header("X-Accel-Buffering", "no")
                    .body("event: response.failed\ndata: " + data + "\n\n");
        } catch (IOException serializationException) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("event: response.failed\ndata: {\"type\":\"response.failed\",\"error\":{\"message\":\"模型响应失败\"}}\n\n");
        }
    }

    private boolean isClientDisconnect(IOException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("Broken pipe") || message.contains("Connection reset"));
    }
}
