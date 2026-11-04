package com.labos.kernel.driver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟驱动：内存实现，可注入故障，用于仿真与回归。
 *
 * <p>它和真实 gRPC 驱动实现同一个 {@link DeviceDriver}，
 * 所以「跑仿真」只是换一个实现，不是另开一条代码路径。
 */
public final class VirtualDriver implements DeviceDriver {

    private static final int IDEMPOTENT_CACHE_LIMIT = 2000;

    private final DriverDescriptor descriptor;
    /** key = deviceId:method，value = 剩余失败次数 */
    private final Map<String, Integer> faults = new HashMap<>();
    private final Map<String, String> faultCodes = new HashMap<>();
    /** 幂等缓存：只缓存成功结果，失败允许重试 */
    private final Map<String, DriverResult> succeeded = new LinkedHashMap<>();
    private final List<String> callLog = new ArrayList<>();
    private boolean healthy = true;

    public VirtualDriver(String code, String endpoint, String protocol,
                         Map<Long, Set<String>> deviceCapabilities) {
        Set<String> caps = new java.util.LinkedHashSet<>();
        deviceCapabilities.values().forEach(caps::addAll);
        this.descriptor = new DriverDescriptor(code, endpoint, protocol, "virtual",
                caps, deviceCapabilities, deviceCapabilities.keySet(),
                Map.of("simulated", "true"));
    }

    /** 注入故障：该设备该方法连续失败 times 次。 */
    public synchronized VirtualDriver fault(long deviceId, String method, int times, String errorCode) {
        String key = deviceId + ":" + method;
        faults.put(key, times);
        faultCodes.put(key, errorCode);
        return this;
    }

    public synchronized VirtualDriver setHealthy(boolean v) {
        this.healthy = v;
        return this;
    }

    @Override
    public DriverDescriptor describe() {
        return descriptor;
    }

    @Override
    public synchronized DriverResult execute(DriverCommand cmd) {
        // 幂等：已成功过的同 id 指令直接返回缓存，不重复动作
        DriverResult cached = succeeded.get(cmd.commandId());
        if (cached != null) {
            return cached;
        }

        callLog.add(cmd.deviceInstanceId() + ":" + cmd.method());

        if (!healthy) {
            return DriverResult.rejected(cmd.commandId(), "EC-06-0001", "驱动不可用");
        }
        String key = cmd.deviceInstanceId() + ":" + cmd.method();
        int remain = faults.getOrDefault(key, 0);
        if (remain > 0) {
            faults.put(key, remain - 1);
            String code = faultCodes.getOrDefault(key, "EC-08-0001");
            return DriverResult.failed(cmd.commandId(), code,
                    "模拟故障: " + cmd.method() + " @ device " + cmd.deviceInstanceId(),
                    "<DEVICE><error>" + code + "</error><detail>simulated</detail></DEVICE>",
                    12);
        }

        DriverResult ok = DriverResult.success(cmd.commandId(),
                "<DEVICE><status>ok</status><method>" + cmd.method() + "</method></DEVICE>", 8);
        succeeded.put(cmd.commandId(), ok);
        if (succeeded.size() > IDEMPOTENT_CACHE_LIMIT) {
            String first = succeeded.keySet().iterator().next();
            succeeded.remove(first);
        }
        return ok;
    }

    @Override
    public synchronized void abort(String commandId) {
        // 虚拟驱动瞬时完成，无需中止
    }

    @Override
    public boolean healthy() {
        return healthy;
    }

    public synchronized List<String> callLog() {
        return List.copyOf(callLog);
    }

    public synchronized int callCount(long deviceId, String method) {
        String key = deviceId + ":" + method;
        return (int) callLog.stream().filter(key::equals).count();
    }
}
