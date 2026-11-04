package com.labos.kernel.driver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 驱动自描述：驱动启动时必须说清"我是谁、我能干什么、我管哪些设备"。
 *
 * <p>这是「新接一台设备不改中控代码」的前提——中控只依赖能力声明，
 * 不依赖任何设备型号知识。每接一台设备都要改接入层代码。
 */
public record DriverDescriptor(
        String code,
        String endpoint,
        String protocol,
        String driverKind,
        Set<String> capabilities,
        Map<Long, Set<String>> deviceCapabilities,
        Set<Long> deviceInstanceIds,
        Map<String, String> metadata) {

    public DriverDescriptor {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException(
                    "驱动必须声明至少一项能力——否则调度器无法把它分配给任何工艺节点");
        }
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
        deviceInstanceIds = deviceInstanceIds == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(deviceInstanceIds));
        Map<Long, Set<String>> copy = new LinkedHashMap<>();
        if (deviceCapabilities != null) {
            deviceCapabilities.forEach((k, v) ->
                    copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        }
        deviceCapabilities = Collections.unmodifiableMap(copy);
        metadata = metadata == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public boolean supports(String capability) {
        return capabilities.contains(capability);
    }

    /** 某台设备具体具备的能力（驱动级能力不等于设备级能力）。 */
    public Set<String> capabilitiesOf(long deviceInstanceId) {
        return deviceCapabilities.getOrDefault(deviceInstanceId, Set.of());
    }

    public int deviceCount() {
        return deviceInstanceIds.size();
    }
}
