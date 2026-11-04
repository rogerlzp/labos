package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作用域扣留（runtime hold）：现场级暂停的落地与查询。
 *
 * <h3>为什么暂停是"扣留"而不是"改某条记录的状态"</h3>
 * 一个岛通常只有一台机械臂，多个实验并行时共用设备。现场任何一次暂停
 * （设备故障、板位未知、转人工）都是<b>安全事件</b>，不是某个实验的私事：
 * 此时其他在跑的实验必须一起停在动作边界，否则机械臂分不清板子归属。
 *
 * <p>所以这里用一张独立的 {@code runtime_hold} 表表达"现场被扣住了"，
 * 范围分三级（{@code site} / {@code island} / {@code experiment}），
 * 与"某条 experiment_run 停没停"解耦 —— 扣留期间可以一条实验都没在跑，
 * 只是禁止再派发新的。
 *
 * <h3>三条不能退的语义</h3>
 * <ol>
 *   <li><b>只协作式生效</b>：执行器在<b>动作边界</b>（下一步开始前）检查，
 *       正在执行的动作不硬杀 —— 机械臂抓着板不能松手。</li>
 *   <li><b>扣留不释放资源</b>：现场被扣住，板位必须原样保留。
 *       这与流程级 {@link PauseManager} 的"释放已占未用"是<b>相反</b>的口径，
 *       两者作用对象不同：PauseManager 管的是单个 run 的预约，这里是现场闸门。</li>
 *   <li><b>解除必须由人触发</b>：系统不猜"是不是好了"。</li>
 * </ol>
 */
public final class RuntimeHold {

    /** 生效范围。 */
    public enum Scope {
        /** 全场（最严重，如公共气路故障）。 */
        SITE("site"),
        /** 岛级（默认：一个实验暂停 → 同岛其他实验一起停）。 */
        ISLAND("island"),
        /** 仅该实验。 */
        EXPERIMENT("experiment");

        private final String code;

        Scope(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Scope of(String s) {
            if (s != null) {
                for (Scope v : values()) {
                    if (v.code.equalsIgnoreCase(s)) {
                        return v;
                    }
                }
            }
            return ISLAND;
        }
    }

    /** 扣留类型。与 experiment_run.pause_kind / run.pause_kind 同口径。 */
    public enum Kind {
        /** 设备故障。 */
        FAULT("fault"),
        /** 板位未知，必须盘点。 */
        PLATE_UNKNOWN("plate_unknown"),
        /** 需人工确认。 */
        HUMAN("human"),
        /** 人工主动暂停。 */
        MANUAL("manual");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 归一化：既认本枚举的 code，也认流程级 {@link PauseManager.Kind} 的 code
         * —— 实验级暂停是从流程级暂停上浮来的，两处 code 必须能对上，
         * 否则"设备故障"会显示成"需人工确认"，值班员会按错误的处置流程处理。
         */
        public static Kind of(String s) {
            if (s != null) {
                String v = s.trim();
                for (Kind k : values()) {
                    if (k.code.equalsIgnoreCase(v)) {
                        return k;
                    }
                }
                // 流程级暂停码 → 现场扣留类型
                switch (v.toLowerCase(java.util.Locale.ROOT)) {
                    case "paused_fault":
                        return FAULT;
                    case "paused_unknown":
                        return PLATE_UNKNOWN;
                    case "waiting_human":
                        return HUMAN;
                    default:
                        break;
                }
            }
            return HUMAN;
        }
    }

    /** 边界闸门判定结果。 */
    public enum Gate {
        /** 可以继续。 */
        GO,
        /** 被扣留，停在边界等人工解除。 */
        HELD,
        /** 收到人工停止请求，转 aborted。 */
        STOPPED
    }

    /** 一条生效的扣留（供纯函数判定与展示）。 */
    public record Active(String scope, Long scopeId, String kind, String reason) {
    }

    private final DbConfig db;
    private final long tenantId;

    public RuntimeHold(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    // ---------------------------------------------------------------- 纯函数

    /**
     * 从生效扣留里挑出"覆盖这条实验"的那条，越内层越优先：
     * {@code experiment > island > site}。
     *
     * <p>纯函数，不碰库 —— 让"哪条扣留管到我"这件事可单测，
     * 不用为了验证作用域优先级去建库。
     *
     * @param active       当前所有生效扣留
     * @param experimentId 目标实验
     * @param islandId     目标实验所在岛（0 表示未分配岛，不匹配 island 级扣留）
     */
    static Active covering(List<Active> active, long experimentId, long islandId) {
        Active best = null;
        int bestRank = -1;
        for (Active h : active) {
            int rank;
            if (Scope.EXPERIMENT.code().equals(h.scope())) {
                if (h.scopeId() == null || h.scopeId().longValue() != experimentId) {
                    continue;
                }
                rank = 2;
            } else if (Scope.ISLAND.code().equals(h.scope())) {
                if (h.scopeId() == null || h.scopeId().longValue() != islandId) {
                    continue;
                }
                rank = 1;
            } else if (Scope.SITE.code().equals(h.scope())) {
                rank = 0;
            } else {
                continue;
            }
            if (rank > bestRank) {
                bestRank = rank;
                best = h;
            }
        }
        return best;
    }

    /**
     * 边界闸门：先看人工停止请求，再看是否被扣留。
     *
     * <p>停止优先于扣留 —— 人已经明确说"不要了"，就不必再等解除扣留。
     */
    static Gate gate(boolean stopRequested, Active covering) {
        if (stopRequested) {
            return Gate.STOPPED;
        }
        return covering == null ? Gate.GO : Gate.HELD;
    }

    // ---------------------------------------------------------------- 落库

    /**
     * 扣留。同一 (范围, 范围 id) 已生效时<b>幂等不重复建</b>。
     *
     * @param scopeId island 级传 island.id，experiment 级传 experiment.id，site 级传 null
     * @return true=本次新扣留；false=已有生效扣留（沿用没变）
     */
    public boolean raise(Scope scope, Long scopeId, Kind kind, String reason, Long by)
            throws SQLException {
        String sql = """
                INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason, held_by)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM runtime_hold
                     WHERE tenant_id = ? AND scope = ?
                       AND COALESCE(scope_id, 0) = COALESCE(CAST(? AS bigint), 0)
                       AND released_at IS NULL
                )
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, scope.code());
            if (scopeId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, scopeId);
            }
            ps.setString(4, kind.code());
            ps.setString(5, reason);
            if (by == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, by);
            }
            ps.setLong(7, tenantId);
            ps.setString(8, scope.code());
            if (scopeId == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, scopeId);
            }
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 解除扣留（人工处置完事故后）。没有生效扣留时返回 false。
     */
    public boolean clear(Scope scope, Long scopeId, Long by, String note) throws SQLException {
        String sql = """
                UPDATE runtime_hold
                   SET released_at = now(), released_by = ?, release_note = ?
                 WHERE tenant_id = ? AND scope = ?
                   AND COALESCE(scope_id, 0) = COALESCE(CAST(? AS bigint), 0)
                   AND released_at IS NULL
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (by == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, by);
            }
            ps.setString(2, note);
            ps.setLong(3, tenantId);
            ps.setString(4, scope.code());
            if (scopeId == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, scopeId);
            }
            return ps.executeUpdate() > 0;
        }
    }

    /** 当前所有生效扣留。 */
    public List<Active> active() {
        List<Active> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT scope, scope_id, kind, reason
                       FROM runtime_hold
                      WHERE tenant_id = ? AND released_at IS NULL
                      ORDER BY held_at
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long sid = rs.getLong("scope_id");
                    list.add(new Active(rs.getString("scope"),
                            rs.wasNull() ? null : sid,
                            rs.getString("kind"), rs.getString("reason")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[扣留] 读取生效扣留失败：" + e.getMessage());
        }
        return list;
    }

    /** 覆盖这条实验的扣留（没有则 null）。 */
    public Active coveringFor(long experimentId, long islandId) {
        return covering(active(), experimentId, islandId);
    }

    /** 现场是否处于任何扣留中（调度器用：扣留期不派发新实验）。 */
    public boolean anyActive() {
        return !active().isEmpty();
    }

    /** 生效扣留的展示结构（API 用）。 */
    public List<Map<String, Object>> activeJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, scope, scope_id, kind, reason, held_at, held_by
                       FROM runtime_hold
                      WHERE tenant_id = ? AND released_at IS NULL
                      ORDER BY held_at
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("scope", rs.getString("scope"));
                    long sid = rs.getLong("scope_id");
                    m.put("scopeId", rs.wasNull() ? null : sid);
                    m.put("kind", rs.getString("kind"));
                    m.put("reason", rs.getString("reason"));
                    java.sql.Timestamp t = rs.getTimestamp("held_at");
                    m.put("heldAt", t == null ? null : t.toInstant().toString());
                    long by = rs.getLong("held_by");
                    m.put("heldBy", rs.wasNull() ? null : by);
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            // 返回空
        }
        return list;
    }
}
