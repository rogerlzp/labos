package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 整流程影子跑：把一条流程的每个节点都真实下发一次，**只记录不采信**。
 *
 * <p>和正式执行的区别：
 * <ul>
 *   <li>不触发恢复策略（失败就记下来，不重试、不换设备）</li>
 *   <li>不落 run / run_node / run_command（不污染生产数据）</li>
 *   <li>不检查时间约束（影子跑不等待）</li>
 *   <li>遇到失败**继续跑下一个**——目的是把所有节点的契约都验一遍</li>
 * </ul>
 *
 * <p>为什么这样设计：影子跑是为了"确认驱动契约对不对"，
 * 不是"确认这条流程能不能跑通"。前者需要覆盖所有节点，后者需要停在一个地方。
 *
 * <p><b>安全边界</b>：本类会真实驱动设备。现场第一次跑前，
 * 必须先用单命令探针（{@code /api/shadow/probe}）逐台验证过。
 */
public final class ShadowRunner {

    /** 延迟取 Mapper 代理（new 出来的工具类，无构造注入；见 Mappers）。 */
    private static com.labos.kernel.persistence.QueryMapper qm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class);
    }

    /** 一个节点的影子结果。 */
    public record NodeProbe(
            String nodeKey, String name, String capability, String method,
            long deviceId, String deviceCode,
            boolean ok, String errorCode, String errorText,
            long elapsedMs, int contractMissing) {
    }

    /** 整条流程的影子报告。 */
    public record Report(
            long workflowId, String workflowName,
            int total, int ok, int failed,
            /** 契约字段缺失的节点数（驱动返回格式不符）。 */
            int contractGaps,
            List<NodeProbe> nodes) {
    }

    private final DbConfig db;
    private final long tenantId;
    private final ShadowProbe probe;
    private final Map<String, List<Long>> capPool;

    public ShadowRunner(DbConfig db, long tenantId, DriverRegistry registry,
                        Map<String, List<Long>> capabilityPool) {
        this.db = db;
        this.tenantId = tenantId;
        this.probe = new ShadowProbe(registry);
        this.capPool = capabilityPool;
    }

    /**
     * 跑整条流程的影子检查。
     *
     * <p>按节点顺序逐个下发；某节点失败不影响后续——目标是覆盖全部。
     */
    public Report run(long workflowId) throws SQLException {
        String wfName = "";
        List<NodeProbe> results = new ArrayList<>();
        int ok = 0;
        int contractGaps = 0;

        // 读节点：key / name / 能力 / 方法
        try {
            String found = qm().selectWorkflowName(tenantId, workflowId);
            if (found != null) {
                wfName = found;
            }
        } catch (RuntimeException ignore) {
            // 流程名取不到不影响影子跑
        }

        for (java.util.Map<String, Object> nodeRow : com.labos.kernel.persistence.Mappers
                .get(com.labos.kernel.persistence.GraphMapper.class)
                .selectShadowProbeNodes(tenantId, workflowId)) {
            String key = (String) nodeRow.get("nodeKey");
            String name = (String) nodeRow.get("name");
            String cap = (String) nodeRow.get("capability");
            String method = (String) nodeRow.get("method");
            Object boundObj = nodeRow.get("bound");
            Long bound = boundObj instanceof Number num ? num.longValue() : null;
            if (cap == null || "LOGIC".equals(cap)) {
                continue;   // 逻辑节点没有设备命令，跳过
            }

            // 节点绑了具体设备（搬运序列生成的流程）就用它，否则按能力挑
            List<Long> candidates;
            if (bound != null && bound > 0) {
                Long instance = deviceInstanceOf(bound);
                candidates = instance == null ? List.of() : List.of(instance);
            } else {
                candidates = capPool.getOrDefault(cap, List.of());
            }
            if (candidates.isEmpty()) {
                results.add(new NodeProbe(key, name, cap, method,
                        0, null, false, "EC-06-0002",
                        "该能力没有可用设备", 0, 0));
                continue;
            }

            long dev = candidates.get(0);
            String devCode = deviceCode(dev);
            ShadowProbe.Probe p = probe.probe(dev, method);
            long missing = p.contract() == null ? 0
                    : p.contract().values().stream().filter(v -> !v).count();
            if (missing > 0) {
                contractGaps++;
            }
            if (p.ok()) {
                ok++;
            }
            results.add(new NodeProbe(key, name, cap, method, dev, devCode,
                    p.ok(), p.errorCode(), p.errorText(),
                    p.elapsedMs(), (int) missing));
        }

        return new Report(workflowId, wfName, results.size(), ok,
                results.size() - ok, contractGaps, results);
    }

    /** resource_node.id -> device_instance.id。 */
    private Long deviceInstanceOf(long resourceNodeId) {
        try {
            return com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.WorkflowWebMapper.class)
                    .selectDeviceInstanceId(resourceNodeId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String deviceCode(long deviceInstanceId) {
        try {
            return com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.WorkflowWebMapper.class)
                    .selectDeviceCodeByInstance(deviceInstanceId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 报告转成前端要的 JSON 片段（nodes 用 Raw 包一层，否则会被序列化成字符串）。 */
    public static Object[] toJson(Report r) {
        List<String> rows = new ArrayList<>();
        for (NodeProbe n : r.nodes()) {
            rows.add(Jsons.obj(
                    "nodeKey", n.nodeKey(),
                    "name", n.name(),
                    "capability", n.capability(),
                    "method", n.method(),
                    "deviceId", n.deviceId(),
                    "deviceCode", n.deviceCode(),
                    "ok", n.ok(),
                    "errorCode", n.errorCode(),
                    "errorText", n.errorText(),
                    "elapsedMs", n.elapsedMs(),
                    "contractMissing", n.contractMissing()));
        }
        return new Object[]{
                "workflowId", r.workflowId(),
                "workflowName", r.workflowName(),
                "total", r.total(),
                "ok", r.ok(),
                "failed", r.failed(),
                "contractGaps", r.contractGaps(),
                "nodes", new Jsons.Raw(Jsons.arr(rows)),
        };
    }
}
