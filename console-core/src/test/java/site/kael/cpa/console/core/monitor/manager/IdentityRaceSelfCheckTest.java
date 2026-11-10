package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.BoundTarget;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialEntryDetail;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver.CredentialPage;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * I-C1 end-to-end proof: after the GET (precheck) and after handler lookup, the same logical
 * identity is replaced by a different physical object B; the single verify POST still targets
 * the same logical id/index/model and a strict top-level true still classifies SUCCESS.
 */
class IdentityRaceSelfCheckTest {

    @Test
    void sameLogicalTargetReplacedBetweenGetAndPostStillVerifiesOnce() throws Exception {
        List<String> verifyPaths = new CopyOnWriteArrayList<>();
        List<String> verifyBodies = new CopyOnWriteArrayList<>();
        AtomicInteger posts = new AtomicInteger();
        AtomicReference<String> servedAuthFile = new AtomicReference<>("A");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/management/credentials", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            if (path.endsWith("/models")) {
                byte[] body = ("{\"models\":[{\"id\":\"m\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            String file = servedAuthFile.get();
            String entry = file.equals("A")
                    ? "{\"id\":\"x\",\"auth_index\":\"y\",\"name\":\"acct-a.json\",\"provider\":\"codex\",\"type\":\"codex\",\"disabled\":false,\"unavailable\":false,\"source\":\"file\"}"
                    : "{\"id\":\"x\",\"auth_index\":\"y\",\"name\":\"acct-b.json\",\"provider\":\"codex\",\"type\":\"codex\",\"disabled\":false,\"unavailable\":false,\"source\":\"file\"}";
            byte[] body = ("{\"files\":[" + entry + "],\"has_more\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v8/management/credentials/verify", exchange -> {
            posts.incrementAndGet();
            verifyPaths.add(exchange.getRequestURI().getPath());
            verifyBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // Physical object B answers; strict top-level true with no contradiction.
            byte[] body = "{\"verified\":true,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CpaCredentialVerificationClient client = new CpaCredentialVerificationClient(
                    new CpaCredentialVerificationClient.CpaEndpoint(
                            "http://127.0.0.1:" + server.getAddress().getPort(), "cfg-v1"),
                    "test-key");
            CpaIdentityResolver resolver = new CpaIdentityResolver(
                    client, new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg=="),
                    Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneId.of("UTC")));

            // Phase 1: GET sees physical A, precheck resolves the bound target.
            BoundTarget target = resolver.verifyBoundTarget("inst", "x", "y", "m");
            assertEquals("x", target.authId());
            assertEquals("y", target.authIndex());

            // Phase 2: before the POST, physical object B replaces A under the same logical id/index.
            servedAuthFile.set("B");

            // Single POST captured with exactly the logical fields; B may answer.
            CpaCredentialVerificationClient.VerificationHttpResponse response =
                    client.verify(new CpaCredentialVerificationClient.VerifyRequest(
                            target.authId(), target.authIndex(), target.routeModel()));

            assertEquals(1, posts.get());
            assertEquals("/v8/management/credentials/verify", verifyPaths.get(0));
            com.fasterxml.jackson.databind.JsonNode sent = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(verifyBodies.get(0));
            assertEquals("x", sent.path("auth_id").asText());
            assertEquals("y", sent.path("auth_index").asText());
            assertEquals("m", sent.path("model").asText());

            // Strict current verified=true with no contradiction → SUCCESS even though B answered.
            VerificationResponseClassifier.Classification classification =
                    new VerificationResponseClassifier(response.statusCode(), response.body()).classify();
            assertEquals(VerificationResponseClassifier.Verdict.SUCCESS, classification.verdict());
            assertEquals("VERIFIED", classification.reason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replacedLogicalFieldsDoNotMatchAndProduceNoPost() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v8/management/credentials", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/models")) {
                byte[] body = "{\"models\":[{\"id\":\"m\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            byte[] body = ("{\"files\":[{\"id\":\"x\",\"auth_index\":\"y2\",\"name\":\"a.json\","
                    + "\"provider\":\"codex\",\"type\":\"codex\",\"disabled\":false,\"unavailable\":false,\"source\":\"file\"}],"
                    + "\"has_more\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v8/management/credentials/verify", exchange -> {
            posts.incrementAndGet();
            byte[] body = "{\"verified\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CpaCredentialVerificationClient client = new CpaCredentialVerificationClient(
                    new CpaCredentialVerificationClient.CpaEndpoint(
                            "http://127.0.0.1:" + server.getAddress().getPort(), "cfg"),
                    "key");
            CpaIdentityResolver resolver = new CpaIdentityResolver(
                    client, new ApiKeyCrypto("MDEyMzQ1Njc4OWFiY2RlZg=="),
                    Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneId.of("UTC")));
            // Bound index y no longer matches live y2 → unsafe; no POST may happen.
            try {
                resolver.verifyBoundTarget("inst", "x", "y", "m");
                throw new AssertionError("Expected identity mismatch rejection");
            } catch (CpaIdentityResolver.IdentityResolutionException expected) {
                assertNotNull(expected.getMessage());
            }
            assertEquals(0, posts.get());
        } finally {
            server.stop(0);
        }
    }
}
