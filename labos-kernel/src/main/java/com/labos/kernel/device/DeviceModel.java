package com.labos.kernel.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **具体设备型号**的定义（不是"类型"）。
 *
 * <p>{@code sim.DeviceProfile} 描述的是**类别**（离心机、封膜机…）——
 * 该类设备"该有哪些方法、参数范围多少"；这里描述的是**具体某台机器的协议**：
 * 真实指令名、真实参数、真实错误码、动作超时。
 *
 * <p><b>为什么必须分两层</b>：离心机有很多型号，每家的指令名都不一样。
 * 只按"类型"建模时，接第二台离心机就得改代码；按"型号"建模后，
 * <b>加一台设备 = 加一条定义</b>，模拟器、适配器、文档都不用动逻辑。
 *
 * <p><b>一份定义，三处消费</b>（这是本类存在的核心理由）：
 * <ol>
 *   <li><b>模拟器</b>：按手册的方法名/参数/错误码回包，让中控在仿真阶段就撞到真问题；</li>
 *   <li><b>中控适配层</b>：{@link DeviceErrorMapper} 用同一份错误码表把
 *       设备原始码（{@code 0x8000000C}）翻译成中控错误码（{@code EC-08-300C}）；</li>
 *   <li><b>文档</b>：{@link DeviceErrorSql} 从这张表生成 {@code error_code} /
 *       {@code recovery_policy} 的 INSERT，避免"代码一份、SQL 一份"漂移。</li>
 * </ol>
 *
 * <p><b>诚实标注</b>：{@link Evidence} 区分"手册逐条可核"与"按公开规格/推算"。
 * 只有 {@link Evidence#MANUAL} 的型号可以拿去向客户承诺"能直接对接"。
 *
 * <p>命令有两个名字：
 * <ul>
 *   <li>{@code cmd} —— 设备手册上的**真实指令名**（如 {@code ControlCover}）</li>
 *   <li>{@code alias} —— 我们流程里习惯用的名字（如 {@code OpenDoorA}）</li>
 * </ul>
 * 模拟器**两个都接受**：用真实名测"能不能对接现场"，用别名跑"现有流程"。
 */
public final class DeviceModel {

    /** 这台型号的协议是怎么来的——决定了能不能对客户说"能直接接"。 */
    public enum Evidence {
        /** 有厂商手册，指令名/参数/错误码逐条可核。 */
        MANUAL,
        /** 只有公开规格（参数范围、方法族），指令名待现场核对。 */
        SPEC,
        /** 只知道型号，手册还没拿到：只能先按类型跑流程，不能对接真机。 */
        PLANNED
    }

    /** 错误处置建议：中控拿到这个错误码后该怎么办。 */
    public enum Disposition {
        /** 可以直接重试（瞬时故障）。 */
        RETRY,
        /** 参数问题：重试同样的参数没意义，改完再跑。 */
        FIX_PARAM,
        /** 必须人到现场处理（堵转、缺耗材、刀片脱落）。 */
        MANUAL,
        /** 直接终止，不要重试（协议/版本不匹配）。 */
        ABORT
    }

    /** 一条指令。 */
    public record Method(
            /** 设备手册上的真实指令名。 */
            String cmd,
            /** 流程里用的别名；null 表示与 {@code cmd} 相同。 */
            String alias,
            /** 真实参数（XML 片段 / 二进制字段说明 / null = 手册没给）。 */
            String args,
            /** 模拟耗时（毫秒）；0 = 用类型默认。 */
            long delayMs,
            /**
             * 手册里是否真的有这条指令。
             *
             * <p>{@code false} = 我们框架需要、但手册没写的（如 {@code GetStatus}）。
             * 这类方法**接真机时可能根本不存在**，必须现场确认，
             * 不能让"模拟器能跑"被误当成"真机能跑"。
             */
            boolean documented) {

        public boolean matches(String name) {
            return cmd.equals(name) || (alias != null && alias.equals(name));
        }

        public String aliasOf() {
            return alias == null || alias.isBlank() ? cmd : alias;
        }
    }

    /**
     * 一个设备错误码及其处置建议。
     *
     * @param vendorCode   设备侧原始码（{@code 0x8000000C} / {@code nRetValue=0}），
     *                     写进 {@code device_raw_response} 的就是它
     * @param kernelCode   中控侧归一码（{@code EC-08-400C}）；
     *                     {@code recovery_policy} 按这个查策略
     * @param action       给现场的人看的一句话（"换纸后按清除故障"）
     */
    public record ErrorDef(String vendorCode, String meaning, Disposition disposition,
                           String kernelCode, String action) { }

    public final String id;
    public final String type;
    public final String vendor;
    public final String model;
    /** 协议形态：{@code xml-dll} / {@code binary-serial} / {@code json-tcp}。 */
    public final String protocol;
    /** 物理层说明。 */
    public final String transport;
    /** 手册路径（相对仓库根）；没有手册就写 null。 */
    public final String manual;
    /** 证据等级。 */
    public final Evidence evidence;
    /** 门数（离心机 ASpin 2K 有 A/B 两扇门）；0 = 没有门。 */
    public final int doorCount;
    /**
     * 手册给出的"动作类命令最长要等多久"（毫秒）；0 = 手册没写。
     *
     * <p>ASpin 2K 手册的示例代码是 {@code WaitForEndOfAction(120000 + time*1000)}
     * ——离心 5 分钟要等 120s+300s=7 分钟。中控若按默认 15/30 秒超时，
     * <b>每次离心都会判超时</b>，然后触发重试（＝重复离心）。这个字段就是给超时策略用的。
     */
    public final long actionTimeoutMs;
    /** 补充说明（接真机前必须确认的点）。 */
    public final String note;

    /**
     * **错误码块**：每个型号分配一个字节，中控码 = {@code EC-08-<块><手册字节>}。
     *
     * <p>为什么必须有块：两台不同型号的机器可能都报 {@code 0x8000000C}，
     * 但含义完全可能不同（S100 是无纸，另一台可能是"门未关"）。
     * 只按字节编号会让两个型号抢同一个中控码，策略自然就串了。
     *
     * <p>块值用**十六进制两位数**，直接拼在码里方便人对：
     * 块 {@code 0x40} + 字节 {@code 0x0C} → {@code EC-08-400C}。
     * 现场看到 {@code 400C} 能立刻拆成"块 40 = S100，字节 0C = 无纸"。
     *
     * <p>分配表（**新加型号时从 42 往后取，不要复用**）：
     * <pre>
     *   0x40  s100        S100 封膜机
     *   0x41  aspin-2k    上海爱翱 ASpin 2K（手册未提供错误码表，预留）
     * </pre>
     *
     * <p>{@code 0} = 这个型号不占用错误码块（手册没给码表的型号）。
     */
    public final int errorBlock;

    /** 真实指令名 → 首条定义（同一 cmd 可能有多个别名，见 {@link #byAlias}）。 */
    private final Map<String, Method> byCmd = new LinkedHashMap<>();
    /** 别名 → 定义。 */
    private final Map<String, Method> byAlias = new LinkedHashMap<>();
    private final Map<String, ErrorDef> errors = new LinkedHashMap<>();

    public DeviceModel(String id, String type, String vendor, String model,
                       String protocol, String transport, String manual,
                       Evidence evidence, int doorCount, long actionTimeoutMs, String note) {
        this(id, type, vendor, model, protocol, transport, manual,
                evidence, doorCount, actionTimeoutMs, note, 0);
    }

    public DeviceModel(String id, String type, String vendor, String model,
                       String protocol, String transport, String manual,
                       Evidence evidence, int doorCount, long actionTimeoutMs, String note,
                       int errorBlock) {
        this.errorBlock = errorBlock;
        this.id = id;
        this.type = type;
        this.vendor = vendor;
        this.model = model;
        this.protocol = protocol;
        this.transport = transport;
        this.manual = manual;
        this.evidence = evidence;
        this.doorCount = doorCount;
        this.actionTimeoutMs = actionTimeoutMs;
        this.note = note;
    }

    /** 注册一条**手册里有**的指令（链式，便于在一个地方把手册抄完）。 */
    public DeviceModel method(String cmd, String alias, String args, long delayMs) {
        return method(cmd, alias, args, delayMs, true);
    }

    /** 注册一条指令；{@code documented=false} 表示手册没写、是我们补的。 */
    public DeviceModel method(String cmd, String alias, String args, long delayMs,
                              boolean documented) {
        Method m = new Method(cmd, alias, args, delayMs, documented);
        byCmd.putIfAbsent(cmd, m);
        byAlias.putIfAbsent(cmd, m);
        if (alias != null && !alias.isBlank()) {
            byAlias.putIfAbsent(alias, m);
        }
        return this;
    }

    /**
     * 注册一个**设备手册上的**错误码；中控码由块 + 字节自动算。
     *
     * <p>{@code vendorCode} 必须能解析出字节（{@code 0x8000000C} → {@code 0x0C}）。
     * 解析不出来直接抛错——在自检阶段炸，比到现场发现"这条码没翻译"好。
     */
    public DeviceModel error(String vendorCode, String meaning, Disposition d, String action) {
        if (errorBlock <= 0) {
            throw new IllegalStateException(id + " 没有分配错误码块（errorBlock），"
                    + "不能登记设备错误码 " + vendorCode);
        }
        int b = byteOf(vendorCode);
        String code = String.format("EC-08-%02X%02X", errorBlock, b);
        errors.put(vendorCode, new ErrorDef(vendorCode, meaning, d, code, action));
        return this;
    }

    /**
     * 注册**适配层自己产生的**码（不是设备回的），如 {@code EC-05-2002} 未建模块实例。
     * 这类码全型号通用，不进块。
     */
    public DeviceModel adapterError(String kernelCode, String meaning, Disposition d,
                                    String action) {
        errors.put(kernelCode, new ErrorDef(kernelCode, meaning, d, kernelCode, action));
        return this;
    }

    /** 从 {@code 0x8000000C} 里取出低字节 {@code 0x0C}。 */
    private static int byteOf(String vendorCode) {
        String s = vendorCode == null ? "" : vendorCode.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                // 用 long 解析：0x8000000C 超过 int 正数范围，parseInt 会抛溢出
                return (int) (Long.parseLong(s.substring(2), 16) & 0xFF);
            } catch (NumberFormatException e) {
                // 落到下面统一报错
            }
        }
        throw new IllegalArgumentException(
                "错误码 " + vendorCode + " 取不出字节 —— 设备码要写成 0x8000000C 这种十六进制");
    }

    /** 按真实名或别名查指令；查不到返回 null。 */
    public Method method(String name) {
        return name == null ? null : byAlias.get(name);
    }

    public boolean supports(String name) {
        return method(name) != null;
    }

    /** 去重后的指令表（按 cmd 首次出现的顺序）。 */
    public List<Method> methods() {
        List<Method> out = new ArrayList<>();
        for (Method m : byCmd.values()) {
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** 同一 cmd 的多个别名（如 ControlCover → OpenDoorA/OpenDoorB/CloseDoor）。 */
    public List<Method> aliases(String cmd) {
        List<Method> out = new ArrayList<>();
        for (Method m : byAlias.values()) {
            if (m.cmd().equals(cmd) && !out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    public Map<String, ErrorDef> errorTable() {
        return errors;
    }

    /** 错误码查处置建议；未知码返回 null（由调用方按"保守转人工"处理）。 */
    public ErrorDef error(String vendorCode) {
        return vendorCode == null ? null : errors.get(vendorCode);
    }

    /** 是否"只有型号、没有手册"的占位。占位型号不校验方法集（允许按类型跑流程）。 */
    public boolean placeholder() {
        return evidence == Evidence.PLANNED;
    }

    /** 概要，给 {@code --list-models} 用。 */
    public String summary() {
        return String.format("%-22s %-10s %-22s %-8s %2d 条指令  %2d 个错误码",
                id, type, vendor + " " + model, evidence, methods().size(), errors.size());
    }

    @Override
    public String toString() {
        return id;
    }
}
