package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /** 三种暂停类型。 */
    public enum Kind {
        /** 设备故障，心跳恢复后自动继续。 */
        PAUSED_FAULT("paused_fault"),
        /** 需人工确认。 */
        WAITING_HUMAN("waiting_human"),
        /** 板位置未知，必须盘点。 */
        PAUSED_UNKNOWN("paused_unknown");

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
            return WAITING_HUMAN;
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
        String sql = """
                UPDATE run SET status = 'paused', pause_kind = ?, pause_reason = ?,
                       paused_at = now(), pause_node_key = ?,
                       updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'running'
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind.code());
            ps.setString(2, reason);
            ps.setString(3, nodeKey);
            ps.setLong(4, tenantId);
            ps.setLong(5, runId);
            if (ps.executeUpdate() == 0) {
                return 0;   // 不在 running，不用暂停
            }
        }
        return releaseResources(runId);
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
        int released = 0;

        // 1) 位置预约（非豁免）
        released += exec("""
                DELETE FROM position_state
                WHERE tenant_id = ? AND reserved_by_run = ?
                  AND plate_id IS NULL
                  AND COALESCE(block_reason, '') <> 'PLATE_UNKNOWN'
                """, runId);

        // 2) 资源预约（非豁免）；回程承诺的 release_exempt = true 自动跳过。
        // resource_booking 没有 run_id 列，通过 run_node_id 关联。
        released += exec("""
                DELETE FROM resource_booking
                WHERE tenant_id = ? AND status = 'active'
                  AND COALESCE(release_exempt, false) = false
                  AND run_node_id IN (SELECT id FROM run_node WHERE run_id = ?)
                """, runId);

        // 3) 通道占用：暂停说明不再处于搬运中，可以放
        released += exec("""
                UPDATE device_port_usage u SET in_use = 0, updated_at = now()
                WHERE u.tenant_id = ?
                  AND u.port_id IN (SELECT id FROM device_port WHERE tenant_id = ?)
                  AND u.in_use > 0
                """, runId);

        // 4) 设备锁：只放"状态不是 running"的（running 说明正在用）
        released += exec("""
                UPDATE device_state SET current_run_id = NULL, status = 'idle',
                       updated_at = now()
                WHERE tenant_id = ? AND current_run_id = ? AND status <> 'running'
                """, runId);

        return released;
    }

    private int exec(String sql, long runId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[暂停] 释放资源失败：" + e.getMessage());
            return 0;
        }
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
        // 但原来这里只认 status='paused' —— 点"继续"直接回"该执行不在暂停状态"，
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

        int corrected = 0;
        if (corrections != null) {
            for (Map.Entry<String, String> e : corrections.entrySet()) {
                if (locatePlate(e.getKey(), e.getValue(), operator, runId)) {
                    corrected++;
                }
            }
        }

        String newStatus;
        String sql;
        if (action == ResumeAction.ABORT) {
            newStatus = "aborted";
            sql = """
                    UPDATE run SET status = ?::run_status, pause_kind = NULL,
                           resumed_at = now(),
                           error_code = COALESCE(error_code, 'EC-HUMAN-ABORT'),
                           updated_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """;
        } else {
            // P0-5 修复（评审 20261102-03）：原来直接置 running，
            // 但执行体（同步阻塞在 WorkflowService.execute 里的那个线程）
            // 早在暂停时就已经退出了 —— DB 说 running，实际没人在跑，
            // 形成"僵尸 running"：看板显示在跑，进度永远不动。
            //
            // 现在置 queued：**如实表达"已放行、等待被重新拾起"**，
            // 由调度器（ExperimentScheduler）或人工重新提交执行。
            // 不假装"继续" —— 从中间某步继续需要执行体支持断点续跑，
            // 那是下一轮的事（记录里列为 P1）。
            newStatus = "queued";
            sql = """
                    UPDATE run SET status = ?::run_status, pause_kind = NULL,
                           pause_reason = NULL, resumed_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """;
        }

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setLong(2, tenantId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }

        return new ResumeResult(true, null, runId, action.name(), corrected);
    }

    /**
     * 是否是"崩溃中断、可恢复"的 run：{@code status='failed'} 且
     * {@code input.recoverable=true}（由 {@code StartupRecovery.settle} 打的标）。
     */
    public boolean isCrashRecoverable(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT 1 FROM run
                      WHERE tenant_id = ? AND id = ? AND status = 'failed'
                        AND (input->>'recoverable')::boolean IS TRUE
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE run
                        SET status = 'aborted', pause_kind = NULL, pause_reason = NULL,
                            pause_node_key = NULL, actual_end = now(),
                            error_code = COALESCE(error_code, 'EC-HUMAN-DISMISS'),
                            updated_at = now()
                      WHERE tenant_id = ? AND id = ? AND status = 'paused'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            return ps.executeUpdate() > 0;
        }
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
     * 原来的"全部忽略"要么清光（可能带走真在等人的单）要么一条条点。
     */
    public int dismissBatch(java.util.List<Long> ids, String kind, Integer beforeHours)
            throws SQLException {
        if (ids != null && !ids.isEmpty()) {
            int n = 0;
            for (Long id : ids) {
                if (dismiss(id)) {
                    n++;
                }
            }
            return n;
        }
        StringBuilder sql = new StringBuilder("""
                UPDATE run
                   SET status = 'aborted', pause_kind = NULL, pause_reason = NULL,
                       pause_node_key = NULL, actual_end = now(),
                       error_code = COALESCE(error_code, 'EC-HUMAN-DISMISS'),
                       updated_at = now()
                 WHERE tenant_id = ? AND status = 'paused'
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (kind != null && !kind.isBlank() && !"all".equals(kind)) {
            sql.append(" AND pause_kind = ?");   // pause_kind 是 varchar，不是枚举（别加 ::pause_kind，会报 type does not exist）
            args.add(kind);
        }
        if (beforeHours != null && beforeHours > 0) {
            // 用 updated_at 近似"最后一次动过的时间"：暂停后没人再操作，它就等于暂停时间
            sql.append(" AND updated_at < now() - make_interval(hours => ?)");
            args.add(beforeHours);
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof Long l) {
                    ps.setLong(i + 1, l);
                } else if (a instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else {
                    ps.setString(i + 1, String.valueOf(a));
                }
            }
            return ps.executeUpdate();
        }
    }

    /** 当前暂停类型，null = 没暂停。 */
    public String pauseKindOf(long runId) throws SQLException {
        String sql = """
                SELECT pause_kind FROM run
                WHERE tenant_id = ? AND id = ? AND status = 'paused'
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
        String sql = """
                UPDATE plate SET position_status = 'UNKNOWN',
                       possible_locations = ?
                WHERE tenant_id = ? AND barcode = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("bigint", possible.toArray()));
            ps.setLong(2, tenantId);
            ps.setString(3, barcode);
            ps.executeUpdate();
        }

        // 2) 封锁可能位置（24 小时，且不自动解封）
        int blocked = 0;
        if (!possible.isEmpty()) {
            String in = String.join(",", possible.stream().map(String::valueOf).toList());
            blocked = execRaw("""
                    UPDATE position_state
                    SET reserved_by_run = -1, block_reason = 'PLATE_UNKNOWN',
                        reserved_until = now() + interval '24 hours', updated_at = now()
                    WHERE tenant_id = %d AND resource_id IN (%s)
                      AND plate_id IS NULL
                    """.formatted(tenantId, in));
        }

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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE plate SET position_status = ?, possible_locations = NULL
                     WHERE tenant_id = ? AND barcode = ?
                     """)) {
            ps.setString(1, toStatus);
            ps.setLong(2, tenantId);
            ps.setString(3, barcode);
            ps.executeUpdate();
        } catch (SQLException e) {
            return false;
        }

        // 3) 解封：只解"因这块板被封的"
        if (!possible.isEmpty()) {
            String in = String.join(",", possible.stream().map(String::valueOf).toList());
            execRaw("""
                    UPDATE position_state
                    SET reserved_by_run = NULL, block_reason = NULL,
                        reserved_until = NULL, updated_at = now()
                    WHERE tenant_id = %d AND resource_id IN (%s)
                      AND block_reason = 'PLATE_UNKNOWN'
                    """.formatted(tenantId, in));
        }

        audit(barcode, "UNKNOWN", toStatus, actualLocation,
                operator == null ? "unknown" : operator,
                lost ? "人工确认丢失" : "人工盘点", runId);
        return true;
    }

    private List<Long> possibleLocationsOf(String barcode) {
        List<Long> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT possible_locations FROM plate WHERE tenant_id = ? AND barcode = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Array arr = rs.getArray(1);
                    if (arr != null) {
                        for (Object o : (Object[]) arr.getArray()) {
                            if (o instanceof Number n) {
                                list.add(n.longValue());
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // 读不到就当没有
        }
        return list;
    }

    private void audit(String barcode, String from, String to, String actual,
                       String operator, String note, Long runId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO plate_location_audit
                       (tenant_id, barcode, from_status, to_status,
                        actual_location, operator, note, run_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, barcode);
            ps.setString(3, from);
            ps.setString(4, to);
            ps.setString(5, actual);
            ps.setString(6, operator);
            ps.setString(7, note);
            if (runId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, runId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[盘点] 审计写入失败：" + e.getMessage());
        }
    }

    private int execRaw(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[暂停] SQL 失败：" + e.getMessage());
            return 0;
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
        String sql = """
                SELECT r.id, r.run_no, r.pause_kind, r.pause_reason, r.paused_at, r.pause_node_key,
                       n.name        AS node_name,
                       n.status::text AS node_status,
                       n.input->>'reason' AS node_reason
                FROM run r
                LEFT JOIN LATERAL (
                      SELECT n2.name, n2.status, n2.input
                      FROM run_node n2
                      WHERE n2.run_id = r.id AND n2.level = 0
                        AND n2.status NOT IN ('completed', 'skipped')
                      ORDER BY n2.id LIMIT 1) n ON true
                WHERE r.tenant_id = ? AND r.status = 'paused' AND r.deleted_at IS NULL
                ORDER BY r.paused_at DESC LIMIT 100
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runId", rs.getLong("id"));
                    m.put("runNo", rs.getString("run_no"));
                    m.put("pauseKind", rs.getString("pause_kind"));
                    m.put("pauseReason", rs.getString("pause_reason"));
                    m.put("pauseNodeKey", rs.getString("pause_node_key"));
                    m.put("nodeName", rs.getString("node_name"));
                    m.put("nodeStatus", rs.getString("node_status"));
                    m.put("nodeReason", rs.getString("node_reason"));
                    java.sql.Timestamp t = rs.getTimestamp("paused_at");
                    m.put("pausedAt", t == null ? null : t.toInstant().toString());
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            // 返回空
        }
        return list;
    }

    /**
     * 位置未知的板。
     *
     * <p><b>2026-11-02 修复</b>：原来直接把 {@code possible_locations}
     * （类型 {@code bigint[]}，存的是 {@code resource_node.id}）返回给前端，
     * 于是盘点页显示的是 {@code [504821, 504822]} —— <b>操作员看不懂</b>。
     * 现在 JOIN {@code resource_node} 把 id 翻成 {@code code}（如 {@code TP412}），
     * 同时保留 id 数组供前端调用 locate 时精确传递。
     */
    public List<Map<String, Object>> unknownPlates() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = """
                SELECT p.barcode,
                       p.position_status,
                       COALESCE((SELECT array_agg(r.code ORDER BY r.id)
                                 FROM resource_node r
                                 WHERE r.id = ANY(p.possible_locations)), '{}') AS location_names,
                       p.possible_locations AS location_ids
                FROM plate p
                WHERE p.tenant_id = ? AND p.position_status IN ('UNKNOWN','LOST')
                ORDER BY p.barcode LIMIT 100
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("barcode", rs.getString("barcode"));
                    m.put("positionStatus", rs.getString("position_status"));
                    java.sql.Array names = rs.getArray("location_names");
                    m.put("possibleLocations",
                            names == null ? List.of() : List.of((Object[]) names.getArray()));
                    java.sql.Array ids = rs.getArray("location_ids");
                    m.put("possibleLocationIds",
                            ids == null ? List.of() : List.of((Object[]) ids.getArray()));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            // 返回空
        }
        return list;
    }

    /**
     * 自动恢复：设备故障类（{@code paused_fault}）在设备心跳恢复后自动继续。
     *
     * <p>只看 {@code paused_fault} —— 另外两类必须人来。
     *
     * @return 恢复了几个
     */
    public int autoResumeFaulted() {
        String sql = """
                UPDATE run SET status = 'running', pause_kind = NULL,
                       pause_reason = NULL, resumed_at = now(), updated_at = now()
                WHERE tenant_id = ? AND status = 'paused' AND pause_kind = 'paused_fault'
                  AND NOT EXISTS (
                        SELECT 1 FROM device_state ds
                        WHERE ds.tenant_id = run.tenant_id AND ds.online = false
                  )
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }
}
