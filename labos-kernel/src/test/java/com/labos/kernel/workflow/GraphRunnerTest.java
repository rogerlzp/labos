package com.labos.kernel.workflow;

import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按图执行的验收测试。
 *
 * <p>三条必须成立的语义（对应规格 7.3）：
 * <ol>
 *   <li>并行分叉后，两条分支都要执行</li>
 *   <li>并行汇合点，等所有入边到达才继续</li>
 *   <li>异或分叉，只走一条出边</li>
 * </ol>
 */
public final class GraphRunnerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("════ 按图执行验收 ════");
        System.out.println();

        caseLinear();
        caseParallelBothBranches();
        caseParallelJoinWaits();
        caseXorOnlyOneBranch();
        caseXorDefaultEdge();
        testPauseCheckpoint();
        caseLoopRounds();
        caseBuiltinNodes();
        caseSkipKeysResume();

        System.out.println();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 线性：start -> A -> end，只执行 A。 */
    private static void caseLinear() {
        check("线性流程：只执行 1 个任务", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                    """);
            RunProgress p = run(xml);
            eq("执行任务数", 1, p.all().size());
            eq("全部完成", 1, p.count(NodeStatus.COMPLETED));
        });
    }

    /** 并行：两条分支都要跑。 */
    private static void caseParallelBothBranches() {
        check("并行分叉：两条分支都执行", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <parallelGateway id="SPLIT"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <parallelGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SPLIT"/>
                    <sequenceFlow id="F2" sourceRef="SPLIT" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="SPLIT" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            RunProgress p = run(xml);
            eq("执行任务数（A/B/C 三个）", 3, p.all().size());
            eq("全部完成", 3, p.count(NodeStatus.COMPLETED));
        });
    }

    /** 并行汇合：入度没满足时，汇合点后的节点不执行。 */
    private static void caseParallelJoinWaits() {
        check("并行汇合：缺一条分支时后续不执行", () -> {
            // XOR 只走 A；B->JOIN 的边永远不激活，JOIN 入度 2 只到 1
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="XOR" default="F2"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <parallelGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="XOR"/>
                    <sequenceFlow id="F2" sourceRef="XOR" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="XOR" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            RunProgress p = run(xml);
            // A 执行；B 不执行；JOIN 等不到第二条，C 也不执行
            eq("执行任务数（只有 A）", 1, p.all().size());
            long aId = Math.abs((long) "A".hashCode());
            checkTrue("执行的是 A", p.all().stream().anyMatch(np -> np.nodeId() == aId));
        });
    }

    /** 异或：只走一条。 */
    private static void caseXorOnlyOneBranch() {
        check("异或分叉：只执行一条分支", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="XOR" default="F3"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="XOR"/>
                    <sequenceFlow id="F2" sourceRef="XOR" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="XOR" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="E"/>
                    """);
            RunProgress p = run(xml);
            eq("只执行 1 个任务", 1, p.all().size());
            long bId = Math.abs((long) "B".hashCode());
            checkTrue("执行的是 default 分支 B",
                    p.all().stream().anyMatch(np -> np.nodeId() == bId));
        });
    }

    /** 无 default 时走稳定排序第一条。 */
    private static void caseXorDefaultEdge() {
        check("异或无 default：走稳定排序第一条", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="XOR"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="XOR"/>
                    <sequenceFlow id="F2" sourceRef="XOR" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="XOR" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="E"/>
                    """);
            RunProgress p = run(xml);
            eq("只执行 1 个任务", 1, p.all().size());
        });
    }

    /** P1-8：暂停检查点 —— 在第 2 个节点边界停下，只执行 1 个节点。 */
    private static void testPauseCheckpoint() {
        check("暂停检查点：第 2 个节点边界停下，只跑 1 个", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="B"/>
                    <sequenceFlow id="F3" sourceRef="B" targetRef="C"/>
                    <sequenceFlow id="F4" sourceRef="C" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());

            // 第 1 次检查放行，第 2 次起报告"要暂停"
            java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L, 102L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withPauseChecker(() -> calls.incrementAndGet() > 1);

            RunProgress p = runner.run(g, new SimulatedBus());

            eq("只执行了 1 个节点", 1, p.all().size());
            eq("标记为已暂停", true, runner.runPaused());
            eq("没有被判失败", 0, p.count(NodeStatus.MANUAL) + (p.aborted() ? 1 : 0));
        });
    }

    // ---------------------------------------------------------------- 工具

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


    /**
     * 循环：每圈都要回调一次 `RoundRecorder`，圈次从 1 递增。
     *
     * <p>为什么重要：`run_node` 每个节点只有一行，循环跑 5 圈也只有最终状态 ——
     * "第 3 圈为什么失败"查不到。这里钉住"每圈都上报"，落库那侧才有数据可写。
     */
    private static void caseLoopRounds() {
        check("循环：每圈都上报圈次", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <exclusiveGateway id="AGAIN" default="F3"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="AGAIN"/>
                    <sequenceFlow id="F3" sourceRef="AGAIN" targetRef="E"/>
                    <sequenceFlow id="F4" sourceRef="AGAIN" targetRef="A">
                      <conditionExpression>1 == 1</conditionExpression>
                    </sequenceFlow>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            List<Integer> rounds = new ArrayList<>();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withMaxVisits(3)
                    .withRoundRecorder((key, round, st, ec, s1, e1) -> {
                        if ("A".equals(key)) {
                            rounds.add(round);
                        }
                    });
            runner.run(g, new SimulatedBus());
            // 3 圈 = 3 次回调，圈次 1/2/3
            eq("圈次回调次数", 3, rounds.size());
            eq("圈次序列", "[1, 2, 3]", rounds.toString());
        });
    }


    /**
     * 内置节点：`__setVar` / `__expr` 不驱动设备，引擎自己算，状态照样记完成。
     *
     * <p>这条用例的价值：内置节点最容易出的事故是"被当成设备任务"——
     * 那样会因为"没有候选设备"直接转人工（我们第一次跑就是这样）。
     */
    private static void caseBuiltinNodes() {
        check("内置节点：变量/表达式不碰设备", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="SET" name="初始化" protocolName="__setVar"/>
                    <task id="INC" name="计数+1" protocolName="__expr"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SET"/>
                    <sequenceFlow id="F2" sourceRef="SET" targetRef="INC"/>
                    <sequenceFlow id="F3" sourceRef="INC" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withBuiltinParams(Map.of(
                            "SET", Map.of("var", "counter", "value", "0"),
                            "INC", Map.of("var", "counter", "expr", "${counter} + 1")));
            RunProgress p = runner.run(g, bus);
            eq("两个内置节点都完成", 2, p.count(NodeStatus.COMPLETED));
            eq("没有产生任何设备命令", 0, bus.callLog().size());
        });
    }

    /**
     * 断点续跑：`skipKeys` 按 `node_key` 匹配。
     *
     * <p>钉住两件事：
     * <ol>
     *   <li>命中：上一轮已完成的节点这一轮不执行、也不再发设备命令（这才是真续跑）；</li>
     *   <li>失配：节点**改名后**旧 key 对不上，节点会被重跑 —— 这是已知边界，
     *       必须被测出来，而不是被当成"续跑成功"。</li>
     * </ol>
     */
    private static void caseSkipKeysResume() {
        String xml = bpmn("""
                <startEvent id="S"/>
                <task id="A" name="Transfer"/>
                <task id="B" name="Transfer"/>
                <endEvent id="E"/>
                <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                <sequenceFlow id="F2" sourceRef="A" targetRef="B"/>
                <sequenceFlow id="F3" sourceRef="B" targetRef="E"/>
                """);

        check("断点续跑：命中的节点跳过、不驱动设备", () -> {
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSkipKeys(java.util.Set.of("A"));
            RunProgress p = runner.run(g, bus);
            eq("只执行 B 一个节点", 1, p.all().size());
            checkTrue("跳过的是 A（A 不在执行记录里）", p.all().stream()
                    .noneMatch(np -> np.nodeId() == Math.abs((long) "A".hashCode())));
            eq("只发 1 条设备命令（B）", 1, bus.callLog().size());
        });

        check("断点续跑：节点改名后旧 key 失配，节点会重跑（已知边界）", () -> {
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSkipKeys(java.util.Set.of("A_RENAMED"));
            RunProgress p = runner.run(g, bus);
            eq("A/B 都执行（旧 key 没对上）", 2, p.all().size());
            eq("发 2 条设备命令", 2, bus.callLog().size());
        });
    }

    private static RunProgress run(String xml) {
        Graph g = WorkflowGraphParser.parse(xml);
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        GraphRunner runner = new GraphRunner(sm,
                Map.of("TRANSFER", List.of(101L, 102L)),
                Map.of("Transfer", "TRANSFER"),
                s -> { });
        return runner.run(g, new SimulatedBus());
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
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            failed++;
            System.out.printf("  ✘ %s%n     %s%n", name, e.getMessage());
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    private static void checkTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what + " 不成立");
        }
    }

    private GraphRunnerTest() {
    }
}
