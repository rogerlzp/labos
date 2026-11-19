package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle boundary for the single-process Workflow Runtime Host.
 *
 * <p>Event ingress is started before recovery so device messages can be durably
 * recorded in Inbox. Its processor, dispatchers and scheduler belong in the
 * post-recovery component list and therefore cannot run before recovery ends.
 */
public final class RuntimeHost implements AutoCloseable {
    public enum State { NEW, STARTING, RECOVERING, RUNNING, STOPPING, STOPPED, FAILED }

    public interface LifecycleComponent {
        void start();
        void stop();
    }

    @FunctionalInterface
    public interface StartupRecovery {
        WorkerExecutionRecoveryService.StartupResult recover(int limit, String actor);
    }

    private final StartupRecovery recovery;
    private final LifecycleComponent eventIngress;
    private final List<LifecycleComponent> afterRecovery;
    private final SchedulingGate schedulingGate;
    private final int recoveryLimit;
    private final String recoveryActor;
    private final List<LifecycleComponent> startedComponents = new ArrayList<>();

    private State state = State.NEW;
    private WorkerExecutionRecoveryService.StartupResult recoveryResult;
    private boolean ingressStartAttempted;

    public RuntimeHost(WorkerExecutionRecoveryService recovery,
                       LifecycleComponent eventIngress,
                       List<LifecycleComponent> afterRecovery,
                       SchedulingGate schedulingGate,
                       int recoveryLimit,
                       String recoveryActor) {
        this(Objects.requireNonNull(recovery, "recovery")::recoverAtStartup,
                eventIngress, afterRecovery, schedulingGate, recoveryLimit, recoveryActor);
    }

    public RuntimeHost(StartupRecovery recovery,
                       LifecycleComponent eventIngress,
                       List<LifecycleComponent> afterRecovery,
                       SchedulingGate schedulingGate,
                       int recoveryLimit,
                       String recoveryActor) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.eventIngress = Objects.requireNonNull(eventIngress, "eventIngress");
        this.afterRecovery = List.copyOf(Objects.requireNonNull(afterRecovery, "afterRecovery"));
        this.schedulingGate = Objects.requireNonNull(schedulingGate, "schedulingGate");
        if (recoveryLimit < 1 || recoveryLimit > 1000)
            throw new IllegalArgumentException("recoveryLimit must be 1..1000");
        if (recoveryActor == null || recoveryActor.isBlank())
            throw new IllegalArgumentException("recoveryActor is required");
        this.recoveryLimit = recoveryLimit;
        this.recoveryActor = recoveryActor;
        this.schedulingGate.close();
    }

    public synchronized void start() {
        if (state != State.NEW) throw new IllegalStateException("RuntimeHost can only start once: " + state);
        state = State.STARTING;
        try {
            ingressStartAttempted = true;
            eventIngress.start();
            state = State.RECOVERING;
            recoveryResult = recovery.recover(recoveryLimit, recoveryActor);
            for (LifecycleComponent component : afterRecovery) {
                component.start();
                startedComponents.add(component);
            }
            schedulingGate.open();
            state = State.RUNNING;
        } catch (RuntimeException | Error failure) {
            schedulingGate.close();
            stopStartedComponents(failure);
            if (ingressStartAttempted) stopOne(eventIngress, failure);
            state = State.FAILED;
            throw failure;
        }
    }

    public synchronized State state() { return state; }

    public synchronized WorkerExecutionRecoveryService.StartupResult recoveryResult() {
        return recoveryResult;
    }

    public SchedulingGate schedulingGate() { return schedulingGate; }

    @Override
    public synchronized void close() {
        if (state == State.STOPPED) return;
        if (state == State.NEW) {
            schedulingGate.close();
            state = State.STOPPED;
            return;
        }
        if (state == State.STOPPING) return;
        state = State.STOPPING;
        schedulingGate.close();
        RuntimeException failure = null;
        List<LifecycleComponent> reverse = new ArrayList<>(startedComponents);
        Collections.reverse(reverse);
        for (LifecycleComponent component : reverse) {
            try { component.stop(); }
            catch (RuntimeException e) {
                if (failure == null) failure = new RuntimeException("RuntimeHost shutdown failed");
                failure.addSuppressed(e);
            }
        }
        startedComponents.clear();
        if (ingressStartAttempted) {
            try { eventIngress.stop(); }
            catch (RuntimeException e) {
                if (failure == null) failure = new RuntimeException("RuntimeHost shutdown failed");
                failure.addSuppressed(e);
            }
        }
        state = State.STOPPED;
        if (failure != null) throw failure;
    }

    private void stopStartedComponents(Throwable original) {
        List<LifecycleComponent> reverse = new ArrayList<>(startedComponents);
        Collections.reverse(reverse);
        for (LifecycleComponent component : reverse) stopOne(component, original);
        startedComponents.clear();
    }

    private static void stopOne(LifecycleComponent component, Throwable original) {
        try { component.stop(); }
        catch (RuntimeException stopFailure) { original.addSuppressed(stopFailure); }
    }
}
