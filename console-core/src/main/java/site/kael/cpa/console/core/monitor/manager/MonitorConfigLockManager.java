package site.kael.cpa.console.core.monitor.manager;

import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * D5.1a hot-update fence (T02): configuration updates and the final send fence use one
 * read/write lock. An update takes the write lock and swaps the immutable client/config
 * version atomically. A dispatch takes the read lock, captures the immutable endpoint and
 * client reference and submits the single verify POST under the same lock; a PATCH that wins
 * means no POST, a send that wins means the old client finishes its call.
 */
public final class MonitorConfigLockManager {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile CpaCredentialVerificationClient currentClient;
    private volatile String currentConfigVersion;

    public MonitorConfigLockManager(CpaCredentialVerificationClient initialClient, String initialConfigVersion) {
        this.currentClient = Objects.requireNonNull(initialClient, "initialClient");
        this.currentConfigVersion = Objects.requireNonNull(initialConfigVersion, "initialConfigVersion");
    }

    /** Atomically installs a new immutable client/config version; callers serialize behind the write lock. */
    public void updateClient(CpaCredentialVerificationClient newClient, String newConfigVersion) {
        Objects.requireNonNull(newClient, "newClient");
        Objects.requireNonNull(newConfigVersion, "newConfigVersion");
        lock.writeLock().lock();
        try {
            this.currentClient = newClient;
            this.currentConfigVersion = newConfigVersion;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Read-side capture of the current immutable client and config version. */
    public Snapshot snapshot() {
        lock.readLock().lock();
        try {
            return new Snapshot(currentClient, currentConfigVersion);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Read-only work executed under the shared read lock. */
    public <T> T readLocked(Supplier<T> work) {
        lock.readLock().lock();
        try {
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Captured immutable call context; used by the send fence in T06. */
    public record Snapshot(CpaCredentialVerificationClient client, String configVersion) {
        public Snapshot {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(configVersion, "configVersion");
        }
    }
}
