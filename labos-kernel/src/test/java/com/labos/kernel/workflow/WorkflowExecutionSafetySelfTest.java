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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 回归：图执行已产生副作用后抛异常，绝不能退回顺序路径重跑。 */
public final class WorkflowExecutionSafetySelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        caseGraphRuntimeFailureDoesNotFallback();
        caseParseFailureFallsBackBeforeExecution();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void caseGraphRuntimeFailureDoesNotFallback() {
        check("图执行期异常不触发顺序 fallback", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                    """);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { });
            AtomicInteger sideEffects = new AtomicInteger();
            AtomicInteger sequentialFallbacks = new AtomicInteger();
            StepExecutor executor = ctx -> {
                sideEffects.incrementAndGet();
                throw new IllegalStateException("injected after device side effect");
            };

            boolean thrown = false;
            try {
                WorkflowService.executeGraphWithParseFallback(
                        xml,
                        () -> {
                            sequentialFallbacks.incrementAndGet();
                            return null;
                        },
                        runner,
                        executor);
            } catch (IllegalStateException expected) {
                thrown = "injected after device side effect".equals(expected.getMessage());
            }
            eq("执行期异常向上传播", true, thrown);
            eq("模拟物理副作用只发生一次", 1, sideEffects.get());
            eq("顺序 fallback 未被调用", 0, sequentialFallbacks.get());
        });
    }

    private static void caseParseFailureFallsBackBeforeExecution() {
        check("BPMN 解析失败仍可在执行前走兼容 fallback", () -> {
            AtomicInteger fallback = new AtomicInteger();
            WorkflowService.GraphExecution result =
                    WorkflowService.executeGraphWithParseFallback(
                            "<definitions><broken>",
                            () -> {
                                fallback.incrementAndGet();
                                return null;
                            },
                            null,
                            null);
            eq("不是图执行路径", false, result.viaGraph());
            eq("fallback 调用一次", 1, fallback.get());
        });
    }

    private static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             id="D1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <process id="P1" isExecutable="false">
                %s
                  </process>
                </definitions>
                """.formatted(body);
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy(null, RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 99, true))));
    }

    private static void check(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  ✔ " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  ✘ " + name + " | " + e);
        }
    }

    private static void eq(String label, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private WorkflowExecutionSafetySelfTest() {
    }
}
