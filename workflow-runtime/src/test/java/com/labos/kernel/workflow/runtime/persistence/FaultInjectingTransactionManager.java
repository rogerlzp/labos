package com.labos.kernel.workflow.runtime.persistence;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

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
 * faulted, so a fault armed for one write call cannot be consumed by an unrelated snapshot read.
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

    private final PlatformTransactionManager delegate;
    private volatile Mode mode = Mode.NONE;
    private final AtomicInteger remaining = new AtomicInteger();

    FaultInjectingTransactionManager(PlatformTransactionManager delegate) {
        this.delegate = delegate;
    }

    /** Arm a one-shot fault. It fires on the next <em>write</em> commit, then self-disarms. */
    void armOneShot(Mode mode) {
        if (mode == Mode.NONE) throw new IllegalArgumentException("NONE is not an injectable fault");
        this.mode = mode;
        this.remaining.set(1);
    }

    /** Disarm without firing, so setup calls stay clean. */
    void disarm() {
        this.mode = Mode.NONE;
        this.remaining.set(0);
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        Mode current = this.mode;
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (current == Mode.NONE || readOnly
                || remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) <= 0) {
            delegate.commit(status);
            return;
        }
        // Fault consumed: self-disarm so any retry inside the same test runs against real code.
        this.mode = Mode.NONE;
        if (current == Mode.ABORT_BEFORE_COMMIT) {
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
