package com.labos.kernel.device;

import com.labos.boot.web.controller.DeviceController;

import java.util.List;
import java.util.Map;

/**
 * 设备接入端点的**参数解析**纯逻辑自测（2026-11-15）。
 *
 * <p><b>为什么单独测这一层</b>：这里出过一次真事故 ——
 * 数字参数用 {@code jsonField} 取，而它**只匹配引号里的值** ⇒
 * 裸数字（{@code {"cols":8}}）被读成 null→0，端点直接 400；
 * 而前端表单发的是字符串，又对不上另一条路。
 * ⇒ "带不带引号都认"这件事必须有断言钉住，否则下次改端点会再踩一次。
 *
 * <p>不需要连库、不需要起服务。
 */
public final class DeviceEndpointParsingSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 设备接入端点 · 参数解析 自测 ════");
        System.out.println();

        section("1. 数字参数：带不带引号都要认");
        eq("裸数字", 8.0, DeviceController.jsonDouble("{\"cols\":8}", "cols"));
        eq("带引号的数字", 8.0, DeviceController.jsonDouble("{\"cols\":\"8\"}", "cols"));
        eq("小数", 45.5, DeviceController.jsonDouble("{\"maxHeightMm\":45.5}", "maxHeightMm"));
        eq("负数", -3.0, DeviceController.jsonDouble("{\"x\":-3}", "x"));
        eq("缺失字段返回 null", null, DeviceController.jsonDouble("{\"a\":1}", "cols"));
        eq("空 body 返回 null", null, DeviceController.jsonDouble("", "cols"));
        eq("null body 返回 null", null, DeviceController.jsonDouble(null, "cols"));

        section("2. 规格数组解析（双工位那种逐条点位）");
        String body = """
                {"deviceCode":"ID-EXT1","points":[
                  {"code":"ID-EXT1-EXT2","label":"EXT2","slotKind":"deck_out",
                   "slotIndex":1,"pointKind":"transfer_point"},
                  {"code":"ID-EXT1-INT1","label":"INT1","slotKind":"deck_in",
                   "pointKind":"transfer_point"}]}
                """;
        List<Map<String, Object>> pts = DeviceController.parseSpecPoints(body);
        eq("解析出 2 条规格", 2, pts.size());
        if (pts.size() == 2) {
            Map<String, Object> a = pts.get(0);
            Map<String, Object> b = pts.get(1);
            eq("第 1 条：编号", "ID-EXT1-EXT2", a.get("code"));
            eq("第 1 条：坑位语义", "deck_out", a.get("slotKind"));
            eq("第 1 条：槽位号是数字", 1.0, numOf(a.get("slotIndex")));
            eq("第 1 条：节点类型", "transfer_point", a.get("pointKind"));
            eq("第 2 条：坑位语义", "deck_in", b.get("slotKind"));
            eq("第 2 条：没给槽位号就没有", null, b.get("slotIndex"));
        }

        section("3. 畸形输入不许把端点带崩");
        eq("没有 points 字段", 0, DeviceController.parseSpecPoints("{\"a\":1}").size());
        eq("points 不是数组", 0,
                DeviceController.parseSpecPoints("{\"points\":\"x\"}").size());
        eq("空 body", 0, DeviceController.parseSpecPoints(null).size());
        eq("截断的 JSON", 0, DeviceController.parseSpecPoints("{\"points\":[{").size());

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static Object numOf(Object o) {
        return o instanceof Double ? o : null;
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
