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
import java.util.Set;
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

    /**
     * 安全闸门三态（ADR-PAUSE-005 / 工作项 P-03）。
     *
     * <h3>为什么不是 boolean</h3>
     * 旧实现把「读库失败」和「现场无扣留」都表示成 {@code false} / {@code null}，
     * 等于<b>「读不到 Hold ⇒ 继续派发」</b>——这是 fail-open。
     * 读不到扣留时正确做法是<b>阻断</b>：宁可停机等人工确认，
     * 也不能让机械臂在未知的现场状态下继续动作（撞机不可逆）。
     */
    public enum HoldState {
        /** 读库成功且无覆盖扣留 —— 放行。 */
        CLEAR,
        /** 读库成功且存在覆盖扣留 —— 阻断，等人工解除。 */
        HELD,
        /** 读库失败/异常，无法判断 —— <b>按阻断处理</b>（fail-closed）。 */
        UNKNOWN;

        /** 是否应阻断派发 / 执行 / 恢复：{@code HELD} 与 {@code UNKNOWN} 都阻断。 */
        public boolean blocks() {
            return this != CLEAR;
        }
    }

    /** 覆盖判定结果：三态 + 命中的扣留（{@code CLEAR} / {@code UNKNOWN} 时 {@code hold} 为 null）。 */
    public record Coverage(HoldState state, Active hold) {
        /** 同 {@link HoldState#blocks()}，让调用点读成「要不要阻断」。 */
        public boolean blocks() {
            return state.blocks();
        }
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
     * N-10：按**实验涉及的全部 Workcell** 判覆盖（ADR-FAULT-SCOPE-002）。
     *
     * <p>与 {@link #covering} 的唯一区别：island 级扣留只要命中该实验任一
     * Workcell 就阻断整个实验，不再只看 {@code experiment.island_id}。
     */
    static Active coveringExperiment(List<Active> active, long experimentId,
                                     Set<Long> islandIds) {
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
                if (h.scopeId() == null || islandIds == null
                        || !islandIds.contains(h.scopeId())) {
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

    /** 纯函数：实验级覆盖三态（N-10）。 */
    static Coverage coverageForExperiment(List<Active> active, long experimentId,
                                          Set<Long> islandIds) {
        Active a = coveringExperiment(active, experimentId, islandIds);
        return a == null
                ? new Coverage(HoldState.CLEAR, null)
                : new Coverage(HoldState.HELD, a);
    }

    /**
     * 按实验涉及的全部 Workcell 判覆盖（N-10，查库）。
     */
    public Coverage coveringForExperiment(long experimentId) {
        try {
            Set<Long> islands = ExperimentWorkcellScope.islandsOf(db, tenantId, experimentId);
            return coverageForExperiment(activeChecked(), experimentId, islands);
        } catch (SQLException e) {
            System.err.println("[扣留] 实验 Workcell 覆盖读取失败（按 UNKNOWN 阻断）："
                    + e.getMessage());
            return unknownCoverage();
        }
    }

    /** 同 {@link #coveringForExperiment}，但读库失败抛异常（fail-closed 写路径用）。 */
    public Active coveringForExperimentChecked(long experimentId) throws SQLException {
        Set<Long> islands = ExperimentWorkcellScope.islandsOf(db, tenantId, experimentId);
        return coveringExperiment(activeChecked(), experimentId, islands);
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

    /**
     * 把「已读到的生效扣留列表」折成三态覆盖结果（纯函数，便于单测）。
     *
     * <p>注意：只有<b>成功读到</b>的列表才能进这个函数。读库失败不属于 CLEAR，
     * 必须由调用方走 {@link #unknownCoverage()} —— 这正是 fail-closed 的分界。
     */
    static Coverage coverageOf(List<Active> active, long experimentId, long islandId) {
        Active a = covering(active, experimentId, islandId);
        return a == null
                ? new Coverage(HoldState.CLEAR, null)
                : new Coverage(HoldState.HELD, a);
    }

    /** 读库失败时的覆盖结果：UNKNOWN（阻断）。 */
    static Coverage unknownCoverage() {
        return new Coverage(HoldState.UNKNOWN, null);
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
        try (Connection c = db.open()) {
            return raiseTx(c, scope, scopeId, kind, reason, by);
        }
    }

    /**
     * 同 {@link #raise}，但复用调用方事务连接（P-06 V2：Hold 与 pause_event/幂等键同事务）。
     */
    public boolean raiseTx(Connection c, Scope scope, Long scopeId, Kind kind,
                           String reason, Long by) throws SQLException {
        String sql = """
                INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason, held_by)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM runtime_hold
                     WHERE tenant_id = ? AND scope = ?
                       AND COALESCE(scope_id, 0) = COALESCE(CAST(? AS bigint), 0)
                       AND kind = ?
                       AND released_at IS NULL
                )
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
            ps.setString(10, kind.code());
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

    /** 同 {@link #clear}，但复用调用方事务连接（P-06 V2 Resume 单事务）。 */
    public boolean clearTx(Connection c, Scope scope, Long scopeId, Long by, String note)
            throws SQLException {
        String sql = """
                UPDATE runtime_hold
                   SET released_at = now(), released_by = ?, release_note = ?
                 WHERE tenant_id = ? AND scope = ?
                   AND COALESCE(scope_id, 0) = COALESCE(CAST(? AS bigint), 0)
                   AND released_at IS NULL
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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

    /**
     * 按 kind 精确解除：多 Hold 并存时只解一类，不误伤其它原因（P-03）。
     */
    public boolean clearKind(Scope scope, Long scopeId, Kind kind, Long by, String note)
            throws SQLException {
        try (Connection c = db.open()) {
            return clearKindTx(c, scope, scopeId, kind, by, note);
        }
    }

    /** 同 {@link #clearKind}，复用调用方事务连接。 */
    public boolean clearKindTx(Connection c, Scope scope, Long scopeId, Kind kind,
                               Long by, String note) throws SQLException {
        String sql = """
                UPDATE runtime_hold
                   SET released_at = now(), released_by = ?, release_note = ?
                 WHERE tenant_id = ? AND scope = ?
                   AND COALESCE(scope_id, 0) = COALESCE(CAST(? AS bigint), 0)
                   AND kind = ? AND released_at IS NULL
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
            ps.setString(6, kind.code());
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 同 {@link #activeChecked}，但复用调用方事务连接（读到本事务内已 clear 的扣留）。
     */
    public List<Active> activeCheckedTx(Connection c) throws SQLException {
        List<Active> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
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
        }
        return list;
    }

    /**
     * 同 {@link #coveringForExperimentChecked}，但复用事务连接读生效扣留；
     * Workcell 集合仍按已提交数据推导（只读）。
     */
    public Active coveringForExperimentCheckedTx(Connection c, long experimentId)
            throws SQLException {
        Set<Long> islands = ExperimentWorkcellScope.islandsOf(db, tenantId, experimentId);
        return coveringExperiment(activeCheckedTx(c), experimentId, islands);
    }

    /**
     * 当前所有生效扣留；<b>读库失败时抛 {@link SQLException}</b>，不做 fail-open 兜底。
     *
     * <p>所有安全判定都必须走这个方法（或建在它之上的三态方法），
     * 只有纯展示（{@link #active()}）才允许吞异常；{@link #activeView()} 读库失败返回
     * {@link HoldState#UNKNOWN}（对外显式可见）。
     */
    public List<Active> activeChecked() throws SQLException {
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
        }
        return list;
    }

    /** 当前所有生效扣留（展示用；读库失败返回空列表，安全判定请用 {@link #activeChecked()}）。 */
    public List<Active> active() {
        try {
            return activeChecked();
        } catch (SQLException e) {
            System.err.println("[扣留] 读取生效扣留失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 现场是否有任何生效扣留 —— 三态（调度器全局闸门用）。
     *
     * <p>读库失败返回 {@link HoldState#UNKNOWN}，调用方<b>必须</b>按「阻断」处理。
     */
    public HoldState siteState() {
        try {
            return activeChecked().isEmpty() ? HoldState.CLEAR : HoldState.HELD;
        } catch (SQLException e) {
            System.err.println("[扣留] 现场闸门读取失败（按 UNKNOWN 阻断派发）：" + e.getMessage());
            return HoldState.UNKNOWN;
        }
    }

    /**
     * 覆盖这条实验的扣留 —— 三态（读库失败 ⇒ {@link HoldState#UNKNOWN}）。
     */
    public Coverage coverageFor(long experimentId, long islandId) {
        try {
            return coverageOf(activeChecked(), experimentId, islandId);
        } catch (SQLException e) {
            System.err.println("[扣留] 覆盖判定读取失败（按 UNKNOWN 阻断）：" + e.getMessage());
            return unknownCoverage();
        }
    }

    /**
     * 覆盖判定（读库失败抛 {@link SQLException}）—— 供必须 fail-closed 的写路径
     * （如 RunControl.resume）使用：让「读不到扣留」直接变成一次失败操作，
     * 而不是被当成「没扣留」放行。
     */
    public Active coveringForChecked(long experimentId, long islandId) throws SQLException {
        return covering(activeChecked(), experimentId, islandId);
    }

    /** 覆盖这条实验的扣留（没有则 null）。 */
    public Active coveringFor(long experimentId, long islandId) {
        return covering(active(), experimentId, islandId);
    }

    /**
     * 覆盖「正在执行这条流程的实验」的扣留（没有则 null）。
     *
     * <p><b>为什么需要这层反查</b>：实验级暂停落的是扣留，**不改** workflow 级
     * {@code run} 的状态（见 {@link RunControl}）。底层流程（{@code GraphRunner} 的
     * 节点边界检查、可中断方法的 abort 轮询）手里只有自己的 {@code workflow_id}，
     * 要判断「该不该停」只能反查：正在跑这条流程的实验步骤 → 实验 → 岛，
     * 再看有没有扣留盖住它。
     *
     * <p><b>为什么不按 {@code experiment_run.status='running'} 过滤</b>：
     * 实验级暂停会先把 {@code experiment_run} 置 {@code paused}
     * （{@link RunControl#pause} → {@code markCoveredPaused}），
     * 再用 {@code running} 过滤就永远查不到 —— 这正是「暂停发出去、
     * 底层 run 照旧把所有节点跑完」的根因（2026-11-06 实测）。
     * 步骤状态只在执行期是 {@code running}，暂停后会被执行器改成 {@code paused}，
     * 所以判据落在**步骤行还在跑**这一件事上，而不是实验行的状态。
     */
    public Active coveringForWorkflow(long workflowId) {
        if (workflowId <= 0) {
            return null;
        }
        List<Active> active = active();
        if (active.isEmpty()) {
            return null;   // 现场没有任何扣留 —— 常见快路径，不必反查
        }
        String sql = """
                SELECT er.experiment_id, COALESCE(e.island_id, 0) AS island_id
                  FROM experiment_run_step s
                  JOIN experiment_run er
                    ON er.tenant_id = s.tenant_id AND er.id = s.experiment_run_id
                  JOIN experiment e
                    ON e.tenant_id = er.tenant_id AND e.id = er.experiment_id
                 WHERE s.tenant_id = ? AND s.workflow_id = ? AND s.status = 'running'
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Active a = covering(active, rs.getLong(1), rs.getLong(2));
                    if (a != null) {
                        return a;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[扣留] 反查流程归属失败：" + e.getMessage());
        }
        return null;
    }

    /**
     * 覆盖「正在执行这条流程的实验」的扣留 —— 三态（读库失败 ⇒ {@link HoldState#UNKNOWN}）。
     *
     * <p>执行器（节点边界检查 / 可中断方法 abort 轮询）用它判「该不该停」。
     * 读不到扣留时返回 {@code UNKNOWN}，调用方按「停」处理，不得继续跑物理动作。
     */
    public Coverage coverageForWorkflow(long workflowId) {
        if (workflowId <= 0) {
            return new Coverage(HoldState.CLEAR, null);
        }
        try {
            List<Active> active = activeChecked();
            if (active.isEmpty()) {
                return new Coverage(HoldState.CLEAR, null);   // 现场没有任何扣留 —— 常见快路径，不必反查
            }
            String sql = """
                    SELECT er.experiment_id, COALESCE(e.island_id, 0) AS island_id
                      FROM experiment_run_step s
                      JOIN experiment_run er
                        ON er.tenant_id = s.tenant_id AND er.id = s.experiment_run_id
                      JOIN experiment e
                        ON e.tenant_id = er.tenant_id AND e.id = er.experiment_id
                     WHERE s.tenant_id = ? AND s.workflow_id = ? AND s.status = 'running'
                    """;
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, workflowId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Active a = covering(active, rs.getLong(1), rs.getLong(2));
                        if (a != null) {
                            return new Coverage(HoldState.HELD, a);
                        }
                    }
                }
            }
            return new Coverage(HoldState.CLEAR, null);
        } catch (SQLException e) {
            System.err.println("[扣留] 反查流程归属读取失败（按 UNKNOWN 阻断执行）：" + e.getMessage());
            return unknownCoverage();
        }
    }

    /**
     * 现场是否处于任何扣留中。
     *
     * <p><b>注意</b>：这是展示/兼容用的两值版本，读库失败会返回 {@code false}。
     * 安全判定（派发闸门）必须用三态的 {@link #siteState()}，否则又会退化为 fail-open。
     */
    public boolean anyActive() {
        return !active().isEmpty();
    }

    /** 生效扣留的展示结构 + 三态（读库失败 = {@link HoldState#UNKNOWN}）。 */
    public record HoldView(HoldState state, List<Map<String, Object>> holds) {
    }

    /**
     * 生效扣留的展示结构 + 三态（API 用）。
     *
     * <p>与 {@link #active()} 的区别：读库失败时返回 {@link HoldState#UNKNOWN}，而不是
     * 静默当成「无扣留」。{@code UNKNOWN} 会让调度器 fail-closed 阻断新派发，若接口只回
     * 空列表，运维看到的「实验不派发」将没有任何界面线索（ADR-PAUSE-010）。
     */
    public HoldView activeView() {
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
            return new HoldView(list.isEmpty() ? HoldState.CLEAR : HoldState.HELD, list);
        } catch (SQLException e) {
            System.err.println("[扣留] 读取生效扣留失败（按 UNKNOWN 暴露给运维）：" + e.getMessage());
            return new HoldView(HoldState.UNKNOWN, list);
        }
    }
}
