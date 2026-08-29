package site.kael.cpa.console.core.usage;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public interface UsageEventSource {
    void run(BiConsumer<String, String> sink, BooleanSupplier running);
}
