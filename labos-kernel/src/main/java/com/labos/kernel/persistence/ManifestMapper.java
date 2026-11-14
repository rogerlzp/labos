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

    /** §4.5.2：清掉「本次实验专属（lifecycle=process）且 loaded」物料占用的点位状态。 */
    int clearProcessPositionStates(@Param("t") long tenantId, @Param("runId") long runId);

    /** §4.5.2：把上述 placement 置 unloaded（lifecycle=inventory 的保持原位不动）。 */
    int markProcessPlacementsUnloaded(@Param("t") long tenantId, @Param("runId") long runId);

    // ---------------------------------------------------------------- 三条件

    /** 该 run 涉及的所有设备是否在线（C1）。返回离线设备码列表。 */
    List<String> selectOfflineDevices(@Param("t") long tenantId, @Param("runId") long runId);

    /** 该 run 未核验的 required 物料数（C3）。 */
    long countUnverifiedPlacements(@Param("t") long tenantId, @Param("runId") long runId);
}
