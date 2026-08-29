package site.kael.cpa.console.core.usage;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Usage source used when usage collection is explicitly disabled.
 */
public class NoopUsageEventSource implements UsageEventSource {
    @Override
    public void run(BiConsumer<String, String> sink, BooleanSupplier running) {
        // Deliberately do not connect to or consume anything from CPA.
    }
}
