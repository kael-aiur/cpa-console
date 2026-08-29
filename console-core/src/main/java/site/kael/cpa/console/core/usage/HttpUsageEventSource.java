package site.kael.cpa.console.core.usage;

import site.kael.cpa.console.core.cpa.client.CpaApiClient;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class HttpUsageEventSource implements UsageEventSource {
    private final CpaApiClient client;
    private final Duration timeout;
    private final Duration pollInterval;
    private final int batchSize;

    public HttpUsageEventSource(CpaApiClient client, Duration timeout, Duration pollInterval, int batchSize) {
        this.client = client; this.timeout = timeout; this.pollInterval = pollInterval; this.batchSize = batchSize;
    }

    @Override
    public void run(BiConsumer<String, String> sink, BooleanSupplier running) {
        while (running.getAsBoolean()) {
            try {
                for (String raw : client.fetchUsageQueue(batchSize, timeout)) sink.accept("http_usage_queue", raw);
            } catch (RuntimeException ignored) {
                // HTTP failures are retried on the next configured poll interval.
            }
            RedisUsageEventSource.sleep(pollInterval, running);
        }
    }
}
