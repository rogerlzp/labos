package com.labos.kernel.device;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备业务状态（客户文档 §1.5）的**读时派生**解析器（2026-11-16）。
 *
 * <p><b>为什么是「读时派生」而不是「存一列」</b>：
 * <ul>
 *   <li>存一列就要**有人负责更新** ⇒ 漏一次更新，界面就一直显示旧状态
 *       （现场最常见的"界面骗人"）；</li>
 *   <li>而 {@code device_state} 这张表**正在被另一条线改动** ⇒ 加列会撞；</li>
 *   <li>演示要的恰恰是"**此刻**的状态" ⇒ 现算现出，没有"陈旧"这一类 bug。</li>
 * </ul>
 *
 * <p><b>派生优先级</b>（从高到低）：
 * <ol>
 *   <li><b>故障</b> —— {@code device_state.last_error} 非空 或 status='error'
 *       ⇒ 该类别的 {@code fault} 态（故障优先于一切）。</li>
 *   <li><b>在跑的命令</b> —— 有 status='running' 的命令（且其 run 未结束）
 *       ⇒ 匹配 (kind, trigger='command', source_key 含该方法) 的状态。</li>
 *   <li><b>最近一条设备事件</b> —— 且事件时间**晚于**最后一条命令的结束时间
 *       ⇒ 匹配 (kind, trigger='event', source_key 含该事件码) 的状态。
 *       <p>★ 这一条就是客户 §1.2 的核心：设备**主动上报**【等待交互】后，
 *       它就**一直保持"等待交互"**，直到下一个命令开始
 *       ⇒ 演示第一屏（"这台提取仪在等人"）靠的就是它。</li>
 *   <li><b>最近一条已结束的命令</b> ⇒ 匹配 trigger='command_done' 的状态
 *       （典型：分液器"分液完成"——分完了、板还没被取走的那几秒）。</li>
 *   <li><b>都没有</b> ⇒ 该类别的 {@code is_idle} 态（静止常态）。</li>
 * </ol>
 *
 * <p><b>耗材栈（STORE）是派生型</b>：按空闲槽位数
 * （≥6 充足 / 1~5 预警 / 0 不足）—— 与客户 §3 阶段 9-2「不足 6 块」同一口径。
 *
 * <p><b>口径提醒</b>：设备类别用的是**能力**（EXTRACT/WASH/MIX/TRANSFER/STORE），
 * <b>不是</b> {@code device.device_type}（那是厂商登记的原始分类："Extractors"/"Robots"…
 * 复数、含空格、有空值 ⇒ 按它查字典会一条都查不到）。
 */
public final class DeviceBusinessStateResolver {

    /** 派生结果：一台设备的当前业务状态。 */
    public record DeviceBusinessState(
            long instanceId,
            String deviceCode,
            long resourceId,
            /** 能力口径的设备类别（字典的分组键）；没配能力 ⇒ null */
            String kind,
            /** 业务状态码；该类别没配字典 ⇒ null */
            String stateCode,
            String stateName,
            /** 触发来源：fault / command / event / command_done / idle / derived */
            String source,
            /** 触发细节（方法名或事件码），便于现场看懂"为什么它在这个状态" */
            String sourceDetail,
            /** 该状态的判定时间 */
            Timestamp since) {
    }

    private final DbConfig db;
    private final long tenantId;
    private final DeviceStateMapper m;

    public DeviceBusinessStateResolver(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
        this.m = Mappers.get(DeviceStateMapper.class);
    }

    /**
     * 解析某岛（{@code islandId} 为 null ⇒ 全租户）的设备业务状态。
     *
     * @return 设备实例 id → 业务状态
     */
    public Map<Long, DeviceBusinessState> resolve(Long islandId) {
        // ---- ① 设备清单（实例 → 能力）
        List<Map<String, Object>> devices = m.selectDeviceKindList(tenantId, islandId);
        // ---- ② 字典（能力 → 状态定义）
        Map<String, List<Map<String, Object>>> defsByKind = new LinkedHashMap<>();
        for (Map<String, Object> d : m.selectBusinessStateDefs(tenantId)) {
            defsByKind.computeIfAbsent(str(d.get("deviceKind")), k -> new ArrayList<>()).add(d);
        }
        // ---- ③ 在跑的命令 / ④ 最近结束的命令 / ⑤ 最近事件
        Map<Long, Map<String, Object>> running = byDeviceId(m.selectRunningCommands(tenantId));
        Map<Long, Map<String, Object>> finished = byDeviceId(m.selectLastFinishedCommands(tenantId));
        Map<String, Map<String, Object>> latestEvent = new LinkedHashMap<>();
        for (Map<String, Object> e : m.selectLatestDeviceEvents(tenantId)) {
            latestEvent.putIfAbsent(str(e.get("deviceCode")), e);
        }
        // ---- ⑥ 设备状态行（故障优先判定用）
        Map<Long, Map<String, Object>> stateRows = new LinkedHashMap<>();
        for (Map<String, Object> s : m.selectDeviceStates(tenantId)) {
            stateRows.putIfAbsent(num(s.get("resourceId")), s);
        }
        // ---- ⑦ 耗材栈槽位（**批量**查：设备几百台，逐台查会让端点慢到不可用）
        Map<Long, long[]> slotStats = new LinkedHashMap<>();   // resourceId → [槽位总数, 空闲数]
        for (Map<String, Object> s : m.selectStackSlotStats(tenantId)) {
            slotStats.put(num(s.get("resourceId")),
                    new long[]{num(s.get("slots")), num(s.get("freeSlots"))});
        }
        Map<Long, Long> stackFree = new LinkedHashMap<>();
        for (Map<String, Object> d : devices) {
            long instanceId = num(d.get("instanceId"));
            long[] st = slotStats.get(num(d.get("resourceId")));
            // ⚠ 槽位数为 0 ⇒ 这个设备**根本不是耗材栈**（只是能力词表里也是 STORE）
            //   ⇒ 不给它"不足"（那是在编一个不存在的状态）。
            stackFree.put(instanceId, st == null || st[0] == 0 ? -1L : st[1]);
        }

        Map<Long, DeviceBusinessState> out = new LinkedHashMap<>();
        for (Map<String, Object> d : devices) {
            long instanceId = num(d.get("instanceId"));
            long resourceId = num(d.get("resourceId"));
            String code = str(d.get("deviceCode"));
            String kind = str(d.get("capability"));
            List<Map<String, Object>> defs = kind == null ? null : defsByKind.get(kind);
            if (defs == null || defs.isEmpty()) {
                // 该设备类别没配业务状态字典（如非这 5 类的设备）⇒ 不编一个状态出来
                out.put(instanceId, new DeviceBusinessState(
                        instanceId, code, resourceId, kind, null, null, "none", null, null));
                continue;
            }
            out.put(instanceId, derive(d, defs, kind, running, finished, latestEvent,
                    stackFree, stateRows));
        }
        return out;
    }

    // ---------------------------------------------------------------- 派生

    private DeviceBusinessState derive(Map<String, Object> device,
                                       List<Map<String, Object>> defs,
                                       String kind,
                                       Map<Long, Map<String, Object>> running,
                                       Map<Long, Map<String, Object>> finished,
                                       Map<String, Map<String, Object>> latestEvent,
                                       Map<Long, Long> stackFree,
                                       Map<Long, Map<String, Object>> stateRows) {
        long instanceId = num(device.get("instanceId"));
        long resourceId = num(device.get("resourceId"));
        String code = str(device.get("deviceCode"));

        // ---- ① 故障优先
        Map<String, Object> st = stateRows.get(resourceId);
        if (st != null && ("error".equals(str(st.get("status")))
                || (str(st.get("lastError")) != null && !str(st.get("lastError")).isBlank()))) {
            Map<String, Object> d = pick(defs, "fault", null);
            if (d != null) {
                return state(instanceId, code, resourceId, kind, d, "fault",
                        str(st.get("lastError")), ts(st.get("updatedAt")));
            }
        }

        // ---- 耗材栈：派生型（按空闲槽位）
        if ("derived".equals(triggerOf(defs))) {
            long free = stackFree.getOrDefault(instanceId, -1L);
            if (free < 0) {
                // 不是耗材栈 ⇒ 这一组状态（derived）不适用
                Map<String, Object> none = null;
                return state(instanceId, code, resourceId, kind, none, "none", null, null);
            }
            String stateCode = free >= 6 ? "sufficient" : (free > 0 ? "warning" : "insufficient");
            Map<String, Object> d = pick(defs, "derived", stateCode);
            if (d != null) {
                return state(instanceId, code, resourceId, kind, d, "derived",
                        "free=" + free, null);
            }
        }

        // ---- ② 在跑的命令
        Map<String, Object> run = running.get(instanceId);
        if (run != null) {
            String method = str(run.get("method"));
            Map<String, Object> d = pickBySourceKey(defs, "command", method);
            if (d != null) {
                return state(instanceId, code, resourceId, kind, d, "command",
                        method, ts(run.get("sentAt")));
            }
        }

        // ---- ③ 最近一条设备事件（且比最后一条命令**更新**）
        Map<String, Object> fin = finished.get(instanceId);
        Timestamp finAt = fin == null ? null : ts(fin.get("finishedAt"));
        Map<String, Object> ev = latestEvent.get(code);
        if (ev != null) {
            Timestamp evAt = ts(ev.get("createdAt"));
            boolean newer = evAt != null && (finAt == null || evAt.after(finAt));
            if (newer) {
                String eventType = str(ev.get("eventType"));
                Map<String, Object> d = pickBySourceKey(defs, "event", eventType);
                if (d != null) {
                    return state(instanceId, code, resourceId, kind, d, "event",
                            eventType, evAt);
                }
            }
        }

        // ---- ④ 最近一条已结束的命令
        if (fin != null) {
            boolean failed = "failed".equals(str(fin.get("status")));
            if (failed) {
                Map<String, Object> d = pick(defs, "fault", null);
                if (d != null) {
                    return state(instanceId, code, resourceId, kind, d, "fault",
                            str(fin.get("method")), finAt);
                }
            }
            Map<String, Object> d = pick(defs, "command_done", null);
            if (d != null) {
                return state(instanceId, code, resourceId, kind, d, "command_done",
                        str(fin.get("method")), finAt);
            }
        }

        // ---- ⑤ 静止常态
        Map<String, Object> d = pick(defs, "idle", null);
        if (d == null) {
            d = pick(defs, null, null);   // 兜底：取该类别第一条
        }
        return state(instanceId, code, resourceId, kind, d, "idle", null, null);
    }

    // ---------------------------------------------------------------- 字典查找

    /** 该类别是不是**派生型**（耗材栈那种按数量算的）。 */
    private static String triggerOf(List<Map<String, Object>> defs) {
        for (Map<String, Object> d : defs) {
            if ("derived".equals(str(d.get("trigger")))) {
                return "derived";
            }
        }
        return null;
    }

    /** 按 trigger 取该类别的那一条（派生型可按 stateCode 取）。 */
    private static Map<String, Object> pick(List<Map<String, Object>> defs,
                                            String trigger, String stateCode) {
        for (Map<String, Object> d : defs) {
            if (stateCode != null) {
                if (stateCode.equals(str(d.get("stateCode")))) {
                    return d;
                }
            } else if (trigger != null && trigger.equals(str(d.get("trigger")))) {
                return d;
            }
        }
        return null;
    }

    /** 按 trigger + source_key（方法名/事件码，逗号分隔）取那条。 */
    private static Map<String, Object> pickBySourceKey(List<Map<String, Object>> defs,
                                                       String trigger, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (Map<String, Object> d : defs) {
            if (!trigger.equals(str(d.get("trigger")))) {
                continue;
            }
            String src = str(d.get("sourceKey"));
            if (src == null || src.isBlank()) {
                continue;
            }
            for (String s : src.split(",")) {
                if (key.trim().equalsIgnoreCase(s.trim())) {
                    return d;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 小工具

    private DeviceBusinessState state(long instanceId, String code, long resourceId,
                                      String kind, Map<String, Object> def, String source,
                                      String detail, Timestamp since) {
        if (def == null) {
            return new DeviceBusinessState(instanceId, code, resourceId, kind,
                    null, null, source, detail, since);
        }
        return new DeviceBusinessState(instanceId, code, resourceId, kind,
                str(def.get("stateCode")), str(def.get("stateName")),
                source, detail, since);
    }

    private static Map<Long, Map<String, Object>> byDeviceId(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.putIfAbsent(num(r.get("deviceId")), r);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Timestamp ts(Object o) {
        if (o instanceof Timestamp t) {
            return t;
        }
        if (o instanceof java.util.Date d) {
            return new Timestamp(d.getTime());
        }
        return null;
    }
}
