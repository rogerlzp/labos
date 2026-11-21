package com.labos.kernel.workflow.runtime.persistence;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only {@link PlatformTransactionManager} decorator that injects a fault exactly at the
 * JDBC transaction boundary.
 *
 * <p>It does not change any production code path: the decorated
 * {@link WorkflowRuntimeRepository} keeps using its own {@code TransactionTemplate}s and its own
 * {@code lockRun}/{@code appendEvents} logic. This decorator only decides (a) whether the physical
 * commit is attempted and (b) whether the caller observes the result. Because it hooks the
 * {@link PlatformTransactionManager} contract rather than JDBC internals, it also releases the
 * Spring connection binding exactly once, so a faulted transaction never leaks a pooled
 * connection or a still-open PostgreSQL transaction into the next test step.
 *
 * <p>Read-only transactions (the Repository's {@code snapshotTx}, used only for reads) are never
 * faulted and never consume the armed fault, so a fault armed for one write call cannot be
 * consumed by an unrelated snapshot read.
 *
 * <p>The whole armed state is one immutable {@link Armed} value held in a single
 * {@link AtomicReference}. There is no separate "mode" + "remaining" pair to observe
 * inconsistently: reading the reference and decrementing the ordinal is a single atomic CAS.
 */
final class FaultInjectingTransactionManager implements PlatformTransactionManager {

    /** How an injected fault behaves at the commit boundary. */
    enum Mode {
        /** No fault. */
        NONE,
        /**
         * Simulate a crash/abort before commit: physically roll the transaction back, then fail the
         * caller. Nothing the transaction wrote is durable.
         */
        ABORT_BEFORE_COMMIT,
        /**
         * Simulate "commit succeeded but the caller never received the result": physically commit,
         * then fail the caller. Every write of the transaction IS durable.
         */
        LOST_RESULT_AFTER_COMMIT
    }

    /** Thrown for {@link Mode#ABORT_BEFORE_COMMIT}; nothing is durable. */
    static final class InjectedCommitAbort extends RuntimeException {
        InjectedCommitAbort(String message) { super(message); }
    }

    /** Thrown for {@link Mode#LOST_RESULT_AFTER_COMMIT}; the write IS durable. */
    static final class InjectedLostResult extends RuntimeException {
        InjectedLostResult(String message) { super(message); }
    }

    /**
     * Immutable armed state: the fault to inject and the 1-based ordinal of the write commit that
     * must trigger it. Read-only commits are not counted.
     */
    private record Armed(Mode mode, int writeOrdinal) {}

    private final PlatformTransactionManager delegate;
    private final AtomicReference<Armed> armed = new AtomicReference<>();

    FaultInjectingTransactionManager(PlatformTransactionManager delegate) {
        this.delegate = delegate;
    }

    /** Arm a one-shot fault on the next <em>write</em> commit, then self-disarm. */
    void armOneShot(Mode mode) {
        armAtWrite(mode, 1);
    }

    /**
     * Arm a one-shot fault on the <em>Nth</em> write commit after this call. Use
     * {@code armAtWrite(mode, 2)} to crash the second transaction of a multi-transaction caller
     * (for example {@code GraphCoordinator.advance}) while the first transaction stays durable.
     * Read-only commits are ignored and do not consume an ordinal.
     */
    void armAtWrite(Mode mode, int writeOrdinal) {
        if (mode == Mode.NONE) throw new IllegalArgumentException("NONE is not an injectable fault");
        if (writeOrdinal < 1) throw new IllegalArgumentException("writeOrdinal must be >= 1");
        this.armed.set(new Armed(mode, writeOrdinal));
    }

    /** Disarm without firing, so setup calls stay clean. */
    void disarm() {
        this.armed.set(null);
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        Armed current = this.armed.get();
        if (current == null || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            delegate.commit(status);
            return;
        }
        // Consume one write ordinal atomically; the fault fires only when the counter reaches zero.
        Armed next = this.armed.updateAndGet(prev -> (prev == null || prev.writeOrdinal() <= 1)
                ? null : new Armed(prev.mode(), prev.writeOrdinal() - 1));
        if (next != null) {
            delegate.commit(status);
            return;
        }
        if (current.mode() == Mode.ABORT_BEFORE_COMMIT) {
            delegate.rollback(status);
            throw new InjectedCommitAbort("injected abort before commit");
        }
        // LOST_RESULT_AFTER_COMMIT: the write is already durable when the caller gets the failure.
        delegate.commit(status);
        throw new InjectedLostResult("injected lost result after commit");
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        delegate.rollback(status);
    }
}
