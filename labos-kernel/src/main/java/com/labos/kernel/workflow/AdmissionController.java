package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

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

        java.util.Map<String, Object> row = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .selectCapabilityCapacity(tenantId, capabilityId);
        if (row != null) {
            code = (String) row.get("capCode");
            total = intOf(row.get("total"));
            free = intOf(row.get("freeCnt"));
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
        for (java.util.Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExecutionMapper.class)
                .selectBusyDevices(tenantId, capabilityId)) {
            int est = intOf(row.get("nodeEstimateSec"));
            int elapsed = intOf(row.get("elapsedSec"));
            list.add(new Blocker(
                    ((Number) row.get("currentRunId")).longValue(),
                    (String) row.get("runNo"),
                    ((Number) row.get("deviceId")).longValue(),
                    (String) row.get("deviceCode"),
                    (String) row.get("currentMethod"),
                    Math.max(0, est - elapsed)));
        }
        return list;
    }

    /** Map 里的数值统一转 int（PG count 是 bigint，经 Number 兜住）。 */
    private static int intOf(Object o) {
        return o instanceof Number num ? num.intValue() : 0;
    }

    /**
     * 全岛视角：现在有几个执行在跑。
     *
     * <p>用于界面提示"当前并发 N 个"。
     */
    public int runningCount() {
        try {
            return com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.ExecutionMapper.class)
                    .countRunningRuns(tenantId);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
