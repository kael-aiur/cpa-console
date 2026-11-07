package site.kael.cpa.console.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.monitor.manager.MonitorLifecycleManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T07 application wiring (D5.2): the scheduler starts only after the data source and its
 * migrations are ready, and stops before the datasource closes on context shutdown. A
 * startup failure marks the lifecycle FAULTED and leaves the application running without
 * scheduling so existing non-monitor endpoints keep working; the safe stop keeps guards.
 */
@Component
public class MonitorLifecycleRunner implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorLifecycleRunner.class);

    private final MonitorLifecycleManager lifecycleManager;
    private final String configuredBaseUrl;
    private final AtomicBoolean running = new AtomicBoolean();

    public MonitorLifecycleRunner(
            MonitorLifecycleManager lifecycleManager,
            @Value("${cpa.base-url:${CPA_BASE_URL:http://127.0.0.1:8317}}") String configuredBaseUrl) {
        this.lifecycleManager = lifecycleManager;
        this.configuredBaseUrl = configuredBaseUrl;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            lifecycleManager.start(configuredBaseUrl);
            LOGGER.info("Monitor lifecycle started (phase={})", lifecycleManager.phase());
        } catch (RuntimeException exception) {
            // Lifecycle failure must not take the whole console down; scheduling stays off.
            LOGGER.error("Monitor lifecycle failed to start: {}", lifecycleManager.faultReason(), exception);
        }
    }

    @Override
    public void stop() {
        lifecycleManager.stop();
        running.set(false);
        LOGGER.info("Monitor lifecycle stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Stop before the DataSource bean is destroyed (SmartLifecycle default phases run
        // highest-first on stop), mirroring the usage runner contract.
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }
}
