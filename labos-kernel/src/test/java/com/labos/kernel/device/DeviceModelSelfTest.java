package com.labos.kernel.device;

import com.labos.kernel.device.DeviceModel.Disposition;
import com.labos.kernel.device.DeviceModel.ErrorDef;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.ErrorContext;
import com.labos.kernel.recovery.RecoveryAction;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryDecision;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 型号库 / 错误码映射 / 恢复决策 三件事的自检（**不需要数据库**）。
 *
 * <p>它回答的是三个具体问题：
 * <ol>
 *   <li>型号库自身有没有笔误（同一个中控码挂到两个设备码、方法别名解析不到…）；</li>
 *   <li>S100 的码能不能翻成中控码，未登记的码会不会被静默放过；</li>
 *   <li>翻出来的码**在恢复引擎里到底会做什么**——重试、转人工还是终止。</li>
 * </ol>
 *
 * <p>第 3 点是关键：前面两步都对、但策略写错 scope（比如写成 {@code command}
 * 而实际抛出的是 {@code node}），策略就永远匹配不上，
 * 表现是"所有错误都转人工"。这里用真实的 {@link RecoveryDecider} 跑一遍，
 * 而不是只看字符串。
 */
public final class DeviceModelSelfTest {

    private static int pass;
    private static int fail;
    private static int skip;

    public static void main(String[] args) {
        section("1. 型号库自身");
        checkAspin();
        checkS100();
        checkPlanned();
        checkAliasesResolvable();

        section("2. 设备错误码 → 中控码");
        checkTranslation();

        section("3. 中控码 → 恢复动作（真实 RecoveryDecider）");
        checkDecisions();

        section("4. 错误码字典 SQL 与型号库同步");
        checkSeedSql();

        System.out.println();
        System.out.printf("通过 %d, 失败 %d", pass, fail);
        if (skip > 0) {
            System.out.printf("（跳过 %d）", skip);
        }
        System.out.println();
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1 型号库

    /** ASpin 2K：手册里能核对的点。 */
    private static void checkAspin() {
        DeviceModel m = DeviceModels.of("aspin-2k");
        ok("aspin-2k 已登记", m != null);
        if (m == null) {
            return;
        }
        eq("依据是手册", DeviceModel.Evidence.MANUAL, m.evidence);
        eq("类型是离心机", "centrifuge", m.type);
        eq("两扇门（A/B）", 2, m.doorCount);
        // 手册示例 120000 + time*1000 —— 中控超时至少不能小于 120 秒
        ok("动作超时 ≥ 120s（手册 WaitForEndOfAction）", m.actionTimeoutMs >= 120_000);

        // 手册真实指令名，一个都不能少
        for (String cmd : List.of("PcCreateModules", "Init", "ControlCover", "Start",
                "Stop", "CurrSpeed", "CurrTime")) {
            ok("手册指令 " + cmd + " 已登记", m.supports(cmd));
        }
        // 三个别名指向同一条真实指令 —— 这是"一扇门三条指令"的正确建模
        eq("OpenDoorA → ControlCover", "ControlCover", cmdOf(m, "OpenDoorA"));
        eq("OpenDoorB → ControlCover", "ControlCover", cmdOf(m, "OpenDoorB"));
        eq("CloseDoor → ControlCover", "ControlCover", cmdOf(m, "CloseDoor"));
        eq("RunProgram → Start", "Start", cmdOf(m, "RunProgram"));
        eq("Home → Init", "Init", cmdOf(m, "Home"));
        // 不能凭空造一个 ControlCoverClose
        ok("没有伪造的 ControlCoverClose 指令", !m.supports("ControlCoverClose"));
        // GetStatus 手册没有，必须标成"非手册"
        DeviceModel.Method st = m.method("GetStatus");
        ok("GetStatus 标记为手册未提供", st != null && !st.documented());
    }

    /** S100：14 个错误码逐个核对（手册原文）。 */
    private static void checkS100() {
        DeviceModel m = DeviceModels.of("s100");
        ok("s100 已登记", m != null);
        if (m == null) {
            return;
        }
        eq("依据是手册", DeviceModel.Evidence.MANUAL, m.evidence);
        eq("错误码数量（手册 14 个，不是 15 个）", 14, m.errorTable().size());

        Map<String, String> expect = new LinkedHashMap<>();
        expect.put("0x80000001", "信号量溢出");
        expect.put("0x80000002", "错误消息类型");
        expect.put("0x80000003", "参数范围有误");
        expect.put("0x80000004", "CAN 通信错误");
        expect.put("0x80000005", "电机堵转");
        expect.put("0x80000006", "未检测到孔板");
        expect.put("0x80000007", "刀片脱落");
        expect.put("0x80000008", "收到错误指令");
        expect.put("0x80000009", "数值错误");
        expect.put("0x8000000A", "存储参数出错");
        expect.put("0x8000000B", "传感器失效");
        expect.put("0x8000000C", "无纸");
        expect.put("0x8000000E", "事件等待超时");
        expect.put("0x8000001A", "托盘不在原点，无法封膜");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            ErrorDef def = m.error(e.getKey());
            ok("错误码 " + e.getKey() + " 已登记", def != null);
            if (def != null) {
                eq(e.getKey() + " 含义", e.getValue(), def.meaning());
                ok(e.getKey() + " 有中控码", def.kernelCode() != null
                        && def.kernelCode().startsWith("EC-08-4"));
                ok(e.getKey() + " 有现场建议", def.action() != null && !def.action().isBlank());
            }
        }
        // 手册里没有 0x0D / 0x0F 这类码，不能自己编
        ok("没有编造 0x8000000D", m.error("0x8000000D") == null);

        // 处置分类：手册值钱的地方就在这
        eq("无纸 → 要人处理", Disposition.MANUAL, m.error("0x8000000C").disposition());
        eq("电机堵转 → 要人处理", Disposition.MANUAL, m.error("0x80000005").disposition());
        eq("信号量溢出 → 可重试", Disposition.RETRY, m.error("0x80000001").disposition());
        eq("托盘不在原点 → 改参数/补指令", Disposition.FIX_PARAM,
                m.error("0x8000001A").disposition());

        // 手册里 0x04 一条指令码挂三种用法（封膜 / 改板型 / 查板型），
        // 靠索引字节区分：0x00 / 0xA5 / 0x5A。这里要保证三者都标注了同一个指令码，
        // 否则适配层会以为是三条不同的指令。
        for (String alias : List.of("Seal", "SealWithType", "GetPlateType")) {
            DeviceModel.Method mm = m.method(alias);
            ok(alias + " 标注的指令码是 0x04", mm != null && mm.args().startsWith("0x04"));
        }
        eq("MoveTray 指令码", "0x0C", firstToken(m.method("MoveTray").args()));
        eq("ClearFault 指令码", "0x06", firstToken(m.method("ClearFault").args()));
    }

    private static void checkPlanned() {
        List<DeviceModels.Planned> planned = DeviceModels.planned();
        ok("待适配清单非空（离心机还有机型要接）", !planned.isEmpty());
        for (DeviceModels.Planned p : planned) {
            ok("待适配 " + p.id() + " 没有和已适配型号重名",
                    DeviceModels.of(p.id()) == null);
            ok("待适配 " + p.id() + " 写了缺什么", !p.missing().isBlank());
        }
    }

    /** 每个别名都要能解析回一条真实指令，否则模拟器会拒掉流程里的合法指令。 */
    private static void checkAliasesResolvable() {
        for (DeviceModel m : DeviceModels.all()) {
            for (DeviceModel.Method mm : m.methods()) {
                ok(m.id + " 别名 " + mm.aliasOf() + " 可解析",
                        m.method(mm.aliasOf()) != null);
                ok(m.id + " 真实名 " + mm.cmd() + " 可解析",
                        m.method(mm.cmd()) != null);
            }
        }
    }

    // ---------------------------------------------------------------- 2 翻译

    private static void checkTranslation() {
        DeviceErrorMapper mapper = new DeviceErrorMapper(Map.of(214L, "s100"), null);

        DeviceErrorMapper.Mapped paper = mapper.map(214L, "0x8000000C", "no paper");
        eq("0x8000000C → 中控码", "EC-08-400C", paper.kernelCode());
        eq("0x8000000C → 已登记", true, paper.known());
        eq("0x8000000C → 要人处理", Disposition.MANUAL, paper.disposition());
        eq("0x8000000C → 严重度 error", "error", paper.severity());
        ok("0x8000000C 的说明带上了型号", paper.describe().contains("s100"));

        DeviceErrorMapper.Mapped home = mapper.map(214L, "0x8000001A", null);
        eq("0x8000001A → 中控码", "EC-08-401A", home.kernelCode());
        eq("0x8000001A → 严重度 error", "error", home.severity());

        DeviceErrorMapper.Mapped retry = mapper.map(214L, "0x80000001", null);
        eq("可重试的码严重度是 warn（不要半夜叫人）", "warn", retry.severity());

        DeviceErrorMapper.Mapped abort = mapper.map(214L, "0x80000008", null);
        eq("协议不匹配是 fatal", "fatal", abort.severity());

        // 未登记：不能静默放过，也不能猜
        DeviceErrorMapper.Mapped unknown = mapper.map(214L, "0xDEADBEEF", "boom");
        eq("未登记码 → EC-08-9001", DeviceErrorMapper.UNKNOWN_CODE, unknown.kernelCode());
        eq("未登记码 → 未登记", false, unknown.known());
        eq("未登记码 → 转人工", Disposition.MANUAL, unknown.disposition());
        ok("未登记码保留原始码", unknown.vendorCode().contains("0xDEADBEEF"));
        ok("未登记码在说明里保留原文", unknown.meaning().contains("boom"));

        // 已经是中控码 → 不二次翻译
        DeviceErrorMapper.Mapped already = mapper.map(214L, "EC-06-0006", "门没关");
        eq("中控码原样透传", "EC-06-0006", already.kernelCode());

        // 没绑型号 → 只能报未登记（不能瞎猜成 s100）
        DeviceErrorMapper.Mapped noModel = mapper.map(999L, "0x8000000C", null);
        eq("没绑型号时不猜型号", false, noModel.known());
        eq("没绑型号时 modelId 为空", null, noModel.modelId());

        // 兜底型号：现场临时联调方便，但启动时要能看出来
        DeviceErrorMapper withDefault = new DeviceErrorMapper(Map.of(), "s100");
        eq("兜底型号生效", "EC-08-400C", withDefault.map(1L, "0x8000000C", null).kernelCode());
    }

    // ---------------------------------------------------------------- 3 决策

    /**
     * 把型号库的错误码灌进**真实的** RecoveryPolicyResolver + RecoveryDecider，
     * 看最终动作对不对。
     *
     * <p>这里刻意用 {@code RecoveryScope.NODE}：{@code DriverStepExecutor} 抛出
     * 设备失败时用的就是 NODE。写成 COMMAND 的种子策略永远匹配不上，
     * 这是本项目踩过的坑，自检必须把它钉住。
     */
    private static void checkDecisions() {
        List<RecoveryPolicy> policies = new ArrayList<>();
        for (ErrorDef def : DeviceModelSelfTest.allDefs()) {
            DeviceErrorSql.Policy p = DeviceErrorSql.policyOf(def.disposition());
            policies.add(new RecoveryPolicy(def.kernelCode(), RecoveryScope.NODE,
                    com.labos.kernel.recovery.RecoveryStrategy.valueOf(p.strategy().toUpperCase()),
                    p.maxAttempts(), Backoff.EXPONENTIAL,
                    Duration.ofMillis(500), p.priority(), true));
        }
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(policies));

        for (ErrorDef def : allDefs()) {
            RecoveryAction want = switch (def.disposition()) {
                case RETRY -> RecoveryAction.RETRY;
                case MANUAL, FIX_PARAM -> RecoveryAction.MANUAL;
                case ABORT -> RecoveryAction.ABORT;
            };
            RecoveryDecision d = decider.decide(
                    ErrorContext.of(def.kernelCode(), RecoveryScope.NODE, 0));
            eq(def.kernelCode() + "（" + def.meaning() + "）→ " + want, want, d.action());
        }

        // scope 写成 command 就匹配不上 —— 用反例钉住这个坑
        RecoveryPolicy wrongScope = new RecoveryPolicy("EC-08-400C", RecoveryScope.COMMAND,
                com.labos.kernel.recovery.RecoveryStrategy.RETRY, 3, Backoff.FIXED,
                Duration.ZERO, 1, true);
        RecoveryDecider wrong = new RecoveryDecider(
                new RecoveryPolicyResolver(List.of(wrongScope)));
        eq("scope 写错就退化成未知错误（所以种子必须写 node）",
                RecoveryAction.MANUAL,
                wrong.decide(ErrorContext.of("EC-08-400C", RecoveryScope.NODE, 0)).action());

        // 未登记码没有策略 → 安全默认：转人工
        RecoveryDecider empty = new RecoveryDecider(new RecoveryPolicyResolver(List.of()));
        eq("无策略时安全默认转人工", RecoveryAction.MANUAL,
                empty.decide(ErrorContext.of(DeviceErrorMapper.UNKNOWN_CODE,
                        RecoveryScope.NODE, 0)).action());
    }

    private static List<ErrorDef> allDefs() {
        List<ErrorDef> out = new ArrayList<>();
        for (DeviceErrorSql.Mapping m : DeviceErrorSql.allMappings()) {
            out.add(m.def());
        }
        return out;
    }

    // ---------------------------------------------------------------- 4 SQL

    /**
     * 检查种子 SQL 是否已包含型号库里的每个中控码。
     *
     * <p>为什么值得写：型号库改了、SQL 忘了重生成，运行时表现是
     * "日志里能看到设备码，恢复引擎却查不到策略"，排查成本很高。
     * 这里在单测阶段就把它挡住。
     */
    private static void checkSeedSql() {
        Path sql = findSeedSql();
        if (sql == null) {
            skip++;
            System.out.println("  ○ 找不到 pg_rebuild/schema/95_seed_data.sql，跳过 SQL 一致性检查");
            return;
        }
        String text;
        try {
            text = Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            skip++;
            System.out.println("  ○ 读不了 " + sql + "：" + e.getMessage());
            return;
        }
        Set<String> codes = new HashSet<>();
        for (ErrorDef d : allDefs()) {
            codes.add(d.kernelCode());
        }
        for (String code : codes) {
            ok("95_seed_data.sql 已包含 " + code, text.contains("'" + code + "'"));
        }
        ok("95_seed_data.sql 的策略 scope 是 node（不是 command）",
                text.contains("'node'"));
    }

    private static Path findSeedSql() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            Path cand = p.resolve("pg_rebuild/schema/95_seed_data.sql");
            if (Files.exists(cand)) {
                return cand;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private static String cmdOf(DeviceModel m, String alias) {
        DeviceModel.Method mm = m.method(alias);
        return mm == null ? "<未登记>" : mm.cmd();
    }

    /** 取 args 的第一个词（"0x0C + 托盘 Y 轴…" → "0x0C"）。 */
    private static String firstToken(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        return args.trim().split("[\\s+（(]")[0];
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 60 - title.length())));
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  ✓ " + what + (want == null ? "" : " = " + want));
        } else {
            fail++;
            System.out.println("  ✗ " + what + "：期望 " + want + "，实际 " + got);
        }
    }

    private DeviceModelSelfTest() {
    }
}
