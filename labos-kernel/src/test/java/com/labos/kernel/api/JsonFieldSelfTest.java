package com.labos.kernel.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 极简 JSON 取值 纯逻辑自测（不连库）。
 *
 * <p>锁住 {@link ApiServer#jsonField} 的两件事：
 * <ol>
 *   <li><b>解转义</b>：按标准 JSON 编码中文的客户端（Python {@code json.dumps} 默认
 *       {@code ensure_ascii=True}）发来的 Unicode 转义（&#92;uXXXX）必须解成中文。不解的话
 *       中文名会以字面转义序列落库；</li>
 *   <li><b>不崩</b>：字段缺失、body 为空、&#92;u 后面长度不足或不是十六进制、
 *       未知转义，一律返回 {@code null} 或原样保留，绝不能让请求 500。</li>
 * </ol>
 */
public final class JsonFieldSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 基本取值");
        eq("普通字符串", "verify-流程",
                ApiServer.jsonField("{\"name\":\"verify-流程\"}", "name"));
        eq("值里带冒号/逗号不误截", "a:b,c",
                ApiServer.jsonField("{\"name\":\"a:b,c\"}", "name"));
        eq("字段缺失返回 null", null, ApiServer.jsonField("{\"x\":1}", "name"));
        eq("body 为 null / 空白返回 null", Arrays.asList(null, null),
                Arrays.asList(ApiServer.jsonField(null, "name"),
                        ApiServer.jsonField("   ", "name")));

        section("2. \\uXXXX 解码（本轮修的 bug）");
        eq("中文名不再落库成字面 \\uXXXX", "并发",
                ApiServer.jsonField("{\"name\":\"\\u5e76\\u53d1\"}", "name"));
        eq("与直接写中文等价", ApiServer.jsonField("{\"name\":\"并发\"}", "name"),
                ApiServer.jsonField("{\"name\":\"\\u5e76\\u53d1\"}", "name"));
        eq("代理对（emoji）两个 \\u 拼成一个码点", "😀",
                ApiServer.jsonField("{\"name\":\"\\ud83d\\ude00\"}", "name"));
        eq("转义恰好落在串尾也能解", "中",
                ApiServer.jsonField("{\"name\":\"\\u4e2d\"}", "name"));

        section("3. 常规转义");
        eq("\\\" \\\\ \\/ \\b \\f \\n \\r \\t 八种",
                "a\"b\\c/d\be\ff\ng\rh\ti",
                ApiServer.jsonField(
                        "{\"name\":\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"}", "name"));

        section("4. 脏数据不崩");
        eq("\\u 后长度不足 / 非十六进制 → 原样保留", List.of("\\u", "\\uZZZZ"),
                List.of(ApiServer.jsonField("{\"name\":\"\\u\"}", "name"),
                        ApiServer.jsonField("{\"name\":\"\\uZZZZ\"}", "name")));
        eq("未知转义原样保留", "\\x",
                ApiServer.jsonField("{\"name\":\"\\x\"}", "name"));

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

    private JsonFieldSelfTest() {
    }
}
