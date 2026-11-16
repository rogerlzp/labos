package com.labos.boot.web.service;

import com.labos.kernel.persistence.BindingMapper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.MaterialMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 状态字典（客户文档 §1.4 板状态 + §1.5 设备业务状态）的连库契约自测（2026-11-15）。
 *
 * <p><b>测什么</b>：
 * <ol>
 *   <li>板状态：客户 §1.4 的 6 态**齐**，且每条都填了「客户文档叫法」；
 *   <li>设备业务状态：客户 §1.5 的 5 类设备 × 各自动作状态**齐**，
 *       且 device_kind 用的是**平台自己的 device_type 口径**（不自造一套）；</li>
 *   <li><b>删除守卫是活的</b>（变异测试）：被板引用的状态删不掉，
 *       引用一撤就能删 ⇒ 证明它不是"永远返回 0"的绿壳。</li>
 * </ol>
 *
 * <p><b>为什么要有删除守卫</b>：{@code plate_process_state} 是**全平台通用**的
 * 物料状态字典，板已经在某个阶段上（{@code plate.process_state}）。
 * 删掉字典 ⇒ 那些板的状态变成**非法值** ⇒ 静默失配（现场表现为"板状态不认识"）。
 *
 * <p>连库跑；只造**自造数据**（负数 id / 前缀 ZZTEST），跑完清理 ⇒ 不碰现场数据。
 */
public final class StateDictDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;

    /** 自造：一条临时板状态 + 一块临时板（引用它）。 */
    private static final String TMP_CODE = "zz_test_guard_state";
    private static final String TMP_BARCODE = "ZZTEST-PLATE-GUARD";

    // ---------------------------------------------------------------- 客户文档 §1.4 口径
    private static final List<String> PLATE_CODES = List.of(
            "raw", "ready", "in_progress", "waiting_transfer", "completed", "discarded");

    // ---------------------------------------------------------------- 客户文档 §1.5 口径
    //
    // ⚠ 键是**能力**（capability），不是 device_type（2026-11-16 口径改正）：
    //   库里 `device.device_type` 的实际值是厂商登记的原始分类
    //   （"Extractors" / "Dispensers & Washers" / "Robots" / "Storage" …，复数、
    //   含空格，还有 29 个 MISC 与 4 个空值）⇒ 按 device_type 查字典一条都查不到。
    //   能力是平台自己的分类，且每台设备都有（resource_capability）：
    //     EXTRACT 提取仪 · WASH 分液器/洗板机 · MIX 搅拌单元 · TRANSFER 机械臂 · STORE 耗材栈
    //   ⇒ migration 20261116_device_business_runtime.sql 把 device_kind 从
    //   extractor/dispenser/stirrer/robot/plate_hotel 改成了上面这五个能力码。
    private static final Map<String, List<String>> DEVICE_STATES = Map.of(
            "EXTRACT", List.of("idle", "initializing", "waiting_interaction",
                    "turntable_running", "operating", "finished", "fault"),
            "WASH", List.of("ready", "dispensing", "dispensed", "fault"),
            "MIX", List.of("running", "stopped", "fault"),
            "TRANSFER", List.of("home", "moving", "handling", "occupied", "fault"),
            "STORE", List.of("sufficient", "warning", "insufficient"));

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        System.out.println("════ 状态字典（§1.4 板状态 + §1.5 设备业务状态）· 连库自测 ════");
        System.out.println();
        cleanup();
        try {
            testPlateStates();
            testDeviceStates();
            testDeleteGuard();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 板状态

    private static void testPlateStates() {
        System.out.println("── 1. 板状态（客户 §1.4 六态） ──");
        MaterialMapper m = Mappers.get(MaterialMapper.class);
        List<Map<String, Object>> rows = m.selectProcessStates(1, true);
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byCode.put(String.valueOf(r.get("code")), r);
        }
        for (String code : PLATE_CODES) {
            Map<String, Object> r = byCode.get(code);
            if (r == null) {
                bad("板状态缺：" + code);
                continue;
            }
            String doc = str(r.get("docLabel"));
            if (doc == null || doc.isBlank()) {
                bad("板状态「" + code + "」没填客户文档叫法（doc_label）");
            } else {
                ok("板状态 " + code + "（叫法：" + doc + "）");
            }
        }
        // ⚠ **既有条目不许被改名**：plate_process_state 已被运行时消费
        //   （MaterialEffectService 校验 processState 必须在启用字典里），
        //   改名会打断既有物料效果 ⇒ 通用叫法必须保持原样。
        for (Map.Entry<String, String> e : Map.of(
                "raw", "未处理", "ready", "已就绪", "in_progress", "处理中",
                "completed", "已完成", "discarded", "已废弃").entrySet()) {
            Map<String, Object> r = byCode.get(e.getKey());
            if (r == null) {
                continue;
            }
            eq("既有板状态的显示名未被改名：" + e.getKey(), e.getValue(), str(r.get("displayName")));
        }
    }

    // ---------------------------------------------------------------- 设备业务状态

    private static void testDeviceStates() {
        System.out.println();
        System.out.println("── 2. 设备业务状态（客户 §1.5 五类设备） ──");
        BindingMapper m = Mappers.get(BindingMapper.class);
        List<Map<String, Object>> rows = m.selectDeviceBusinessStates(1, null);
        Set<String> have = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            have.add(String.valueOf(r.get("deviceKind")) + "/" + String.valueOf(r.get("stateCode")));
        }
        for (Map.Entry<String, List<String>> e : DEVICE_STATES.entrySet()) {
            for (String code : e.getValue()) {
                if (have.contains(e.getKey() + "/" + code)) {
                    ok("设备业务状态 " + e.getKey() + "/" + code);
                } else {
                    bad("设备业务状态缺：" + e.getKey() + "/" + code);
                }
            }
        }
        // device_kind 必须是**平台 device_type 口径**里的（不自造一套）
        Set<String> known = new LinkedHashSet<>(DEVICE_STATES.keySet());
        for (Map<String, Object> r : rows) {
            String kind = String.valueOf(r.get("deviceKind"));
            if (!known.contains(kind)) {
                bad("device_kind 用了平台口径外的值：" + kind);
            }
        }
    }

    // ---------------------------------------------------------------- 删除守卫（变异测试）

    /**
     * 删除守卫是**活的**：被板引用时删不掉，引用一撤就能删。
     *
     * <p>只断言"删不掉"是不够的 —— 那可能只是 SQL 写错了永远返回 0。
     * ⇒ 后半段**撤掉引用再删一次**，必须成功 ⇒ 证明守卫判的是"引用"而不是"永远拒绝"。
     */
    private static void testDeleteGuard() throws Exception {
        System.out.println();
        System.out.println("── 3. 删除守卫（变异测试：改错必须变红） ──");
        MaterialMapper m = Mappers.get(MaterialMapper.class);

        // 造一条临时状态 + 一块引用它的临时板
        m.insertProcessState(1, TMP_CODE, "ZZTEST 临时态", 997, "ZZTEST");
        insertPlate();

        int blocked = m.deleteProcessState(1, TMP_CODE);
        eq("被板引用时删不掉（返回 0）", 0, blocked);

        // ⚠ **变异**：撤掉引用后再删 ⇒ 必须成功
        //   （若仍返回 0，说明守卫判的不是引用，而是"永远拒绝" ⇒ 守卫是错的）
        deletePlate();
        int allowed = m.deleteProcessState(1, TMP_CODE);
        eq("撤掉引用后能删（返回 1）", 1, allowed);

        // 复原：万一上面某步失败，临时状态可能还在
        m.deleteProcessState(1, TMP_CODE);
    }

    // ---------------------------------------------------------------- 造数/清理

    private static void insertPlate() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols, status, "
                            + "process_state, seal_state, position_status, state_version, identity_status) "
                            + "VALUES (1, ?, '96', 8, 12, 'active', ?, 'none', 'UNKNOWN', 0, 'CONFIRMED')")) {
                ps.setString(1, TMP_BARCODE);
                ps.setString(2, TMP_CODE);
                ps.executeUpdate();
            }
        }
    }

    private static void deletePlate() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM plate WHERE tenant_id = 1 AND barcode = ?")) {
                ps.setString(1, TMP_BARCODE);
                ps.executeUpdate();
            }
        }
    }

    private static void cleanup() throws Exception {
        deletePlate();
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM plate_process_state WHERE tenant_id = 1 AND code = ?")) {
                ps.setString(1, TMP_CODE);
                ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void ok(String what) {
        pass++;
        System.out.println("  ✔ " + what);
    }

    private static void bad(String what) {
        fail++;
        System.out.println("  ✘ " + what);
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            ok(what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
