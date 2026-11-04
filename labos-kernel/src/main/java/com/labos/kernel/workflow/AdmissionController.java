package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动准入：用**主链粗筛**判断"现在启动这个流程，资源够不够"。
 *
 * <h3>为什么需要这个</h3>
 * 纯等待图只能"跑到一半才发现抢不到"：
 * <pre>
 *   用户点启动 → 系统接受 → 跑 5 分钟 → 卡在抢设备上 → 一直等
 * </pre>
 * 用户感受是"**点了没反应**"。
 *
 * <p>粗筛在启动时就算一遍，提前告诉用户"资源不够，被谁占着，大概等多久"。
 *
 * <h3>粗筛可以错，细管不能错</h3>
 * <ul>
 *   <li><b>粗筛</b>只按主链算，实际走了非主链就可能算少 → 最多"等很久"，不影响正确性</li>
 *   <li><b>细管</b>（等待图）在执行中兜底 → 保证不死锁</li>
 * </ul>
 *
 * <h3>资源单元为什么是"设备"不是"位置"</h3>
 * 设备数量少（10 台 vs 80 个位置），算法快，且设备是主要瓶颈。
 * 位置冲突由细管的位置锁处理。
 */
public final class AdmissionController {

    private final DbConfig db;
    private final long tenantId;

    /** 主链需求 + 冲突信息。 */
    public record Decision(boolean canStart,
                           String reason,
                           Integer estimatedWaitSeconds,
                           List<Blocker> blocking,
                           String strategy,
                           int needDeviceCount,
                           int totalEstimateSec) {

        /** 直接出 JSON 字符串（避免跨包依赖 Jsons）。 */
        public String toJsonString() {
            return write(toJson());
        }

        private static String write(Object v) {
            if (v == null) {
                return "null";
            }
            if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
            if (v instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('"').append(esc(String.valueOf(e.getKey())))
                            .append("\":").append(write(e.getValue()));
                }
                return sb.append('}').toString();
            }
            if (v instanceof Iterable<?> it) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(write(o));
                }
                return sb.append(']').toString();
            }
            return "\"" + esc(String.valueOf(v)) + "\"";
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canStart", canStart);
            m.put("strategy", strategy);
            m.put("needDeviceCount", needDeviceCount);
            m.put("totalEstimateSec", totalEstimateSec);
            if (!canStart) {
                m.put("reason", reason);
                m.put("estimatedWaitSeconds", estimatedWaitSeconds);
                List<Map<String, Object>> bs = new ArrayList<>();
                for (Blocker b : blocking) {
                    Map<String, Object> bm = new LinkedHashMap<>();
                    bm.put("runId", b.runId());
                    bm.put("runNo", b.runNo());
                    bm.put("deviceInstanceId", b.deviceInstanceId());
                    bm.put("deviceCode", b.deviceCode());
                    bm.put("nodeName", b.nodeName());
                    bm.put("etaSeconds", b.etaSeconds());
                    bs.add(bm);
                }
                m.put("blocking", bs);
            }
            return m;
        }
    }

    /** 占着设备的执行。 */
    public record Blocker(long runId, String runNo, long deviceInstanceId,
                          String deviceCode, String nodeName, int etaSeconds) {
    }

    public AdmissionController(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 粗筛。
     *
     * @param workflowId 要启动的流程
     */
    public Decision check(long workflowId) throws SQLException {
        MainPathSelector.MainPath main = new MainPathSelector(db, tenantId).select(workflowId);
        if (main.isEmpty()) {
            return new Decision(true, null, null, List.of(), "empty", 0, 0);
        }

        Map<Long, Integer> needs = main.capabilityNeeds();
        if (needs.isEmpty()) {
            return new Decision(true, null, null, List.of(), main.strategy(),
                    0, main.totalEstimateSec());
        }

        // 按能力查候选池：每台设备是否被占
        List<Blocker> blockers = new ArrayList<>();
        List<String> shortCapabilities = new ArrayList<>();

        for (Map.Entry<Long, Integer> e : needs.entrySet()) {
            long capabilityId = e.getKey();
            int needCount = e.getValue();
            Capacity cap = loadCapacity(capabilityId);
            if (cap.total() == 0) {
                // 这个能力没有任何设备：配置问题，不是资源不足
                shortCapabilities.add(cap.code() + "（无设备）");
                continue;
            }
            if (cap.free() < needCount) {
                shortCapabilities.add(cap.code() + "（空闲 " + cap.free()
                        + "/" + cap.total() + "）");
                blockers.addAll(cap.busyDevices());
            }
        }

        if (shortCapabilities.isEmpty()) {
            return new Decision(true, null, null, List.of(), main.strategy(),
                    needs.size(), main.totalEstimateSec());
        }

        blockers.sort((a, b) -> Integer.compare(a.etaSeconds(), b.etaSeconds()));
        Integer waitSec = blockers.isEmpty() ? null : blockers.get(0).etaSeconds();
        String reason = "资源不足：" + String.join("、", shortCapabilities);

        return new Decision(false, reason, waitSec, blockers,
                main.strategy(), needs.size(), main.totalEstimateSec());
    }

    /** 某个能力的设备池状况。 */
    private record Capacity(String code, int total, int free, List<Blocker> busyDevices) {
    }

    private Capacity loadCapacity(long capabilityId) throws SQLException {
        String code = "";
        int total = 0;
        int free = 0;
        List<Blocker> busy = new ArrayList<>();

        String sql = """
                SELECT c.code AS cap_code,
                       count(di.id) AS total,
                       count(*) FILTER (WHERE ds.current_run_id IS NULL) AS free_cnt
                FROM capability c
                LEFT JOIN resource_capability rc ON rc.capability_id = c.id
                LEFT JOIN device_instance di
                       ON di.resource_id = rc.resource_id AND di.tenant_id = c.tenant_id
                       AND di.deleted_at IS NULL
                LEFT JOIN device_state ds
                       ON ds.resource_id = di.resource_id AND ds.tenant_id = c.tenant_id
                WHERE c.tenant_id = ? AND c.id = ?
                GROUP BY c.code
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, capabilityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    code = rs.getString("cap_code");
                    total = rs.getInt("total");
                    free = rs.getInt("free_cnt");
                }
            }
        }

        if (free < 1) {
            // 只有不足时才去查"谁占着"，正常情况不查（省开销）
            busy.addAll(busyDevicesOf(capabilityId));
        }
        return new Capacity(code, total, free, busy);
    }

    /** 这个能力下被占用的设备 + 持有者。 */
    private List<Blocker> busyDevicesOf(long capabilityId) throws SQLException {
        List<Blocker> list = new ArrayList<>();
        String sql = """
                SELECT di.id AS device_id, di.code AS device_code,
                       ds.current_run_id, ds.current_method,
                       r.run_no,
                       COALESCE(EXTRACT(EPOCH FROM (now() - r.actual_start))::int, 0)
                           AS elapsed_sec,
                       COALESCE((
                           SELECT rn.estimate_sec FROM run_node rn
                           WHERE rn.run_id = r.id AND rn.status = 'running'
                           ORDER BY rn.id DESC LIMIT 1
                       ), 60) AS node_estimate_sec
                FROM resource_capability rc
                JOIN device_instance di ON di.resource_id = rc.resource_id
                                       AND di.tenant_id = ?
                                       AND di.deleted_at IS NULL
                JOIN device_state ds ON ds.resource_id = di.resource_id
                                    AND ds.tenant_id = di.tenant_id
                LEFT JOIN run r ON r.id = ds.current_run_id
                WHERE rc.capability_id = ?
                  AND ds.current_run_id IS NOT NULL
                ORDER BY di.code
                LIMIT 20
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, capabilityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int est = rs.getInt("node_estimate_sec");
                    int elapsed = rs.getInt("elapsed_sec");
                    list.add(new Blocker(
                            rs.getLong("current_run_id"),
                            rs.getString("run_no"),
                            rs.getLong("device_id"),
                            rs.getString("device_code"),
                            rs.getString("current_method"),
                            Math.max(0, est - elapsed)));
                }
            }
        }
        return list;
    }

    /**
     * 全岛视角：现在有几个执行在跑。
     *
     * <p>用于界面提示"当前并发 N 个"。
     */
    public int runningCount() {
        String sql = "SELECT count(*) FROM run WHERE tenant_id = ? AND status = 'running'";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
