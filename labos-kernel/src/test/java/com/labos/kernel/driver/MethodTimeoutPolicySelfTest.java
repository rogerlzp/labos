package com.labos.kernel.driver;

import java.util.Map;

/**
 * 超时按方法取 —— 纯逻辑自测（不连库）。
 *
 * <p>锁住四条不能退的语义：
 * <ol>
 *   <li><b>优先级</b>：设备方法级 &gt; 设备能力级 &gt; 流程节点级 &gt; 能力默认 &gt; 兜底 30 秒。
 *       顺序反了就会出现「设备库明明写了 3 秒，却按 10 分钟等」的怪事。</li>
 *   <li><b>0 = 没配</b>：三级的 0 都当「没配」跳过，而不是「超时 0 秒」——
 *       库里 6161 个 workflow_node、300 多个 device_method 的 timeout_sec 就是 0。</li>
 *   <li><b>显式值不被抬高</b>：设备库的 3s/5s/20s 是真机契约，
 *       能力默认只填空缺，**不覆盖**已有值（否则死设备要等 10 分钟才发现）。</li>
 *   <li><b>保底够长</b>：能力默认至少 600 秒 ——
 *       演示岛仿真机方法耗时随机 30–300 秒，保底太短会每每撞线误杀。</li>
 * </ol>
 */
public final class MethodTimeoutPolicySelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ---- 优先级：设备方法级最高 ----
        resolved("设备方法级优先", 3, 120, 60, "SEAL", 3_000L,
                MethodTimeoutPolicy.SRC_DEVICE_METHOD);
        resolved("设备方法级=0 时看设备方法库上限", 0, 120, 60, "SEAL", 120_000L,
                MethodTimeoutPolicy.SRC_DEVICE_LIBRARY);
        resolved("设备两级都为 0 时看流程节点级", 0, 0, 60, "SEAL", 60_000L,
                MethodTimeoutPolicy.SRC_WORKFLOW_NODE);
        resolved("三级都为 0 时用能力默认", 0, 0, 0, "SEAL", 600_000L,
                MethodTimeoutPolicy.SRC_CAPABILITY_DEFAULT);
        // ---- 兜底 30 秒：能力码为空 = 不知道是什么能力，按改前的保守值 ----
        resolved("空能力码 → 兜底 30 秒（改前行为）", 0, 0, 0, null, MethodTimeoutPolicy.FALLBACK_MS,
                MethodTimeoutPolicy.SRC_FALLBACK);
        resolved("空能力码 → 兜底 30 秒（空串同理）", 0, 0, 0, "", MethodTimeoutPolicy.FALLBACK_MS,
                MethodTimeoutPolicy.SRC_FALLBACK);

        // ---- 0 = 没配，不是「超时 0 秒」----
        resolved("设备方法级 0 不参与比较", 0, 0, 0, "INCUBATE", 1_800_000L,
                MethodTimeoutPolicy.SRC_CAPABILITY_DEFAULT);

        // ---- 能力默认表 ----
        capDefault("INCUBATE 默认 1800 秒", "INCUBATE", 1800L);
        capDefault("THERMOCYCLE 默认 3600 秒（PCR 长工艺）", "THERMOCYCLE", 3600L);
        capDefault("CENTRIFUGE 默认 900 秒", "CENTRIFUGE", 900L);
        capDefault("WASH 默认 900 秒", "WASH", 900L);
        capDefault("TRANSFER 默认 600 秒", "TRANSFER", 600L);
        capDefault("SCAN 默认 600 秒", "SCAN", 600L);
        capDefault("未登记能力走保底 600 秒", "NOT_A_CAPABILITY", 600L);
        capDefault("null 能力 = 没有默认（走 30 秒兜底）", null, 0L);
        capDefault("空串能力 = 没有默认（走 30 秒兜底）", "", 0L);
        capDefault("小写能力码也认", "incubate", 1800L);

        // ---- 保底必须够长（仿真机随机 30–300 秒）----
        capDefaultAtLeast("所有能力默认 ≥ 300 秒（仿真机上限 300 秒）", 300L);

        // ---- 显式设备方法值不被能力默认抬高 ----
        resolved("设备库 3 秒不被抬到能力默认", 3, 0, 0, "SEAL", 3_000L,
                MethodTimeoutPolicy.SRC_DEVICE_METHOD);
        resolved("设备库 20 秒不被抬到能力默认", 20, 0, 0, "INCUBATE", 20_000L,
                MethodTimeoutPolicy.SRC_DEVICE_METHOD);

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void resolved(String label, long methodSec, long capSec, long nodeSec,
                                 String capability, long expectMs, String expectSource) {
        MethodTimeoutPolicy.Resolved r = MethodTimeoutPolicy.resolve(
                methodSec, capSec, nodeSec, capability, MethodTimeoutPolicy.FALLBACK_MS);
        if (r.timeoutMs() == expectMs && expectSource.equals(r.source())) {
            pass++;
            System.out.println("  ✓ " + label + " → " + r.timeoutMs() + "ms（" + r.source() + "）");
        } else {
            fail++;
            System.out.println("  ✗ " + label + " 期望 " + expectMs + "ms/" + expectSource
                    + " 实际 " + r.timeoutMs() + "ms/" + r.source());
        }
    }

    private static void capDefault(String label, String capability, long expectSec) {
        long v = MethodTimeoutPolicy.capabilityDefaultSec(capability);
        if (v == expectSec) {
            pass++;
            System.out.println("  ✓ " + label + " → " + v + "s");
        } else {
            fail++;
            System.out.println("  ✗ " + label + " 期望 " + expectSec + "s 实际 " + v + "s");
        }
    }

    private static void capDefaultAtLeast(String label, long floorSec) {
        long min = Long.MAX_VALUE;
        String worst = "";
        for (String cap : new String[]{"INCUBATE", "CENTRIFUGE", "WASH", "DECAP", "PEEL",
                "SEAL", "ALIQUOT", "ANALYZE", "TRANSFER", "READ_PLATE", "STORE", "PICK",
                "SCAN", "SENSE", "MIX"}) {
            long v = MethodTimeoutPolicy.capabilityDefaultSec(cap);
            if (v < min) {
                min = v;
                worst = cap;
            }
        }
        if (min >= floorSec) {
            pass++;
            System.out.println("  ✓ " + label + "（最小 " + min + "s / " + worst + "）");
        } else {
            fail++;
            System.out.println("  ✗ " + label + " 最小只有 " + min + "s（" + worst + "）");
        }
    }
}
