package com.labos.kernel.sim;

import java.util.Set;

/**
 * 设备画像的**四个维度**自测（2026-11-15）。
 *
 * <p>画像现在要回答四个问题，自测就钉这四个：
 * <ol>
 *   <li><b>方法</b>：这类设备支持哪些方法（来自厂商公开资料）；</li>
 *   <li><b>时间</b>：每个方法跑多久（步骤时长表；时长是参数时按参数走）；</li>
 *   <li><b>参数</b>：参数范围（配错了在模拟阶段就暴露，不带到现场）；</li>
 *   <li><b>结果</b>：方法回包里带什么（流程要拿得到设备的输出）。</li>
 * </ol>
 *
 * <p>参数值来源见 {@code docs/模拟器设备库.md} 与 {@link DeviceProfile} 的注释
 * （厂商公开规格，**仅用于边界校验与模拟时序，不是采购依据**）。
 */
public final class DeviceProfileSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 设备画像 · 方法/时间/参数/结果 自测 ════");
        System.out.println();

        section("1. 新画像都在（转盘式提取仪 / 搅拌器 / 轨道机械臂 / 耗材栈）");
        for (String p : new String[]{"carousel_extractor", "stirrer", "gantry", "plate_stack"}) {
            DeviceProfile prof = DeviceProfile.of(p);
            ok("画像存在：" + p, prof != DeviceProfile.GENERIC && !prof.methods.isEmpty());
        }

        section("2. 方法：转盘式提取仪要能表达「启动 / 继续」这套握手");
        DeviceProfile ext = DeviceProfile.of("carousel_extractor");
        ok("有 StartProcess（启动提取流程）", ext.methods.contains("StartProcess"));
        ok("有 Continue（继续信号）", ext.methods.contains("Continue"));
        ok("有 ReadPlate（读板）", ext.methods.contains("ReadPlate"));
        ok("轨道机械臂有 Scan（每次抓板扫码）",
                DeviceProfile.of("gantry").methods.contains("Scan"));
        ok("通用 robot 没有 Scan（两者是不同画像）",
                !DeviceProfile.of("robot").methods.contains("Scan"));

        section("3. 时间：步骤时长表（差着数量级的步骤不能共用一个耗时）");
        // ⚠ 时长口径 2026-11-19 换过：原值取自**厂商公开规格**（KingFisher 协议 PDF：
        //   结合孵育 8 min / 洗涤 3 min），现值取自**客户《描述 v2》现场标定**
        //   （这台机器的真实规格，优先于公开规格）⇒ 断言同步改，并补上洗脱/分液两条。
        ok("结合/内部裂解计时 200 秒（客户 v2 现场标定）",
                ext.timingOf("BindIncubate") == 200_000L, String.valueOf(ext.timingOf("BindIncubate")));
        ok("洗涤 150 秒（客户 v2 现场标定）", ext.timingOf("Wash") == 150_000L);
        ok("洗脱（含晾干）600 秒（客户 v2 现场标定）", ext.timingOf("Elute") == 600_000L);
        ok("分液 10 秒（客户 v2 现场标定）",
                DeviceProfile.of("washer").timingOf("Run") == 10_000L);
        ok("风干 10 分钟", ext.timingOf("Dry") == 600_000L);
        ok("板式磁分离 30 秒", ext.timingOf("MagnetSeparate") == 30_000L);
        ok("分液器整板分液 8 秒（KX100-1 手册）",
                DeviceProfile.of("dispenser").timingOf("Dispense") == 8_000L);
        ok("封膜机每板 8 秒（PlateLoc 手册）",
                DeviceProfile.of("sealer").timingOf("Seal") == 8_000L);
        ok("耗材栈取板 10 秒（XHarbor12 / Cytomat）",
                DeviceProfile.of("plate_stack").timingOf("Retrieve") == 10_000L);
        ok("没登记的方法走通用规则（门 1.5 秒）",
                ext.methodDelayMs("OpenDoor") == 1_500L);
        ok("登记了的方法走时长表，不走通用规则",
                ext.methodDelayMs("BindIncubate") == 200_000L);

        section("4. 时间：时长由参数决定（离心 30 秒 vs 30 分钟是同一个方法）");
        DeviceProfile cen = DeviceProfile.of("centrifuge");
        ok("按参数取时长：time=600 ⇒ 600 秒",
                cen.durationSecondsOf("RunProgram", "{\"time\":600}") == 600L);
        ok("太小 ⇒ 夹到下限", cen.durationSecondsOf("RunProgram", "{\"time\":0}") == 1L);
        ok("太大 ⇒ 夹到上限",
                cen.durationSecondsOf("RunProgram", "{\"time\":99999}") == 3_600L);
        ok("没给参数 ⇒ 不接管（-1 = 按别的规则算）",
                cen.durationSecondsOf("RunProgram", "{}") == -1L);
        ok("没登记时长参数的方法 ⇒ -1",
                ext.durationSecondsOf("GetStatus", "{\"time\":600}") == -1L);

        section("5. 参数：配错了在模拟阶段就暴露（不带到现场）");
        ok("离心时间超上限 ⇒ 拒绝",
                cen.validate("RunProgram", "{\"time\":99999}", false) != null);
        ok("离心时间在范围内 ⇒ 放行",
                cen.validate("RunProgram", "{\"time\":600}", false) == null);
        ok("搅拌转速超上限（300–1500）⇒ 拒绝",
                DeviceProfile.of("stirrer").validate("SetRpm", "{\"rpm\":5000}", false) != null);
        ok("搅拌转速在范围内 ⇒ 放行",
                DeviceProfile.of("stirrer").validate("SetRpm", "{\"rpm\":800}", false) == null);
        ok("提取仪体积超上限（20–1000 µL）⇒ 拒绝",
                ext.validate("StartProcess", "{\"volume\":2000}", false) != null);
        ok("封膜温度超上限（30–200 °C）⇒ 拒绝",
                DeviceProfile.of("sealer").validate("SetTemp", "{\"temp\":250}", false) != null);
        ok("板位超容量（8 个板位）⇒ 拒绝",
                ext.validate("Load", "{\"slot\":9}", false) != null);

        section("6. 结果：方法回包里要带得出结果字段");
        ok("提取仪 GetStatus 声明了结果字段",
                !ext.resultFieldsOf("GetStatus").isEmpty());
        ok("轨道机械臂 Scan 声明了板码",
                DeviceProfile.of("gantry").resultFieldsOf("Scan").contains("plate_barcode"));
        ok("耗材栈 GetStatus 声明了剩余板数",
                DeviceProfile.of("plate_stack").resultFieldsOf("GetStatus")
                        .contains("remaining_plates"));
        ok("约定键有值：state", "idle".equals(ext.resultValue("state")));
        ok("约定键有值：plate_present", "true".equals(ext.resultValue("plate_present")));
        ok("约定键有值：remaining_plates（耗材栈 288 位）",
                "288".equals(DeviceProfile.of("plate_stack").resultValue("remaining_plates")));
        ok("不认识的键给空串（不编造）", "".equals(ext.resultValue("no_such_key")));

        section("7. 老画像没被改坏（13 个原有画像都还在）");
        for (String p : new String[]{"robot", "liquid_handler", "centrifuge", "reader",
                "incubator", "washer", "dispenser", "plate_hotel", "sealer", "peeler",
                "barcode", "thermocycler", "shaker", "freezer"}) {
            ok("画像仍在：" + p,
                    DeviceProfile.of(p) != DeviceProfile.GENERIC
                            && !DeviceProfile.of(p).methods.isEmpty());
        }
        ok("摇床 0–3000 rpm 没被搅拌器的范围污染",
                DeviceProfile.of("shaker").maxRpm == 3_000);
        ok("通用画像仍然不校验方法", DeviceProfile.of("generic") == DeviceProfile.GENERIC);

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
        ok(what, cond, null);
    }

    private static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + (detail == null ? "" : "（" + detail + "）"));
        }
    }
}
