package com.labos.kernel.driver;

import com.labos.kernel.sim.DeviceProfile;

import java.util.List;
import java.util.Map;

/**
 * L1 移液账本闸门自检：不连库、不碰设备，纯内存验证拦截语义。
 *
 * <p>运行：在 kernel out 目录编译后
 * {@code java -cp out:lib/... com.labos.kernel.driver.LiquidLedgerGateSelfTest}
 */
public final class LiquidLedgerGateSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ L1 移液账本闸门 · 自检 ════\n");

        DeviceProfile lh = DeviceProfile.of("liquid_handler");

        // 1) 正常稀释：A1 200 → 转 100 到 B1 → 转 50 到 B2，账本应为 A1=50 B1=100 B2=50
        LiquidLedgerGate.Verdict ok = LiquidLedgerGate.check(lh,
                LiquidLedgerGate.wells("src_plate/A1", 200.0, "dst_plate/B1", 0.0),
                List.of(
                        LiquidLedgerGate.Op.of("GetTips", Map.of("count", 1)),
                        LiquidLedgerGate.Op.of("Transfer", Map.of("srcLabware", "src_plate", "srcWell", "A1",
                                "dstLabware", "dst_plate", "dstWell", "B1", "volume", 100.0)),
                        LiquidLedgerGate.Op.of("Transfer", Map.of("srcLabware", "src_plate", "srcWell", "A1",
                                "dstLabware", "dst_plate", "dstWell", "B2", "volume", 50.0))));
        eq("正常流程放行", true, ok.allow());
        eq("账本 A1=50", 50.0, ok.projectedWells().get("src_plate/A1"));
        eq("账本 B1=100", 100.0, ok.projectedWells().get("dst_plate/B1"));
        eq("账本 B2=50", 50.0, ok.projectedWells().get("dst_plate/B2"));

        // 2) 吸后为负：源孔 40 µL 要转 100 → 拦
        LiquidLedgerGate.Verdict neg = LiquidLedgerGate.check(lh,
                LiquidLedgerGate.wells("src/A1", 40.0),
                List.of(LiquidLedgerGate.Op.of("Aspirate",
                        Map.of("labware", "src", "well", "A1", "volume", 100.0, "hasTip", true))));
        // 没有 GetTips ⇒ 先被 TIP-MISSING 拦；这里单独验证负体积也能被记录
        boolean hasNeg = neg.blocking().stream().anyMatch(f -> "EC-PHYS-VOL-NEGATIVE".equals(f.code()));
        eq("源孔不足被拦(VOL-NEGATIVE)", true, hasNeg);

        // 3) 未取枪头吸液 → 拦
        LiquidLedgerGate.Verdict noTip = LiquidLedgerGate.check(lh,
                LiquidLedgerGate.wells("src/A1", 200.0),
                List.of(LiquidLedgerGate.Op.of("Aspirate",
                        Map.of("labware", "src", "well", "A1", "volume", 50.0))));
        eq("无枪头吸液被拦", false, noTip.allow());
        eq("无枪头错误码", "EC-PHYS-TIP-MISSING", noTip.firstCode());

        // 4) 量程越界：12000 µL 超出 liquid_handler 的 0.5–5000 → 拦
        LiquidLedgerGate.Verdict range = LiquidLedgerGate.check(lh,
                LiquidLedgerGate.wells("src/A1", 20000.0),
                List.of(LiquidLedgerGate.Op.of("GetTips", Map.of("count", 1)),
                        LiquidLedgerGate.Op.of("Aspirate",
                                Map.of("labware", "src", "well", "A1", "volume", 12000.0))));
        eq("量程越界被拦", false, range.allow());
        eq("量程错误码", "EC-PHYS-RANGE", range.firstCode());

        // 5) 溢出：目标已有 350，再分 100 → 超过 360 → 拦
        LiquidLedgerGate.Verdict overflow = LiquidLedgerGate.check(lh,
                LiquidLedgerGate.wells("dst/B1", 350.0),
                List.of(LiquidLedgerGate.Op.of("Dispense",
                        Map.of("labware", "dst", "well", "B1", "volume", 100.0))));
        boolean hasOverflow = overflow.blocking().stream().anyMatch(f -> "EC-PHYS-OVERFLOW".equals(f.code()));
        eq("液面溢出被拦", true, hasOverflow);

        // 6) 几何：8 通道跨度 72 mm > 槽长 71.2 → 拦（VERID 0.8mm 案例）
        LiquidLedgerGate.Verdict geom = LiquidLedgerGate.check(lh, Map.of(),
                List.of(LiquidLedgerGate.Op.of("Span8Check",
                        Map.of("channels", 8, "troughLengthMm", 71.2))));
        eq("窄槽几何被拦", false, geom.allow());
        eq("几何错误码", "EC-PHYS-GEOMETRY", geom.firstCode());

        // 6b) 7 通道跨度 54 mm < 71.2 → 放行（智能体的实际改法）
        LiquidLedgerGate.Verdict geom7 = LiquidLedgerGate.check(lh, Map.of(),
                List.of(LiquidLedgerGate.Op.of("Span8Check",
                        Map.of("channels", 7, "troughLengthMm", 71.2))));
        eq("7通道窄槽放行", true, geom7.allow());

        // 7) fail-open：孔体积未知只告警不拦（设计稿 D4「未登记=不拦」）
        LiquidLedgerGate.Verdict unknown = LiquidLedgerGate.check(lh, Map.of(),
                List.of(LiquidLedgerGate.Op.of("GetTips", Map.of("count", 1)),
                        LiquidLedgerGate.Op.of("Transfer",
                                Map.of("srcLabware", "src", "srcWell", "A1",
                                        "dstLabware", "dst", "dstWell", "B1", "volume", 50.0))));
        eq("未知体积放行(fail-open)", true, unknown.allow());
        eq("未知体积有告警", true, unknown.hasWarnings());

        // 8) GENERIC 画像跳过量程/几何，仍做守恒
        LiquidLedgerGate.Verdict generic = LiquidLedgerGate.check(DeviceProfile.GENERIC,
                LiquidLedgerGate.wells("src/A1", 10.0),
                List.of(LiquidLedgerGate.Op.of("GetTips", Map.of("count", 1)),
                        LiquidLedgerGate.Op.of("Transfer",
                                Map.of("srcLabware", "src", "srcWell", "A1",
                                        "dstLabware", "dst", "dstWell", "B1", "volume", 100.0))));
        eq("GENERIC 仍守恒拦截", false, generic.allow());
        eq("GENERIC 错误码", "EC-PHYS-VOL-NEGATIVE", generic.firstCode());

        System.out.println("\n通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
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
