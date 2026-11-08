package com.labos.kernel.workflow;

import java.util.List;
import java.util.Set;

/** N-10 Handoff 门禁纯逻辑自测（ADR-ISLAND-002/004）。 */
public final class HandoffGateSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        List<Long> chain = List.of(1L, 2L, 3L);
        check("完整配置 ⇒ 无缺失", HandoffGate.missing(chain,
                Set.of("1->2", "2->3"), Set.of()).isEmpty(), "missing");
        List<String> m1 = HandoffGate.missing(chain, Set.of("1->2"), Set.of());
        check("缺 2->3 ⇒ 阻断并点名", m1.size() == 1 && m1.get(0).contains("2->3"), m1);
        List<String> m2 = HandoffGate.missing(chain, Set.of("1->2", "2->3"),
                Set.of("1->2"));
        check("运行时确认 UNKNOWN ⇒ 阻断", m2.size() == 1 && m2.get(0).contains("1->2"), m2);
        check("单 Workcell ⇒ 不适用", HandoffGate.missing(List.of(1L),
                Set.of(), Set.of()).isEmpty(), "single");
        check("无关 Workcell 配置不算数", HandoffGate.missing(List.of(1L, 2L),
                Set.of("9->8"), Set.of()).size() == 1, "unrelated");
        check("连续重复 Workcell 已去重", HandoffGate.missing(List.of(1L, 2L),
                Set.of("1->2"), Set.of()).isEmpty(), "dedup");
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }
}
