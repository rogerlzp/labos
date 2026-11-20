package com.labos.kernel.workflow.runtime.coordinator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunCoordinatorRegistryTest {
    @Test
    void oneMailboxIsCreatedPerTenantScopedRunAndRemovalClosesIt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        List<String> handled = new CopyOnWriteArrayList<>();
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
        UUID runA = UUID.randomUUID(), runB = UUID.randomUUID();
        var keyA = new RunCoordinatorRegistry.RunKey(tenantA, runA);
        var keyB = new RunCoordinatorRegistry.RunKey(tenantA, runB);
        var crossTenantSameRunId = new RunCoordinatorRegistry.RunKey(tenantB, runA);

        Set<RunCoordinatorRegistry.RunKey> activeRuns = ConcurrentHashMap.newKeySet();
        activeRuns.add(keyA);
        activeRuns.add(keyB);

        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 8, activeRuns::contains, key -> event -> handled.add(key.tenantId() + ":" + key.runId() + ":" + event));
        try {
            assertSame(registry.mailboxFor(keyA), registry.mailboxFor(keyA));
            assertNotSame(registry.mailboxFor(keyA), registry.mailboxFor(keyB));
            // Same runId under a different tenant must be rejected when not active for that tenant.
            assertThrows(IllegalStateException.class, () -> registry.mailboxFor(crossTenantSameRunId));

            registry.submit(keyA, "a").get(2, TimeUnit.SECONDS);
            registry.submit(keyB, "b").get(2, TimeUnit.SECONDS);
            assertEquals(2, registry.runCount());
            assertEquals(2, handled.size());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!registry.mailboxFor(keyA).isIdle() && System.nanoTime() < deadline) Thread.yield();
            assertTrue(registry.remove(keyA));
            assertFalse(registry.remove(keyA));
            activeRuns.remove(keyA);
            assertThrows(IllegalStateException.class, () -> registry.submit(keyA, "after-close"));
            assertEquals(1, registry.runCount());
        } finally {
            registry.close();
            executor.shutdownNow();
        }
    }

    @Test
    void admissionCheckRunsOutsideGlobalLockSoSlowRunDoesNotBlockOtherRuns() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var callerPool = Executors.newFixedThreadPool(2);
        UUID tenant = UUID.randomUUID();
        var slowKey = new RunCoordinatorRegistry.RunKey(tenant, UUID.randomUUID());
        var fastKey = new RunCoordinatorRegistry.RunKey(tenant, UUID.randomUUID());
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);

        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 4,
                key -> {
                    if (key.equals(slowKey)) {
                        slowEntered.countDown();
                        try { assertTrue(releaseSlow.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    return true;
                },
                key -> event -> {});
        try {
            CompletableFuture<SerialMailbox<String>> slowFuture =
                    CompletableFuture.supplyAsync(() -> registry.mailboxFor(slowKey), callerPool);
            assertTrue(slowEntered.await(2, TimeUnit.SECONDS));

            // While slowKey is blocked inside runIsActive, fastKey must succeed immediately.
            registry.submit(fastKey, "fast").get(2, TimeUnit.SECONDS);
            assertEquals(1, registry.runCount());

            releaseSlow.countDown();
            assertNotNull(slowFuture.get(2, TimeUnit.SECONDS));
            assertEquals(2, registry.runCount());
        } finally {
            releaseSlow.countDown();
            registry.close();
            executor.shutdownNow();
            callerPool.shutdownNow();
        }
    }

    @Test
    void requestEvictWhenIdleSafelyReclaimsMailboxWithoutDroppingConcurrentSubmits() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        UUID tenant = UUID.randomUUID();
        var key = new RunCoordinatorRegistry.RunKey(tenant, UUID.randomUUID());
        Set<RunCoordinatorRegistry.RunKey> activeRuns = ConcurrentHashMap.newKeySet();
        activeRuns.add(key);
        AtomicInteger processed = new AtomicInteger();

        RunCoordinatorRegistry<String>[] holder = new RunCoordinatorRegistry[1];
        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 16, activeRuns::contains,
                k -> event -> {
                    processed.incrementAndGet();
                    if ("complete".equals(event)) {
                        activeRuns.remove(k);
                        holder[0].requestEvictWhenIdle(k);
                    }
                });
        holder[0] = registry;
        try {
            registry.submit(key, "step1").get(2, TimeUnit.SECONDS);
            registry.submit(key, "complete").get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.runCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, registry.runCount(), "completed Run mailbox must be reclaimed once idle");
            assertEquals(2, processed.get());
            assertThrows(IllegalStateException.class, () -> registry.submit(key, "after-complete"));
        } finally {
            registry.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeRejectsCreationOfNewRunMailboxes() {
        var executor = Executors.newSingleThreadExecutor();
        RunCoordinatorRegistry<String> registry = new RunCoordinatorRegistry<>(
                executor, 4, key -> true, key -> event -> {});
        registry.close();
        try {
            assertThrows(IllegalStateException.class,
                    () -> registry.mailboxFor(UUID.randomUUID(), UUID.randomUUID()));
        } finally {
            executor.shutdownNow();
        }
    }
}
