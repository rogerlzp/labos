package com.labos.kernel.workflow;

import com.labos.kernel.workflow.WorkflowGraphParser.GEdge;
import com.labos.kernel.workflow.WorkflowGraphParser.GNode;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;
import com.labos.kernel.workflow.WorkflowGraphParser.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点位视角的**执行期搬运插入**（设计见 {@code docs/architecture/点位视角设计器-设计_20261116.md} §4）。
 *
 * <p>一句话：**图里只有点位，搬运是这一次图变换**。
 * 应用者在设计器里只画「在点位 A 做 X」「在点位 B 做 Y」，两点位跨设备时，
 * 这里在两个节点之间插一个机械臂 {@code Transfer}，把板搬过去。
 *
 * <p><b>为什么不在设计期插节点</b>：
 * 设计期插会把机械臂画进用户的图（应用者说"我不关心搬运"），而且岛上一换臂就得改图。
 * 执行期插 ⇒ 图保持干净、可换臂、可重试、可观测（{@code transport_segment}）。
 *
 * <p><b>为什么只处理"没有显式搬运节点"的图</b>：
 * 存量 43 节点流程是**显式**机械臂节点写法（`ARM_TRANSFER` + Transfer）。
 * 两种写法不能叠加，否则一次换设备会搬两次。守卫见 {@link #hasExplicitTransfer}。
 *
 * <p><b>纯函数</b>：不查库、不碰设备，输入 Graph + 绑定，输出新 Graph + 参数。
 * 这样 planner 本身可以被单测覆盖（搬错位置是最贵的一类 bug）。
 */
public final class PositionFlowPlanner {

    /** 合成节点 key 前缀（稳定、幂等：同一条边重算得到同一个 key）。 */
    static final String KEY_PREFIX = "__move__";

    /** 合成节点的显示名（中性 code，显示时本地化，与 ARM_TRANSFER 同一约定）。 */
    static final String NODE_NAME = "ARM_TRANSFER";

    /**
     * 一个节点绑的点位。
     *
     * @param pointSpec 驱动认的点位标识（现场标签 &gt; 槽位号 &gt; 点位编码）
     * @param deviceCode 设备编码（{@code Transfer} 的 source/target 认这个）
     */
    public record BindingPoint(String nodeKey, long positionId, long deviceResourceId,
                               String pointCode, String pointSpec, String deviceCode,
                               boolean robotReachable) {
    }

    /** 规划出来的一段搬运（给日志/预检/前端预览用）。 */
    public record PlannedTransport(String nodeKey, String fromNode, String toNode,
                                   String fromPoint, String toPoint,
                                   String fromDevice, String toDevice) {
    }

    /**
     * 规划结果。
     *
     * @param graph           插入搬运节点后的图
     * @param paramsByNodeKey 合成节点的 Transfer 参数（source/target/sourcePosition/targetPosition/barcode）
     * @param transports      本次插入的搬运段
     */
    public record Plan(Graph graph,
                       Map<String, Map<String, Object>> paramsByNodeKey,
                       List<PlannedTransport> transports) {
    }

    private PositionFlowPlanner() {
    }

    /** 图里是否已有显式搬运节点（存量写法）⇒ 不再插入，避免搬两次。 */
    public static boolean hasExplicitTransfer(Graph g) {
        return g.nodes().values().stream()
                .anyMatch(n -> n.method() != null && n.method().startsWith("Transfer"));
    }

    /**
     * 在需要搬运的边上插入机械臂节点。
     *
     * <p>判定：边的两端都是"绑了点位的设备任务"、且**不属于同一台设备**。
     * 同设备的两点位（如提取仪操作位↔交互位）由设备内部机构搬，不插机械臂。
     *
     * @param g            原图
     * @param pointsByNode node_key → 点位绑定（没有绑定的节点不在其中）
     * @param armResourceId 本次执行要用的机械臂 **resource_node.id**
     *                      （能力池里装的就是这个；写进 GNode.resource，让执行器按
     *                      既有的 resource→instance 映射走，避免两套 id 空间混用）
     * @param paramsByNodeKey 现有节点的参数（用于把 barcode 顺过去）
     */
    public static Plan plan(Graph g,
                            Map<String, BindingPoint> pointsByNode,
                            long armResourceId,
                            Map<String, Map<String, Object>> paramsByNodeKey) {
        return plan(g, pointsByNode, armResourceId, paramsByNodeKey, java.util.Set.of());
    }

    /**
     * @param trimKeys 本次被阶段裁剪的节点：与它们相连的边**不插搬运**
     *                 （否则"只跑 S1"会因为 S2 的边需要搬板而卡住）。
     */
    public static Plan plan(Graph g,
                            Map<String, BindingPoint> pointsByNode,
                            long armResourceId,
                            Map<String, Map<String, Object>> paramsByNodeKey,
                            java.util.Set<String> trimKeys) {
        if (pointsByNode == null || pointsByNode.isEmpty() || hasExplicitTransfer(g)) {
            return new Plan(g, Map.of(), List.of());
        }

        Map<String, GNode> nodes = new LinkedHashMap<>(g.nodes());
        List<GEdge> edges = new ArrayList<>(g.edges().size() + 8);
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        List<PlannedTransport> transports = new ArrayList<>();

        for (GEdge e : g.edges()) {
            BindingPoint from = pointsByNode.get(e.from());
            BindingPoint to = pointsByNode.get(e.to());
            if (from == null || to == null || from.deviceResourceId() == to.deviceResourceId()
                    || (trimKeys != null
                        && (trimKeys.contains(e.from()) || trimKeys.contains(e.to())))) {
                edges.add(e);
                continue;
            }

            String key = KEY_PREFIX + e.id();
            if (!nodes.containsKey(key)) {
                nodes.put(key, new GNode(key, NODE_NAME, "task", Kind.TASK,
                        "TRANSFER", "Transfer", armResourceId));
                params.put(key, transferParams(from, to, paramsByNodeKey));
                transports.add(new PlannedTransport(key, e.from(), e.to(),
                        from.pointCode(), to.pointCode(), from.deviceCode(), to.deviceCode()));
            }
            // 原边 A→B 拆成 A→T（保留条件/default）+ T→B（无条件）
            edges.add(new GEdge(e.id(), e.from(), key, e.condition(), e.isDefault()));
            edges.add(new GEdge(e.id() + "_to", key, e.to(), null, false));
        }

        Graph out = new Graph(nodes, edges, index(edges, true), index(edges, false));
        return new Plan(out, params, transports);
    }

    /**
     * 一条搬运的参数。
     *
     * <p>{@code source}/{@code target} 是**设备编码**（`DriverStepExecutor` 用
     * `selectResourceIdByCode` 解析，见 `实验编排-功能设计.md` §14.6/§15.1 的口径差异）；
     * {@code sourcePosition}/{@code targetPosition} 才是精确坑位。
     *
     * <p>{@code barcode} 从相邻节点的参数里顺过来：没有它，驱动无法判断搬哪块板
     * （实测漏了会报 `EC-POSITION-OCCUPIED`）。顺不到就不写 —— 留空让预检/运行期
     * 明确报错，比编一个假条码安全。
     */
    private static Map<String, Object> transferParams(BindingPoint from, BindingPoint to,
                                                      Map<String, Map<String, Object>> paramsByNodeKey) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("source", from.deviceCode());
        p.put("target", to.deviceCode());
        if (from.pointSpec() != null && !from.pointSpec().isBlank()) {
            p.put("sourcePosition", from.pointSpec());
        }
        if (to.pointSpec() != null && !to.pointSpec().isBlank()) {
            p.put("targetPosition", to.pointSpec());
        }
        Object barcode = firstBarcode(paramsByNodeKey, from.nodeKey(), to.nodeKey());
        if (barcode != null) {
            p.put("barcode", barcode);
        }
        return p;
    }

    private static Object firstBarcode(Map<String, Map<String, Object>> paramsByNodeKey,
                                       String... keys) {
        if (paramsByNodeKey == null) {
            return null;
        }
        for (String k : keys) {
            Map<String, Object> p = paramsByNodeKey.get(k);
            if (p == null) {
                continue;
            }
            Object v = p.get("barcode");
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, List<GEdge>> index(List<GEdge> edges, boolean outgoing) {
        Map<String, List<GEdge>> m = new LinkedHashMap<>();
        for (GEdge e : edges) {
            m.computeIfAbsent(outgoing ? e.from() : e.to(), k -> new ArrayList<>()).add(e);
        }
        return m;
    }
}
