package site.kael.cpa.console.core.cpa;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;

import java.net.InetSocketAddress;
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
