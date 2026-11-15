package com.labos.kernel.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1 · 「节点绑定点位」注入设备参数的纯逻辑自测（不连库）。
 *
 * <p>为什么这两件事值得单独测：方向判错 = 机械臂去搬错位置（最贵的一类 bug），
 * spec 选错 = 驱动按错的槽位号寻址。两者都不该等到连库/e2e 才发现。
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@code bindingPositionParam}：绑定设备是目标 ⇒ targetPosition；是源 ⇒ sourcePosition；
 *       都不是 / 缺失 ⇒ null（不注入）；源目标同设备时按目标处理（放板优先）。</li>
 *   <li>{@code bindingPointSpec}：现场标签 > 槽位号 > 点位编码；标签是空白串要跳过。</li>
 * </ol>
 */
public final class NodeBindingPositionSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 方向判定（绑到目标位 / 源位 / 判不出来）");
        eq("绑定设备 == 目标设备 → targetPosition",
                "targetPosition", WorkflowService.bindingPositionParam(200L, 100L, 200L));
        eq("绑定设备 == 源设备 → sourcePosition",
                "sourcePosition", WorkflowService.bindingPositionParam(100L, 100L, 200L));
        eq("绑定设备既不是源也不是目标 → null（不注入）",
                null, WorkflowService.bindingPositionParam(300L, 100L, 200L));
        eq("源/目标是同一台且绑的就是它 → targetPosition（放板优先）",
                "targetPosition", WorkflowService.bindingPositionParam(200L, 200L, 200L));
        eq("绑定设备缺失 → null",
                null, WorkflowService.bindingPositionParam(null, 100L, 200L));
        eq("节点没写 source（只有 target）时绑该设备 → targetPosition",
                "targetPosition", WorkflowService.bindingPositionParam(200L, null, 200L));
        eq("节点没写 target（只有 source）时绑该设备 → sourcePosition",
                "sourcePosition", WorkflowService.bindingPositionParam(100L, 100L, null));

        section("2. spec 选择（现场标签 > 槽位号 > 点位编码）");
        eq("有现场标签 → 用标签", "EXT1", WorkflowService.bindingPointSpec(row("EXT1", 7, "P-7")));
        eq("标签空白 → 用槽位号", "7", WorkflowService.bindingPointSpec(row("  ", 7, "P-7")));
        eq("没有标签也没有槽位号 → 用点位编码", "P-7", WorkflowService.bindingPointSpec(row(null, null, "P-7")));
        eq("什么都没有 → null（调用方跳过）", null, WorkflowService.bindingPointSpec(row(null, null, null)));

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static Map<String, Object> row(String label, Integer slot, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positionLabel", label);
        m.put("slotIndex", slot);
        m.put("pointCode", code);
        return m;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (java.util.Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private NodeBindingPositionSelfTest() {
    }
}
