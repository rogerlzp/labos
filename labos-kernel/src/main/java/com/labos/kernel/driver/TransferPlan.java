package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次搬运的资源申请计划。
 *
 * <h3>固定全序（这是防死锁的核心）</h3>
 * <pre>
 * ① 源设备锁 + 源设备的门（开）
 * ② 源通道
 * ③ 源位置
 * ④ 目标设备锁 + 目标设备的门（开）
 * ⑤ 目标通道
 * ⑥ 目标位置
 * ⑦ 回程位置（position-return，暂停也不释放）
 * ⑧ 机械臂
 *    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *    到此为止所有资源到手；板仍在源位，可安全放弃
 *    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * ⑨ 抓板（不可逆点）
 * ⑩ 关门之后再释放源／目标设备锁
 * </pre>
 *
 * <p><b>为什么门排最前</b>：门开了但没抢到机械臂，可以关门放弃，代价小。
 * 反过来——抢了机械臂才发现门打不开——板已经在爪子上，没法放回去。
 *
 * <p><b>为什么顺序必须固定</b>：固定全序消除跨类型的循环等待。
 * 按 id 升序只在同类资源内有效，门/通道/位置/机械臂之间必须显式规定。
 *
 * <h3>失败时怎么回滚</h3>
 * 任何一步申请失败 → **逆序释放已申请的全部** → 板还在源位，状态不变，可安全重试。
 *
 * <h3>释放时要注意什么</h3>
 * <b>回程位置（position-return）不能释放</b>——它是"承诺"不是"预留"。
 * 释放了别人的板就能占进去，回程时无处可放，死锁复现。
 */
public final class TransferPlan implements AutoCloseable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TransferPlan.class);

    private final DbConfig db;
    private final long tenantId;
    private final long runId;

    // 申请到的资源（按申请顺序，用于逆序释放）
    private final List<Resource> acquired = new ArrayList<>();

    /**
     * 本次搬运涉及的两台设备与开门器工厂。
     *
     * <p><b>P1-3 修复（评审 20261102-03）</b>：{@code releaseAll} 里
     * {@code case ACCESS -> { /* 门由调用方在 finally 里关 *\/ }} 是**空操作**，
     * 而 {@code closeAccesses} 只在**成功路径**的 finally 调。
     *
     * <p>后果：源门已开、目标门申请失败 → 抛 AcquireException →
     * <b>源门一直开着没人管</b>。现场门敞开，且 {@code device_access.state}
     * 停留在 open：下一次 ensureOpen 会跳过开门动作，但门可能已被人工关掉 ——
     * 机械臂伸进去就是撞机。
     *
     * <p>所以回滚必须把门关上。关门要下发命令，故把 factory 存下来。
     */
    private long rollbackSourceRes = -1;
    private long rollbackTargetRes = -1;
    private AccessOpenerFactory rollbackFactory;

    private DevicePortLock portLock;
    private DeviceAccessManager accessManager;

    /**
     * 全局等待图。**必须是单例**——每个 TransferPlan 各持一个就失去意义了，
     * 因为环是跨执行形成的。
     */
    private static final WaitForGraph GRAPH = new WaitForGraph();

    public static WaitForGraph graph() {
        return GRAPH;
    }

    /** 一种已申请的资源。 */
    private record Resource(Type type, long id) {
        enum Type { DEVICE, ACCESS, PORT, POSITION, RETURN, ROBOT }
    }

    /** 申请失败。调用方应重试或转人工。 */
    public static final class AcquireException extends RuntimeException {
        private final String step;

        public AcquireException(String step, String reason) {
            super("申请 " + step + " 失败：" + reason);
            this.step = step;
        }

        public String step() {
            return step;
        }
    }

    public TransferPlan(DbConfig db, long tenantId, long runId) {
        this.db = db;
        this.tenantId = tenantId;
        this.runId = runId;
        this.portLock = new DevicePortLock(db, tenantId);
        this.accessManager = new DeviceAccessManager(db, tenantId);
    }

    /**
     * 按固定全序申请全部资源。
     *
     * <p>**全部成功才返回** —— 返回后调用方才可以抓板。
     * 中途失败会抛 {@link AcquireException}，并已回滚。
     */
    public TransferPlan acquireAll(long sourceResourceId,
                                   Long sourcePositionId,
                                   long targetResourceId,
                                   Long targetPositionId,
                                   Long returnPositionId,
                                   Long robotResourceId,
                                   AccessOpenerFactory factory)
            throws SQLException {
        // P1-3：记下来，失败回滚时要把这些门关回去
        this.rollbackSourceRes = sourceResourceId;
        this.rollbackTargetRes = targetResourceId;
        this.rollbackFactory = factory;
        try {
            // ① 源设备的门
            acquireAccess(sourceResourceId, "源门", factory);
            // ② 源通道
            acquirePort(sourceResourceId, "in", "源通道");
            // ③ 源位置
            acquirePosition(sourcePositionId, "源位置");
            // ④ 目标设备的门
            acquireAccess(targetResourceId, "目标门", factory);
            // ⑤ 目标通道
            acquirePort(targetResourceId, "in", "目标通道");
            // ⑥ 目标位置
            acquirePosition(targetPositionId, "目标位置");
            // ⑦ 回程位置（承诺，不可释放）
            acquireReturn(returnPositionId);
            // ⑧ 机械臂
            acquireRobot(robotResourceId);
            return this;
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    // ---------------------------------------------------------------- 各步申请

    private void acquireAccess(long resourceId, String label,
                               AccessOpenerFactory factory) {
        DeviceAccessManager.Access a = accessManager.accessOf(resourceId);
        if (a == null) {
            return;   // 没门，跳过
        }
        // 门不是独立物理设备：开门到关门期间必须独占整台源/目标设备。
        // 否则另一条 run 能在搬运开门后抢进 RunProgram，撞到门未关闭。
        DeviceLock deviceLock = new DeviceLock(db, tenantId);
        if (!deviceLock.tryAcquire(resourceId, runId)) {
            Long holder = lockHolder(resourceId);
            if (holder != null && holder != runId && GRAPH.waitFor(runId, holder)) {
                GRAPH.release(runId);
                throw new AcquireException(label + "设备",
                        "会死锁（等待成环），已拒绝。持有者 run=" + holder);
            }
        }
        if (!deviceLock.acquireWithWait(resourceId, runId, 60_000)) {
            GRAPH.release(runId);
            throw new AcquireException(label + "设备", "被占用超过 60 秒");
        }
        GRAPH.release(runId);
        // 先登记再开门：开门失败时 rollback 也必须释放设备锁。
        acquired.add(new Resource(Resource.Type.DEVICE, resourceId));

        // **开门命令必须发给这台设备自己的驱动**，不是机械臂的。
        // 用同一把 mechanical driver 去开别的设备的门，真机上会开错门。
        AccessOpener opener = factory == null ? null : factory.forDevice(resourceId);
        String err = accessManager.ensureOpen(a, opener == null ? null : opener::open);
        if (err != null) {
            throw new AcquireException(label, err);
        }
        acquired.add(new Resource(Resource.Type.ACCESS, a.id()));
    }

    private void acquirePort(long resourceId, String direction, String label) {
        if (!portLock.hasPorts(resourceId)) {
            return;   // 没配通道（存量数据），跳过
        }
        Long portId = portLock.findAvailablePort(resourceId, direction);
        if (portId == null) {
            throw new AcquireException(label, "都满了");
        }
        if (!portLock.tryAcquire(portId)) {
            throw new AcquireException(label, "瞬时竞争（另一个执行刚抢走）");
        }
        acquired.add(new Resource(Resource.Type.PORT, portId));
    }

    private void acquirePosition(Long positionId, String label) {
        if (positionId == null) {
            return;   // 不需要占位置（比如只查询）
        }
        PositionLock.ReserveResult r;
        try {
            r = new PositionLock(db, tenantId)
                    .tryReserve(positionId, runId, java.time.Duration.ofMinutes(30));
        } catch (SQLException e) {
            throw new AcquireException(label, "数据库错误：" + e.getMessage());
        }
        if (r != PositionLock.ReserveResult.OK) {
            // 被别的 run 预约时，看看加边会不会成环
            if (r == PositionLock.ReserveResult.RESERVED_BY_OTHER) {
                Long holder = positionHolder(positionId);
                if (holder != null && holder != runId && GRAPH.waitFor(runId, holder)) {
                    throw new AcquireException(label,
                            "会死锁（等待成环），已拒绝。持有者 run=" + holder);
                }
            }
            throw new AcquireException(label, r.name());
        }
        acquired.add(new Resource(Resource.Type.POSITION, positionId));
    }

    private void acquireReturn(Long positionId) {
        if (positionId == null) {
            return;   // 不预留回程
        }
        // P1-4：回程位是**承诺**不是**预留**，不能带 TTL ——
        // TTL 到期会被 sweepExpired 清掉，别的板占进来，回程时死锁复现。
        PositionLock.ReserveResult r;
        try {
            r = new PositionLock(db, tenantId)
                    .tryReserveForever(positionId, runId);
        } catch (SQLException e) {
            throw new AcquireException("回程位置", "数据库错误：" + e.getMessage());
        }
        if (r != PositionLock.ReserveResult.OK) {
            throw new AcquireException("回程位置", r.name());
        }
        acquired.add(new Resource(Resource.Type.RETURN, positionId));
    }

    private void acquireRobot(Long robotResourceId) {
        if (robotResourceId == null) {
            return;
        }
        DeviceLock robotLock = new DeviceLock(db, tenantId);

        // 先试一次（不等），失败就查持有者、检测环
        if (!robotLock.tryAcquire(robotResourceId, runId)) {
            Long holder = lockHolder(robotResourceId);
            if (holder != null && holder != runId) {
                // 加边并检测：会成环就立刻拒绝，不傻等 60 秒
                if (GRAPH.waitFor(runId, holder)) {
                    throw new AcquireException("机械臂",
                            "会死锁（等待成环），已拒绝。持有者 run=" + holder);
                }
            }
        }

        // 无环才进入等待
        if (!robotLock.acquireWithWait(robotResourceId, runId, 60_000)) {
            GRAPH.release(runId);
            throw new AcquireException("机械臂", "被占用超过 60 秒");
        }
        GRAPH.release(runId);   // 拿到了，不再等
        acquired.add(new Resource(Resource.Type.ROBOT, robotResourceId));
    }

    /** 谁预约着这个位置。 */
    private Long positionHolder(long positionId) {
        try {
            return com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionReservedBy(tenantId, positionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 谁占着这台设备。 */
    private Long lockHolder(long resourceId) {
        return new DeviceLock(db, tenantId).holder(resourceId);
    }

    // ---------------------------------------------------------------- 释放

    /**
     * 逆序释放。
     *
     * <p><b>回程位置不释放</b>（除非调用方显式要求）——
     * 它是"承诺"，板还没回家，释放了就会被别人占。
     */
    @Override
    public void close() {
        releaseAll(true);
    }

    /** 抓板失败等场景：连回程位置一起放掉（板没离开源位）。 */
    public void abortBeforeGrab() {
        releaseAll(false);
    }

    private void releaseAll(boolean keepReturn) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            Resource r = acquired.get(i);
            if (keepReturn && r.type() == Resource.Type.RETURN) {
                continue;   // 回程承诺保持
            }
            try {
                switch (r.type()) {
                    case PORT -> portLock.release(r.id());
                    case DEVICE, ROBOT -> new DeviceLock(db, tenantId).release(r.id(), runId);
                    case POSITION, RETURN -> {
                        try {
                            new PositionLock(db, tenantId).release(r.id(), runId);
                        } catch (SQLException e) {
                            log.error("[搬运] 释放位置失败：" + e.getMessage());
                        }
                    }
                    case ACCESS -> { /* 门由调用方在 finally 里关，见 closeAccesses */ }
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.error("[搬运] 释放 " + r.type() + " 失败：" + e.getMessage());
            }
        }
        acquired.clear();
        GRAPH.release(runId);   // 不再等任何东西
    }

    /** 关掉这次涉及的门。要在动作全部完成后调（含异常路径）。 */
    public void closeAccesses(long sourceResourceId, long targetResourceId,
                              AccessOpenerFactory factory) {
        if (factory == null) {
            return;
        }
        for (long rid : new long[]{targetResourceId, sourceResourceId}) {
            DeviceAccessManager.Access a = accessManager.accessOf(rid);
            if (a == null) {
                continue;
            }
            AccessOpener opener = factory.forDevice(rid);
            String warn = accessManager.close(a, opener == null ? null : opener::open);
            if (warn != null) {
                log.error("[搬运] " + warn);
            }
        }
    }

    private void rollback() {
        releaseAll(false);   // 还没抓板，连回程位置一起放
        // P1-3：把已开的门关回去。顺序与开门相反（先目标后源），
        // 避免"两台都开着时又有人进来"。
        if (rollbackFactory != null && rollbackSourceRes > 0 && rollbackTargetRes > 0) {
            try {
                closeAccesses(rollbackSourceRes, rollbackTargetRes, rollbackFactory);
            } catch (RuntimeException e) {
                log.error("[搬运] 回滚关门失败（门可能仍开着）：" + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- 门控调用接口

    /**
     * 门的开合需要下发命令，但 {@code driver} 包不能依赖 {@code workflow}。
     * 用这个函数式接口把"调命令"的能力注入进来。
     */
    @FunctionalInterface
    public interface AccessOpener {
        DriverResult open(String method);
    }

    /**
     * 按设备找它的开合方法调用器。
     *
     * <p><b>为什么需要这一层</b>：一次 Transfer 涉及两台设备的门，
     * 每台设备有**自己的 driver**。用机械臂的 driver 去开离心机的门，
     * 真机上会开错门（甚至打到不存在的接口）。
     */
    @FunctionalInterface
    public interface AccessOpenerFactory {
        AccessOpener forDevice(long resourceId);
    }
}
