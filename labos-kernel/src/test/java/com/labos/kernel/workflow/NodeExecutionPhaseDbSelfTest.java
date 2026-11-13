package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.RunMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 节点执行阶段的数据库契约与计时回归。 */
public final class NodeExecutionPhaseDbSelfTest {
    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static RunMapper mapper;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        mapper = Mappers.get(RunMapper.class);
        long runId = insertRun();
        long nodeId = -1;
        try {
            nodeId = insertNode(runId);
            Map<String, Object> initial = row(nodeId);
            check("初始没有执行阶段", initial.get("execution_phase") == null);
            check("初始累计等待为零", ((Number) initial.get("wait_resource_ms")).longValue() == 0);

            mapper.updateNodeExecutionPhase(1L, nodeId, "WAITING_RESOURCE", runId, "等待设备");
            Map<String, Object> waiting = row(nodeId);
            check("等锁阶段可见", "WAITING_RESOURCE".equals(waiting.get("execution_phase")));
            check("等待阶段记录占用方", ((Number) waiting.get("blocked_by_run_id")).longValue() == runId);
            check("等待阶段尚无派发时间", waiting.get("dispatch_started_at") == null);
            check("等待阶段不写命令账本", ledgerCount(runId) == 0);
            Map<String, Object> waitingApi = apiNode(runId, nodeId);
            check("等待中的详情 API 暴露执行阶段",
                    "WAITING_RESOURCE".equals(waitingApi.get("executionPhase"))
                            && waitingApi.get("phaseSince") != null
                            && "等待设备".equals(waitingApi.get("blockedReason"))
                            && ((Number) waitingApi.get("blockedByRunId")).longValue() == runId);
            String serviceJson = new com.labos.boot.web.service.RunService(
                    db, null, null, mapper, null, null).runDetail(runId);
            check("运行详情 JSON 不过滤执行阶段",
                    serviceJson.contains("\"executionPhase\":\"WAITING_RESOURCE\"")
                            && serviceJson.contains("\"blockedReason\":\"等待设备\"")
                            && serviceJson.contains("\"waitResourceMs\":0"));

            Thread.sleep(35);
            mapper.updateNodeExecutionPhase(1L, nodeId, "DISPATCHING", null, null);
            Map<String, Object> dispatching = row(nodeId);
            check("获得锁后进入派发", "DISPATCHING".equals(dispatching.get("execution_phase")));
            check("获得锁与派发时间均已记录", dispatching.get("lock_acquired_at") != null
                    && dispatching.get("dispatch_started_at") != null);
            check("等待耗时单独累计", ((Number) dispatching.get("wait_resource_ms")).longValue() >= 10);
            check("派发后清占用方", dispatching.get("blocked_by_run_id") == null);

            Timestamp dispatchAt = (Timestamp) dispatching.get("dispatch_started_at");
            long waitMs = ((Number) dispatching.get("wait_resource_ms")).longValue();
            mapper.updateNodeExecutionPhase(1L, nodeId, "DISPATCHING", null, null);
            Map<String, Object> repeated = row(nodeId);
            check("重复派发阶段不重置开始时间", dispatchAt.equals(repeated.get("dispatch_started_at")));
            check("重复派发阶段不重复累计等待",
                    ((Number) repeated.get("wait_resource_ms")).longValue() == waitMs);

            Thread.sleep(35);
            mapper.updateNodeExecutionPhase(1L, nodeId, "FINALIZING", null, null);
            Map<String, Object> finalizing = row(nodeId);
            check("收到最终回包进入收尾", "FINALIZING".equals(finalizing.get("execution_phase")));
            check("最终回包时间已记录", finalizing.get("response_at") != null);
            check("设备执行耗时单独累计",
                    ((Number) finalizing.get("device_execution_ms")).longValue() >= 10);

            mapper.updateNodeExecutionPhase(1L, nodeId, null, null, null);
            Map<String, Object> cleared = row(nodeId);
            check("步骤结束清执行阶段", cleared.get("execution_phase") == null);
            check("清阶段后保留两类累计耗时",
                    ((Number) cleared.get("wait_resource_ms")).longValue() >= 10
                            && ((Number) cleared.get("device_execution_ms")).longValue() >= 10);

            mapper.updateNodeExecutionPhase(1L, nodeId, "WAITING_RESOURCE", runId, "第二步等待");
            Map<String, Object> secondWait = row(nodeId);
            check("下一步等待会清上一步回包时间", secondWait.get("response_at") == null);
            mapper.updateNodeExecutionPhase(1L, nodeId, "DISPATCHING", null, null);
            mapper.updateNodeResult(1L, runId, "PHASE_TEST", "completed", 0,
                    null, null, "{}");
            Map<String, Object> terminal = row(nodeId);
            check("终态清执行阶段", terminal.get("execution_phase") == null);
            check("终态不清审计耗时", ((Number) terminal.get("wait_resource_ms")).longValue() >= waitMs);

            Map<String, Object> api = apiNode(runId, nodeId);
            check("终态详情 API 清执行阶段", api.get("executionPhase") == null
                    && api.get("phaseSince") == null && api.get("blockedReason") == null);
            check("详情 API 返回累计等待", ((Number) api.get("waitResourceMs")).longValue() >= waitMs);
            check("全过程没有提前创建命令账本", ledgerCount(runId) == 0);
        } finally {
            deleteRun(runId);
            System.out.printf("通过 %d, 失败 %d%n", pass, fail);
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static long insertRun() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO run (tenant_id,run_no,run_type,status,mode,priority,created_at) "
                             + "VALUES (1,?,'workflow','running','normal',5,now()) RETURNING id")) {
            ps.setString(1, "SELF-NEP-" + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertNode(long runId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO run_node (tenant_id,run_id,node_key,name,seq,node_type,status,"
                             + "level,loop_index) VALUES (1,?,'PHASE_TEST','阶段自测',1,'task',"
                             + "'running',0,0) RETURNING id")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Map<String, Object> row(long nodeId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT execution_phase,blocked_by_run_id,lock_acquired_at,"
                             + "dispatch_started_at,response_at,wait_resource_ms,"
                             + "device_execution_ms FROM run_node WHERE id=?")) {
            ps.setLong(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> out = new java.util.HashMap<>();
                out.put("execution_phase", rs.getString("execution_phase"));
                long holder = rs.getLong("blocked_by_run_id");
                out.put("blocked_by_run_id", rs.wasNull() ? null : holder);
                out.put("lock_acquired_at", rs.getTimestamp("lock_acquired_at"));
                out.put("dispatch_started_at", rs.getTimestamp("dispatch_started_at"));
                out.put("response_at", rs.getTimestamp("response_at"));
                out.put("wait_resource_ms", rs.getLong("wait_resource_ms"));
                out.put("device_execution_ms", rs.getLong("device_execution_ms"));
                return out;
            }
        }
    }

    private static Map<String, Object> apiNode(long runId, long nodeId) throws Exception {
        for (Map<String, Object> node : mapper.selectRunDetailNodes(runId)) {
            if (((Number) node.get("id")).longValue() == nodeId) {
                return node;
            }
        }
        throw new AssertionError("详情 API 未返回自测节点");
    }

    private static int ledgerCount(long runId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM command_ledger WHERE run_id=?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void deleteRun(long runId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM run WHERE id=?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("清理失败 run=" + runId + ": " + e.getMessage());
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name);
        }
    }

    private NodeExecutionPhaseDbSelfTest() {
    }
}
