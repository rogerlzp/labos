package com.labos.kernel.driver;

import com.labos.kernel.engine.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子模式：对真实设备发一条命令，只观察结果，不参与决策。
 *
 * <p>为什么先做「单命令探针」而不是「整条流程影子跑」：
 * 影子跑意味着流程会真实驱动设备走完全程——开盖、取板、放板都是物理动作，
 * 一旦中途契约不匹配，设备停在半路比不跑更糟。
 *
 * <p>先逐条命令验证契约（这个方法能不能调通、返回字段齐不齐、错误码是什么），
 * 全流程影子跑等单命令都通了再开。
 *
 * <p>规格 6.3 的完整影子模式是「同一执行器 + 只读驱动」；
 * 本类是它的第一级：<b>真驱动、单命令、决策丢弃</b>。
 */
public final class ShadowProbe {

    /** 一条探针结果。 */
    public record Probe(
            long resourceId,
            String method,
            boolean ok,
            String errorCode,
            String errorText,
            long elapsedMs,
            String rawResponse,
            /** 契约检查：返回原文里是否含必需字段。 */
            Map<String, Boolean> contract) {
    }

    private final DriverRegistry registry;

    public ShadowProbe(DriverRegistry registry) {
        this.registry = registry;
    }

    /**
     * 对指定设备发一条命令并观察。
     *
     * <p>无论如何都不抛异常——探针的价值在于"看到什么"，而不是"成功与否"。
     */
    public Probe probe(long resourceId, String method) {
        return probe(resourceId, method, java.util.Map.of());
    }

    /** 带参数下发。 */
    public Probe probe(long resourceId, String method, Map<String, Object> params) {
        Map<String, Boolean> contract = new LinkedHashMap<>();
        contract.put("messageId", false);
        contract.put("messageType", false);
        contract.put("method", false);
        contract.put("methodStatus", false);

        long t0 = System.nanoTime();

        DeviceDriver driver = registry.driverFor(resourceId).orElse(null);
        if (driver == null) {
            return new Probe(resourceId, method, false, "EC-06-0001",
                    "设备未注册到任何驱动端点", 0, null, contract);
        }

        try {
            DriverResult r = driver.execute(new DriverCommand(
                    "shadow-" + java.util.UUID.randomUUID(),
                    resourceId, method, params == null ? Map.of() : params, 15_000L, null));

            long ms = (System.nanoTime() - t0) / 1_000_000;
            String raw = r.deviceRawResponse();

            if (raw != null && !raw.isBlank()) {
                contract.put("messageId", raw.contains("message_id"));
                contract.put("messageType", raw.contains("message_type"));
                contract.put("method", raw.contains("\"method\""));
                contract.put("methodStatus", raw.contains("method_status"));
            }

            return new Probe(resourceId, method,
                    r.status() == DriverResult.Status.SUCCESS,
                    r.errorCode(), r.message(), ms, raw, contract);

        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new Probe(resourceId, method, false, "EC-99-0002",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), ms, null, contract);
        }
    }

    /** 批量探针：同一方法在多台设备上，看契约是否一致。 */
    public List<Probe> sweep(String method, List<Long> resourceIds) {
        List<Probe> out = new ArrayList<>();
        for (Long rid : resourceIds) {
            out.add(probe(rid, method));
        }
        return out;
    }

    /** 给 {@link StepContext} 用的便捷入口（影子模式下忽略 steps）。 */
    public static String describe(Probe p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.ok() ? "OK" : "FAIL");
        sb.append(" | ").append(p.method()).append(" @").append(p.resourceId());
        sb.append(" | ").append(p.elapsedMs()).append("ms");
        if (!p.ok() && p.errorCode() != null) {
            sb.append(" | ").append(p.errorCode());
        }
        long missing = p.contract().values().stream().filter(v -> !v).count();
        if (missing > 0) {
            sb.append(" | 契约缺 ").append(missing).append(" 个字段");
        }
        return sb.toString();
    }
}
