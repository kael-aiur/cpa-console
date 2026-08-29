package site.kael.cpa.console.core.usage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.usage.dao.UsageEventDao;
import site.kael.cpa.console.core.usage.dao.UsageEventInboxDao;
import site.kael.cpa.console.core.usage.model.UsageEventInbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class UsageEventInboxService {
    private final UsageEventInboxDao inboxDao;
    private final UsageEventDao eventDao;
    private final UsageEventParser parser;
    private final TransactionTemplate transactionTemplate;

    public UsageEventInboxService(UsageEventInboxDao inboxDao, UsageEventDao eventDao, ApiKeyCrypto apiKeyCrypto,
                                  JdbcTemplate jdbcTemplate, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.inboxDao = inboxDao;
        this.eventDao = eventDao;
        this.parser = new UsageEventParser(new ObjectMapper(), apiKeyCrypto);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void accept(String source, String rawJson, Instant receivedAt) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson.trim())) return;
        try {
            var root = parser.objectMapper().readTree(rawJson);
            if (root.isObject() && (root.path("support_refresh").asBoolean(false) || root.path("refresh").asBoolean(false))) return;
        } catch (Exception ignored) {
            // Keep malformed payloads in the inbox so the processing stage can record the decode failure.
        }
        inboxDao.insert(source, rawJson, sha256(rawJson), receivedAt);
    }

    public int processBatch(int limit) {
        int processed = 0;
        for (UsageEventInbox inbox : inboxDao.findProcessable(limit)) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    try {
                        eventDao.insert(parser.parse(inbox));
                        inboxDao.delete(inbox.id());
                    } catch (Exception exception) {
                        status.setRollbackOnly();
                        throw new RuntimeException(exception);
                    }
                });
                processed++;
            } catch (RuntimeException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof IllegalArgumentException || cause instanceof java.io.IOException) inboxDao.markDecodeFailed(inbox.id(), cause.getMessage());
                else inboxDao.markProcessFailed(inbox.id(), cause.getMessage());
            }
        }
        return processed;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
