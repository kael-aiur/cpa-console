package site.kael.cpa.console.core.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.usage.dao.UsageEventDao;
import site.kael.cpa.console.core.usage.dao.UsageEventInboxDao;
import site.kael.cpa.console.core.usage.service.UsageEventInboxService;

import java.util.Base64;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageEventInboxServiceTest {
    private static String tempDatabase() {
        try { return Files.createTempFile("usage-test-", ".db").toString(); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }
    @Test
    void processesInboxAndRemovesRawPayloadWhilePersistingKeyHash() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDatabase());
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/001__init.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UsageEventInboxDao inboxDao = new UsageEventInboxDao(jdbc);
        UsageEventDao eventDao = new UsageEventDao(jdbc);
        ApiKeyCrypto crypto = new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[16]));
        UsageEventInboxService service = new UsageEventInboxService(inboxDao, eventDao, crypto, jdbc, new DataSourceTransactionManager(dataSource));

        service.accept("redis_subscribe:usage", """
                {"timestamp":"2026-08-29T01:02:03Z","api_key":"sk-user","request_id":"req-1","provider":"openai",
                 "model":"gpt-5","tokens":{"input_tokens":4,"output_tokens":6,"total_tokens":10}}
                """, java.time.Instant.now());

        assertEquals(1, service.processBatch(10));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM usage_event_inbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM usage_events WHERE api_key_hash = ?", Integer.class, crypto.hash("sk-user")));
        assertEquals(10, jdbc.queryForObject("SELECT total_tokens FROM usage_events WHERE request_id = 'req-1'", Integer.class));
    }

    @Test
    void keepsMalformedPayloadForDecodeFailure() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDatabase());
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/001__init.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UsageEventInboxDao inboxDao = new UsageEventInboxDao(jdbc);
        UsageEventInboxService service = new UsageEventInboxService(inboxDao, new UsageEventDao(jdbc),
                new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[16])), jdbc, new DataSourceTransactionManager(dataSource));
        service.accept("source", "{bad-json", java.time.Instant.now());

        assertEquals(0, service.processBatch(10));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM usage_event_inbox WHERE status = 'decode_failed'", Integer.class));
    }
}
