package com.labos.kernel.workflow.runtime.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local OS process lock for the V1 single-host deployment.
 *
 * <p>The lock file must be on the Runtime Host's local filesystem. This does
 * not use PostgreSQL advisory locks or any distributed coordination protocol.
 * Acquire it before startup recovery, WorkerPool startup, or Outbox dispatch,
 * and hold it until graceful shutdown. The OS releases it after process death.
 */
public final class SingleInstanceHostLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SingleInstanceHostLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceHostLock acquire(Path lockFile) throws IOException {
        Objects.requireNonNull(lockFile, "lockFile");
        Path absolute = lockFile.toAbsolutePath().normalize();
        ensureParentDirectory(absolute.getParent());

        FileChannel channel = FileChannel.open(absolute,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyHeldInThisProcess) {
                lock = null;
            }
            if (lock == null) {
                throw new IllegalStateException(
                        "Another LabOS Workflow Runtime Host already holds the local process lock");
            }
            return new SingleInstanceHostLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel.isOpen()) channel.close();
            throw failure;
        }
    }

    public boolean isValid() {
        return !closed.get() && lock.isValid();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }

    /** Create missing ancestor directories while tolerating existing symlinked directory ancestors (e.g. /tmp, /var/run). */
    private static void ensureParentDirectory(Path parent) throws IOException {
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        ensureParentDirectory(parent.getParent());
        try {
            Files.createDirectory(parent);
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isDirectory(parent)) {
                throw existing;
            }
        }
    }
}