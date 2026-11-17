package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.InterlockMapper;
import com.labos.kernel.persistence.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 阶段 A · 互锁决策快照（只增不改）。
 *
 * <p>每次命令下发前「允许 / 等待 / 拒绝」判定落一条快照，回答
 * 「当时凭什么放行/拒绝」：事实值 + 会话/步骤/交互 + 状态版本 + 观测/接收时间。
 *
 * <p>定位与 {@link CommandLedger#recordAbort} 相同：审计旁路，记不上只打日志、
 * 不阻断物理动作 —— 宁可少一条审计行，也不能因为审计失败重发一条物理命令。
 *
 * <p><b>决策取值</b>（2026-11-17 接线时收口，表无 CHECK 约束）：
 * <ul>
 *   <li>{@link #ALLOW} 放行；{@link #WAIT} 有界等待中；{@link #REJECT} 拒绝；</li>
 *   <li>{@link #SKIP} <b>守卫未启用</b>——无事实源/未配对/未携带交互上下文。
 *       这一条是「为什么该拦的没拦」的唯一答案：缺配置时守卫静默跳过，
 *       没有 SKIP 快照就只能看到表里空空，无法区分「放行」和「根本没判」。</li>
 * </ul>
 *
 * <p><b>互锁编号</b>（{@code interlock_code} 此前无既有词表，这里定为唯一真源；
 * 与设计文档 L1~L5 编号对齐，便于按层统计命中率）。
 */
public final class InterlockDecision {

    public static final String ALLOW = "ALLOW";
    public static final String WAIT = "WAIT";
    public static final String REJECT = "REJECT";
    /** 守卫未启用（无事实源/未配对/无交互上下文）—— 不是放行，是「没判」。 */
    public static final String SKIP = "SKIP";

    /** L1 转盘互锁。 */
    public static final String L1_TURNTABLE = "L1_TURNTABLE";
    /** L3 轨道分区互锁。 */
    public static final String L3_RAIL_ZONE = "L3_RAIL_ZONE";
    /** L4 搅拌—分液互锁。 */
    public static final String L4_STIRRER = "L4_STIRRER";
    /** L5 等待交互互锁。 */
    public static final String L5_WAIT = "L5_WAIT";

    private static final Logger log = LoggerFactory.getLogger(InterlockDecision.class);

    /** 事实快照 + 决策（链式填充，未填字段落 NULL）。 */
    public static final class Rec {
        public final String interlockCode;
        public final String decision;
        public String factType;
        public String factValue;
        public String observedAt;
        public String receivedAt;
        public String stateVersion;
        public String deviceSessionId;
        public String workflowStepId;
        public String interactionId;
        public String reason;
        public String errorCode;

        public Rec(String interlockCode, String decision) {
            this.interlockCode = interlockCode;
            this.decision = decision;
        }

        public Rec fact(String type, String value) {
            this.factType = type;
            this.factValue = value;
            return this;
        }

        public Rec times(String observedAt, String receivedAt) {
            this.observedAt = observedAt;
            this.receivedAt = receivedAt;
            return this;
        }

        public Rec version(String stateVersion) {
            this.stateVersion = stateVersion;
            return this;
        }

        public Rec session(String deviceSessionId, String workflowStepId, String interactionId) {
            this.deviceSessionId = deviceSessionId;
            this.workflowStepId = workflowStepId;
            this.interactionId = interactionId;
            return this;
        }

        public Rec reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Rec error(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }
    }

    /** 落一条决策快照。{@code db == null} 或 {@code rec == null} 时 no-op。 */
    public static void record(DbConfig db, long tenantId, Long runId, Long nodeId,
                              String commandId, Long resourceId, Rec rec) {
        if (db == null || rec == null) {
            return;
        }
        try {
            Mappers.get(InterlockMapper.class).insertDecision(
                    tenantId, runId, nodeId, commandId, resourceId,
                    rec.interlockCode, rec.factType, rec.factValue,
                    rec.observedAt, rec.receivedAt, rec.stateVersion,
                    rec.deviceSessionId, rec.workflowStepId, rec.interactionId,
                    rec.decision, rec.reason, rec.errorCode);
        } catch (RuntimeException e) {
            log.warn("[互锁决策] 快照落库失败（不阻断下发）：{}", e.getMessage());
        }
    }

    private InterlockDecision() {
    }
}
