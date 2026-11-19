package com.labos.kernel.workflow.runtime.coordinator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RunCoordinatorRegistryTest {
    @Test
    void oneMailboxIsCreatedPerRunAndRemovalClosesIt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        List<String> handled = new CopyOnWriteArrayList<>();
        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 8, runId -> event -> handled.add(runId + ":" + event));
        UUID runA = UUID.randomUUID(), runB = UUID.randomUUID();
        try {
            assertSame(registry.mailboxFor(runA), registry.mailboxFor(runA));
            assertNotSame(registry.mailboxFor(runA), registry.mailboxFor(runB));
            registry.submit(runA, "a").get(2, TimeUnit.SECONDS);
            registry.submit(runB, "b").get(2, TimeUnit.SECONDS);
            assertEquals(2, registry.runCount());
            assertEquals(2, handled.size());
            assertTrue(registry.remove(runA));
            assertFalse(registry.remove(runA));
            assertThrows(IllegalStateException.class, () -> registry.submit(runA, "after-close"));
            assertEquals(1, registry.runCount());
        } finally {
            registry.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeRejectsCreationOfNewRunMailboxes() {
        var executor = Executors.newSingleThreadExecutor();
        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 4, runId -> event -> {});
        registry.close();
        try {
            assertThrows(IllegalStateException.class, () -> registry.mailboxFor(UUID.randomUUID()));
        } finally {
            executor.shutdownNow();
        }
    }
}
