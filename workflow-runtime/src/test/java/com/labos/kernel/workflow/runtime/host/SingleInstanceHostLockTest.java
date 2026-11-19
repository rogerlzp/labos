package com.labos.kernel.workflow.runtime.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}