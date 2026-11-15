package com.labos.boot.web.controller;

import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceEventMapper;
import com.labos.kernel.persistence.DeviceEventRow;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备事件的入站与查询端点。
 *
 * <p><b>为什么需要这个端点</b>：客户的提取仪是「分段自驱」——设备自己跑到交换点，
 * <b>主动上报【等待交互】</b>，上位机被动响应。
 * 这条事件必须有个地方落地，流程里的等待节点（{@code __awaitEvent}）才有东西可等。
 *
 * <p><b>为什么端点是"薄"的</b>：设备协议千差万别（帧格式、事件码各不相同），
 * 中控不该在 HTTP 层猜设备的协议 ⇒ 端点只认**统一形状**：
 * <pre>
 *   POST /api/device-events
 *   {"device":"ID-EXT1","eventType":"waiting_interaction",
 *    "payload":{"station":"exchange"},"runId":123}
 * </pre>
 * 谁把设备的原始帧翻译成这个形状，是**适配层**的活（侧车 / 网关 / 模拟器）。
 *
 * <p>⇒ 侧车那一侧要做的是"转发 + 翻译"，不是"改内核"。
 */
@RestController
@RequestMapping("/api/device-events")
public class DeviceEventController {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final AuditRecorder audit;

    public DeviceEventController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    private DeviceEventMapper mapper() {
        return Mappers.get(DeviceEventMapper.class);
    }

    /**
     * 入站：写一条设备事件。
     *
     * <p>设备码解析不出实例号也**照样落库**（设备可能还没建档）——
     * 事件本身比"实例号"更重要，丢了就再也等不到了。
     */
    @PostMapping(produces = "application/json; charset=utf-8")
    public ResponseEntity<String> ingest(@RequestBody(required = false) String body,
                                          HttpServletRequest req) {
        // 令牌：中控设了 LABOS_DEVICE_EVENT_TOKEN 就必须带对（sidecar 没有中控账号，
        // 免鉴权只在内网可信口径下成立；装了令牌就收口到"知道令牌的人才能写事件"）。
        String expected = eventToken();
        if (expected != null) {
            String got = req.getHeader("X-Labos-Device-Token");
            if (got == null || !expected.equals(got.trim())) {
                return ResponseEntity.status(401).body(Jsons.obj("ok", false,
                        "error", "设备事件令牌不对（X-Labos-Device-Token）"));
            }
        }
        String device = trimmed(com.labos.kernel.api.ApiServer.jsonField(body, "device"));
        String eventType = trimmed(com.labos.kernel.api.ApiServer.jsonField(body, "eventType"));
        if (eventType == null) {
            return ResponseEntity.status(400).body(Jsons.obj("ok", false,
                    "error", "缺 eventType（等哪个事件？）"));
        }
        String payload = rawJsonField(body, "payload");
        Long runId = com.labos.kernel.api.ApiServer.jsonLong(body, "runId");
        Long deviceId = null;
        if (device != null) {
            try {
                deviceId = mapper().selectInstanceIdByCode(TENANT, device);
            } catch (RuntimeException ex) {
                // 设备还没建档 ⇒ device_id 留空，事件照样落库
                deviceId = null;
            }
        }
        try {
            int n = mapper().insertEvent(TENANT, deviceId, device, eventType, payload, runId);
            audit.record(session(req), "device.event_ingest", "device_event", null,
                    device, Jsons.obj("eventType", eventType, "runId", runId),
                    req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("ok", n > 0, "device", device,
                    "eventType", eventType, "runId", runId));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(500).body(Jsons.obj("ok", false,
                    "error", "写入设备事件失败：" + ex.getMessage()));
        }
    }

    /**
     * 查询事件（观测用：谁报了、什么时候报的、被谁消费了没有）。
     */
    @GetMapping(produces = "application/json; charset=utf-8")
    public ResponseEntity<String> list(@RequestParam(required = false) String device,
                                        @RequestParam(required = false) String eventType,
                                        @RequestParam(required = false) Long afterId,
                                        @RequestParam(required = false) Integer limit) {
        long after = afterId == null ? 0L : afterId;
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        try {
            List<DeviceEventRow> rows = mapper().selectEventsSince(
                    TENANT, trimmed(device), trimmed(eventType), after, lim);
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            for (int i = 0; i < rows.size(); i++) {
                DeviceEventRow r = rows.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":").append(r.id())
                        .append(",\"device\":").append(Jsons.quote(r.deviceCode()))
                        .append(",\"eventType\":").append(Jsons.quote(r.eventType()))
                        .append(",\"payload\":").append(r.payload() == null ? "{}" : r.payload())
                        .append(",\"runId\":").append(r.runId() == null ? "null" : r.runId())
                        .append('}');
            }
            sb.append("]}");
            return ResponseEntity.ok(sb.toString());
        } catch (RuntimeException ex) {
            return ResponseEntity.status(500).body(Jsons.obj("ok", false,
                    "error", "查询设备事件失败：" + ex.getMessage()));
        }
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 设备事件令牌（{@code LABOS_DEVICE_EVENT_TOKEN}）。
     *
     * <p>没配 ⇒ 内网免鉴权口径（与 {@code /api/devices/registry} 同档：
     * sidecar 没有中控账号，它要把设备上报转上来）。
     * 配了 ⇒ 端点校验 {@code X-Labos-Device-Token}，对不上直接 401。
     */
    private static String eventToken() {
        String t = System.getenv("LABOS_DEVICE_EVENT_TOKEN");
        return t == null || t.isBlank() ? null : t.trim();
    }

    /**
     * 取一个字段的**原始 JSON**（对象/数组/字符串/数字都认）。
     *
     * <p>为什么不用 {@code jsonField}：它只认字符串值，
     * 而事件的 {@code payload} 是个对象（{@code {"station":"exchange"}}）。
     */
    public static String rawJsonField(String body, String key) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*").matcher(body);
        if (!m.find()) {
            return null;
        }
        int i = m.end();
        if (i >= body.length()) {
            return null;
        }
        char c = body.charAt(i);
        if (c == '{' || c == '[') {
            int depth = 0;
            boolean inStr = false;
            for (int j = i; j < body.length(); j++) {
                char ch = body.charAt(j);
                if (inStr) {
                    if (ch == '\\') {
                        j++;
                    } else if (ch == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (ch == '"') {
                    inStr = true;
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return body.substring(i, j + 1);
                    }
                }
            }
            return null;   // 括号没闭合 ⇒ 当没有
        }
        // 标量：读到逗号或右括号为止
        int j = i;
        while (j < body.length() && body.charAt(j) != ',' && body.charAt(j) != '}') {
            j++;
        }
        String raw = body.substring(i, j).trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.isEmpty() ? null : raw;
    }

    private static com.labos.kernel.api.AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(com.labos.boot.web.ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof com.labos.kernel.api.AuthService.Session s ? s : null;
    }
}
