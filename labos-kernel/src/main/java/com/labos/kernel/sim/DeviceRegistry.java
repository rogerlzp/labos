package com.labos.kernel.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 中控的设备清单 —— **模拟端从中控的表里读**（2026-11-11）。
 *
 * <p><b>为什么要读</b>：模拟器启动时本来要手工传
 * {@code --device 171 --code ID1183 --cap INCUBATE --profile incubator} ——
 * 这几个值**和中控的表是同一份事实**，手工传就多了一份可能对不上的副本。
 * 设备改了 IP、换了能力，模拟器还按老参数跑，表现为"模拟器说在线、中控说离线"
 * 或者"命令发出去设备不认"，排查成本极高。
 *
 * <p><b>口径</b>：一台设备 = <b>设备ID</b>（device_instance.id）
 * + <b>设备IP</b>（device_instance.ip）+ 端口。三端（模拟端 / 中控 / tcp client）
 * 认的必须是同一组值。
 *
 * <p><b>中控读不到怎么办</b>：不致命 —— 退回手工参数继续跑（工位上中控可能还没起）。
 * 但会打一行警告，因为"悄悄用着旧参数"正是我们要消灭的那种不一致。
 */
public final class DeviceRegistry {

    /** 清单接口路径（中控侧免鉴权，只含设备端点与型号）。 */
    public static final String PATH = "/api/devices/registry";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeviceRegistry() {
    }

    /** 清单里的一台设备。 */
    public record Entry(long deviceId,
                        String code,
                        String ip,
                        int port,
                        String endpoint,
                        String capability,
                        String simProfile,
                        String modelId,
                        /**
                         * 该型号在库里登记的方法名（{@code device_method}）。
                         *
                         * <p>为什么模型端要它：协议与方法是**配置**，代码里不该再有第二份。
                         * 模拟器把它与画像方法集取并集 —— 新设备只 INSERT 数据即可，
                         * 不必再往 {@code DeviceProfile.TABLE} 加硬编码画像。
                         */
                        java.util.List<String> methods,
                        /**
                         * 中控登记的**方法预估时长**（method → 秒）。
                         *
                         * <p>为什么需要：画像的 step timing 只登记了一部分方法名
                         * （如 carousel_extractor 记的是 BindIncubate/Wash/Elute），
                         * 而中控登记的是**厂商动作名**（PAuto：Bind/Incubate/…）⇒ 对不上 ⇒
                         * 那些工序落回画像默认的 20 秒 ⇒ 「按真实时长跑」其实没真等。
                         */
                        java.util.Map<String, Integer> methodEstimates) {
    }

    /** 拉一次清单；失败抛异常（调用方决定降级还是退出）。 */
    public static List<Entry> fetch(String baseUrl) throws Exception {
        String url = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + PATH
                : baseUrl + PATH;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("中控清单接口返回 " + resp.statusCode());
        }
        return parse(resp.body());
    }

    /** 解析清单 JSON。 */
    public static List<Entry> parse(String body) throws Exception {
        List<Entry> out = new ArrayList<>();
        JsonNode items = MAPPER.readTree(body).path("items");
        if (!items.isArray()) {
            return out;
        }
        for (JsonNode it : items) {
            long id = it.path("deviceId").asLong(0);
            if (id <= 0) {
                continue;   // 没有设备ID的条目没法定位，跳过
            }
            List<String> methods = new ArrayList<>();
            JsonNode arr = it.path("methods");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    String name = m.asText("");
                    if (!name.isBlank()) {
                        methods.add(name);
                    }
                }
            }
            java.util.Map<String, Integer> estimates = new java.util.LinkedHashMap<>();
            JsonNode est = it.path("methodEstimates");
            if (est.isObject()) {
                est.fields().forEachRemaining(f -> {
                    int sec = f.getValue().asInt(0);
                    if (sec > 0) {
                        estimates.put(f.getKey(), sec);
                    }
                });
            }
            out.add(new Entry(
                    id,
                    it.path("code").asText(""),
                    it.path("ip").asText(""),
                    it.path("port").asInt(0),
                    it.path("endpoint").asText(""),
                    it.path("capability").asText(""),
                    it.path("simProfile").asText(""),
                    it.path("modelId").asText(""),
                    List.copyOf(methods),
                    java.util.Map.copyOf(estimates)));
        }
        return out;
    }

    /**
     * 按"我监听的那个端点"认领自己。
     *
     * <p>host 是通配地址（{@code 0.0.0.0} / {@code ::}）时只按端口对 ——
     * 通配意味着"本机所有网卡"，它没有指向某个具体 IP。
     */
    public static Entry match(List<Entry> items, String host, int port) {
        if (port <= 0) {
            return null;
        }
        boolean wildcard = host == null || host.isBlank()
                || "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host);
        for (Entry e : items) {
            if (e.port() != port) {
                continue;
            }
            if (wildcard || sameHost(e.ip(), host)) {
                return e;
            }
        }
        return null;
    }

    private static boolean sameHost(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }
}
