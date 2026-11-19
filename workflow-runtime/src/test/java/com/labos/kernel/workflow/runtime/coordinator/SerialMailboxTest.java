package com.labos.kernel.workflow.runtime.coordinator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SerialMailboxTest {
    @Test
    void messagesForOneRunAreProcessedInOrderWithoutOverlap() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        SerialMailbox<Integer> mailbox = new SerialMailbox<>(128, executor, value -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            processed.add(value);
            active.decrementAndGet();
        });
        try {
            List<java.util.concurrent.CompletableFuture<Void>> completions = new ArrayList<>();
            for (int i = 0; i < 100; i++) completions.add(mailbox.submit(i));
            for (var completion : completions) completion.get(3, TimeUnit.SECONDS);
            assertEquals(100, processed.size());
            for (int i = 0; i < 100; i++) assertEquals(i, processed.get(i));
            assertEquals(1, maxActive.get());
        } finally {
            mailbox.close();
            executor.shutdownNow();
        }
    }

    @Test
    void fullMailboxRejectsWithoutGrowingAnUnboundedQueue() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SerialMailbox<Integer> mailbox = new SerialMailbox<>(1, executor, value -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            var running = mailbox.submit(1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = mailbox.submit(2);
            assertThrows(RejectedExecutionException.class, () -> mailbox.submit(3));
            assertEquals(1, mailbox.queuedCount());
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            mailbox.close();
            executor.shutdownNow();
        }
    }

    @Test
    void handlerFailureIsReportedAndDoesNotBlockFollowingMessages() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());
        SerialMailbox<Integer> mailbox = new SerialMailbox<>(4, executor, value -> {
            if (value == 1) throw new IllegalStateException("handler failed");
            processed.add(value);
        });
        try {
            var failed = mailbox.submit(1);
            var succeeded = mailbox.submit(2);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> failed.get(2, TimeUnit.SECONDS));
            succeeded.get(2, TimeUnit.SECONDS);
            assertEquals(List.of(2), processed);
        } finally {
            mailbox.close();
            executor.shutdownNow();
        }
    }
}
