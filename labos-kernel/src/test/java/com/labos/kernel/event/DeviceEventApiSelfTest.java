package com.labos.kernel.event;

import com.labos.kernel.api.ApiServer;
import com.labos.boot.web.controller.DeviceEventController;

import java.util.Map;

/**
 * 设备事件入站端点的**纯逻辑**自测（不连库、不起服务）。
 *
 * <p><b>为什么要有</b>：这个端点是 sidecar 往内核送设备事件的唯一入口，
 * 两处容易出错且都很隐蔽：
 * <ol>
 *   <li><b>鉴权口径</b>：设备事件 POST 使用独立令牌，路径本身不能进入公开白名单，
 *       否则同一路径的 GET 查询也会绕过登录会话。匿名写入仅由显式联调开关开启。</li>
 *   <li><b>载荷解析</b>：{@code payload} 可能是对象、也可能是字符串/数字，
 *       解析器只认字符串就会把对象载荷丢掉。</li>
 * </ol>
 * 两处都错了，现象都是"流程卡住等不到事件"，且看不出是端点的问题。</p>
 */
public final class DeviceEventApiSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 设备事件入站端点 · 纯逻辑自测 ════");
        System.out.println();

        section("1. 鉴权口径：POST 用独立设备令牌，GET 查询仍需登录");
        ok("路径本身不在公开白名单（GET 不会绕过会话鉴权）",
                !ApiServer.isPublic("/api/device-events"));
        ok("默认不允许匿名写入",
                !DeviceEventController.anonymousIngestAllowed(null));
        ok("只有显式联调开关允许匿名写入",
                DeviceEventController.anonymousIngestAllowed("1"));
        ok("其它端点没有被顺带放开",
                !ApiServer.isPublic("/api/plates"));

        section("2. 载荷解析：对象 / 字符串 / 数字 / 缺失 / 脏数据");
        String body = """
                {"device":"ID-EXT1","eventType":"waiting_interaction",
                 "payload":{"station":"exchange","plate":"P001"},"runId":42}
                """;
        eq("对象载荷原样取出",
                "{\"station\":\"exchange\",\"plate\":\"P001\"}",
                DeviceEventController.rawJsonField(body, "payload"));
        eq("字符串字段取到",
                "waiting_interaction",
                ApiServer.jsonField(body, "eventType"));
        eq("数字字段取到",
                "42",
                String.valueOf(ApiServer.jsonLong(body, "runId")));
        eq("不存在的字段返回 null",
                null,
                DeviceEventController.rawJsonField(body, "nosuch"));

        eq("字符串载荷去掉引号",
                "hello",
                DeviceEventController.rawJsonField(
                        "{\"payload\":\"hello\"}", "payload"));
        eq("数字载荷原样",
                "7",
                DeviceEventController.rawJsonField(
                        "{\"payload\":7}", "payload"));
        eq("缺失的载荷字段返回 null",
                null,
                DeviceEventController.rawJsonField("{\"a\":1}", "payload"));

        // 脏数据不能把整个端点带崩
        ok("空 body 安全", DeviceEventController.rawJsonField("", "payload") == null);
        ok("null body 安全", DeviceEventController.rawJsonField(null, "payload") == null);
        ok("括号没闭合安全",
                DeviceEventController.rawJsonField("{\"payload\":{\"a\":1", "payload") == null);
        // ⚠ 载荷**自己**是闭合的、外层 JSON 截断 ⇒ 取到载荷本身是对的（不能误报）
        ok("外层截断但载荷闭合 ⇒ 仍取到载荷",
                "{\"a\":1}".equals(
                        DeviceEventController.rawJsonField("{\"payload\":{\"a\":1}", "payload")));
        ok("键名不是正则元字符",
                DeviceEventController.rawJsonField("{\"a.b\":1}", "a.b") != null);

        section("3. 嵌套对象里的括号不能提前截断");
        eq("嵌套对象完整取出",
                "{\"a\":{\"b\":[1,2]},\"c\":\"}\"}",
                DeviceEventController.rawJsonField(
                        "{\"payload\":{\"a\":{\"b\":[1,2]},\"c\":\"}\"}}", "payload"));

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
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
