package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 孪生差分报告持久层：仿真器通过 POST /api/twin/diffs 推送，页面只读。
 *
 * <p>append-only：没有 update / delete。幂等靠 (tenant_id, report_uid) 唯一键，
 * 重复推送由 Service 先查后插，不产生第二条记录。
 */
public interface TwinDiffMapper {

    /** 报告列表（摘要列，不含 payload）。 */
    List<Map<String, Object>> selectReports(long tenantId, int limit);

    /** 按数字 id 取单份完整报告。 */
    Map<String, Object> selectReportById(long tenantId, long id);

    /** 按 report_uid 查重（幂等）。 */
    Map<String, Object> selectReportByUid(long tenantId, String reportUid);

    /** 插入一份报告，返回受影响行数（幂等由唯一键 + Service 先查保证）。 */
    int insertReport(@Param("tenantId") long tenantId,
                     @Param("reportUid") String reportUid,
                     @Param("jobId") String jobId,
                     @Param("deviceId") String deviceId,
                     @Param("sourceKind") String sourceKind,
                     @Param("sourceDetail") String sourceDetail,
                     @Param("status") String status,
                     @Param("jobStatus") String jobStatus,
                     @Param("payload") String payload,
                     @Param("createdBy") String createdBy);
}
