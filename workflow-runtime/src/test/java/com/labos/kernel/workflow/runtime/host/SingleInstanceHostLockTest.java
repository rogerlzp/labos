package com.labos.kernel.workflow.runtime.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceHostLockTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsSecondHostAndAllowsRestartAfterRelease() throws Exception {
        Path lockFile = tempDir.resolve("runtime/workflow-runtime.lock");
        try (SingleInstanceHostLock first = SingleInstanceHostLock.acquire(lockFile)) {
            assertTrue(first.isValid());
            assertThrows(IllegalStateException.class,
                    () -> SingleInstanceHostLock.acquire(lockFile));
        }
        try (SingleInstanceHostLock restarted = SingleInstanceHostLock.acquire(lockFile)) {
            assertTrue(restarted.isValid());
        }
    }

    @Test
    void acquiresLockWhenParentIsSymlinkDirectoryOrNestedUnderSymlink() throws Exception {
        Path realDir = Files.createDirectory(tempDir.resolve("real-dir"));
        Path symlinkDir = Files.createSymbolicLink(tempDir.resolve("tmp-link"), realDir);

        Path viaSymlink = symlinkDir.resolve("workflow-runtime.lock");
        Path viaReal = realDir.resolve("workflow-runtime.lock");
        try (SingleInstanceHostLock lock = SingleInstanceHostLock.acquire(viaSymlink)) {
            assertTrue(lock.isValid());
            assertThrows(IllegalStateException.class,
                    () -> SingleInstanceHostLock.acquire(viaSymlink));
            assertThrows(IllegalStateException.class,
                    () -> SingleInstanceHostLock.acquire(viaReal));
        }
        try (SingleInstanceHostLock reacquired = SingleInstanceHostLock.acquire(viaSymlink)) {
            assertTrue(reacquired.isValid());
        }

        Path nestedUnderSymlink = symlinkDir.resolve("nested/sub/workflow-runtime.lock");
        try (SingleInstanceHostLock nestedLock = SingleInstanceHostLock.acquire(nestedUnderSymlink)) {
            assertTrue(nestedLock.isValid());
        }
    }

    @Test
    void failsClosedWhenParentIsBrokenSymlink() throws Exception {
        Path brokenLink = Files.createSymbolicLink(
                tempDir.resolve("broken-link"), tempDir.resolve("nonexistent-target"));
        assertThrows(FileAlreadyExistsException.class,
                () -> SingleInstanceHostLock.acquire(brokenLink.resolve("workflow-runtime.lock")));
    }
}