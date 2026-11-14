package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暂停、恢复、故障处理。
 *
 * <h3>三种暂停态</h3>
 * <table>
 *   <tr><th>类型</th><th>触发</th><th>恢复条件</th></tr>
 *   <tr><td>{@code paused_fault}</td><td>设备故障</td><td><b>心跳恢复就自动继续</b>，不需要人</td></tr>
 *   <tr><td>{@code waiting_human}</td><td>重试耗尽、板位不一致</td><td><b>必须人确认</b></td></tr>
 *   <tr><td>{@code paused_unknown}</td><td>板位置未知</td><td><b>必须盘点 + 校正</b></td></tr>
 * </table>
 *
 * <p><b>为什么要分三种</b>：混成一个"暂停"会导致
 * "设备好了但没人知道可以继续" 或者 "人以为处理了其实没处理"。
 *
 * <h3>暂停只能在动作边界</h3>
 * 机械臂从 A 抓板、移到半空 —— <b>不能停在这里，板会掉</b>。
 * 所以暂停请求只标记，由执行循环在<b>下一步开始前</b>检查。
 *
 * <h3>暂停时释放资源（有豁免）</h3>
 * 被暂停的执行如果还占着资源，会把整个岛拖死。
 * 但三类不能放：回程承诺、活动设备锁、抓着板的机械臂。
 */
public final class PauseManager {

    /** 延迟取 Mapper 代理：本类是 new 出来的工具类，没有构造注入（见 Mappers）。 */
    private static com.labos.kernel.persistence.PauseMapper mappers() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.PauseMapper.class);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PauseManager.class);

    /** 三种暂停类型。 */
    public enum Kind {
        /** 设备故障，心跳恢复后自动继续。 */
        PAUSED_FAULT("paused_fault"),
        /** 需人工确认。 */
        WAITING_HUMAN("waiting_human"),
        /** 板位置未知，必须盘点。 */
        PAUSED_UNKNOWN("paused_unknown"),
        /** R8 步骤 2：人工主动暂停（PauseUnknown 转人工）。 */
        MANUAL("manual");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Kind of(String code) {
            for (Kind k : values()) {
                if (k.code.equals(code)) {
                    return k;
                }
            }
            // R8 步骤 2：也认现场扣留 RuntimeHold.Kind 的 code（唯一真源），避免两套词表漂移。
            if (code != null) {
                switch (code.trim()) {
                    case "fault":
                        return PAUSED_FAULT;
                    case "plate_unknown":
                        return PAUSED_UNKNOWN;
                    case "human":
                        return WAITING_HUMAN;
                    case "manual":
                        return MANUAL;
                    default:
                        break;
                }
            }
            return WAITING_HUMAN;
        }

        /** R8 步骤 2：到现场扣留 RuntimeHold.Kind 的别名映射（唯一真源是 RuntimeHold.Kind）。 */
        public RuntimeHold.Kind toHoldKind() {
            return switch (this) {
                case PAUSED_FAULT -> RuntimeHold.Kind.FAULT;
                case WAITING_HUMAN -> RuntimeHold.Kind.HUMAN;
                case PAUSED_UNKNOWN -> RuntimeHold.Kind.PLATE_UNKNOWN;
                case MANUAL -> RuntimeHold.Kind.MANUAL;
            };
        }
    }

    /** 恢复动作。 */
    public enum ResumeAction {
        /** 继续（人工已处理）。 */
        CONTINUE,
        /** 重试当前动作。 */
        RETRY,
        /** 跳过这一步（危险，要二次确认）。 */
        SKIP,
        /** 终止。 */
        ABORT;

        public static ResumeAction of(String s) {
            if (s == null) {
                return CONTINUE;
            }
            return switch (s.toLowerCase()) {
                case "retry" -> RETRY;
                case "skip" -> SKIP;
                case "abort" -> ABORT;
                default -> CONTINUE;
            };
        }
    }

    private final DbConfig db;
    private final long tenantId;

    public PauseManager(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    // ---------------------------------------------------------------- 暂停

    /**
     * 暂停一个执行。
     *
     * @param kind    暂停类型
     * @param reason  给人看的原因（含错误码和设备编号）
     * @param nodeKey 停在哪个节点（恢复时从这里继续）
     * @return 释放了几个资源
     */
    public int pause(long runId, Kind kind, String reason, String nodeKey)
            throws SQLException {
        // P-06 V2：run 落 paused 与释放资源（位置/预约/通道/设备锁）同一事务。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            int released = pauseTx(c, runId, kind, reason, nodeKey);
            return released;
        });
    }

    /**
     * 同 {@link #pause}，但复用调用方事务连接（P-06 V2 E-1 单事务）。
     *
     * @return 释放的资源条数；0 = run 不在 running（无需暂停）
     */
    public int pauseTx(Connection c, long runId, Kind kind, String reason, String nodeKey)
            throws SQLException {
        // R8 步骤 3：run.status='paused' 收口到 ExperimentStateProjector（唯一写入口）。
        if (ExperimentStateProjector.applyPausedRun(c, tenantId, runId, kind.code(), reason, nodeKey) == 0) {
            return 0;   // 不在 running，不用暂停
        }
        // N-08：waiting_human 暂停自动开人工窗口（复用 manual_window）
        if (kind == Kind.WAITING_HUMAN) {
            openManualWindow(c, runId, reason, nodeKey);
        }
        return releaseResourcesTx(c, runId);
    }

    /** N-08：waiting_human 暂停自动建 pending 人工窗口（同事务）。 */
    private void openManualWindow(Connection c, long runId, String reason, String nodeKey) {
        try {
            if (mappers().hasPendingManualWindow(tenantId, runId)) {
                return;   // 已有待决窗口，不重复开
            }
            mappers().insertManualWindow(tenantId, runId, "pause:" + runId, nodeKey,
                    reason == null ? "等待人工确认" : reason);
        } catch (RuntimeException e) {
            log.error("[暂停] 自动建人工窗口失败：" + e.getMessage());
        }
    }

    /** N-08：run 恢复/终止时关闭其 pending 人工窗口。 */
    private void closeManualWindow(Connection c, long runId, String operator, String action) {
        try {
            mappers().closeManualWindow(tenantId, runId,
                    operator == null ? "api" : operator, action);
        } catch (RuntimeException e) {
            log.error("[暂停] 关闭人工窗口失败：" + e.getMessage());
        }
    }

    /**
     * 释放"已占但未用"的资源，**豁免的不动**。
     *
     * <p>豁免条件（{@code release_exempt = true}）：
     * <ul>
     *   <li>回程承诺（{@code position-return}）—— 放了别人的板就占进去，回程时死锁</li>
     *   <li>活动设备锁 —— 板可能还在里面</li>
     *   <li>抓着板的机械臂 —— 不能松手</li>
     * </ul>
     *
     * @return 释放条数
     */
    public int releaseResources(long runId) {
        // 原实现在这里吞掉 SQLException、记日志后返回 0；保持该对外语义不变，
        // 事务本身仍由 Spring 管理（异常会让 Tx 回滚）。
        try {
            return com.labos.kernel.persistence.Tx.inTransaction(
                    db, c -> releaseResourcesTx(c, runId));
        } catch (SQLException e) {
            log.error("[暂停] 释放资源失败：{}", e.getMessage());
            return 0;
        }
    }

    /** 同 {@link #releaseResources}，但复用调用方事务连接。 */
    private int releaseResourcesTx(Connection c, long runId) throws SQLException {
        int released = 0;

        // 1) 位置预约（非豁免）
        released += mappers().releasePositionReservations(tenantId, runId);

        // 2) 资源预约（非豁免）；回程承诺的 release_exempt = true 自动跳过。
        released += mappers().releaseResourceBookings(tenantId, runId);

        // 3) 通道占用：暂停说明不再处于搬运中，可以放。
        released += mappers().releasePortUsage(tenantId);

        // 4) 设备锁：只放"状态不是 running"的（running 说明正在用）
        released += mappers().releaseDeviceLocks(tenantId, runId);

        return released;
    }

    // ---------------------------------------------------------------- 恢复

    /** 恢复结果。 */
    public record ResumeResult(boolean ok, String error, long runId,
                               String action, int correctedPlates) {
        public String toJsonString() {
            return "{\"ok\":" + ok
                    + ",\"runId\":" + runId
                    + (error == null ? "" : ",\"error\":\"" + esc(error) + "\"")
                    + ",\"action\":\"" + action + "\""
                    + ",\"correctedPlates\":" + correctedPlates + "}";
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * 人工恢复。
     *
     * <p><b>必须由人触发</b> —— 系统不能自己猜"是不是处理好了"。
     *
     * <p><b>paused_unknown 必须带校正</b>：现场和系统记录大概率不一致，
     * 不校正就继续，后面的板位判断全错。
     *
     * @param corrections 板位校正：barcode → 实际位置
     */
    public ResumeResult resume(long runId, ResumeAction action,
                               Map<String, String> corrections, String operator)
            throws SQLException {
        String kind = pauseKindOf(runId);
        // **崩溃中断的 run 也要能续跑**。
        // StartupRecovery 会把崩溃时还在 running 的单子标成 failed 并打上
        // input.recoverable=true，界面（/api/runs/recoverable）也列出来了，
        // 但改动前这里只认 status='paused' —— 点"继续"直接回"该执行不在暂停状态"，
        // 那 40 多条可恢复记录等于只能看不能用。
        boolean crashed = false;
        if (kind == null) {
            crashed = isCrashRecoverable(runId);
            if (!crashed) {
                return new ResumeResult(false, "该执行不在暂停状态", runId,
                        action.name(), 0);
            }
        }

        // paused_unknown 没有校正就拒绝恢复
        if (Kind.PAUSED_UNKNOWN.code().equals(kind)
                && (corrections == null || corrections.isEmpty())
                && action != ResumeAction.ABORT) {
            return new ResumeResult(false,
                    "板位置未知，必须提供 corrections（板号 → 实际位置）才能恢复",
                    runId, action.name(), 0);
        }

        // P-08 强门禁：位置未确认（仅运动报告）时不允许继续/重试，只允许终止
        if (action != ResumeAction.ABORT
                && mappers().countUnconfirmedTransfers(tenantId, runId) > 0) {
            return new ResumeResult(false,
                    "存在未确认的搬运位置，请先扫码/人工确认"
                            + "（POST /api/transfers/{id}/confirm）",
                    runId, action.name(), 0);
        }
        int corrected = 0;
        if (corrections != null) {
            for (Map.Entry<String, String> e : corrections.entrySet()) {
                if (locatePlate(e.getKey(), e.getValue(), operator, runId)) {
                    corrected++;
                }
            }
        }

        if (action == ResumeAction.ABORT) {
            mappers().resumeToAborted(tenantId, runId);
        } else {
            // P0-5 修复（评审 20261102-03）：改动前直接置 running，
            // 但执行体（同步阻塞在 WorkflowService.execute 里的那个线程）
            // 早在暂停时就已经退出了 —— DB 说 running，实际没人在跑，
            // 形成"僵尸 running"：看板显示在跑，进度永远不动。
            //
            // 现在置 queued：**如实表达"已放行、等待被重新拾起"**，
            // 由调度器（ExperimentScheduler）或人工重新提交执行。
            // 不假装"继续" —— 从中间某步继续需要执行体支持断点续跑，
            // 那是下一轮的事（记录里列为 P1）。
            mappers().resumeToQueued(tenantId, runId);
        }

        closeManualWindow(null, runId, operator, action.name());
        return new ResumeResult(true, null, runId, action.name(), corrected);
    }

    /**
     * 是否是"崩溃中断、可恢复"的 run：{@code status='failed'} 且
     * {@code input.recoverable=true}（由 {@code StartupRecovery.settle} 打的标）。
     */
    public boolean isCrashRecoverable(long runId) throws SQLException {
        return mappers().isCrashRecoverable(tenantId, runId);
    }

    /**
     * **忽略**一条暂停的 run：不再等人处理，标记为 aborted 并写清原因。
     *
     * <p>和"终止"（ABORT）的区别只在语义：终止是"正在跑的活我不要了"，
     * 忽略是"这条卡住的我不打算处理了"。落地状态一样（aborted），
     * 但错误码分开写，报表上能看出"多少人是因为不理它才结束的"。
     *
     * <p>为什么需要：暂停队列里堆了 89 条（大部分是历史遗留 + 联调残留），
     * 没人会一条条点"终止"，结果页面永远脏着，真出事的单子反而看不见。
     */
    public boolean dismiss(long runId) throws SQLException {
        return mappers().dismissRun(tenantId, runId) > 0;
    }

    /**
     * 批量忽略：{@code ids} 为空表示"当前队列里的全部"。
     *
     * @return 实际忽略了几条
     */
    public int dismissBatch(java.util.List<Long> ids) throws SQLException {
        return dismissBatch(ids, null, null);
    }

    /**
     * 按条件批量忽略。
     *
     * @param ids          指定单号（非空时优先，忽略后面两个条件）
     * @param kind         只忽略这个暂停类型（null = 全部类型）
     * @param beforeHours  只忽略「最后更新早于 N 小时前」的单子（null = 不限时间）
     *
     * <p>为什么要条件：队列里堆着 89 条历史残留，运维想"只清掉 24 小时前的联调垃圾"，
     * 改动前的"全部忽略"要么清光（可能带走真在等人的单）要么一条条点。
     */
    public int dismissBatch(java.util.List<Long> ids, String kind, Integer beforeHours)
            throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            int n = dismissBatchTx(c, ids, kind, beforeHours);
            return n;
        });
    }

    /** 同 {@link #dismissBatch(java.util.List, String, Integer)}，但复用调用方事务连接。 */
    public int dismissBatchTx(Connection c, java.util.List<Long> ids, String kind,
                              Integer beforeHours) throws SQLException {
        if (ids != null && !ids.isEmpty()) {
            int n = 0;
            for (Long id : ids) {
                n += mappers().dismissRun(tenantId, id);
            }
            return n;
        }
        // 用 updated_at 近似"最后一次动过的时间"：暂停后没人再操作，它就等于暂停时间
        return mappers().dismissRunsConditional(tenantId,
                kind != null && !"all".equals(kind) ? kind : null, beforeHours);
    }

    /** 当前暂停类型，null = 没暂停。 */
    public String pauseKindOf(long runId) throws SQLException {
        return mappers().selectPauseKind(tenantId, runId);
    }

    // ---------------------------------------------------------------- 板位三态

    /**
     * 板位置未知：标 UNKNOWN + 封锁可能位置。
     *
     * <p><b>可能位置取全路径并集</b>：源设备、目标设备、机械臂。
     * 宁可多封，不可漏封 —— 漏封会撞机。
     *
     * <p><b>封锁只封位置不封设备</b>：设备还能干别的，
     * 只是这些位置不能放新板。
     *
     * @return 封锁了几个位置
     */
    public int markPlateUnknown(String barcode, Long srcResourceId,
                                Long dstResourceId, Long robotResourceId, long runId)
            throws SQLException {
        List<Long> possible = new ArrayList<>();
        for (Long id : new Long[]{srcResourceId, dstResourceId, robotResourceId}) {
            if (id != null) {
                possible.add(id);
            }
        }

        // 1) 板标 UNKNOWN + 记录可能位置
        mappers().markPlateUnknown(tenantId, barcode, possible);

        // 2) 封锁可能位置（24 小时，且不自动解封）
        int blocked = possible.isEmpty() ? 0
                : mappers().blockPossiblePositions(tenantId, possible);

        // 3) 审计
        audit(barcode, "KNOWN", "UNKNOWN", null, "system",
                "搬运中异常，可能位置 " + possible, runId);
        return blocked;
    }

    /**
     * 人工盘点：报实际位置，解封其余位置。
     *
     * @param actualLocation 实际位置（设备编号或位置 code）；null 或 "LOST" 表示丢了
     */
    public boolean locatePlate(String barcode, String actualLocation,
                               String operator, Long runId) {
        boolean lost = actualLocation == null
                || actualLocation.isBlank()
                || "LOST".equalsIgnoreCase(actualLocation);

        String toStatus = lost ? "LOST" : "KNOWN";

        // 1) 读可能位置（用于解封）
        List<Long> possible = possibleLocationsOf(barcode);

        // 2) 更新板状态
        try {
            mappers().updatePlatePositionStatus(tenantId, barcode, toStatus);
        } catch (RuntimeException e) {
            return false;
        }

        // 3) 解封：只解"因这块板被封的"
        if (!possible.isEmpty()) {
            mappers().unblockPossiblePositions(tenantId, possible);
        }

        audit(barcode, "UNKNOWN", toStatus, actualLocation,
                operator == null ? "unknown" : operator,
                lost ? "人工确认丢失" : "人工盘点", runId);
        try (Connection c = db.open()) {
            insertEvidence(c, barcode, actualLocation, operator, runId);
        } catch (SQLException e) {
            log.error("[暂停] 位置证据落库失败：" + e.getMessage());
        }
        return true;
    }

    /**
     * P-06 V2 第七切片：恢复写集在调用方事务内完成（E-2）。
     *
     * <p>与 {@link #resume} 同语义：校验暂停类型/崩溃可恢复性 → 应用板位校正 →
     * run 回 queued/aborted。校正的板状态/解封/审计也在同事务（locatePlateTx）。
     * 异步续跑仍由调用方 commit 后触发（内存动作，不属于 DB 事务）。
     */
    public ResumeResult resumeTx(Connection c, long runId, ResumeAction action,
                                 Map<String, String> corrections, String operator)
            throws SQLException {
        String kind = pauseKindOfTx(c, runId);
        boolean crashed = false;
        if (kind == null) {
            crashed = isCrashRecoverableTx(c, runId);
            if (!crashed) {
                return new ResumeResult(false, "该执行不在暂停状态", runId,
                        action.name(), 0);
            }
        }
        if (Kind.PAUSED_UNKNOWN.code().equals(kind)
                && (corrections == null || corrections.isEmpty())
                && action != ResumeAction.ABORT) {
            return new ResumeResult(false,
                    "板位置未知，必须提供 corrections（板号 → 实际位置）才能恢复",
                    runId, action.name(), 0);
        }
        int corrected = 0;
        if (corrections != null) {
            for (Map.Entry<String, String> e : corrections.entrySet()) {
                if (locatePlateTx(c, e.getKey(), e.getValue(), operator, runId)) {
                    corrected++;
                }
            }
        }
        if (action == ResumeAction.ABORT) {
            mappers().resumeToAborted(tenantId, runId);
        } else {
            mappers().resumeToQueued(tenantId, runId);
        }
        closeManualWindow(c, runId, operator, action.name());
        return new ResumeResult(true, null, runId, action.name(), corrected);
    }

    private String pauseKindOfTx(Connection c, long runId) {
        return mappers().selectPauseKind(tenantId, runId);
    }

    private boolean isCrashRecoverableTx(Connection c, long runId) {
        return mappers().isCrashRecoverable(tenantId, runId);
    }

    private boolean locatePlateTx(Connection c, String barcode, String actualLocation,
                                  String operator, Long runId) {
        boolean lost = actualLocation == null || actualLocation.isBlank()
                || "LOST".equalsIgnoreCase(actualLocation);
        String toStatus = lost ? "LOST" : "KNOWN";
        try {
            List<Long> possible = possibleLocationsOfTx(c, barcode);
            mappers().updatePlatePositionStatus(tenantId, barcode, toStatus);
            if (!possible.isEmpty()) {
                mappers().unblockPossiblePositions(tenantId, possible);
            }
            auditTx(c, barcode, "UNKNOWN", toStatus, actualLocation,
                    operator == null ? "unknown" : operator,
                    lost ? "人工确认丢失" : "人工盘点", runId);
            // P-08：人工盘点证据独立落 position_evidence（设备回包不算位置确认）
            insertEvidence(c, barcode, actualLocation, operator, runId);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private List<Long> possibleLocationsOfTx(Connection c, String barcode) {
        return mappers().selectPossibleLocations(tenantId, barcode);
    }

    /** P-08：位置证据（人工盘点来源）。失败不掩盖主流程。 */
    private void insertEvidence(Connection c, String barcode, String actualLocation,
                                String operator, Long runId) {
        try {
            mappers().insertPositionEvidenceManual(tenantId, barcode, runId,
                    operator == null ? "unknown" : operator, actualLocation);
        } catch (RuntimeException e) {
            log.error("[暂停] 位置证据落库失败：" + e.getMessage());
        }
    }

    private void auditTx(Connection c, String barcode, String from, String to,
                         String actual, String operator, String note, Long runId)
            throws SQLException {
        mappers().insertPlateAudit(tenantId, barcode, from, to, actual,
                operator, note, runId);
    }

    private List<Long> possibleLocationsOf(String barcode) {
        return mappers().selectPossibleLocations(tenantId, barcode);
    }

    private void audit(String barcode, String from, String to, String actual,
                       String operator, String note, Long runId) {
        try {
            mappers().insertPlateAudit(tenantId, barcode, from, to, actual,
                    operator, note, runId);
        } catch (RuntimeException e) {
            log.error("[盘点] 审计写入失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 观测

    /** 当前所有暂停的执行。 */
    public List<Map<String, Object>> pausedRuns() {
        List<Map<String, Object>> list = new ArrayList<>();
        // 暂停队列必须能回答"为什么停、停在哪"。
        // run.pause_node_key 常常是 null（比如转到人工时没传），
        // 所以这里直接取**第一个没完成的业务节点**：名字 + 状态 + input.reason
        // （reason 就是「策略[EC-08-400C@NODE] 指定直接转人工」这种，错误码在里面）。
        try {
            for (Map<String, Object> row : mappers().selectPausedRuns(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>(row);
                Object t = m.get("pausedAt");
                m.put("pausedAt", t instanceof java.sql.Timestamp ts
                        ? ts.toInstant().toString() : null);
                list.add(m);
            }
        } catch (RuntimeException e) {
            // 返回空
        }
        return list;
    }

    /**
     * 位置未知的板。
     *
     * <p><b>2026-11-02 修复</b>：改动前直接把 {@code possible_locations}
     * （类型 {@code bigint[]}，存的是 {@code resource_node.id}）返回给前端，
     * 于是盘点页显示的是 {@code [504821, 504822]} —— <b>操作员看不懂</b>。
     * 现在 JOIN {@code resource_node} 把 id 翻成 {@code code}（如 {@code TP412}），
     * 同时保留 id 数组供前端调用 locate 时精确传递。
     */
    public List<Map<String, Object>> unknownPlates() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (Map<String, Object> row : mappers().selectUnknownPlates(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("barcode", row.get("barcode"));
                m.put("positionStatus", row.get("positionStatus"));
                m.put("possibleLocations", toList(row.get("locationNames")));
                m.put("possibleLocationIds", toList(row.get("locationIds")));
                list.add(m);
            }
        } catch (RuntimeException e) {
            // 返回空（toList 不再抛 checked 异常，SQL 异常已被 Mapper 包成 RuntimeException）
        }
        return list;
    }

    /**
     * PG 数组列 → List。
     *
     * <p>⚠ PG 数组列在 {@code resultType=java.util.Map} 下返回的是**Java 数组**
     * （{@code Long[]} / {@code String[]}），不是 {@link java.sql.Array} ——
     * 原来这里只认 {@code java.sql.Array}，于是**板位可能位置恒为空**
     * （不报错，所以一直没被发现）。统一交给 {@code DbArrays} 摊平。
     */
    private static List<Object> toList(Object arrayVal) {
        return com.labos.kernel.persistence.DbArrays.objects(arrayVal);
    }

    /**
     * 自动恢复：设备故障类（{@code paused_fault}）在设备心跳恢复后自动继续。
     *
     * <p>只看 {@code paused_fault} —— 另外两类必须人来。
     *
     * @return 恢复了几个
     */
    public int autoResumeFaulted() {
        try {
            // S-04-B 修复：只看**这条 run 的流程实际用到的设备**是否在线。
            // 旧口径是「全租户所有 device_state 都在线」，现场只要有一台无关
            // 设备离线就永远不自动恢复（实测 demo 环境多台离线 → 永不恢复）。
            return mappers().autoResumeFaulted(tenantId);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
