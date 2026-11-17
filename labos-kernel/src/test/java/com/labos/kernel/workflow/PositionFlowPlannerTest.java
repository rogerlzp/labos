package com.labos.kernel.workflow;

import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PositionFlowPlanner.BindingPoint;
import com.labos.kernel.workflow.PositionFlowPlanner.Plan;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行期搬运插入的验收（纯逻辑，不碰库、不碰设备）。
 *
 * <p>为什么这条要有测试：搬错位置是**最贵**的一类 bug（板子掉、撞机、污染），
 * 而"跨设备才插 / 同设备不插 / 已有显式搬运不重复插"三条判据里错任何一条，
 * 现场表现都只是"板子没到位"，没有任何报错指向规划器。
 */
public final class PositionFlowPlannerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("跨设备相邻点位 → 插入一段机械臂搬运", PositionFlowPlannerTest::crossDeviceInserts);
        run("同设备两工位 → 不插（设备自己搬）", PositionFlowPlannerTest::sameDeviceSkips);
        run("已有显式 Transfer 节点 → 不插（存量写法）", PositionFlowPlannerTest::explicitTransferSkips);
        run("参数口径：source/target 用设备码，position 用点位 spec", PositionFlowPlannerTest::paramsCarryOver);
        run("幂等：对规划后的图再规划不会叠第二段", PositionFlowPlannerTest::idempotent);
        run("装配闭环：规划后的图经 GraphRunner 真跑到搬运节点", PositionFlowPlannerTest::executesTransport);
        // 输出格式必须与 scripts/test-all.sh 的解析器一致（通过 N, 失败 M），
        // 否则脚本会把"全绿"判成"无法解析输出"（实测踩过）。
        System.out.printf("%n  PositionFlowPlannerTest  通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    private static void crossDeviceInserts() {
        Graph g = parse(twoStep());

        Plan plan = PositionFlowPlanner.plan(g, bindings(11, 22), 9001, Map.of());

        eq("插入段数", 1, plan.transports().size());
        checkTrue("合成节点在图上", plan.graph().nodes().containsKey("__move__f2"));
        eq("合成节点方法", "Transfer", plan.graph().nodes().get("__move__f2").method());
        eq("合成节点能力", "TRANSFER", plan.graph().nodes().get("__move__f2").capability());
        // A→T 保留原边 id；T→B 是新边
        checkTrue("A 的出边指向搬运", plan.graph().out("A").stream()
                .anyMatch(e -> "__move__f2".equals(e.to())));
        checkTrue("搬运的出边指向 B", plan.graph().out("__move__f2").stream()
                .anyMatch(e -> "B".equals(e.to())));
        eq("原边不再直连", 0, plan.graph().out("A").stream()
                .filter(e -> "B".equals(e.to())).count());
    }

    private static void sameDeviceSkips() {
        Graph g = parse(twoStep());

        // 两个点位同属设备 200（如提取仪操作位/交互位）
        Plan plan = PositionFlowPlanner.plan(g, bindings(11, 11), 9001, Map.of());

        eq("同设备不插", 0, plan.transports().size());
        checkTrue("没有合成节点", !plan.graph().nodes().containsKey("__move__f2"));
    }

    private static void explicitTransferSkips() {
        String xml = twoStep().replace(
                "<bpmn:task id=\"B\"",
                "<bpmn:task id=\"T\" name=\"ARM_TRANSFER\" protocolName=\"Transfer\" resource=\"200\"/>\n"
                        + "  <bpmn:task id=\"B\"");
        Graph g = parse(xml);

        Plan plan = PositionFlowPlanner.plan(g, bindings(11, 22), 9001, Map.of());

        eq("存量写法不插", 0, plan.transports().size());
        eq("节点数不变", g.nodes().size(), plan.graph().nodes().size());
    }

    @SuppressWarnings("unchecked")
    private static void paramsCarryOver() {
        Graph g = parse(twoStep());
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        existing.put("A", Map.of("barcode", "PLT-0001", "volume", 100));

        Plan plan = PositionFlowPlanner.plan(g, bindings(11, 22), 9001, existing);
        Map<String, Object> p = plan.paramsByNodeKey().get("__move__f2");

        eq("source 用设备码", "DISP1", p.get("source"));
        eq("target 用设备码", "DISP3", p.get("target"));
        eq("sourcePosition 用点位 spec", "PLATE", p.get("sourcePosition"));
        eq("targetPosition 用点位 spec", "S01", p.get("targetPosition"));
        eq("barcode 从相邻节点顺过来", "PLT-0001", p.get("barcode"));
        eq("合成节点绑机械臂资源", 9001L,
                plan.graph().nodes().get("__move__f2").resourceId());
    }

    private static void idempotent() {
        Graph g = parse(twoStep());
        Plan once = PositionFlowPlanner.plan(g, bindings(11, 22), 9001, Map.of());
        Plan twice = PositionFlowPlanner.plan(once.graph(), bindings(11, 22), 9001, Map.of());

        eq("第二次不再插", 0, twice.transports().size());
        eq("节点数不增长", once.graph().nodes().size(), twice.graph().nodes().size());
    }

    /**
     * 装配闭环：plan → GraphRunner 真跑一遍。
     *
     * <p>只测 planner 的纯函数还不够 —— 合成节点必须有 id、必须 pin 到机械臂、
     * 参数必须能被状态机取到；这三件事任一没接上，图上是"有搬运节点"，
     * 执行时却是一台随机设备、或者干脆没下发。
     */
    private static void executesTransport() {
        Graph g = parse(twoStep());
        Map<String, Long> nodeIdByKey = new LinkedHashMap<>();
        nodeIdByKey.put("A", 1L);
        nodeIdByKey.put("B", 2L);
        Map<Long, Map<String, Object>> paramsByNode = new LinkedHashMap<>();
        paramsByNode.put(1L, Map.of("barcode", "PLT-0001"));
        Graph planned = WorkflowService.applyTransportPlan(g, bindings(11, 22), 9001L,
                nodeIdByKey, paramsByNode, java.util.Set.of());

        checkTrue("合成节点进入 id 表", nodeIdByKey.containsKey("__move__f2"));
        // 机械臂用 resource_node.id 写进节点的 resource（执行器再映射到实例）
        eq("合成节点绑机械臂资源", 9001L,
                planned.nodes().get("__move__f2").resourceId());

        java.util.List<String> seen = new java.util.ArrayList<>();
        com.labos.kernel.engine.StepExecutor recorder = ctx -> seen.add(
                ctx.nodeKey() + "|" + String.join(",", ctx.effectiveSteps())
                        + "|" + ctx.params());
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new java.util.ArrayList<>()), Clock.systemUTC())
                .withParamPlanner(id -> paramsByNode.getOrDefault(id, Map.of()));
        GraphRunner runner = new GraphRunner(sm,
                Map.of("TRANSFER", List.of(9001L), "WASH", List.of(200L, 300L)),
                Map.of("Transfer", "TRANSFER", "Run", "WASH"),
                // A/B 显式给能力：夹具里的 protocolName 是 "Run"，真实库里靠
                // protocol→capability 表解析；纯逻辑测试直接给，避免依赖库。
                Map.of("A", "WASH", "B", "WASH"),
                // ⚠ 必须传 nodeIdByKey：5 参构造会把它置成空 map ⇒ 合成节点拿不到
                // 自己的 id ⇒ 参数规划器按 id 取不到 Transfer 参数（实测踩到）。
                nodeIdByKey,
                s -> { });
        com.labos.kernel.engine.RunProgress progress = runner.run(planned, recorder);
        String statuses = progress.all().stream()
                .map(np -> np.nodeId() + ":" + np.status()).toList().toString();

        checkTrue("搬运节点被执行（不是只在图上） 执行=" + seen + " 状态=" + statuses,
                seen.stream().anyMatch(s -> s.startsWith("__move__f2|Transfer")));
        checkTrue("搬运参数带上了源/目标 执行=" + seen + " paramsByNode=" + paramsByNode
                        + " nodeIdByKey=" + nodeIdByKey, seen.stream()
                .anyMatch(s -> s.contains("source=DISP1") && s.contains("target=DISP3")));
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy(null, RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, java.time.Duration.ZERO, 20, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, java.time.Duration.ZERO, 99, true))));
    }

    // ---------------------------------------------------------------- 夹具

    private static String twoStep() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="P1">
                    <bpmn:startEvent id="S"/>
                    <bpmn:task id="A" name="Run" protocolName="Run" resource="200"/>
                    <bpmn:task id="B" name="Run" protocolName="Run" resource="300"/>
                    <bpmn:endEvent id="E"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="A"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="A" targetRef="B"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="B" targetRef="E"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    /** A 绑 DISP1-PLATE（设备 200）、B 绑 DISP3 的槽位 S01（设备 300）。 */
    private static Map<String, BindingPoint> bindings(long aDevice, long bDevice) {
        Map<String, BindingPoint> m = new LinkedHashMap<>();
        m.put("A", new BindingPoint("A", 11, aDevice, "DISP1-PLATE", "PLATE", "DISP1", true));
        m.put("B", new BindingPoint("B", 22, bDevice, "DISP3-S01", "S01", "DISP3", true));
        return m;
    }

    private static Graph parse(String xml) {
        return WorkflowGraphParser.parse(xml);
    }

    // ---------------------------------------------------------------- 断言工具

    private static void run(String name, Runnable body) {
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

    private PositionFlowPlannerTest() {
    }
}
