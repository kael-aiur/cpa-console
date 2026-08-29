package site.kael.cpa.console.core.usage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoopUsageEventSourceTest {
    @Test
    void doesNotConsumeOrEmitUsageEvents() {
        AtomicInteger emitted = new AtomicInteger();
        new NoopUsageEventSource().run((source, raw) -> emitted.incrementAndGet(), () -> true);
        assertEquals(0, emitted.get());
    }
}
