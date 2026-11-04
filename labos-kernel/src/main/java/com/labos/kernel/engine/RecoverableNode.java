package com.labos.kernel.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个可恢复的节点定义。
 *
 * <p>关键：{@code capabilityCode} 表达「需要什么能力」，
 * {@code candidateResourceIds} 是调度器给出的「能提供该能力的设备」。
 * 节点**不绑定某一台具体设备**——这是工艺与设备解耦的核心。
 *
 * <p>{@code method} 是驱动侧方法名（{@code protocol_name}）。为 null 时驱动回退到能力码。
 */
public record RecoverableNode(
        long id,
        String key,
        String name,
        String capabilityCode,
        String method,
        Long initialResourceId,
        Set<Long> candidateResourceIds) {

    public RecoverableNode {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (capabilityCode == null || capabilityCode.isBlank()) {
            throw new IllegalArgumentException("capabilityCode 不能为空");
        }
        candidateResourceIds = candidateResourceIds == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(candidateResourceIds));
    }

    /** 便捷构造：不带驱动方法名（方法名回退为能力码）。 */
    public static RecoverableNode of(long id, String key, String capabilityCode,
                                     Long initialResourceId, List<Long> candidates) {
        return new RecoverableNode(id, key, key, capabilityCode, null, initialResourceId,
                new LinkedHashSet<>(candidates));
    }

    /** 完整构造：带驱动方法名（真实工艺用）。 */
    public static RecoverableNode of(long id, String key, String name, String capabilityCode,
                                     String method, Long initialResourceId, List<Long> candidates) {
        return new RecoverableNode(id, key, name, capabilityCode, method, initialResourceId,
                new LinkedHashSet<>(candidates));
    }

    /** 复制并替换候选资源（用于剔除已知故障设备）。 */
    public RecoverableNode withCandidates(List<Long> candidates) {
        return new RecoverableNode(id, key, name, capabilityCode, method,
                candidates.isEmpty() ? null : candidates.get(0),
                new LinkedHashSet<>(candidates));
    }
}
