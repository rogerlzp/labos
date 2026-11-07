package com.labos.kernel.workflow;

import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.engine.StepExecutor;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutionSafetyTest {

    @Test
    void graphRuntimeFailureAfterSideEffectMustNotInvokeSequentialFallback() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             id="D1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <process id="P1" isExecutable="false">
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                  </process>
                </definitions>
                """;
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        GraphRunner runner = new GraphRunner(sm,
                Map.of("TRANSFER", List.of(101L)),
                Map.of("Transfer", "TRANSFER"),
                s -> { });
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger sequentialFallbacks = new AtomicInteger();
        StepExecutor executor = ctx -> {
            sideEffects.incrementAndGet(); // 模拟设备已产生不可撤销副作用
            throw new IllegalStateException("injected after device side effect");
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> WorkflowService.executeGraphWithParseFallback(
                        xml,
                        () -> {
                            sequentialFallbacks.incrementAndGet();
                            return null;
                        },
                        runner,
                        executor));

        assertEquals("injected after device side effect", thrown.getMessage());
        assertEquals(1, sideEffects.get());
        assertEquals(0, sequentialFallbacks.get(),
                "执行期异常不得退回顺序执行，避免从头重放物理动作");
    }

    @Test
    void parseFailureMayUsePreExecutionFallback() {
        AtomicInteger sequentialFallbacks = new AtomicInteger();

        WorkflowService.GraphExecution result =
                WorkflowService.executeGraphWithParseFallback(
                        "<definitions><broken>",
                        () -> {
                            sequentialFallbacks.incrementAndGet();
                            return null;
                        },
                        null,
                        null);

        assertFalse(result.viaGraph());
        assertNull(result.progress());
        assertEquals(1, sequentialFallbacks.get());
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy(null, RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 99, true))));
    }
}
