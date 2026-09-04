package site.kael.cpa.console.core.model.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ModelCatalogSyncRunner implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCatalogSyncRunner.class);
    private final AvailableModelManager availableModels;
    private final LiteLlmModelManager liteLlmModels;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cpa-model-sync"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public ModelCatalogSyncRunner(AvailableModelManager availableModels, LiteLlmModelManager liteLlmModels) {
        this.availableModels = availableModels; this.liteLlmModels = liteLlmModels;
    }

    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(this::syncLiteLlmModels, 0, 1, TimeUnit.HOURS);
        // Run the first CPA sync after metadata has had a chance to load, then continue every 10 minutes.
        executor.schedule(() -> {
            syncAvailableModels();
            if (running.get()) executor.scheduleWithFixedDelay(this::syncAvailableModels, 10, 10, TimeUnit.MINUTES);
        }, 30, TimeUnit.SECONDS);
    }
    private void syncAvailableModels() { try { LOGGER.info("Synchronized {} available models", availableModels.synchronize()); } catch (Exception e) { LOGGER.warn("Available model synchronization failed", e); } }
    private void syncLiteLlmModels() { try { LOGGER.info("Synchronized {} LiteLLM model metadata entries", liteLlmModels.synchronize()); } catch (Exception e) { LOGGER.warn("LiteLLM metadata synchronization failed", e); } }
    @Override public void stop() { if (!running.compareAndSet(true, false)) return; executor.shutdownNow(); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
