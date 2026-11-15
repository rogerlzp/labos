package com.labos.kernel.driver;

import java.time.Duration;
import java.util.Objects;

/**
 * P2 阶段 C · L1 转盘互锁 纯逻辑自测。
 */
public final class TurntableGateSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        section("1. 放行：明确停止 + 新鲜");
        eq("stopped + 新鲜 → allow", true,
                TurntableGate.check("stopped", now, null).allow());

        section("2. 暂态：运动中 → busy（有界等待）");
        TurntableGate.Verdict m = TurntableGate.check("moving", now, null);
        eq("moving → busy", true, m.busy());
        eq("moving 错误码 EC-INTERLOCK-TURNTABLE", "EC-INTERLOCK-TURNTABLE", m.errorCode());

        section("3. 反例：过期/未知");
        eq("stopped + 过期 → EC-STATE-STALE", "EC-STATE-STALE",
                TurntableGate.check("stopped", now - 30_000L, Duration.ofSeconds(5)).errorCode());
        eq("null → EC-STATE-UNKNOWN", "EC-STATE-UNKNOWN",
                TurntableGate.check(null, now, null).errorCode());
        eq("未知值 → EC-STATE-UNKNOWN", "EC-STATE-UNKNOWN",
                TurntableGate.check("weird", now, null).errorCode());
        eq("观测时间未知 → EC-STATE-STALE", "EC-STATE-STALE",
                TurntableGate.check("stopped", 0L, null).errorCode());

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private TurntableGateSelfTest() {
    }
}
