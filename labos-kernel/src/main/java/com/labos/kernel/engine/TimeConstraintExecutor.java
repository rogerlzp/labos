package com.labos.kernel.engine;

import com.labos.kernel.recovery.RecoveryScope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 时间约束：两个节点之间的**最短 / 最长间隔**。
 *
 * <p>这是规格 7.3 里 A 层关系表达不了的东西——A 层管的是"下一块流程何时启动"，
 * 管不了"取出和读板之间不得超过 30 分钟"。孵育窗口、撕膜后等待时间都靠它。
 *
 * <p>作为 {@link StepExecutor} 的装饰器，所以**两条执行路径都能用**
 * （顺序执行和按图执行都走 executor）。
 *
 * <p>三种行为：
 * <ul>
 *   <li>间隔不足 {@code minSec} → 补等（不是错误）</li>
 *   <li>间隔超过 {@code maxSec} → 抛 {@code EC-11-0001}，交给恢复策略
 *       （默认升 {@code MANUAL}，可配成 {@code ABORT}）</li>
 *   <li>前驱还没跑过 → 不检查（没有基准时刻）</li>
 * </ul>
 */
public final class TimeConstraintExecutor implements StepExecutor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TimeConstraintExecutor.class);

    /**
     * 一条约束。
     *
     * @param afterNodeId  相对哪个节点（前驱）计时
     * @param minSec       最短间隔（秒），0 表示不限制
     * @param maxSec       最长间隔（秒），0 表示不限制
     */
    public record Constraint(long afterNodeId, int minSec, int maxSec) {

        public boolean hasMin() {
            return minSec > 0;
        }

        public boolean hasMax() {
            return maxSec > 0;
        }
    }

    /** 节点完成时刻（key = nodeId）。 */
    private final Map<Long, Instant> finishedAt = new ConcurrentHashMap<>();

    private final StepExecutor inner;
    private final Map<Long, Constraint> constraints;
    private final Clock clock;
    private final long waitRealMs;

    /** 补等的硬上限：2 小时。超过就认为 minSec 配错了，不硬等。 */
    private static final long MAX_WAIT_MS = 2 * 60 * 60 * 1000L;

    /**
     * @param waitRealMs 是否真的 sleep 补等；仿真时传 0（只记录不等待）
     */
    public TimeConstraintExecutor(StepExecutor inner, Map<Long, Constraint> constraints,
                                  Clock clock, long waitRealMs) {
        this.inner = inner;
        this.constraints = constraints == null ? Map.of() : constraints;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.waitRealMs = waitRealMs;
    }

    @Override
    public void execute(StepContext ctx) throws StepFailure {
        Constraint c = constraints.get(ctx.nodeId());
        if (c != null) {
            Instant prev = finishedAt.get(c.afterNodeId());
            if (prev != null) {
                long gapSec = Duration.between(prev, clock.instant()).getSeconds();

                if (c.hasMax() && gapSec > c.maxSec()) {
                    throw new StepFailure("EC-11-0001", RecoveryScope.NODE,
                            String.format("间隔超限：距前序节点 %d 已 %.1f 分钟，上限 %d 分钟",
                                    c.afterNodeId(), gapSec / 60.0, c.maxSec() / 60),
                            ctx.nodeId() == 0 ? 0 : 1, ctx.effectiveMethod());
                }
                if (c.hasMin() && gapSec < c.minSec()) {
                    long needMs = (c.minSec() - gapSec) * 1000L;
                    // P1-15 修复（评审 20261102-03）：改动前是
                    //     Thread.sleep(Math.min(needMs, waitRealMs))
                    // —— waitRealMs 被当成了"最多等多久"（装配时传 60_000）。
                    // 工艺要求间隔 5 分钟，代码只等 60 秒就放行，
                    // 间隔照样不足，"补等"是假的。
                    //
                    // 现在语义改为：waitRealMs <= 0 = 仿真（只记录不等待），
                    // > 0 = 真等，且**等够 needMs**。
                    // 用 MAX_WAIT_MS 兜底，防止 minSec 配错（比如配成天）把线程挂死。
                    if (waitRealMs <= 0) {
                        log.info("[时间约束] 模拟模式，跳过补等 "
                                + (needMs / 1000) + " 秒（节点 " + ctx.nodeId() + "）");
                    } else if (needMs > 0) {
                        if (needMs > MAX_WAIT_MS) {
                            throw new StepFailure("EC-11-0003", RecoveryScope.NODE,
                                    "最短间隔要求等待 " + (needMs / 60000) + " 分钟，"
                                            + "超过上限 " + (MAX_WAIT_MS / 60000)
                                            + " 分钟，疑似配置错误",
                                    ctx.nodeId() == 0 ? 0 : 1, ctx.effectiveMethod());
                        }
                        try {
                            Thread.sleep(needMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new StepFailure("EC-11-0002", RecoveryScope.NODE,
                                    "等待最短间隔时被中断");
                        }
                    }
                }
            }
        }

        inner.execute(ctx);
        finishedAt.put(ctx.nodeId(), clock.instant());
    }

    /** 已记录的完成时刻（诊断用）。 */
    public Map<Long, Instant> finished() {
        return Map.copyOf(finishedAt);
    }
}
