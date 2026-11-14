package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.util.List;

/**
 * 作用域扣留 + 边界闸门 纯逻辑自测（不连库）。
 *
 * <p>锁住"现场暂停"的三条不能退的语义：
 * <ol>
 *   <li><b>覆盖优先级</b>：{@code experiment > island > site} —— 越具体越优先，
 *       这样"这条实验因板位未知停了"不会被一条更宽的站点扣留掩盖掉原因；</li>
 *   <li><b>岛只匹配自己的岛</b>：别岛的扣留不能停到我；未分配岛（0）不匹配任何岛级扣留；</li>
 *   <li><b>停止优先于扣留</b>：人已明确说不要了，就不必再等解扣留。</li>
 * </ol>
 */
public final class RuntimeHoldSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 覆盖优先级 experiment > island > site");
        picks("只有站点扣留：管到所有实验",
                List.of(site("公共气路故障")), 100L, 7L, "site");
        picks("站点 + 岛：岛更具体",
                List.of(site("公共气路故障"), island(7L, "岛内板位未知")), 100L, 7L, "island");
        picks("站点 + 岛 + 实验：实验最具体",
                List.of(site("公共气路故障"), island(7L, "岛内板位未知"),
                        experiment(100L, "板位未知")), 100L, 7L, "experiment");
        picks("只有别的岛的扣留：管不到我",
                List.of(island(8L, "邻岛故障")), 100L, 7L, null);

        section("2. 岛匹配");
        picks("未分配岛（0）不匹配岛级扣留",
                List.of(island(7L, "岛内故障")), 100L, 0L, null);
        picks("别岛的实验级扣留不影响我",
                List.of(experiment(101L, "别的实验")), 100L, 7L, null);

        section("3. 边界闸门");
        gate("无人停、无扣留 -> 放行", false, null, "GO");
        gate("有人请求停止 -> 停", true, null, "STOPPED");
        gate("被扣留 -> 停在边界", false, island(7L, "故障"), "HELD");
        gate("停止优先于扣留（不必等解扣留）", true, island(7L, "故障"), "STOPPED");

        section("4. 暂停类型归一化（流程级 -> 现场扣留）");
        kind("paused_fault -> FAULT", "paused_fault", RuntimeHold.Kind.FAULT);
        kind("paused_unknown -> PLATE_UNKNOWN", "paused_unknown", RuntimeHold.Kind.PLATE_UNKNOWN);
        kind("waiting_human -> HUMAN", "waiting_human", RuntimeHold.Kind.HUMAN);
        kind("manual -> MANUAL", "manual", RuntimeHold.Kind.MANUAL);
        kind("未知 -> HUMAN（保守：按需人工处理）", "???", RuntimeHold.Kind.HUMAN);

        section("4b. R8 步骤 2：双向词表一致性（PauseManager.Kind ↔ RuntimeHold.Kind）");
        for (PauseManager.Kind pk : PauseManager.Kind.values()) {
            RuntimeHold.Kind hk = pk.toHoldKind();
            // 正向：PauseManager.Kind -> RuntimeHold.Kind
            if (hk != null && pk.code().equals(hk.toPauseKindCode())) {
                passed++;
                System.out.printf("  ✓ %-34s -> %s -> %s（round-trip）%n", pk.code(), hk.code(), hk.toPauseKindCode());
            } else {
                failed++;
                System.out.printf("  ✗ %s 双向映射不一致%n", pk.code());
            }
        }
        // 反向：RuntimeHold.Kind -> PauseManager.Kind.of
        for (RuntimeHold.Kind hk : RuntimeHold.Kind.values()) {
            String code = hk.toPauseKindCode();
            PauseManager.Kind pk = PauseManager.Kind.of(code);
            if (pk != null && pk.toHoldKind() == hk) {
                passed++;
                System.out.printf("  ✓ %-34s -> %s -> %s（反向 round-trip）%n", hk.code(), code, pk.code());
            } else {
                failed++;
                System.out.printf("  ✗ %s 反向映射不一致（code=%s -> %s）%n", hk.code(), code, pk);
            }
        }

        section("5. 范围解析");
        scope("site -> SITE", "site", RuntimeHold.Scope.SITE);
        scope("island -> ISLAND", "island", RuntimeHold.Scope.ISLAND);
        scope("缺失 -> ISLAND（实验暂停默认岛级）", null, RuntimeHold.Scope.ISLAND);

        section("6. 安全闸门三态（fail-closed 判定）");
        state("CLEAR -> 放行", RuntimeHold.HoldState.CLEAR, RuntimeHold.HoldState.CLEAR, false);
        state("HELD -> 阻断", RuntimeHold.HoldState.HELD, RuntimeHold.HoldState.HELD, true);
        state("UNKNOWN -> 阻断", RuntimeHold.HoldState.UNKNOWN, RuntimeHold.HoldState.UNKNOWN, true);
        coverage("已读到且无扣留 -> CLEAR（hold 为空）",
                List.of(), 100L, 7L, "CLEAR", false);
        coverage("站点扣留盖到我 -> HELD（hold 非空）",
                List.of(site("公共气路故障")), 100L, 7L, "HELD", true);
        coverage("本岛扣留盖到我 -> HELD",
                List.of(island(7L, "岛内板位未知")), 100L, 7L, "HELD", true);
        coverage("别的岛的扣留 -> CLEAR",
                List.of(island(8L, "邻岛故障")), 100L, 7L, "CLEAR", false);
        coverage("读库失败构造的三态 -> UNKNOWN（阻断）",
                null, 100L, 7L, "UNKNOWN", true);

        section("7. 读库失败 ⇒ UNKNOWN（fail-closed：读不到就阻断，不放行）");
        // 这一节是 V1-1 的核心分界：走的是 RuntimeHold 的真实读库路径（coverageFor /
        // coverageForWorkflow / siteState），不是纯函数。读库失败必须折成 UNKNOWN 阻断。
        // 关键分界：旧实现把「读库失败」和「现场无扣留」都当成放行（fail-open）。
        //
        // 收编后读库经 MyBatis Mapper（全局 standalone DataSource，不再吃实例上的
        // DbConfig），所以「坏库」要在 JVM 级注入：test-all.sh 跑本类时把
        // LABOS_DB_HOST/PORT 指向 127.0.0.1:1（必拒连）。下面这个 broken 实例上的
        // 死连接串只是留作文档，说明意图是「读库一定失败」。
        RuntimeHold broken = new RuntimeHold(
                new DbConfig("jdbc:postgresql://127.0.0.1:1/labdb-probe", "nobody", "nobody"), 1L);
        state("siteState 读库失败 -> UNKNOWN（阻断派发）", broken.siteState(),
                RuntimeHold.HoldState.UNKNOWN, true);
        coverageActual("coverageFor 读库失败 -> UNKNOWN",
                "UNKNOWN", true, broken.coverageFor(100L, 7L));
        coverageActual("coverageForWorkflow 读库失败 -> UNKNOWN",
                "UNKNOWN", true, broken.coverageForWorkflow(12345L));

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static RuntimeHold.Active site(String reason) {
        return new RuntimeHold.Active(RuntimeHold.Scope.SITE.code(), null,
                RuntimeHold.Kind.FAULT.code(), reason);
    }

    private static RuntimeHold.Active island(long islandId, String reason) {
        return new RuntimeHold.Active(RuntimeHold.Scope.ISLAND.code(), islandId,
                RuntimeHold.Kind.PLATE_UNKNOWN.code(), reason);
    }

    private static RuntimeHold.Active experiment(long expId, String reason) {
        return new RuntimeHold.Active(RuntimeHold.Scope.EXPERIMENT.code(), expId,
                RuntimeHold.Kind.PLATE_UNKNOWN.code(), reason);
    }

    private static void picks(String name, List<RuntimeHold.Active> active,
                              long experimentId, long islandId, String expectScope) {
        RuntimeHold.Active got = RuntimeHold.covering(active, experimentId, islandId);
        String gotScope = got == null ? null : got.scope();
        if (expectScope == null ? got == null : expectScope.equals(gotScope)) {
            passed++;
            System.out.printf("  ✓ %-34s %s%n", name, got == null ? "无扣留" : gotScope);
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s，实际 %s%n", name, expectScope, gotScope);
        }
    }

    private static void gate(String name, boolean stopRequested,
                             RuntimeHold.Active covering, String expect) {
        RuntimeHold.Gate got = RuntimeHold.gate(stopRequested, covering);
        if (expect.equals(got.name())) {
            passed++;
            System.out.printf("  ✓ %-34s %s%n", name, got);
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s，实际 %s%n", name, expect, got);
        }
    }

    private static void kind(String name, String code, RuntimeHold.Kind expect) {
        RuntimeHold.Kind got = RuntimeHold.Kind.of(code);
        if (got == expect) {
            passed++;
            System.out.printf("  ✓ %-34s %s%n", name, got.code());
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s，实际 %s%n", name, expect.code(), got.code());
        }
    }

    private static void scope(String name, String code, RuntimeHold.Scope expect) {
        RuntimeHold.Scope got = RuntimeHold.Scope.of(code);
        if (got == expect) {
            passed++;
            System.out.printf("  ✓ %-34s %s%n", name, got.code());
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s，实际 %s%n", name, expect.code(), got.code());
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    /** 三态本身：既要状态名对，也要 blocks() 与预期一致（block 才是调用点真正读的）。 */
    private static void state(String name, RuntimeHold.HoldState got,
                              RuntimeHold.HoldState expect, boolean expectBlocks) {
        if (got == expect && got.blocks() == expectBlocks) {
            passed++;
            System.out.printf("  ✓ %-34s %s(block=%s)%n", name, got, got.blocks());
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s(block=%s)，实际 %s(block=%s)%n",
                    name, expect, expectBlocks, got, got.blocks());
        }
    }

    /**
     * 覆盖判定的纯函数路径。
     *
     * @param active 已读到的生效扣留；{@code null} 表示「读库失败」（应折成 UNKNOWN，不得当成 CLEAR）
     */
    private static void coverage(String name, List<RuntimeHold.Active> active,
                                 long experimentId, long islandId,
                                 String expectState, boolean expectBlocks) {
        RuntimeHold.Coverage got = active == null
                ? RuntimeHold.unknownCoverage()
                : RuntimeHold.coverageOf(active, experimentId, islandId);
        coverageActual(name, expectState, expectBlocks, got);
    }

    /** 覆盖判定的实际结果（模拟 RuntimeHold 读库路径返回的三态，如 coverageFor 读库失败）。 */
    private static void coverageActual(String name, String expectState,
                                       boolean expectBlocks, RuntimeHold.Coverage actual) {
        String gotState = actual.state().name();
        if (expectState.equals(gotState) && actual.blocks() == expectBlocks) {
            passed++;
            System.out.printf("  ✓ %-34s %s(block=%s)%n", name, gotState, actual.blocks());
        } else {
            failed++;
            System.out.printf("  ✗ %-34s 期望 %s(block=%s)，实际 %s(block=%s)%n",
                    name, expectState, expectBlocks, gotState, actual.blocks());
        }
    }

    private RuntimeHoldSelfTest() {
    }
}
