package site.kael.cpa.console.core.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.usage.model.UsageEvent;
import site.kael.cpa.console.core.usage.model.UsageEventInbox;
import site.kael.cpa.console.core.usage.service.UsageEventParser;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class UsageEventParserTest {
    @Test
    void parsesUsagePayloadAndStoresOnlyApiKeyHash() throws Exception {
        byte[] key = new byte[16];
        String encryptionKey = Base64.getEncoder().encodeToString(key);
        ApiKeyCrypto crypto = new ApiKeyCrypto(encryptionKey);
        UsageEventParser parser = new UsageEventParser(new ObjectMapper(), crypto);
        UsageEventInbox inbox = new UsageEventInbox(7, "redis_subscribe:usage", "hash", """
                {"timestamp":"2026-08-29T01:02:03Z","provider":"openai","endpoint":"/v1/chat/completions",
                 "auth_type":"api_key","auth_index":"client","api_key":"sk-secret","request_id":"req-1",
                 "model":"gpt-5","alias":"gpt-alias","failed":false,"latency_ms":12,
                 "tokens":{"input_tokens":10,"output_tokens":20,"total_tokens":30}}
                """, "pending", 0, "", Instant.now(), null, Instant.now(), Instant.now());

        UsageEvent event = parser.parse(inbox);

        assertEquals("req-1", event.requestId());
        assertEquals("apikey", event.authType());
        assertEquals("gpt-5", event.model());
        assertEquals(10, event.inputTokens());
        assertEquals(30, event.totalTokens());
        assertEquals(crypto.hash("sk-secret"), event.apiKeyHash());
    }

    @Test
    void rejectsPayloadWithoutRequestId() {
        ApiKeyCrypto crypto = new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[16]));
        UsageEventParser parser = new UsageEventParser(new ObjectMapper(), crypto);
        UsageEventInbox inbox = new UsageEventInbox(1, "source", "hash", "{}", "pending", 0, "", Instant.now(), null, Instant.now(), Instant.now());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(inbox));
    }
}
