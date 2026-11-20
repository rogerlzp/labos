package com.labos.kernel.workflow.runtime.host;

/** Test-only factory keeping {@link SchedulingGate#open()} package-private in production. */
public final class TestSchedulingGates {
    private TestSchedulingGates() {}

    public static SchedulingGate openGate() {
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        return gate;
    }
}
