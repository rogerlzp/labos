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
 * 岛边界与绑定有效性门禁（P-05「执行前重校验配置快照」）。
 *
 * <p><b>为什么要有这个类</b>：规格 §6 矩阵里「Experiment execution | Revalidate
 * Experiment → Workflow → resource Island chain before dispatch」与
 * 「compile/preflight/publish | Revalidate graph, capabilities…」都指向同一件事 ——
 * <b>执行之前把"保存时的配置"拿现在的资源树重新校验一遍</b>。原先这条校验只长在
 * 预检里（{@link PreflightChecker}），于是：
 * <ul>
 *   <li>预检能报出来，但**实验派发不查** ⇒ 实验照样开跑，跑起来才在节点上转人工；</li>
 *   <li>实验派发路径与预检路径两套口径 ⇒ 预检说"没问题"、实验说"跑不了"。</li>
 * </ul>
 * ⇒ 把逻辑抽到这里，**两边共用同一套判定与同一套文案**。
 *
 * <p><b>与 {@link HandoffGate} 同构</b>：HandoffGate 管"跨 Workcell 的 Handoff 配没配"，
 * 本类管"每个 Workflow 自己的绑定还成不成立、还在不在它那个岛上"。
 *
 * <p><b>四条判定</b>（PF 码与预检共用）：
 * <ul>
 *   <li><b>PF-10</b>（ERROR）绑定的 id 在资源树里两个空间都找不到 ⇒ 绑定已失效；</li>
 *   <li><b>PF-11</b>（WARN）绑定的 id 是**设备实例号** ⇒ 与执行器的口径不一致，
 *       绑定当前不生效（节点实际按能力挑别的设备）；</li>
 *   <li><b>PF-12</b>（ERROR）绑定的设备不在流程所属的岛内 ⇒ 执行期会被拒绝；</li>
 *   <li><b>PF-13</b>（ERROR）流程所属的岛内没有具备该能力的设备 ⇒ 该节点会转人工。</li>
 * </ul>
 */
public final class IslandBindingGate {

    private IslandBindingGate() {
    }

    /** 一条违规。{@code level} 用预检的 Level（同一套词表 ⇒ 同一个界面能显示）。 */
    public record Violation(String code, PreflightChecker.Level level,
                            String nodeKey, String message) {
    }

    /**
     * 校验所需的"当前资源树"快照。
     *
     * <p>为什么要单独一个快照对象：预检会**批量**跑全量流程（1000+），
     * 每条流程各查一遍资源关系太慢 ⇒ 由调用方预加载一次，共享给所有流程。
     *
     * @param islandId            流程的权威岛；null = 没有岛约束（无权威岛的旧流程）
     * @param resourceToInstance  resource_node.id → device_instance.id
     * @param instanceToResource  device_instance.id → resource_node.id
     * @param islandInstances     岛内设备实例（device_instance.id）
     * @param islandResources     岛内设备资源（resource_node.id）
     * @param capPool             能力码 → 候选资源（resource_node.id 口径）
     * @param protocolCap         方法 → 能力码
     * @param resourceCap         资源 → 能力码
     */
    public record Context(Long islandId,
                          Map<Long, Long> resourceToInstance,
                          Map<Long, Long> instanceToResource,
                          Set<Long> islandInstances,
                          Set<Long> islandResources,
                          Map<String, List<Long>> capPool,
                          Map<String, String> protocolCap,
                          Map<Long, String> resourceCap) {

        public Context {
            resourceToInstance = Map.copyOf(resourceToInstance);
            instanceToResource = Map.copyOf(instanceToResource);
            islandInstances = Set.copyOf(islandInstances);
            islandResources = Set.copyOf(islandResources);
            capPool = Map.copyOf(capPool);
            protocolCap = Map.copyOf(protocolCap);
            resourceCap = Map.copyOf(resourceCap);
        }
    }

    /**
     * 载入"当前资源树"快照（一次查完，所有流程共用）。
     *
     * @param islandId 流程的权威岛；null = 不做岛约束
     */
    public static Context load(DbConfig db, long tenantId, Long islandId) throws SQLException {
        return load(db, tenantId, islandId,
                RealWorkflowRunner.loadCapabilityPool(db),
                RealWorkflowRunner.loadProtocolCapability(db),
                RealWorkflowRunner.loadResourceCapability(db));
    }

    /**
     * 载入"当前资源树"快照（能力映射由调用方预加载 —— 预检一次要跑全量流程，
     * 不能每条流程各查一遍能力表）。
     */
    public static Context load(DbConfig db, long tenantId, Long islandId,
                               Map<String, List<Long>> capPool,
                               Map<String, String> protocolCap,
                               Map<Long, String> resourceCap) throws SQLException {
        Map<Long, Long> resourceToInstance = new LinkedHashMap<>();
        Map<Long, Long> instanceToResource = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceStateMapper.class)
                .selectResourceInstancePairs(tenantId)) {
            long rid = ((Number) row.get("resourceId")).longValue();
            long iid = ((Number) row.get("instanceId")).longValue();
            resourceToInstance.put(rid, iid);
            instanceToResource.put(iid, rid);
        }
        Set<Long> islandInstances = new LinkedHashSet<>();
        Set<Long> islandResources = new LinkedHashSet<>();
        if (islandId != null) {
            islandInstances.addAll(com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectIslandInstanceIds(tenantId, islandId));
            for (Map.Entry<Long, Long> e : resourceToInstance.entrySet()) {
                if (islandInstances.contains(e.getValue())) {
                    islandResources.add(e.getKey());
                }
            }
        }
        return new Context(islandId, resourceToInstance, instanceToResource,
                islandInstances, islandResources, capPool, protocolCap, resourceCap);
    }

    /**
     * 纯函数：给定快照与节点，列出越界/失效的绑定（没有显式能力标注时用这个）。
     */
    public static List<Violation> violations(Context ctx,
                                              List<WorkflowGraphParser.GNode> tasks) {
        return violations(ctx, tasks, Map.of());
    }

    /**
     * 纯函数：给定快照与节点，列出越界/失效的绑定。
     *
     * <p>纯函数 ⇒ 自测不用连库（构造一份合成快照即可覆盖四条判定）。
     */
    public static List<Violation> violations(Context ctx, List<WorkflowGraphParser.GNode> tasks,
                                              Map<String, String> explicitCap) {
        List<Violation> out = new ArrayList<>();
        for (WorkflowGraphParser.GNode t : tasks) {
            RealWorkflowRunner.CapabilitySource cs = RealWorkflowRunner.resolveCapability(
                    t.name(), t.method(), t.resourceId(),
                    explicitCap == null ? null : explicitCap.get(t.key()),
                    ctx.protocolCap(), ctx.resourceCap());
            String cap = cs.capability();
            if (RealWorkflowRunner.LOGIC.equals(cap) || "UNKNOWN".equals(cap)) {
                continue;   // 逻辑节点不占设备；能力未知的已由 PF-01 报过
            }

            // ---- 岛内有没有具备该能力的设备（PF-13）----
            if (ctx.islandId() != null) {
                boolean hasInIsland = ctx.capPool().getOrDefault(cap, List.of()).stream()
                        .anyMatch(ctx.islandResources()::contains);
                if (!hasInIsland) {
                    out.add(new Violation("PF-13", PreflightChecker.Level.ERROR, t.key(),
                            String.format(
                                    "节点「%s」需要 %s 能力，但流程所属的岛 %d 内没有具备该能力的设备"
                                            + " —— 执行时该节点会转人工（不会去借别岛的设备）",
                                    t.name(), cap, ctx.islandId())));
                }
            }

            // ---- 绑定的设备是否还在、是否在岛内（PF-10/11/12）----
            Long bound = t.resourceId();
            if (bound == null) {
                continue;
            }
            Long instance = ctx.resourceToInstance().get(bound);
            if (instance != null) {
                if (ctx.islandId() != null && !ctx.islandInstances().contains(instance)) {
                    out.add(new Violation("PF-12", PreflightChecker.Level.ERROR, t.key(),
                            String.format(
                                    "节点「%s」绑定的设备（资源 %d / 实例 %d）不在流程所属的岛 %d 内"
                                            + " —— 执行时会被拒绝（设备归属由服务端决定，执行时不能换设备）",
                                    t.name(), bound, instance, ctx.islandId())));
                }
                continue;
            }
            Long asInstance = ctx.instanceToResource().get(bound);
            if (asInstance != null) {
                out.add(new Violation("PF-11", PreflightChecker.Level.WARN, t.key(),
                        String.format(
                                "节点「%s」绑定的 id %d 是**设备实例号**（对应资源 %d），"
                                        + "而执行器按**资源号**查 —— 该绑定当前不生效，"
                                        + "执行时实际会按能力挑另一台设备",
                                t.name(), bound, asInstance)));
                continue;
            }
            out.add(new Violation("PF-10", PreflightChecker.Level.ERROR, t.key(),
                    String.format(
                            "节点「%s」绑定的设备（id %d）在资源树里找不到"
                                    + "（资源号、设备实例号两个空间都没有）—— 该绑定当前不生效，"
                                    + "执行时实际会按能力挑另一台设备",
                            t.name(), bound)));
        }
        return out;
    }

    /**
     * 只取**阻塞级**违规（ERROR）—— 派发路径用它决定"放不放行"。
     *
     * <p>为什么分级：PF-11（绑定存成设备实例号 ⇒ 当前不生效）是 **WARN** ——
     * 流程照样能跑（只是跑的不是绑的那台），拿它去拦实验会把**大量本来能跑的
     * 实验**误杀（库里 8295 条绑定是这个形态，涉及 988 个流程）。
     * ⇒ 派发只拦真正会让执行失败/跑偏的（PF-10 设备已失效、PF-12 越岛绑定、
     * PF-13 岛内无该能力），WARN 级的交给预检去报。
     */
    public static List<Violation> blocking(List<Violation> violations) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (v.level() == PreflightChecker.Level.ERROR) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * **fail-closed** 门禁：校验一个流程的配置快照还有效。
     *
     * <p>给实验派发路径用：任一**阻塞级**违规 ⇒ 抛 {@link IllegalStateException}，
     * 调用方**不让实验开跑**（与 {@link HandoffGate} 同样的口径：
     * 宁可开跑前说清，也不要跑起来在节点上转人工）。
     *
     * @throws IllegalStateException 有越界/失效的绑定（ERROR 级）
     */
    public static void check(DbConfig db, long tenantId, long workflowId) throws SQLException {
        List<Violation> vs = blocking(violationsOf(db, tenantId, workflowId));
        if (vs.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Violation v : vs) {
            lines.add(v.code() + " " + v.message());
        }
        throw new IllegalStateException("流程 " + workflowId + " 的配置已不满足岛边界："
                + String.join("；", lines));
    }

    /**
     * 取一个流程的全部违规（自包含：自己查岛、自己查图、自己查资源树）。
     *
     * <p>给"派发前逐个流程校验"的调用方用（实验派发、批量重跑）。
     */
    public static List<Violation> violationsOf(DbConfig db, long tenantId, long workflowId)
            throws SQLException {
        Long islandId = authoritativeIslandId(db, tenantId, workflowId);
        String xml = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.WorkflowWebMapper.class)
                .selectWorkflowXml(tenantId, workflowId);
        if (xml == null || xml.isBlank()) {
            return List.of();   // 没有图 = 没有绑定可校验（PF-08 由预检负责）
        }
        WorkflowGraphParser.Graph graph;
        try {
            graph = WorkflowGraphParser.parse(xml);
        } catch (RuntimeException e) {
            return List.of();   // 图解析不了 = 预检 PF-08 的职责
        }
        List<WorkflowGraphParser.GNode> tasks = new ArrayList<>();
        for (WorkflowGraphParser.GNode n : graph.nodes().values()) {
            if (n.kind() == WorkflowGraphParser.Kind.TASK) {
                tasks.add(n);
            }
        }
        Map<String, String> explicitCap = new LinkedHashMap<>();
        for (Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class)
                .selectNodeCapabilityCodes(tenantId, workflowId)) {
            explicitCap.put((String) row.get("nodeKey"), (String) row.get("capabilityCode"));
        }
        return violations(load(db, tenantId, islandId), tasks, explicitCap);
    }

    /** 流程的权威岛（{@code workflow.island_id}）；没有则 null。 */
    public static Long authoritativeIslandId(DbConfig db, long tenantId, long workflowId) {
        try {
            Map<String, Object> row = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.GraphMapper.class)
                    .selectWorkflowHeader(tenantId, workflowId);
            if (row == null) {
                return null;
            }
            Object v = row.get("islandId");
            return v instanceof Number ? ((Number) v).longValue() : null;
        } catch (RuntimeException e) {
            // 读不到岛 ⇒ 视为"没有岛约束"（与执行期一致：读不到就不拦）
            return null;
        }
    }
}
