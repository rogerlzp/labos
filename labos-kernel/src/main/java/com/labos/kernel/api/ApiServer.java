package com.labos.kernel.api;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内核服务：把决策 / 执行 / 持久化 / 告警四个模块暴露成 HTTP API。
 *
 * <p>用 JDK 自带 {@code com.sun.net.httpserver}，零框架依赖——
 * 目的不是替代 Spring Boot，而是**证明内核可以独立成为一个服务**，
 * 不被框架、容器、配置绑架。
 *
 * <pre>
 *   java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer [port]
 * </pre>
 *
 * <p>端点：
 * <pre>
 *   GET  /health              健康检查
 *   GET  /api/policies        恢复策略列表
 *   GET  /api/runs            最近执行
 *   GET  /api/runs/{id}       执行详情（节点 + 事件流）
 *   GET  /api/alerts          告警（按指纹聚合，排除 info）
 *   GET  /api/errors          错误明细（含 SKIPPED 留痕）
 *   GET  /api/meta/status-vocabulary  状态词表（前端/后端唯一权威）
 *   POST /api/demo/run        触发一次演示执行
 * </pre>
 */
public final class ApiServer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApiServer.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    static final long TENANT = 1L;

    final DbConfig db;
    final QueryService query;
    /** 鉴权：默认开启；LABOS_AUTH=0 可关闭（仅联调用）。 */
    final AuthService auth;
    /** 多实验并行调度器（岛级互斥）。 */
    final ExperimentScheduler scheduler;
    /** 设备心跳：每 10 秒探活一次，结果写 device_state.online。 */
    final com.labos.kernel.driver.HeartbeatService heartbeat;
    public static final boolean AUTH_ENABLED = !"0".equals(System.getenv("LABOS_AUTH"));
    /** 请求体上限 1 MB。 */
    private static final long MAX_BODY = 1024L * 1024L;
    final WorkflowService workflows;
    final int port;

    // 路由 controller（构造时注入 this；与 route() 分发一一对应）
    private final DeviceApi deviceApi = new DeviceApi(this);
    private final WorkflowApi workflowApi = new WorkflowApi(this);
    private final ExperimentApi experimentApi = new ExperimentApi(this);


    /**
     * A 批：所有依赖由 Spring 容器注入，不再在构造函数里 {@code new}。
     * 这样对 workflows/query 的调用才会经过 AOP 代理，{@code @Transactional} 才生效。
     */
    public ApiServer(int port, DbConfig db, QueryService query, AuthService auth,
                     WorkflowService workflows, ExperimentScheduler scheduler,
                     com.labos.kernel.driver.HeartbeatService heartbeat) {
        this.db = db;
        this.query = query;
        this.auth = auth;
        this.workflows = workflows;
        this.scheduler = scheduler;
        this.heartbeat = heartbeat;
        // 心跳参数以库里为准（页面可改），没有就用环境变量/默认值
        com.labos.kernel.driver.RuntimeSettings.loadFromDb(db, TENANT);
        this.port = port;
    }

    /**
     * HTTP 已由 Spring MVC（Tomcat）接管：端口、线程池、生命周期都归容器。
     * 本类只保留路由分发与启动收尾，不再创建任何 ServerSocket。
     */
    // ---------------------------------------------------------------- 路由

    public void route(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("OPTIONS".equals(method)) {
                cors(ex);
                send(ex, 204, "");
                return;
            }

            // ---------- 鉴权拦截（必须在所有业务路由之前） ----------
            //
            // P0-1 修复（评审 20261102-03）：改动前这段代码在路由链中段，
            // 前面注册的 ~500 行路由（workflows / runs / devices / topology …）
            // 全部绕过鉴权 —— 无 token 也能读全部数据、执行流程。
            //
            // 现在前置到路由分发之前，只放行 isPublic 白名单
            // （health / 登录 / 静态资源）。
            if (AUTH_ENABLED && !isPublic(path)) {
                AuthService.Session s0 = currentSession(ex);
                if (s0 == null) {
                    send(ex, 401, err("未登录或会话已过期"));
                    return;
                }
                // 写操作要求 operator 以上；账号管理要求 admin
                boolean write = !"GET".equals(method);
                if (write && !s0.canWrite()) {
                    send(ex, 403, err("当前账号（" + s0.role() + "）无写权限"));
                    return;
                }
                if (path.startsWith("/api/users") && !s0.isAdmin()) {
                    send(ex, 403, err("仅管理员可管理账号"));
                    return;
                }
            }

            // ---------- 业务路由：拆到同包 controller（按首次出现顺序分发） ----------
            // 各 controller 的 path 前缀互不重叠（/api/runs、/api/experiments、…），
            // 因此跨 controller 的顺序不改变语义；controller 内部保持 if/else 顺序。
            // 任一 controller 命中即 return，未命中落到下面的静态资源/健康检查/404。
            // AuthApi 已拆成注解式 AuthController（com.labos.boot.web.controller），
            // 不再走这条分发链；鉴权拦截器在 Spring MVC 层先命中。
            if (deviceApi.handle(ex, path, method)
                    // SchedulerApi / MetaApi 已拆成注解式 Controller
                    || workflowApi.handle(ex, path, method)
                    || experimentApi.handle(ex, path, method)) {
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                serveUi(ex);
                return;
            }
            if (path.startsWith("/vendor/") || "/designer.html".equals(path)) {
                serveStatic(ex, path);
                return;
            }
            if ("/health".equals(path)) {
                requireGet(method, ex);
                send(ex, 200, query.health());
                return;
            }
            send(ex, 404, err("no route: " + method + " " + path));
        } catch (IllegalArgumentException e) {
            // 参数不合法（含 id 溢出、未知动作）：400 而不是 500
            sendQuietly(ex, 400, err(e.getMessage()));
        } catch (IllegalStateException e) {
            sendQuietly(ex, 405, err(e.getMessage()));
        } catch (BodyTooLarge e) {
            sendQuietly(ex, 413, err(e.getMessage()));
        } catch (Exception e) {
            sendQuietly(ex, 500, err(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** 从极简 JSON body 里取字符串字段（无依赖，够用即可）。 */
    public static String jsonField(String body, String key) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    /**
     * 解码 JSON 字符串里的转义序列（含 Unicode 转义）。
     *
     * <p>不解码的话，按标准 JSON 编码中文的客户端（如 Python json.dumps 默认
     * {@code ensure_ascii=True}）会让中文名落库成字面的转义序列（&#92;uXXXX），
     * 而不是真正的中文。
     */
    static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append("\\u");
                        }
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }

    /** 写一条审计（取当前会话，失败静默）。 */
    void audited(String action, String entityType, Long entityId,
                         String entityKey, String detail, HttpExchange ex) {
        if (!AUTH_ENABLED) {
            return;
        }
        AuthService.Session s0 = currentSession(ex);
        auth.audit(s0, s0 == null ? "anonymous" : s0.username(), action,
                entityType, entityId,
                entityKey == null ? null : Jsons.obj("key", entityKey),
                detail, remoteIp(ex), "ok");
    }

    /**
     * 读请求体，超过 1 MB 直接拒绝。
     *
     * <p>BPMN XML 最大约 200 KB，1 MB 够用；
     * 不设上限的话一个超大 body 就能把内存打满（readAllBytes 会一次性分配）。
     */
    byte[] readBody(HttpExchange ex) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try {
                if (Long.parseLong(len) > MAX_BODY) {
                    throw new BodyTooLarge(MAX_BODY);
                }
            } catch (NumberFormatException ignore) {
                // 长度头异常就交给实际读取兜底
            }
        }
        byte[] buf = ex.getRequestBody().readNBytes((int) MAX_BODY + 1);
        if (buf.length > MAX_BODY) {
            throw new BodyTooLarge(MAX_BODY);
        }
        return buf;
    }

    /** 请求体超限。 */
    private static final class BodyTooLarge extends IOException {
        BodyTooLarge(long max) {
            super("请求体超过 " + (max / 1024) + " KB");
        }
    }

    /** 免鉴权路径：健康检查、静态资源、登录本身。 */
    /**
     * 免鉴权路径白名单。
     *
     * <p><b>登录接口必须在白名单里</b>——否则鉴权前置后，连登录都被拦住，
     * 系统直接不可用（这个坑 P0-1 修复时踩过一次）。
     */
    public static boolean isPublic(String path) {
        return "/health".equals(path)
                || "/api/auth/login".equals(path)
                || "/".equals(path)
                || "/index.html".equals(path)
                || "/designer.html".equals(path)
                || path.startsWith("/vendor/")
                || path.startsWith("/assets/");
    }

    public static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null) {
            return null;
        }
        return h.startsWith("Bearer ") ? h.substring(7).trim() : h.trim();
    }

    AuthService.Session currentSession(HttpExchange ex) {
        return auth.verify(bearer(ex));
    }

    static String remoteIp(HttpExchange ex) {
        java.net.InetSocketAddress a = ex.getRemoteAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    /** 从 query string 取整型参数。 */
    static long paramLong(String query, String key) {
        if (query == null) {
            return 0L;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=(-?\\d+)").matcher(query);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /**
     * 从 query string 取字符串参数，并做 application/x-www-form-urlencoded 解码。
     *
     * <p>改动前是原样返回（注释还写着「不做 URL 解码」）：页面暂停原因带空格时，
     * 前端 URLSearchParams 会编码成 {@code +}，库里就存成 {@code 4.20+页面暂停验证}；
     * 其它带中文/空格/百分号的参数（实验名、vars 表达式）也会同样失真。
     * 解码失败（非法 {@code %} 序列）时退回原值，不让参数解析把请求打成 500。
     */
    public static String paramStr(String query, String key) {
        if (query == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=([^&]+)").matcher(query);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    /** 取一个 long 字段（带不带引号都认）。id 类字段用这个，别用 jsonInt（会溢出）。 */
    public static Long jsonLong(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(body);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    public static Boolean jsonBool(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    public static Integer jsonInt(String body, String key) {
        if (body == null) {
            return null;
        }
        // **带不带引号都要认**：前端有时发 {"maxAttempts":2}，
        // 有时发 {"maxAttempts":"2"}（表单里的 InputNumber 常被当字符串）。
        // 改动前只认裸数字，导致"保存成功但值没变"——返回 200，库里没动。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(body);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** ?mode=unattended 时走无人值守。 */
    public static String modeParam(String query) {
        if (query != null && query.contains("mode=unattended")) {
            return WorkflowService.UNATTENDED;
        }
        return WorkflowService.ATTENDED;
    }

    /** 解析 "k1=v1,k2=v2"。空串返回空表。 */
    static Map<String, String> parseVarsExpr(String expr) {
        Map<String, String> m = new LinkedHashMap<>();
        if (expr == null || expr.isBlank()) {
            return m;
        }
        for (String pair : expr.split(",")) {
            int i = pair.indexOf('=');
            if (i <= 0) {
                continue;
            }
            m.put(java.net.URLDecoder.decode(pair.substring(0, i).trim(), StandardCharsets.UTF_8),
                  java.net.URLDecoder.decode(pair.substring(i + 1).trim(), StandardCharsets.UTF_8));
        }
        return m;
    }

    // ---------------------------------------------------------------- HTTP 工具

    void requireGet(String method, HttpExchange ex) {
        if (!"GET".equals(method)) {
            throw new IllegalStateException("method not allowed: " + method);
        }
    }

    static int intParam(HttpExchange ex, String name, int def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null || q.isBlank()) {
            return def;
        }
        for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(name)) {
                try {
                    return Integer.parseInt(p[1]);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    public static String err(String msg) {
        return Jsons.obj("error", msg);
    }

    /** 托管静态资源（限定在 web/ 目录内，阻止路径穿越）。 */
    void serveStatic(HttpExchange ex, String path) throws IOException {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) {
            send(ex, 400, err("bad path"));
            return;
        }
        java.nio.file.Path file = java.nio.file.Path.of("web", rel);
        if (!java.nio.file.Files.exists(file) || java.nio.file.Files.isDirectory(file)) {
            send(ex, 404, err("not found: " + rel));
            return;
        }
        byte[] body = java.nio.file.Files.readAllBytes(file);
        String ct = rel.endsWith(".js") ? "application/javascript; charset=utf-8"
                : rel.endsWith(".css") ? "text/css; charset=utf-8"
                : rel.endsWith(".html") ? "text/html; charset=utf-8"
                : rel.endsWith(".woff2") ? "font/woff2"
                : rel.endsWith(".woff") ? "font/woff"
                : rel.endsWith(".ttf") ? "font/ttf"
                : rel.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        sendRaw(ex, 200, body, ct);
    }

    /** 原样发送（XML / 二进制），不做 JSON 包装。 */
    void sendRaw(HttpExchange ex, int code, String body, String contentType) throws IOException {
        sendRaw(ex, code, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    void sendRaw(HttpExchange ex, int code, byte[] bytes, String contentType) throws IOException {
        cors(ex);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    /** 托管单页看板：优先 classpath，其次工作目录下的 web/index.html。 */
    void serveUi(HttpExchange ex) throws IOException {
        byte[] body = null;
        try (var in = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
            if (in != null) {
                body = in.readAllBytes();
            }
        }
        if (body == null) {
            java.nio.file.Path p = java.nio.file.Path.of("web/index.html");
            if (java.nio.file.Files.exists(p)) {
                body = java.nio.file.Files.readAllBytes(p);
            }
        }
        if (body == null) {
            send(ex, 404, err("web/index.html not found (run from labos-kernel dir)"));
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }

    /**
     * CORS：不再回 {@code *}。
     *
     * <p>改动前 {@code Access-Control-Allow-Origin: *} 意味着**内网任意网页都能跨域调本服务**，
     * 而 {@code Content-Type: text/plain} 属于 simple request 免预检，
     * 于是一个恶意页面就能 POST {@code /api/workflows/{id}/run} 去驱动真实设备。
     *
     * <p>这里改为回显受信任的 origin（默认本机），非受信任的不给 CORS 头——
     * 浏览器会拦，等于挡掉 CSRF。需要放行更多来源时用环境变量配置：
     * {@code LABOS_CORS_ORIGINS=http://a,http://b}
     */
    void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !trustedOrigin(origin)) {
            return;   // 不受信任：不给任何 CORS 头，浏览器会拦
        }
        if (origin != null) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().add("Vary", "Origin");
        }
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    static boolean trustedOrigin(String origin) {
        String allow = System.getenv("LABOS_CORS_ORIGINS");
        if (allow != null && !allow.isBlank()) {
            for (String one : allow.split(",")) {
                if (one.trim().equalsIgnoreCase(origin)) {
                    return true;
                }
            }
            return false;
        }
        // 默认只信本机（含不同端口）
        return origin.matches("^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?$");
    }

    void send(HttpExchange ex, int code, String body) throws IOException {
        cors(ex);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    void sendQuietly(HttpExchange ex, int code, String body) {
        try {
            send(ex, code, body);
        } catch (IOException ignored) {
            // 连接已断，无需处理
        }
    }

    // ---------------------------------------------------------------- 启动

    /**
     * A 批：启动内核与全部启动收尾逻辑。由 Spring 的 StartupRunner 调用，
     * 旧的 {@code java -cp out ... ApiServer 18080} 入口已删除。
     */
    public void runKernel() throws Exception {
        // HTTP 由 Spring MVC 容器启动，这里只跑启动收尾。

        this.scheduler.start();
        this.heartbeat.start();

        if (AUTH_ENABLED) {
            try {
                this.auth.ensureSeedUser();
            } catch (Exception e) {
                log.info("  [鉴权] 初始化失败: " + e.getMessage());
            }
        }

        // 崩溃收尾：上次进程残留的 running 标成 failed，并标记可恢复
        try {
            var orphans = com.labos.kernel.workflow.StartupRecovery
                    .settle(com.labos.kernel.persistence.DbConfig.fromEnv(), TENANT);
            if (!orphans.isEmpty()) {
                log.info("");
                log.info("  [启动收尾] 发现 " + orphans.size() + " 个被中断的 run：");
                for (var o : orphans) {
                    printf("    #%-7d %-30s 完成 %d/%d  %s%n",
                            o.runId(), o.runNo(), o.completedNodes(), o.totalNodes(),
                            o.recoverable() ? "可续跑" : "");
                }
                log.info("");
            }
        } catch (Exception e) {
            log.info("  [启动收尾] 跳过: " + e.getMessage());
        }

        // BUG-14：experiment_run 层的孤儿 running 兜底 settle（与上面 run 表那层并列）。
        // 库里遗留 status='running' 的 experiment_run（进程崩溃/历史脏数据）会让
        // "是否在运行"的判断、ETA/报表误判。单实例部署下启动瞬间本进程没有任何
        // 执行线程持有 run，库里的 running 一定是孤儿 —— 统一落 failed 并回写
        // 批次层（D8）或实验状态（非批次 run）；口径与未来多实例改法见
        // StartupRecovery.settleOrphanExperimentRuns 的注释。
        try {
            var settled = StartupRecovery.settleOrphanExperimentRuns(
                    com.labos.kernel.persistence.DbConfig.fromEnv(), TENANT);
            log.info("  [启动收尾] 孤儿 running settle：" + settled.size() + " 条"
                    + (settled.isEmpty() ? "（无遗留）" : ""));
            for (var s : settled) {
                printf("    run#%-7d 实验 %-10s %s → failed%n",
                        s.runId(), s.experimentNo(),
                        s.batchRun() ? "第 " + s.batchNo() + " 批" : "整单");
            }
        } catch (Exception e) {
            log.info("  [启动收尾] 孤儿 running settle 跳过: " + e.getMessage());
        }

        // P-07 第一切片：命令账本启动对账。
        // 进程崩溃时可能有「已下发、没拿到回包」的设备指令；把陈旧 DISPATCH_STARTED
        // 标成 UNKNOWN（物理结果未知），后续同 commandId 重发会被账本 fail-closed 拦住，
        // 必须人工确认后处置。只标记，不自动重发。
        try {
            int unknown = com.labos.kernel.driver.CommandLedger.reconcileOnStartup(
                    com.labos.kernel.persistence.DbConfig.fromEnv(), TENANT,
                    com.labos.kernel.driver.CommandLedger.STALE_MINUTES);
            log.info("  [启动收尾] 命令账本对账：标记 UNKNOWN " + unknown + " 条"
                    + (unknown == 0 ? "（无遗留）" : ""));
        } catch (Exception e) {
            log.info("  [启动收尾] 命令账本对账跳过: " + e.getMessage());
        }

        // P-08：物理结果未知的 run 隔离（必须在命令账本对账之后）。
        try {
            var quarantined = StartupRecovery.quarantineUncertainRuns(
                    com.labos.kernel.persistence.DbConfig.fromEnv(), TENANT);
            log.info("  [启动收尾] 物理未知隔离：" + quarantined.size() + " 条"
                    + (quarantined.isEmpty() ? "（无遗留）" : ""));
            for (var q : quarantined) {
                printf("    run#%-7d %s → quarantined（实验 %s）%n",
                        q.runId(), q.runNo(), q.experimentId());
            }
        } catch (Exception e) {
            log.info("  [启动收尾] 物理未知隔离跳过: " + e.getMessage());
        }

        // S3.2-A：重启 rehydrate 待派发队列。
        // 调度器队列是内存态：进程重启后库里仍 queued 的 experiment_run（async 整单 run、
        // 异步分批已建出来的下一批）没人入队就永远不会执行。启动时一次性装回内存队列；
        // 运行中不重复调用（rehydrateQueued 自身幂等，但没必要周期性扫库）。
        // 失败只打日志：rehydrate 是恢复优化，不能因为一次读库失败让 API 起不来。
        try {
            int rehydrated = this.scheduler.rehydrateQueued();
            log.info("  [启动收尾] 待派发 rehydrate：" + rehydrated + " 条");
        } catch (Exception e) {
            log.info("  [启动收尾] 待派发 rehydrate 跳过: " + e.getMessage());
        }

        // FIX-P0-04：workflow 级 continue/retry 在 commit 后若进程退出，会遗留 status='queued'。
        // 启动时把这些 run 再交给 resumeAsync，避免一直卡在 queued。
        try {
            var resumed = com.labos.kernel.workflow.WorkflowResumeRecovery.rehydrateQueued(
                    com.labos.kernel.persistence.DbConfig.fromEnv(), TENANT,
                    this.workflows::resumeAsync);
            log.info("  [启动收尾] queued workflow rehydrate："
                    + resumed.started() + "/" + resumed.scanned()
                    + (resumed.failed() == 0 ? "" : "，失败 " + resumed.failed() + " 条"));
        } catch (Exception e) {
            log.info("  [启动收尾] queued workflow rehydrate 跳过: " + e.getMessage());
        }

        log.info("=".repeat(72));
        log.info("  实验室中控 v0.1 内核服务已启动");
        log.info("=".repeat(72));
        log.info("  控制台: http://localhost:" + port + "/");
        log.info("  API   : http://localhost:" + port + "/api/...");
        log.info("");
        log.info("  POST /api/auth/login      登录");
        log.info("  GET  /health              健康检查");
        log.info("  GET  /api/policies        恢复策略列表");
        log.info("  GET  /api/workflows       真实工艺列表");
        log.info("  GET  /api/workflows/{id}  工艺详情（节点+能力）");
        log.info("  POST /api/workflows/{id}/run  执行工艺");
        log.info("  GET  /api/workflows/{id}/graph  BPMN XML");
        log.info("  GET  /api/workflows/{id}/compile 编译预览（节点→步骤）");
        log.info("  GET  /api/workflows/{id}/preflight 保存前预检（能不能跑）");
        log.info("  GET/PUT /api/workflows/{id}/options  流程选项（循环上限 maxIterations）");
        log.info("  POST /api/runs/{id}/attendance   切换值守模式");
        log.info("  GET  /api/process-status         正在跑的 run 卡在哪");
        log.info("  PUT  /api/policies/{id}          改策略");
        log.info("  POST /api/policies/validate      生效校验");
        log.info("  GET  /api/choreographies         搬运流程列表");
        log.info("  GET  /api/workflows/{id}/history          流程图历史版本");
        log.info("  POST /api/workflows/{id}/history/{h}/restore  回滚到某版本");
        log.info("  POST /api/choreography           搬运序列 -> 正式流程");
        log.info("  POST /api/islands                新增岛屿");
        log.info("  POST /api/devices                新增设备（含 IP / 能力 / 驱动端点）");
        log.info("  PUT/DELETE /api/devices/{id}     改 / 删设备");
        log.info("  GET  /api/drivers                驱动端点列表");
        log.info("  GET  /api/methods/{m}/params     设备方法的参数定义");
        log.info("  GET/POST /api/users              用户管理（仅 admin）");
        log.info("  GET  /api/audit                  审计日志");
        log.info("  GET/PUT /api/experiments/{id}/processes  实验编排（流程顺序）");
        log.info("  POST /api/shadow/probe?resourceId=&method=  影子探针（单命令）");
        log.info("  POST /api/workflows/{id}/shadow-run         整流程影子跑（逐节点验契约）");
        log.info("  GET  /api/durations              时长模型（p50/p90）");
        log.info("  GET  /api/runs/{id}/timeline     甘特时间轴");
        log.info("  GET  /api/workflows/{id}/predict 总时长预测");
        log.info("  PUT  /api/devices/{id}/island    设备挂到岛（islandId=0 移出）");
        log.info("  DELETE /api/islands/{id}         删岛（有设备时拒绝）");
        log.info("  GET/PUT /api/islands/{id}/flow   岛屿编排动线");
        log.info("  GET  /api/islands/{id}/robot-points       机械臂点位");
        log.info("  POST /api/islands/{id}/robot-points/sync?robotResourceId=  按设备生成点位");
        log.info("  PUT  /api/islands/{id}/robot-points/{code}  示教：更新坐标");
        log.info("  POST /api/islands/{id}/robot-points/{code}/capture?robotResourceId=&method=  读坐标");
        log.info("  GET/POST /api/islands/{id}/robot-points.csv  点位导入导出");
        log.info("  PUT  /api/devices/{id}/position  保存设备摆放坐标");
        log.info("  POST /api/devices/{id}/clone?islandId=  复制设备实例");
        log.info("  GET  /api/heartbeat              设备心跳状态");
        log.info("  GET  /api/scheduler/status       调度状态（岛锁/队列明细）");
        log.info("  GET  /api/experiments/eta        实验预计等待（排队估算）");
        log.info("  GET  /api/meta/status-vocabulary  状态词表（前端/后端唯一权威）");
        log.info("  GET  /api/runtime/holds          生效的现场扣留（站点/岛/实验）");
        log.info("  POST /api/experiments/runs/{id}/pause?scope=&kind=&reason=  暂停实验（现场扣留）");
        log.info("  POST /api/experiments/runs/{id}/stop     停止实验（动作边界协作式）");
        log.info("  POST /api/experiments/runs/{id}/resume   恢复实验（解扣留 + 断点续跑）");
        log.info("  GET  /api/positions?islandId=    位置占用（哪些位置有板）");
        log.info("  GET  /api/positions/stats        位置占用统计");
        log.info("  GET  /api/device-models          设备型号池（可重复部署）");
        log.info("  POST /api/device-models/{id}/deploy  按型号部署一台到岛");
        log.info("  GET  /api/devices/pool           全量设备池（含所属岛）");
        log.info("  GET  /api/devices/unassigned     未分配的设备");
        log.info("  GET  /api/topology               岛屿 -> 设备");
        log.info("  GET  /api/experiments            实验列表（650 个）");
        log.info("  GET  /api/devices/{id}/methods   设备支持的方法");
        log.info("  POST /api/alerts/{fp}/ack|close|silence|reopen  告警处置");
        log.info("");
        log.info("  设计器: http://localhost:" + port + "/designer.html?id=239");
        log.info("  GET  /api/runs?limit=20   最近执行");
        log.info("  GET  /api/runs/{id}       执行详情（节点 + 事件流）");
        log.info("  GET  /api/alerts          告警聚合");
        log.info("  GET  /api/errors          错误明细");
        log.info("  POST /api/demo/run        触发演示执行");
        log.info("");
        log.info("=".repeat(72));

    }
}
