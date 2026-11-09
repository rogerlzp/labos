package com.labos.kernel.workflow;

import com.labos.kernel.persistence.Jsons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态词表：实验域内「状态码 → 文案 / 颜色 / terminal / active」的<b>唯一权威</b>。
 *
 * <p>背景（ROADMAP P2「状态映射收口」，BUG-13/14 这类"词表不一致"的根）：
 * 前端 8 个页面各自维护一份 状态→文案/颜色 映射，后端
 * {@link ExperimentExecutor#syncExperimentStatus} 里另有一份 run→experiment 状态映射；
 * 同一状态在列表 / 详情 / 甘特上文案与颜色可能不同，run 与 experiment 之间还会出现
 * "列表显示已完成、详情显示失败"这类词表漂移。
 *
 * <p>本类把词表收口为一份<b>纯数据 + 纯函数</b>（不碰库、不读文件、无静态可变状态）：
 * <ul>
 *   <li>后端以 {@link #toJson()} 暴露 {@code GET /api/meta/status-vocabulary}，
 *       前端消费同一份（本类是权威，前端只做 fallback）；</li>
 *   <li>落库侧的 run→experiment 状态迁移 SQL 由 {@link #runToExperimentCaseSql()} 生成，
 *       保证"写入用的映射"与"展示用的映射"同源，改口径只改这里。</li>
 * </ul>
 *
 * <p>改词表只改本类（并递增 {@link #VERSION}），改完跑
 * {@code StatusVocabularySelfTest}（已注册进 {@code scripts/test-all.sh} 的 PURE 列表）。
 */
public final class StatusVocabulary {

    /** 契约版本：词表内容变更时同步递增，前端可据此判缓存是否过期。 */
    public static final String VERSION = "2026-11-09";

    /** scope 名（与前端、数据库列取值一致）。 */
    public static final String SCOPE_RUN = "run";
    public static final String SCOPE_NODE = "node";
    public static final String SCOPE_STEP = "step";
    public static final String SCOPE_EXPERIMENT = "experiment";
    public static final String SCOPE_BATCH = "batch";

    /**
     * {@code experiment_run.control_state} 的控制状态 scope（L-02 / ADR-PAUSE-008）。
     *
     * <p>与 {@code status}（生命周期）正交：{@code status} 回答"实验处在哪个阶段"，
     * 本 scope 回答"暂停控制过程走到哪了"。取值 {@code NONE / PAUSE_REQUESTED /
     * PAUSING / RESUMING}；列默认 {@code NONE}。L-02 <b>只授予表达能力不改行为</b>：
     * 值由 PR-S04-B 接线，当前落库仍恒为默认 {@code NONE}。
     */
    public static final String SCOPE_CONTROL = "control";

    /**
     * 一个状态的定义。
     *
     * @param code     状态码（落库值 / API 值）
     * @param label    展示文案（中文，前端不再各写一份）
     * @param color    颜色令牌：green/red/orange/gold/blue/default/processing/volcano
     * @param terminal 是否终态（不再变化，可落 finished_at）
     * @param active   是否"还在进行中"（调度/看板用，与 terminal 互补但非严格取反）
     */
    public record StatusDef(String code, String label, String color,
                            boolean terminal, boolean active) {
    }

    /**
     * 暂停类型定义（"为什么停的"）。
     *
     * @param auto 是否会自动恢复（设备心跳恢复即继续，无需人工）
     */
    public record PauseKindDef(String code, String label, String color,
                               boolean auto, String hint) {
    }

    /** 现场扣留类型定义。 */
    public record HoldKindDef(String code, String label, String color) {
    }

    /** 扣留作用域定义。 */
    public record HoldScopeDef(String code, String label) {
    }

    // ------------------------------------------------------------ 数据（唯一事实来源）

    /** scope -> 有序状态列表。插入序即前端展示序，勿随意调整。 */
    private static final Map<String, List<StatusDef>> SCOPES = new LinkedHashMap<>();

    /**
     * run.status -> experiment.status 的有序映射。
     *
     * <p>恒等项（failed→failed / running→running / …）也显式列出：契约要求前端拿到
     * 完整映射，同时 CASE 生成"按 map 迭代序"产出的 SQL 对任意输入与改前
     * 「completed→finished，其余原样」逐状态等价（恒等 WHEN 的 THEN 与 ELSE 同值）。
     */
    private static final Map<String, String> RUN_TO_EXPERIMENT = new LinkedHashMap<>();

    private static final Map<String, String> RUN_TO_EXPERIMENT_VIEW =
            Collections.unmodifiableMap(RUN_TO_EXPERIMENT);

    private static final List<PauseKindDef> PAUSE_KINDS = List.of(
            new PauseKindDef("paused_fault", "设备故障", "red", true,
                    "设备心跳恢复后会自己继续，通常不用管；超过 10 分钟仍未恢复建议检查设备"),
            new PauseKindDef("waiting_human", "等待人工", "orange", false,
                    "重试耗尽或需要判断，确认现场后点继续"),
            new PauseKindDef("paused_unknown", "板位未知", "volcano", false,
                    "板在哪不确定，必须到现场盘点后填写实际位置"));

    private static final List<HoldKindDef> HOLD_KINDS = List.of(
            new HoldKindDef("fault", "设备故障", "red"),
            new HoldKindDef("plate_unknown", "板位未知", "volcano"),
            new HoldKindDef("human", "等人工确认", "orange"),
            new HoldKindDef("manual", "人工暂停", "orange"));

    private static final List<HoldScopeDef> HOLD_SCOPES = List.of(
            new HoldScopeDef("site", "全场"),
            new HoldScopeDef("island", "岛"),
            new HoldScopeDef("experiment", "本实验"));

    static {
        SCOPES.put(SCOPE_RUN, List.of(
                new StatusDef("draft", "草稿", "default", false, false),
                new StatusDef("pending", "待执行", "default", false, false),
                new StatusDef("planned", "已排程", "blue", false, false),
                new StatusDef("queued", "排队中", "default", false, true),
                new StatusDef("running", "执行中", "blue", false, true),
                new StatusDef("paused", "已暂停", "orange", false, true),
                new StatusDef("quarantined", "隔离待确认", "red", false, true),
                new StatusDef("completed", "已完成", "green", true, false),
                new StatusDef("failed", "失败", "red", true, false),
                new StatusDef("aborted", "已终止", "red", true, false),
                new StatusDef("cancelled", "已取消", "default", true, false),
                new StatusDef("skipped", "已跳过", "default", true, false)));

        SCOPES.put(SCOPE_NODE, List.of(
                new StatusDef("pending", "未执行", "default", false, false),
                new StatusDef("ready", "待就绪", "default", false, false),
                new StatusDef("running", "执行中", "blue", false, true),
                new StatusDef("completed", "已完成", "green", true, false),
                new StatusDef("failed", "失败", "red", true, false),
                new StatusDef("manual", "待人工", "orange", false, true),
                new StatusDef("skipped", "已跳过", "gold", true, false),
                new StatusDef("aborted", "已终止", "red", true, false),
                new StatusDef("bypassed", "分支未选中", "default", true, false)));

        // P-11 核实（2026-11-09）：step 落到 run_command.status，取值由
        // WorkflowService.stepStatus() 决定 —— completed / aborted / failed。
        //   · 原来这里写的是 "paused"，但**没有任何写入路径**会产生 step 级 paused
        //     （暂停发生在 run/node 边界，命令层不会被暂停）⇒ 删掉，别让界面
        //     承诺一个永远不会出现的状态；
        //   · 原来**缺** "aborted"，而 EC-99-0005（命令被中止）就是落成 aborted
        //     ⇒ 补上，否则界面显示原始码。
        SCOPES.put(SCOPE_STEP, List.of(
                new StatusDef("pending", "未执行", "default", false, false),
                new StatusDef("running", "执行中", "blue", false, true),
                new StatusDef("completed", "已执行", "green", true, false),
                new StatusDef("failed", "失败", "red", true, false),
                new StatusDef("aborted", "已中止", "orange", true, false),
                new StatusDef("skipped", "已跳过", "orange", true, false)));

        SCOPES.put(SCOPE_EXPERIMENT, List.of(
                new StatusDef("created", "已建", "default", false, false),
                new StatusDef("planned", "已排程", "blue", false, false),
                new StatusDef("queued", "排队中", "default", false, true),
                new StatusDef("running", "执行中", "processing", false, true),
                new StatusDef("paused", "已暂停", "orange", false, true),
                new StatusDef("completed", "已完成", "green", true, false),
                new StatusDef("finished", "已完成", "green", true, false),
                new StatusDef("failed", "失败", "red", true, false),
                new StatusDef("aborted", "已终止", "default", true, false),
                new StatusDef("cancelled", "已取消", "default", true, false),
                new StatusDef("skipped", "已跳过", "default", true, false),
                new StatusDef("deleted", "已删除", "default", true, false)));

        SCOPES.put(SCOPE_BATCH, List.of(
                new StatusDef("pending", "待执行", "default", false, false),
                new StatusDef("queued", "排队中", "default", false, true),
                new StatusDef("running", "执行中", "blue", false, true),
                new StatusDef("paused", "已暂停", "orange", false, true),
                new StatusDef("completed", "已完成", "green", true, false),
                new StatusDef("failed", "失败", "red", true, false),
                new StatusDef("aborted", "已终止", "default", true, false)));

        // control：暂停控制过程（与 status 生命周期正交，见 SCOPE_CONTROL）。
        // NONE=无控制过程；其余三态都是"在途"，故 active=true（看板可据此提示"控制中"）。
        // 控制过程不设终态：走完自然回到 NONE，因此 terminal 一律 false。
        SCOPES.put(SCOPE_CONTROL, List.of(
                new StatusDef("NONE", "无", "default", false, false),
                new StatusDef("PAUSE_REQUESTED", "已请求暂停", "gold", false, true),
                new StatusDef("PAUSING", "暂停中", "orange", false, true),
                new StatusDef("RESUMING", "恢复中", "blue", false, true)));

        RUN_TO_EXPERIMENT.put("completed", "finished");
        RUN_TO_EXPERIMENT.put("failed", "failed");
        RUN_TO_EXPERIMENT.put("quarantined", "failed");
        RUN_TO_EXPERIMENT.put("running", "running");
        RUN_TO_EXPERIMENT.put("paused", "paused");
        RUN_TO_EXPERIMENT.put("aborted", "aborted");
        RUN_TO_EXPERIMENT.put("cancelled", "cancelled");
        RUN_TO_EXPERIMENT.put("skipped", "skipped");
        RUN_TO_EXPERIMENT.put("queued", "queued");
    }

    private StatusVocabulary() {
    }

    // ------------------------------------------------------------ 查询

    /** 全部 scope 名，按契约顺序（run/node/step/experiment/batch/control）。 */
    public static List<String> scopes() {
        return List.copyOf(SCOPES.keySet());
    }

    /** 某 scope 的有序状态列表；未知 scope 返回空列表（不抛异常，读侧保守）。 */
    public static List<StatusDef> statuses(String scope) {
        List<StatusDef> defs = scope == null ? null : SCOPES.get(scope);
        return defs == null ? List.of() : defs;
    }

    /** 查一个状态定义；未知 scope / code 返回 {@code null}（是否兜底由调用方定）。 */
    public static StatusDef find(String scope, String code) {
        if (code == null) {
            return null;
        }
        for (StatusDef d : statuses(scope)) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /** 是否终态；未知状态按"非终态"处理（保守：不把不认识的码当成结束了）。 */
    public static boolean isTerminal(String scope, String code) {
        StatusDef d = find(scope, code);
        return d != null && d.terminal();
    }

    /** 是否进行中；未知状态按"非进行中"处理。 */
    public static boolean isActive(String scope, String code) {
        StatusDef d = find(scope, code);
        return d != null && d.active();
    }

    /** 颜色令牌；未知状态回退 {@code default}（前端不必再判空）。 */
    public static String colorOf(String scope, String code) {
        StatusDef d = find(scope, code);
        return d == null ? "default" : d.color();
    }

    /** 展示文案；未知状态回退 code 本身（宁可显示原始码，也不显示空白）。 */
    public static String labelOf(String scope, String code) {
        StatusDef d = find(scope, code);
        return d == null ? code : d.label();
    }

    /** 暂停类型列表（顺序即前端展示序）。 */
    public static List<PauseKindDef> pauseKinds() {
        return PAUSE_KINDS;
    }

    /** 按 code 查暂停类型；未知返回 {@code null}。 */
    public static PauseKindDef pauseKind(String code) {
        if (code == null) {
            return null;
        }
        for (PauseKindDef d : PAUSE_KINDS) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /** 现场扣留类型列表。 */
    public static List<HoldKindDef> holdKinds() {
        return HOLD_KINDS;
    }

    /** 按 code 查扣留类型；未知返回 {@code null}。 */
    public static HoldKindDef holdKind(String code) {
        if (code == null) {
            return null;
        }
        for (HoldKindDef d : HOLD_KINDS) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /** 扣留作用域列表。 */
    public static List<HoldScopeDef> holdScopes() {
        return HOLD_SCOPES;
    }

    /** 扣留作用域的展示文案（如 site -> 全场）；未知回退 code。 */
    public static String holdScopeLabel(String code) {
        if (code == null) {
            return null;
        }
        for (HoldScopeDef d : HOLD_SCOPES) {
            if (d.code().equals(code)) {
                return d.label();
            }
        }
        return code;
    }

    /** run→experiment 映射（只读视图，迭代序 = 契约顺序 = CASE 生成序）。 */
    public static Map<String, String> runToExperiment() {
        return RUN_TO_EXPERIMENT_VIEW;
    }

    // ------------------------------------------------------------ SQL 生成

    /**
     * 由 {@link #runToExperiment()} 生成 {@code experiment} 状态回写用的 SQL CASE 片段。
     *
     * <p>形如：{@code CASE WHEN r.status = 'completed' THEN 'finished' WHEN ... ELSE r.status END}。
     * 由映射表生成而不是手写，保证"写入口径"与"接口词表"同源；顺序按 {@link LinkedHashMap}
     * 迭代序，产出稳定、可被自测逐字钉死。
     *
     * <p>片段只含词表里的<b>受控常量</b>（无外部输入），{@link #sqlLiteral} 的转义只是防御性写法。
     */
    public static String runToExperimentCaseSql() {
        StringBuilder sb = new StringBuilder("CASE");
        for (Map.Entry<String, String> e : RUN_TO_EXPERIMENT.entrySet()) {
            sb.append(" WHEN r.status = ").append(sqlLiteral(e.getKey()))
                    .append(" THEN ").append(sqlLiteral(e.getValue()));
        }
        return sb.append(" ELSE r.status END").toString();
    }

    /** 单引号 SQL 字面量（词表常量专用；单引号按 SQL 标准双写转义）。 */
    static String sqlLiteral(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------ JSON 契约

    /**
     * 输出契约 JSON：{@code version / scopes / pauseKinds / holdKinds / holdScopes / transitions}。
     *
     * <p>字段名与 {@code GET /api/meta/status-vocabulary} 契约逐字一致；数组顺序即展示序。
     * 用 {@link Jsons} 组装（零依赖、与 ApiServer 其它接口同一套序列化）。
     */
    public static String toJson() {
        List<String> scopePairs = new ArrayList<>();
        for (Map.Entry<String, List<StatusDef>> e : SCOPES.entrySet()) {
            scopePairs.add(Jsons.quote(e.getKey()) + ":" + statusesJson(e.getValue()));
        }
        List<String> transitionPairs = new ArrayList<>();
        RUN_TO_EXPERIMENT.forEach((from, to) ->
                transitionPairs.add(Jsons.quote(from) + ":" + Jsons.quote(to)));
        return Jsons.obj(
                "version", VERSION,
                "scopes", new Jsons.Raw("{" + String.join(",", scopePairs) + "}"),
                "pauseKinds", new Jsons.Raw(pauseKindsJson()),
                "holdKinds", new Jsons.Raw(holdKindsJson()),
                "holdScopes", new Jsons.Raw(holdScopesJson()),
                "transitions", new Jsons.Raw(Jsons.obj(
                        "runToExperiment",
                        new Jsons.Raw("{" + String.join(",", transitionPairs) + "}"))));
    }

    private static String statusesJson(List<StatusDef> defs) {
        List<String> items = new ArrayList<>();
        for (StatusDef d : defs) {
            items.add(Jsons.obj(
                    "code", d.code(),
                    "label", d.label(),
                    "color", d.color(),
                    "terminal", d.terminal(),
                    "active", d.active()));
        }
        return Jsons.arr(items);
    }

    private static String pauseKindsJson() {
        List<String> items = new ArrayList<>();
        for (PauseKindDef d : PAUSE_KINDS) {
            items.add(Jsons.obj(
                    "code", d.code(),
                    "label", d.label(),
                    "color", d.color(),
                    "auto", d.auto(),
                    "hint", d.hint()));
        }
        return Jsons.arr(items);
    }

    private static String holdKindsJson() {
        List<String> items = new ArrayList<>();
        for (HoldKindDef d : HOLD_KINDS) {
            items.add(Jsons.obj(
                    "code", d.code(),
                    "label", d.label(),
                    "color", d.color()));
        }
        return Jsons.arr(items);
    }

    private static String holdScopesJson() {
        List<String> items = new ArrayList<>();
        for (HoldScopeDef d : HOLD_SCOPES) {
            items.add(Jsons.obj("code", d.code(), "label", d.label()));
        }
        return Jsons.arr(items);
    }
}
