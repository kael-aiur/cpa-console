package site.kael.cpa.console.core.cpa.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-CPA self-check for the D3.2 contract: only the two GET endpoints and a single POST
 * with an exact JSON body, management key only in the backend, no redirects or retries.
 */
class CpaCredentialVerificationClientTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private volatile int respondStatus = 200;
    private volatile String respondBody = "{\"files\":[],\"has_more\":false}";
    private volatile String redirectLocation;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()
                    + (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery()));
            methods.add(exchange.getRequestMethod());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("X-Management-Key"));
            if (redirectLocation != null) {
                exchange.getResponseHeaders().add("Location", redirectLocation);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] response = respondBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CpaCredentialVerificationClient client() {
        return new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint("http://127.0.0.1:" + server.getAddress().getPort(), "test-config"),
                "test-management-key");
    }

    @Test
    void paginatesCredentialsUntilNoMorePages() throws Exception {
        java.util.concurrent.atomic.AtomicInteger pageRequests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/paginated", exchange -> {
            int page = pageRequests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            methods.add(exchange.getRequestMethod());
            String body = page == 1
                    ? "{\"files\":[{\"id\":\"a1\",\"auth_index\":\"ix1\"}],\"has_more\":true,\"total\":2,\"page\":1,\"page_size\":1}"
                    : "{\"files\":[{\"id\":\"a2\",\"auth_index\":\"ix2\"}],\"has_more\":false,\"total\":2,\"page\":2,\"page_size\":1}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        CpaCredentialVerificationClient pagedClient = new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/paginated", "cfg"),
                "key");
        List<CredentialPage> pages = pagedClient.listAllCredentialPages();
        assertEquals(2, pages.size());
        assertEquals(2, pageRequests.get());
        assertEquals("/paginated/v8/management/credentials?page=1&page_size=100", paths.get(paths.size() - 2));
        assertEquals("/paginated/v8/management/credentials?page=2&page_size=100", paths.get(paths.size() - 1));
        assertTrue(methods.stream().allMatch("GET"::equals));
    }

    @Test
    void modelQueryUsesStrictUrlEncoding() {
        client().listModels("id with space&x=1");
        assertEquals("/v8/management/credentials/models?name=id+with+space&x=1", paths.get(0));
    }

    @Test
    void verifyPostSendsExactlyThreeFieldsWithManagementKey() {
        CpaCredentialVerificationClient.VerificationHttpResponse response =
                client().verify(new CpaCredentialVerificationClient.VerifyRequest("auth-1", "idx-9", "gpt-5.5"));
        assertEquals(200, response.statusCode());
        assertEquals(1, methods.size());
        assertEquals("POST", methods.get(0));
        assertEquals("/v8/management/credentials/verify", paths.get(0));
        assertEquals("test-management-key", keys.get(0));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(bodies.get(0));
            assertEquals(3, json.size());
            assertEquals("auth-1", json.path("auth_id").asText());
            assertEquals("idx-9", json.path("auth_index").asText());
            assertEquals("gpt-5.5", json.path("model").asText());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void verifyPostIsNeverRetriedAndNeverFollowsRedirects() throws Exception {
        java.util.concurrent.atomic.AtomicInteger posts = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/verify-redirect", exchange -> {
            posts.incrementAndGet();
            methods.add(exchange.getRequestMethod());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (posts.get() == 1) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        CpaCredentialVerificationClient redirectClient = new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/verify-redirect", "cfg"),
                "key");
        CpaCredentialVerificationClient.VerificationHttpResponse response =
                redirectClient.verify(new CpaCredentialVerificationClient.VerifyRequest("a", "i", "m"));
        // The client surfaces the 302 instead of following it, and never retries the POST.
        assertEquals(302, response.statusCode());
        assertEquals(1, posts.get());
        assertEquals(1, methods.size());
        assertEquals("POST", methods.get(0));
    }

    @Test
    void blankModelIsRejectedBeforeAnyNetworkCall() {
        CpaCredentialVerificationClient client = client();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> client.verify(new CpaCredentialVerificationClient.VerifyRequest("a", "i", "  ")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> client.verify(new CpaCredentialVerificationClient.VerifyRequest("a", "", "m")));
        assertEquals(0, methods.size());
    }
}
