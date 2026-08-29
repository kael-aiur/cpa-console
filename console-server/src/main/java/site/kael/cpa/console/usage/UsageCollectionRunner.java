package site.kael.cpa.console.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.usage.UsageEventSource;
import site.kael.cpa.console.core.usage.service.UsageEventInboxService;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class UsageCollectionRunner implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(UsageCollectionRunner.class);
    private final UsageEventSource source;
    private final UsageEventInboxService inbox;
    private final Duration processInterval;
    private final int processBatchSize;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cpa-usage-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Future<?> ingestFuture;
    private volatile Future<?> processFuture;

    public UsageCollectionRunner(UsageEventSource source, UsageEventInboxService inbox,
                                 @Value("${cpa.usage-process-interval:${CPA_USAGE_PROCESS_INTERVAL:1s}}") Duration processInterval,
                                 @Value("${cpa.usage-process-batch-size:${CPA_USAGE_PROCESS_BATCH_SIZE:100}}") int processBatchSize) {
        this.source = source; this.inbox = inbox; this.processInterval = processInterval; this.processBatchSize = processBatchSize;
    }

    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        ingestFuture = executor.submit(this::runIngest);
        processFuture = executor.submit(this::runProcess);
        LOGGER.info("CPA usage collection task started with source {}", source.getClass().getSimpleName());
    }

    private void runIngest() {
        try {
            source.run((sourceName, raw) -> inbox.accept(sourceName, raw, Instant.now()), running::get);
        } catch (Exception exception) {
            if (running.get()) LOGGER.error("CPA usage source stopped unexpectedly", exception);
        }
    }

    private void runProcess() {
        while (running.get()) {
            try { inbox.processBatch(processBatchSize); }
            catch (Exception exception) { LOGGER.warn("CPA usage inbox processing failed", exception); }
            sleep(processInterval);
        }
    }

    private void sleep(Duration duration) {
        try { Thread.sleep(Math.max(1, duration.toMillis())); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    @Override public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (ingestFuture != null) ingestFuture.cancel(true);
        if (processFuture != null) processFuture.cancel(true);
        executor.shutdownNow();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
