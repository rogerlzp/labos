package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.util.List;
import java.util.Map;

/**
 * 保存前预检（{@link PreflightChecker}）的连库自测。
 *
 * <p>为什么要连库：`check(workflowId, xml, ...)` 除了解析 BPMN，还会
 * 1) 查一次 `workflow.name`（查不到就用调用方传进来的名字，不报错）、
 * 2) 批量加载能力池 / 协议能力 / 资源能力缓存。
 * 这三件事都得有真库，所以在纯逻辑轨里测不了。
 *
 * <p>覆盖的是 2026-11-06 评审 ③ 修好后的**两条相邻缝隙**：
 * <ol>
 *   <li><b>纯内置图被误报 PF-07</b>：`tasks` 只收 `Kind.TASK`，
 *       只含 `__sleep` / `__wait` 的流程会被判"没有任务节点"；</li>
 *   <li><b>内置节点在干跑里抛异常 → 下游真实设备任务被误报 PF-03</b>：
 *       干跑用的 `GraphRunner` 没注入 `builtinParams`，
 *       `__sleep` 缺参抛异常、`__wait` 必然等到超时，两者都让整图停下。</li>
 * </ol>
 *
 * <p>探针流程都是内存里拼的 XML，不往库里写任何数据（`workflowId` 用一个
 * 不存在的负数 id，`doCheck` 查不到 name 会沿用调用方传入的名字）。
 */
public final class PreflightDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    /** 不存在的流程 id：name 查不到就用调用方传的，绝不误伤真实数据。 */
    private static final long PHANTOM_WF = -994001L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        System.out.println("════ 保存前预检 · 连库自测 ════");
        System.out.println();

        pureBuiltinGraphHasNoPf07();
        builtinDoesNotBlockDownstream();
        controlDeviceOnlyGraphHasNoPf03();
        gatewayOnlyGraphStillReportsPf07();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 纯内置图（只有 `__wait`）：合法流程，**不许**报 PF-07。
     *
     * <p>修复前：`tasks` 为空 ⇒ `PF-07 流程里没有任务节点`（ERROR，阻塞启动）。
     */
    private static void pureBuiltinGraphHasNoPf07() {
        check("纯内置图不再误报 PF-07", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="W" name="等待变量" protocolName="__wait"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                    <sequenceFlow id="F2" sourceRef="W" targetRef="E"/>
                    """);
            PreflightChecker.Report r = preflight(xml);
            eq("没有 PF-07", 0, codes(r, "PF-07").size());
            eq("内置节点不占设备，整图无阻塞项", 0, r.errors());
        });
    }

    /**
     * 内置节点 + 下游真实设备任务：干跑必须走到下游，**不许**假报 PF-03。
     *
     * <p>修复前：`__sleep` 缺参会抛异常、`__wait` 必然超时，
     * 两者都让干跑 `break`，下游 `T` 被判「永远不会被执行」。
     */
    private static void builtinDoesNotBlockDownstream() {
        check("内置节点不拦住下游：下游不再假报 PF-03", () -> {
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
            PreflightChecker.Report r = preflight(xml);
            eq("下游 T 没有被判走不到", 0, codes(r, "PF-03").size());
            eq("任务数只算设备任务（T）", 1, r.taskCount());
            eq("设备任务 T 可达", 1, r.reachableCount());
        });
    }

    /** 对照：只有设备任务的图，本来就不该有 PF-03（防"为了过 PF-03 把检查改瞎了"）。 */
    private static void controlDeviceOnlyGraphHasNoPf03() {
        check("对照：纯设备任务图无 PF-03", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <task id="T" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="T"/>
                    <sequenceFlow id="F2" sourceRef="T" targetRef="E"/>
                    """);
            PreflightChecker.Report r = preflight(xml);
            eq("没有 PF-03", 0, codes(r, "PF-03").size());
            eq("没有 PF-07", 0, codes(r, "PF-07").size());
        });
    }

    /** 反例：真的一步活都没有（只有网关/事件）时，PF-07 必须还在。 */
    private static void gatewayOnlyGraphStillReportsPf07() {
        check("反例：没有可执行节点仍然报 PF-07", () -> {
            String xml = bpmn("""
                    <startEvent id="S"/>
                    <parallelGateway id="G"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="G"/>
                    <sequenceFlow id="F2" sourceRef="G" targetRef="E"/>
                    """);
            PreflightChecker.Report r = preflight(xml);
            eq("有 PF-07", 1, codes(r, "PF-07").size());
        });
    }

    // ---------------------------------------------------------------- 工具

    private static PreflightChecker.Report preflight(String xml) throws Exception {
        PreflightChecker checker = new PreflightChecker(db, TENANT);
        return checker.check(PHANTOM_WF, xml, Map.of(), "自测探针");
    }

    private static List<String> codes(PreflightChecker.Report r, String code) {
        return r.issues().stream().filter(i -> code.equals(i.code())).map(i -> i.code()).toList();
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

    private static void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            pass++;
            System.out.printf("  ✔ %s%n", name);
        } catch (Throwable e) {
            fail++;
            System.out.printf("  ✘ %s%n     %s%n", name, e.getMessage());
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    /** 用例体允许抛受检异常（预检要连库）。 */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private PreflightDbSelfTest() {
    }
}
