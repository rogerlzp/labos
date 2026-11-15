package com.labos.kernel.api;

import com.labos.kernel.device.DeviceModels;

import java.util.Map;

/**
 * 本轮修的两个真 bug 的**回归断言**（2026-11-15）。
 *
 * <p>两个 bug 都是**静默失效**型：界面正常、接口 200，现场跑起来才发现。
 *
 * <ol>
 *   <li><b>{@code paramLong} 找不到参数时返回 {@code 0L}</b> ⇒
 *       「不带 driverId 部署设备」这条最常见的路径**必然 500**
 *       （{@code device_instance_driver_id_fkey} 外键报错），
 *       而且报错信息指向的是外键，看不出真凶是「参数解析」。
 *       ⇒ 现在找不到就返回 {@code null}（= 不修改）。</li>
 *   <li><b>PLANNED 型号登记不了</b> ⇒ 「高通量核酸提取工作站」那 5 类设备
 *       （提取仪/分液器/搅拌/轨道臂/耗材栈）**一台都建不出来**
 *       ⇒ 现在 {@code DeviceRegistrar --model <planned-id>} 能登记，
 *       且能力按类型自动带上（EXTRACT/WASH/…）。</li>
 * </ol>
 *
 * <p>不连库、不起服务。
 */
public final class ParamAndModelRegistrationSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 参数解析 / 型号登记 · 纯逻辑自测 ════");
        System.out.println();

        section("1. paramLong：找不到参数要返回 null，不能返回 0");
        eq("query 为 null → null", null, ApiServer.paramLong(null, "driverId"));
        eq("query 里没有这个键 → null", null, ApiServer.paramLong("islandId=5", "driverId"));
        eq("query 是空串 → null", null, ApiServer.paramLong("", "driverId"));
        eq("有值 → 数值", 7L, ApiServer.paramLong("driverId=7", "driverId"));
        eq("有值但后面跟别的参数 → 仍取到", 12L,
                ApiServer.paramLong("islandId=3&driverId=12&x=1", "driverId"));
        // ⚠ 这条是本 bug 的**核心断言**：返回 0 会被当成"驱动 id = 0"写进库 ⇒ 外键报错。
        ok("找不到时不是 0（0 会被当 id 写库 ⇒ 外键报错）",
                ApiServer.paramLong("islandId=5", "driverId") == null);

        section("2. PLANNED 型号能被找到（登记的前提）");
        String[] planned = {
            "carousel-extractor-generic", "dispenser-plate-generic", "stirrer-generic",
            "gantry-robot-generic", "plate-stack-generic",
        };
        for (String id : planned) {
            DeviceModels.Planned p = DeviceModels.plannedOf(id);
            ok("PLANNED " + id + " 能查到", p != null);
            if (p != null) {
                ok("  类型非空（" + p.type() + "）", p.type() != null && !p.type().isBlank());
                ok("  型号描述非空", p.vendorModel() != null && !p.vendorModel().isBlank());
            }
        }
        eq("不存在的 id → null", null, DeviceModels.plannedOf("no-such-model"));
        eq("null → null", null, DeviceModels.plannedOf(null));

        section("3. PLANNED 型号的模拟画像映射（方法集从画像派生）");
        eq("提取仪 → carousel_extractor", "carousel_extractor",
                DeviceModels.simProfileOf("carousel-extractor-generic"));
        eq("分液器 → dispenser", "dispenser",
                DeviceModels.simProfileOf("dispenser-plate-generic"));
        eq("搅拌 → stirrer", "stirrer",
                DeviceModels.simProfileOf("stirrer-generic"));
        eq("轨道臂 → gantry", "gantry",
                DeviceModels.simProfileOf("gantry-robot-generic"));
        eq("耗材栈 → plate_stack", "plate_stack",
                DeviceModels.simProfileOf("plate-stack-generic"));

        section("4. MANUAL 型号仍走原来的路（别改坏）");
        ok("aspin-2k 仍在 MANUAL 型号表里", DeviceModels.of("aspin-2k") != null);
        ok("s100 仍在 MANUAL 型号表里", DeviceModels.of("s100") != null);
        ok("普通型号的画像映射为 null（走通用兜底）",
                DeviceModels.simProfileOf("aspin-2k") == null);

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
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
