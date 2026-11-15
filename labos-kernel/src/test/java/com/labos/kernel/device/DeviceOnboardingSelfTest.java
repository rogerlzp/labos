package com.labos.kernel.device;

import com.labos.boot.web.service.DeviceService;

import java.util.Map;

/**
 * 新设备类型接进来这条路的**纯逻辑**自测（2026-11-15）。
 *
 * <p>背景：交付一条"高通量核酸提取工作站"流程时，5 类新设备（转盘式提取仪 /
 * 分液器 / 搅拌单元 / 轨道四轴机械臂 / 自动化耗材栈）全是**已知型号、手册未到**。
 * 之前它们卡在三处：
 * <ol>
 *   <li>登记时被拒（{@code deviceModel 不在型号库里}）⇒ 设备根本建不出来；</li>
 *   <li>方法集只从 {@code DeviceModels.TABLE} 派生 ⇒ PLANNED 型号的
 *       {@code device_method} 是 0 行 ⇒ <b>设计器里一个方法都选不到</b>；</li>
 *   <li>模拟画像路由按设备类型分不出"通用机械臂 vs 轨道四轴臂"
 *       ⇒ 新写的画像走不到。</li>
 * </ol>
 * 这三条现在都有断言钉住。
 */
public final class DeviceOnboardingSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 新设备类型接入 · 纯逻辑自测 ════");
        System.out.println();

        section("1. PLANNED 型号允许登记（手册未到 ≠ 不能建设备）");
        ok("转盘式提取仪是已知占位型号",
                DeviceModels.isPlanned("carousel-extractor-generic"));
        ok("分液器是已知占位型号",
                DeviceModels.isPlanned("dispenser-plate-generic"));
        ok("搅拌单元是已知占位型号",
                DeviceModels.isPlanned("stirrer-generic"));
        ok("轨道四轴机械臂是已知占位型号",
                DeviceModels.isPlanned("gantry-robot-generic"));
        ok("自动化耗材栈是已知占位型号",
                DeviceModels.isPlanned("plate-stack-generic"));
        ok("已适配型号不算占位", !DeviceModels.isPlanned("s100"));
        ok("不存在的型号不算占位", !DeviceModels.isPlanned("no-such-model"));

        section("2. 型号 → 模拟器画像（设备类型分不出轨道臂/耗材栈）");
        eq("carousel-extractor-generic → carousel_extractor",
                "carousel_extractor", DeviceModels.simProfileOf("carousel-extractor-generic"));
        eq("gantry-robot-generic → gantry",
                "gantry", DeviceModels.simProfileOf("gantry-robot-generic"));
        eq("plate-stack-generic → plate_stack",
                "plate_stack", DeviceModels.simProfileOf("plate-stack-generic"));
        eq("stirrer-generic → stirrer",
                "stirrer", DeviceModels.simProfileOf("stirrer-generic"));
        eq("dispenser-plate-generic → dispenser",
                "dispenser", DeviceModels.simProfileOf("dispenser-plate-generic"));
        ok("已适配型号不走型号映射（走设备类型/能力兜底）",
                DeviceModels.simProfileOf("s100") == null);

        section("3. 画像路由：型号优先，其次设备类型，最后能力");
        eq("型号优先于设备类型（轨道臂即使登记成 Robots 也走 gantry）",
                "gantry",
                DeviceService.simProfileOf("gantry-robot-generic", "Robots", "TRANSFER"));
        eq("耗材栈即使登记成 Storage 也走 plate_stack",
                "plate_stack",
                DeviceService.simProfileOf("plate-stack-generic", "Storage", "STORE"));
        eq("没型号时按设备类型",
                "incubator",
                DeviceService.simProfileOf(null, "Incubators", null));
        eq("再退按能力",
                "robot",
                DeviceService.simProfileOf(null, null, "TRANSFER"));
        eq("都不匹配才 generic",
                "generic",
                DeviceService.simProfileOf(null, "MISC", "SOME_UNKNOWN_CAP"));

        section("4. 新画像都有方法集（方法派生得出来 ⇒ 设计器选得到方法）");
        for (String m : new String[]{"carousel-extractor-generic", "dispenser-plate-generic",
                "stirrer-generic", "gantry-robot-generic", "plate-stack-generic"}) {
            String profile = DeviceModels.simProfileOf(m);
            ok(m + " → " + profile + " 有方法集",
                    profile != null
                            && !com.labos.kernel.sim.DeviceProfile.of(profile).methods.isEmpty());
        }

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
