package site.kael.cpa.console.core.usage;

import site.kael.cpa.console.core.usage.redis.RedisUsageClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class RedisUsageEventSource implements UsageEventSource {
    private final RedisUsageClient client;
    private final Duration fallbackInterval;
    private final int batchSize;

    public RedisUsageEventSource(RedisUsageClient client, Duration fallbackInterval, int batchSize) {
        this.client = client;
        this.fallbackInterval = fallbackInterval;
        this.batchSize = batchSize;
    }

    @Override
    public void run(BiConsumer<String, String> sink, BooleanSupplier running) {
        while (running.getAsBoolean()) {
            RedisUsageClient.Subscription subscription = null;
            try {
                subscription = client.subscribe();
                drain(sink, running);
                while (running.getAsBoolean()) {
                    String raw = subscription.receive();
                    if (raw != null) sink.accept("redis_subscribe:usage", raw);
                }
            } catch (Exception ignored) {
                // The next fallback interval retries both queue polling and subscription recovery.
            } finally {
                if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
            }
            if (!running.getAsBoolean()) return;
            sleep(fallbackInterval, running);
            try { pull(sink); } catch (Exception ignored) { }
        }
    }

    private void drain(BiConsumer<String, String> sink, BooleanSupplier running) throws Exception {
        while (running.getAsBoolean()) {
            List<String> values = client.pull();
            for (String raw : values) sink.accept("redis_pull:" + client.lastQueueKey(), raw);
            if (values.size() < batchSize) return;
        }
    }

    private void pull(BiConsumer<String, String> sink) throws Exception {
        for (String raw : client.pull()) sink.accept("redis_pull:" + client.lastQueueKey(), raw);
    }

    static void sleep(Duration duration, BooleanSupplier running) {
        long remaining = Math.max(1, duration.toMillis());
        while (running.getAsBoolean() && remaining > 0) {
            long step = Math.min(remaining, 250);
            try { Thread.sleep(step); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            remaining -= step;
        }
    }
}
