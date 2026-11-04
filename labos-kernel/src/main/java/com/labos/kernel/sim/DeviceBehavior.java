package com.labos.kernel.sim;

import java.util.Map;

/**
 * **型号专属行为**：让模拟器按这台机器真实的时序/前提条件回包。
 *
 * <p>为什么不能只靠 {@code DeviceProfile} 的通用校验：
 * 「门没关不能离心」这类规则是<b>每台机器</b>的物理约束，
 * 而 S100 还有「托盘不在原点不能封膜」「板型只有 1–8」这种
 * 只有读了手册才知道的规则。这些规则不实现，模拟器就只是个"回声器"，
 * 中控少发一条回原点指令也照样跑通，到现场才炸。
 *
 * <p>行为对象**持有一台设备的运行状态**（托盘位置、板型、模块是否创建…），
 * 与 {@link DeviceCore} 一一对应；进程退出即丢弃，不需要持久化。
 */
public interface DeviceBehavior {

    /** 型号 id，必须与 {@code DeviceModels} 里登记的一致。 */
    String modelId();

    /**
     * 处理一条命令。
     *
     * @return {@code null} 表示这条命令交回 {@link DeviceCore} 的默认逻辑
     */
    Reply handle(DeviceCore core, String method, String msg);

    /** 型号专属状态（GUI 与接口展示用）。 */
    default Map<String, Object> state() {
        return Map.of();
    }

    /**
     * 一条回复。
     *
     * @param vendorCode 设备原始错误码（如 {@code 0x8000001A}）；成功时为 null。
     *                   <b>不要在这里转成中控码</b>——翻译是中控侧的事，
     *                   模拟器必须像真设备一样只说自己的话。
     */
    record Reply(boolean ok, String vendorCode, String errorText, String dataJson) {

        public static Reply ok(String dataJson) {
            return new Reply(true, null, null, dataJson == null ? "{}" : dataJson);
        }

        public static Reply err(String vendorCode, String errorText) {
            return new Reply(false, vendorCode, errorText, "{}");
        }
    }
}
