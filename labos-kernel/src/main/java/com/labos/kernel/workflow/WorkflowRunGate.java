package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 同一流程的**并发运行门禁**（2026-11-17）。
 *
 * <h3>为什么需要</h3>
 * 两件实测出来的事，都指向"同一流程不要并排跑两单"：
 * <ol>
 *   <li><b>设备事件会被抢先消费</b>：{@code device_event} 是先到先得、一个 run 只消费一次
 *       ⇒ 两个 run 同时在等<em>同一台设备</em>的同一个事件时，后到的那个**永远等不到**
 *       （交付清单第 24 条：实测踩到，上一轮卡住的 run 没停就发新 run，新 run 的
 *       「等 P_AUTO-3」被老 run 抢走）。</li>
 *   <li><b>机械臂是唯一物理资源</b>：{@code RailZoneGuard} 的分区锁里，
 *       全部搬运路径都含 {@code RAIL-X231-1}（就是那台 XRobot A10LR-1）⇒ 搬运天然串行。
 *       实测 Transfer 均值 9.33s ⇒ 单臂吞吐 ≈ 6.4 次/分钟，而单个 run 的搬运频率
 *       就有 3.5~4 次/分钟 ⇒ **2 个 run 并行已到容量边缘，4 个必排队**；
 *       run 1001411 的 Transfer 等满 62.7s 后 {@code EC-RESOURCE-BUSY}（唯一一例）。</li>
 * </ol>
 *
 * <h3>规则</h3>
 * 同租户 + 同 {@code workflow_id} 已经存在**在途 run**（{@code queued / running / paused /
 * quarantined}，即 {@link StatusVocabulary} 里 {@code active=true} 的那些）⇒ 拒绝再次启动。
 * {@code draft / pending / planned / 各种终态} 不算在途，不拦（它们没有在跑）。
 *
 * <h3>开关</h3>
 * 环境变量 {@code LABOS_WORKFLOW_CONCURRENT_RUN=allow} 可放开（默认禁止）。
 * 放开后行为回到改造前（只靠设备锁/位置锁兜），用于"确实要并排跑两单"的调试场景。
 *
 * <h3>为什么不用岛锁</h3>
 * 岛锁（{@code ?exclusive=1} + {@code resource_booking} EXCLUDE）是**岛级**互斥，太粗：
 * 一个岛里还有孵箱/离心机/读板机，"孵育 30 分钟"会把整岛挡住。这里的粒度是**同一流程**，
 * 正好对应"会互相抢同一批设备事件"的那个范围。
 *
 * <h3>⚠ 接线点（下一步）</h3>
 * 应在 {@code WorkflowWebService.run(long workflowId, String attendance)} 取到
 * {@link Decision} 后，{@code !allowed} 时直接返回
 * {@code {"error": <reason>, "errorCode": "EC-WORKFLOW-RUNNING", "activeRunId": <runId>}}，
 * 与既有的 {@code EC-ISLAND-BUSY} 分支同一位置、同一返回形状。
 * 本轮未接线的原因：该文件（以及 {@code WorkflowService} / {@code WorkflowController}）
 * 正被另一条并发线的大改占用，改进去会把对方的在制品一起带进提交。
 * 判别性自测已就绪：{@code WorkflowRunGateDbSelfTest}（13/0）。
 */
public final class WorkflowRunGate {

    /** 放开并发的开关值（大小写不敏感）。 */
    private static final List<String> ALLOW_VALUES = List.of("allow", "1", "true", "yes", "on");

    private WorkflowRunGate() {
    }

    /**
     * 门禁结论。
     *
     * @param allowed  是否允许启动
     * @param runId    挡路的那个 run（{@code allowed=true} 时为 0）
     * @param runNo    挡路 run 的业务号（便于直接告诉用户"等哪一个"）
     * @param status   挡路 run 的状态（running / paused / …）
     * @param reason   可直接展示给用户的理由
     */
    public record Decision(boolean allowed, long runId, String runNo, String status, String reason) {

        static Decision allow() {
            return new Decision(true, 0L, null, null, "无同流程在途 run");
        }

        static Decision deny(long runId, String runNo, String status, String reason) {
            return new Decision(false, runId, runNo, status, reason);
        }
    }

    /** 是否放开了并发（{@code LABOS_WORKFLOW_CONCURRENT_RUN=allow}）。 */
    public static boolean concurrentAllowed() {
        String v = System.getenv("LABOS_WORKFLOW_CONCURRENT_RUN");
        return v != null && ALLOW_VALUES.contains(v.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 检查这个流程现在能不能启动。
     *
     * @param excludeRunId 排除自己（续跑/重跑同一 run 时传该 run id；新建传 null）
     */
    public static Decision check(DbConfig db, long tenantId, long workflowId, Long excludeRunId) {
        if (concurrentAllowed()) {
            return Decision.allow();
        }
        if (db == null || tenantId <= 0 || workflowId <= 0) {
            // 参数不全 ⇒ 交由下游各自校验，不在这里造拒绝（避免掩盖真正的错误）
            return Decision.allow();
        }
        List<String> active = activeStatuses();
        if (active.isEmpty()) {
            // 词表读不到时**不拦**：宁可放过也不能因为一张词表把平台锁死
            return Decision.allow();
        }
        // ⚠ run.status 是**枚举类型 run_status**（不是 varchar）⇒ 与字符串参数比较必须显式转 text，
        //   否则报 `column "status" is of type run_status but expression is of type character varying`。
        StringBuilder sql = new StringBuilder(
                "SELECT id, run_no, status::text AS status FROM run "
                        + " WHERE tenant_id = ? AND workflow_id = ? AND deleted_at IS NULL"
                        + "   AND status::text IN (");
        for (int i = 0; i < active.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        if (excludeRunId != null && excludeRunId > 0) {
            sql.append(" AND id <> ?");
        }
        sql.append(" ORDER BY CASE status::text WHEN 'running' THEN 0 WHEN 'paused' THEN 1"
                + " ELSE 2 END, id DESC LIMIT 1");

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, tenantId);
            ps.setLong(i++, workflowId);
            for (String s : active) {
                ps.setString(i++, s);
            }
            if (excludeRunId != null && excludeRunId > 0) {
                ps.setLong(i, excludeRunId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Decision.allow();
                }
                long id = rs.getLong(1);
                String runNo = rs.getString(2);
                String status = rs.getString(3);
                return Decision.deny(id, runNo, status,
                        "该流程已有在途运行 " + runNo + "（" + status + "），"
                                + "同一流程不允许并发启动：设备事件先到先得，两个 run 会互相抢事件；"
                                + "且机械臂是唯一物理资源，搬运只能串行。"
                                + "请等它结束，或用 LABOS_WORKFLOW_CONCURRENT_RUN=allow 显式放开。");
            }
        } catch (SQLException e) {
            // 查询失败也**不拦**：门禁是"防止自己人打自己人"，不该变成"库抖一下就启动不了"
            return Decision.allow();
        }
    }

    /** 词表里所有 active=true 的 run 状态（在途）。 */
    static List<String> activeStatuses() {
        List<String> out = new ArrayList<>();
        for (StatusVocabulary.StatusDef d : StatusVocabulary.statuses(StatusVocabulary.SCOPE_RUN)) {
            if (d.active()) {
                out.add(d.code());
            }
        }
        return out;
    }
}
