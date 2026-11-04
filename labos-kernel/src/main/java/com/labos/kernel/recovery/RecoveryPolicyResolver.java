package com.labos.kernel.recovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 策略匹配器：从一组策略中挑出与上下文最匹配的那一条。
 *
 * <p>排序规则（决定了匹配优先级）：
 * <ol>
 *   <li>匹配精度高者优先（错误码+层级 &gt; 只匹配其一 &gt; 全通配）</li>
 *   <li>同精度时 priority 数字小者优先</li>
 *   <li>再相同则保持输入顺序（保证决策确定性，可回放）</li>
 * </ol>
 */
public final class RecoveryPolicyResolver {

    private static final Comparator<RecoveryPolicy> ORDER =
            Comparator.comparingInt(RecoveryPolicy::specificity).reversed()
                      .thenComparingInt(RecoveryPolicy::priority);

    private final List<RecoveryPolicy> ordered;

    public RecoveryPolicyResolver(Collection<RecoveryPolicy> policies) {
        if (policies == null) {
            throw new IllegalArgumentException("policies 不能为 null");
        }
        List<RecoveryPolicy> list = new ArrayList<>(policies);
        list.sort(ORDER);
        this.ordered = List.copyOf(list);
    }

    public Optional<RecoveryPolicy> resolve(ErrorContext ctx) {
        return ordered.stream().filter(p -> p.matches(ctx)).findFirst();
    }

    /** 已排序的策略列表（只读），便于诊断「为什么选了这条」。 */
    public List<RecoveryPolicy> policies() {
        return ordered;
    }
}
