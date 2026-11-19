package com.labos.kernel.workflow.runtime;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Central transaction boundary for Workflow Runtime repository adapters.
 * Runtime write transactions are short READ COMMITTED transactions with
 * explicit row locks/CAS. Checkpoint reads use a separate REPEATABLE READ,
 * read-only snapshot. External IO must never be executed inside these callbacks.
 */
public final class PostgresRuntimeTransactionExecutor {
    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate checkpointTemplate;

    public PostgresRuntimeTransactionExecutor(PlatformTransactionManager manager) {
        Objects.requireNonNull(manager, "manager");
        this.writeTemplate = new TransactionTemplate(manager);
        this.writeTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.writeTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.writeTemplate.setReadOnly(false);

        this.checkpointTemplate = new TransactionTemplate(manager);
        this.checkpointTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.checkpointTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.checkpointTemplate.setReadOnly(false);
    }

    public <T> T write(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return writeTemplate.execute(status -> work.get());
    }

    public <T> T checkpointSnapshot(Supplier<T> readSnapshot) {
        Objects.requireNonNull(readSnapshot, "readSnapshot");
        return checkpointTemplate.execute(status -> readSnapshot.get());
    }
}
