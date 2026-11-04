package com.labos.kernel.sim;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备类型画像：一类设备"该有哪些方法、参数范围是多少、默认要跑多久"。
 *
 * <p><b>为什么要这个</b>：原来的模拟器只有 4 种类型、方法集写死在 switch 里、
 * 参数完全不校验、延迟固定 150ms。结果是中控把离心机配成 20000 rpm、
 * 把酶标仪波长配成 2000 nm，模拟器照样回 OK —— <b>错到现场才发现</b>。
 *
 * <p>这里按 {@code docs/模拟器设备库.md} 整理的厂商参数做边界校验，
 * 让"配错了"在模拟阶段就暴露。
 *
 * <p><b>参数值来源</b>：厂商公开规格（Eppendorf 5810、SpectraMax 190/ABS、
 * Tecan Freedom EVO、Biomek i3/i5/i7、BioTek 405 等）。
 * <b>仅用于边界校验</b>，不是采购依据。
 */
public final class DeviceProfile {

    /** 类型名（命令行 --profile 的值）。 */
    public final String name;
    /** 没显式给 --delay 时的默认耗时。真实设备的量级差异很大。 */
    public final long defaultDelayMs;
    public final Set<String> methods;

    /** 离心机转速范围（rpm）。 */
    public final int minRpm;
    public final int maxRpm;
    /** 移液量程（µL）。 */
    public final double minVolume;
    public final double maxVolume;
    /** 酶标仪波长范围（nm）。 */
    public final int minWavelength;
    public final int maxWavelength;
    /** 温控范围（°C）。 */
    public final double minTemp;
    public final double maxTemp;
    /** 板位/层位数量；0 表示不限制。 */
    public final int slots;

    /** 是否"有门"（离心机/孵育箱/酶标仪/板库）。 */
    public final boolean hasDoor;
    /** 是否"持板"型设备（机械臂：Grip/Release）。 */
    public final boolean holdsPlate;

    private DeviceProfile(String name, long defaultDelayMs, Set<String> methods,
                          int minRpm, int maxRpm,
                          double minVolume, double maxVolume,
                          int minWavelength, int maxWavelength,
                          double minTemp, double maxTemp, int slots,
                          boolean hasDoor, boolean holdsPlate) {
        this.name = name;
        this.defaultDelayMs = defaultDelayMs;
        this.methods = methods;
        this.minRpm = minRpm;
        this.maxRpm = maxRpm;
        this.minVolume = minVolume;
        this.maxVolume = maxVolume;
        this.minWavelength = minWavelength;
        this.maxWavelength = maxWavelength;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.slots = slots;
        this.hasDoor = hasDoor;
        this.holdsPlate = holdsPlate;
    }

    private static final Set<String> HOME = Set.of("Home", "GetStatus", "Stop");

    // ---------------------------------------------------------------- 画像表

    private static final Map<String, DeviceProfile> TABLE = Map.ofEntries(

            // 机械臂：Brooks PreciseFlex c3 / KUKA。搬一块板 2 秒量级。
            Map.entry("robot", new DeviceProfile("robot", 2_000,
                    Set.of("Home", "Transfer", "TransferTo", "GetPosition", "GetPoints",
                            "SyncPoints", "Grip", "Release", "GetStatus", "Stop"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    true, true)),

            // 移液工作站：Tecan Freedom EVO / Biomek i3-i7。0.5–5000 µL。
            Map.entry("liquid_handler", new DeviceProfile("liquid_handler", 1_500,
                    Set.of("Home", "GetTips", "DropTips", "Aspirate", "Dispense",
                            "Transfer", "Mix", "SetTemp", "GetStatus", "Stop"),
                    0, 0, 0.5, 5000, 0, 0, 4, 60, 45,
                    false, false)),

            // 离心机：Eppendorf 5810，200–14000 rpm，-9~40 °C，板转子 4 位。
            Map.entry("centrifuge", new DeviceProfile("centrifuge", 300_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Load", "Unload",
                            "RunProgram", "Centrifuge", "SetTemp", "GetStatus", "Stop"),
                    200, 14_000, 0, 0, 0, 0, -9, 40, 4,
                    true, false)),

            // 酶标仪：SpectraMax 190（190–850 nm）。
            Map.entry("reader", new DeviceProfile("reader", 5_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Load", "Unload",
                            "Read", "GetResult", "GetStatus"),
                    0, 0, 0, 0, 190, 850, 0, 0, 4,
                    true, false)),

            // 孵育箱：Thermo Heracell，室温+5 ~ 55 °C，CO2 0–20%。
            Map.entry("incubator", new DeviceProfile("incubator", 3_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Deposit1", "Withdraw1",
                            "SetTemp", "SetCO2", "GetTemp", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 5, 55, 20,
                    true, false)),

            // 洗板机：BioTek 405 TS。
            Map.entry("washer", new DeviceProfile("washer", 8_000,
                    Set.of("Home", "Load", "Unload", "Wash", "Dispense", "GetStatus"),
                    0, 0, 50, 300, 0, 0, 0, 0, 0,
                    false, false)),

            // 分液器：Thermo Multidrop，0.5–2500 µL。
            Map.entry("dispenser", new DeviceProfile("dispenser", 4_000,
                    Set.of("Home", "Dispense", "Prime", "GetStatus"),
                    0, 0, 0.5, 2500, 0, 0, 0, 0, 0,
                    false, false)),

            // 板存储：LiCONiC StoreX / Thermo Cytomat。
            Map.entry("plate_hotel", new DeviceProfile("plate_hotel", 6_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Store", "Retrieve", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 200,
                    true, false)),

            // 封膜机：Agilent PlateLoc，165 °C / 3.5 s 量级。
            Map.entry("sealer", new DeviceProfile("sealer", 12_000,
                    Set.of("Home", "Load", "Unload", "Seal", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 100, 200, 0,
                    false, false)),

            // 撕膜机。
            Map.entry("peeler", new DeviceProfile("peeler", 5_000,
                    Set.of("Home", "Load", "Unload", "Peel", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false)),

            // 条码扫描。
            Map.entry("barcode", new DeviceProfile("barcode", 400,
                    Set.of("Home", "Scan", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false)),

            // PCR 仪：4–100 °C。
            Map.entry("thermocycler", new DeviceProfile("thermocycler", 60_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "RunProgram", "SetTemp",
                            "GetStatus", "Stop"),
                    0, 0, 0, 0, 0, 0, 4, 100, 0,
                    true, false)),

            // 摇床：0–3000 rpm。
            Map.entry("shaker", new DeviceProfile("shaker", 30_000,
                    Set.of("Home", "Start", "Stop", "GetStatus"),
                    0, 3_000, 0, 0, 0, 0, 4, 60, 0,
                    false, false)),

            // 低温存储：-80 °C 量级。
            Map.entry("freezer", new DeviceProfile("freezer", 8_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Store", "Retrieve",
                            "SetTemp", "GetTemp", "GetStatus"),
                    0, 0, 0, 0, 0, 0, -90, -20, 100,
                    true, false))
    );

    /** generic：不校验方法也不校验参数（保持老行为，用于临时调试）。 */
    public static final DeviceProfile GENERIC = new DeviceProfile("generic", 150,
            Set.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

    public static DeviceProfile of(String name) {
        if (name == null || name.isBlank() || "generic".equals(name)) {
            return GENERIC;
        }
        return TABLE.get(name);
    }

    public static Set<String> knownNames() {
        return TABLE.keySet();
    }

    /**
     * 校验一条命令。
     *
     * @return null 表示通过；否则是给中控看的错误文本
     */
    public String validate(String method, String msg) {
        return validate(method, msg, true);
    }

    /**
     * 校验一条命令。
     *
     * @param checkMethods 是否校验"该方法属不属于这类设备"。
     *                     <b>有具体型号时要传 false</b>：型号才是方法集的权威
     *                     （S100 的 {@code MoveTray}/{@code SetParam} 不在通用
     *                     封膜机画像里，按画像校验会被误拒），
     *                     画像此时只用来做参数边界校验。
     */
    public String validate(String method, String msg, boolean checkMethods) {
        if (this == GENERIC) {
            return null;
        }
        if (checkMethods && !methods.contains(method)) {
            return "设备类型 " + name + " 不支持方法 " + method;
        }

        // ---- 转速（离心机 / 摇床）----
        if (maxRpm > 0 && ("RunProgram".equals(method) || "Centrifuge".equals(method)
                || "Start".equals(method))) {
            Double rpm = number(msg, "rpm");
            if (rpm != null && (rpm < minRpm || rpm > maxRpm)) {
                return String.format("转速 %.0f 超出机型范围 %d–%d rpm", rpm, minRpm, maxRpm);
            }
        }

        // ---- 量程（移液/分液/洗板）----
        if (maxVolume > 0 && ("Aspirate".equals(method) || "Dispense".equals(method)
                || "Transfer".equals(method) || "Mix".equals(method)
                || "Wash".equals(method))) {
            Double v = number(msg, "volume");
            if (v != null && (v < minVolume || v > maxVolume)) {
                return String.format("体积 %.2f µL 超出机型量程 %.1f–%.0f µL",
                        v, minVolume, maxVolume);
            }
        }

        // ---- 波长（酶标仪）----
        if (maxWavelength > 0 && "Read".equals(method)) {
            Double w = number(msg, "wavelength");
            if (w != null && (w < minWavelength || w > maxWavelength)) {
                return String.format("波长 %.0f nm 超出机型范围 %d–%d nm",
                        w, minWavelength, maxWavelength);
            }
        }

        // ---- 温度 ----
        if (minTemp != 0 && maxTemp != 0 && "SetTemp".equals(method)) {
            Double t = number(msg, "temp");
            if (t == null) {
                t = number(msg, "celsius");
            }
            if (t != null && (t < minTemp || t > maxTemp)) {
                return String.format("温度 %.1f °C 超出机型范围 %.0f–%.0f °C",
                        t, minTemp, maxTemp);
            }
        }

        // ---- CO2 ----
        if ("SetCO2".equals(method)) {
            Double co2 = number(msg, "co2");
            if (co2 != null && (co2 < 0 || co2 > 20)) {
                return String.format("CO2 %.1f%% 超出范围 0–20%%", co2);
            }
        }

        // ---- 板位/层位 ----
        if (slots > 0) {
            Double s = number(msg, "slot");
            if (s == null) {
                s = number(msg, "position");
            }
            if (s != null && (s < 1 || s > slots)) {
                return String.format("板位 %.0f 超出机型容量 1–%d", s, slots);
            }
        }
        return null;
    }

    /**
     * 单个方法的模拟耗时。
     *
     * <p><b>为什么不能全用一个延迟</b>：真实设备开门 2 秒、回原点 3 秒、
     * 离心 30 分钟。原来所有方法共用一个 delay，导致"开门"也要等 5 分钟，
     * 自动化测试直接超时 —— 而且完全不符合现场观感。
     *
     * <p>规则：
     * <ul>
     *   <li>查询类（GetStatus/GetPoints/Scan）—— 几百毫秒</li>
     *   <li>门/装夹类（OpenDoor/CloseDoor/Load/Unload/Grip/Release）—— 1.5 秒</li>
     *   <li>工艺类（RunProgram/Read/Wash/Seal…）—— 用该设备类型的默认耗时</li>
     * </ul>
     */
    public long methodDelayMs(String method) {
        if (this == GENERIC) {
            return defaultDelayMs;
        }
        return switch (method == null ? "" : method) {
            case "GetStatus", "GetPoints", "GetPosition", "Scan", "GetResult", "GetTemp" -> 300L;
            case "OpenDoor", "CloseDoor", "Load", "Unload", "Grip", "Release" -> 1_500L;
            case "Home" -> 3_000L;
            case "SyncPoints", "SetTemp", "SetCO2", "Prime", "Mix" -> 2_000L;
            default -> defaultDelayMs;
        };
    }

    /** 从 JSON 片段里取一个数字字段；取不到返回 null。 */
    private static Double number(String msg, String key) {
        if (msg == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(msg);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
