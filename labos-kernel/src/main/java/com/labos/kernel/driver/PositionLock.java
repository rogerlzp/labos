package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 位置锁：一个位置同一时刻只能被一个 run 占用。
 *
 * <p><b>收编说明（阶段3）</b>：业务 SQL 已全部归 {@code DeviceStateMapper}/
 * {@code QueryMapper}；唯一保留的 3 条 {@code prepareStatement} 是
 * {@code SAVEPOINT / RELEASE SAVEPOINT / ROLLBACK TO SAVEPOINT} —— 事务控制语句，
 * 必须在 {@code markPlateInTransfer} 拿到的**同一条裸 Connection** 上执行
 * （MyBatis mapper 无法对外层 Spring 事务打 savepoint），属有意保留，勿再收编。
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

    /** 延迟取 Mapper 代理（new 出来的工具类，无构造注入；见 Mappers）。 */
    private static com.labos.kernel.persistence.QueryMapper qm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class);
    }

    /** 位置锁/设备态走 DeviceStateMapper（同一个懒取约定）。 */
    private static com.labos.kernel.persistence.DeviceStateMapper dm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceStateMapper.class);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PositionLock.class);

    /** 默认预约时长：搬运正常几秒，留足余量。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(120);

    /**
     * 板号别名优先级（权威键 {@code barcode} 优先）。
     *
     * <p>搬运事务（{@code DriverStepExecutor}）与位置链路（{@code writeStep → resolvePlateId}）
     * 原先读的键集合不相交：前者只读 {@code plate}，后者只读
     * {@code plate_id/plateID/plateId/barcode}。只写 {@code plate} 时搬运表有板号、
     * 位置链路却仍兜底成 {@code AUTO-<runId>}。故收敛为这一份清单，两边共用。
     */
    public static final String[] PLATE_BARCODE_KEYS = {
            "barcode", "plate", "plateBarcode", "plateId", "plateID", "plate_id"};

    /**
     * M 方案自动确认开关：按 workflow 生效，**默认全关**。
     *
     * <p>为什么不能做成全局布尔：全库 23 个 workflow 含 Transfer 节点，目前只有
     * 1000830 配了板号；全局开启会让启动预检把另外 22 个流程一并阻断。
     * 取值：空=关；{@code 1/true/*}=全局；逗号分隔的 workflow id = 仅这些流程。
     */
    public static boolean autoConfirmEnabledFor(long workflowId) {
        return parseAutoConfirmScope(
                System.getenv("LABOS_TRANSFER_AUTO_CONFIRM"), workflowId);
    }

    /** 纯逻辑部分（{@code System.getenv} 运行时不可改，抽出来才能自测覆盖）。 */
    public static boolean parseAutoConfirmScope(String v, long workflowId) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String t = v.trim();
        if ("1".equals(t) || "true".equalsIgnoreCase(t) || "*".equals(t)) {
            return true;
        }
        for (String s : t.split(",")) {
            if (s.trim().equals(String.valueOf(workflowId))) {
                return true;
            }
        }
        return false;
    }

    /** 位置状态快照（给前端画图用）。 */
    public record PositionState(
            long resourceId, String code, String name, String kind,
            String plateId, boolean reserved, Long reservedByRun,
            String occupiedSince,
            /** 属于哪台设备（孵箱/存储塔），null 表示直接挂在岛上。 */
            Long parentDeviceId, String parentDeviceCode,
            /** P2：点位语义（未配置的老点位为 null）。 */
            String pointRole, Boolean robotReachable, Boolean isEntry, Boolean isExit,
            String contentKind, Long plateUid, String identityStatus,
            String contentCode, String contentName,
            Integer gridRow, Integer gridCol, Double maxHeightMm, String plateTypes,
            /** 现场贴的位置标识（空 = 前端回退显示 code）。 */
            String positionLabel,
            /** true = 网格之外的单独位置（培养箱/堆栈的进出口装载口）。 */
            Boolean standalone) {

        /** 可用 = 没有板 且 没有有效预约。 */
        public boolean available() {
            return (plateId == null || plateId.isBlank()) && !reserved;
        }

        /** 兼容旧调用方（只要前 10 个字段）。 */
        public PositionState(long resourceId, String code, String name, String kind,
                             String plateId, boolean reserved, Long reservedByRun,
                             String occupiedSince, Long parentDeviceId,
                             String parentDeviceCode) {
            this(resourceId, code, name, kind, plateId, reserved, reservedByRun,
                    occupiedSince, parentDeviceId, parentDeviceCode,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
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

        if (dm().reservePositionForever(tenantId, resourceId, runId) > 0) {
            return ReserveResult.OK;
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
        // P1-4（DeviceStateMapper.reservePosition）：判定能不能占看**谁占着**——
        // 只按 reserved_until IS NULL 判空闲会出错："回程承诺"恰恰是
        // reserved_until=NULL + reserved_by_run 非空 —— 于是承诺位被当成空位抢走。
        // 新三态：reserved_by_run IS NULL → 真空闲；非空且过期 → 可占；
        //         非空且 until IS NULL → 永久承诺，不可占。
        if (dm().reservePosition(tenantId, resourceId, life.toSeconds() + " seconds", runId) > 0) {
            return ReserveResult.OK;
        }

        // ③ 没占到，判断原因（这里可以非原子，只是给出可读的错误）
        Map<String, Object> occupant = dm().selectPositionOccupant(tenantId, resourceId);
        if (occupant != null) {
            String plate = (String) occupant.get("plateId");
            if (plate != null && !plate.isBlank()) {
                return ReserveResult.OCCUPIED_BY_PLATE;
            }
            return ReserveResult.RESERVED_BY_OTHER;
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

    /**
     * P0 安全：机械臂动作**之前**为目标设备原子预留一个可达装载口。
     *
     * <p>为什么必须事前预留：此前 {@code TransferPlan.acquireAll(srcRes, null,
     * dstRes, null, ...)} 的位置参数全为 null，{@code acquirePosition} 直接 return；
     * 位置选位发生在 {@code driver.execute} 之后的 {@code noteTransferMove}。
     * 两个并发 run 会选到同一个空载位，机械臂物理上都往那里放板 —— 生产级相撞。
     *
     * <p>本方法用 {@code tryReserve} 的一条原子 UPSERT（带 TTL + 归属）抢占；
     * 抢不到时按用户口径「等待 + 提醒」，超时才拒绝转人工（不静默放行、也不无限等）。
     * 预留后续由 {@code putPlateOnPosition} 写占用时自动清除，中间无窗口。
     *
     * @return 预留成功的位置 id；超时/中断返回 null（调用方必须拒绝搬运并转人工）
     */
    public Long reserveTargetPosition(long deviceResourceId, long runId,
                                      long timeoutMs) throws SQLException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean warned = false;
        for (;;) {
            Long candidate = firstAvailablePosition(deviceResourceId);
            if (candidate != null
                    && tryReserve(candidate, runId, Duration.ofMinutes(30))
                    == ReserveResult.OK) {
                return candidate;
            }
            if (!warned) {
                log.warn("[搬运] 目标设备 {} 暂无可用装载口（或已被别的 run 预留），等待中…",
                        deviceResourceId);
                warned = true;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("[搬运] 等待装载口超时 {}ms，拒绝搬运并转人工", timeoutMs);
                return null;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 释放预约（不板——板还在位置上）。 */
    public void release(long resourceId, long runId) throws SQLException {
        dm().releasePosition(tenantId, resourceId, runId);
    }

    /** 释放某个 run 的全部预约（run 结束时兜底）。 */
    public int releaseAll(long runId) throws SQLException {
        return dm().releaseAllPositions(tenantId, runId);
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
            // **留痕**：板子放进了哪个位置 —— 这是"板子怎么流转"的唯一来源
            auditPlateMove(plateId, "IN_TRANSFER", "KNOWN", resourceId, runId, "放板");
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
        try {
            if (dm().updatePlateLocation(tenantId, plateId, positionResourceId) == 0) {
                dm().insertPlateIfAbsent(tenantId, plateId, positionResourceId);
            }
            // P3：临时板号补一条 alias 指向刚建/已有的 plate.id。扫码后只需再加一条
            // canonical 真条码别名，历史证据表里的 TMP- 字符串一律不改写。
            if (plateId != null && plateId.startsWith("TMP-")) {
                dm().insertPlateAlias(tenantId, plateId, "TEMP", false, runId,
                        "点位 " + positionResourceId + " 上未扫码的板");
            }
        } catch (RuntimeException e) {
            log.error("[PLATE-WARN] 冗余表 plate 写入失败（不影响位置锁）："
                    + e.getMessage());
        }
    }

    /** 位置变化后，刷新它所属设备的 has_plate。 */
    private void refreshHasPlateOfPosition(long positionResourceId) {
        try {
            Long pid = qm().selectParentIdOfNode(tenantId, positionResourceId);
            if (pid != null) {
                refreshHasPlate(pid);
            }
        } catch (SQLException | RuntimeException ignore) {
            // 刷新失败不影响主流程，下次变动会再算
        }
    }

    private int putPlateInternal(long resourceId, String plateId, long runId) throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            // 用 UPSERT 而不是纯 UPDATE（DeviceStateMapper.putPlateOnPosition）：
            // position_state 里可能还没有这一行（tryReserve 没插或插失败），
            // 纯 UPDATE 会静默影响 0 行，表现为"返回了位置但层位上没板"。
            int n = dm().putPlateOnPosition(tenantId, resourceId, plateId, runId);
            // 不在主事务里写 plate 表：那张表是冗余，列名一变就会
            // 让整个事务作废，把 position_state 的更新一起回滚。
            // 改为 commit 之后用独立连接写（见 putPlate 包装）。
            return n;
        });
    }

    /** 登记"板取走了"：位置变空。 */
    public int takePlate(long resourceId, long runId) throws SQLException {
        // 取走前先记住是哪个板（取完位置就空了）
        String plate = plateOnPosition(resourceId);
        int n = takePlateInternal(resourceId, runId);
        if (n > 0 && plate != null && !plate.isBlank()) {
            // **留痕**：板子从哪个位置被取走了
            auditPlateMove(plate, "KNOWN", "IN_TRANSFER", resourceId, runId, "取板");
        }
        refreshHasPlateOfPosition(resourceId);
        return n;
    }

    /** 这个位置上现在放着哪个板（没有就 null）。 */
    private String plateOnPosition(long resourceId) {
        try {
            return dm().selectPlateOnPosition(tenantId, resourceId);
        } catch (RuntimeException e) {
            log.error("[WARN] 读位置上的板失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * **写一条板子流转审计**（{@code plate_location_audit}）。
     *
     * <p><b>为什么必须有</b>：这张表以前**只在人工盘点时写**
     * （{@code PauseManager} 的"未知板/丢失板"处置）⇒ 执行过程中
     * 板子被放进哪个设备、又被搬到哪儿去，**一条痕迹都没有**。
     * 现场问"PL-001 现在在哪、怎么过来的"，答不上来。
     *
     * <p><b>为什么不在主事务里写</b>：跟 {@code plate} 表同理 ——
     * 审计写失败不该把位置锁的更新一起回滚（表现是"日志说放板成功，层位上没板"）。
     * ⇒ 独立连接写，失败只记日志。
     *
     * <p>{@code operator} 用 {@code AUTO} 区分「自动流转」与「人工盘点」。
     */
    private void auditPlateMove(String barcode, String from, String to,
                                long positionResourceId, long runId, String action) {
        if (barcode == null || barcode.isBlank()) {
            return;
        }
        try {
            String location = positionCode(positionResourceId);
            String note = action + (location == null ? "" : " @ " + location);
            com.labos.kernel.persistence.Mappers.get(
                            com.labos.kernel.persistence.PauseMapper.class)
                    .insertPlateAudit(tenantId, barcode, from, to, location,
                            "AUTO", note, runId);
        } catch (RuntimeException e) {
            // 审计写不进去不该影响执行（数据少了比流程停了好）
            log.error("[PLATE-WARN] 板子流转审计写入失败 " + barcode + ": " + e.getMessage());
        }
    }

    /** 位置的编码（{@code resource_node.code}）；查不到就返回 null。 */
    private String positionCode(long positionResourceId) {
        try {
            return qm().selectNodeCode(tenantId, positionResourceId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int takePlateInternal(long resourceId, long runId) throws SQLException {
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            String plate = dm().selectPlateOnPosition(tenantId, resourceId);
            // ⚠ **归属校验**（2026-11-10，DeviceStateMapper.clearPlateFromPosition）：
            //   位置上的板是谁放上去的，`occupied_by_run` 记着；取板只允许取**自己的**。
            //   以前这条约束是靠「岛级互斥」隐含保证的（岛内同时只有一个实验在跑，
            //   所以设备上的板必然是自己的）—— 岛内并行一放开，这个隐含保证就没了：
            //   A 把板放进孵箱、B 对同一台设备做 Withdraw，就会把 A 的板取走。
            //   读不到归属（occupied_by_run 为空）时按**取不到**处理（fail-closed）：
            //   宁可报「没板可取」让节点显式转人工，也不要静默搬走别人的板。
            int n = dm().clearPlateFromPosition(tenantId, resourceId, runId);
            if (n > 0 && plate != null) {
                markPlateInTransfer(c, plate, runId);
            }
            return n;
        });
    }

    /** 清掉过期预约（定时任务调）。返回清理条数。 */
    public int sweepExpired() throws SQLException {
        return dm().sweepExpiredReservations(tenantId);
    }

    /** 某个岛下所有位置的占用状态（前端画图用）。 */
    public List<PositionState> byIsland(long islandId) throws SQLException {
        List<PositionState> out = new ArrayList<>();
        for (Map<String, Object> row : dm().selectPositionsByIsland(tenantId, islandId)) {
            java.sql.Timestamp until = (java.sql.Timestamp) row.get("reservedUntil");
            boolean reserved = until != null && until.after(new java.util.Date());
            Object rb = row.get("reservedByRun");
            java.sql.Timestamp occ = (java.sql.Timestamp) row.get("occupiedSince");
            Object pid = row.get("parentId");
            out.add(new PositionState(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("code"),
                    (String) row.get("name"),
                    (String) row.get("kind"),
                    (String) row.get("plateId"),
                    reserved,
                    rb == null ? null : ((Number) rb).longValue(),
                    occ == null ? null : occ.toLocalDateTime().toString(),
                    pid == null ? null : ((Number) pid).longValue(),
                    (String) row.get("parentCode"),
                    (String) row.get("pointRole"),
                    (Boolean) row.get("robotReachable"),
                    (Boolean) row.get("isEntry"),
                    (Boolean) row.get("isExit"),
                    (String) row.get("contentKind"),
                    row.get("plateUid") == null ? null
                            : ((Number) row.get("plateUid")).longValue(),
                    (String) row.get("identityStatus"),
                    (String) row.get("contentCode"),
                    (String) row.get("contentName"),
                    row.get("gridRow") == null ? null
                            : ((Number) row.get("gridRow")).intValue(),
                    row.get("gridCol") == null ? null
                            : ((Number) row.get("gridCol")).intValue(),
                    row.get("maxHeightMm") == null ? null
                            : ((Number) row.get("maxHeightMm")).doubleValue(),
                    (String) row.get("plateTypes"),
                    (String) row.get("positionLabel"),
                    (Boolean) row.get("standalone")));
        }
        return out;
    }

    /** 位置类型（不在允许集合内返回 null）。 */
    private String positionKind(long resourceId) throws SQLException {
        return dm().selectPositionKind(tenantId, resourceId);
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
     * 整个 takePlate 由 Spring 事务回滚（原实现靠手写 commit 抛错）。
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
            dm().markPlateInTransfer(tenantId, barcode);
            if (hasSavepoint) {
                try (PreparedStatement rel = c.prepareStatement("RELEASE SAVEPOINT " + sp)) {
                    rel.execute();
                }
            }
        } catch (RuntimeException | SQLException e) {
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
        dm().refreshDeviceHasPlate(tenantId, deviceResourceId);
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
    /**
     * 板号**单一真源**解析器：搬运事务与位置链路都必须调它。
     *
     * <p>只接受字符串条码，三类值一律拒绝（否则会造出不存在的板）：
     * <ul>
     *   <li>数值型：material 声明的 {@code target.plateId} 是 bigint {@code plate.id}，
     *       {@code String.valueOf} 会把 123 变成假条码 "123"，
     *       {@code insertPlateIfAbsent} 会据此建板并撞 {@code uk_plate_barcode}；</li>
     *   <li>{@code ${...}}：变量未被 {@code substVars} 替换时会落成字面量条码；</li>
     *   <li>{@code AUTO-...}：占位板号会让并行分支的两块板共用一个假身份。</li>
     * </ul>
     *
     * @return 合法板号，或 {@code null}（调用方决定兜底，禁止把 null 当身份落库）
     */
    public static String resolvePlateBarcode(java.util.Map<String, Object> params,
                                             String rawResponse) {
        if (params != null) {
            for (String k : PLATE_BARCODE_KEYS) {
                Object v = params.get(k);
                if (v == null) {
                    continue;
                }
                if (v instanceof CharSequence s) {
                    String t = s.toString().trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (t.startsWith("${") || t.startsWith("AUTO-")) {
                        log.warn("[PLATE] 忽略非法板号 {}（键 {}）", t, k);
                        continue;
                    }
                    return t;
                }
                log.warn("[PLATE] 忽略非字符串板号 {}（键 {}，类型 {}）：plate.id 不是条码",
                        v, k, v.getClass().getSimpleName());
            }
        }
        if (rawResponse != null) {
            // 设备返回里常见字段：plate_id / barcode / plateBarcode
            for (String k : new String[]{"plate_id", "plateBarcode", "barcode", "plateId"}) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"" + k + "\"\\s*:\\s*\"([^\"]+)\"").matcher(rawResponse);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty() && !v.startsWith("${") && !v.startsWith("AUTO-")) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 兼容旧调用（{@code writeStep} 的日志/占位路径）：解析不到时仍返回
     * {@code AUTO-<runId>}。**自动确认链路不得用这个方法**——它必须用
     * {@link #resolvePlateBarcode} 并显式判 null。
     */
    public static String resolvePlateId(java.util.Map<String, Object> params,
                                        String rawResponse, long runId) {
        String barcode = resolvePlateBarcode(params, rawResponse);
        return barcode != null ? barcode : "AUTO-" + runId;
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
        Long deviceResource = qm().selectDeviceResourceByInstance(deviceInstanceId);
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
            // P3：未知板按「点位」生成 TMP- 临时身份，同一 run 多板不再共用一个
            // AUTO-{runId}（后者受 uk_plate_barcode 唯一约束会串账）。
            String effective = plateId;
            if (effective == null || effective.isBlank() || effective.startsWith("AUTO-")) {
                effective = "TMP-" + runId + "-" + pos + "-1";
            }
            int written = putPlate(pos, effective, runId);
            if (written <= 0) {
                // 写入 0 行 = 层位上还是没板。不能返回 pos 骗调用方，
                // 否则日志说"放板成功"、层位上空着，取板时取不到。
                log.error("[PLATE-WARN] putPlate 写入 0 行，位置 " + pos
                        + " 未更新（板号 " + plateId + "）");
                return NO_EMPTY_POSITION;
            }
            return pos;
        }

        // withdraw：找第一个**本 run 放上去的**有板位置
        // 同样不直接写 has_plate，由 takePlate 内部的 refreshHasPlate 派生
        // ⚠ 归属过滤在 firstOccupiedPosition 里（2026-11-10）：只取自己的板。
        //   这台设备上有别人的板时，这里返回 null → NO_PLATE_TO_TAKE，
        //   节点显式转人工，而不是静默搬走别人的板。
        Long pos = firstOccupiedPosition(deviceResource, runId);
        if (pos == null) {
            return NO_PLATE_TO_TAKE;
        }
        takePlate(pos, runId);
        return pos;
    }

    /** 一次 Transfer 的位置登记结果（供上层判定能否自动确认）。 */
    public record TransferMove(boolean ok, String reason, String plate,
                               Long srcPosition, Long dstPosition, boolean guided) {
    }

    /**
     * M 方案：搬运成功后登记「源位释放 + 目标位占用」。
     *
     * <p>复用既有 {@code takePlate/putPlate} —— 它们自带
     * {@code plate_location_audit} 两行（取板 KNOWN→IN_TRANSFER / 放板
     * IN_TRANSFER→KNOWN）、{@code occupied_by_run} 归属校验与
     * {@code device_state.has_plate} 派生刷新；这里不另写裸 INSERT。
     *
     * <p>{@code ok=false} 时调用方**必须**保持 transfer_transaction 为
     * REPORTED/UNKNOWN 并转人工 confirm，绝不能据此置 CONFIRMED。
     *
     * <p>源位分三种情况：本 run 的板 → 正常取板；确实无板 → 记为「首次引导」
     * （简化流程起点 position_state 尚未初始化）；有别人的板 → 拒绝。
     * 不区分第三种会凭空在目标位复制出一块板。
     */
    /** 兼容旧调用：未传预预留位置时自行选位（仅用于非 protect 路径）。 */
    public TransferMove noteTransferMove(long srcDeviceRes, long dstDeviceRes,
                                         String plate, long runId) throws SQLException {
        return noteTransferMove(srcDeviceRes, dstDeviceRes, null, plate, runId);
    }

    /**
     * @param reservedDstPos 机械臂动作前已原子预留的目标位置；非 null 时**直接使用**，
     *                       不再 {@code firstAvailablePosition} 事后选位（防并发相撞）。
     *                       {@code putPlateOnPosition} 写占用时会自动清掉该预留。
     */
    public TransferMove noteTransferMove(long srcDeviceRes, long dstDeviceRes,
                                         Long reservedDstPos, String plate, long runId)
            throws SQLException {
        if (plate == null || plate.isBlank() || plate.startsWith("AUTO-")) {
            return new TransferMove(false, "板号缺失或为 AUTO- 占位",
                    plate, null, null, false);
        }

        // ① 源位
        Long srcPos = firstOccupiedPosition(srcDeviceRes, runId);
        boolean guided = false;
        if (srcPos == null) {
            Long any = dm().selectFirstOccupiedPositionAny(tenantId, srcDeviceRes);
            if (any != null) {
                return new TransferMove(false,
                        "源位有板但不属于本 run（层位 " + any + "），禁止自动确认",
                        plate, null, null, false);
            }
            guided = true;
        } else {
            String onSrc = plateOnPosition(srcPos);
            if (onSrc != null && !onSrc.isBlank() && !onSrc.equals(plate)) {
                return new TransferMove(false,
                        "源位上是 " + onSrc + "，与声明板号 " + plate + " 不一致，禁止自动确认",
                        plate, srcPos, null, false);
            }
        }

        // ② 目标位：优先用事前原子预留的位置，绝不事后重选（否则并发会选到同一格）。
        Long dstPos = reservedDstPos != null
                ? reservedDstPos : firstAvailablePosition(dstDeviceRes);
        if (dstPos == null) {
            return new TransferMove(false, "目标设备没有空层位",
                    plate, srcPos, null, guided);
        }

        // ②a 板型/高度校验不在这里做：机械臂到达的是装载口（通常无约束），
        // 约束配置在 internal 内部格上。校验已移到 DeviceInternalTransfer
        // 收到设备进站通知、知道真实格位之后才执行（见交接文档 §10.3）。
        // 此处保留校验会让两处口径混淆，已清理。

        // ③ 取板（首次引导时源位本来就没板，跳过）
        if (!guided) {
            int taken = takePlate(srcPos, runId);
            if (taken <= 0) {
                return new TransferMove(false, "取板影响 0 行（归属校验未通过）",
                        plate, srcPos, dstPos, false);
            }
        }

        // ④ 放板
        int put = putPlate(dstPos, plate, runId);
        if (put <= 0) {
            return new TransferMove(false, "放板影响 0 行（目标位已被占用）",
                    plate, srcPos, dstPos, guided);
        }
        // 统一「点位内容」真源：putPlateOnPosition 只写 plate_id，补写 content_*。
        // 板尚未建立身份（TMP- 等）时跳过，不影响位置登记。
        Long uid = dm().selectPlateUidByBarcode(tenantId, plate);
        if (uid != null) {
            dm().markPositionPlateContent(tenantId, dstPos, uid);
        }

        // ⑤ 目标位落的确是这块板
        String onDst = plateOnPosition(dstPos);
        if (!plate.equals(onDst)) {
            return new TransferMove(false, "目标位板号不一致：" + onDst,
                    plate, srcPos, dstPos, guided);
        }

        // ⑥ 源/目标设备的 has_plate 都派生刷新
        refreshHasPlateOfPosition(dstPos);
        if (!guided) {
            refreshHasPlateOfPosition(srcPos);
        }
        return new TransferMove(true,
                guided ? "首次引导：源位无板，直接占用目标位" : "取板+放板均成功且板号一致",
                plate, srcPos, dstPos, guided);
    }

    /**
     * 放板前校验板型/高度（点位配置了约束才拦，空约束 = 不限制）。
     *
     * @return null=通过；否则返回拒绝原因（用于 TransferMove.reason）
     */
    public String checkPlateFits(long resourceId, String plate) {
        if (plate == null || plate.isBlank()
                || plate.startsWith("TMP-") || plate.startsWith("AUTO-")) {
            return null;   // 临时/占位身份跳过
        }
        try {
            java.util.Map<String, Object> pt = dm().selectPlateType(tenantId, plate);
            if (pt == null) {
                return null;   // 板不存在（可能尚未建身份）→ 不拦
            }
            java.util.Map<String, Object> cons = dm().selectPointConstraints(tenantId, resourceId);
            if (cons == null) {
                return null;
            }
            String plateTypes = (String) cons.get("plateTypes");
            if (plateTypes != null && !plateTypes.isBlank()) {
                String plateType = (String) pt.get("plateType");
                boolean hit = false;
                for (String s : plateTypes.split(",")) {
                    if (s.trim().equals(plateType)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return "板型 " + plateType + " 不在点位允许板型 [" + plateTypes + "]";
                }
            }
            Object maxH = cons.get("maxHeightMm");
            Object plateH = pt.get("heightMm");
            if (maxH instanceof Number && plateH instanceof Number
                    && ((Number) plateH).doubleValue() > ((Number) maxH).doubleValue()) {
                return "板高 " + plateH + "mm 超过点位最大 " + maxH + "mm";
            }
            return null;
        } catch (RuntimeException e) {
            // 校验失败不应阻断搬运（约束查询是附加保护，不是主链）——但失败要显式告警
            log.error("[PLATE-FIT] 校验异常（放行）: " + e.getMessage());
            return null;
        }
    }

    /** 扫码类方法：设备回包里会带真 barcode。 */
    public static boolean isBarcodeMethod(String method) {
        if (method == null) {
            return false;
        }
        return "ReadBarcode".equals(method) || "BarcodeScan".equals(method)
                || "BarcodeScanEx".equals(method) || "Scan".equals(method)
                || method.startsWith("Barcode");
    }

    /**
     * P3：扫码成功后，把该设备当前放着的板（可能是 {@code TMP-} 临时身份）
     * 关联到回包里的真 barcode。
     *
     * <p>不改写任何历史字符串：只新增一条 canonical alias + 升级 identity_status。
     * 回包里没有合法 barcode、设备上没有板、或真 barcode 属于另一块板时返回 false。
     */
    public boolean confirmScanAtDevice(long deviceInstanceId, String rawResponse, long runId) {
        String scanned = resolvePlateBarcode(null, rawResponse);
        if (scanned == null) {
            return false;
        }
        Long devRes = qm().selectDeviceResourceByInstance(deviceInstanceId);
        if (devRes == null) {
            return false;
        }
        // P2-4：设备下有多块板时无法凭空定位到具体层位，拒绝关联（避免错板）。
        long platesOnDevice = dm().selectPlateCountOnDevice(tenantId, devRes);
        if (platesOnDevice != 1L) {
            log.warn("[扫码] 设备 {} 下有 {} 块板，无法唯一关联到具体层位，跳过（保持 TMP- 身份）",
                    devRes, platesOnDevice);
            return false;
        }
        String current = dm().selectPlateIdOnDevice(tenantId, devRes);
        if (current == null || current.isBlank()) {
            return false;
        }
        return com.labos.kernel.material.PlateIdentity
                .confirmScanned(tenantId, current, scanned, runId);
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
        // A2 修复（评审 20261102-04，DeviceStateMapper.selectFirstAvailablePosition）：
        // 这里改动前还是二态 `reserved_until IS NULL OR < now()`，而 tryReserve 已改成
        // 看 reserved_by_run。两处判定不一致的后果：**永久承诺位（reserved_by_run 非空
        // + reserved_until 为 NULL）会被这里当成空位选出来**，然后 tryReserve 又抢不到
        // —— 表现为"选的位子总是抢失败"。
        return dm().selectFirstAvailablePosition(tenantId, deviceResourceId);
    }

    /** 设备下第一个有板的位置。 */
    private Long firstOccupiedPosition(long deviceResourceId, long runId) throws SQLException {
        // ⚠ **归属过滤**（2026-11-10，DeviceStateMapper.selectFirstOccupiedPosition）：
        //   只认**本 run 放上去的板**。以前这台设备上的板必然是自己的（岛级互斥隐含
        //   保证），岛内并行后不成立 —— 不过滤会把别的实验的板取走。
        //   归属为空（读不到）时按「不认」处理（fail-closed）。
        return dm().selectFirstOccupiedPosition(tenantId, deviceResourceId, runId);
    }

    /** 占位统计（给监控用）。 */
    public Map<String, Object> stats() throws SQLException {
        // reserved 含永久承诺位（A2 残留修复，评审 20261102-05）——只数
        // `reserved_until > now()` 会漏掉 reserved_by_run 非空 + until 为 NULL 的
        // 承诺位，"已预约"监控数字恒比实际少。
        Map<String, Object> row = dm().selectPositionStats(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("withPlate", ((Number) row.get("withPlate")).intValue());
        m.put("reserved", ((Number) row.get("reserved")).intValue());
        m.put("tracked", ((Number) row.get("tracked")).intValue());
        return m;
    }

}
