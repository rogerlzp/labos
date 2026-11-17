package com.labos.boot.web.service;

import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.TwinDiffMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 孪生差分报告 Service：仿真器推送落库，前端只读查询。
 *
 * <p>唯一写入方纪律：只有 POST /api/twin/diffs 能写这张表；没有 update/delete。
 * 幂等靠 (tenant_id, report_uid)：同一份报告重推返回原记录，不产生第二条。
 * 容错的边界：报告字段缺失一律拒绝（400），但**不猜、不补默认值**——
 * 差分报告的字段是仿真器契约的一部分，猜出来的字段会让对账失去意义。
 */
@Service
public class TwinDiffService {

    private static final long TENANT = 1L;
    private static final int MAX_LIMIT = 200;

    private final TwinDiffMapper mapper;

    public TwinDiffService(TwinDiffMapper mapper) {
        this.mapper = mapper;
    }

    /** 报告列表（摘要，不含 payload）。 */
    public String list(int limit) {
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        List<String> items = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectReports(TENANT, n)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "reportUid", row.get("reportUid"),
                    "jobId", row.get("jobId"),
                    "deviceId", row.get("deviceId"),
                    "sourceKind", row.get("sourceKind"),
                    "status", row.get("status"),
                    "jobStatus", row.get("jobStatus"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                         "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 单份完整报告（payload 是 jsonb，取出来直接当 Raw 嵌回响应）。 */
    public String detail(long id) {
        Map<String, Object> row = mapper.selectReportById(TENANT, id);
        if (row == null) {
            return null;
        }
        Object payload = row.get("payload");
        String payloadJson = payload instanceof String s ? s : String.valueOf(payload);
        return Jsons.obj(
                "id", row.get("id"),
                "reportUid", row.get("reportUid"),
                "jobId", row.get("jobId"),
                "deviceId", row.get("deviceId"),
                "sourceKind", row.get("sourceKind"),
                "sourceDetail", row.get("sourceDetail"),
                "status", row.get("status"),
                "jobStatus", row.get("jobStatus"),
                "createdBy", row.get("createdBy"),
                "createdAt", row.get("createdAt"),
                "report", new Jsons.Raw(payloadJson));
    }

    /** 推送一份报告。返回 "created" 或 "duplicate"。 */
    public String push(String reportUid, String jobId, String deviceId,
                       String sourceKind, String sourceDetail, String status,
                       String jobStatus, String payload, String createdBy) {
        Map<String, Object> exists = mapper.selectReportByUid(TENANT, reportUid);
        if (exists != null) {
            return Jsons.obj("inserted", 0, "duplicate", true,
                             "id", exists.get("id"), "reportUid", reportUid);
        }
        mapper.insertReport(TENANT, reportUid, jobId, deviceId,
                sourceKind, sourceDetail, status, jobStatus, payload, createdBy);
        Map<String, Object> saved = mapper.selectReportByUid(TENANT, reportUid);
        return Jsons.obj("inserted", 1, "duplicate", false,
                         "id", saved == null ? null : saved.get("id"),
                         "reportUid", reportUid);
    }
}
