package com.labos.kernel.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 角色/权限规则 纯逻辑自测（不连库）。
 *
 * <p>锁住四件事：
 * <ol>
 *   <li><b>角色白名单</b>：admin/operator/viewer 合法；boss/root/空/大小写变体一律拒绝——
 *       否则建号接口能把脏角色写进库，账号能登录但权限判断全落空；</li>
 *   <li><b>权限矩阵</b>：写操作 = admin/operator；管账号 = 仅 admin；</li>
 *   <li><b>展示文案</b>：管理员/操作员/只读；未知角色原样返回（便于发现脏数据）；</li>
 *   <li><b>最后管理员保护</b>：唯一启用 admin 被降级/停用必须拒绝；还有别的 admin、
 *       目标不是 admin、目标本来没启用、管理员计数为 0（边界脏数据）都放行。</li>
 * </ol>
 */
public final class RbacRulesSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 角色白名单");
        eq("admin/operator/viewer 合法",
                List.of(true, true, true),
                List.of(RbacRules.validRole("admin"),
                        RbacRules.validRole("operator"),
                        RbacRules.validRole("viewer")));
        eq("未知角色/null/空串/大小写变体一律拒绝",
                List.of(false, false, false, false, false),
                List.of(RbacRules.validRole("boss"),
                        RbacRules.validRole(null),
                        RbacRules.validRole(""),
                        RbacRules.validRole("Admin"),
                        RbacRules.validRole(" root ")));

        section("2. 权限矩阵");
        eq("canWrite：admin/operator 可写，viewer/未知/null 不可写",
                List.of(true, true, false, false, false),
                List.of(RbacRules.canWrite("admin"),
                        RbacRules.canWrite("operator"),
                        RbacRules.canWrite("viewer"),
                        RbacRules.canWrite("boss"),
                        RbacRules.canWrite(null)));
        eq("canManageUsers：仅 admin",
                List.of(true, false, false, false),
                List.of(RbacRules.canManageUsers("admin"),
                        RbacRules.canManageUsers("operator"),
                        RbacRules.canManageUsers("viewer"),
                        RbacRules.canManageUsers("boss")));

        section("3. 角色展示文案");
        eq("已知角色中文文案",
                List.of("管理员", "操作员", "只读"),
                List.of(RbacRules.roleLabel("admin"),
                        RbacRules.roleLabel("operator"),
                        RbacRules.roleLabel("viewer")));
        eq("未知角色原样返回、null 仍为 null",
                Arrays.asList("boss", null),
                Arrays.asList(RbacRules.roleLabel("boss"), RbacRules.roleLabel(null)));

        section("4. 最后管理员保护：唯一启用 admin 不允许降级/停用");
        String demote = RbacRules.lastAdminGuard("admin", true, "operator", null, 1);
        check("唯一 admin 降级被拒，且原因非空并说明后果",
                demote != null && !demote.isBlank() && demote.contains("管理员"), demote);
        String disable = RbacRules.lastAdminGuard("admin", true, null, Boolean.FALSE, 1);
        check("唯一 admin 停用被拒，原因提到停用",
                disable != null && disable.contains("停用"), disable);
        String both = RbacRules.lastAdminGuard("admin", true, "viewer", Boolean.FALSE, 1);
        check("唯一 admin 降级+停用被拒", both != null && !both.isBlank(), both);

        section("5. 保护放行分支（不能误伤正常操作）");
        eq("还有其它 admin（计数=2）时允许降级", null,
                RbacRules.lastAdminGuard("admin", true, "operator", null, 2));
        eq("目标不是 admin 时允许", null,
                RbacRules.lastAdminGuard("operator", true, "viewer", Boolean.FALSE, 1));
        eq("目标 admin 但本来就没启用时允许", null,
                RbacRules.lastAdminGuard("admin", false, "viewer", null, 1));
        eq("enabledAdminCount=0（边界脏数据）放行而不是拦死", null,
                RbacRules.lastAdminGuard("admin", true, "operator", null, 0));
        eq("本次不改角色/启用（只改密码）时允许", null,
                RbacRules.lastAdminGuard("admin", true, null, null, 1));
        eq("保持 admin 身份不变时允许", null,
                RbacRules.lastAdminGuard("admin", true, "admin", Boolean.TRUE, 1));

        section("4. 物料权限码（S2 §8.2 第 17 项）");
        eq("admin 全量", true, RbacRules.canMaterial("admin", RbacRules.MATERIAL_RECONCILE));
        eq("operator 可 reconcile", true,
                RbacRules.canMaterial("operator", RbacRules.MATERIAL_RECONCILE));
        eq("operator 可 cancel", true,
                RbacRules.canMaterial("operator", RbacRules.MATERIAL_CANCEL));
        eq("viewer 无权限", false,
                RbacRules.canMaterial("viewer", RbacRules.MATERIAL_RETRY));
        eq("未知角色无权限", false,
                RbacRules.canMaterial("boss", RbacRules.MATERIAL_WRITE));

        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s（期望 %s，实际 %s）%n", name, expect, got);
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s（实际 %s）%n", name, actual);
        }
    }

    private RbacRulesSelfTest() {
    }
}
