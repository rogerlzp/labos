package com.labos.boot.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 统一错误响应：**不再吞真因**（2026-11-17）。
 *
 * <p><b>为什么</b>：旧实现在 5 个控制器里各写一份，逻辑是
 * 「异常消息里含 {@code duplicate key} / {@code uk_} 就回一句笼统 hint」。
 * 实测踩到：
 * <ul>
 *   <li>建模板插入第 2 行物料失败 ⇒ 真实原因是 {@code uk_manifest_item_role}
 *       （同一角色只能一行），却被报成「templateNo 可能重复」—— 方向完全错；
 *   <li>{@code msg.contains("uk_")} 这条还会把**别的**约束名误判成唯一冲突。</li>
 * </ul>
 * <p>现在：① 从 PostgreSQL 错误消息里解析出**约束名**，命中已知约束就给**准确文案**；
 * ② 响应体固定带 {@code detail}（原始异常消息）与 {@code constraint}（约束名）——
 * 现场/运维不用再靠猜。
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    /** 约束名 → 人话提示。只列「容易误解」的；没列的走「唯一约束 X 冲突」。 */
    private static final java.util.Map<String, String> CONSTRAINT_HINTS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("uk_device_instance",
                            "设备编号已被占用（若这台设备是软删的，说明编号仍被占着）"),
                    java.util.Map.entry("uk_manifest_item_role",
                            "同一模板里这个物料角色只能有一行（要放多块同角色的板，请拆成多行并各自指定板号）"),
                    java.util.Map.entry("uk_workflow", "流程编号已存在"),
                    java.util.Map.entry("uk_device_pool", "设备池编号已存在"),
                    java.util.Map.entry("uk_material_role_code", "物料角色编号已存在"),
                    java.util.Map.entry("uk_driver", "驱动编号已存在"),
                    java.util.Map.entry("uk_device", "设备型号编号已存在"),
                    java.util.Map.entry("uk_device_point", "点位编号已存在"),
                    java.util.Map.entry("uk_experiment", "实验编号已存在"));

    /**
     * 统一的写失败处理。
     *
     * @param hint 调用方给的兜底文案（识别不出具体约束时用它）
     */
    public static ResponseEntity<String> writeError(Exception e, String hint) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String constraint = extractConstraint(msg);
        if (isUniqueViolation(msg)) {
            String text = hint != null ? hint : "唯一约束冲突";
            String known = constraint == null ? null : CONSTRAINT_HINTS.get(constraint);
            if (known != null) {
                text = known;   // ← 命中具体约束 ⇒ 用准确文案（不再拿笼统 hint 顶）
            } else if (constraint != null) {
                text = text + "（约束 " + constraint + "）";
            }
            return conflict(text, msg, constraint);
        }
        if (msg.contains("ck_") || msg.contains("check constraint")) {
            return conflict(hint != null ? hint : "数据约束冲突", msg, constraint);
        }
        if (msg.contains("exclude") || msg.contains("overlap") || msg.contains("ex_booking")) {
            return conflict(hint != null ? hint : "时间区间重叠", msg, constraint);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("写失败：" + msg, msg, constraint));
    }

    /** 简化版：任何异常都 500，但同样带 detail（行为与旧实现一致）。 */
    public static ResponseEntity<String> writeError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("操作失败：" + msg, msg, null));
    }

    /**
     * 是不是唯一约束冲突？
     *
     * <p>⚠ 旧实现还有一条 {@code msg.contains("uk_")} —— 它会把**别的**约束名也当成唯一冲突
     * （实测：{@code uk_manifest_item_role} 的冲突被报成「templateNo 可能重复」）。
     * ⇒ 现在只在**能解析出 uk_ 约束名**时才按唯一冲突处理。
     */
    private static boolean isUniqueViolation(String msg) {
        if (msg.contains("duplicate key") || msg.contains("violates unique")) {
            return true;
        }
        String c = extractConstraint(msg);
        return c != null && c.startsWith("uk_");
    }

    /** 从 PostgreSQL 错误消息里取约束名（{@code ... violates unique constraint "uk_xxx"}）。 */
    private static String extractConstraint(String msg) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("constraint \\\"([A-Za-z0-9_]+)\\\"").matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private static ResponseEntity<String> conflict(String text, String detail, String constraint) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(text, detail, constraint));
    }

    /** 错误响应体：error（人话）+ detail（原始异常）+ constraint（约束名）。 */
    private static String body(String error, String detail, String constraint) {
        if (constraint == null) {
            return com.labos.kernel.persistence.Jsons.obj(
                    "error", error, "detail", detail);
        }
        return com.labos.kernel.persistence.Jsons.obj(
                "error", error, "detail", detail, "constraint", constraint);
    }
}