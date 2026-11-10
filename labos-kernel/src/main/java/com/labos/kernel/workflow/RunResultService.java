package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步结果：设备完成动作后上传，流程后续节点查询。
 *
 * <p><b>为什么需要</b>：读板要 10 分钟，结果异步产生。
 * 流程前面送出板、后面按结果分支，中间要能"查"。
 *
 * <p>结果通过 {@code ${results.<key>.<field>}} 在表达式里引用。
 *
 * <p><b>和变量的区别</b>：
 * <ul>
 *   <li>变量（run_variable）—— 流程自己算出来的，简单的键值</li>
 *   <li>结果（run_result）—— 设备和外部的产物，**可能还没出来**、**有有效期**</li>
 * </ul>
 */
public final class RunResultService {

    /** 结果注入到变量时的前缀。 */
    public static final String PREFIX = "results.";

    /** 存在性标记的后缀：{@code results.readPlate.__exists = true} */
    public static final String EXISTS_SUFFIX = ".__exists";

    private final DbConfig db;
    private final long tenantId;

    public RunResultService(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 保存结果。同一个 key 重复出现时**报错**——
     * 静默覆盖会让问题藏起来（见设计文档"重复产生怎么办"）。
     *
     * @param validUntil 有效期，null 表示永不过期
     */
    public void save(long runId, Long nodeId, String key, Map<String, Object> payload,
                     String source, Timestamp validUntil) throws SQLException {
        // SQL 收编进 RunResultMapper（阶段3第24批）；db 字段保留维持构造签名。
        mapper().insertResult(tenantId, runId, nodeId, key, toJson(payload),
                source == null ? "device" : source, validUntil);
    }

    /**
     * 加载本 run 的全部有效结果，展开成扁平变量。
     *
     * <pre>
     *   {"readPlate": {"od": 0.65, "plate": "PL-001"}}
     *   →
     *   results.readPlate.od       = 0.65
     *   results.readPlate.plate    = "PL-001"
     *   results.readPlate.__exists = true
     * </pre>
     *
     * <p><b>过期的结果不注入</b>——用 2 小时前的读板结果判定阳性/阴性，
     * 依据可能已经失效而不自知。
     */
    public Map<String, Object> loadAsVars(long runId) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper().selectResults(tenantId, runId)) {
            String key = (String) row.get("resultKey");
            Timestamp vu = (Timestamp) row.get("validUntil");
            if (vu != null && vu.before(new java.sql.Timestamp(System.currentTimeMillis()))) {
                // 过期：注入一个标记，但字段不注入
                out.put(PREFIX + key + ".__expired", true);
                out.put(PREFIX + key + EXISTS_SUFFIX, false);
                continue;
            }
            flatten(PREFIX + key, parseJson((String) row.get("payload")), out);
            out.put(PREFIX + key + EXISTS_SUFFIX, true);
        }
        return out;
    }

    /** 某个结果存不存在（且没过期）。 */
    public boolean exists(long runId, String key) {
        try {
            return mapper().hasResult(tenantId, runId, key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 工具

    private com.labos.kernel.persistence.RunResultMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.RunResultMapper.class);
    }

    /** 把嵌套 JSON 展开成 "a.b.c" 形式的扁平键。 */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (value instanceof Iterable<?> it) {
            // 数组不展开（表达式不支持数组），整体存成字符串
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(o);
                first = false;
            }
            out.put(prefix, sb.append(']').toString());
        } else {
            out.put(prefix, value);
        }
    }

    /** 极简 JSON 序列化（值都是标量或一层 Map，够用）。 */
    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":").append(valueJson(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String valueJson(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":")
                        .append(valueJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 极简 JSON 解析（只处理对象/标量，够用；复杂结构交给驱动的原文）。 */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new MiniJson(json).parseValue();
        } catch (RuntimeException e) {
            return Map.of("raw", json);
        }
    }

    /** 递归下降的极简 JSON 解析，只支持本表存的形态。 */
    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object parseValue() {
            skip();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;   // {
            skip();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length()) {
                skip();
                String k = parseString();
                skip();
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                }
                Object v = parseValue();
                m.put(k, v);
                skip();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                }
                break;
            }
            return m;
        }

        private Object parseArray() {
            java.util.List<Object> list = new java.util.ArrayList<>();
            i++;   // [
            skip();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (i < s.length()) {
                list.add(parseValue());
                skip();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                }
                break;
            }
            return list;
        }

        private String parseString() {
            if (i >= s.length() || s.charAt(i) != '"') {
                return "";
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            i++;
            return sb.toString();
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(start, i);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return num;
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
