package com.labos.kernel.recovery;

import java.util.Comparator;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 异常自愈决策引擎。
 *
 * <p>这是整个恢复子系统的核心，也是与老系统最本质的差异：
 * <b>它是一个纯函数</b>——不查库、不发消息、不碰设备，
 * 输入 {@link ErrorContext}，输出 {@link RecoveryDecision}。
 * 因此它可以被单元测试、被历史事件回放、被仿真推演。
 *
 * <p>决策优先级：
 * <ol>
 *   <li>无匹配策略 → <b>MANUAL</b>（不猜，交人）</li>
 *   <li>重试/换机次数耗尽 → <b>MANUAL</b>（附原因，不再无限重试刷日志）</li>
 *   <li>需换设备但无可用替代 → <b>MANUAL</b>（附候选与已失败清单）</li>
 *   <li>否则按策略执行</li>
 * </ol>
 */
public final class RecoveryDecider {

    private final RecoveryPolicyResolver resolver;
    private final RandomGenerator random;
    private final double jitterRatio;

    public RecoveryDecider(RecoveryPolicyResolver resolver) {
        this(resolver, null, 0.0);
    }

    public RecoveryDecider(RecoveryPolicyResolver resolver, RandomGenerator random, double jitterRatio) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver 不能为空");
        }
        this.resolver = resolver;
        this.random = random;
        this.jitterRatio = jitterRatio;
    }

    public RecoveryDecision decide(ErrorContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx 不能为空");
        }

        Optional<RecoveryPolicy> found = resolver.resolve(ctx);
        if (found.isEmpty()) {
            return RecoveryDecision.manual(String.format(
                    "无匹配策略（错误码=%s, 层级=%s），按安全默认交人工", ctx.errorCode(), ctx.scope()));
        }

        RecoveryPolicy p = found.get();

        // 次数耗尽——升级人工，而不是继续重试
        if (ctx.attempt() >= p.maxAttempts()) {
            return RecoveryDecision.manual(String.format(
                    "策略[%s]已耗尽：attempt=%d >= maxAttempts=%d，原意图=%s",
                    describe(p), ctx.attempt(), p.maxAttempts(), p.strategy()));
        }

        return switch (p.strategy()) {
            case RETRY -> {
                long delay = BackoffCalculator.compute(
                        p.backoff(), p.baseDelay(), ctx.attempt(), jitterRatio, random);
                yield RecoveryDecision.retry(delay, String.format(
                        "策略[%s] 第 %d/%d 次重试，等待 %dms",
                        describe(p), ctx.attempt() + 1, p.maxAttempts(), delay));
            }
            case REASSIGN -> {
                Long target = pickTarget(ctx);
                if (target == null) {
                    yield RecoveryDecision.manual(String.format(
                            "策略[%s] 需换设备，但无可用替代（候选=%s, 已失败=%s, 当前=%s）",
                            describe(p), ctx.candidateResourceIds(),
                            ctx.failedResourceIds(), ctx.currentResourceId()));
                }
                yield RecoveryDecision.reassign(target, String.format(
                        "策略[%s] 由资源 %s 切换至 %s", describe(p), ctx.currentResourceId(), target));
            }
            case SKIP -> RecoveryDecision.skip(String.format("策略[%s] 跳过该节点", describe(p)));
            case ABORT -> RecoveryDecision.abort(String.format("策略[%s] 终止执行", describe(p)));
            case MANUAL -> RecoveryDecision.manual(String.format(
                    "策略[%s] 指定直接转人工（attempt=%d）", describe(p), ctx.attempt()));
            case COMPENSATE -> RecoveryDecision.manual(String.format(
                    "策略[%s] 要求补偿回滚，该能力尚未实现，降级为人工（attempt=%d）",
                    describe(p), ctx.attempt()));
        };
    }

    /**
     * 选择替代资源：候选集中排除「已失败过」和「当前正在用的」，取 id 最小者。
     * 排序是为了确定性——同样的输入必须得到同样的输出，否则回放结果不可复现。
     */
    private Long pickTarget(ErrorContext ctx) {
        return ctx.candidateResourceIds().stream()
                .filter(id -> id != null)
                .filter(id -> !ctx.failedResourceIds().contains(id))
                .filter(id -> !id.equals(ctx.currentResourceId()))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static String describe(RecoveryPolicy p) {
        String code = p.errorCode() == null ? "*" : p.errorCode();
        String scope = p.scope() == null ? "*" : p.scope().name();
        return code + "@" + scope;
    }
}
