package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.ExperimentQueryMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import static com.labos.kernel.persistence.QJson.*;
import com.labos.kernel.persistence.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实验查询域的只读 Service：{@code ExperimentQueryController} 的九个查询端点。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} 拆出，
 * SQL 归 {@link ExperimentQueryMapper}。纯只读，无事务。
 */
@Service
public class ExperimentQueryService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final ExperimentQueryMapper experimentQueryMapper;

    public ExperimentQueryService(DbConfig db, ExperimentQueryMapper experimentQueryMapper) {
        this.db = db;
        this.experimentQueryMapper = experimentQueryMapper;
    }


    /** 实验编排图（nodes + edges）。 */
    public String experimentGraphJson(long experimentId) throws java.sql.SQLException {
        String g = experimentQueryMapper.selectExperimentGraph(TENANT, experimentId);
        return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
    }

    /** N-10：跨实验 Handoff 处置列表（可按状态过滤）。 */
    public String handoffsJson(String status, int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        String statusFilter = status == null || status.isBlank() ? null : status;
        for (java.util.Map<String, Object> row : experimentQueryMapper.selectHandoffs(
                TENANT, statusFilter, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "experimentId", row.get("experimentId"),
                    "experimentNo", row.get("experimentNo"),
                    "fromWorkcellId", row.get("fromWorkcellId"),
                    "fromName", row.get("fromName"),
                    "toWorkcellId", row.get("toWorkcellId"),
                    "toName", row.get("toName"),
                    "status", row.get("status"),
                    "carrier", row.get("carrier"),
                    "remark", row.get("remark")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验 Handoff 配置列表（N-10）。 */
    public String experimentHandoffsJson(long experimentId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentHandoffs(TENANT, experimentId)) {
            items.add(Jsons.obj("fromWorkcellId", row.get("fromWorkcellId"),
                    "toWorkcellId", row.get("toWorkcellId"),
                    "carrier", row.get("carrier"),
                    "status", row.get("status"),
                    "remark", row.get("remark")));
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)), "count", items.size());
    }

    /** 实验显式 Workcell 绑定（N-10）。 */
    public String experimentWorkcellsJson(long experimentId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentWorkcells(TENANT, experimentId)) {
            items.add(Jsons.obj("workcellId", row.get("workcellId"),
                    "name", row.get("name")));
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)), "count", items.size());
    }

    public String experimentRunStepsJson(long runId) throws java.sql.SQLException {
        java.util.Map<String, Object> header =
                experimentQueryMapper.selectExperimentRunHeader(TENANT, runId);
        if (header == null) {
            throw new IllegalArgumentException("实验执行不存在: " + runId);
        }
        long expId = ((Number) header.get("experimentId")).longValue();
        String runStatus = String.valueOf(header.get("status"));
        int expanded = mint(header, "expandedSteps");

        // 计划：每条流程 × 循环次数
        java.util.List<long[]> plan = new ArrayList<>();          // [seq, loop]
        java.util.Map<String, String> wfName = new java.util.HashMap<>();
        java.util.Map<Integer, Long> wfId = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentProcessPlan(TENANT, expId)) {
            int seq = mint(row, "seq");
            long wf = ((Number) row.get("workflowId")).longValue();
            wfId.put(seq, wf);
            wfName.put(seq + ":" + wf,
                    row.get("name") == null ? null : String.valueOf(row.get("name")));
            int times = 1;
            Object loopObj = row.get("loopCount");
            String loop = loopObj == null ? null : String.valueOf(loopObj);
            if (loop != null && loop.trim().matches("\\d+")) {
                times = Math.max(1, Integer.parseInt(loop.trim()));
            }
            for (int i = 0; i < times; i++) {
                plan.add(new long[]{seq, i});
            }
        }

        // 实际执行行
        java.util.Map<String, String[]> done = new java.util.HashMap<>();   // "seq:loop" → [status,runId,err]
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentRunStepRows(TENANT, runId)) {
            Object runObj = row.get("runId");
            done.put(mint(row, "seq") + ":" + mint(row, "loopIndex"), new String[]{
                    row.get("status") == null ? null : String.valueOf(row.get("status")),
                    runObj == null ? null : String.valueOf(((Number) runObj).longValue()),
                    row.get("error") == null ? null : String.valueOf(row.get("error"))});
        }

        List<String> items = new ArrayList<>();
        int okCnt = 0;
        int badCnt = 0;
        int pendingCnt = 0;
        for (long[] p : plan) {
            int seq = (int) p[0];
            int loop = (int) p[1];
            String[] d = done.get(seq + ":" + loop);
            String st = d == null ? "pending" : d[0];
            if ("completed".equals(st)) {
                okCnt++;
            } else if ("failed".equals(st)) {
                badCnt++;
            } else if ("pending".equals(st)) {
                pendingCnt++;
            }
            items.add(Jsons.obj(
                    "seq", seq,
                    "loopIndex", loop,
                    "workflowId", wfId.get(seq),
                    "workflowName", wfName.get(seq + ":" + wfId.get(seq)),
                    "status", st,
                    "runId", d == null || d[1] == null ? null : Long.valueOf(d[1]),
                    "error", d == null ? null : d[2]));
        }

        return Jsons.obj("runId", runId, "status", runStatus,
                "expandedSteps", expanded,
                "completed", okCnt, "failed", badCnt, "pending", pendingCnt,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentRunsJson(long experimentId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentRuns(TENANT, experimentId)) {
            Object dur = row.get("durationSec");
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "status", row.get("status"),
                    "expandedSteps", mint(row, "expandedSteps"),
                    "currentSeq", mint(row, "currentSeq"),
                    "startedAt", mstr(row, "startedAt"),
                    "finishedAt", mstr(row, "finishedAt"),
                    "durationSec", dur instanceof Number n ? Math.round(n.doubleValue()) : null,
                    "pauseKind", row.get("pauseKind"),
                    "pauseReason", row.get("pauseReason"),
                    "controlState", row.get("controlState"),
                    "stopRequested", Boolean.TRUE.equals(row.get("stopRequested")),
                    "error", row.get("error")));
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 实验变量列表。 */
    public String experimentVariablesJson(long experimentId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentVariables(TENANT, experimentId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "name", row.get("name"),
                    "type", row.get("type"),
                    "defaultValue", row.get("defaultValue"),
                    "description", row.get("description")));
        }
        return Jsons.obj("experimentId", experimentId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentProcessesJson(long experimentId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperimentProcesses(TENANT, experimentId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "workflowId", row.get("workflowId"),
                    "workflowName", row.get("workflowName"),
                    "workflowCode", row.get("workflowCode"),
                    // seq 用输出序号而不是库里的 seq：原实现如此（列表序号从 1 起）
                    "seq", items.size() + 1,
                    "runMode", row.get("runMode"),
                    "runAfter", mint(row, "runAfter"),
                    "nodeCount", mint(row, "nodeCount")));
        }
        return Jsons.obj("experimentId", experimentId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String experimentsJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        // 列表页要能一眼看出：哪个岛、什么内容、第几版、编了几条流程
        for (java.util.Map<String, Object> row
                : experimentQueryMapper.selectExperiments(TENANT, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "experimentNo", row.get("experimentNo"),
                    "name", row.get("name"),
                    "status", row.get("status"),
                    "plannedAt", mstr(row, "plannedAt"),
                    "startedAt", mstr(row, "startedAt"),
                    "finishedAt", mstr(row, "finishedAt"),
                    "workflowId", row.get("workflowId"),
                    "workflowName", row.get("workflowName"),
                    "version", mint(row, "version"),
                    "priority", mint(row, "priority"),
                    "remark", row.get("remark"),
                    "islandCode", row.get("islandCode"),
                    "islandName", row.get("islandName"),
                    "workcellNames", row.get("workcellNames"),
                    "flowCount", mint(row, "flowCount")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

}
