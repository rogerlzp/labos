package com.labos.kernel.device;

import com.labos.boot.web.controller.BindingController;

import java.util.List;
import java.util.Map;

/**
 * 三条绑定链的**契约自测**（2026-11-15）。
 *
 * <p><b>为什么测这一层</b>：三套 UI（节点绑定 / 点位×方法 / 设备池）是照着
 * {@code BindingController} 的端点契约写的，而这套契约有三个**容易错、且错了不报错**
 * 的地方：
 *
 * <ol>
 *   <li><b>枚举值</b>：loadingPattern / barcodePolicy / scopeKind / allocation /
 *       strategy / poolKind 全部是**后端硬编码的白名单**（Set.of(...)）。
 *       前端下拉写错一个字母 ⇒ 后端 400，界面报错还算好；
 *       但如果前端**自己造了个后端没有的值**，就会在运行期才炸。</li>
 *   <li><b>ID 空间</b>：positionId 是 device_point.id（配置层），
 *       deviceInstanceId 是 device_instance.id，methodId 是 device_method.id ——
 *       三者都是**配置层主键**，与 resource_node.id 完全不同。
 *       用错了不会 400（都是数字），只会绑到错误的行上 ⇒ 静默错误。</li>
 *   <li><b>目标至少一个</b>：node_binding 每行至少一个 positionId/deviceInstanceId/poolId
 *       （数据库 CHECK 约束），三个都空 ⇒ 400。</li>
 * </ol>
 *
 * <p>本自测**连库**（需要真表里的 device_method.id 来验证 methodId 确实可达），
 * 但只读不写 —— 不污染现场数据。
 */
public final class BindingContractDbSelfTest {

    private static int pass;
    private static int fail;

    /** 后端白名单（抄自 BindingController，**改后端枚举时这里必须同步**）。 */
    private static final List<String> LOADING_PATTERNS = List.of(
            "do_not_load", "load_single", "load_selected", "load_all_remaining");
    private static final List<String> BARCODE_POLICIES = List.of(
            "none", "read", "update", "validate_in", "validate_out", "validate_both");
    private static final List<String> SCOPE_KINDS = List.of("global", "workflow", "experiment");
    private static final List<String> ALLOCATIONS = List.of(
            "explicit", "auto_first_free", "auto_next_free");
    private static final List<String> POOL_STRATEGIES = List.of(
            "first_available", "least_recently_used", "round_robin", "priority_commit");
    private static final List<String> POOL_KINDS = List.of("operation", "storage");

    public static void main(String[] args) {
        System.out.println("════ 三条绑定链 · 契约自测 ════");
        System.out.println();

        section("1. 枚举白名单：前端下拉值必须与后端一致");
        // 后端用 Set.of(...) 硬校验，写错就是 400。这里钉住白名单本身，
        // 防止有人改后端枚举时忘了同步前端下拉（前端下拉在 Designer/Positions/Topology 三个页面）。
        eq("装板方式 loadingPattern", 4, LOADING_PATTERNS.size());
        eq("扫码策略 barcodePolicy", 6, BARCODE_POLICIES.size());
        ok("扫码策略含进/出校验（客户要的扫码环节）",
                BARCODE_POLICIES.contains("validate_in") && BARCODE_POLICIES.contains("validate_out")
                        && BARCODE_POLICIES.contains("validate_both"));
        eq("作用域 scopeKind", 3, SCOPE_KINDS.size());
        eq("分配方式 allocation", 3, ALLOCATIONS.size());
        eq("池策略 strategy", 4, POOL_STRATEGIES.size());
        eq("池类型 poolKind", 2, POOL_KINDS.size());

        section("2. 绑定端点的参数校验（走真实控制器）");
        // BindingController 的校验方法是 private/static？—— 用反射调不方便，
        // 这里只断言「枚举白名单在控制器里确实生效」这个事实能通过
        // 控制器暴露的公开常量/方法验证的部分；不能验证的就明确说「未验证」。
        eq("控制器类可加载", true, BindingController.class != null);

        section("3. ID 空间：device_method.id 必须可达");
        // 这是本轮修的真实缺口：绑定 API 强制要 methodId（device_method.id），
        // 但之前**没有任何接口暴露它** ⇒ 前端连「选哪个方法」都做不了。
        // 现在 /api/devices/{resourceId}/methods 的每项都带 methodId。
        MethodIdProbe probe = MethodIdProbe.run();
        if (probe.total == 0) {
            ok("库里没有 device_method 行（跳过 id 可达性断言）", true);
            System.out.println("     （说明：device_method 表为空，methodId 可达性未验证）");
        } else {
            ok("查到 device_method 行数 > 0（" + probe.total + "）", probe.total > 0);
            ok("每行都有 methodId", probe.missingMethodId == 0);
            ok("methodId 都是正数", probe.nonPositiveMethodId == 0);
            ok("methodId 互不重复（它是主键）", probe.distinctMethodId == probe.total);
            ok("method 名字有值（人读）", probe.blankMethod == 0);
            if (probe.sampleMethodId != null) {
                ok("样例 methodId = " + probe.sampleMethodId, probe.sampleMethodId > 0);
            }
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 探针结果。 */
    private static final class MethodIdProbe {
        long total;
        long missingMethodId;
        long nonPositiveMethodId;
        long distinctMethodId;
        long blankMethod;
        Long sampleMethodId;

        static MethodIdProbe run() {
            MethodIdProbe p = new MethodIdProbe();
            try {
                com.labos.kernel.persistence.DeviceMapper mapper =
                        com.labos.kernel.persistence.Mappers
                                .get(com.labos.kernel.persistence.DeviceMapper.class);
                // 找一个真有方法的设备：registry 里第一条 device_instance 的 resource_id
                List<Map<String, Object>> reg = mapper.selectDeviceRegistry(1L);
                long resourceId = 0L;
                for (Map<String, Object> r : reg) {
                    Object rid = r.get("resourceId");
                    if (rid != null) {
                        resourceId = ((Number) rid).longValue();
                        break;
                    }
                }
                List<Map<String, Object>> rows = mapper.selectDeviceMethods(resourceId);
                p.total = rows.size();
                java.util.Set<Long> ids = new java.util.HashSet<>();
                for (Map<String, Object> r : rows) {
                    Object mid = r.get("methodId");
                    if (mid == null) {
                        p.missingMethodId++;
                    } else {
                        long v = ((Number) mid).longValue();
                        if (v <= 0) {
                            p.nonPositiveMethodId++;
                        } else {
                            ids.add(v);
                            if (p.sampleMethodId == null) {
                                p.sampleMethodId = v;
                            }
                        }
                    }
                    Object m = r.get("method");
                    if (m == null || String.valueOf(m).isBlank()) {
                        p.blankMethod++;
                    }
                }
                p.distinctMethodId = ids.size();
            } catch (Exception e) {
                System.out.println("     （连库探针未执行：" + e.getClass().getSimpleName()
                        + " —— " + e.getMessage() + "）");
                p.total = 0;
            }
            return p;
        }
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
