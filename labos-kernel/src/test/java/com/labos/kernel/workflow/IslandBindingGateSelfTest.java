package com.labos.kernel.workflow;

import com.labos.kernel.workflow.WorkflowGraphParser.GNode;
import com.labos.kernel.workflow.WorkflowGraphParser.Kind;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 岛边界与绑定有效性门禁（{@link IslandBindingGate}）的**纯逻辑**自测。
 *
 * <p>为什么能不连库测：判定逻辑是纯函数 —— 给一份**合成的"当前资源树"快照**
 * （Context），就能覆盖四条判定（PF-10/11/12/13）。⇒ 进纯逻辑轨，不占库。
 *
 * <p>覆盖：
 * <ol>
 *   <li>绑定在册且在岛内 ⇒ 无违规（这是"正常"的样子，别误报）；</li>
 *   <li>绑定在册但**不在岛内** ⇒ PF-12（执行期会被拒绝）；</li>
 *   <li>绑定存的是**设备实例号** ⇒ PF-11（口径不一致、绑定当前不生效）；</li>
 *   <li>绑定两空间都找不到 ⇒ PF-10（设备已失效）；</li>
 *   <li>岛内没有该能力的设备 ⇒ PF-13（执行时该节点转人工）；</li>
 *   <li>逻辑节点不参与（不占设备）。</li>
 * </ol>
 */
public final class IslandBindingGateSelfTest {

    private static int pass;
    private static int fail;

    /** 合成的岛：100；岛内设备：资源 200（实例 20，TRANSFER）。 */
    private static final long ISLAND = 100L;

    public static void main(String[] args) {
        System.out.println("════ 岛边界绑定门禁 · 纯逻辑自测 ════");
        System.out.println();

        bindingOnIslandIsClean();
        bindingOutsideIslandIsPf12();
        bindingAsInstanceIdIsPf11();
        danglingBindingIsPf10();
        noDeviceInIslandIsPf13();
        logicNodeIsExempt();
        noIslandMeansNoIslandRules();
        onlyErrorLevelBlocks();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 在册且在岛内 ⇒ 无违规。 */
    private static void bindingOnIslandIsClean() {
        check("绑定在册且在岛内：无违规", () -> {
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(task("N1", "Transfer", 200L)));
            eq("违规条数", 0, vs.size());
        });
    }

    /** 绑定的设备在册但不在岛内 ⇒ PF-12（顺带 PF-13：岛内也没有该能力）。 */
    private static void bindingOutsideIslandIsPf12() {
        check("绑定在岛外：报 PF-12（ERROR）＋ PF-13（岛内无该能力）", () -> {
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(task("N2", "Incubate", 300L)));
            // 两条都是真的：绑定越岛（PF-12）+ 岛内没有 INCUBATE 设备（PF-13）
            Set<String> codes = new LinkedHashSet<>();
            for (IslandBindingGate.Violation v : vs) {
                codes.add(v.code());
            }
            eq("违规条数", 2, vs.size());
            eq("含 PF-12 与 PF-13", "[PF-12, PF-13]",
                    codes.contains("PF-12") && codes.contains("PF-13") ? "[PF-12, PF-13]" : codes.toString());
            eq("节点", "N2", vs.get(0).nodeKey());
        });
    }

    /** 绑定存的是设备实例号 ⇒ PF-11（WARN）。 */
    private static void bindingAsInstanceIdIsPf11() {
        check("绑定存成设备实例号：报 PF-11（WARN）", () -> {
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(task("N3", "Transfer", 20L)));
            eq("违规条数", 1, vs.size());
            eq("码", "PF-11", vs.get(0).code());
            eq("级别", PreflightChecker.Level.WARN, vs.get(0).level());
        });
    }

    /** 绑定两空间都找不到 ⇒ PF-10（ERROR）。 */
    private static void danglingBindingIsPf10() {
        check("悬空绑定：报 PF-10（ERROR）", () -> {
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(task("N4", "Transfer", 999L)));
            eq("违规条数", 1, vs.size());
            eq("码", "PF-10", vs.get(0).code());
            eq("级别", PreflightChecker.Level.ERROR, vs.get(0).level());
        });
    }

    /** 岛内没有该能力的设备 ⇒ PF-13。 */
    private static void noDeviceInIslandIsPf13() {
        check("岛内没有该能力：报 PF-13（ERROR）", () -> {
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(task("N5", "Incubate", null)));
            eq("违规条数", 1, vs.size());
            eq("码", "PF-13", vs.get(0).code());
            eq("级别", PreflightChecker.Level.ERROR, vs.get(0).level());
        });
    }

    /** 逻辑节点不占设备 ⇒ 不参与这四条判定。 */
    private static void logicNodeIsExempt() {
        check("逻辑节点不参与（不占设备）", () -> {
            GNode logic = new GNode("N6", "变量", "task", Kind.BUILTIN, null, "__setVar", null);
            List<IslandBindingGate.Violation> vs =
                    IslandBindingGate.violations(ctx(), List.of(logic));
            eq("违规条数", 0, vs.size());
        });
    }

    /** 没有权威岛的旧流程 ⇒ 不做岛约束（只有绑定有效性判定还生效）。 */
    private static void noIslandMeansNoIslandRules() {
        check("没有权威岛：不套岛规则（PF-12/PF-13 不报）", () -> {
            IslandBindingGate.Context c = ctx();
            IslandBindingGate.Context noIsland = new IslandBindingGate.Context(
                    null, c.resourceToInstance(), c.instanceToResource(),
                    Set.of(), Set.of(), c.capPool(), c.protocolCap(), c.resourceCap());
            List<IslandBindingGate.Violation> vs = IslandBindingGate.violations(noIsland,
                    List.of(task("N2", "Incubate", 300L), task("N5", "Incubate", null)));
            eq("违规条数", 0, vs.size());
        });
    }

    /**
     * 派发只拦**阻塞级**（ERROR）：PF-11（WARN）不拦。
     *
     * <p>为什么这条必须钉死：PF-11 是"绑定存成设备实例号、当前不生效"，流程
     * **照样能跑**（只是跑的不是绑的那台）—— 拿它去拦实验，会把大量本来能跑的
     * 实验误杀（库里 8295 条绑定是这个形态，涉及 988 个流程）。
     */
    private static void onlyErrorLevelBlocks() {
        check("派发只拦 ERROR 级：PF-11（WARN）不拦", () -> {
            List<IslandBindingGate.Violation> warn =
                    IslandBindingGate.violations(ctx(), List.of(task("N3", "Transfer", 20L)));
            eq("PF-11 判为 WARN", 1, warn.size());
            eq("PF-11 级别", PreflightChecker.Level.WARN, warn.get(0).level());
            eq("WARN 不算阻塞", 0, IslandBindingGate.blocking(warn).size());
            List<IslandBindingGate.Violation> err =
                    IslandBindingGate.violations(ctx(), List.of(task("N4", "Transfer", 999L)));
            eq("PF-10 算阻塞", 1, IslandBindingGate.blocking(err).size());
            List<IslandBindingGate.Violation> out =
                    IslandBindingGate.violations(ctx(), List.of(task("N2", "Incubate", 300L)));
            eq("PF-12/PF-13 都是 ERROR ⇒ 都阻塞", 2, IslandBindingGate.blocking(out).size());
        });
    }

    // ---------------------------------------------------------------- 工具

    /** 合成的"当前资源树"快照。 */
    private static IslandBindingGate.Context ctx() {
        Map<Long, Long> r2i = new LinkedHashMap<>();
        r2i.put(200L, 20L);     // 资源 200（岛内，TRANSFER）→ 实例 20
        r2i.put(300L, 30L);     // 资源 300（岛外，INCUBATE）→ 实例 30
        Map<Long, Long> i2r = new LinkedHashMap<>();
        i2r.put(20L, 200L);
        i2r.put(30L, 300L);
        Set<Long> islandInstances = new LinkedHashSet<>();
        islandInstances.add(20L);                    // 岛 100 内只有实例 20
        Set<Long> islandResources = new LinkedHashSet<>();
        islandResources.add(200L);
        Map<String, List<Long>> capPool = new LinkedHashMap<>();
        capPool.put("TRANSFER", List.of(200L));
        capPool.put("INCUBATE", List.of(300L));      // 300 不在岛内
        Map<String, String> protocolCap = new LinkedHashMap<>();
        protocolCap.put("Transfer", "TRANSFER");
        protocolCap.put("Incubate", "INCUBATE");
        Map<Long, String> resourceCap = new LinkedHashMap<>();
        resourceCap.put(200L, "TRANSFER");
        resourceCap.put(300L, "INCUBATE");
        return new IslandBindingGate.Context(ISLAND, r2i, i2r, islandInstances, islandResources,
                capPool, protocolCap, resourceCap);
    }

    private static GNode task(String key, String method, Long resourceId) {
        return new GNode(key, key, "task", Kind.TASK, null, method, resourceId);
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

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private IslandBindingGateSelfTest() {
    }
}
