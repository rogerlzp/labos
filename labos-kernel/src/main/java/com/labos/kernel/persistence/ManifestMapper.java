package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * P1 · 实验模板 / 物料清单 / 物料位置配置（manifest）的读写。
 * 语句见 {@code mapper/ManifestMapper.xml}。
 */
public interface ManifestMapper {

    // ---------------------------------------------------------------- 实验模板

    List<Map<String, Object>> selectTemplates(@Param("t") long tenantId,
                                              @Param("islandId") Long islandId,
                                              @Param("status") String status);

    Map<String, Object> selectTemplate(@Param("t") long tenantId, @Param("id") long id);

    int insertTemplate(@Param("t") long tenantId,
                       @Param("templateNo") String templateNo,
                       @Param("name") String name,
                       @Param("islandId") long islandId,
                       @Param("workflowIds") String workflowIds,
                       @Param("spec") String specJson);

    int updateTemplate(@Param("id") long id,
                       @Param("name") String name,
                       @Param("status") String status,
                       @Param("spec") String specJson);

    int deleteTemplate(@Param("t") long tenantId, @Param("id") long id);

    /**
     * 模板编辑器的两个"整包替换"字段。
     *
     * <p>为什么不塞进 {@link #updateTemplate}：那是**部分更新**语义（name/status/spec
     * 各自可空），而流程清单与点位白名单在界面上是"整张表提交"⇒ 语义不同，混在一起
     * 会让"只改名字"意外清空流程清单。
     */
    int updateTemplateWhitelist(@Param("id") long id, @Param("whitelist") String whitelistJson);

    int updateTemplateWorkflowIds(@Param("id") long id, @Param("workflowIds") String workflowIds);

    /**
     * 模板自检：一条流程的概况（是否存在 / 是否软删 / 岛 / 任务节点数）。
     *
     * <p>用一条查询而不是"先查 workflow 再查 node"：自检要对每个流程各查一次，
     * 两次往返会让"模板自检"在多流程模板上明显变慢。
     */
    Map<String, Object> selectWorkflowBrief(@Param("t") long tenantId,
                                            @Param("workflowId") long workflowId);

    /**
     * 模板自检：点位所属的岛（资源树向上走到 kind='island'）。
     *
     * <p>资源节点没有 island_id 列（岛就是树根），所以必须递归上行 —— 这也保证了
     * "白名单里的点位属于这个岛"是真判定，而不是拿 code 前缀猜。
     */
    Long selectPointIsland(@Param("t") long tenantId, @Param("pointId") long pointId);

    // ---- 模板导出 / 导入（跨环境搬运：一切用**业务码**，不用本环境的 id） ----

    /** 资源节点（岛 / 设备）的 code。 */
    String selectResourceCodeById(@Param("t") long tenantId, @Param("id") long id);

    /** 流程 code 是否已存在（导入时重名要改名，不能撞 uk_workflow）。 */
    Long selectWorkflowIdByCode(@Param("t") long tenantId, @Param("code") String code);

    /** 岛 code → 岛资源 id（导入时定位目标岛）。 */
    Long selectIslandIdByCode(@Param("t") long tenantId, @Param("code") String code);

    /** 点位 code（可带设备 code 消歧）→ device_point.id。 */
    Long selectPointIdByCode(@Param("t") long tenantId,
                             @Param("pointCode") String pointCode,
                             @Param("deviceCode") String deviceCode);

    /** device_point.id → 点位 code（导出用）。 */
    String selectPointCodeById(@Param("t") long tenantId, @Param("pointId") long pointId);

    /** 设备 code + 方法名 → device_method.id（方法按型号登记，scoped model）。 */
    Long selectMethodIdByDeviceAndName(@Param("t") long tenantId,
                                       @Param("deviceCode") String deviceCode,
                                       @Param("methodName") String methodName);

    /** 物料角色 code 是否存在（导入时决定是警告还是静默）。 */
    Long selectMaterialRoleIdByCode(@Param("t") long tenantId,
                                    @Param("roleCode") String roleCode);

    /** 导出：一条流程的全部绑定（点位/方法都用 code 表达）。 */
    List<Map<String, Object>> selectBindingsForExport(@Param("t") long tenantId,
                                                      @Param("workflowId") long workflowId);

    List<Map<String, Object>> selectTemplateProcesses(@Param("t") long tenantId,
                                                      @Param("templateId") long templateId);

    int insertTemplateProcess(@Param("t") long tenantId,
                              @Param("templateId") long templateId,
                              @Param("seq") int seq,
                              @Param("workflowId") long workflowId,
                              @Param("params") String paramsJson);

    int deleteTemplateProcesses(@Param("t") long tenantId, @Param("templateId") long templateId);

    // ---------------------------------------------------------------- 物料清单（模板侧）

    List<Map<String, Object>> selectManifestItems(@Param("t") long tenantId,
                                                  @Param("templateId") long templateId);

    int insertManifestItem(@Param("t") long tenantId,
                           @Param("templateId") long templateId,
                           @Param("seq") int seq,
                           @Param("roleCode") String roleCode,
                           @Param("materialKind") String materialKind,
                           @Param("materialRef") Long materialRef,
                           @Param("quantity") double quantity,
                           @Param("required") boolean required);

    int deleteManifestItems(@Param("t") long tenantId, @Param("templateId") long templateId);

    // ---------------------------------------------------------------- 实例化 + 位置配置

    int insertExperiment(@Param("t") long tenantId,
                         @Param("experimentNo") String experimentNo,
                         @Param("name") String name,
                         @Param("islandId") long islandId,
                         @Param("templateId") long templateId,
                         @Param("spec") String specJson);

    Long selectExperimentIdByNo(@Param("t") long tenantId, @Param("experimentNo") String experimentNo);

    int insertExperimentRun(@Param("t") long tenantId, @Param("experimentId") long experimentId);

    Long selectExperimentRunId(@Param("t") long tenantId, @Param("experimentId") long experimentId);

    /** 把模板的 manifest_item 复制成某 run 的 manifest_placement（unassigned）。 */
    int insertPlacementFromTemplate(@Param("t") long tenantId,
                                    @Param("templateId") long templateId,
                                    @Param("runId") long runId);

    List<Map<String, Object>> selectPlacements(@Param("t") long tenantId, @Param("runId") long runId);

    int updatePlacementPosition(@Param("t") long tenantId,
                                @Param("placementId") long placementId,
                                @Param("positionId") long positionId);

    int updatePlacementVerify(@Param("t") long tenantId,
                              @Param("placementId") long placementId,
                              @Param("verifiedBy") long verifiedBy);

    /** §4.5.2：清掉「本次实验专属（lifecycle=process）且 unloading」物料占用的点位状态（确认卸载时）。 */
    int clearProcessPositionStates(@Param("t") long tenantId, @Param("runId") long runId);

    /** R12 请求卸载：loaded → unloading（位置仍占用）。 */
    int markProcessPlacementsUnloading(@Param("t") long tenantId, @Param("runId") long runId);

    /** R12 确认卸载：unloading → unloaded（配合 clearProcessPositionStates 清位置）。 */
    int markProcessPlacementsUnloaded(@Param("t") long tenantId, @Param("runId") long runId);

    /** R12 批次请求卸载（跨所有批次 run）。 */
    int markProcessPlacementsUnloadingByExperiment(@Param("t") long tenantId,
                                                    @Param("experimentId") long experimentId);

    /** §4.5.2 批次：按实验（跨所有批次 run）清点位状态。 */
    int clearProcessPositionStatesByExperiment(@Param("t") long tenantId,
                                                @Param("experimentId") long experimentId);

    /** §4.5.2 批次：按实验（跨所有批次 run）置 unloaded。 */
    int markProcessPlacementsUnloadedByExperiment(@Param("t") long tenantId,
                                                   @Param("experimentId") long experimentId);

    // ---------------------------------------------------------------- 三条件

    /** 该 run 涉及的所有设备是否在线（C1）。返回离线设备码列表。 */
    List<String> selectOfflineDevices(@Param("t") long tenantId, @Param("runId") long runId);

    /** 该 run 未核验的 required 物料数（C3）。 */
    long countUnverifiedPlacements(@Param("t") long tenantId, @Param("runId") long runId);
}
