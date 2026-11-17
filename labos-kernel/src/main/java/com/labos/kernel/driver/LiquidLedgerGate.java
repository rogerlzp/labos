package com.labos.kernel.driver;

import com.labos.kernel.sim.DeviceProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L1 移液账本闸门：在物理下发前，用**独立内存账本**预演一段协议，
 * 回答「这条指令，此刻能不能真的执行」。
 *
 * <p>与 PyLabRobot 的 volume_tracker、LabOS 的 plate_well 是**第三套**独立实现，
 * 所以它给出的反证不是"复读设备状态"，而是物理约束的硬校验：
 * <ul>
 *   <li>体积守恒：吸出的量不能超过源孔当前量；分液不能凭空产生</li>
 *   <li>枪头状态：未取枪头不能吸液；退枪头后才重新可取</li>
 *   <li>板位几何/容量：孔位必须存在、液面不得超过孔容量、槽跨度不得超过通道间距×通道数</li>
 *   <li>设备量程：volume 超出机型上下限（复用 {@link DeviceProfile#validate}）</li>
 * </ul>
 *
 * <p><b>安全语义（关键，别改错）</b>：这是**增量拦截层**，不是 fail-closed 的准入闸门。
 * 规则只拦「确定不可能」的动作；任何信息不足（孔位体积未知、参数缺失、画像为 GENERIC）
 * 都记为 WARN 并放行 —— 与对齐设计稿 D4「未登记=不拦」一致。
 * 原因很实际：生产搬运链路上有大量非移液设备，误拦的代价比漏拦更高；
 * 等 plate_well 写入方接通（M1）、规则登记完备后再逐条收紧。
 *
 * <p><b>同名方法陷阱</b>：中控的 {@code Transfer} 在 robot 上是「搬板」，
 * 在 liquid_handler 上才是「移液」。本闸门**只对显式声明 liquid_handler 画像的设备生效**，
 * 其他设备直接跳过——绝不按方法名猜测。
 *
 * <p>用法（调用方在派发前）：
 * <pre>{@code
 * LiquidLedgerGate.Verdict v = LiquidLedgerGate.check(profile, initialWells, commands);
 * if (!v.allow()) { // 返回带 EC-PHYS-* 反证的 DriverResult / StepFailure
 * }
 * }</pre>
 */
public final class LiquidLedgerGate {

    /** 孔容量默认上限（µL）：96 孔平底板常见 360 µL，与仿真器画像一致。 */
    public static final double DEFAULT_WELL_CAPACITY_UL = 360.0;
    /** 通道中心间距（mm）——由 VERID 案例反推：8 通道总跨度 72.0 ⇒ 72.0/(8−1)。 */
    public static final double CHANNEL_PITCH_MM = 72.0 / 7.0;
    /** 试剂槽可用长度（mm）——同一案例：8 通道跨度 72.0 > 71.2 ⇒ 拦；7 通道 61.7 ⇒ 放行。 */
    public static final double DEFAULT_TROUGH_LENGTH_MM = 71.2;

    private LiquidLedgerGate() {
    }

    /** 一条待预演的命令。 */
    public record Op(String method, Map<String, Object> params) {
        public Op {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
        public static Op of(String method, Map<String, Object> params) {
            return new Op(method, params);
        }
    }

    /** 单条反证。 */
    public record Finding(String code, String message) {
    }

    /** 闸门结论：allow=true 放行（可能带 warnings）；allow=false 拦下（带 blocking）。 */
    public record Verdict(boolean allow, List<Finding> blocking, List<Finding> warnings,
                          Map<String, Double> projectedWells) {
        public Verdict {
            blocking = List.copyOf(blocking);
            warnings = List.copyOf(warnings);
            projectedWells = Collections.unmodifiableMap(new LinkedHashMap<>(projectedWells));
        }
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        public String firstCode() {
            return blocking.isEmpty() ? null : blocking.get(0).code();
        }
        public String firstMessage() {
            return blocking.isEmpty() ? null : blocking.get(0).message();
        }
    }

    /**
     * 预演一段协议。
     *
     * @param profile      设备画像；null / GENERIC 时只做守恒与枪头检查，跳过量程与几何
     * @param initialWells 孔位初始体积，key 形如 {@code src_plate/A1}；null 视为全未知
     * @param ops          命令序列
     */
    public static Verdict check(DeviceProfile profile, Map<String, Double> initialWells,
                                List<Op> ops) {
        Map<String, Double> wells = new LinkedHashMap<>();
        if (initialWells != null) {
            wells.putAll(initialWells);
        }
        List<Finding> blocking = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();
        boolean hasTip = false;
        boolean knownGeometry = profile != null && profile != DeviceProfile.GENERIC;
        // 孔容量是耗材几何，与 profile.maxVolume（移液器量程）无关，不能混用
        double capacity = DEFAULT_WELL_CAPACITY_UL;
        if (ops == null) {
            ops = List.of();
        }
        int seq = 0;
        for (Op op : ops) {
            seq++;
            if (op == null || op.method() == null) {
                warnings.add(new Finding("EC-PHYS-UNKNOWN", "第 " + seq + " 步缺少方法名，跳过"));
                continue;
            }
            String m = op.method();
            Map<String, Object> p = op.params();

            // 设备量程（复用画像校验，但不校验方法集——型号才是方法权威）
            if (knownGeometry) {
                String rangeErr = profile.validate(m, toJson(p), false);
                if (rangeErr != null) {
                    blocking.add(new Finding("EC-PHYS-RANGE", "第 " + seq + " 步 " + m + "：" + rangeErr));
                }
            }

            double vol = number(p, "volume");
            boolean volKnown = p.containsKey("volume") && vol > 0;

            switch (m) {
                case "GetTips" -> {
                    if (hasTip) {
                        blocking.add(new Finding("EC-PHYS-TIP-DUP",
                                "第 " + seq + " 步取枪头：当前已持有枪头，重复取会浪费/碰撞"));
                    }
                    Integer count = integer(p, "count");
                    if (count != null && (count < 1 || count > 8)) {
                        blocking.add(new Finding("EC-PHYS-CHANNEL",
                                "第 " + seq + " 步取枪头通道数 " + count + " 超出 1–8"));
                    }
                    hasTip = true;
                }
                case "DropTips" -> {
                    if (!hasTip) {
                        warnings.add(new Finding("EC-PHYS-TIP-NONE",
                                "第 " + seq + " 步退枪头：账本显示当前无枪头（可能状态未知）"));
                    }
                    hasTip = false;
                }
                case "Aspirate" -> {
                    if (!hasTip) {
                        blocking.add(new Finding("EC-PHYS-TIP-MISSING",
                                "第 " + seq + " 步吸液前未取枪头"));
                    }
                    String key = wellKey(p, "labware", "well");
                    if (volKnown && key != null) {
                        Double cur = wells.get(key);
                        if (cur == null) {
                            warnings.add(new Finding("EC-PHYS-VOL-UNKNOWN",
                                    "第 " + seq + " 步源孔 " + key + " 体积未知，无法校验余量"));
                        } else if (cur + 1e-9 < vol) {
                            blocking.add(new Finding("EC-PHYS-VOL-NEGATIVE",
                                    String.format("第 %d 步源孔 %s 只有 %.2f µL，要吸 %.2f µL（吸后为负）",
                                            seq, key, cur, vol)));
                        } else {
                            wells.put(key, round(cur - vol));
                        }
                    }
                }
                case "Dispense" -> {
                    if (!hasTip) {
                        blocking.add(new Finding("EC-PHYS-TIP-MISSING",
                                "第 " + seq + " 步分液前未取枪头"));
                    }
                    String key = wellKey(p, "labware", "well");
                    if (volKnown && key != null) {
                        Double cur = wells.get(key);
                        double base = cur == null ? 0.0 : cur;
                        if (base + vol > capacity + 1e-9) {
                            blocking.add(new Finding("EC-PHYS-OVERFLOW",
                                    String.format("第 %d 步目标孔 %s 液面将达 %.2f µL，超过容量 %.1f µL",
                                            seq, key, base + vol, capacity)));
                        } else {
                            wells.put(key, round(base + vol));
                        }
                        if (cur == null) {
                            warnings.add(new Finding("EC-PHYS-VOL-UNKNOWN",
                                    "第 " + seq + " 步目标孔 " + key + " 初始体积未知，按 0 计入"));
                        }
                    }
                }
                case "Transfer" -> {
                    // liquid_handler 画像下的 Transfer 才是移液（robot 的搬板在入口已排除）
                    if (!hasTip) {
                        warnings.add(new Finding("EC-PHYS-TIP-NONE",
                                "第 " + seq + " 步 Transfer 账本显示无枪头（部分机型内建枪头，告警不拦）"));
                    }
                    String src = wellKey(p, "srcLabware", "srcWell");
                    String dst = wellKey(p, "dstLabware", "dstWell");
                    if (volKnown && src != null && dst != null) {
                        Double sCur = wells.get(src);
                        if (sCur == null) {
                            warnings.add(new Finding("EC-PHYS-VOL-UNKNOWN",
                                    "第 " + seq + " 步源孔 " + src + " 体积未知"));
                        } else if (sCur + 1e-9 < vol) {
                            blocking.add(new Finding("EC-PHYS-VOL-NEGATIVE",
                                    String.format("第 %d 步源孔 %s 只有 %.2f µL，要转 %.2f µL",
                                            seq, src, sCur, vol)));
                        } else {
                            wells.put(src, round(sCur - vol));
                            double dCur = wells.getOrDefault(dst, 0.0);
                            if (dCur + vol > capacity + 1e-9) {
                                blocking.add(new Finding("EC-PHYS-OVERFLOW",
                                        String.format("第 %d 步目标孔 %s 液面将达 %.2f µL，超过容量 %.1f µL",
                                                seq, dst, dCur + vol, capacity)));
                            } else {
                                wells.put(dst, round(dCur + vol));
                            }
                        }
                    }
                }
                case "Mix" -> {
                    // 吸-分为一轮，净液位不变；只校验孔位存在与量程（上面已做）
                    String key = wellKey(p, "labware", "well");
                    if (key == null) {
                        warnings.add(new Finding("EC-PHYS-PARAM",
                                "第 " + seq + " 步 Mix 缺少 labware/well，跳过几何校验"));
                    }
                }
                case "Span8Check" -> {
                    // 显式几何检查：槽长 vs 通道跨度。VERID 案例的可复用抽象。
                    Integer channels = integer(p, "channels");
                    double trough = number(p, "troughLengthMm");
                    int ch = channels == null ? 8 : channels;
                    double length = trough > 0 ? trough : DEFAULT_TROUGH_LENGTH_MM;
                    double span = (ch - 1) * CHANNEL_PITCH_MM;
                    if (span > length + 1e-9) {
                        blocking.add(new Finding("EC-PHYS-GEOMETRY",
                                String.format("第 %d 步 %d 通道跨度 %.1f mm > 槽长 %.1f mm（间距 %.1f mm），物理上无法全部浸入",
                                        seq, ch, span, length, CHANNEL_PITCH_MM)));
                    }
                }
                default -> {
                    // Home / GetStatus / Stop / SetTemp 等不改动液面账；不误报
                }
            }
        }
        return new Verdict(blocking.isEmpty(), blocking, warnings, wells);
    }

    /** 只放行「确定不可能」的动作；未知一律放行（增量拦截语义）。 */
    public static boolean allow(Verdict v) {
        return v != null && v.allow();
    }

    private static String wellKey(Map<String, Object> p, String labwareKey, String wellKey) {
        Object lw = p.get(labwareKey);
        Object wl = p.get(wellKey);
        if (lw == null || wl == null) {
            return null;
        }
        return String.valueOf(lw) + "/" + String.valueOf(wl).trim().toUpperCase();
    }

    private static double number(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Integer integer(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 给 DeviceProfile.validate 用的轻量 JSON：数字必须是裸数字（它的正则只认 :数字）。 */
    private static String toJson(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Map || v instanceof List) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** 便捷构造初始孔位账本。 */
    public static Map<String, Double> wells(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }
}
