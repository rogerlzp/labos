package com.labos.kernel.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S100 封膜机的真实行为（依据：{@code docs/设备/S100封膜机通信接口说明 .docx}）。
 *
 * <p>手册里的三条硬约束，模拟器必须实现，否则中控的流程缺陷测不出来：
 * <ol>
 *   <li><b>托盘不在原点不能封膜</b>，回 {@code 0x8000001A}。
 *       ⇒ 流程里封膜前必须有一条 {@code MoveTray(0)}；漏了就在仿真阶段暴露。</li>
 *   <li><b>板型只有 1–8</b>，超范围回 {@code 0x80000003}（参数范围有误）。</li>
 *   <li><b>收到不认识的指令</b>回 {@code 0x80000008}（收到错误指令）。</li>
 * </ol>
 *
 * <p>还有两条"发生过的现场事实"写在这里，因为排查时最费时间：
 * 换新耗材后仍报 {@code 0x8000000C}（无纸），原因是<b>耗材滚筒没带动从动滚轮</b>，
 * 需要弯折从动轮固定金属片。模拟器把这句话放进错误说明里，
 * 告警推给现场时能看到。
 */
final class S100Behavior implements DeviceBehavior {

    /** 托盘当前绝对位置；0 = 原点。 */
    private int trayPos;
    /** 孔板类型 1–8（手册：封膜时可顺带改）。 */
    private int plateType = 1;
    /** 参数表：索引 → 值（0x00 设置 / 0x01 读取）。 */
    private final Map<Integer, String> params = new LinkedHashMap<>();

    @Override
    public String modelId() {
        return "s100";
    }

    @Override
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trayPos", trayPos);
        m.put("trayAtOrigin", trayPos == 0);
        m.put("plateType", plateType);
        m.put("params", new LinkedHashMap<>(params));
        return m;
    }

    @Override
    public Reply handle(DeviceCore core, String method, String msg) {
        switch (method) {
            case "MoveTray" -> {
                Integer v = DeviceCore.intParam(msg, "value");
                if (v == null) {
                    v = DeviceCore.intParam(msg, "position");
                }
                if (v == null) {
                    v = 0;
                }
                if (v < 0) {
                    return Reply.err("0x80000009", "托盘位置为负（数值错误）");
                }
                trayPos = v;
                return Reply.ok("{\"trayPos\":" + trayPos + "}");
            }
            case "Reset", "Home" -> {
                trayPos = 0;
                return Reply.ok("{\"reset\":true,\"trayPos\":0}");
            }
            case "ClearFault", "ClearError" -> {
                return Reply.ok("{\"fault\":null}");
            }
            case "Seal" -> {
                Reply bad = checkBeforeSeal();
                return bad != null ? bad
                        : Reply.ok("{\"sealed\":true,\"trayPos\":0,\"plateType\":" + plateType + "}");
            }
            case "SealWithPlateType", "SealWithType" -> {
                // **先校验参数、再看托盘**：参数错是"你发错了"，跟托盘在哪无关；
                // 真机也是先验帧再动机械。反过来会让"板型填 9"报成"托盘不在原点"，
                // 现场会去查机械、白折腾半天。
                Integer type = DeviceCore.intParam(msg, "plateType");
                if (type == null) {
                    type = DeviceCore.intParam(msg, "index");
                }
                if (type == null || type < 1 || type > 8) {
                    return Reply.err("0x80000003",
                            "孔板类型必须是 1–8，收到 " + type);
                }
                Reply bad = checkBeforeSeal();
                if (bad != null) {
                    return bad;
                }
                plateType = type;
                return Reply.ok("{\"sealed\":true,\"plateType\":" + plateType + "}");
            }
            case "QueryPlateType", "GetPlateType" -> {
                return Reply.ok("{\"plateType\":" + plateType + "}");
            }
            case "GetVersion" -> {
                return Reply.ok("{\"version\":\"S100\"}");
            }
            case "SetParam" -> {
                Integer idx = DeviceCore.intParam(msg, "index");
                Integer val = DeviceCore.intParam(msg, "value");
                if (idx == null || val == null) {
                    return Reply.err("0x80000003", "设置参数需要 index 与 value");
                }
                params.put(idx, String.valueOf(val));
                return Reply.ok("{\"index\":" + idx + ",\"value\":" + val + "}");
            }
            case "ReadParam" -> {
                Integer idx = DeviceCore.intParam(msg, "index");
                if (idx == null) {
                    return Reply.err("0x80000003", "读参数需要 index");
                }
                return Reply.ok("{\"index\":" + idx + ",\"value\":"
                        + params.getOrDefault(idx, "0") + "}");
            }
            case "SaveParam" -> {
                return Reply.ok("{\"saved\":true}");
            }
            case "PrintParams" -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<Integer, String> e : params.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
                    first = false;
                }
                sb.append('}');
                return Reply.ok("{\"params\":" + sb + "}");
            }
            default -> {
                // GetStatus 是框架需要的查询（手册没有这条指令），交回默认逻辑
                if (!"GetStatus".equals(method)) {
                    // 手册里没有这个指令码 —— 真机会回 0x80000008（收到错误指令）
                    return Reply.err("0x80000008", "S100 不认识指令 " + method + "（收到错误指令）");
                }
            }
        }
        return null;
    }

    /** 封膜前的两条前提：先清故障，再看托盘在不在原点。 */
    private Reply checkBeforeSeal() {
        if (trayPos != 0) {
            return Reply.err("0x8000001A",
                    "托盘不在原点（当前 " + trayPos + "），无法封膜");
        }
        return null;
    }
}
