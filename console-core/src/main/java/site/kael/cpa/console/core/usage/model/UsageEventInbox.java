package site.kael.cpa.console.core.usage.model;

import java.time.Instant;

public record UsageEventInbox(
        long id,
        String source,
        String messageHash,
        String rawEventJson,
        String status,
        int attemptCount,
        String lastError,
        Instant receivedAt,
        Instant processedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String PENDING = "pending";
    public static final String PROCESSED = "processed";
    public static final String DECODE_FAILED = "decode_failed";
    public static final String PROCESS_FAILED = "process_failed";
    public static final String DISCARDED = "discarded";
}
