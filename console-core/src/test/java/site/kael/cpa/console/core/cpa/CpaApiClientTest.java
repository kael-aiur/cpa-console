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
