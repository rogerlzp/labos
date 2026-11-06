package com.labos.kernel.sim;

import com.labos.kernel.device.DeviceModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单设备模拟器：一个进程模拟一台设备，监听一个端口。
 *
 * <p><b>为什么一台设备一个进程</b>：真实的实验室里，每台设备都是独立的硬件实体，
 * 有自己的响应速度、自己的故障模式。用一个进程模拟所有设备，
 * 测不出"设备 A 慢而设备 B 快"这类真实的并发问题。
 *
 * <p>每台设备一个模拟器后：
 * <ul>
 *   <li>可以单独把某台设备设成"慢"或"故障"，验证降级与隔离</li>
 *   <li>多台设备真并发（各自的 socket 互不阻塞）</li>
 *   <li>可以随时杀掉某一台，验证"设备离线"的处理</li>
 * </ul>
 *
 * <p><b>跨平台</b>：纯 Java 实现，一个 jar 在 macOS / Windows / Linux 上都能跑，
 * 不需要为每个平台单独打包。
 *
 * <p><b>用法</b>：
 * <pre>
 *   java -cp out com.labos.kernel.sim.DeviceSimulator \
 *        --port 19401 --device 171 --code ID1183 --cap INCUBATE --delay 200
 * </pre>
 *
 * <p><b>参数</b>：
 * <table>
 *   <tr><td>{@code --port}</td><td>监听端口（必填）</td></tr>
 *   <tr><td>{@code --device}</td><td>设备实例 id（回包里的 equipment_id）</td></tr>
 *   <tr><td>{@code --code}</td><td>设备编号，仅用于日志</td></tr>
 *   <tr><td>{@code --cap}</td><td>能力，仅用于日志</td></tr>
 *   <tr><td>{@code --delay}</td><td>每个动作耗时（毫秒），默认 0</td></tr>
 *   <tr><td>{@code --long}</td><td>模拟长任务：先回运行中，等 half 秒再回完成</td></tr>
 *   <tr><td>{@code --fail}</td><td>每 N 条**动作类**命令失败一次（0=不失败；查询不计数）</td></tr>
 *   <tr><td>{@code --fail-code}</td><td>失败时返回的错误码，默认 EC-05-0001</td></tr>
 *   <tr><td>{@code --fail-method}</td><td>只在这个方法上失败</td></tr>
 *   <tr><td>{@code --fail-from}</td><td>第 N 条动作类命令起才开始失败</td></tr>
 *   <tr><td>{@code --offline}</td><td>直接拒绝连接（模拟设备没上电）</td></tr>
 *   <tr><td>{@code --model}</td><td>按具体型号模拟（真实指令名、错误码、时序约束）</td></tr>
 * </table>
 */
public final class DeviceCore {

    private static final String SEP = "#!LABSEP!#";
    private static final Pattern P_ID = Pattern.compile("\"message_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_EQUIP = Pattern.compile("\"equipment_id\"\\s*:\\s*\"([^\"]*)\"");

    private volatile int port;
    private final String deviceId;
    private final String code;
    private final String capability;
    private final long delayMs;
    /**
     * 随机耗时区间（毫秒，闭区间）；null = 不随机，按画像/型号的固定耗时。
     *
     * <p>为什么需要：固定耗时测不出「设备还在执行时来暂停」这类场景 ——
     * 命令 150ms 就回来了，暂停信号根本来不及在动作中间到达。
     * 随机 30–300 秒才能复现真机（离心 5 分钟、PCR 几十分钟）的时序。
     */
    private final long randDelayMinMs;
    private final long randDelayMaxMs;
    /** 随机种子；固定则每次跑的序列相同（可复现），null = 真随机。 */
    private final Long randDelaySeed;
    private final java.util.Random randDelayRng;
    private final boolean longTask;
    private final int failEvery;
    /** 只在这个方法上注入故障；null = 所有方法。 */
    private final String failMethod;
    /** 第几条命令开始注入（1 起算）；0 = 从第一条开始。 */
    private final int failFrom;
    /** 设备类型画像：决定模拟器支持哪些方法。 */
    private final String profile;
    /** 设备画像：方法集、参数范围、默认耗时。 */
    private final DeviceProfile prof;

    /**
     * 具体型号定义；null 表示"只按类型模拟"。
     *
     * <p>有型号时，指令按**手册上的真实名**校验（同时接受我们的别名），
     * 错误码也能用型号专属的（如 S100 的 0x8000000C 无纸）。
     */
    private final DeviceModel model;

    /**
     * 型号专属行为（托盘原点、双门、模块初始化顺序…）。
     *
     * <p>为 null 时走通用状态机（只认识"门/运行/持板"三态）。
     */
    private final DeviceBehavior behavior;

    // ---- 设备状态机（改动前完全不维护，任何命令都回 OK）----
    /** 门是否开着。真实设备：门开着不能运行。 */
    private volatile boolean doorOpen;
    /** 是否正在跑长任务。真实设备：运行中不能开门。 */
    private volatile boolean running;
    /** 机械臂是否持板。真实设备：持着板不能再抓。 */
    private volatile boolean holding;
    private final String failCode;
    private final boolean offline;

    /**
     * 已经"报出去、还没被清除"的故障码。
     *
     * <p>真实设备多数是<b>故障锁存</b>的：报了无纸，你把纸装上但没发 {@code 0x06}
     * 清除故障，再按封膜还是报无纸。模拟器不实现锁存，中控就会以为
     * "重试一下就好了"，而现场必须有人去按清除键。
     */
    private volatile String latchedFault;

    /** GUI 上"手动制造一次故障"用的下一个故障码；消费一次即清空。 */
    private volatile String pendingFailCode;

    private final AtomicLong received = new AtomicLong();
    /**
     * **动作类**命令计数（查询不算）。
     *
     * <p>{@code --fail N} 的 N 按这个计数，而不是"收到的第 N 条"：
     * 中控穿插 {@code GetStatus} 很常见，按总条数算会让"每 3 条失败一次"
     * 变得不可预测（有时落在查询上、被跳过，这一轮就没失败）。
     */
    private final AtomicLong actionSeq = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong duplicated = new AtomicLong();

    /**
     * 已成功执行的命令 → 结果帧（幂等去重）。
     *
     * <p><b>为什么设备侧必须去重</b>：中控的超时策略是 retry ×3。
     * 如果设备<b>已经执行完、只是响应在路上丢了</b>，中控会带着
     * <b>同一个命令 id</b> 再发一次。设备若不去重，就会<b>再搬一次板</b>。
     *
     * <p>现在：收到重复 id → 直接返回上次的结果帧，不重复执行，
     * 并在日志里明确写"重复命令"。
     *
     * <p>只缓存**成功**结果（`method_status=2`）。失败（3）不缓存，
     * 让中控的重试真的能重试。
     */
    private final java.util.Map<String, String> doneCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **查询类**命令：重复调用是正常的，不该去重。
     *
     * <p>区分标准不是"有没有副作用"，而是**调用意图**：
     * <ul>
     *   <li>动作类（{@code Transfer}/{@code OpenDoor}/{@code RunProgram}）：
     *       中控重发说明上次结果丢了，重复执行会搬两次板 → 必须去重</li>
     *   <li>查询类（{@code GetStatus}/{@code GetPoints}/{@code CurrSpeed}）：
     *       中控重复调用就是想再查一次当前值，**去重会让它拿到过期数据**
     *       （比如点位表，本就该每次返回最新的）</li>
     * </ul>
     */
    private static final java.util.Set<String> QUERY_METHODS = java.util.Set.of(
            "GetStatus", "GetPoints", "GetPosition", "GetTemp", "GetResult",
            "Scan", "CurrSpeed", "CurrTime", "GetSpeed", "GetRemainTime",
            // 型号专属的查询类（S100 读参数/查板型/查版本，打印参数也只是读）
            "ReadParam", "GetPlateType", "QueryPlateType", "GetVersion", "PrintParams");

    // ---- 端点与服务控制（GUI 要能改 IP/端口、启停）----
    private volatile String bindHost = "0.0.0.0";
    private volatile boolean listening;
    private volatile ServerSocket serverSocket;
    private final java.util.Set<Socket> clients =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** GUI 订阅：日志 + 状态变化。CLI 模式可不设。 */
    public interface Listener {
        void onLog(String line);

        default void onStateChange() { }
    }

    private volatile Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 统一出口：既打 stdout（CLI/日志文件），也推给 GUI。 */
    private void log(String line) {
        System.out.println("[" + code + "] " + line);
        Listener l = listener;
        if (l != null) {
            l.onLog(line);
        }
    }

    public DeviceCore(int port, String deviceId, String code, String capability,
                            long delayMs, boolean longTask, int failEvery,
                            String failCode, boolean offline, String profile,
                            DeviceModel model) {
        this(port, deviceId, code, capability, delayMs, longTask, failEvery,
                failCode, offline, profile, model, null, 0);
    }

    /**
     * @param failMethod 只在这个方法上注入故障；null = 所有方法
     * @param failFrom   第几条命令起开始注入（1 起算）；0 = 从第一条开始
     */
    public DeviceCore(int port, String deviceId, String code, String capability,
                      long delayMs, boolean longTask, int failEvery,
                      String failCode, boolean offline, String profile,
                      DeviceModel model, String failMethod, int failFrom) {
        this(port, deviceId, code, capability, delayMs, longTask, failEvery,
                failCode, offline, profile, model, failMethod, failFrom,
                -1, -1, null);
    }

    /**
     * 完整构造：额外支持**随机耗时**。
     *
     * @param randDelayMinMs 随机耗时下界（毫秒，含）；&lt;= 0 表示不随机
     * @param randDelayMaxMs 随机耗时上界（毫秒，含）
     * @param randDelaySeed  随机种子；null = 真随机（每次不同），给定则可复现
     */
    public DeviceCore(int port, String deviceId, String code, String capability,
                      long delayMs, boolean longTask, int failEvery,
                      String failCode, boolean offline, String profile,
                      DeviceModel model, String failMethod, int failFrom,
                      long randDelayMinMs, long randDelayMaxMs, Long randDelaySeed) {
        this.port = port;
        this.deviceId = deviceId;
        this.code = code;
        this.capability = capability;
        this.longTask = longTask;
        this.failEvery = failEvery;
        this.failCode = failCode;
        this.failMethod = failMethod;
        this.failFrom = Math.max(0, failFrom);
        this.offline = offline;
        this.profile = profile == null ? "generic" : profile;
        DeviceProfile p = DeviceProfile.of(this.profile);
        if (p == null) {
            System.err.println("未知设备类型 " + this.profile + "，可用："
                    + DeviceProfile.knownNames());
            System.exit(2);
        }
        this.prof = p;
        this.model = model;
        // 有型号就用型号专属行为；没有就走通用状态机
        this.behavior = model == null ? null : newBehavior(model);
        // delay 传负数表示"用该类型的默认值"（离心 5 分钟 vs 扫描 0.4 秒，
        // 改动前一律 150ms，测出来的耗时没有参考意义）
        // 负数/0 = 用画像的分方法延迟；正数 = 所有方法统一用这个值（自动化测试想跑快）
        this.delayMs = delayMs > 0 ? delayMs : -1;
        // 随机耗时：显式给了区间就用随机（覆盖画像/型号的固定耗时），
        // 没给就沿用原逻辑。优先级：--rand-delay > --delay > 画像/型号默认。
        this.randDelayMinMs = Math.max(0, randDelayMinMs);
        this.randDelayMaxMs = Math.max(this.randDelayMinMs, randDelayMaxMs);
        this.randDelaySeed = randDelaySeed;
        this.randDelayRng = randDelaySeed == null
                ? new java.util.Random()
                : new java.util.Random(randDelaySeed);
    }

    /**
     * 型号 → 行为。**加一台设备时若它有时序约束，就在这里加一行**。
     *
     * <p>没有专属行为的型号返回 null（按通用状态机跑）——
     * 这允许"先登记型号、后补时序"，不会因为缺行为而跑不起来。
     */
    private static DeviceBehavior newBehavior(DeviceModel model) {
        return switch (model.id) {
            case "s100" -> new S100Behavior();
            case "aspin-2k" -> new AspinBehavior();
            default -> null;
        };
    }


    /**
     * 开始监听（GUI 点"启动"或 CLI 启动时调）。
     *
     * @param host 绑定地址；{@code 0.0.0.0} 表示所有网卡
     */
    public synchronized void startServer(String host, int port) throws IOException {
        if (listening) {
            stopServer();
        }
        this.bindHost = host == null || host.isBlank() ? "0.0.0.0" : host;
        this.port = port;

        if (offline) {
            log("模拟设备离线：不监听端口 " + port + "（中控会收到连接拒绝）");
            return;
        }

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new java.net.InetSocketAddress(this.bindHost, port));
        this.serverSocket = ss;
        this.listening = true;

        Thread accept = new Thread(() -> {
            while (listening) {
                try {
                    Socket client = ss.accept();
                    clients.add(client);
                    log("客户端接入 " + client.getRemoteSocketAddress()
                            + "（当前 " + clients.size() + " 个）");
                    notifyState();
                    Thread t = new Thread(() -> {
                        try {
                            handle(client);
                        } finally {
                            clients.remove(client);
                            log("客户端断开（剩 " + clients.size() + " 个）");
                            notifyState();
                        }
                    }, "sim-" + code + "-" + client.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (listening) {
                        log("监听异常：" + e.getMessage());
                    }
                    break;
                }
            }
        }, "sim-accept-" + code);
        accept.setDaemon(true);
        accept.start();

        log("监听 " + this.bindHost + ":" + port
                + "  device=" + deviceId + "  cap=" + capability
                + (delayMs > 0 ? "  delay=" + delayMs + "ms" : ""));
    }

    /** 停止监听并断开所有客户端。 */
    public synchronized void stopServer() {
        listening = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignore) {
            // 关不上也继续
        }
        serverSocket = null;
        for (Socket c : clients) {
            try {
                c.close();
            } catch (IOException ignore) {
                // 同上
            }
        }
        clients.clear();
        log("已停止监听");
        notifyState();
    }

    public boolean isListening() {
        return listening;
    }

    public int clientCount() {
        return clients.size();
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) {
            l.onStateChange();
        }
    }

    /** 当前状态快照（GUI 用来刷新指示灯/计数）。 */
    public java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code);
        m.put("profile", profile);
        if (model != null) {
            m.put("model", model.id);
            m.put("modelName", model.vendor + " " + model.model);
            m.put("protocol", model.protocol);
            m.put("transport", model.transport);
            m.put("manual", model.manual);
            m.put("evidence", model.evidence.name());
            m.put("doorCount", model.doorCount);
            // 错误码表给 GUI 下拉用：可以"按手册上的码"制造故障
            java.util.List<java.util.Map<String, Object>> errs = new java.util.ArrayList<>();
            for (var e : model.errorTable().values()) {
                java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("code", e.vendorCode());
                one.put("meaning", e.meaning());
                one.put("disposition", e.disposition().name());
                one.put("kernelCode", e.kernelCode());
                one.put("action", e.action());
                errs.add(one);
            }
            m.put("errors", errs);
            // 方法表：GUI 展示"能发哪些指令、哪些是手册没有的"
            java.util.List<java.util.Map<String, Object>> ms = new java.util.ArrayList<>();
            for (var mm : model.methods()) {
                java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("cmd", mm.cmd());
                one.put("alias", mm.aliasOf());
                one.put("documented", mm.documented());
                one.put("args", mm.args());
                ms.add(one);
            }
            m.put("modelMethods", ms);
        }
        if (behavior != null) {
            m.put("state", behavior.state());
        }
        m.put("latchedFault", latchedFault);
        m.put("device", deviceId);
        m.put("capability", capability);
        m.put("host", bindHost);
        m.put("port", port);
        m.put("listening", listening);
        m.put("clients", clients.size());
        m.put("delayMs", delayMs);
        m.put("doorOpen", doorOpen);
        m.put("running", running);
        m.put("holding", holding);
        m.put("received", received.get());
        m.put("failed", failed.get());
        m.put("duplicated", duplicated.get());
        m.put("hasDoor", prof.hasDoor);
        m.put("holdsPlate", prof.holdsPlate);
        // 有型号时，方法集以型号为准（画像只是参数边界）
        m.put("methods", model != null
                ? model.methods().stream().map(DeviceModel.Method::aliasOf).sorted().toList()
                : prof.methods.stream().sorted().toList());
        return m;
    }

    // ---- GUI 手动操作（"面板上点一下"，模拟现场按设备按钮）----

    public void manualOpenDoor() {
        doorOpen = true;
        log("（面板操作）开门");
        notifyState();
    }

    public void manualCloseDoor() {
        doorOpen = false;
        log("（面板操作）关门");
        notifyState();
    }

    public void manualToggleRunning(boolean run) {
        running = run;
        log("（面板操作）" + (run ? "启动" : "停止") + "运行");
        notifyState();
    }

    public void manualToggleHolding(boolean hold) {
        holding = hold;
        log("（面板操作）" + (hold ? "抓板" : "放板"));
        notifyState();
    }

    public boolean doorOpen() {
        return doorOpen;
    }

    public boolean runningState() {
        return running;
    }

    public boolean holdingState() {
        return holding;
    }

    public DeviceModel modelInfo() {
        return model;
    }

    public DeviceProfile profileInfo() {
        return prof;
    }

    // ---- 型号行为用的状态出口 ----

    /** 型号行为改门状态时调用（门开/关要反映到面板与通用状态机）。 */
    public void setDoorOpen(boolean open) {
        this.doorOpen = open;
        notifyState();
    }

    public void setRunning(boolean run) {
        this.running = run;
        notifyState();
    }

    /**
     * 让**下一条**符合条件的命令失败一次（GUI 上"制造一次无纸"）。
     *
     * <p>和 {@code --fail} 周期注入的区别：这个是**手动、一次性**，
     * 用来复现"现场刚好卡在这一步"的场景，不用重算第几条。
     */
    public void injectNextFailure(String vendorCode) {
        this.pendingFailCode = vendorCode;
        log("（面板操作）下一个动作将返回故障 " + vendorCode);
    }

    public String latchedFault() {
        return latchedFault;
    }

    /** 清除故障锁存（面板上相当于现场按了"清除故障"再复位）。 */
    public void clearLatchedFault() {
        latchedFault = null;
        log("（面板操作）清除故障锁存");
        notifyState();
    }

    /**
     * 面板上直接驱动一次型号动作（相当于"现场的人按了设备按钮"）。
     *
     * <p>典型用法：把托盘挪走再让中控封膜 —— 用来验证
     * "中控漏发回原点"这种情况会不会被 {@code 0x8000001A} 挡住。
     */
    public String manualModelCommand(String alias, String paramsJson) {
        if (behavior == null) {
            return "该型号没有专属行为";
        }
        String msg = "{\"method\":\"" + alias + "\""
                + (paramsJson == null || paramsJson.isBlank() ? "" : "," + paramsJson) + "}";
        DeviceBehavior.Reply r = behavior.handle(this, alias, msg);
        String result = r == null ? "交给默认逻辑" : (r.ok() ? "成功" : r.vendorCode());
        log("（面板操作）" + alias + " → " + result
                + (r != null && !r.ok() ? " " + r.errorText() : ""));
        notifyState();
        return result;
    }

    public String code() {
        return code;
    }

    public String deviceId() {
        return deviceId;
    }

    private void handle(Socket client) {
        try (client) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();

            StringBuilder buf = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                buf.append((char) ch);
                int idx = buf.indexOf(SEP);
                if (idx < 0) {
                    continue;
                }
                String msg = buf.substring(0, idx);
                buf.delete(0, idx + SEP.length());

                String reply = process(msg);
                if (reply != null) {
                    out.write((reply + SEP).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException ignore) {
            // 客户端断开
        }
    }

    /**
     * 处理一条命令：幂等去重 + 统一日志。
     *
     * <p>日志要能回答"这条命令到底怎么了"：
     * 收到 → 校验/状态是否放行 → 耗时多少 → 结果。现场排障就靠这个。
     */
    private String process(String msg) {
        String id = find(P_ID, msg);
        String method = find(P_METHOD, msg);

        String equipForDup = find(P_EQUIP, msg);

        // 查询类每次都真查一遍（见 QUERY_METHODS 的说明）
        if (method != null && QUERY_METHODS.contains(method)) {
            return processInner(msg);
        }

        // 幂等：同一个命令 id 已经成功执行过 → **不重复执行**
        if (id != null && !id.isEmpty()) {
            String cached = doneCache.get(id);
            if (cached != null) {
                // 缓存值格式：方法名 \u0000 完成时刻 \u0000 响应帧原文
                String[] parts = cached.split("\u0000", 3);
                String cachedMethod = parts.length > 0 ? parts[0] : "";
                String cachedAt = parts.length > 1 ? parts[1] : "?";
                if (method != null && method.equals(cachedMethod)) {
                    duplicated.incrementAndGet();
                    log("重复命令 id=" + id + "（" + method + "）→ 不重复执行；"
                            + "当前状态：" + stateText());
                    // **不能把上一帧原样返回**。上一帧只说明"上次那一刻成功了"，
                    // 而现在设备状态可能已经变了（离心转完、门被开过、板被取走）。
                    // 中控收到重发结果后要能判断"设备现在到底什么状态"，
                    // 所以这里重建一帧：保持成功语义（method_status=2），
                    // 但在 data 里明确写"这是重复命令、上次是什么时候做的、现状如何"。
                    return frame(id, method, equipForDup, 2, "null",
                            "{\"duplicate\":true,"
                                    + "\"originalCompletedAt\":\"" + cachedAt + "\","
                                    + "\"note\":\"该命令此前已执行成功，本次未重复执行\","
                                    + "\"currentState\":" + stateJson() + "}");
                }
                log("同 id 不同方法（缓存是 " + cachedMethod + "，这次是 " + method
                        + "）→ 按新命令处理");
            }
        }

        long t0 = System.currentTimeMillis();
        log("收到 " + method + "  id=" + id);
        String reply;
        try {
            reply = processInner(msg);
        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - t0;
            failed.incrementAndGet();
            log("异常 " + method + "（" + ms + "ms）：" + e.getMessage());
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;

        boolean ok = reply != null && reply.contains("\"method_status\":2");
        boolean rejected = reply != null && reply.contains("\"method_status\":3");
        if (ok) {
            if (id != null && !id.isEmpty() && !QUERY_METHODS.contains(method)) {
                doneCache.put(id, (method == null ? "" : method)
                        + '\u0000' + java.time.LocalTime.now().withNano(0)
                        + '\u0000' + reply);
                // 防止长跑把内存撑爆：超过 5000 条清掉最早的一半
                if (doneCache.size() > 5000) {
                    doneCache.keySet().stream().limit(2500).toList()
                            .forEach(doneCache::remove);
                }
            }
            log("完成 " + method + "（" + ms + "ms）");
        } else if (rejected) {
            failed.incrementAndGet();
            log("拒绝 " + method + "（" + ms + "ms）");
        } else {
            log("回应 " + method + "（" + ms + "ms）");
        }
        return reply;
    }

    /** 当前状态的 JSON（给"重复命令"的响应体用）。 */
    private String stateJson() {
        return "{\"door\":\"" + (doorOpen ? "open" : "closed") + "\","
                + "\"running\":" + running + ","
                + "\"holding\":" + holding + ","
                + "\"received\":" + received.get() + ","
                + "\"failed\":" + failed.get() + ","
                + "\"duplicated\":" + duplicated.get() + "}";
    }

    /** 当前状态的一句话（给日志用）。 */
    private String stateText() {
        StringBuilder sb = new StringBuilder();
        if (prof.hasDoor) {
            sb.append("门=").append(doorOpen ? "开" : "关").append(' ');
        }
        sb.append("运行=").append(running ? "进行中" : "空闲");
        if (prof.holdsPlate) {
            sb.append(" 手持=").append(holding ? "有板" : "空");
        }
        return sb.toString();
    }

    /** 真正的命令处理。外面包一层 {@link #process} 做幂等与日志。 */
    private String processInner(String msg) {
        String id = find(P_ID, msg);
        String alias = find(P_METHOD, msg);
        String equip = find(P_EQUIP, msg);
        long seq = received.incrementAndGet();

        // 回包里回**设备真正认识的名字**（手册上的 cmd）。
        // 别名只是我们流程里的叫法，抓包时应该和手册能逐字对上。
        String method = realMethodName(alias);

        // 只响应自己的设备；别的设备 id 说明中控配错了，明确回错误而不是静默
        if (!deviceId.equals(equip)) {
            System.out.println("[" + code + "] 收到别的设备的命令 equipment_id=" + equip
                    + "（我是 " + deviceId + "），返回错误");
            return frame(id, method, equip, 3,
                    "{\"error_code\":\"EC-06-0001\",\"error_text\":\"设备不属于本模拟器\"}",
                    "{}");
        }

        System.out.println("[" + code + "] #" + seq + " " + alias
                + (alias.equals(method) ? "" : " → " + method)
                + (delayMs > 0 ? "（模拟耗时 " + delayMs + "ms）" : ""));

        // 占位型号（只有型号、手册还没到）：不校验方法集，允许先按类型跑流程。
        // 这是"能建资源、能跑骨架、不能对接真机"的诚实表达。
        boolean checkMethodSet = model != null && !model.placeholder();
        if (checkMethodSet && !model.supports(alias)) {
            System.out.println("[" + code + "] 型号 " + model.id
                    + " 没有这条指令：" + alias);
            return frame(id, method, equip, 3,
                    "{\"error_code\":\"EC-06-0004\",\"error_text\":\"型号 "
                            + model.id + " 不支持指令 " + alias + "（手册里没有）\"}",
                    "{}");
        }

        // 设备画像校验：参数是否在机型范围内。
        // **有型号时不再校验"方法属不属于这个类型"** —— 型号才是方法集的权威：
        // S100 的 MoveTray/SetParam、ASpin 的 CreateModules 都不在通用画像里，
        // 按画像校验会把合法指令误拒（这个坑本轮实测踩到过）。
        String profileError = prof.validate(alias, msg, model == null);
        if (profileError != null) {
            System.out.println("[" + code + "] 校验失败 " + alias + "：" + profileError);
            // 方法认识、只是参数越界 → 0005；方法本身不认识 → 0004。
            // 有型号时"认不认识"要以型号为准（画像里可能没这个方法）。
            boolean known = prof.methods.contains(alias) || (model != null && model.supports(alias));
            String errCode = known ? "EC-06-0005" : "EC-06-0004";
            return frame(id, method, equip, 3,
                    "{\"error_code\":\"" + errCode + "\",\"error_text\":\""
                            + esc(profileError) + "\"}",
                    "{}");
        }

        // 清除类指令先解锁存故障（现场按"清除故障"要真的有效）
        if (isClearMethod(alias)) {
            if (latchedFault != null) {
                log("清除故障锁存 " + latchedFault);
            }
            latchedFault = null;
        }

        long waited = delayMs > 0 ? delayMs : methodDelayMs(alias, method);
        // 随机耗时优先（--rand-delay）：每次调用在区间里取一个值。
        // 为什么放在 delayMs 之后判断：--delay 是「所有方法统一固定耗时」，
        // 自动化测试要它；--rand-delay 是「模拟真机时序」，两者互斥时
        // 显式参数优先，但随机用于本场景（真机耗时不确定）。
        if (randDelayMaxMs > 0) {
            long span = randDelayMaxMs - randDelayMinMs;
            waited = span <= 0
                    ? randDelayMinMs
                    : randDelayMinMs + (long) (randDelayRng.nextDouble() * (span + 1));
            if (waited > randDelayMaxMs) {
                waited = randDelayMaxMs;
            }
        }
        sleep(waited);

        // ① 故障注入优先：真机是先失败、不是先成功。
        //    放在成功分支之前 —— 改动前放在 RunProgram 之后，导致离心永远测不出失败。
        String injected = pickInjection(alias);
        if (injected != null) {
            failed.incrementAndGet();
            return errorFrame(id, method, equip, injected, "模拟故障", true);
        }

        // ② 型号专属行为（托盘原点、双门、模块初始化顺序…）
        if (behavior != null) {
            DeviceBehavior.Reply r = behavior.handle(this, alias, msg);
            if (r != null) {
                if (!r.ok()) {
                    failed.incrementAndGet();
                    return errorFrame(id, method, equip, r.vendorCode(), r.errorText(), false);
                }
                String done = frame(id, method, equip, 2, "null", r.dataJson());
                if (longTask) {
                    // 型号行为也要支持"先回运行中、再回完成"，
                    // 否则 --long 在有型号的设备上会静默失效（测不出运行中处理）
                    String running = frame(id, method, equip, 1, "null", "{}");
                    sleep(Math.max(1000, delayMs / 2));
                    return running + SEP + done;
                }
                return done;
            }
        }

        // ③ 通用状态机：真实设备会拒绝非法时序，模拟器必须也能
        String stateError = checkState(alias);
        if (stateError != null) {
            System.out.println("[" + code + "] 状态不允许 " + alias + "：" + stateError);
            return frame(id, method, equip, 3,
                    "{\"error_code\":\"EC-06-0006\",\"error_text\":\""
                            + esc(stateError) + "\"}",
                    "{}");
        }
        applyState(alias);

        // 门操作：动作成功即状态变更
        if (isDoorMethod(alias)) {
            return frame(id, method, equip, 2, "null",
                    "{\"door\":\"" + (alias.startsWith("Open") ? "open" : "closed") + "\"}");
        }

        // 点位同步：机械臂返回它"认为"的点位列表
        if ("GetPoints".equals(alias) || "SyncPoints".equals(alias)) {
            return frame(id, method, equip, 2, "null", pointsJson());
        }

        // 离心程序：回显传入的参数，便于验证参数是否传到
        if ("RunProgram".equals(alias) || "Centrifuge".equals(alias)) {
            String rpm = find(java.util.regex.Pattern.compile("\"rpm\"\\s*:\\s*(\\d+)"), msg);
            String min = find(java.util.regex.Pattern.compile("\"minutes\"\\s*:\\s*(\\d+)"), msg);
            return frame(id, method, equip, 2, "null",
                    "{\"rpm\":" + (rpm.isEmpty() ? "0" : rpm)
                            + ",\"minutes\":" + (min.isEmpty() ? "0" : min)
                            + ",\"finished\":true}");
        }

        String done = frame(id, method, equip, 2, "null",
                "{\"position\":\"A1\",\"plate\":\"SIM-PLATE-" + seq + "\"}");

        if (longTask) {
            // 先回一帧"运行中"，再接"完成"。中控应当继续等，而不是判超时。
            String running = frame(id, method, equip, 1, "null", "{}");
            sleep(Math.max(1000, delayMs / 2));
            return running + SEP + done;
        }
        return done;
    }

    /** 别名 → 设备真实指令名；没有型号时原样返回。 */
    private String realMethodName(String alias) {
        if (model == null) {
            return alias;
        }
        DeviceModel.Method m = model.method(alias);
        return m == null ? alias : m.cmd();
    }

    /** 这台型号的某个方法模拟耗时（型号里给了就用型号的）。 */
    private long methodDelayMs(String alias, String method) {
        if (model != null) {
            DeviceModel.Method m = model.method(alias);
            if (m != null && m.delayMs() > 0) {
                return m.delayMs();
            }
        }
        return prof.methodDelayMs(method);
    }

    /** 清除故障类指令：锁存的故障能被它们解开。 */
    private static boolean isClearMethod(String method) {
        return "ClearFault".equals(method) || "ClearError".equals(method)
                || "Reset".equals(method) || "Home".equals(method);
    }

    /**
     * "会动起来"的指令：故障注入与故障锁存只作用在它们身上。
     *
     * <p>判定是**排除法**而不是穷举：查询类（{@code GetStatus}/{@code GetPoints}）
     * 与清除类（{@code ClearFault}/{@code Reset}）之外的都算动作类。
     * 穷举法踩过坑 —— 一开始只列了 Seal/Start，结果机器人 {@code Transfer}
     * 完全不响应 {@code --fail}，现有脚本的故障注入静默失效。
     */
    private static boolean isActionMethod(String method) {
        if (method == null || method.isBlank()) {
            return false;
        }
        if (QUERY_METHODS.contains(method) || isClearMethod(method)) {
            return false;
        }
        return !isDoorMethod(method);
    }

    /**
     * 决定这条命令要不要注入故障，返回设备错误码（null = 不注入）。
     *
     * <p>三个来源，优先级从高到低：
     * <ol>
     *   <li>面板手动注入（{@link #injectNextFailure}）—— 一次性；</li>
     *   <li>已锁存的故障 —— 真机就是这样：不按清除就一直报同一个；</li>
     *   <li>{@code --fail N} 周期性注入。</li>
     * </ol>
     */
    private String pickInjection(String method) {
        if (!isActionMethod(method)) {
            return null;
        }
        long n = actionSeq.incrementAndGet();

        String manual = pendingFailCode;
        if (manual != null) {
            pendingFailCode = null;
            return manual;
        }
        if (latchedFault != null) {
            return latchedFault;
        }
        if (failEvery > 0 && n >= failFrom && n % failEvery == 0) {
            if (failMethod == null || failMethod.isBlank() || failMethod.equals(method)) {
                return failCode;
            }
        }
        return null;
    }

    /**
     * 设备错误帧。
     *
     * <p><b>这里只回设备自己的码</b>（{@code error_code=0x8000000C}），
     * 翻译成中控码是 {@code DeviceErrorMapper} 的事。
     * 但型号库里登记过的码会额外带上"手册原文含义"和"现场该干什么"，
     * 因为模拟器的读者是调试的人，不是设备。
     */
    private String errorFrame(String id, String method, String equip,
                              String vendorCode, String detail, boolean latch) {
        DeviceModel.ErrorDef def = model == null ? null : model.error(vendorCode);
        String text = def != null ? def.meaning() : (detail == null ? "模拟故障" : detail);
        StringBuilder err = new StringBuilder("{");
        err.append("\"error_code\":\"").append(esc(vendorCode)).append("\",");
        err.append("\"error_text\":\"").append(esc(text)).append("\"");
        if (def != null) {
            err.append(",\"disposition\":\"").append(def.disposition()).append("\"");
            err.append(",\"action\":\"").append(esc(def.action())).append("\"");
        } else if (detail != null) {
            err.append(",\"detail\":\"").append(esc(detail)).append("\"");
        }
        err.append('}');

        if (latch) {
            latchedFault = vendorCode;
        }
        log("故障 " + vendorCode + " " + text
                + (def == null ? "（型号库未登记）" : " → 建议：" + def.action())
                + (latch ? "（已锁存，需 ClearFault/Reset 解除）" : ""));
        return frame(id, method, equip, 3, err.toString(), "{}");
    }

    /**
     * 状态检查：真实设备会因为"门没关""正在跑""手里有板"而拒绝。
     *
     * <p>模拟器不校验这些的话，中控把"没关门就离心"这种顺序配错，
     * 要等到现场撞了才知道。
     */
    private String checkState(String method) {
        // 1) 运行类命令：门必须已关
        boolean isRun = switch (method) {
            case "RunProgram", "Centrifuge", "Read", "Wash", "Seal", "Peel", "Start", "Scan" -> true;
            default -> false;
        };
        if (isRun) {
            if (prof.hasDoor && doorOpen) {
                return "门未关闭，不能执行 " + method;
            }
            if (running) {
                return "设备正在运行，不能重复启动";
            }
        }
        // 2) 运行中不能开门
        if (isDoorMethod(method) && method.startsWith("Open") && running) {
            return "运行中禁止开门";
        }
        // 3) 机械臂：持板状态
        if (prof.holdsPlate) {
            if ("Grip".equals(method) && holding) {
                return "已持板，不能重复抓取";
            }
            if ("Release".equals(method) && !holding) {
                return "手里没有板，无法释放";
            }
        }
        return null;
    }

    /** 命令被接受后更新状态。 */
    private void applyState(String method) {
        if (isDoorMethod(method)) {
            doorOpen = method.startsWith("Open");
            return;
        }
        if (prof.holdsPlate) {
            if ("Grip".equals(method)) {
                holding = true;
            } else if ("Release".equals(method)) {
                holding = false;
            }
        }
        switch (method) {
            case "RunProgram", "Centrifuge", "Read", "Wash", "Seal", "Peel", "Start", "Scan" -> {
                running = true;
                // 同步任务：模拟器在这条命令里就等完了，返回前复位
                // （真实长任务用 --long 走两帧）
                if (!longTask) {
                    running = false;
                }
            }
            default -> { }
        }
    }

    /** JSON 字符串转义（错误信息里可能有引号）。 */
    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isDoorMethod(String method) {
        return method != null && (method.startsWith("Open") || method.startsWith("Close"));
    }

    /** 机械臂上报的点位（模拟固定 4 个层位）。 */
    private String pointsJson() {
        return "{\"points\":["
                + "{\"code\":\"" + code + "-L1\",\"x\":320,\"y\":140,\"z\":45},"
                + "{\"code\":\"" + code + "-L2\",\"x\":320,\"y\":180,\"z\":45},"
                + "{\"code\":\"" + code + "-L3\",\"x\":320,\"y\":220,\"z\":45},"
                + "{\"code\":\"" + code + "-L4\",\"x\":320,\"y\":260,\"z\":45}"
                + "]}";
    }

    private String frame(String id, String method, String equip,
                         int status, String errorJson, String dataJson) {
        return "{\"message_id\":\"" + id + "\","
                + "\"message_type\":5,"
                + "\"method\":\"" + method + "\","
                + "\"equipment_id\":\"" + equip + "\","
                + "\"equipment_status\":1,"
                + "\"method_status\":" + status + ","
                + "\"error\":" + errorJson + ","
                + "\"data\":" + dataJson + ","
                + "\"process_info\":{},"
                + "\"timestamp\":\"" + java.time.LocalDateTime.now()
                        .withNano(0).toString().replace('T', ' ') + "\","
                + "\"checksum\":\"\","
                + "\"connected\":1}";
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 从命令 JSON 里取一个整数字段；取不到返回 null。
     *
     * <p>型号行为用它拿真实参数（{@code value} / {@code index} / {@code plateType}）。
     * 取不到时行为要回"参数缺失"，而不是当成 0 —— 把缺失当 0 会让
     * "少传一个参数"这种缺陷在仿真里看不出来。
     */
    static Integer intParam(String msg, String key) {
        if (msg == null) {
            return null;
        }
        // 值可能带引号：真实驱动（TcpDriverAdapter）把所有参数都序列化成字符串，
        // 模拟器必须两种都认，否则"参数传过去了但模拟器说没传"这种假故障会浪费半天。
        Matcher m = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(msg);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
