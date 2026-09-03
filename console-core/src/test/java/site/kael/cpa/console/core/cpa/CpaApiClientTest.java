package site.kael.cpa.console.core.cpa;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpaApiClientTest {
    @Test
    void identifiesInteractionsCredentialFromEndpointWhenBaseUrlIsMissing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/v0/management/api-key-usage" -> "{}";
                case "/v0/management/auth-files" -> "{\"files\":[]}";
                case "/v0/management/interactions-api-key" -> "{\"interactions-api-key\":[{\"api-key\":\"AQ.Ab8RN6IExxxxx\",\"models\":[],\"auth-index\":\"eb01aa1dxxxxx\"}]}";
                default -> "{}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            CpaApiClient client = new CpaApiClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), "management-key");

            var credentials = client.listCredentials(Duration.ofSeconds(2));

            assertEquals(1, credentials.size());
            assertEquals("Google Interactions(AQ***xxxx)", credentials.get(0).name());
            assertEquals("Google Interactions", credentials.get(0).provider());
            assertEquals("", credentials.get(0).baseUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsResponsesFromOpenAiResponsesEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            assertEquals("Bearer user-key", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, new ObjectMapper().readTree(requestBody).path("stream").asBoolean());
            byte[] body = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CpaApiClient client = new CpaApiClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), "management-key");
            HttpResponse<InputStream> response = client.createResponseStream("user-key", new ObjectMapper().readTree("{\"model\":\"gpt-5\",\"input\":\"hi\",\"stream\":true}"), Duration.ofSeconds(2));
            try (InputStream body = response.body()) {
                assertEquals(200, response.statusCode());
                assertEquals(true, new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("response.output_text.delta"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsModelsFromOpenAiCompatibleEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
            assertEquals("Bearer user-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\",\"owned_by\":\"openai\"},{\"id\":\"\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CpaApiClient client = new CpaApiClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), "management-key");
            assertEquals(java.util.List.of("gpt-5"), client.listModels("user-key", Duration.ofSeconds(2)).stream().map(model -> model.id()).toList());
        } finally {
            server.stop(0);
        }
    }
}
