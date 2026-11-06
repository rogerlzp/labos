package com.labos.kernel.driver;

import java.util.Map;

/**
 * 方法超时策略：**超时按方法取**，不再由驱动层写死 30 秒。
 *
 * <p><b>为什么必须改</b>：改动前 {@code DriverStepExecutor} 用的是
 * <b>执行器级固定 30 秒</b>（{@code new DriverStepExecutor(registry, sink, db, tenantId, runId)}
 * → {@code this(registry, 30_000L, ...)}），
 * 而 {@code device_method.timeout_sec} / {@code workflow_node.timeout_sec}
 * 两张表里**存了超时值但驱动层从来不读**
 * （{@code grep timeout_sec} 在 driver/ 下零命中）——
 * 后果是**任何耗时超过 30 秒的真机方法都被中控判超时**：
 * 离心 5 分钟、PCR 几十分钟、孵育 30 分钟全部必挂，
 * 与"暂停"无关，是纯 bug。
 *
 * <p><b>取值优先级</b>（先具体后笼统，先设备后流程）：
 * <ol>
 *   <li><b>设备方法级</b> {@code device_method.timeout_sec}：
 *       该台设备（device_instance）该方法名的值 —— 这是设备库给出的**真实契约**
 *       （例如 SEAL 的 GetStatus=3s、Seal=14s，都是「预计耗时 × 2」算出来的）。</li>
 *   <li><b>设备方法库上限</b>：该设备 {@code device_method} 里所有方法的最大值 ——
 *       设备库常常只登记了部分方法（演示岛的 ID1731 只有一行方法名「液体」，
 *       而流程实际下发的是 {@code Home}），精确匹配会落空。
 *       这里**不按能力过滤**：节点上的能力码是**按名字启发式猜出来的**
 *       （`resolveCapability` 第 6 级：节点名「机械臂搬运」会被猜成 WASH），
 *       拿它去过滤设备自己的方法库反而匹配不上。</li>
 *   <li><b>节点级</b> {@code workflow_node.timeout_sec}：流程侧对这个节点的约束。</li>
 *   <li><b>能力默认</b>：{@link #capabilityDefaultSec(String)} —— 按能力给保底。</li>
 *   <li><b>兜底</b>：30 秒（保持改前行为，任何一项都取不到时用它）。</li>
 * </ol>
 *
 * <p><b>为什么显式的设备方法值不抬到能力默认</b>：设备库里那批 3s / 5s / 20s 的值
 * 是**真机契约**，抬高等于把「扫码机死了」从 3 秒才发现变成 10 分钟才发现。
 * 能力默认只填**空缺**（{@code timeout_sec = 0}），不覆盖已有值。
 *
 * <p><b>为什么保底是 600 秒</b>：超时是「设备不回话」的兜底，不是正常耗时；
 * 10 分钟足够覆盖真机长工艺（离心/孵育另给更长），又不会把整单无限期挂死。
 * 演示岛的仿真机方法耗时随机 30–300 秒，保底 600 秒才不会每每撞线。
 */
public final class MethodTimeoutPolicy {

    /** 兜底超时（毫秒）：保持改前行为 —— 驱动层固定 30 秒。 */
    public static final long FALLBACK_MS = 30_000L;

    /** 能力默认超时（秒）。没登记的能力走 {@link #DEFAULT_CAPABILITY_SEC}。 */
    private static final Map<String, Long> CAPABILITY_DEFAULT_SEC = Map.ofEntries(
            Map.entry("INCUBATE", 1800L),      // 孵育是长工艺：30 分钟
            Map.entry("THERMOCYCLE", 3600L),  // 扩增/PCR：60 分钟
            Map.entry("CENTRIFUGE", 900L),     // 离心 15 分钟
            Map.entry("WASH", 900L),           // 洗板 15 分钟
            Map.entry("DECAP", 600L),          // 开盖
            Map.entry("PEEL", 600L),           // 撕膜
            Map.entry("SEAL", 600L),           // 封膜
            Map.entry("ALIQUOT", 600L),        // 移液分装
            Map.entry("ANALYZE", 600L),        // 流式分析
            Map.entry("TRANSFER", 600L),       // 转运
            Map.entry("READ_PLATE", 600L),     // 读板
            Map.entry("STORE", 600L),          // 存储
            Map.entry("PICK", 600L),           // 挑克隆
            Map.entry("SCAN", 600L),           // 扫码
            Map.entry("SENSE", 600L),          // 光电检测
            Map.entry("MIX", 600L));           // 混匀/振荡

    /** 没登记的能力用这个保底（秒）。 */
    public static final long DEFAULT_CAPABILITY_SEC = 600L;

    /** 来源标签：设备方法级。 */
    public static final String SRC_DEVICE_METHOD = "device_method";
    /** 来源标签：设备方法库上限。 */
    public static final String SRC_DEVICE_LIBRARY = "device_library";
    /** 来源标签：流程节点级。 */
    public static final String SRC_WORKFLOW_NODE = "workflow_node";
    /** 来源标签：能力默认。 */
    public static final String SRC_CAPABILITY_DEFAULT = "capability_default";
    /** 来源标签：兜底。 */
    public static final String SRC_FALLBACK = "fallback";

    private MethodTimeoutPolicy() {
    }

    /**
     * 解析结果：超时毫秒 + 来源标签。
     *
     * @param timeoutMs 实际用于等待设备回包的超时（毫秒）
     * @param source    这个值来自哪一级（用于日志与排障）
     */
    public record Resolved(long timeoutMs, String source) {
    }

    /**
     * 能力默认超时（秒）。
     *
     * <p><b>空能力码返回 0</b>（= 没有默认，走 30 秒兜底）：
     * 能力码为空说明「不知道这是什么能力」，此时按改前的 30 秒保守处理；
     * 而「知道是什么能力、只是没登记默认」才用 {@link #DEFAULT_CAPABILITY_SEC} 保底。
     */
    public static long capabilityDefaultSec(String capabilityCode) {
        if (capabilityCode == null || capabilityCode.isBlank()) {
            return 0L;
        }
        Long v = CAPABILITY_DEFAULT_SEC.get(capabilityCode.trim().toUpperCase());
        return v == null ? DEFAULT_CAPABILITY_SEC : v;
    }

    /**
     * 按优先级解析超时。
     *
     * @param deviceMethodSec  设备方法级超时（秒），0 = 没配
     * @param deviceLibrarySec 设备方法库上限（秒），0 = 没配
     * @param workflowNodeSec  流程节点级超时（秒），0 = 没配
     * @param capabilityCode   能力编码（用于能力默认）—— 传**设备的真实能力**，
     *                         不要传节点上启发式猜出来的能力码
     * @param fallbackMs       兜底（毫秒）
     */
    public static Resolved resolve(long deviceMethodSec,
                                   long deviceLibrarySec,
                                   long workflowNodeSec,
                                   String capabilityCode,
                                   long fallbackMs) {
        if (deviceMethodSec > 0) {
            return new Resolved(deviceMethodSec * 1000L, SRC_DEVICE_METHOD);
        }
        if (deviceLibrarySec > 0) {
            return new Resolved(deviceLibrarySec * 1000L, SRC_DEVICE_LIBRARY);
        }
        if (workflowNodeSec > 0) {
            return new Resolved(workflowNodeSec * 1000L, SRC_WORKFLOW_NODE);
        }
        long capSec = capabilityDefaultSec(capabilityCode);
        if (capSec > 0) {
            return new Resolved(capSec * 1000L, SRC_CAPABILITY_DEFAULT);
        }
        return new Resolved(fallbackMs, SRC_FALLBACK);
    }
}
