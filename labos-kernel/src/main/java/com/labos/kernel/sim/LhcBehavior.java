package com.labos.kernel.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BioTek LHC 洗板/分液机的真实行为。
 *
 * <p>依据：{@code LHC Runner User Application Interface Specification 5336208-SP Rev G}
 * （我方解读见 {@code docs/设备/洗板机-BioTek-LHC.md}）。
 *
 * <p>手册里能确定的时序约束（这些<b>不实现就等于没接</b>）：
 * <ol>
 *   <li><b>初始化不可跳过</b>：{@code SetProductName → SetCommunications →
 *       TestCommunications}，否则后面任何一条都失败（本模拟器报 {@code EC-05-2204}）；</li>
 *   <li><b>没有装载协议不能跑</b>（{@code EC-05-2205}）；</li>
 *   <li><b>装载后必须先验证</b>，或者显式 {@code OverrideValidation(1)}（{@code EC-05-2206}）；
 *       而且<b>每 Load 一次就作废一次验证</b> —— 手册原话，最容易漏的一条；</li>
 *   <li><b>RunProtocol 只有返回 eBusy(3) 才算启动成功</b>，其它值一律没启动；</li>
 *   <li><b>Abort 必须先 Pause</b>（或运行中直接中止），暂停后才可 Resume/Abort；</li>
 *   <li>50 TS 才有条（strip）范围，且 {@code SetFirstStrip}/{@code SetNumberOfStrips}
 *       必须成对、不得越出板宽；</li>
 *   <li>同一时刻只能有一个协议在跑（{@code EC-05-2210}）。</li>
 * </ol>
 *
 * <p><b>轮询怎么模拟</b>：真机靠不断调 {@code GetProtocolStatus} 推进协议。
 * 这里用"轮询 N 次后完成"来近似（{@link #pollsToFinish}），好处是
 * "少轮询/不轮询就直接当成功"这种缺陷在仿真阶段就能暴露 —— 播完第一次状态还是 eBusy。
 *
 * <p><b>不模拟的部分</b>（诚实标注）：真实洗板动作（吸/排/浸泡）对 LabOS 不可见，
 * 参数全在 .LHC 文件里，所以模拟器只维护状态机，不校验洗涤体积/次数。
 */
final class LhcBehavior implements DeviceBehavior {

    /** 手册 §6.5 的产品名字符串；写错就是 {@code eInvalid_Product_Type(4)}。 */
    private static final List<String> PRODUCT_NAMES = List.of(
            "EL406", "405 TS/LS", "ELx405", "MultiFlo", "MultiFloFX", "MicroFlo Select", "50 TS");

    /** 手册 §6.4 RunStatus。 */
    private static final int ST_UNINITIALIZED = 0;
    private static final int ST_READY = 1;
    private static final int ST_NOT_READY = 2;
    private static final int ST_BUSY = 3;
    private static final int ST_ERROR = 4;
    private static final int ST_DONE = 5;
    private static final int ST_PAUSED = 7;
    private static final int ST_STOPPED = 9;

    private String productName;
    private String communications;
    private boolean commTested;
    private boolean selfCheckDone;

    private String protocol;
    private String protocolSource;      // file / flash
    private boolean validated;
    private boolean overrideValidation;
    private boolean vacuumKeepOn;

    /** 当前 RunStatus。 */
    private int runStatus = ST_UNINITIALIZED;
    private int polls;
    private int pollsToFinish = 3;
    private int lastErrorCode;
    private String lastErrorText;

    private int firstStrip = 1;
    private int stripCount = 12;

    private boolean manifoldTestRunning;
    private int manifoldPolls;

    @Override
    public String modelId() {
        return "biotek-lhc";
    }

    @Override
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", productName);
        m.put("communications", communications);
        m.put("commTested", commTested);
        m.put("selfCheckDone", selfCheckDone);
        m.put("protocol", protocol);
        m.put("validated", validated);
        m.put("overrideValidation", overrideValidation);
        m.put("vacuumKeepOn", vacuumKeepOn);
        m.put("runStatus", runStatus);
        m.put("runStatusText", runStatusText(runStatus));
        m.put("polls", polls);
        m.put("lastErrorCode", lastErrorCode);
        m.put("firstStrip", firstStrip);
        m.put("stripCount", stripCount);
        return m;
    }

    @Override
    public Reply handle(DeviceCore core, String method, String msg) {
        switch (method) {
            // ------------------------------------------------ 初始化
            case "LHC_SetProductName", "SetProductName" -> {
                String name = DeviceCore.strParam(msg, "productName");
                if (name == null) {
                    name = DeviceCore.strParam(msg, "name");
                }
                if (name == null || name.isBlank()) {
                    return Reply.err("4", "SetProductName 缺少 productName（手册 §6.5 的字符串）");
                }
                if (!PRODUCT_NAMES.contains(name.trim())) {
                    // 手册："50 TS" 中间必须有空格；拼错就是 eInvalid_Product_Type
                    return Reply.err("4", "产品名 \"" + name + "\" 不被识别，可用：" + PRODUCT_NAMES);
                }
                productName = name.trim();
                // 换机型 ⇒ 之前的通讯测试与协议装载全部作废（真机会重新握手）
                commTested = false;
                protocol = null;
                validated = false;
                runStatus = ST_UNINITIALIZED;
                return Reply.ok("{\"productName\":\"" + productName + "\"}");
            }
            case "LHC_SetCommunications", "SetCommunications" -> {
                String port = DeviceCore.strParam(msg, "port");
                if (port == null) {
                    port = DeviceCore.strParam(msg, "setting");
                }
                if (port == null || port.isBlank()) {
                    return Reply.err("0", "SetCommunications 缺少 port（\"COMn\" 或 \"USB <产品> sn:<序列号>\"）");
                }
                communications = port.trim();
                commTested = false;
                return Reply.ok("{\"communications\":\"" + communications + "\"}");
            }
            case "LHC_TestCommunications", "TestCommunications" -> {
                Reply bad = requireInit();
                if (bad != null) {
                    return bad;
                }
                commTested = true;
                runStatus = ST_READY;
                return Reply.ok("{\"connected\":true,\"productName\":\"" + productName + "\"}");
            }
            case "LHC_PerformSelfCheck", "SelfCheck", "Home" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                selfCheckDone = true;
                runStatus = protocol == null ? ST_READY : runStatus;
                return Reply.ok("{\"selfCheck\":\"passed\",\"productName\":\"" + productName + "\"}");
            }

            // ------------------------------------------------ 协议装载 / 验证
            case "LHC_LoadProtocolFromFile", "LoadProtocol" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                String path = DeviceCore.strParam(msg, "protocolPath");
                if (path == null) {
                    path = DeviceCore.strParam(msg, "path");
                }
                if (path == null || path.isBlank()) {
                    return Reply.err("5", "LoadProtocol 缺少协议文件路径");
                }
                if (!path.toLowerCase().endsWith(".lhc")) {
                    return Reply.err("5", "协议文件名不是 .LHC：" + path);
                }
                return loadProtocol(path, "file");
            }
            case "LHC_LoadProtocolFromFlash", "LoadProtocolFromFlash" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                String name = DeviceCore.strParam(msg, "protocolName");
                if (name == null || name.isBlank()) {
                    return Reply.err("5", "LoadProtocolFromFlash 缺少机内程序名");
                }
                return loadProtocol(name, "flash");
            }
            case "LHC_ValidateProtocol", "ValidateProtocol" -> {
                Reply bad = requireProtocol();
                if (bad != null) {
                    return bad;
                }
                // bGetSettingsFirst=false 时手册明确"不保证能在本机跑通"，这里给提示但仍放行
                Integer getSettings = DeviceCore.intParam(msg, "getSettingsFirst");
                if (getSettings != null && getSettings == 0) {
                    return Reply.err("6",
                            "ValidateProtocol(false) 只按协议文件里的设置校验，可能与本机不匹配；"
                                    + "请改用 true");
                }
                validated = true;
                return Reply.ok("{\"validated\":true,\"protocol\":\"" + protocol + "\"}");
            }
            case "LHC_OverrideValidation", "OverrideValidation" -> {
                if ("ELx405".equals(productName) || "MicroFlo Select".equals(productName)) {
                    return Reply.err("0", productName + " 不支持 OverrideValidation");
                }
                Integer v = DeviceCore.intParam(msg, "override");
                if (v == null || (v != 0 && v != 1)) {
                    return Reply.err("0", "OverrideValidation 只接受 0 或 1");
                }
                overrideValidation = v == 1;
                return Reply.ok("{\"overrideValidation\":" + overrideValidation + "}");
            }
            case "LHC_LeaveVacuumPumpOn", "LeaveVacuumPumpOn" -> {
                Integer v = DeviceCore.intParam(msg, "vacPumpOn");
                if (v == null) {
                    v = DeviceCore.intParam(msg, "on");
                }
                if (v == null || (v != 0 && v != 1)) {
                    // 手册：这条在协议跑完后来回切；0/1 之外没有定义
                    return Reply.err("0", "LeaveVacuumPumpOn 只接受 0 或 1");
                }
                vacuumKeepOn = v == 1;
                return Reply.ok("{\"vacuumPumpOn\":" + vacuumKeepOn + "}");
            }
            case "LHC_SetRunnerThreading", "SetRunnerThreading" ->
                    Reply.ok("{\"threading\":" + String.valueOf(
                            DeviceCore.intParam(msg, "threadingModel")) + "}");

            // ------------------------------------------------ 执行 / 控制
            case "LHC_RunProtocol", "RunProtocol", "Wash", "Dispense", "Prime", "Run" -> {
                Reply bad = requireProtocol();
                if (bad != null) {
                    return bad;
                }
                if (runStatus == ST_BUSY || runStatus == ST_PAUSED) {
                    return Reply.err("EC-05-2210",
                            "上一条协议仍在运行（runStatus=" + runStatusText(runStatus) + "）");
                }
                if (!validated && !overrideValidation) {
                    return Reply.err("EC-05-2206",
                            "协议已装载但未验证：先 ValidateProtocol(true)，或 OverrideValidation(1)");
                }
                // 50 TS 的条范围校验放在这里：真机是启动时才判的
                if ("50 TS".equals(productName) && firstStrip + stripCount - 1 > 12) {
                    return Reply.err("EC-05-2207",
                            "条范围越界：firstStrip=" + firstStrip + ", numberOfStrips=" + stripCount);
                }
                polls = 0;
                runStatus = ST_BUSY;
                // 手册："RunProtocol 只有返回 eBusy 才算启动成功"
                return Reply.ok("{\"runStatus\":" + ST_BUSY
                        + ",\"runStatusText\":\"eBusy\",\"protocol\":\"" + protocol + "\"}");
            }
            case "LHC_GetProtocolStatus", "GetProtocolStatus", "GetStatus" -> {
                advance();
                return Reply.ok("{\"runStatus\":" + runStatus
                        + ",\"runStatusText\":\"" + runStatusText(runStatus) + "\""
                        + ",\"protocol\":" + (protocol == null ? "null" : "\"" + protocol + "\"")
                        + ",\"lastErrorCode\":" + lastErrorCode + "}");
            }
            case "LHC_PauseProtocol", "PauseProtocol", "Pause" -> {
                if (runStatus != ST_BUSY) {
                    return Reply.err("EC-05-2201",
                            "只能在运行中暂停，当前 " + runStatusText(runStatus));
                }
                runStatus = ST_PAUSED;
                return Reply.ok("{\"runStatus\":" + ST_PAUSED + "}");
            }
            case "LHC_ResumeProtocol", "ResumeProtocol", "Resume" -> {
                if (runStatus != ST_PAUSED) {
                    return Reply.err("EC-05-2201",
                            "必须先暂停才能继续，当前 " + runStatusText(runStatus));
                }
                runStatus = ST_BUSY;
                return Reply.ok("{\"runStatus\":" + ST_BUSY + "}");
            }
            case "LHC_AbortProtocol", "AbortProtocol", "Abort", "Stop" -> {
                if (runStatus != ST_BUSY && runStatus != ST_PAUSED) {
                    return Reply.err("EC-05-2201",
                            "没有正在运行的协议可中止（当前 " + runStatusText(runStatus) + "）");
                }
                runStatus = ST_STOPPED;
                return Reply.ok("{\"runStatus\":" + ST_STOPPED + ",\"aborted\":true}");
            }

            // ------------------------------------------------ 读回
            case "LHC_GetLastErrorCode", "GetLastErrorCode" ->
                    Reply.ok("{\"errorCode\":" + lastErrorCode + "}");
            case "LHC_GetErrorString", "GetErrorString" ->
                    Reply.ok("{\"message\":\"" + esc(lastErrorText == null
                            ? "（无错误）" : lastErrorText) + "\"}");
            case "LHC_GetProductName", "GetProductName" ->
                    Reply.ok("{\"productName\":"
                            + (productName == null ? "null" : "\"" + productName + "\"") + "}");
            case "LHC_GetProductSerialNumber", "GetSerialNumber" -> {
                if ("ELx405".equals(productName)) {
                    return Reply.err("0", "ELx405 不支持读取序列号");
                }
                return Reply.ok("{\"serialNumber\":\"SIM-LHC-" + Math.abs(hash()) + "\"}");
            }
            case "LHC_GetWellCount", "GetWellCount" -> {
                if ("ELx405".equals(productName) || "MicroFlo Select".equals(productName)) {
                    return Reply.err("0", productName + " 不支持 GetWellCount");
                }
                Reply bad = requireProtocol();
                if (bad != null) {
                    return bad;
                }
                return Reply.ok("{\"wellCount\":96}");
            }
            case "LHC_GetOnBoardProtocolCount", "GetOnBoardProtocolCount" ->
                    Reply.ok("{\"count\":3}");
            case "LHC_GetOnBoardProtocolName", "GetOnBoardProtocolName" -> {
                Integer idx = DeviceCore.intParam(msg, "index");
                if (idx == null || idx < 0 || idx > 2) {
                    return Reply.ok("{\"protocolName\":\"\"}");
                }
                return Reply.ok("{\"protocolName\":\"SIM_ONBOARD_" + (idx + 1) + "\"}");
            }

            // ------------------------------------------------ 50 TS 条范围
            // 手册 §5.28：条（strip）范围只有 50 TS 有。
            // **为什么本方法只校验自身范围、不做交叉校验**：手册说两者"必须成对使用"，
            // 但没说先调哪个；若在这里就用当前 numberOfStrips 交叉校验，
            // "先 SetFirstStrip(8) 再 SetNumberOfStrips(5)"这种合法顺序会被误拒。
            // ⇒ 交叉校验放在 SetNumberOfStrips（能立刻判断）与 RunProtocol（最终闸门）。
            case "LHC_SetFirstStrip", "SetFirstStrip" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                if (!"50 TS".equals(productName)) {
                    return Reply.err("0", "SetFirstStrip 只对 50 TS 有效（当前 " + productName + "）");
                }
                Integer v = DeviceCore.intParam(msg, "firstStrip");
                if (v == null || v < 1 || v > 12) {
                    return Reply.err("EC-05-2207", "firstStrip 必须是 1..12，收到 " + v);
                }
                firstStrip = v;
                return Reply.ok("{\"firstStrip\":" + firstStrip
                        + ",\"numberOfStrips\":" + stripCount + "}");
            }
            case "LHC_SetNumberOfStrips", "SetNumberOfStrips" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                if (!"50 TS".equals(productName)) {
                    return Reply.err("0", "SetNumberOfStrips 只对 50 TS 有效（当前 " + productName + "）");
                }
                Integer v = DeviceCore.intParam(msg, "numberOfStrips");
                if (v == null || v < 1 || v > 12) {
                    return Reply.err("EC-05-2207", "numberOfStrips 必须是 1..12，收到 " + v);
                }
                if (firstStrip + v - 1 > 12) {
                    return Reply.err("EC-05-2207",
                            "firstStrip=" + firstStrip + " + numberOfStrips=" + v
                                    + " 超出板宽 12 列；先调小 firstStrip 或减少条数");
                }
                stripCount = v;
                return Reply.ok("{\"numberOfStrips\":" + stripCount + "}");
            }

            // ------------------------------------------------ Verify Manifold
            case "LHC_RunVerifyManifoldTest", "RunVerifyManifoldTest" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                if (!"405 TS/LS".equals(productName)) {
                    return Reply.err("0",
                            "Verify Manifold 只支持带液面检测的 405 TS/LS（当前 " + productName + "）");
                }
                manifoldTestRunning = true;
                manifoldPolls = 0;
                return Reply.ok("{\"runStatus\":" + ST_BUSY + "}");
            }
            case "LHC_GetVerifyManifoldRunStatus", "GetVerifyManifoldRunStatus" -> {
                if (!manifoldTestRunning) {
                    return Reply.ok("{\"runStatus\":" + ST_READY + "}");
                }
                if (++manifoldPolls >= 3) {
                    manifoldTestRunning = false;
                    return Reply.ok("{\"runStatus\":" + ST_READY + "}");
                }
                return Reply.ok("{\"runStatus\":" + ST_BUSY + "}");
            }
            case "LHC_GetVerifyManifoldTestResults", "GetVerifyManifoldResults" ->
                    Reply.ok("{\"results\":\"PASSED\"}");
            case "LHC_CreateVerifyManifoldTestReport", "CreateVerifyManifoldReport" ->
                    Reply.ok("{\"report\":\"C:\\\\ProgramData\\\\BioTek\\\\ManifoldTest\\\\SIM_r1.txt\"}");
            // 没登记在这台型号行为里的方法：交回通用状态机
            default -> {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 内部

    private Reply loadProtocol(String name, String source) {
        protocol = name;
        protocolSource = source;
        // 手册原话："Each time you load a protocol, all the validation information is removed"
        validated = false;
        runStatus = ST_READY;
        return Reply.ok("{\"protocol\":\"" + esc(name) + "\",\"source\":\"" + source
                + "\",\"validated\":false}");
    }

    /** 轮询推进：Busy 累计 3 次后完成。 */
    private void advance() {
        if (runStatus != ST_BUSY) {
            return;
        }
        if (++polls >= pollsToFinish) {
            runStatus = ST_READY;
        }
    }

    private Reply requireInit() {
        if (productName == null || communications == null) {
            lastErrorCode = 6;
            lastErrorText = "未完成初始化（缺 SetProductName / SetCommunications）";
            return Reply.err("EC-05-2204", lastErrorText);
        }
        return null;
    }

    private Reply requireComm() {
        Reply bad = requireInit();
        if (bad != null) {
            return bad;
        }
        if (!commTested) {
            lastErrorCode = 6;
            lastErrorText = "未做通讯测试（TestCommunications）";
            return Reply.err("EC-05-2204", lastErrorText);
        }
        return null;
    }

    private Reply requireProtocol() {
        Reply bad = requireComm();
        if (bad != null) {
            return bad;
        }
        if (protocol == null) {
            lastErrorCode = 6;
            lastErrorText = "尚未装载协议";
            return Reply.err("EC-05-2205", lastErrorText);
        }
        return null;
    }

    private int hash() {
        int h = (productName + "/" + protocol).hashCode();
        return h == 0 ? 1 : h;
    }

    /** 手册 §6.4 的状态名（给日志/面板看，避免只看到数字）。 */
    static String runStatusText(int s) {
        return switch (s) {
            case ST_UNINITIALIZED -> "eUninitialized";
            case ST_READY -> "eReady";
            case ST_NOT_READY -> "eNotReady";
            case ST_BUSY -> "eBusy";
            case ST_ERROR -> "eError";
            case ST_DONE -> "eDone";
            case 6 -> "eIncomplete";
            case ST_PAUSED -> "ePaused";
            case 8 -> "eStopRequested";
            case ST_STOPPED -> "eStopping";
            case 10 -> "eNotRequired";
            default -> "unknown(" + s + ")";
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
