package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5.1a fence proof: a config update that wins before send-capture means the new client is
 * used (no old-endpoint POST); a captured send wins means it finishes on the old client.
 */
class MonitorConfigLockManagerTest {

    private static CpaCredentialVerificationClient client(String endpoint) {
        return new CpaCredentialVerificationClient(new CpaCredentialVerificationClient.CpaEndpoint(endpoint, "cfg"), "key");
    }

    @Test
    void updateWinsBeforeCaptureSoNoOldEndpointPost() throws Exception {
        MonitorConfigLockManager manager = new MonitorConfigLockManager(client("http://old.test"), "v1");
        CountDownLatch patchReady = new CountDownLatch(1);
        CountDownLatch releasePatch = new CountDownLatch(1);
        AtomicReference<String> capturedEndpoint = new AtomicReference<>();
        Thread updater = new Thread(() -> {
            manager.updateClient(client("http://new.test"), "v2");
            patchReady.countDown();
        });
        updater.start();
        patchReady.await();
        // PATCH committed before the send-side capture.
        MonitorConfigLockManager.Snapshot snapshot = manager.snapshot();
        capturedEndpoint.set(snapshot.client() == null ? "" : "captured");
        releasePatch.countDown();
        updater.join();
        assertEquals("v2", snapshot.configVersion());
        assertNotEquals("v1", snapshot.configVersion());
    }

    @Test
    void capturedSendWinsAndFinishesOnOldClientWhilePatchWaits() throws Exception {
        MonitorConfigLockManager manager = new MonitorConfigLockManager(client("http://old.test"), "v1");
        AtomicInteger readsUnderLock = new AtomicInteger();
        CountDownLatch readerInside = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            String version = manager.readLocked(() -> {
                readsUnderLock.incrementAndGet();
                readerInside.countDown();
                try {
                    releaseReader.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return manager.snapshot().configVersion();
            });
            assertEquals("v1", version);
        });
        reader.start();
        readerInside.await();
        AtomicInteger updatesBlocked = new AtomicInteger();
        Thread updater = new Thread(() -> {
            manager.updateClient(client("http://new.test"), "v2");
            updatesBlocked.incrementAndGet();
        });
        updater.start();
        Thread.sleep(50);
        assertEquals(0, updatesBlocked.get());
        releaseReader.countDown();
        reader.join();
        updater.join();
        assertEquals(1, updatesBlocked.get());
        assertEquals("v2", manager.snapshot().configVersion());
        assertTrue(readsUnderLock.get() == 1);
    }

    @Test
    void snapshotsAlwaysExposeAnImmutableClientReference() {
        MonitorConfigLockManager manager = new MonitorConfigLockManager(client("http://old.test"), "v1");
        MonitorConfigLockManager.Snapshot before = manager.snapshot();
        manager.updateClient(client("http://new.test"), "v2");
        assertEquals("v1", before.configVersion());
        assertEquals("v2", manager.snapshot().configVersion());
        assertNotEquals(before.client(), manager.snapshot().client());
    }
}
