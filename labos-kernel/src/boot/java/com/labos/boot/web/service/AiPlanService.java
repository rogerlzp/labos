package com.labos.boot.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.persistence.Jsons;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * <b>AI 流程规划</b>：把"人话需求"变成对当前流程图的修改方案。
 *
 * <p><b>两种输出模式</b>（模型自己选，提示词里讲清楚了）：
 * <ul>
 *   <li>{@code ops} —— <b>局部改动</b>：改参数 / 换设备 / 换方法 / 改名。
 *       直接给操作列表，前端逐条应用。</li>
 *   <li>{@code plan} —— <b>结构改动</b>：加步骤、删步骤、调整顺序、把一段拆成并行分支。
 *       <b>不给 connect/disconnect</b>，而是先给出改完之后应该长什么样的**顺序图**，
 *       由前端**确定性地**展开成操作并重连 —— 这样连线不会断。</li>
 * </ul>
 *
 * <p><b>为什么结构改动不让模型直接给 connect/disconnect</b>（2026-11-13 用户实测）：
 * 模型能"连线"，但保证不了图还连着 —— 实测它把主链拆成两截、
 * 留下一堆走不到的孤儿节点（PF-03 报 8 处）。⇒ 结构改动一律走顺序图：
 * <b>模型只负责"想清楚长什么样"，连线由代码做</b>。
 *
 * <p><b>职责边界</b>：本服务只做"转发 + 解析"——
 * ① 拼提示词（模板在这里，不在页面上 ⇒ 页面不能随便改提示词）；
 * ② 调 LLM；③ 把返回的 JSON 抠出来。
 * <b>顺序图编译、ops 校验、应用都在前端</b>（webapp/src/bpmn/flowPlan.ts / flowOps.ts）——
 * 那边的规则（图标、岛校验、能力解析）与画布是同一套，不该在 Java 里重写一遍。
 *
 * <p><b>配置（环境变量）</b>：
 * <ul>
 *   <li>{@code LABOS_AI_APIKEY} 或密钥文件 {@code LABOS_AI_KEY_FILE}
 *       （默认 {@code ~/.minimax/ai-key}）—— 没配就<b>禁用</b>本功能
 *       （端点返回 503，页面提示"AI 未配置"），不影响其他功能；</li>
 *   <li>{@code LABOS_AI_BASE_URL} —— 默认 {@code https://api.deepseek.com/v1}；</li>
 *   <li>{@code LABOS_AI_MODEL} —— 默认 {@code deepseek-flash}；</li>
 *   <li>{@code LABOS_AI_TIMEOUT_SEC} —— 默认 90。</li>
 * </ul>
 *
 * <p><b>为什么不让浏览器直接调 LLM</b>：① key 会暴露在前端（任何登录用户都能拿走）；
 * ② 浏览器直连第三方 API 有 CORS 风险；③ 没有服务端配额/审计。
 */
@Service
public class AiPlanService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 提示词模板：角色 + 两种输出格式 + 硬规则。**服务端持有**，页面不能改。 */
    private static final String SYSTEM_PROMPT = """
            你是实验室自动化流程设计器的"流程规划器"。
            用户给你三样东西：① 当前流程图（JSON，节点带 id）② 设备目录（真实存在的设备与方法）
            ③ 一条修改需求。你只输出 JSON。

            **先想清楚改完之后这张图应该长什么样，再决定输出哪种格式。**

            ── 情况一：局部改动（只改某个已有节点的属性：参数 / 方法 / 设备 / 名字）──
            输出：
            {"mode":"ops","ops":[
              {"op":"update_params","id":"<节点id>","params":{"参数名":"值"}},
              {"op":"set_device","id":"<节点id>","code":"<设备编码>"},
              {"op":"set_method","id":"<节点id>","method":"<方法名>"},
              {"op":"rename","id":"<节点id>","name":"<新名字>"}
            ],"notes":"<给用户看的一句话说明>"}

            ── 情况二：结构改动（加步骤 / 删步骤 / 调整顺序 / 把一段拆成并行分支 / 把并行合并成串行）──
            **不要输出 connect / disconnect**（那会把图的连线搞断）。
            先给出改完之后应该长什么样的**顺序图**，系统会照着它重新连线：
            {"mode":"plan","plan":{"steps":[
              {"type":"start","ref":"<已有开始节点的id>"},
              {"type":"method","ref":"<已有节点id>","label":"取板","device":"<设备编码>","method":"<方法名>","params":{}},
              {"type":"parallel","branches":[
                 [{"type":"method","label":"离心","device":"<设备编码>","method":"<方法名>","params":{}}],
                 [{"type":"method","label":"封膜","device":"<设备编码>","method":"<方法名>","params":{}}]
              ]},
              {"type":"end","ref":"<已有结束节点的id>"}
            ]},"notes":"<给用户看的一句话说明>"}

            顺序图的规则：
            - steps 是**改完之后从上到下的顺序**（start … end）。
            - 每个 step 的 type：start / end / method / logic / parallel。
            - method 的 device 与 method 必须是设备目录里真实存在的；
              params 的参数名必须是该方法参数定义里真实存在的，不要臆造。
            - **复用已有节点时带上 "ref":"<它的id>"**（系统会保留它的身份与既有参数）；
              新节点不要写 ref。
            - parallel 的 branches 是若干条分支，每条分支是一个 step 数组（分支内部是串行的）。
              **"并行网关开始"和"并行网关结束"两个节点由系统自动生成并接好** ——
              你不需要（也不该）自己写这两个网关。
            - 顺序图里**没出现**的已有节点会被删掉 ⇒ 要把它改完之后的**全部**步骤都列出来。
            - 逻辑节点 type=logic，method 只能是 __setVar / __expr / __sleep / __wait / __waitAny。
              __waitAny 的参数用 vars（逗号分隔的变量名，可写成 a=1,b=2 逐个给目标值）+ value（公共目标值）。

            共同的硬规则：
            1. id 必须是「当前流程图」里**真实存在**的节点 id，不许编。
            2. 不要输出与需求无关的修改。需求没要求改的地方一律不动。
            3. 一次需求对应一组最小改动 —— 不要顺手"优化"整个流程。
            4. **需求里有做不到的部分（设备不支持该方法、方法名不存在等），
               仍然把能做的做掉，并在 notes 里写清楚哪部分做不到、为什么。**
               静默丢弃用户的要求是最糟的 —— 用户会以为系统没听懂。
            """;

    private final String baseUrl;
    private final String model;
    private final String keyFile;
    private final int timeoutSec;

    public AiPlanService() {
        this.baseUrl = env("LABOS_AI_BASE_URL", "https://api.deepseek.com/v1");
        this.model = env("LABOS_AI_MODEL", "deepseek-flash");
        this.keyFile = env("LABOS_AI_KEY_FILE",
                System.getProperty("user.home") + "/.minimax/ai-key");
        this.timeoutSec = Integer.parseInt(env("LABOS_AI_TIMEOUT_SEC", "90"));
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    /**
     * 取 API key —— <b>每次请求时取</b>，不是启动时取一次。
     *
     * <p>顺序：环境变量 {@code LABOS_AI_APIKEY} → 密钥文件 {@code LABOS_AI_KEY_FILE}
     * （默认 {@code ~/.minimax/ai-key}）。
     *
     * <p><b>为什么优先用文件</b>：① 进程环境变量在 {@code ps eww} 里对同机用户可见，
     * key 落到文件（600 权限）更安全；② 内核被别的会话重启时，环境变量会丢、
     * 文件不会 ⇒ AI 功能不会"重启一次就失效"。
     */
    private String apiKey() {
        String env = System.getenv("LABOS_AI_APIKEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            java.nio.file.Path p = java.nio.file.Path.of(keyFile);
            if (java.nio.file.Files.isReadable(p)) {
                String s = java.nio.file.Files.readString(p).trim();
                return s.isEmpty() ? "" : s;
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    /** AI 功能是否可用（能取到 key 才算）。 */
    public boolean enabled() {
        return !apiKey().isEmpty();
    }

    /**
     * 生成修改方案。
     *
     * @param instruction 用户的修改需求（自然语言）
     * @param currentSpec 当前流程图（JSON 文本）
     * @param catalog     设备目录（文本：设备 + 方法 + 参数定义）
     * @return 方案 JSON（{@code {"mode":"ops"|"plan", ...}}）
     * @throws IllegalStateException 未配置 key / LLM 调用失败 / 返回不合法
     */
    public String plan(String instruction, String currentSpec, String catalog)
            throws IllegalStateException {
        String key = apiKey();
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    "AI 功能未配置（缺少 LABOS_AI_APIKEY 或密钥文件 " + keyFile + "）");
        }
        String ins = instruction == null ? "" : instruction.trim();
        if (ins.isEmpty()) {
            throw new IllegalStateException("需求不能为空");
        }
        // 上限保护：别让一次请求把几十万 token 塞进模型
        if (ins.length() > 2000) {
            ins = ins.substring(0, 2000);
        }
        String spec = clip(currentSpec, 16000);
        String cat = clip(catalog, 16000);

        String user = "当前流程图：\n" + spec
                + "\n\n设备目录：\n" + cat
                + "\n\n用户的修改需求：\n" + ins
                + "\n\n请只输出 JSON。";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 推理模型会吃掉 max_tokens（返回 reasoning_content，推理 token 也算进
        // completion_tokens）⇒ 正文被截断成半个 JSON（finish_reason="length"）。
        // 实测：结构改动要吐很长的顺序图，4096 不够 ⇒ 截断 ⇒ 报"JSON 不合法"。
        // ⇒ 检测到截断就把预算 ×3 重试（最多 3 次，上限 32768）。
        int budget = 4096;
        String lastText = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            String body = Jsons.obj(
                    "model", model,
                    "messages", new Jsons.Raw(Jsons.arr(java.util.List.of(
                            Jsons.obj("role", "system", "content", SYSTEM_PROMPT),
                            Jsons.obj("role", "user", "content", user)))),
                    "max_tokens", budget,
                    "temperature", 0.2,
                    "stream", false);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("调用 LLM 失败：" + e.getMessage());
            }
            if (resp.statusCode() != 200) {
                throw new IllegalStateException(
                        "LLM 返回 HTTP " + resp.statusCode() + "：" + clip(resp.body(), 300));
            }
            String text = extractContent(resp.body());
            lastText = text;
            if (!isTruncated(resp.body())) {
                return normalize(text);
            }
            budget = Math.min(budget * 3, 32768);
        }
        throw new IllegalStateException(
                "LLM 连续 3 次都把输出截断（max_tokens 不够）。"
                        + "可以缩短需求描述，或调大 LABOS_AI_TIMEOUT_SEC / 模型预算。"
                        + "最后一段输出：" + clip(lastText, 200));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 响应是不是被 max_tokens 截断了。 */
    private static boolean isTruncated(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            return "length".equals(root.path("choices").path(0).path("finish_reason").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 chat/completions 响应里取 assistant 的正文。 */
    private static String extractContent(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode msg = root.path("choices").path(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank()) {
                content = msg.path("reasoning_content").asText("");
            }
            if (content.isBlank()) {
                throw new IllegalStateException(
                        "LLM 返回里没有内容：" + clip(body, 300));
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 LLM 响应失败：" + clip(body, 300));
        }
    }

    /** 抠出 JSON（去掉 Markdown 围栏），再归一成 {@code {"mode":…}} 的形状。 */
    private static String normalize(String text) {
        String t = text.trim();
        int fence = t.indexOf("```");
        if (fence >= 0) {
            int end = t.indexOf("```", fence + 3);
            t = end > fence ? t.substring(fence + 3, end) : t.substring(fence + 3);
            t = t.replaceFirst("^json\\s*", "").trim();
        }
        int start = t.indexOf("{");
        if (start < 0) {
            throw new IllegalStateException("LLM 没返回 JSON：" + clip(text, 300));
        }
        String json = t.substring(start);
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 返回的 JSON 不合法：" + clip(json, 300));
        }
        String mode = root.path("mode").asText("");
        String notes = root.path("notes").asText("");
        StringBuilder sb = new StringBuilder("{");
        if ("plan".equals(mode)) {
            JsonNode plan = root.path("plan");
            if (!plan.isObject() || !plan.path("steps").isArray()) {
                throw new IllegalStateException("LLM 的 plan 里没有 steps 数组：" + clip(json, 300));
            }
            sb.append("\"mode\":\"plan\",\"plan\":").append(plan.toString());
        } else {
            JsonNode ops = root.path("ops");
            if (!ops.isArray()) {
                throw new IllegalStateException("LLM 返回里没有 ops 数组：" + clip(json, 300));
            }
            sb.append("\"mode\":\"ops\",\"ops\":").append(ops.toString());
        }
        if (!notes.isBlank()) {
            sb.append(",\"notes\":").append(Jsons.quote(notes));
        }
        sb.append("}");
        return sb.toString();
    }
}
