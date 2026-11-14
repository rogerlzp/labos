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
import java.util.HashMap;
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
        caseDryRunNoDeviceStillReachable();
        caseXorDefaultEdge();
        caseConditionalSplitMultipleBranches();
        caseConditionalJoinSkipsDeadBranch();
        caseConditionalDefaultFallback();
        caseConditionalInsideSkippedBranch();
        caseConditionalDryRunAllReachable();
        testPauseCheckpoint();
        caseLoopRounds();
        caseLoopRoundScoping();
        caseBuiltinNodes();
        caseSleepNode();
        caseWaitSatisfied();
        caseWaitTimeout();
        caseWaitNotClobberedByRefresh();
        caseWaitConsumeChain();
        caseWaitConsumeRejectsStale();
        caseWaitWithoutConsumePassesStale();
        caseWaitAnyHits();
        caseWaitAnyTimeout();
        caseBuiltinProtocolResolvesToLogic();
        caseBuiltinDryRunNoOp();
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

            // 干跑（保存前预检）里异或**不分叉**：条件读运行时变量，干跑值未知，
            // 只按 default 走单边会把另一条分支误判成「永远走不到」（PF-03 假报）。
            // 预检只回答「有没有可能走到」，所以两条分支都要算可达。
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            GraphRunner dry = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L, 102L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { }).withDryRun(true);
            dry.run(g, new SimulatedBus());
            checkTrue("干跑两条分支都算可达（条件分支不假报 PF-03）",
                    dry.activatedKeys().contains("A") && dry.activatedKeys().contains("B"));
        });
    }

    /**
     * 干跑不该因为「任务节点分不到设备」而中断：任务在干跑里只回答结构可达性，
     * 设备分配是 PF-01/PF-02 的职责。此前干跑会真执行任务 —— 分不到设备就 MANUAL，
     * 整图 break，下游节点被误判成 PF-03「永远走不到」
     * （2026-11-06 实踩：异或的默认分支 B 被误报）。
     */
    private static void caseDryRunNoDeviceStillReachable() {
        check("干跑：任务分不到设备也不误报下游走不到", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="A" name="未知动作"/>
                    <task id="B" name="未知动作"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="B"/>
                    <sequenceFlow id="F3" sourceRef="B" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            // 空能力池：两个任务都分不到设备（真跑会转人工）
            GraphRunner dry = new GraphRunner(sm, Map.of(), Map.of(), s -> { })
                    .withDryRun(true);
            dry.run(g, new SimulatedBus());
            checkTrue("干跑 A 可达", dry.activatedKeys().contains("A"));
            checkTrue("干跑 B 可达（不因上游分不到设备而中断）",
                    dry.activatedKeys().contains("B"));
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

    // -------------------------------------------------- 条件网关（包容网关）

    /**
     * 包容语义的核心：**条件成立的几条分支都走**。
     *
     * <p>异或做不到这件事 —— 它是「第一条成立就赢」，后面的条件根本不会被求值。
     */
    private static void caseConditionalSplitMultipleBranches() {
        check("条件分叉：两条条件都成立 → 两条分支都执行", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A">
                      <conditionExpression>${a} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B">
                      <conditionExpression>${b} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            RunProgress p = runWithVars(xml, Map.of("a", "yes", "b", "yes"));
            eq("A/B/C 三个任务都执行", 3, p.all().size());
            eq("全部完成", 3, p.count(NodeStatus.COMPLETED));
        });
    }

    /**
     * 条件汇合最关键的一条：<b>被跳过的分支不能把汇合点卡死</b>。
     *
     * <p>没有死分支追踪的话，汇合点会一直等 B 的入边 ⇒ C 永远不执行 ⇒ 流程静默停住。
     */
    private static void caseConditionalJoinSkipsDeadBranch() {
        check("条件汇合：分支被跳过时汇合照样放行（不死等）", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A">
                      <conditionExpression>${a} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B">
                      <conditionExpression>${b} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            RunProgress p = runWithVars(xml, Map.of("a", "yes", "b", "no"));
            eq("只执行 A 和 C 两个任务", 2, p.all().size());
            checkTrue("B（条件不成立的分支）没执行", p.all().stream()
                    .noneMatch(np -> np.nodeId() == Math.abs((long) "B".hashCode())));
            checkTrue("C 执行了（汇合点没被死等卡住）", p.all().stream()
                    .anyMatch(np -> np.nodeId() == Math.abs((long) "C".hashCode())));
        });
    }

    /** 一条都不成立时走 default（与异或同一口径）。 */
    private static void caseConditionalDefaultFallback() {
        check("条件分叉：都不成立时走 default 边", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND" default="F3"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A">
                      <conditionExpression>${a} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="E"/>
                    """);
            RunProgress p = runWithVars(xml, Map.of("a", "no"));
            eq("只执行 1 个任务", 1, p.all().size());
            checkTrue("执行的是 default 分支 B", p.all().stream()
                    .anyMatch(np -> np.nodeId() == Math.abs((long) "B".hashCode())));
        });
    }

    /**
     * 死分支要能<b>穿过嵌套的网关</b>传下去。
     *
     * <p>异或只走 A ⇒ B 支线整条不执行 ⇒ B 支线里那个条件分叉也不会跑
     * ⇒ 它下面的分支也不该跑 ⇒ 汇合点不能等它们。
     */
    private static void caseConditionalInsideSkippedBranch() {
        check("死分支穿过嵌套条件分叉：整条支线不执行，汇合不死等", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="XOR" default="F2"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="COND2"/>
                    <task id="B2" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="XOR"/>
                    <sequenceFlow id="F2" sourceRef="XOR" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="XOR" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="COND2"/>
                    <sequenceFlow id="F6" sourceRef="COND2" targetRef="B2">
                      <conditionExpression>${b2} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F7" sourceRef="B2" targetRef="JOIN"/>
                    <sequenceFlow id="F8" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F9" sourceRef="C" targetRef="E"/>
                    """);
            // 异或走 default（A）；B 支线整条被跳过
            RunProgress p = runWithVars(xml, Map.of("b2", "yes"));
            eq("只执行 A 和 C", 2, p.all().size());
            checkTrue("B 没执行", p.all().stream()
                    .noneMatch(np -> np.nodeId() == Math.abs((long) "B".hashCode())));
            checkTrue("嵌套支线里的 B2 也没执行", p.all().stream()
                    .noneMatch(np -> np.nodeId() == Math.abs((long) "B2".hashCode())));
            checkTrue("C 执行了（汇合没死等）", p.all().stream()
                    .anyMatch(np -> np.nodeId() == Math.abs((long) "C".hashCode())));
        });
    }

    /** 条件网关在干跑里不分叉：条件读的是运行期变量，干跑值未知。 */
    private static void caseConditionalDryRunAllReachable() {
        check("干跑：条件网关的分支都算可达（不假报 PF-03）", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A">
                      <conditionExpression>${a} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B">
                      <conditionExpression>${b} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            GraphRunner dry = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L, 102L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { }).withDryRun(true);
            dry.run(g, new SimulatedBus());
            checkTrue("干跑 A 可达", dry.activatedKeys().contains("A"));
            checkTrue("干跑 B 可达", dry.activatedKeys().contains("B"));
            checkTrue("干跑 C 可达", dry.activatedKeys().contains("C"));
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
            java.util.List<String> logs = new java.util.ArrayList<>();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L, 102L)),
                    Map.of("Transfer", "TRANSFER"),
                    logs::add)
                    .withPauseChecker(() -> calls.incrementAndGet() > 1);

            RunProgress p = runner.run(g, new SimulatedBus());

            eq("只执行了 1 个节点", 1, p.all().size());
            eq("标记为已暂停", true, runner.runPaused());
            eq("停在第二个任务节点 B（A 已执行，B 还没开始）", "B", runner.pausedAtKey());
            eq("日志点名待执行节点",
                    "[暂停] 收到暂停请求，在节点边界停下，待执行节点：B",
                    logs.stream().filter(s -> s.startsWith("[暂停]")).findFirst().orElse(""));
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
     * 圈次口径是**节点级**而非图级：图里有环时，环外的前置/收尾 TASK 节点不得被写成第 1 圈。
     *
     * <p>根因（R13-06 遗留）：旧口径 {@code graphHasCycle ? visit : 0} 用图级有环一刀切，
     * 环外的节点也拿到 visit=1 ⇒ source_loop_index/圈次行写错（run 1001345 的 Q3 脏数据）。
     */
    private static void caseLoopRoundScoping() {
        check("圈次只在环上的节点记，环外节点为 0", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="B" name="Transfer"/>
                    <task id="A" name="Transfer"/>
                    <exclusiveGateway id="AGAIN" default="F4"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="B"/>
                    <sequenceFlow id="F2" sourceRef="B" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="A" targetRef="AGAIN"/>
                    <sequenceFlow id="F4" sourceRef="AGAIN" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="AGAIN" targetRef="A">
                      <conditionExpression>1 == 1</conditionExpression>
                    </sequenceFlow>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            Map<String, List<Integer>> rounds = new HashMap<>();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L, 102L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withMaxVisits(3)
                    .withRoundRecorder((key, round, st, ec, s1, e1) ->
                            rounds.computeIfAbsent(key, k -> new ArrayList<>()).add(round));
            runner.run(g, new SimulatedBus());
            eq("环上节点 A 记 3 圈", "[1, 2, 3]",
                    String.valueOf(rounds.getOrDefault("A", List.of())));
            eq("环外节点 B 不记圈", true, !rounds.containsKey("B"));
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
     * `__sleep`：在这里停多少秒 —— 真的等，但不碰设备。
     *
     * <p>用 `Sleeper.recording` 记录"睡了多久"并断言总量，
     * 而不是只断言"跑得快"：后者会把"压根没睡"也判成通过。
     */
    private static void caseSleepNode() {
        check("内置节点：__sleep 按秒等待且不碰设备", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="SL" name="延时" protocolName="__sleep"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SL"/>
                    <sequenceFlow id="F2" sourceRef="SL" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            List<Long> slept = new ArrayList<>();
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(slept))
                    .withBuiltinParams(Map.of("SL", Map.of("sec", "2")));
            RunProgress p = runner.run(g, bus);
            eq("延时节点完成", 1, p.count(NodeStatus.COMPLETED));
            eq("累计睡眠 2000ms", 2000L, slept.stream().mapToLong(Long::longValue).sum());
            eq("没有产生任何设备命令", 0, bus.callLog().size());
        });
    }

    /**
     * `__wait`：等某个变量变成某个值才算通过。
     *
     * <p>变量的变化来自执行体之外，所以每轮都要读一次外部刷新源 ——
     * 这条用例钉住"它在轮询、而且是外部值让它通过的"（只读内存表永远等不到）。
     */
    private static void caseWaitSatisfied() {
        check("内置节点：__wait 等变量变值（轮询外部源）", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等待就绪" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            java.util.concurrent.atomic.AtomicInteger polls =
                    new java.util.concurrent.atomic.AtomicInteger();
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    // 前两轮还是 0，第三轮变成 1 —— 模拟"运维在库里改了变量"
                    .withVariableRefresher(() -> Map.of("ready",
                            polls.incrementAndGet() >= 3 ? "1" : "0"))
                    .withBuiltinParams(Map.of("W", Map.of(
                            "var", "ready", "value", "1", "timeout", "10", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("等待节点完成", 1, p.count(NodeStatus.COMPLETED));
            eq("轮询到第 3 次才通过", 3, polls.get());
            eq("没有产生任何设备命令", 0, bus.callLog().size());
        });
    }

    /**
     * `__wait` 超时：转人工停下，**不带错值往下走**（后面可能拿它做分支）。
     *
     * <p>计时用"已经睡掉的量"，所以这条用例在 recording Sleeper 下必然终止。
     */
    private static void caseWaitTimeout() {
        check("内置节点：__wait 超时转人工，不驱动下游", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等待就绪" protocolName="__wait"/>
                    <task id="T" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="T"/>
                    <sequenceFlow id="F3" sourceRef="T" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withVariableRefresher(() -> Map.of("ready", "0"))
                    .withBuiltinParams(Map.of("W", Map.of(
                            "var", "ready", "value", "1", "timeout", "2", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("等待节点转人工", 1, p.count(NodeStatus.MANUAL));
            eq("超时后不再驱动下游设备", 0, bus.callLog().size());
        });
    }

    /**
     * `__wait` 的**外部刷新不能盖掉本图刚写的值**（2026-11-06 评审返工②）。
     *
     * <p>复现路径：实验启动时就带了 `ready=0`，前面 `__setVar ready=1`，
     * 后面 `__wait ready == 1`。`__setVar` 只改内存、不回写 `run.input.vars`，
     * 刷新若整表 `putAll`，第一次轮询就把 1 盖回库里的 0 → 一路等到超时。
     *
     * <p>钉住的语义：刷新只补**本图没写过的键**。
     */
    private static void caseWaitNotClobberedByRefresh() {
        check("内置节点：__wait 的外部刷新不覆盖本图 __setVar 写的值", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="SET" name="置就绪" protocolName="__setVar"/>
                    <task id="W" name="等待就绪" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SET"/>
                    <sequenceFlow id="F2" sourceRef="SET" targetRef="W"/>
                    <sequenceFlow id="F3" sourceRef="W" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    // 库里一直还是启动时那个 ready=0（本图赋值没回写库）
                    .withVariables(Map.of("ready", "0"))
                    .withVariableRefresher(() -> Map.of("ready", "0"))
                    .withBuiltinParams(Map.of(
                            "SET", Map.of("var", "ready", "value", "1"),
                            "W", Map.of("var", "ready", "value", "1",
                                    "timeout", "10", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("等待节点没有转人工（被盖回就会超时）", 0, p.count(NodeStatus.MANUAL));
            eq("赋值与等待两个内置节点都完成", 2, p.count(NodeStatus.COMPLETED));
            eq("没有产生任何设备命令", 0, bus.callLog().size());
        });
    }

    /**
     * `__wait consume=true`：**复位标志 → 再等下一次上报**这条链走得通。
     *
     * <p>背景（2026-11-14 高通量核酸提取工作站线）：设备上报的"等待交互"是
     * **电平**（一直是 1），上位机要等的是**一次新的上报**（边）。
     *
     * <p>今天走不通的原因：{@code locallyWrittenVars} 是 run 生命周期内**只增不减**的集合 ——
     * 图里用 {@code __setVar} 复位一次，这个键就**永久**被挡在外部刷新之外 ⇒
     * 设备再报同一个值，{@code __wait} 永远等不到。
     *
     * <p>消费语义解决它：等待成功即把键**移交给外部拥有** ⇒
     * 本图之后复位它不再挡刷新，设备的下一次上报照样进得来。
     *
     * <p>钉住的语义：设备第 2 轮上报 1 → 图里复位成 0 → 设备第 6 轮再上报 1 ⇒ 第二次等待通过。
     */
    private static void caseWaitConsumeChain() {
        check("内置节点：__wait consume=true —— 复位标志后还能等到下一次上报", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W1" name="等第一次" protocolName="__wait"/>
                    <task id="R" name="复位" protocolName="__setVar"/>
                    <task id="W2" name="等第二次" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W1"/>
                    <sequenceFlow id="F2" sourceRef="W1" targetRef="R"/>
                    <sequenceFlow id="F3" sourceRef="R" targetRef="W2"/>
                    <sequenceFlow id="F4" sourceRef="W2" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            java.util.concurrent.atomic.AtomicInteger polls =
                    new java.util.concurrent.atomic.AtomicInteger();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    // 设备行为：第 2 轮上报 1；之后外部回到 0；第 6 轮再上报 1。
                    .withVariableRefresher(() -> {
                        int n = polls.incrementAndGet();
                        return Map.of("ext", (n == 2 || n >= 6) ? "1" : "0");
                    })
                    .withBuiltinParams(Map.of(
                            "W1", Map.of("var", "ext", "value", "1", "consume", "true",
                                    "timeout", "30", "interval", "1"),
                            "R", Map.of("var", "ext", "value", "0"),
                            "W2", Map.of("var", "ext", "value", "1", "consume", "true",
                                    "timeout", "30", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("两次等待都通过（没有转人工）", 0, p.count(NodeStatus.MANUAL));
            eq("三个内置节点都完成", 3, p.count(NodeStatus.COMPLETED));
            eq("第二次上报在第 6 轮才等到", 6, polls.get());
        });
    }

    /**
     * `__wait consume=true`：**陈旧值不算新事件** —— 第二次等同一个标志不能立刻通过。
     *
     * <p>外部一直保持 1（设备没复位，也没重新上报）⇒ 第二次等待必须**等到超时**，
     * 而不是拿这条旧上报当新事件（那会让机械臂白跑一趟）。
     */
    private static void caseWaitConsumeRejectsStale() {
        check("内置节点：__wait consume=true —— 陈旧值不算新事件", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W1" name="等第一次" protocolName="__wait"/>
                    <task id="W2" name="等第二次" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W1"/>
                    <sequenceFlow id="F2" sourceRef="W1" targetRef="W2"/>
                    <sequenceFlow id="F3" sourceRef="W2" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    // 外部一直是 1：从没复位过 ⇒ 第二次没有"新上报"
                    .withVariableRefresher(() -> Map.of("ext", "1"))
                    .withBuiltinParams(Map.of(
                            "W1", Map.of("var", "ext", "value", "1", "consume", "true",
                                    "timeout", "30", "interval", "1"),
                            "W2", Map.of("var", "ext", "value", "1", "consume", "true",
                                    "timeout", "5", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("第一次等待通过", 1, p.count(NodeStatus.COMPLETED));
            eq("第二次等待因陈旧值转人工（不能把旧上报当新事件）",
                    1, p.count(NodeStatus.MANUAL));
        });
    }

    /**
     * 对照组：**不带** `consume` 时，陈旧值会立刻通过。
     *
     * <p>这条用例存在的意义是把"consume 到底改了什么"钉死：
     * 两次等同一个标志、外部不变 ⇒ 不消费就**第二次立刻通过**（把旧上报当新事件）。
     */
    private static void caseWaitWithoutConsumePassesStale() {
        check("内置节点：__wait 不带 consume —— 陈旧值会立刻通过（consume 修的就是这个）", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W1" name="等第一次" protocolName="__wait"/>
                    <task id="W2" name="等第二次" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W1"/>
                    <sequenceFlow id="F2" sourceRef="W1" targetRef="W2"/>
                    <sequenceFlow id="F3" sourceRef="W2" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withVariableRefresher(() -> Map.of("ext", "1"))
                    .withBuiltinParams(Map.of(
                            "W1", Map.of("var", "ext", "value", "1",
                                    "timeout", "10", "interval", "1"),
                            "W2", Map.of("var", "ext", "value", "1",
                                    "timeout", "10", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("两次都立刻通过（旧语义：电平，不是边）", 2, p.count(NodeStatus.COMPLETED));
            eq("没有转人工", 0, p.count(NodeStatus.MANUAL));
        });
    }

    /**
     * `__waitAny`：等**一组**变量里**任意一个**变成目标值；并把命中的变量名交给下游。
     *
     * <p>为什么需要：多台设备各自独立上报时，谁先来是不确定的 ——
     * "等 4 台提取仪里任意一台上报【等待交互】"用 {@code __wait} 表达不了。
     * 这是"事件驱动"落地前的**最小可用近似**：用状态位（电平）代替事件流。
     */
    private static void caseWaitAnyHits() {
        check("内置节点：__waitAny —— 任意一个变量命中即通过，并记下命中的是哪一个", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等任一上报" protocolName="__waitAny"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    // 4 台设备，ext3 先报
                    .withVariableRefresher(() -> Map.of(
                            "ext1", "0", "ext2", "0", "ext3", "1", "ext4", "0"))
                    .withBuiltinParams(Map.of("W", Map.of(
                            "vars", "ext1,ext2,ext3,ext4", "value", "1",
                            "into", "which", "timeout", "10", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("等待节点通过", 1, p.count(NodeStatus.COMPLETED));
            eq("没有转人工", 0, p.count(NodeStatus.MANUAL));
            eq("命中的变量名写进了 into（下游才知道是哪台设备）",
                    "ext3", runner.variableValue("which"));
        });
    }

    /**
     * `__waitAny` 超时：转人工停下，**不带错值往下走**。
     */
    private static void caseWaitAnyTimeout() {
        check("内置节点：__waitAny 超时转人工，不驱动下游", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等任一上报" protocolName="__waitAny"/>
                    <task id="T" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="T"/>
                    <sequenceFlow id="F3" sourceRef="T" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withVariableRefresher(() -> Map.of("ext1", "0", "ext2", "0"))
                    .withBuiltinParams(Map.of("W", Map.of(
                            "vars", "ext1,ext2", "value", "1",
                            "timeout", "3", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            eq("等待节点转人工", 1, p.count(NodeStatus.MANUAL));
            eq("超时后不驱动下游设备", 0, bus.callLog().size());
        });
    }

    /**
     * 内置节点的能力判定必须认 `protocolName` 的 `__` 前缀（2026-11-06 评审返工③）。
     *
     * <p>设计器拖出来的内置节点显示名是中文（「延时」「等待变量」），
     * 只看显示名关键词一条都匹配不上 ⇒ 能力被解析成 `UNKNOWN`、候选设备为空。
     * 这里同时钉住两件事：解析器给 `Kind.BUILTIN`，能力解析给 `LOGIC`。
     */
    private static void caseBuiltinProtocolResolvesToLogic() {
        check("内置节点：__ 前缀 → BUILTIN / 能力 LOGIC（不看显示名）", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="SL" name="延时" protocolName="__sleep"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SL"/>
                    <sequenceFlow id="F2" sourceRef="SL" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            eq("解析器把 __sleep 归为 BUILTIN",
                    WorkflowGraphParser.Kind.BUILTIN, g.nodes().get("SL").kind());
            eq("__sleep 不需要设备（LOGIC）",
                    RealWorkflowRunner.LOGIC,
                    RealWorkflowRunner.resolveCapability(
                            "延时", "__sleep", null, null, Map.of(), Map.of()).capability());
            eq("__wait 同样不需要设备（LOGIC）",
                    RealWorkflowRunner.LOGIC,
                    RealWorkflowRunner.resolveCapability(
                            "等待变量", "__wait", null, null, Map.of(), Map.of()).capability());
        });
    }

    /**
     * 干跑模式：内置节点**直通**，不执行、不取值（2026-11-06 评审③相邻缝隙）。
     *
     * <p>复现路径（保存前预检的干跑）：`GraphRunner` 没注入 `builtinParams`，
     * `__wait` 在空变量下必然等到超时、`__sleep` 缺参会直接抛异常 ——
     * 两者都会让整图 `break`，于是**下游真实设备任务**被判 `PF-03`「永远走不到」。
     *
     * <p>钉住的语义：`withDryRun(true)` 时内置节点只当一步走过去，下游照常可达。
     */
    private static void caseBuiltinDryRunNoOp() {
        check("干跑：内置节点直通，不执行也不拦住下游", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等待变量" protocolName="__wait"/>
                    <task id="SL" name="延时" protocolName="__sleep"/>
                    <task id="T" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="SL"/>
                    <sequenceFlow id="F3" sourceRef="SL" targetRef="T"/>
                    <sequenceFlow id="F4" sourceRef="T" targetRef="E"/>
                    """);
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            List<Long> slept = new ArrayList<>();
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withDryRun(true)                        // 干跑：内置节点不真执行
                    .withSleeper(Sleeper.recording(slept)); // 真执行 __sleep 会留下睡眠记录
            RunProgress p = runner.run(g, bus);
            eq("内置节点没有被判转人工（缺参会抛异常）", 0, p.count(NodeStatus.MANUAL));
            eq("干跑没有真的睡（__sleep 未执行）", 0L,
                    slept.stream().mapToLong(Long::longValue).sum());
            checkTrue("下游设备任务被走到了（不再假 PF-03）",
                    runner.activatedKeys().contains("T"));
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
        return runWithVars(xml, Map.of());
    }

    /** 带运行期变量的执行（条件网关的表达式要读变量）。 */
    private static RunProgress runWithVars(String xml, Map<String, String> vars) {
        Graph g = WorkflowGraphParser.parse(xml);
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        GraphRunner runner = new GraphRunner(sm,
                Map.of("TRANSFER", List.of(101L, 102L)),
                Map.of("Transfer", "TRANSFER"),
                s -> { })
                .withVariables(vars);
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
