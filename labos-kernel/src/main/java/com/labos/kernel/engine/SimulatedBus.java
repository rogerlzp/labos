package com.labos.kernel.engine;

import com.labos.kernel.recovery.RecoveryScope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟设备总线：按配置注入故障，用于演示与测试。
 *
 * <p>它与真实驱动实现同一个 {@link StepExecutor} 接口，
 * 所以「仿真」不是另一个运行模式，只是换了一个实现——这正是新架构想要的。
 */
public final class SimulatedBus implements StepExecutor {

    /** 每台设备在成功前要失败几次。key = resourceId:capability */
    private final Map<String, Integer> remainingFaults = new HashMap<>();
    private final Map<String, String> faultCodes = new HashMap<>();
    private final Map<String, RecoveryScope> faultScopes = new HashMap<>();
    private final List<String> callLog = new ArrayList<>();

    /**
     * 配置故障。
     *
     * @param times 失败次数；{@link Integer#MAX_VALUE} 表示永久故障（设备坏了）
     */
    public SimulatedBus fail(long resourceId, String capability, String errorCode, int times) {
        return fail(resourceId, capability, errorCode, times, RecoveryScope.COMMAND);
    }

    public SimulatedBus fail(long resourceId, String capability, String errorCode,
                             int times, RecoveryScope scope) {
        String key = key(resourceId, capability);
        remainingFaults.put(key, times);
        faultCodes.put(key, errorCode);
        faultScopes.put(key, scope);
        return this;
    }

    /** 永久故障，模拟设备彻底离线。 */
    public SimulatedBus dead(long resourceId, String capability, String errorCode) {
        return fail(resourceId, capability, errorCode, Integer.MAX_VALUE);
    }

    public SimulatedBus dead(long resourceId, String capability, String errorCode, RecoveryScope scope) {
        return fail(resourceId, capability, errorCode, Integer.MAX_VALUE, scope);
    }

    @Override
    public void execute(StepContext ctx) throws StepFailure {
        String key = key(ctx.resourceId(), ctx.capabilityCode());
        callLog.add(key);

        Integer remain = remainingFaults.getOrDefault(key, 0);
        if (remain > 0) {
            remainingFaults.put(key, remain - 1);
            throw new StepFailure(faultCodes.get(key),
                    faultScopes.getOrDefault(key, RecoveryScope.COMMAND),
                    String.format("模拟故障 %s @ resource=%d", faultCodes.get(key), ctx.resourceId()));
        }
        // 成功：什么都不做
    }

    public List<String> callLog() {
        return List.copyOf(callLog);
    }

    public int callCount(long resourceId, String capability) {
        String key = key(resourceId, capability);
        return (int) callLog.stream().filter(key::equals).count();
    }

    private static String key(long resourceId, String capability) {
        return resourceId + ":" + capability;
    }
}
