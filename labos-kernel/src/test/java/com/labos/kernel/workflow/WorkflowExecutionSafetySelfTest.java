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

/**
 * B-01 回归：图执行已经产生物理副作用后抛异常，**绝不能**退回顺序执行重跑。
 *
 * <p>「退回 executeAll」等于把同一张流程图从头再执行一遍。真实设备上就是
 * 第二次开盖 / 第二次移液，且此时现场状态已经不是流程图预期的初始状态。
 */
public final class WorkflowExecutionSafetySelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        caseGraphRuntimeFailureDoesNotFallback();
        caseParseFailureFallsBackBeforeExecution();
        caseFallbackOnlyHappensBeforeAnyDispatch();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 执行期异常：异常必须抛出去、副作用只发生一次、顺序 fallback 一次都不能被调用。
     */
    private static void caseGraphRuntimeFailureDoesNotFallback() {
        check("图执行期异常不触发顺序 fallback", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                    """);
            GraphRunner runner = runner();
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

    /** 解析失败（还什么都没派发）：允许退回顺序执行，且结果标记为"不是图执行"。 */
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
                            runner(),
                            ctx -> { });
            eq("不是图执行路径", false, result.viaGraph());
            eq("fallback 调用一次", 1, fallback.get());
        });
    }

    /** 正常路径：viaGraph=true，且执行体被调用（避免"永远 fallback"式的假通过）。 */
    private static void caseFallbackOnlyHappensBeforeAnyDispatch() {
        check("解析成功时走图执行且不触发 fallback", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                    """);
            AtomicInteger fallback = new AtomicInteger();
            AtomicInteger dispatched = new AtomicInteger();
            WorkflowService.GraphExecution result =
                    WorkflowService.executeGraphWithParseFallback(
                            xml,
                            () -> {
                                fallback.incrementAndGet();
                                return null;
                            },
                            runner(),
                            ctx -> dispatched.incrementAndGet());
            eq("走的是图执行路径", true, result.viaGraph());
            eq("没有退回顺序执行", 0, fallback.get());
            eq("图执行确实派发了节点", 1, dispatched.get());
        });
    }

    private static GraphRunner runner() {
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        return new GraphRunner(sm,
                Map.of("TRANSFER", List.of(101L)),
                Map.of("Transfer", "TRANSFER"),
                s -> { });
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
