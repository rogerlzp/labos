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
 * 主链选择：从流程里挑一条"代表性路径"，用于启动前的资源粗筛。
 *
 * <h3>为什么需要"主链"</h3>
 * 银行家算法要求"执行前声明最多需要多少资源"，但流程有分支，
 * 声明不出来（见 {@code docs/为什么不用银行家算法.md}）。
 *
 * <p>折中：**只按主链算**。主链是一条确定的路径，需求是确定的。
 * 如果实际走了非主链、资源不够，由执行中的细管（等待图）兜底 ——
 * 最多"等很久"，不会死锁。
 *
 * <h3>三种选法（按优先级）</h3>
 * <ol>
 *   <li><b>编排时人工标记</b> —— 网关节点上配 {@code params.mainPath}，
 *       值是目标分支的 {@code to_key}。只有编排的人知道业务上哪条是常态。</li>
 *   <li><b>资源最重</b> —— 自动选用到设备最多的分支。偏保守，不会"筛过了但实际不够"。</li>
 *   <li><b>节点最多</b> —— 兜底，只为让算法有确定结果。</li>
 * </ol>
 */
public final class MainPathSelector {

    private final DbConfig db;
    private final long tenantId;

    public MainPathSelector(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /** 节点。 */
    public record Node(String key, String type, String name, Long equipmentId,
                       Long capabilityId, int estimateSec, Map<String, String> mainPath) {
        public boolean isGateway() {
            return type != null && type.startsWith("gateway");
        }

        public boolean isTerminal() {
            return "end".equals(type);
        }

        public boolean isTask() {
            return "task".equals(type) || "subprocess".equals(type);
        }

        /** 有明确设备实例（存量数据/强绑定场景）。 */
        public boolean needsDevice() {
            return equipmentId != null && isTask();
        }

        /** 有明确能力（推荐：按能力从池子里选）。 */
        public boolean needsCapability() {
            return capabilityId != null && isTask();
        }
    }

    /** 主链。 */
    public record MainPath(List<Node> nodes, Set<Long> devices,
                           Map<Long, Integer> capabilityNeeds,
                           int totalEstimateSec, String strategy) {

        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    /**
     * 选主链。
     *
     * @return 主链；流程没有节点时返回空
     */
    public MainPath select(long workflowId) throws SQLException {
        Map<String, Node> nodes = loadNodes(workflowId);
        Map<String, List<String>> edges = loadEdges(workflowId);
        if (nodes.isEmpty()) {
            return new MainPath(List.of(), Set.of(), Map.of(), 0, "empty");
        }

        List<List<String>> paths = new ArrayList<>();
        collectPaths(nodes, edges, startKeys(nodes), new ArrayList<>(), paths, 200);

        if (paths.isEmpty()) {
            // 没有完整路径（图不连通），退化为"所有节点"
            List<Node> all = new ArrayList<>(nodes.values());
            return new MainPath(all, devicesOf(all), capabilitiesOf(all),
                    all.stream().mapToInt(Node::estimateSec).sum(), "all-nodes");
        }

        // ① 人工标记：找有 mainPath 的网关，按它的指定走
        PathChoice marked = chooseByMark(nodes, paths);
        if (marked != null) {
            return build(marked.path(), nodes, "manual-mark");
        }

        // ② 资源最重
        List<String> heaviest = null;
        int maxDevices = -1;
        for (List<String> p : paths) {
            int n = devicesOf(pathNodes(p, nodes)).size();
            if (n > maxDevices) {
                maxDevices = n;
                heaviest = p;
            }
        }

        // ③ 节点最多（并列时）
        List<String> longest = heaviest;
        for (List<String> p : paths) {
            if (devicesOf(pathNodes(p, nodes)).size() == maxDevices
                    && p.size() > longest.size()) {
                longest = p;
            }
        }
        return build(longest == null ? paths.get(0) : longest, nodes, "heaviest-devices");
    }

    private MainPath build(List<String> keys, Map<String, Node> nodes, String strategy) {
        List<Node> list = pathNodes(keys, nodes);
        return new MainPath(list, devicesOf(list), capabilitiesOf(list),
                list.stream().mapToInt(Node::estimateSec).sum(), strategy);
    }

    private record PathChoice(List<String> path) {
    }

    /**
     * 人工标记优先：如果主链路径上的网关节点配了 {@code mainPath}，
     * 且当前路径走的就是它指定的分支，这条路径优先。
     */
    private PathChoice chooseByMark(Map<String, Node> nodes, List<List<String>> paths) {
        for (List<String> p : paths) {
            boolean allMarksRespected = true;
            boolean hasMark = false;

            for (int i = 0; i < p.size() - 1; i++) {
                Node n = nodes.get(p.get(i));
                if (n == null || !n.isGateway() || n.mainPath() == null
                        || n.mainPath().isEmpty()) {
                    continue;
                }
                hasMark = true;
                String want = n.mainPath().get("next");
                if (want != null && !want.isBlank() && !want.equals(p.get(i + 1))) {
                    allMarksRespected = false;
                    break;
                }
            }
            if (hasMark && allMarksRespected) {
                return new PathChoice(p);
            }
        }
        return null;
    }

    private List<Node> pathNodes(List<String> keys, Map<String, Node> nodes) {
        List<Node> list = new ArrayList<>();
        for (String k : keys) {
            Node n = nodes.get(k);
            if (n != null) {
                list.add(n);
            }
        }
        return list;
    }

    private Set<Long> devicesOf(List<Node> list) {
        Set<Long> s = new LinkedHashSet<>();
        for (Node n : list) {
            if (n.needsDevice()) {
                s.add(n.equipmentId());
            }
        }
        return s;
    }

    /**
     * 主链上的能力需求。
     *
     * <p>同一能力出现多次仍按 **1 台**算——顺序执行可以复用同一台设备。
     * 只有并行网关才会真正同时需要多台，那种情况由细管兜底。
     *
     * <p><b>为什么按能力而不是设备实例</b>：执行时是按能力从池子里选设备的
     * （INCUBATE 有 48 台候选），按实例算会误判"资源不足"。
     */
    private Map<Long, Integer> capabilitiesOf(List<Node> list) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (Node n : list) {
            if (n.needsCapability()) {
                m.put(n.capabilityId(), 1);
            }
        }
        return m;
    }

    /** 收集所有从 start 到 end 的路径（有上限，防指数爆炸）。 */
    private void collectPaths(Map<String, Node> nodes, Map<String, List<String>> edges,
                              List<String> starts, List<String> cur,
                              List<List<String>> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (cur.isEmpty()) {
            for (String s : starts) {
                List<String> p = new ArrayList<>();
                p.add(s);
                dfs(nodes, edges, s, p, out, limit, 0);
            }
        }
    }

    private void dfs(Map<String, Node> nodes, Map<String, List<String>> edges,
                     String key, List<String> path, List<List<String>> out,
                     int limit, int depth) {
        if (out.size() >= limit || depth > 100) {
            return;
        }
        Node n = nodes.get(key);
        if (n != null && n.isTerminal()) {
            out.add(new ArrayList<>(path));
            return;
        }
        List<String> next = edges.getOrDefault(key, List.of());
        if (next.isEmpty()) {
            out.add(new ArrayList<>(path));   // 断头也算一条（降级用）
            return;
        }
        for (String t : next) {
            path.add(t);
            dfs(nodes, edges, t, path, out, limit, depth + 1);
            path.remove(path.size() - 1);
        }
    }

    private List<String> startKeys(Map<String, Node> nodes) {
        List<String> s = new ArrayList<>();
        for (Node n : nodes.values()) {
            if ("start".equals(n.type())) {
                s.add(n.key());
            }
        }
        return s;
    }

    // ---------------------------------------------------------------- 加载

    private Map<String, Node> loadNodes(long workflowId) throws SQLException {
        Map<String, Node> m = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectMainPathNodes(tenantId, workflowId)) {
            String params = (String) row.get("params");
            Long eq = extractEquipmentId(params);
            Map<String, String> mark = extractMainPath(params);
            m.put((String) row.get("nodeKey"), new Node(
                    (String) row.get("nodeKey"),
                    (String) row.get("nodeType"),
                    (String) row.get("name"),
                    eq,
                    capabilityId(row),
                    ((Number) row.get("estimateSec")).intValue(),
                    mark));
        }
        return m;
    }

    /** 节点的能力：先看 capability_id 列，再看 params.capability_code。 */
    private Long capabilityId(Map<String, Object> row) throws SQLException {
        Object cid = row.get("capabilityId");
        if (cid instanceof Number num && num.longValue() > 0) {
            return num.longValue();
        }
        String code = strField((String) row.get("params"), "capability_code");
        if (code == null || code.isBlank()) {
            return null;
        }
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceMapper.class)
                .selectCapabilityId(tenantId, code);
    }

    private Map<String, List<String>> loadEdges(long workflowId) throws SQLException {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectMainPathEdges(tenantId, workflowId)) {
            m.computeIfAbsent((String) row.get("fromKey"), k -> new ArrayList<>())
                    .add((String) row.get("toKey"));
        }
        return m;
    }

    /** 从 params 里取设备实例 id（"equipment_instance_id" 或 "resources" 的第一个）。 */
    static Long extractEquipmentId(String paramsJson) {
        if (paramsJson == null) {
            return null;
        }
        Long v = numField(paramsJson, "equipment_instance_id");
        if (v != null && v > 0) {
            return v;
        }
        // resources 形如 "155;" 或 "155;156;"
        String res = strField(paramsJson, "resources");
        if (res != null && !res.isBlank()) {
            for (String part : res.split("[;,]")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    try {
                        long id = Long.parseLong(t);
                        if (id > 0) {
                            return id;
                        }
                    } catch (NumberFormatException ignore) {
                        // 非数字就跳过
                    }
                }
            }
        }
        return null;
    }

    /** 从 params 里取 mainPath 标记。 */
    static Map<String, String> extractMainPath(String paramsJson) {
        if (paramsJson == null || !paramsJson.contains("mainPath")) {
            return Map.of();
        }
        String v = strField(paramsJson, "mainPath");
        return v == null ? Map.of() : Map.of("next", v);
    }

    private static Long numField(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int end = colon + 1;
        while (end < json.length() && " \t\n\r".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        int start = end;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strField(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }
}
