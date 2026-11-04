package com.labos.kernel.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASpin 2K 全自动离心机的真实行为（依据：{@code AC离心机接口协议使用说明V2.0F.pdf}）。
 *
 * <p>手册里能确定的时序约束：
 * <ol>
 *   <li><b>先 {@code PcCreateModules} 建模块实例</b>（模块名 {@code Cluster}），
 *       否则后面任何指令都发不出去；</li>
 *   <li><b>先 {@code Init} 初始化（回原点）</b>，再发动作；</li>
 *   <li><b>两扇门</b>：{@code ControlCover byOpenCoverMode} 0=关、1=开 A、2=开 B；</li>
 *   <li>{@code Start} 参数是<b>时间/速度/加速度</b>，不是 rpm 名义值。</li>
 * </ol>
 *
 * <p>手册<b>没有</b>写"离心中能不能开门"（安全互锁）。模拟器按"拒绝"处理——
 * 这是保守假设，已记在型号 {@code note} 里，现场必须确认。
 *
 * <p>门状态同步给 {@link DeviceCore}，因为面板上要看"门开/关"、
 * 而通用状态机也要据此拒绝"门没关就离心"。
 */
final class AspinBehavior implements DeviceBehavior {

    /** {@code PcCreateModules} 是否已调用。 */
    private boolean modulesCreated;
    /** {@code Init} 是否已调用。 */
    private boolean initialized;
    private boolean doorA;
    private boolean doorB;
    private boolean running;
    private int speed;
    private int remainSec;
    private int lastTimeSec;

    @Override
    public String modelId() {
        return "aspin-2k";
    }

    @Override
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modulesCreated", modulesCreated);
        m.put("initialized", initialized);
        m.put("doorA", doorA);
        m.put("doorB", doorB);
        m.put("remainingSec", remainSec);
        m.put("lastTimeSec", lastTimeSec);
        return m;
    }

    @Override
    public Reply handle(DeviceCore core, String method, String msg) {
        // 1) 模块实例：手册要求先建，否则指令发不出去
        if ("PcCreateModules".equals(method) || "CreateModules".equals(method)) {
            modulesCreated = true;
            return Reply.ok("{\"modules\":\"PlateCtri\"}");
        }
        if (!modulesCreated) {
            return Reply.err("EC-05-2002",
                    "未创建模块实例（先发 PcCreateModules）");
        }

        switch (method) {
            case "Init", "Home" -> {
                initialized = true;
                running = false;
                speed = 0;
                remainSec = 0;
                setDoors(core, false, false);
                return Reply.ok("{\"initialized\":true,\"trayAtOrigin\":true}");
            }
            case "OpenDoor", "OpenDoorA", "OpenDoorB" -> {
                if (running) {
                    return Reply.err("EC-06-0006", "运行中禁止开门（安全互锁，手册未明确，模拟器按拒绝处理）");
                }
                if ("OpenDoorB".equals(method)) {
                    setDoors(core, doorA, true);
                } else {
                    setDoors(core, true, doorB);
                }
                return Reply.ok(doorsJson());
            }
            case "CloseDoor" -> {
                setDoors(core, false, false);
                return Reply.ok(doorsJson());
            }
            case "Start", "RunProgram", "Centrifuge" -> {
                if (!initialized) {
                    return Reply.err("EC-05-2003", "模块未初始化（先发 Init 回原点）");
                }
                if (doorA || doorB) {
                    return Reply.err("EC-06-0006", "门未关闭，不能离心");
                }
                Integer time = DeviceCore.intParam(msg, "time");
                if (time == null) {
                    time = DeviceCore.intParam(msg, "seconds");
                }
                if (time == null) {
                    time = DeviceCore.intParam(msg, "minutes");
                    if (time != null) {
                        time = time * 60;
                    }
                }
                Integer spd = DeviceCore.intParam(msg, "speed");
                if (spd == null) {
                    spd = DeviceCore.intParam(msg, "rpm");
                }
                if (time == null || time <= 0) {
                    return Reply.err("EC-06-0005", "离心时间必须大于 0（wCentriSec）");
                }
                if (spd == null || spd <= 0) {
                    return Reply.err("EC-06-0005", "离心速度必须大于 0（wCentriSpeed）");
                }
                running = true;
                lastTimeSec = time;
                remainSec = time;
                speed = spd;
                return Reply.ok("{\"started\":true,\"timeSec\":" + time
                        + ",\"speed\":" + spd + ",\"ramp\":"
                        + valueOr(DeviceCore.intParam(msg, "ramp"), 0) + "}");
            }
            case "Stop" -> {
                running = false;
                speed = 0;
                return Reply.ok("{\"stopped\":true}");
            }
            case "CurrSpeed", "GetSpeed" -> {
                return Reply.ok("{\"nRetValue\":" + (running ? speed : 0) + "}");
            }
            case "CurrTime", "GetRemainTime" -> {
                return Reply.ok("{\"nRetValue\":" + (running ? remainSec : 0) + "}");
            }
            case "GetStatus" -> {
                return Reply.ok("{\"running\":" + running
                        + ",\"remainingSec\":" + remainSec + "," + doorsJsonBody() + "}");
            }
            default -> {
                // 交回默认逻辑
            }
        }
        return null;
    }

    private void setDoors(DeviceCore core, boolean a, boolean b) {
        doorA = a;
        doorB = b;
        core.setDoorOpen(a || b);
    }

    private String doorsJson() {
        return "{" + doorsJsonBody() + "}";
    }

    private String doorsJsonBody() {
        return "\"doorA\":\"" + (doorA ? "open" : "closed") + "\","
                + "\"doorB\":\"" + (doorB ? "open" : "closed") + "\"";
    }

    private static int valueOr(Integer v, int def) {
        return v == null ? def : v;
    }
}
