package com.boatarde.regatasimulator.application;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Single-JVM application barrier; external writers must be stopped separately. */
@Component
public class MediaMutationGuard {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public Lease mutation() {
        lock.readLock().lock();
        return lock.readLock()::unlock;
    }

    public Lease snapshot() {
        if (lock.getReadHoldCount() != 0) throw new IllegalStateException("Snapshot cannot upgrade a mutation lease");
        lock.writeLock().lock();
        return lock.writeLock()::unlock;
    }

    public interface Lease extends AutoCloseable {
        @Override void close();
    }
}