package com.labos.kernel.material;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §3.3b 声明契约预检 纯逻辑自测（不连库）。
 */
public final class DeclarationValidatorSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        String valid = "{\"mode\":\"transition\",\"target\":{\"plateId\":123},"
                + "\"set\":{\"processState\":\"completed\"},"
                + "\"acceptedEvidenceLevels\":[\"device_ack\"]}";

        section("1. 合法声明");
        eq("合法 transition 无问题", List.of(), DeclarationValidator.validate(valid));
        eq("none 模式无问题", List.of(),
                DeclarationValidator.validate("{\"mode\":\"none\"}"));

        section("2. 各规则负向");
        codes("非法 mode", DeclarationValidator.validate("{\"mode\":\"xxx\"}"),
                Set.of("ME_INVALID_MODE"));
        codes("缺 target", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"set\":{\"processState\":\"completed\"}}"),
                Set.of("ME_TARGET_MISSING"));
        codes("缺 set", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1}}"),
                Set.of("ME_SET_EMPTY"));
        codes("非法 sealState", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},\"set\":{\"sealState\":\"xxx\"}}"),
                Set.of("ME_SEAL_INVALID"));
        codes("AUTO 占位", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"barcode\":\"AUTO-1\"},\"set\":{\"processState\":\"x\"}}"),
                Set.of("ME_AUTO_UNSUPPORTED"));
        codes("scanRef", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1,\"scanRef\":true},\"set\":{\"processState\":\"x\"}}"),
                Set.of("ME_SCANREF_UNSUPPORTED"));
        codes("非法证据等级", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},"
                                + "\"set\":{\"processState\":\"x\"},\"acceptedEvidenceLevels\":[\"bogus\"]}"),
                Set.of("ME_EVIDENCE_LEVEL_INVALID"));
        codes("非法 JSON", DeclarationValidator.validate("not-json"),
                Set.of("ME_INVALID_JSON"));

        section("3. 节点种类（规则 10）");
        eq("task → TASK", true, DeclarationValidator.taskNodeKind("task"));
        eq("script → TASK", true, DeclarationValidator.taskNodeKind("script"));
        eq("wait → 非 TASK", false, DeclarationValidator.taskNodeKind("wait"));
        eq("condition → 非 TASK", false, DeclarationValidator.taskNodeKind("condition"));

        section("4. 评审 R13-11：规则 2/3/6/8 补齐");
        codes("仅 location 不算身份", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"location\":{\"resourceId\":7}},"
                                + "\"set\":{\"processState\":\"completed\"}}"),
                Set.of("ME_TARGET_MISSING"));
        codes("set 只有无关字段", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},\"set\":{\"foo\":1}}"),
                Set.of("ME_SET_EMPTY"));
        codes("多组 outputs", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},"
                                + "\"set\":{\"processState\":\"x\"},\"outputs\":[{},{}]}"),
                Set.of("ME_MULTI_EFFECT"));
        codes("outputs + operations 并存", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},"
                                + "\"set\":{\"processState\":\"x\"},"
                                + "\"outputs\":[{}],\"operations\":[{}]}"),
                Set.of("ME_MULTI_EFFECT"));
        codes("证据等级非数组", DeclarationValidator.validate(
                        "{\"mode\":\"transition\",\"target\":{\"plateId\":1},"
                                + "\"set\":{\"processState\":\"x\"},"
                                + "\"acceptedEvidenceLevels\":\"device_ack\"}"),
                Set.of("ME_EVIDENCE_LEVEL_INVALID"));
        String manual = "{\"mode\":\"transition\",\"target\":{\"plateId\":1},"
                + "\"set\":{\"processState\":\"x\"},"
                + "\"acceptedEvidenceLevels\":[\"manual_confirmed\"]}";
        codes("manual_confirmed 只报 WARN", DeclarationValidator.validate(manual),
                Set.of("ME_EVIDENCE_OVERRIDE"));
        eq("WARN 级别不阻断启动", "WARN",
                DeclarationValidator.validate(manual).get(0).level());

        summary();
    }

    private static void codes(String name, List<DeclarationValidator.Issue> issues,
                              Set<String> expectedCodes) {
        Set<String> got = issues.stream().map(DeclarationValidator.Issue::code)
                .collect(Collectors.toSet());
        if (Objects.equals(expectedCodes, got)) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s（期望 %s，实际 %s）%n", name, expectedCodes, got);
        }
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

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void summary() {
        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail != 0) {
            throw new RuntimeException("DeclarationValidatorSelfTest 有失败用例");
        }
    }

    private DeclarationValidatorSelfTest() {
    }
}
