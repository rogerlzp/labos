package com.labos.kernel.workflow.runtime.host;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local scheduling admission gate for the single Runtime Host.
 * It starts closed and may only be opened by RuntimeHost after startup recovery
 * and component initialization have completed.
 */
public final class SchedulingGate {
    private final AtomicBoolean open = new AtomicBoolean(false);

    void open() { open.set(true); }

    void close() { open.set(false); }

    public boolean isOpen() { return open.get(); }

    public void requireOpen() {
        if (!isOpen()) throw new IllegalStateException("Runtime scheduling gate is closed");
    }
}
