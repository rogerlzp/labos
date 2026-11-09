package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 位置锁：一个位置同一时刻只能被一个 run 占用。
 *
 * <p><b>为什么不是简单地"查一下有没有板"</b>：
 * 两个实验可能同时查到"位置空"，然后都去放板——必须有一次原子的
 * "占住"操作，这就是 {@link #tryReserve} 存在的理由。
 *
 * <p><b>两种占用</b>：
 * <ul>
 *   <li>{@code plate_id} 非空：板实际在里面（持久，几小时）</li>
 *   <li>{@code reserved_until} 未过期：有人预约搬运中（短时，默认 120 秒）</li>
 * </ul>
 * 两者任一成立，位置就不可用。
 *
 * <p><b>TTL 为什么必须有</b>：执行线程可能被 kill -9，`finally` 跑不到。
 * 没有 TTL 的话位置会永久卡死，只能人工改库。
 */
public final class PositionLock {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PositionLock.class);

    /** 默认预约时长：搬运正常几秒，留足余量。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(120);

    /** 位置状态快照（给前端画图用）。 */
    public record PositionState(
            long resourceId, String code, String name, String kind,
            String plateId, boolean reserved, Long reservedByRun,
            String occupiedSince,
            /** 属于哪台设备（孵箱/存储塔），null 表示直接挂在岛上。 */
            Long parentDeviceId, String parentDeviceCode) {

        /** 可用 = 没有板 且 没有有效预约。 */
        public boolean available() {
            return (plateId == null || plateId.isBlank()) && !reserved;
        }
    }

    /** 预约结果。 */
    public enum ReserveResult {
        OK,               // 占到了
        OCCUPIED_BY_PLATE,// 已经有板
        RESERVED_BY_OTHER,// 被别的 run 预约中
        NOT_FOUND         // 位置不存在
    }

    private final DbConfig db;
    private final long tenantId;

    public PositionLock(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 占位**无期限**（回程承诺专用）。
     *
     * <p><b>P1-4 修复（评审 20261102-03）</b>：回程位改动前用 30 分钟 TTL，
     * 而 D5 明确要求"回程承诺不可释放"。TTL 到期后 {@code sweepExpired}
     * 会把它清掉，回程位被别的板占走 → <b>D5 要解决的死锁原样复现</b>。
     *
     * <p>直接传 {@code ttl = null} 不行——{@code tryReserve} 把 null 当
     * "用默认 30 分钟"。所以单独一个方法写 {@code reserved_until = NULL}，
     * 配合已修正的判定：{@code reserved_by_run 非空 + until 为 NULL = 永久承诺}。
     *
     * <p><b>谁来释放</b>：只有持有者自己（{@code release(id, runId)}）或人工处置。
     * 这是刻意的——承诺该由承诺方了结。
     */
    public ReserveResult tryReserveForever(long resourceId, long runId) throws SQLException {
        String kind = positionKind(resourceId);
        if (kind == null) {
            return ReserveResult.NOT_FOUND;
        }

        String upsert = """
                INSERT INTO position_state
                  (tenant_id, resource_id, reserved_until, reserved_by_run, updated_at)
                VALUES (?, ?, NULL, ?, now())
                ON CONFLICT (tenant_id, resource_id) DO UPDATE
                SET reserved_until = NULL,
                    reserved_by_run = EXCLUDED.reserved_by_run,
                    updated_at = now()
                WHERE (position_state.plate_id IS NULL OR position_state.plate_id = '')
                  AND (position_state.reserved_by_run IS NULL
                       OR (position_state.reserved_until IS NOT NULL
                           AND position_state.reserved_until < now())
                       OR position_state.reserved_by_run = EXCLUDED.reserved_by_run)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(upsert)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            ps.setLong(3, runId);
            if (ps.executeUpdate() > 0) {
                return ReserveResult.OK;
            }
        }
        // 抢不到：保守返回"被别人占着"（不区分 NOT_FOUND —— 前面已判过 kind，
        // 走到这里必然是占用冲突）。
        return ReserveResult.RESERVED_BY_OTHER;
    }

    /**
     * 尝试占住一个位置。
     *
     * <p>用一条 UPDATE 完成"检查 + 占用"，靠 WHERE 条件保证原子性——
     * 不能先 SELECT 再 UPDATE，那之间有窗口。
     *
     * @param resourceId 位置（resource_node.id，kind 必须是 incubator/storage/transfer_point）
     * @param runId      谁占的
     * @param ttl        预约时长
     */
    public ReserveResult tryReserve(long resourceId, long runId, Duration ttl) throws SQLException {
        Duration life = ttl == null ? DEFAULT_TTL : ttl;

        // ① 位置必须存在且类型对
        String kind = positionKind(resourceId);
        if (kind == null) {
            return ReserveResult.NOT_FOUND;
        }

        // ② 原子占用：只有"没板 且 预约已过期或没有"才能占
        String upsert = """
                INSERT INTO position_state
                  (tenant_id, resource_id, reserved_until, reserved_by_run, updated_at)
                VALUES (?, ?, now() + ?::interval, ?, now())
                ON CONFLICT (tenant_id, resource_id) DO UPDATE
                SET reserved_until = now() + ?::interval,
                    reserved_by_run = EXCLUDED.reserved_by_run,
                    updated_at = now()
                WHERE (position_state.plate_id IS NULL OR position_state.plate_id = '')
                  -- P1-4：判定能不能占，必须看**谁占着**，不能只看 reserved_until。
                  -- 只按 `reserved_until IS NULL` 判空闲会出错："回程承诺"恰恰是
                  -- reserved_until=NULL + reserved_by_run 非空 —— 于是承诺位被当成空位抢走。
                  -- 新三态：
                  --   reserved_by_run IS NULL              → 真空闲，可占
                  --   非空 且 until < now()                 → 已过期，可占
                  --   非空 且 until IS NULL                 → 永久承诺，不可占
                  AND (position_state.reserved_by_run IS NULL
                       OR (position_state.reserved_until IS NOT NULL
                           AND position_state.reserved_until < now())
                       OR position_state.reserved_by_run = EXCLUDED.reserved_by_run)
                """;
        String interval = life.toSeconds() + " seconds";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(upsert)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            ps.setString(3, interval);
            ps.setLong(4, runId);
            ps.setString(5, interval);
            int n = ps.executeUpdate();
            if (n > 0) {
                return ReserveResult.OK;
            }
        }

        // ③ 没占到，判断原因（这里可以非原子，只是给出可读的错误）
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT plate_id, reserved_until, reserved_by_run
                     FROM position_state WHERE tenant_id = ? AND resource_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String plate = rs.getString(1);
                    if (plate != null && !plate.isBlank()) {
                        return ReserveResult.OCCUPIED_BY_PLATE;
                    }
                    return ReserveResult.RESERVED_BY_OTHER;
                }
            }
        }
        // 更新 0 行 + 查不到记录 = 这个位置在 position_state 里根本不存在，
        // 不能报 OK（否则调用方以为占住了，实际什么都没发生）。
        return ReserveResult.NOT_FOUND;
    }

    /**
     * 一次性占住源和目标（搬运用）。
     *
     * <p>按 resource_id 升序申请——消除循环等待，否则：
     * A 占 X 等 Y、B 占 Y 等 X，互等死锁。
     *
     * <p>第二个失败要放掉第一个（全有或全无），
     * 否则会留下"占了源但没搬"的悬空预约。
     */
    public ReserveResult tryReserveTransfer(long sourceId, long targetId,
                                            long runId, Duration ttl) throws SQLException {
        long first = Math.min(sourceId, targetId);
        long second = Math.max(sourceId, targetId);

        ReserveResult r1 = tryReserve(first, runId, ttl);
        if (r1 != ReserveResult.OK) {
            return r1;
        }
        ReserveResult r2 = tryReserve(second, runId, ttl);
        if (r2 != ReserveResult.OK) {
            release(first, runId);   // 全有或全无
            return r2;
        }
        return ReserveResult.OK;
    }

    /** 释放预约（不板——板还在位置上）。 */
    public void release(long resourceId, long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE position_state
                     SET reserved_until = NULL, reserved_by_run = NULL, updated_at = now()
                     WHERE tenant_id = ? AND resource_id = ? AND reserved_by_run = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    /** 释放某个 run 的全部预约（run 结束时兜底）。 */
    public int releaseAll(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE position_state
                     SET reserved_until = NULL, reserved_by_run = NULL, updated_at = now()
                     WHERE tenant_id = ? AND reserved_by_run = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            return ps.executeUpdate();
        }
    }

    /**
     * 登记"板放进去了"。
     *
     * <p>预约转为实际占用：plate_id 落库，预约清掉。
     * 板会一直占着这个位置，直到被取走。
     */
    /** 该放板但设备下没有空层位。 */
    public static final long NO_EMPTY_POSITION = -1L;

    /** 该取板但设备下没有有板的层位。 */
    public static final long NO_PLATE_TO_TAKE = -2L;

    public int putPlate(long resourceId, String plateId, long runId) throws SQLException {
        int n = putPlateInternal(resourceId, plateId, runId);
        if (n > 0) {
            // 独立连接写冗余表：它失败不该影响已经提交的位置锁
            markPlateOutsideTx(plateId, resourceId, runId);
        }
        refreshHasPlateOfPosition(resourceId);
        return n;
    }

    /**
     * 写 plate 表（冗余，独立连接，失败只记日志）。
     *
     * <p><b>为什么必须在事务外</b>：这张表的列名和代码对不上过（`plate_id` vs `barcode`），
     * 一条 INSERT 失败会让整个事务作废，把 position_state 的更新一起回滚——
     * 表现是"日志说放板成功，层位上没板"。
     *
     * <p>独立连接后，即使 plate 表结构再变，位置锁也不受影响。
     */
    private void markPlateOutsideTx(String plateId, long positionResourceId, long runId) {
        String sql = """
                UPDATE plate SET location_id = ?, updated_at = now()
                WHERE tenant_id = ? AND barcode = ? AND deleted_at IS NULL
                """;
        String ins = """
                INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols,
                                   status, location_id, created_at, updated_at)
                SELECT ?, ?, 'unknown', 8, 12, 'active', ?, now(), now()
                WHERE NOT EXISTS (
                    SELECT 1 FROM plate
                    WHERE tenant_id = ? AND barcode = ? AND deleted_at IS NULL
                )
                """;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, positionResourceId);
                ps.setLong(2, tenantId);
                ps.setString(3, plateId);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                ps.setLong(1, tenantId);
                ps.setString(2, plateId);
                ps.setLong(3, positionResourceId);
                ps.setLong(4, tenantId);
                ps.setString(5, plateId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("[PLATE-WARN] 冗余表 plate 写入失败（不影响位置锁）："
                    + e.getMessage());
        }
    }

    /** 位置变化后，刷新它所属设备的 has_plate。 */
    private void refreshHasPlateOfPosition(long positionResourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT parent_id FROM resource_node WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, positionResourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long pid = rs.getLong(1);
                    if (!rs.wasNull()) {
                        refreshHasPlate(pid);
                    }
                }
            }
        } catch (SQLException ignore) {
            // 刷新失败不影响主流程，下次变动会再算
        }
    }

    private int putPlateInternal(long resourceId, String plateId, long runId) throws SQLException {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                int n;
                // 用 UPSERT 而不是纯 UPDATE：position_state 里可能还没有这一行
                // （tryReserve 没插或插失败），纯 UPDATE 会静默影响 0 行，
                // 表现为"返回了位置但层位上没板"。
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO position_state
                          (tenant_id, resource_id, plate_id, occupied_since,
                           occupied_by_run, updated_at)
                        VALUES (?, ?, ?, now(), ?, now())
                        ON CONFLICT (tenant_id, resource_id) DO UPDATE
                        SET plate_id = EXCLUDED.plate_id,
                            occupied_since = now(),
                            occupied_by_run = EXCLUDED.occupied_by_run,
                            reserved_until = NULL,
                            reserved_by_run = NULL,
                            updated_at = now()
                        WHERE position_state.plate_id IS NULL OR position_state.plate_id = ''
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, resourceId);
                    ps.setString(3, plateId);
                    ps.setLong(4, runId);
                    n = ps.executeUpdate();
                }
                // 不在主事务里写 plate 表：那张表是冗余，列名一变就会
                // 让整个事务作废，把 position_state 的更新一起回滚。
                // 改为 commit 之后用独立连接写（见 putPlate 包装）。
                c.commit();
                return n;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 登记"板取走了"：位置变空。 */
    public int takePlate(long resourceId, long runId) throws SQLException {
        int n = takePlateInternal(resourceId, runId);
        refreshHasPlateOfPosition(resourceId);
        return n;
    }

    private int takePlateInternal(long resourceId, long runId) throws SQLException {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                String plate = null;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT plate_id FROM position_state
                        WHERE tenant_id = ? AND resource_id = ? AND plate_id IS NOT NULL
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, resourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            plate = rs.getString(1);
                        }
                    }
                }
                int n;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE position_state
                        SET plate_id = NULL, occupied_since = NULL, occupied_by_run = NULL,
                            updated_at = now()
                        WHERE tenant_id = ? AND resource_id = ?
                        """)) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, resourceId);
                    n = ps.executeUpdate();
                }
                if (n > 0 && plate != null) {
                    markPlateInTransfer(c, plate, runId);
                }
                c.commit();
                return n;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 清掉过期预约（定时任务调）。返回清理条数。 */
    public int sweepExpired() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE position_state
                     SET reserved_until = NULL, reserved_by_run = NULL, updated_at = now()
                     WHERE tenant_id = ? AND reserved_until IS NOT NULL
                       AND reserved_until < now()
                     """)) {
            ps.setLong(1, tenantId);
            return ps.executeUpdate();
        }
    }

    /** 某个岛下所有位置的占用状态（前端画图用）。 */
    public List<PositionState> byIsland(long islandId) throws SQLException {
        List<PositionState> out = new ArrayList<>();
        String sql = """
                SELECT r.id, r.code, r.name, r.kind::text AS kind,
                       ps.plate_id, ps.reserved_until, ps.reserved_by_run,
                       ps.occupied_since,
                       d.id AS parent_id, d.code AS parent_code
                FROM resource_node r
                LEFT JOIN position_state ps
                       ON ps.resource_id = r.id AND ps.tenant_id = r.tenant_id
                LEFT JOIN resource_node d
                       ON d.id = r.parent_id AND d.kind = 'device' 
                WHERE r.tenant_id = ?
                  AND r.kind IN ('incubator', 'storage', 'transfer_point')
                  AND (r.parent_id = ? OR r.parent_id IN
                       (SELECT id FROM resource_node WHERE parent_id = ? AND kind = 'device'))
                ORDER BY r.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, islandId);
            ps.setLong(3, islandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp until = rs.getTimestamp("reserved_until");
                    boolean reserved = until != null && until.after(new java.util.Date());
                    Object rb = rs.getObject("reserved_by_run");
                    java.sql.Timestamp occ = rs.getTimestamp("occupied_since");
                    Object pid = rs.getObject("parent_id");
                    out.add(new PositionState(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("kind"),
                            rs.getString("plate_id"),
                            reserved,
                            rb == null ? null : ((Number) rb).longValue(),
                            occ == null ? null : occ.toLocalDateTime().toString(),
                            pid == null ? null : ((Number) pid).longValue(),
                            rs.getString("parent_code")));
                }
            }
        }
        return out;
    }

    /** 位置类型（不在允许集合内返回 null）。 */
    private String positionKind(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT kind::text FROM resource_node
                     WHERE tenant_id = ? AND id = ?
                       AND kind IN ('incubator', 'storage', 'transfer_point')
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // A3（评审 20261102-04 Trae 指出）：删掉 markPlatePosition。
    // 它写 plate(plate_id/current_pos/current_run) —— **这三列不存在**，
    // 且 catch(SQLException ignore) 吞异常；同时**没有任何调用者**。
    // 死代码 + 错误列名 = 下一个人照着抄的模板，故删除。
    // 位置真相应在 position_state，由 tryReserve / release 维护。

    /**
     * 标记板"在途"。
     *
     * <p><b>P0-6 修复（评审 20261102-03）</b>：更新 {@code plate.current_pos / current_run / plate_id} —— <b>这三列都不存在</b>
     * （实际列是 {@code barcode}、{@code position_status}）。
     *
     * <p>更致命的是：SQL 失败后 {@code catch (SQLException ignore)} 吞掉异常，
     * 但 **PostgreSQL 已经把整个事务标记为 aborted**，
     * 外层的 {@code c.commit()} 必然抛错 → 整个 takePlate 回滚。
     * 表现是"任何取板动作必失败"，而日志里只看得到一句 catch。
     *
     * <p><b>修法两层</b>：
     * <ol>
     *   <li>SQL 改用真实存在的列（barcode / position_status）</li>
     *   <li>用 {@code SAVEPOINT} 包裹 —— 即使这次更新失败，
     *       也只回滚到这个点，<b>不污染外层事务</b></li>
     * </ol>
     */
    private void markPlateInTransfer(Connection c, String barcode, long runId) {
        final String sp = "sp_mark_plate_in_transfer";
        boolean hasSavepoint = false;
        try {
            // 有 savepoint 才能"局部失败不回滚整体"
            try (PreparedStatement sps = c.prepareStatement("SAVEPOINT " + sp)) {
                sps.execute();
                hasSavepoint = true;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE plate SET position_status = 'IN_TRANSFER', updated_at = now()
                    WHERE tenant_id = ? AND barcode = ?
                    """)) {
                ps.setLong(1, tenantId);
                ps.setString(2, barcode);
                ps.executeUpdate();
            }
            if (hasSavepoint) {
                try (PreparedStatement rel = c.prepareStatement("RELEASE SAVEPOINT " + sp)) {
                    rel.execute();
                }
            }
        } catch (SQLException e) {
            // 这一步只是冗余标记，失败不该让取板整体失败；
            // 但必须回滚到 savepoint，否则事务已 aborted。
            if (hasSavepoint) {
                try (PreparedStatement rb = c.prepareStatement("ROLLBACK TO SAVEPOINT " + sp)) {
                    rb.execute();
                } catch (SQLException e2) {
                    log.error("[位置锁] 回滚 savepoint 失败：" + e2.getMessage());
                }
            }
            log.error("[位置锁] 标记板在途失败（不影响取板）：" + e.getMessage());
        }
    }

    /**
     * 从 position_state 重算 device_state.has_plate。
     *
     * <p><b>为什么必须派生而不是各自维护</b>：这两个字段本来是两拨代码在写
     * （流程执行写 has_plate，notePlateMove 写 plate_id），一旦某次 Deposit
     * 找不到层位，has_plate 变 true 而 plate_id 还是空——后续 Withdraw
     * 就会"从一台显示有板的设备上取不到板"。
     *
     * <p>现在规则很硬：<b>该设备下任一层位有板 → has_plate=true</b>，
     * 否则 false。单一数据源，不可能不一致。
     *
     * @param deviceResourceId resource_node.id（不是 device_instance.id）
     */
    public void refreshHasPlate(long deviceResourceId) throws SQLException {
        String sql = """
                UPDATE device_state ds
                SET has_plate = EXISTS (
                        SELECT 1 FROM resource_node r
                        JOIN position_state ps
                             ON ps.resource_id = r.id AND ps.tenant_id = r.tenant_id
                        WHERE r.parent_id = ds.resource_id AND r.tenant_id = ds.tenant_id
                          AND ps.plate_id IS NOT NULL AND ps.plate_id <> ''
                    ),
                    updated_at = now()
                WHERE ds.tenant_id = ? AND ds.resource_id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, deviceResourceId);
            ps.executeUpdate();
        }
    }

    /**
     * 从多个来源解析板号，按优先级：
     *
     * <ol>
     *   <li>节点参数里显式指定的 {@code plate_id} / {@code plateID}</li>
     *   <li>设备返回原文里的条码（BarcodeScan 之类）</li>
     *   <li>兜底 {@code AUTO-<runId>}（表示"不知道板号"，方便人工补）</li>
     * </ol>
     *
     * <p>不用 UUID 兜底：{@code AUTO-} 前缀一眼能看出"这板没扫描"，
     * 而 UUID 会被误认为真实条码。
     */
    public static String resolvePlateId(java.util.Map<String, Object> params,
                                        String rawResponse, long runId) {
        if (params != null) {
            for (String k : new String[]{"plate_id", "plateID", "plateId", "barcode"}) {
                Object v = params.get(k);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v).trim();
                }
            }
        }
        if (rawResponse != null) {
            // 设备返回里常见字段：plate_id / barcode / plateBarcode
            for (String k : new String[]{"plate_id", "plateBarcode", "barcode", "plateId"}) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"" + k + "\"\\s*:\\s*\"([^\"]+)\"").matcher(rawResponse);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
        }
        return "AUTO-" + runId;
    }

    /**
     * 按方法名自动登记板位变化。
     *
     * <p>为什么按方法名前缀判断：没有"这一步是放板还是取板"的显式标记，
     * 但方法命名有强约定（Deposit/Input/Put/Load = 放入，Withdraw/Output/Get/Unload = 取出）。
     *
     * <p>局限：一台设备有多个层位时，只能取"第一个空的"或"第一个有板的"，
     * 可能不是操作员想要的那个。1.0 接受——真要精确时在节点上配 target_position。
     *
     * @return 实际动了哪个位置（null 表示没找到合适的，或方法不需要登记）
     */
    public Long notePlateMove(long deviceInstanceId, String method, String plateId, long runId)
            throws SQLException {
        if (method == null) {
            return null;
        }
        boolean deposit = startsWithAny(method, "Deposit", "Input", "Put", "Load", "Store");
        boolean withdraw = startsWithAny(method, "Withdraw", "Output", "Get", "Unload", "Retrieve");
        if (!deposit && !withdraw) {
            return null;
        }

        // 设备 -> 资源节点 -> 它的层位
        Long deviceResource = null;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT resource_id FROM device_instance WHERE id = ?")) {
            ps.setLong(1, deviceInstanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    deviceResource = rs.wasNull() ? null : v;
                }
            }
        }
        if (deviceResource == null) {
            return null;
        }

        if (deposit) {
            // 不再直接写 device_state.has_plate——它现在由 position_state 派生
            // （putPlate 内部会调 refreshHasPlate）。两处都写必然不一致。
            Long pos = firstAvailablePosition(deviceResource);
            if (pos == null) {
                // 返回 -1 而不是 null：null 表示"这个方法不需要登记"，
                // -1 表示"该放板但没地方放"，调用方要能区分并告警。
                return NO_EMPTY_POSITION;
            }
            ReserveResult r = tryReserve(pos, runId, DEFAULT_TTL);
            if (r != ReserveResult.OK) {
                return null;
            }
            int written = putPlate(pos,
                    plateId == null ? ("AUTO-" + runId) : plateId, runId);
            if (written <= 0) {
                // 写入 0 行 = 层位上还是没板。不能返回 pos 骗调用方，
                // 否则日志说"放板成功"、层位上空着，取板时取不到。
                log.error("[PLATE-WARN] putPlate 写入 0 行，位置 " + pos
                        + " 未更新（板号 " + plateId + "）");
                return NO_EMPTY_POSITION;
            }
            return pos;
        }

        // withdraw：找第一个有板的位置
        // 同样不直接写 has_plate，由 takePlate 内部的 refreshHasPlate 派生
        Long pos = firstOccupiedPosition(deviceResource);
        if (pos == null) {
            return NO_PLATE_TO_TAKE;
        }
        takePlate(pos, runId);
        return pos;
    }

    /**
     * 同步更新设备级"有没有板"。
     *
     * <p>为什么每步都要更新：调度器按 {@code has_plate} 把"空设备"排前面，
     * 如果只在流程结束后更新，同一条流程里的两个 INCUBATE 节点会选到同一台，
     * 板子永远不会移动。
     */
    private void markDevicePlate(long deviceResourceId, boolean hasPlate, long runId) {
        String sql = """
                INSERT INTO device_state (tenant_id, resource_id, status, has_plate,
                                          current_run_id, updated_at)
                VALUES (?, ?, 'idle', ?, ?, now())
                ON CONFLICT (tenant_id, resource_id) DO UPDATE
                SET has_plate = EXCLUDED.has_plate,
                    current_run_id = EXCLUDED.current_run_id,
                    updated_at = now()
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, deviceResourceId);
            ps.setBoolean(3, hasPlate);
            ps.setLong(4, runId);
            ps.executeUpdate();
        } catch (SQLException ignore) {
            // 板位登记失败不影响执行主链路
        }
    }

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 设备下第一个空位置。 */
    private Long firstAvailablePosition(long deviceResourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.id FROM resource_node r
                     LEFT JOIN position_state ps
                            ON ps.resource_id = r.id AND ps.tenant_id = r.tenant_id
                     WHERE r.tenant_id = ? AND r.parent_id = ?
                       AND r.kind IN ('incubator', 'storage', 'transfer_point')
                       AND (ps.plate_id IS NULL OR ps.plate_id = '')
                       -- A2 修复（评审 20261102-04 Trae 指出）：
                       -- 这里改动前还是二态 `reserved_until IS NULL OR < now()`，
                       -- 而 tryReserve 已改成看 reserved_by_run。
                       -- 两处判定不一致的后果：**永久承诺位（reserved_by_run 非空
                       -- + reserved_until 为 NULL）会被这里当成空位选出来**，
                       -- 然后 tryReserve 又抢不到 —— 表现为"选的位子总是抢失败"。
                       AND (ps.reserved_by_run IS NULL
                            OR (ps.reserved_until IS NOT NULL
                                AND ps.reserved_until < now()))
                     ORDER BY r.id LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, deviceResourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 设备下第一个有板的位置。 */
    private Long firstOccupiedPosition(long deviceResourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.id FROM resource_node r
                     JOIN position_state ps
                          ON ps.resource_id = r.id AND ps.tenant_id = r.tenant_id
                     WHERE r.tenant_id = ? AND r.parent_id = ?
                       AND ps.plate_id IS NOT NULL AND ps.plate_id <> ''
                     ORDER BY r.id LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, deviceResourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 占位统计（给监控用）。 */
    public Map<String, Object> stats() throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE plate_id IS NOT NULL) AS with_plate,
                            -- A2 残留修复（评审 20261102-05 Trae 指出）：
                            -- 这里改动前只数 `reserved_until > now()`，
                            -- **漏掉永久承诺位**（reserved_by_run 非空 + reserved_until 为 NULL）。
                            -- 于是"已预约"这个监控数字恒比实际少，运维看着"还有很多位子"。
                            count(*) FILTER (WHERE reserved_by_run IS NOT NULL
                                               AND (reserved_until IS NULL
                                                    OR reserved_until > now())) AS reserved,
                            count(*)                                        AS tracked
                     FROM position_state WHERE tenant_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    m.put("withPlate", rs.getInt("with_plate"));
                    m.put("reserved", rs.getInt("reserved"));
                    m.put("tracked", rs.getInt("tracked"));
                }
            }
        }
        return m;
    }
}
