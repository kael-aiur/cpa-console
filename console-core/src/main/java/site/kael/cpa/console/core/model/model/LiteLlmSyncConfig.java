package site.kael.cpa.console.core.model.model;

import java.time.Instant;

public record LiteLlmSyncConfig(long id, String url, boolean proxyEnabled, String proxyHost, int proxyPort, Instant updatedAt) {
}
