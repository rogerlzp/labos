package com.labos.kernel.workflow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 条件网关（包容网关）的<b>分支存活性</b>追踪。
 *
 * <p><b>要解决的问题</b>：条件汇合点（{@code JOIN_CONDITIONAL}）必须知道
 * 「我这几条入边里，哪些<b>真的会到</b>」——
 * <ul>
 *   <li>分支 A 的条件成立 ⇒ A 会跑 ⇒ 汇合要<b>等</b> A；</li>
 *   <li>分支 B 的条件不成立 ⇒ B 不会跑 ⇒ 汇合<b>不能</b>等 B（否则死等）。</li>
 * </ul>
 * 并行汇合（AND join）没有这个问题 —— 它的入边<b>全都</b>会到。
 * 异或汇合也没有 —— 它是「任一条到达即放行」。
 *
 * <p><b>怎么判</b>：不预测条件（条件求值发生在运行期，值当时才知道），
 * 而是<b>从「被跳过的边」出发往后推</b>：
 * <ol>
 *   <li>分叉决定之后，被跳过的出边标记为「死边」；</li>
 *   <li>某个节点的<b>所有入边都死了</b> ⇒ 这个节点自己也不会被执行（死节点）；</li>
 *   <li>死节点的出边也就不会被走 ⇒ 继续往下推。</li>
 * </ol>
 * 这是一个<b>单调增长</b>的不动点：边只会从「未知」变成「死」，不会变回活，
 * 所以用工作队列迭代到收敛即可（不会震荡）。
 *
 * <p><b>为什么「所有入边都死」才算死节点</b>：一个节点可能有多条入边
 * （被别的分叉也指向）。只要还有一条入边<b>可能</b>是活的，它就可能执行 ⇒ 不能判死。
 * 这条规则同时避免了「某个分支被跳过 ⇒ 误杀共享节点」的事故。
 *
 * <p><b>未知的边怎么办</b>：汇合点只看三类边：
 * <ul>
 *   <li><b>已到达</b> ⇒ 到了；</li>
 *   <li><b>已判死</b> ⇒ 永远不会到 ⇒ <b>不等</b>；</li>
 *   <li>两者都不是 ⇒ 还不知道 ⇒ <b>等</b>（保守）。</li>
 * </ul>
 * 保守等是安全的：真正的死边一定会被判死（分叉决定后立刻传播），
 * 那时汇合点会被<b>重新判定</b>一次 ⇒ 不会死等。
 *
 * <p><b>线程安全</b>：本类<b>不是</b>线程安全的 —— 它只在一个 run 的
 * 单线程 ready 队列里被使用（与 {@link GraphRunner} 的执行模型一致）。
 */
final class ConditionalBranchTracker {

    /** 已经走过的边 id（源节点跑完并 traverse 过这条边）。 */
    private final Set<String> arrived = new HashSet<>();

    /** 已经确定<b>不会</b>被走到的边 id。 */
    private final Set<String> dead = new HashSet<>();

    /**
     * 节点 key → 「还可能到达的入边数」= 入边总数 − 已判死的入边数。
     *
     * <p>归零 ⇒ 这个节点自己也不会被执行（死节点）⇒ 它的出边也判死。
     */
    private final Map<String, Integer> liveIn = new HashMap<>();

    ConditionalBranchTracker(WorkflowGraphParser.Graph g) {
        for (WorkflowGraphParser.GNode n : g.nodes().values()) {
            liveIn.put(n.key(), g.in(n.key()).size());
        }
    }

    /**
     * 这条边已经走过了。
     *
     * <p>同时把它从「死边」里摘掉：走到过的边一定不是死的
     * （能走到就说明源节点活着），摘掉是为了防御 —— 万一先判死后又被走到
     * （图里有环、节点重入），以「走到了」为准。
     */
    void markArrived(WorkflowGraphParser.GEdge e) {
        arrived.add(e.id());
        dead.remove(e.id());
    }

    /**
     * 分叉决定之后，把<b>被跳过</b>的出边标死并往下传播。
     *
     * @param g        图
     * @param outs     该分叉的全部出边
     * @param takenIds 实际走到的出边 id（不在这个集合里的都算被跳过）
     * @return 有没有新判死的边 —— 变了就要<b>重新判定</b>条件汇合点
     */
    boolean markSkipped(WorkflowGraphParser.Graph g,
                         List<WorkflowGraphParser.GEdge> outs,
                         Set<String> takenIds) {
        Deque<WorkflowGraphParser.GEdge> work = new ArrayDeque<>();
        for (WorkflowGraphParser.GEdge e : outs) {
            if (!takenIds.contains(e.id()) && !dead.contains(e.id())) {
                work.add(e);
            }
        }
        boolean changed = false;
        while (!work.isEmpty()) {
            WorkflowGraphParser.GEdge e = work.poll();
            if (!dead.add(e.id())) {
                continue;   // 已经判过死
            }
            changed = true;
            String target = e.to();
            if (target == null || !liveIn.containsKey(target)) {
                continue;   // 目标不在图里（被解析器滤掉了）⇒ 无下游可推
            }
            int left = liveIn.merge(target, -1, Integer::sum);
            if (left > 0) {
                // 还有别的入边可能是活的 ⇒ 这个节点不一定死 ⇒ 不往下推
                continue;
            }
            // 这个节点彻底不会执行 ⇒ 它的出边也不会被走
            for (WorkflowGraphParser.GEdge out : g.out(target)) {
                if (!dead.contains(out.id())) {
                    work.add(out);
                }
            }
        }
        return changed;
    }

    /**
     * 条件汇合点是否已经「等齐」。
     *
     * <p>口径：<b>所有「还可能到达」的入边都到了</b>才算齐；
     * 已判死的入边（分支被跳过）不算数 —— 否则就死等了。
     *
     * @param g       图
     * @param joinKey 条件汇合点
     * @return 齐了没
     */
    boolean joinReady(WorkflowGraphParser.Graph g, String joinKey) {
        List<WorkflowGraphParser.GEdge> ins = g.in(joinKey);
        if (ins.isEmpty()) {
            return false;
        }
        int expected = 0;
        int got = 0;
        for (WorkflowGraphParser.GEdge e : ins) {
            if (dead.contains(e.id())) {
                continue;   // 永远不会到 ⇒ 不等它
            }
            expected++;
            if (arrived.contains(e.id())) {
                got++;
            }
        }
        // expected == 0 ⇒ 所有入边都死了 ⇒ 汇合点自己永远不会执行 ⇒ 不放行
        return expected > 0 && got == expected;
    }

    /** 诊断用：已判死的边数（验收报告看「有多少分支被跳过」）。 */
    int deadEdgeCount() {
        return dead.size();
    }
}
