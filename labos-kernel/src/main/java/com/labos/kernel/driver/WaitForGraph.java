package com.labos.kernel.driver;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 等待图：记录"谁在等谁"，检测环。
 *
 * <p><b>为什么用它而不是银行家算法</b>：银行家要求"执行前声明最多需要多少资源"，
 * 但流程有分支，这个前提不成立（见 {@code docs/为什么不用银行家算法.md}）。
 * 等待图**不需要预知未来**，只看当下谁在等谁。
 *
 * <h3>怎么工作</h3>
 * <pre>
 * 流程1 要 B（被流程2 占）→ 加边 1→2 → DFS 检测：无环 → 允许等待
 * 流程2 要 C（被流程3 占）→ 加边 2→3 → 无环 → 允许等待
 * 流程3 要 A（被流程1 占）→ 加边 3→1
 *   DFS: 3→1→2→3 ← **成环** → **拒绝流程3**
 * </pre>
 *
 * <p>任何可能成环的申请都被拒，所以等待图永远无环，无环就不会死锁。
 *
 * <p><b>代价</b>：某些本可过的申请会被保守拒绝（误拒）。
 * 这比死锁的代价小——死锁要人介入，误拒只是重试一次。
 *
 * <h3>线程安全</h3>
 * 所有修改都是 {@code synchronized} 的。检测本身是 O(E)，很轻。
 * 这个类应该是**全局单例**——每个 TransferPlan 各持一个就失去意义了。
 */
public final class WaitForGraph {

    /** 等待者 → 它正在等的执行集合。 */
    private final Map<Long, Set<Long>> edges = new HashMap<>();

    /** 统计：拒绝了多少次（排查"为什么总抢不到"用）。 */
    private long rejectedCount;

    /**
     * 记录"waiter 在等 holder"。
     *
     * @return {@code true} = **会成环，应拒绝**（边不会加入）；
     *         {@code false} = 无环，可以等待（边已加入）
     */
    public synchronized boolean waitFor(long waiter, long holder) {
        if (waiter == holder) {
            // 自己等自己：说明状态机出错了，当成环处理
            return true;
        }
        // 先加边再检测：如果加了之后有环，说明这次等待会死锁
        Set<Long> targets = edges.computeIfAbsent(waiter, k -> new HashSet<>());
        targets.add(holder);

        if (hasCycle(waiter)) {
            targets.remove(holder);          // 撤销
            if (targets.isEmpty()) {
                edges.remove(waiter);
            }
            rejectedCount++;
            return true;
        }
        return false;
    }

    /**
     * 清除某个执行的所有等待边。
     *
     * <p>两种情况要调：
     * <ul>
     *   <li>它拿到了资源——不再等了</li>
     *   <li>它结束了/失败了——别人不该再等它</li>
     * </ul>
     *
     * <p>**忘了调会导致图里留下僵尸边**，把没死锁的情况判成死锁。
     */
    public synchronized void release(long runId) {
        edges.remove(runId);
        // 别人等它的边也要清——它已经不在等了，链条断了
        for (Set<Long> targets : edges.values()) {
            targets.remove(runId);
        }
        edges.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** 从 start 出发做 DFS，看能不能回到 start。 */
    private boolean hasCycle(long start) {
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> stack = new ArrayDeque<>();
        stack.push(start);

        while (!stack.isEmpty()) {
            long cur = stack.pop();
            for (long next : edges.getOrDefault(cur, Set.of())) {
                if (next == start) {
                    return true;          // 回到起点 = 环
                }
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 观测

    /** 当前有多少执行在等。 */
    public synchronized int waitingCount() {
        return edges.size();
    }

    /** 被拒绝了多少次。 */
    public synchronized long rejectedCount() {
        return rejectedCount;
    }

    /** 导出图（排查用）。格式：{runId: [等谁...]} */
    public synchronized Map<Long, Set<Long>> snapshot() {
        Map<Long, Set<Long>> copy = new HashMap<>();
        edges.forEach((k, v) -> copy.put(k, new HashSet<>(v)));
        return copy;
    }

    /** 重置（测试用）。 */
    public synchronized void reset() {
        edges.clear();
        rejectedCount = 0;
    }
}
