package com.labos.kernel.driver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 驱动注册表：把「按设备 id 找驱动」变成「按能力找设备」。
 *
 * <p>这是工艺与设备解耦的最后一环：
 * 工艺节点只说"我要 TRANSFER"，注册表给出"当前有哪几台设备能干这事、
 * 哪几台已经被隔离"。老系统把设备 id 写死在流程节点里，设备坏了流程就废了。
 */
public final class DriverRegistry {

    private final Map<String, DeviceDriver> drivers = new LinkedHashMap<>();
    private final Map<Long, String> deviceToDriver = new HashMap<>();
    private final Map<String, List<Long>> devicesByCapability = new LinkedHashMap<>();
    /** 被隔离的设备 -> 原因 */
    private final Map<Long, String> quarantined = new LinkedHashMap<>();

    public synchronized void register(DeviceDriver driver) {
        if (driver == null) {
            throw new IllegalArgumentException("driver 不能为空");
        }
        DriverDescriptor d = driver.describe();
        drivers.put(d.code(), driver);
        for (Long id : d.deviceInstanceIds()) {
            deviceToDriver.put(id, d.code());
        }
        // 关键：按「设备级能力」建索引，而不是「驱动能力 × 全部设备」
        for (Map.Entry<Long, Set<String>> e : d.deviceCapabilities().entrySet()) {
            for (String cap : e.getValue()) {
                List<Long> list = devicesByCapability.computeIfAbsent(cap, k -> new ArrayList<>());
                if (!list.contains(e.getKey())) {
                    list.add(e.getKey());
                }
            }
        }
    }

    /**
     * 某能力下当前可用的设备，按 id 升序（保证决策确定性，可回放）。
     *
     * @param exclude 需要排除的设备（如已在本节点失败过的）
     */
    public synchronized List<Long> candidates(String capability, Set<Long> exclude) {
        List<Long> all = devicesByCapability.getOrDefault(capability, List.of());
        return all.stream()
                .filter(id -> !quarantined.containsKey(id))
                .filter(id -> exclude == null || !exclude.contains(id))
                .distinct()
                .sorted()
                .toList();
    }

    public synchronized Optional<DeviceDriver> driverFor(long deviceInstanceId) {
        String code = deviceToDriver.get(deviceInstanceId);
        return code == null ? Optional.empty() : Optional.ofNullable(drivers.get(code));
    }

    /** 隔离一台设备（执行失败后调用），后续不再分配给它的任务。 */
    public synchronized void quarantine(long deviceInstanceId, String reason) {
        quarantined.put(deviceInstanceId, reason == null ? "unknown" : reason);
    }

    public synchronized void recover(long deviceInstanceId) {
        quarantined.remove(deviceInstanceId);
    }

    public synchronized boolean isQuarantined(long deviceInstanceId) {
        return quarantined.containsKey(deviceInstanceId);
    }

    public synchronized Set<String> capabilities() {
        return Set.copyOf(devicesByCapability.keySet());
    }

    public synchronized int driverCount() {
        return drivers.size();
    }

    public synchronized String healthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-24s %-8s %-6s %-6s %s%n",
                "驱动", "协议", "设备", "健康", "能力"));
        for (DeviceDriver d : drivers.values()) {
            DriverDescriptor desc = d.describe();
            int bad = (int) desc.deviceInstanceIds().stream()
                    .filter(quarantined::containsKey).count();
            sb.append(String.format("%-24s %-8s %-6d %-6s %s%s%n",
                    desc.code(), desc.protocol(), desc.deviceCount(),
                    d.healthy() ? "OK" : "DOWN",
                    desc.capabilities(),
                    bad > 0 ? "  [隔离 " + bad + "]" : ""));
        }
        return sb.toString();
    }
}
