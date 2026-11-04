package com.labos.kernel.workflow;

import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验编排的逐步执行器。
 *
 * <p><b>为什么逐步执行</b>：一次性生成 workflow 时变量还没值（因为还没执行），
 * 永远做不出"上一步的输出喂给下一步"。要做数据流，只能走一步看一步。
 *
 * <p><b>为什么每一步都落 run_node</b>：逐步执行最容易出现"跑到第 3 步挂了，
 * 前两步做了什么没人知道"。每步落库，出问题能查到卡在哪、当时传了什么。
 */
public final class ExperimentFlowRunner {

    /** 一步：在哪台设备上、做什么、带什么参数、产出什么变量。 */
    public record Step(long resourceId, String method, String output, String paramsExpr) {

        /** 兼容调用：没有参数。 */
        public Step(long resourceId, String method, String output) {
            this(resourceId, method, output, null);
        }
    }

    /** 执行结果。 */
    public record Result(long runId, String status, int completed, int total,
                         Map<String, String> vars, String error) {
    }

    private final DbConfig db;
    private final long tenantId;
    private final WorkflowService workflows;

    public ExperimentFlowRunner(DbConfig db, long tenantId, WorkflowService workflows) {
        this.db = db;
        this.tenantId = tenantId;
        this.workflows = workflows;
    }

    /**
     * 逐步执行。
     *
     * @param steps 已展开的步骤（循环已由前端展开）
     * @param vars  初始变量（会被就地更新，最终值在 Result 里）
     */
    public Result run(String name, List<Step> steps, Map<String, String> vars) throws SQLException {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的步骤");
        }
        Map<String, String> vars0 = new LinkedHashMap<>(vars == null ? Map.of() : vars);

        long runId = createRun(name, steps.size(), vars0);
        ShadowProbe probe = new ShadowProbe(workflows.buildDriverRegistry());

        int done = 0;
        String error = null;

        for (int i = 0; i < steps.size(); i++) {
            Step st = steps.get(i);
            long nodeId = createNode(runId, i + 1, st);

            try {
                // 参数：k=v;k2=v2，值里的 ${var} 用当前变量替换
                Map<String, Object> params = new LinkedHashMap<>();
                if (st.paramsExpr() != null) {
                    for (String kv : st.paramsExpr().split(";")) {
                        int eq2 = kv.indexOf('=');
                        if (eq2 <= 0) {
                            continue;
                        }
                        String k = kv.substring(0, eq2).trim();
                        String v = substAll(kv.substring(eq2 + 1).trim(), vars0);
                        if (k.isEmpty()) {
                            continue;
                        }
                        // 纯数字转数字：驱动对类型敏感
                        params.put(k, v.matches("-?\\d+") ? Long.parseLong(v) : v);
                    }
                }
                // ShadowProbe 要的是 device_instance.id，而图里存的是 resource_node.id
                Long instId = instanceIdOf(st.resourceId());
                if (instId == null) {
                    throw new IllegalStateException("设备实例不存在: " + st.resourceId());
                }
                // 可选：把点位坐标也带上（withCoords=1 时）
                enrichWithCoords(params);

                // 搬运前预检：源设备有没有板
                String precheck = precheckTransfer(st.method(), params);
                if (precheck != null) {
                    finishNode(nodeId, false, 0, null, "EC-PLATE-PRECHECK");
                    error = "第 " + (i + 1) + " 步预检失败：" + precheck;
                    break;
                }

                ShadowProbe.Probe p = probe.probe(instId, st.method(), params);

                boolean ok = p.ok();
                finishNode(nodeId, ok, p.elapsedMs(), p.rawResponse(), p.errorCode());
                writeCommand(runId, nodeId, i + 1, st, params, ok, p.elapsedMs(),
                        p.rawResponse(), p.errorCode());

                if (!ok) {
                    error = "第 " + (i + 1) + " 步 " + st.method() + " 失败：" + p.errorCode();
                    break;
                }
                done++;

                // 输出变量：从返回原文取字段
                if (st.output() != null && !st.output().isBlank() && p.rawResponse() != null) {
                    int eq = st.output().indexOf('=');
                    if (eq > 0) {
                        String field = st.output().substring(0, eq).trim();
                        String varName = st.output().substring(eq + 1).trim();
                        String v = extractField(p.rawResponse(), field);
                        if (v != null) {
                            vars0.put(varName, v);
                        }
                    }
                }
            } catch (Exception e) {
                finishNode(nodeId, false, 0, null, e.getClass().getSimpleName());
                error = "第 " + (i + 1) + " 步异常：" + e.getMessage();
                break;
            }
        }

        String status = error == null ? "completed" : "failed";
        finishRun(runId, status, done, steps.size(), error, vars0);
        return new Result(runId, status, done, steps.size(), vars0, error);
    }

    /**
     * 把点位坐标加进参数。
     *
     * <p>默认不加：驱动认点位码就够，多传参数可能被拒（有些驱动严格校验参数名）。
     *
     * <p>开了 {@code withCoords=1} 才加，加的是
     * {@code source_x/source_y/source_z} 和 {@code target_x/target_y/target_z}。
     *
     * <p>为什么用这个命名：驱动侧如果按 "点位名_轴" 解析，这个格式最自然；
     * 如果驱动要别的格式，改这一处即可。
     */
    private void enrichWithCoords(Map<String, Object> params) {
        Object flag = params.remove("withCoords");
        if (flag == null || !("1".equals(String.valueOf(flag))
                || "true".equalsIgnoreCase(String.valueOf(flag)))) {
            return;
        }
        for (String side : new String[]{"source", "target"}) {
            Object code = params.get(side);
            if (code == null) {
                continue;
            }
            String sql = """
                    SELECT x, y, z FROM robot_point
                    WHERE tenant_id = ? AND code = ?
                    LIMIT 1
                    """;
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setString(2, String.valueOf(code));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                    java.math.BigDecimal px = rs.getBigDecimal("x");
                    java.math.BigDecimal py = rs.getBigDecimal("y");
                    java.math.BigDecimal pz = rs.getBigDecimal("z");
                    if (px != null) {
                        params.put(side + "_x", px.doubleValue());
                    }
                    if (py != null) {
                        params.put(side + "_y", py.doubleValue());
                    }
                    if (pz != null) {
                        params.put(side + "_z", pz.doubleValue());
                    }
                }
            } catch (SQLException ignore) {
                // 查不到坐标就不加，别让点位表问题阻断执行
            }
        }
    }

    /**
     * 搬运前预检：源设备上得有板。
     *
     * <p>为什么要在下发前查：机械臂抓空会报错，但错误信息是"抓取失败"，
     * 现场得猜是点位错了、还是板不在。提前拦下能直接说"ID1775 上没有板"。
     *
     * <p>只检查 Transfer 类方法：其他方法（Home/Deposit）不涉及"从某处抓"。
     */
    private String precheckTransfer(String method, Map<String, Object> params) {
        if (method == null || !method.startsWith("Transfer")) {
            return null;
        }
        Object src = params.get("source");
        if (src == null) {
            return null;   // 没传 source 就没法查，交给驱动报错
        }
        String sql = """
                SELECT ds.has_plate
                FROM resource_node r
                LEFT JOIN device_state ds
                       ON ds.resource_id = r.id AND ds.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND r.code = ?
                LIMIT 1
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, String.valueOf(src));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "找不到点位/设备 " + src;
                }
                boolean has = rs.getBoolean(1);
                if (!has) {
                    return src + " 上没有板，不能搬";
                }
            }
        } catch (SQLException e) {
            return null;   // 预检查不了就放行，别拦住正常生产
        }
        return null;
    }

    /** 把 ${name} 换成变量值；找不到保留原样。 */
    private static String substAll(String s, Map<String, String> vars) {
        if (s == null || vars.isEmpty() || !s.contains("${")) {
            return s;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** resource_node.id -> device_instance.id。 */
    private Long instanceIdOf(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id FROM device_instance
                     WHERE tenant_id = ? AND resource_id = ? AND deleted_at IS NULL
                     LIMIT 1
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 从 JSON 原文取字段。
     *
     * <p>不引 JSON 库：只支持顶层字符串/数字字段（`"k":"v"` 或 `"k":123`），
     * 够取 message_id / equipment_id 这类。嵌套字段以后需要再说。
     */
    static String extractField(String json, String field) {
        String body = json.trim();
        // 原文可能被存成 JSON 字符串（外层多一对引号），先剥掉
        if (body.startsWith("\"") && body.endsWith("\"") && body.length() > 1) {
            body = body.substring(1, body.length() - 1).replace("\\\"", "\"");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?)")
                .matcher(body);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1);
        if (v.startsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ---------------------------------------------------------------- 落库

    private long createRun(String name, int total, Map<String, String> vars) throws SQLException {
        String runNo = "EXP-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String sql = """
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, mode, priority,
                                 status, actual_start, input, output, attendance,
                                 created_at, updated_at)
                VALUES (?, ?, 'experiment', NULL, 'normal', 50, 'running', now(),
                        ?::jsonb, '{}'::jsonb, 'attended', now(), now())
                RETURNING id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, runNo);
            ps.setString(3, Jsons.obj("name", name, "steps", total,
                    "vars", new Jsons.Raw(toJson(vars))));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long createNode(long runId, int seq, Step st) throws SQLException {
        String sql = """
                INSERT INTO run_node (tenant_id, run_id, node_key, name, seq, node_type,
                                      resource_id, status, attempt, level, actual_start,
                                      estimate_sec, input)
                VALUES (?, ?, ?, ?, ?, 'task', ?, 'running', 0, 0, now(), 0, '{}'::jsonb)
                RETURNING id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.setString(3, "S" + seq);
            ps.setString(4, st.method());
            ps.setInt(5, seq);
            ps.setLong(6, st.resourceId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void finishNode(long nodeId, boolean ok, long ms, String raw, String errCode)
            throws SQLException {
        // 改动前这里写 duration_ms —— **run_node 根本没有这个列**，
        // 所以每次都抛 SQLException，再退化到"只更新状态"：
        // 表面上流程能跑完，实际上错误码和耗时全丢了（日志里那行注释就是当年踩的坑）。
        // 现在写真正存在的 error_code，耗时放进 input（jsonb 里随便加字段）。
        String sql = """
                UPDATE run_node
                SET status = ?::node_status, actual_end = now(), error_code = ?,
                    input = COALESCE(input, '{}'::jsonb) || ?::jsonb
                WHERE tenant_id = ? AND id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ok ? "completed" : "failed");
            if (errCode == null || errCode.isBlank()) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, errCode.length() > 64 ? errCode.substring(0, 64) : errCode);
            }
            ps.setString(3, Jsons.obj("durationMs", ms, "errorCode", errCode));
            ps.setLong(4, tenantId);
            ps.setLong(5, nodeId);
            ps.executeUpdate();
        }
    }

    private void writeCommand(long runId, long nodeId, int seq, Step st,
                              Map<String, Object> params,
                              boolean ok, long ms, String raw, String errCode)
            throws SQLException {
        String sql = """
                INSERT INTO run_command
                  (tenant_id, run_id, run_node_id, command_seq, method, payload,
                   status, attempt, sent_at, finished_at, result,
                   device_raw_response, error_code, device_id)
                VALUES (?, ?, ?, 1, ?, ?::jsonb, ?::node_status, 0, now(), now(),
                        ?::jsonb, ?::jsonb, ?, ?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.setLong(3, nodeId);
            ps.setString(4, st.method());
            // payload 记实际下发的参数，便于复盘"当时传了什么"
            java.util.List<Object> kv = new ArrayList<>();
            kv.add("resourceId");
            kv.add(st.resourceId());
            kv.add("stepSeq");
            kv.add(seq);
            params.forEach((k, v) -> { kv.add(k); kv.add(v); });
            ps.setString(5, Jsons.obj(kv.toArray()));
            ps.setString(6, ok ? "completed" : "failed");
            ps.setString(7, Jsons.obj("ok", ok, "durationMs", ms));
            if (raw == null || raw.isBlank()) {
                ps.setNull(8, java.sql.Types.OTHER);
            } else {
                ps.setString(8, raw);
            }
            ps.setString(9, errCode);
            ps.setLong(10, st.resourceId());
            ps.executeUpdate();
        }
    }

    private void finishRun(long runId, String status, int done, int total,
                           String error, Map<String, String> vars) throws SQLException {
        String sql = """
                UPDATE run
                SET status = ?::run_status, actual_end = now(),
                    progress = ?, output = ?::jsonb, error_code = ?
                WHERE tenant_id = ? AND id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setBigDecimal(2, java.math.BigDecimal.valueOf(done * 100.0 / total));
            ps.setString(3, Jsons.obj("vars", new Jsons.Raw(toJson(vars)),
                    "completed", done, "total", total, "error", error));
            ps.setString(4, error == null ? null : "EXP_STEP_FAILED");
            ps.setLong(5, tenantId);
            ps.setLong(6, runId);
            ps.executeUpdate();
        }
    }

    private static String toJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"")
              .append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 极简 JSON 构造（避免依赖 Jsons 的细节）。 */
    private static final class Jsons {
        static String obj(Object... kv) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(kv[i]).append("\":").append(val(kv[i + 1]));
            }
            return sb.append('}').toString();
        }

        static String val(Object v) {
            if (v == null) {
                return "null";
            }
            if (v instanceof Raw r) {
                return r.json;
            }
            if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
            return "\"" + esc(String.valueOf(v)) + "\"";
        }

        record Raw(String json) {
        }
    }
}
