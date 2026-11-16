package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 设备运行态与通道占用（HeartbeatService / DevicePortLock 的裸 SQL）。
 *
 * <p>两个调用方都是 new 出来的工具类，用 {@link Mappers#get} 取代理。
 */
public interface DeviceStateMapper {

    /** 驱动报 method_status=1：标记运行中，并记住 current_run_id（不覆盖已有值）。 */
    int markRunning(long tenantId, long deviceInstanceId, String method, Long runId);

    /** 执行完成/失败：清 current_method，写 last_error，status 由调用方决定。 */
    int markIdle(long tenantId, long deviceInstanceId, String status, String lastError);

    /**
     * resource_node.id -> device_instance.id。
     *
     * <p>只取未软删的设备、按 id 升序取第一条 —— 与
     * {@code WorkflowWebMapper.selectDeviceInstanceId} **不同**：那个不带
     * {@code deleted_at} 过滤。两处不能混用。
     */
    Long selectInstanceIdByResource(long resourceId);

    /** 设备编号 -> resource_node.id（params 里的 source/target 换算）。 */
    Long selectResourceIdByCode(long tenantId, String code);

    /** 设备方法是否可中断；查不到返回 null。 */
    Boolean selectMethodInterruptible(long tenantId, long deviceInstanceId, String method);

    /** 层位被哪个 run 预占（position_state.reserved_by_run）。 */
    Long selectPositionReservedBy(long tenantId, long resourceId);

    /** 层位被哪个 run 占用（position_state.occupied_by_run）；空位返回 null。 */
    Long selectPositionOccupiedBy(long tenantId, long resourceId);

    /**
     * P1 精确点位：按 spec 解析某设备下的位置资源 id（resource_node.id）。
     * spec 可为：device_point.id（数字）、resource_node.code、position_label、slot_index。
     * 查不到返回 null。
     */
    Long selectPositionResourceBySpec(@Param("tenantId") long tenantId,
                                      @Param("deviceResourceId") long deviceResourceId,
                                      @Param("spec") String spec);

    /**
     * 按**点位码**（resource_node.code，如 {@code STACK1-S09}）全局解析位置资源 id。
     *
     * <p>{@link #selectPositionResourceBySpec} 是**设备内**解析（带 device_resource_id 约束），
     * 只能解析"该设备自己的点位"；而扫码节点扫的是**别的设备上的板**
     * （机械臂去耗材栈的磁套槽扫码）⇒ 必须全局解析。
     *
     * <p>返回 {@code point_resource_id} —— 与运行期的位置 id 空间一致。
     */
    Long selectPositionResourceByPointCode(@Param("tenantId") long tenantId,
                                            @Param("pointCode") String pointCode);

    /** 谁占着这台设备（device_state.current_run_id）；空表示空闲。 */
    Long selectCurrentRunId(long tenantId, long resourceId);

    /** 门禁状态回写（设备访问控制）。 */
    int updateAccessState(long tenantId, long accessId, String state);

    /** 某设备某方法的超时上限（秒），无配置返回 0。 */
    long selectMethodTimeoutSec(long tenantId, long deviceInstanceId, String method);

    /** 该设备全部方法里的超时上限（秒），无配置返回 0。 */
    long selectLibraryTimeoutSec(long tenantId, long deviceInstanceId);

    /**
     * 设备的真实能力编码（resource_capability），查不到返回 null。
     *
     * <p>⚠ {@code resource_capability} 表**没有 tenant_id 列** —— 带上会抛
     * SQLException（"column rc.tenant_id does not exist"），异常被调用方吞掉
     * 就静默返回 null、能力回落成节点上猜出来的那个。
     */
    String selectCapabilityCodeOfInstance(long tenantId, long deviceInstanceId);

    /** 流程节点级超时（秒），无配置返回 0。 */
    long selectWorkflowNodeTimeoutSec(long tenantId, long workflowNodeId);

    /** device_instance.id -> resource_node.id 全量映射（只取映射到的）。 */
    java.util.List<java.util.Map<String, Object>> selectInstanceResourcePairs();

    /** 全部资源节点 id（校验工作流节点引用的资源是否还在）。 */
    java.util.List<Long> selectAllResourceIds();

    /** 设备有没有配通道（没配说明是存量数据，跳过通道锁）。 */
    boolean hasPorts(long tenantId, long resourceId);

    /** 当前占用数；没有该 port 行时返回 null。 */
    Integer selectPortInUse(long tenantId, long portId);

    /** 抢通道（占用数 +1，未满才成功，RETURNING 用 select 承载）；没抢到返回 null。 */
    Integer tryAcquirePort(@Param("tenantId") long tenantId, @Param("portId") long portId);

    /** 释放通道（占用数 -1，不会减到负数）。 */
    int releasePort(@Param("tenantId") long tenantId, @Param("portId") long portId);

    /**
     * 该设备指定方向的可用通道 id（both 方向可当 in/out 用；按 capacity 降序取第一个）；
     * 没配通道或都满了返回 null。
     */
    Long selectAvailablePort(@Param("tenantId") long tenantId,
                             @Param("resourceId") long resourceId,
                             @Param("direction") String direction);

    /** 有板设备的 device_instance.id 集合（WorkflowService 选设备的"忙集"）。 */
    java.util.List<Long> selectPlateBusyInstanceIds(@Param("tenantId") long tenantId);

    /** 岛（resource_node.parent_id）下全部未软删设备的 device_instance.id。 */
    java.util.List<Long> selectIslandInstanceIds(@Param("tenantId") long tenantId,
                                                 @Param("islandId") long islandId);

    /**
     * **离线**设备的 device_instance.id 集合。
     *
     * <p>在线状态来自 Redis（tcp client 的长连接），内核心跳把它写进 device_state。
     * 执行器用它守住「显式绑定设备」的节点：pinned 分支不经过能力候选池 ⇒
     * 候选池排除离线设备对它无效 ⇒ 离线设备仍会被派活，
     * 而命令走短连接可能照样发得出去 ⇒ 流程假成功。
     */
    java.util.List<Long> selectOfflineInstanceIds(@Param("tenantId") long tenantId);

    /**
     * resource_node.id -&gt; device_instance.id（本租户未软删、resource 非空）。
     *
     * <p>与 {@link #selectInstanceResourcePairs} 方向相反且带租户过滤 ——
     * 那是全量 id-&gt;resource 映射（驱动路由用），这是 resource-&gt;实例（节点绑定用）。
     */
    java.util.List<java.util.Map<String, Object>> selectResourceInstancePairs(
            @Param("tenantId") long tenantId);

    /**
     * method -&gt; capability_id 多数表决（rn=1 即每个 method 票数最高者；
     * 平票取 capability_id 小的，确定性可回放）。
     */
    java.util.List<java.util.Map<String, Object>> selectMethodCapabilityVotes();

    // ---- PositionLock（位置锁） ----

    /** 占位无期限（回程承诺）：reserved_until 落 NULL，只有持有者/人工能放。 */
    int reservePositionForever(long tenantId, long resourceId, long runId);

    /** 原子预约（TTL 秒数文本如 "120 seconds"，同一条件出现两次）。 */
    int reservePosition(long tenantId, long resourceId, String ttlSeconds, long runId);

    /** 占不到时的现场（plateId）；无行返回 null（map 为 null）。 */
    java.util.Map<String, Object> selectPositionOccupant(long tenantId, long resourceId);

    /** 释放预约（不动板）。 */
    int releasePosition(long tenantId, long resourceId, long runId);

    /** 释放某 run 的全部预约（run 结束兜底）；返回清理条数。 */
    int releaseAllPositions(long tenantId, long runId);

    /** UPSERT 登记"板放进去了"（预约转实际占用）。 */
    int putPlateOnPosition(long tenantId, long resourceId, String plateId, long runId);

    /** 层位当前板号（无板/无行返回 null，调用方只用于在途标记）。 */
    String selectPlateOnPosition(long tenantId, long resourceId);

    /** 取板（归属校验：只占本 run 放的；返回 0 = 取不到）。 */
    int clearPlateFromPosition(long tenantId, long resourceId, long runId);

    /** 清掉过期预约（定时任务）；返回清理条数。 */
    int sweepExpiredReservations(long tenantId);

    /**
     * P2 B-3：孤儿永久承诺——reserved_until IS NULL（永久语义）且持有 run 已结束
     * （终态/不存在）的位置。只读报警，不释放：板物理可能在那个位上，
     * 处置必须走 confirmWithPosition 或人工盘点（方案 §4.4）。
     */
    java.util.List<java.util.Map<String, Object>> selectOrphanedPromises(
            @Param("tenantId") long tenantId);

    /** 岛下所有层位占用状态（byIsland 画图用，键见 XML）。 */
    java.util.List<java.util.Map<String, Object>> selectPositionsByIsland(long tenantId,
                                                                          long islandId);

    /** 层位类型（kind::text）；不存在或类型不在允许集合返回 null（无行）。 */
    String selectPositionKind(long tenantId, long resourceId);

    /** 板标"在途"（SAVEPOINT 内调用，失败由调用方局部回滚）。 */
    int markPlateInTransfer(long tenantId, String barcode);

    /**
     * 批量取设备运行态（按设备实例 id）。
     *
     * <p>用途：**设备状态 → 运行时变量**的投影。
     * 设备状态住在 {@code device_state}（按 resource_node.id），
     * 而流程条件只能读扁平的变量表 ⇒ 不投影的话，"4 台提取仪是否都在裂解阶段"
     * 这种**设备状态驱动**的条件根本表达不了。
     *
     * <p>左连接 {@code device_state} ⇒ 没有状态行的设备也会出现（status 为 null），
     * 这样"设备存在但还没上报过状态"和"设备不存在"能区分开。
     */
    java.util.List<java.util.Map<String, Object>> selectStatesForInstances(
            @Param("tenantId") long tenantId,
            @Param("deviceInstanceIds") java.util.List<Long> deviceInstanceIds);
    /** 从 position_state 重算 device_state.has_plate（派生单一数据源）。 */
    int refreshDeviceHasPlate(long tenantId, long deviceResourceId);

    /** 设备下第一个真空闲层位（同 tryReserve 的三态判定）。 */
    Long selectFirstAvailablePosition(long tenantId, long deviceResourceId);

    /** 设备下第一个"本 run 放的板"的层位（归属过滤，fail-closed）。 */
    Long selectFirstOccupiedPosition(long tenantId, long deviceResourceId, long runId);

    /**
     * 设备下第一个有板的层位（**不限归属**）。
     *
     * <p>自动位置确认要区分「源位确实没板（首次搬运，可引导）」与
     * 「源位有别人的板（禁止自动确认）」；带 runId 的归属过滤查询两者都返回 null，
     * 无法区分，误判成引导会凭空在目标位复制一块板。
     */
    Long selectFirstOccupiedPositionAny(long tenantId, long deviceResourceId);

    /** P3 扫码接入：该设备下当前放着的板号（可能是 TMP- 临时身份）。 */
    String selectPlateIdOnDevice(long tenantId, long deviceResourceId);

    // ---- 设备内部搬运通知（装载口 ↔ 内部格） ----

    /** 清空一个点位的全部占用/预留/内容（不带 run 归属条件，设备内部机构搬运用）。 */
    int clearPositionForInternalMove(long tenantId, long resourceId);

    /** 写内部格：板 + 统一内容模型 + 归属，同时清掉预留。 */
    int setPositionForInternalMove(long tenantId, long resourceId, String plate,
                                   long contentId, long runId);

    /** 放板后补写统一内容模型（只动 content_*，不动 plate_id）。 */
    int markPositionPlateContent(long tenantId, long resourceId, long plateId);

    // ---- 实验开始前的起点板位引导 ----

    /** 这块板当前在哪个点位（null = 不在任何点位）。 */
    Long selectPositionByPlate(long tenantId, String plate);

    /**
     * 把「无归属」的点位认领给本 run（{@code occupied_by_run IS NULL} 才生效）。
     *
     * <p>fail-closed 的 {@code resolveSourcePositionBySpec} 要求源位归属本 run；
     * 历史/手工放进去的板没有归属，需要这一步才能被第一次搬运接受。
     * 已有归属（别人的板）返回 0，绝不抢占。
     */
    int claimPosition(long tenantId, long resourceId, long runId);

    /** P2-4：设备下有几块板（>1 时扫码无法凭空关联到具体层位）。 */
    long selectPlateCountOnDevice(long tenantId, long deviceResourceId);

    /** 放板前校验：点位的板型/高度约束。 */
    java.util.Map<String, Object> selectPointConstraints(long tenantId, long resourceId);

    /** 放板前校验：板的板型与高度。 */
    java.util.Map<String, Object> selectPlateType(long tenantId, String barcode);

    /** 占位统计（withPlate/reserved/tracked）。 */
    java.util.Map<String, Object> selectPositionStats(long tenantId);

    // ---- PositionLock 冗余 plate 表（独立连接，失败只记日志） ----

    /** 冗余表登记板位置（UPDATE 命中返回 1）。 */
    int updatePlateLocation(long tenantId, String plateId, long positionResourceId);

    /** 板不在 plate 表时补一行（'unknown' 类型占位）。 */
    int insertPlateIfAbsent(long tenantId, String plateId, long positionResourceId);

    // ---- DeviceLock（设备锁，阶段3第21批收编）----

    /**
     * 抢设备（检查+占用一条 UPDATE，原子性由数据库保证）：只有 current_run_id 为空、
     * 或已经是自己时才更新成功。抢到返回资源 id，没抢到/无行返回 null。
     */
    Long tryAcquireDevice(@Param("tenantId") long tenantId,
                          @Param("resourceId") long resourceId,
                          @Param("runId") long runId);

    /**
     * 确保这台设备在 {@code device_state} 里**有一行**（没有就建一行空闲态）。
     *
     * <p><b>为什么需要这一步</b>（2026-11-11 实测）：抢锁用的是
     * {@code UPDATE device_state SET current_run_id=... WHERE resource_id=...}
     * ⇒ 设备**在 device_state 里没有行**时，UPDATE 匹配 **0 行** ⇒ 抢锁永远失败
     * ⇒ 调用方只能等满超时（60 秒起步）再报「设备被占用超过 60 秒」——
     * **这是误导**：那台设备根本没人占，是它压根没有状态行
     * （实测库里 **43 台**有 device_instance 却没 device_state 行：多半没接驱动、
     * 心跳也覆盖不到）。⇒ 先建行，让执行走到真正的失败点
     * （发命令时明确报"没有驱动/设备不可达"），而不是干等一分钟再报个错的理由。
     *
     * <p>已有行则不动（{@code ON CONFLICT DO NOTHING}）⇒ 不覆盖真实状态。
     */
    int ensureDeviceState(@Param("tenantId") long tenantId,
                           @Param("resourceId") long resourceId);

    /**
     * 查这台设备的状态行（status / online / current_run_id / last_error）。
     *
     * <p>给"抢不到设备锁"时补一句现场状态用：光说"被占用"分不清
     * 「被别的 run 占着」与「这台设备压根没有状态行 / 已离线」。
     */
    java.util.Map<String, Object> selectDeviceStateRow(@Param("tenantId") long tenantId,
                                                       @Param("resourceId") long resourceId);

    /**
     * 释放设备（只释放自己占的，不误伤别人）：status 仅在无 last_error 时回落 idle。
     */
    int releaseDevice(@Param("tenantId") long tenantId,
                      @Param("resourceId") long resourceId,
                      @Param("runId") long runId);

    /** 清孤儿锁（current_run_id 指向的 run 已是终态）；返回清理条数。 */
    int releaseOrphanDeviceLocks(@Param("tenantId") long tenantId);

    /**
     * 清超时锁（updated_at 早于阈值）；ttlSeconds 为秒数文本（与 {@code ' seconds'}
     * 拼接成 interval，与原语句一致），返回清理条数。
     */
    int releaseStaleDeviceLocks(@Param("tenantId") long tenantId,
                                @Param("ttlSeconds") String ttlSeconds);

    /** DeviceAccessManager：设备的门配置（device_access 最早一行；无返回 null）。 */
    java.util.Map<String, Object> selectDeviceAccess(long tenantId, long resourceId);

    // ---- P2：实验开始前的点位与物料清单快照（append-only，幂等） ----

    /** 一次性把全部点位快照写入 run_position_snapshot；返回写入行数。 */
    int insertRunPositionSnapshot(long tenantId, long runId);

    /** 读取某 run 的启动前点位清单。 */
    java.util.List<java.util.Map<String, Object>> selectRunPositionSnapshot(
            long tenantId, long runId);

    // ---- P3：板身份别名层（位置参数，与 XML 的 paramN 对应） ----

    /** 真 barcode 或临时号统一解析到 plate_uid（plate.id）。 */
    Long selectPlateUidByBarcode(long tenantId, String barcode);

    /** 只看别名表（判重 / 冲突检测）。 */
    Long selectPlateUidByAlias(long tenantId, String alias);

    /** 给「plate 表里已有同码行」的临时号补一条 alias。 */
    int insertPlateAlias(long tenantId, String alias, String aliasKind,
                         boolean isCanonical, Long seenRunId, String note);

    /** 给指定 plate_uid 补一条 alias（扫码确认真 barcode 用）。 */
    int insertPlateAliasForUid(long tenantId, long plateUid, String alias,
                               String aliasKind, boolean isCanonical,
                               Long seenRunId, String note);

    /** 扫码确认后把板身份升级为 CONFIRMED（不改 barcode、不改历史证据字符串）。 */
    int markPlateIdentityConfirmed(long tenantId, long plateUid);

    // ---- 设备业务状态（客户文档 §1.5）：**读时派生**，不落库 ----

    /**
     * 设备清单（实例 → 资源 → **能力**）。
     *
     * <p>⚠ 能力是**能力口径**（EXTRACT/WASH/MIX/TRANSFER/STORE），
     * **不是** {@code device.device_type}（那是厂商登记的原始分类：
     * "Extractors"/"Robots"/… 复数、含空格、有空值 ⇒ 按它查字典会一条都查不到）。
     */
    java.util.List<java.util.Map<String, Object>> selectDeviceKindList(
            @Param("tenantId") long tenantId,
            @Param("islandId") Long islandId);

    /** 业务状态字典（含 trigger / source_key / is_idle），按能力分组。 */
    java.util.List<java.util.Map<String, Object>> selectBusinessStateDefs(
            @Param("tenantId") long tenantId);

    /** 每台设备**当前在跑**的命令（若有）。 */
    java.util.List<java.util.Map<String, Object>> selectRunningCommands(
            @Param("tenantId") long tenantId);

    /** 每台设备**最近一条已结束**的命令（成功/失败都要 —— 失败也是一次"做完"）。 */
    java.util.List<java.util.Map<String, Object>> selectLastFinishedCommands(
            @Param("tenantId") long tenantId);

    /** 每台设备**最近一条**设备事件（事件驱动的业务状态靠它）。 */
    java.util.List<java.util.Map<String, Object>> selectLatestDeviceEvents(
            @Param("tenantId") long tenantId);

    /**
     * 耗材栈槽位统计（**批量**，一次查所有资源）：resource_id → 槽位总数 / 空闲数。
     *
     * <p>⚠ 为什么必须批量：设备有几百台，**逐台查**就是几百次往返 ⇒ 端点慢到不可用。
     * ⇒ 一次 GROUP BY 拿全量。
     */
    java.util.List<java.util.Map<String, Object>> selectStackSlotStats(
            @Param("tenantId") long tenantId);

    /** 设备状态行（按资源）：在线/故障/当前方法 —— 业务状态的「故障优先」判定要用。 */
    java.util.List<java.util.Map<String, Object>> selectDeviceStates(
            @Param("tenantId") long tenantId);
}
