package com.labos.kernel.device;

import com.labos.kernel.device.DeviceModel.Disposition;
import com.labos.kernel.device.DeviceModel.Evidence;
import com.labos.kernel.persistence.Jsons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **型号库**：已经适配过的设备型号，一台一条定义。
 *
 * <p>加一台新设备的成本应当只有"在这里加一条"——
 * 模拟器、中控错误码映射、SQL 种子、接口文档全部由这一条派生。
 *
 * <p><b>离心机是一个一个加的</b>：不同厂商的指令名、参数、错误码都不一样，
 * 没有手册就只能按 {@link Evidence#PLANNED} 占位（能建资源、能跑流程骨架，
 * <b>但不能对接真机</b>）。占位清单见 {@link #planned()}，
 * 拿到手册后按"加设备五步"补成 {@link Evidence#MANUAL}。
 */
public final class DeviceModels {

    private DeviceModels() {
    }

    /**
     * 这个型号是不是"已知但手册未到"（{@link #planned()} 里的占位）。
     *
     * <p><b>为什么要单独问</b>：占位型号**允许登记设备** ——
     * 方法集可以从模拟器画像（厂商公开规格）派生 ⇒ 设计器里能选到方法、流程能配出来；
     * 但**接真机还不行**（没手册 ⇒ 没协议、没错误码）⇒ 登记时要打一行警告。
     */
    public static boolean isPlanned(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String id = modelId.trim();
        for (Planned p : PLANNED) {
            if (id.equals(p.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 型号 → 模拟器画像。
     *
     * <p><b>为什么按型号而不是按设备类型</b>：设备类型（{@code device.device_type}）
     * 分不出"通用机械臂"与"轨道四轴臂"、"通用板存储"与"自动化耗材栈" ——
     * 而这两者对模拟器是**不同的画像**（轨道臂每次抓板要扫码、耗材栈 288 位）。
     * 型号 id 是唯一的 ⇒ 用它区分。
     *
     * <p>只列**型号级特有**的映射；通用型号（aspin-2k / s100）走
     * {@code DeviceService.simProfileOf} 的设备类型/能力兜底。
     */
    public static String simProfileOf(String modelId) {
        if (modelId == null) {
            return null;
        }
        return switch (modelId.trim()) {
            case "carousel-extractor-generic" -> "carousel_extractor";
            case "dispenser-plate-generic" -> "dispenser";
            case "stirrer-generic" -> "stirrer";
            case "gantry-robot-generic" -> "gantry";
            case "plate-stack-generic" -> "plate_stack";
            default -> null;
        };
    }

    /**
     * 已知型号、但手册还没拿到：不进 {@link #TABLE}，只在清单里提醒。
     *
     * @param dbModelAlias 生产库 {@code device.model} 列里的写法（如 {@code "360R"}）。
     *                     用来回答"这个占位型号在产线上有几台正在跑"；
     *                     库里没有对应写法时为 {@code null}（不要瞎猜别名，否则台数会算错）。
     */
    public record Planned(String id, String type, String vendorModel, String missing,
                          String dbModelAlias) { }

    // ---------------------------------------------------------------- 型号表

    private static final Map<String, DeviceModel> TABLE = new LinkedHashMap<>();

    static {
        // ================================================================
        // 离心机 1/…：上海爱翱 ASpin 2K（有手册，V2.0 2023-06-30）
        //   手册：docs/设备/AC离心机接口协议使用说明V2.0F.pdf
        //   协议：XML 字符串经 Cluster.dll 的 SendData() 下发；物理层 CAN 卡或串口
        // ================================================================
        TABLE.put("aspin-2k", new DeviceModel(
                "aspin-2k", "centrifuge",
                "上海爱翱生物科技", "ASpin 2K",
                "xml-dll", "CAN 卡 / 串口（经 Cluster.dll）",
                "docs/设备/AC离心机接口协议使用说明V2.0F.pdf",
                Evidence.MANUAL,
                /* 门数 */ 2,
                /* 动作超时 */ 120_000,
                "① 必须先 PcCreateModules 建模块实例，否则任何指令都发不出去；"
                        + "② 手册示例的等待时间是 120000 + 离心秒数*1000，中控超时若按 30 秒会每次误判超时；"
                        + "③ 手册没写重复命令怎么处理 —— 动作类（Start）超时后不要自动重发；"
                        + "④ 手册没写离心中能否开门（安全互锁），模拟器按\"拒绝\"处理，现场需确认。",
                /* 错误码块 */ 0x41)
                // 建模块实例：模块名是 Cluster，不是 PlateCtri（手册示例如此）
                .method("PcCreateModules", "CreateModules",
                        "<PcRefreshModulesArgs nCreateModulesMode=\"0\"></PcRefreshModulesArgs>",
                        500)
                .method("Init", "Home",
                        "<PlateCentrifugeInitArgs nLength=\"0\" byInitMode=\"0\"></PlateCentrifugeInitArgs>",
                        3_000)
                // 一扇门、三条指令：0=关闭 1=开 A 2=开 B（手册 §3(6)）
                .method("ControlCover", "CloseDoor",
                        "<PlateCentrifugeSetCoverModeArgs nLength=\"0\" byOpenCoverMode=\"0\"></PlateCentrifugeSetCoverModeArgs>",
                        1_500)
                .method("ControlCover", "OpenDoorA",
                        "<PlateCentrifugeSetCoverModeArgs nLength=\"0\" byOpenCoverMode=\"1\"></PlateCentrifugeSetCoverModeArgs>",
                        1_500)
                .method("ControlCover", "OpenDoorB",
                        "<PlateCentrifugeSetCoverModeArgs nLength=\"0\" byOpenCoverMode=\"2\"></PlateCentrifugeSetCoverModeArgs>",
                        1_500)
                // 兼容流程：OpenDoor 默认当 A 门
                .method("ControlCover", "OpenDoor",
                        "<PlateCentrifugeSetCoverModeArgs nLength=\"0\" byOpenCoverMode=\"1\"></PlateCentrifugeSetCoverModeArgs>",
                        1_500)
                .method("Start", "RunProgram",
                        "<PlateCentrifugeSetStateArgs nLength=\"0\" byCentriMode=\"0\" "
                                + "wCentriSec=\"{time}\" wCentriSpeed=\"{speed}\" byCentriRamp=\"{ramp}\">"
                                + "</PlateCentrifugeSetStateArgs>",
                        300_000)
                .method("Stop", "Stop", "", 2_000)
                .method("CurrSpeed", "CurrSpeed", "", 300)
                // 手册源码里 CurrTime 后面带一个空格，是手册的笔误，按无空格实现
                .method("CurrTime", "CurrTime", "", 300)
                // 手册没有查询状态的指令 —— 框架需要，但我们不能假装它有
                .method("GetStatus", "GetStatus", null, 300, false)
                // 手册**没有错误码表**（只有"SendData 失败返回 0，错误在 XML 里"），
                // 所以这台机器只有适配层码，没有厂商码。块 0x41 先占住，
                // 将来拿到错误码表直接 .error("0x...", ...) 就能自动编号。
                .adapterError("EC-05-2001", "SendData 返回 0（设备拒绝或通讯异常）",
                        Disposition.MANUAL, "看 AOutResult 里的 XML 错误；核对 CAN/串口与模块节点配置")
                .adapterError("EC-05-2002", "未创建模块实例就下发了指令",
                        Disposition.ABORT, "流程/适配层先发 PcCreateModules，再发其它指令")
                .adapterError("EC-05-2003", "模块未初始化就下发了动作指令",
                        Disposition.ABORT, "动作前先发 Init（回原点），否则设备状态未知"));

        // ================================================================
        // 封膜机：S100（有手册：二进制 12 字节帧）
        //   手册：docs/设备/S100封膜机通信接口说明 .docx
        //   串口 115200 8N1；报头 0xAA / 应答 0xAC / 错误 0xEE / 报尾 0xFF
        // ================================================================
        TABLE.put("s100", new DeviceModel(
                "s100", "sealer",
                "（手册未标注厂商）", "S100",
                "binary-serial", "串口 115200 8N1",
                "docs/设备/S100封膜机通信接口说明 .docx",
                Evidence.MANUAL,
                /* 门数 */ 0,
                /* 动作超时 */ 0,
                "① 封膜指令 0x04 没有温度/时间参数，参数必须先用 0x00 预先设置；"
                        + "② 托盘不在原点直接封膜会回 0x8000001A —— 流程里封膜前要有 MoveTray(0)；"
                        + "③ 错误帧只有第 5 字节是识别字节，完整码是 0x8000000|第5字节；"
                        + "④ 封膜一次要多久手册没写，模拟器按 12 秒，现场实测后改。",
                /* 错误码块 */ 0x40)
                .method("SetParam", "SetParam", "0x00 + 参数索引 + 参数值（低字节在前）", 500)
                .method("ReadParam", "ReadParam", "0x01 + 参数索引", 300)
                .method("SaveParam", "SaveParam", "0x02（不存则断电丢失）", 1_000)
                .method("PrintParams", "PrintParams", "0x03 以字符串打印全部参数", 1_000)
                .method("Seal", "Seal", "0x04：aa 04 00 … 00 ff", 12_000)
                .method("SealWithPlateType", "SealWithType", "0x04 + 索引 0xA5 + 板型 1–8", 12_000)
                .method("QueryPlateType", "GetPlateType", "0x04 + 索引 0x5A", 300)
                .method("Reset", "Reset", "0x05 复位一次", 5_000)
                .method("ClearFault", "ClearFault", "0x06 清除故障标识", 1_000)
                .method("GetVersion", "GetVersion", "0x07 + 索引 0x08", 300)
                .method("MoveTray", "MoveTray", "0x0C + 托盘 Y 轴绝对位置（0=回原点）", 3_000)
                .method("GetStatus", "GetStatus", null, 300, false)
                // ---- 14 个错误码（手册原文；完整码 = 0x8000000 | 第5字节）----
                // 处置原则：瞬时 → 重试；参数 → 改参数；要人动手 → manual；协议不符 → abort
                .error("0x80000001", "信号量溢出",
                        Disposition.RETRY, "自动重试 3 次，仍失败转人工")
                .error("0x80000002", "错误消息类型",
                        Disposition.ABORT, "协议版本不匹配：核对帧格式与固件版本，不要重试")
                .error("0x80000003", "参数范围有误",
                        Disposition.FIX_PARAM, "检查板型/参数值是否在 1–8 或手册范围内")
                .error("0x80000004", "CAN 通信错误",
                        Disposition.RETRY, "重试 2 次，持续出现检查串口线/CAN 卡")
                .error("0x80000005", "电机堵转",
                        Disposition.MANUAL, "人工排查堵转原因，排除后关机重启设备")
                .error("0x80000006", "未检测到孔板",
                        Disposition.MANUAL, "上板后发 0x06 清除故障，再重跑该节点")
                .error("0x80000007", "刀片脱落",
                        Disposition.MANUAL, "故障维护界面进刀换刀片，完成后重启设备")
                .error("0x80000008", "收到错误指令",
                        Disposition.ABORT, "指令码/索引不被支持：核对协议版本，不要重试")
                .error("0x80000009", "数值错误",
                        Disposition.FIX_PARAM, "检查参数数值（位置/板型）")
                .error("0x8000000A", "存储参数出错",
                        Disposition.MANUAL, "人工确认参数存储，必要时复位后重新存参数")
                .error("0x8000000B", "传感器失效",
                        Disposition.MANUAL, "人工检查传感器（原点/到位信号）")
                .error("0x8000000C", "无纸",
                        Disposition.MANUAL, "更换耗材；新卷装上仍报错时弯折从动轮固定金属片")
                .error("0x8000000E", "事件等待超时",
                        Disposition.RETRY, "自动重试 3 次（固定间隔 1 秒）")
                .error("0x8000001A", "托盘不在原点，无法封膜",
                        Disposition.FIX_PARAM, "先发 0x0C 回原点（流程里封膜前加 MoveTray(0)）"));

        // ================================================================
        // 洗板机 / 分液机：BioTek LHC（Liquid Handling Control）
        //   手册：LHC Runner User Application Interface Specification, 5336208-SP Rev G
        //         LHC SiLA Service Provider, 5330207-SP Rev A（另有一条 SOAP 通道）
        //   我方解读：docs/设备/洗板机-BioTek-LHC.md
        //
        //   协议形态：**不是报文**，是 COM 组件 BTILHCRunner.dll 的方法调用 + 轮询。
        //   所以 args 写的是"参数类型/取值范围"，不是字节。
        //
        //   为什么只建一条型号而不是 EL406/405TS/ELx405/50TS 各一条：
        //   LHC 对所有这些机型是**同一套方法**，差异只有
        //     ① LHC_SetProductName 的字符串（"EL406" / "405 TS/LS" / "ELx405" / "50 TS" …）
        //     ② 少数方法不支持（ELx405 不支持 OverrideValidation/SelfCheck/序列号/WellCount；
        //        50 TS 才有 SetFirstStrip/SetNumberOfStrips；405 TS/LS 才有 Verify Manifold）
        //   ⇒ 机型进参数、进 note，而不是复制 29 条指令四遍（复制四遍必然漂移）。
        // ================================================================
        TABLE.put("biotek-lhc", new DeviceModel(
                "biotek-lhc", "washer",
                "BioTek（Agilent）", "LHC 统一接口：EL406 / 405 TS-LS / ELx405 / MultiFloFX / 50 TS",
                "com-lhc-runner", "COM 组件 BTILHCRunner.dll（随 LHC 安装）；仪器走 USB 或串口",
                "docs/设备/洗板机-BioTek-LHC.md",
                Evidence.MANUAL,
                /* 门数 */ 0,          // 洗板机没有门，是承载器（carrier）进出
                /* 动作超时 */ 600_000, // 一个洗板程序几分钟起步，手册不给上限
                "① 顺序是硬约束：SetProductName → SetCommunications → TestCommunications → "
                        + "LoadProtocol → (ValidateProtocol) → RunProtocol → 轮询 GetProtocolStatus；"
                        + "② **每次 LoadProtocol 都会清掉验证信息**，重复跑同一程序必须先 Validate 或 "
                        + "OverrideValidation(1)，否则每次 Run 都重新验证（慢几秒）；"
                        + "③ RunProtocol 只在返回 eBusy(3) 时才算启动成功，其它值一律没启动；"
                        + "④ 手册明确要求轮询**不要快于 500ms**；"
                        + "⑤ ELx405 不支持 OverrideValidation/SelfCheck/GetProductSerialNumber/"
                        + "ValidateProtocol/GetWellCount；"
                        + "⑥ 50 TS 才有 SetFirstStrip/SetNumberOfStrips（且必须成对使用）；"
                        + "⑦ Verify Manifold 只有带液面检测的 405 TS/LS 有；"
                        + "⑧ 50 TS 协议里若含 Prompt 指令，无人值守时会**停下来等人按键**；"
                        + "⑨ 厂商示例代码 BTILHCRunnerWrapper 里 LHC_AbortProtocol 误写成调 PauseProtocol，"
                        + "照抄样例会「暂停但没中止」；"
                        + "⑩ 真空泵：连续跑多板时用 LeaveVacuumPumpOn(1) 可省掉泵重启的等待。",
                /* 错误码块 */ 0x42)
                // ---- 初始化（顺序不能换）----
                .method("LHC_SetProductName", "SetProductName",
                        "String，必须是 \"EL406\" / \"405 TS/LS\" / \"ELx405\" / \"MultiFlo\" / "
                                + "\"MultiFloFX\" / \"MicroFlo Select\" / \"50 TS\" 之一（注意 50 与 TS 之间有空格）",
                        200)
                .method("LHC_SetCommunications", "SetCommunications",
                        "String，\"COMn\"（n 为串口号）或 \"USB <产品> sn:<序列号>\"", 200)
                .method("LHC_TestCommunications", "TestCommunications", "", 3_000)
                // 自检 = 仪器跑一遍系统测试，等价于"回原点 + 确认能动"，故给 Home 这个别名
                .method("LHC_PerformSelfCheck", "SelfCheck", "", 30_000)
                .method("LHC_PerformSelfCheck", "Home", "（语义近似：自检会让仪器回原点）", 30_000)
                // ---- 协议装载 ----
                .method("LHC_LoadProtocolFromFile", "LoadProtocol",
                        "String，.LHC 协议文件的绝对路径", 2_000)
                .method("LHC_LoadProtocolFromFlash", "LoadProtocolFromFlash",
                        "String，机内程序名（50 TS 的维护程序要加 @@ 前缀）", 2_000)
                // ---- 执行 / 控制 ----
                .method("LHC_RunProtocol", "RunProtocol", "", 60_000)
                // 洗 / 分液 / 预填充在 LHC 里是**同一件事**：跑已装载的协议。
                // 参数都在 .LHC 里，所以这三条别名不接受 volume 之类的入参。
                .method("LHC_RunProtocol", "Wash", "（无参数：洗涤体积/次数/浸泡全在 .LHC 协议里）", 60_000)
                .method("LHC_RunProtocol", "Dispense", "（无参数：分液量在 .LHC 协议里）", 60_000)
                .method("LHC_RunProtocol", "Prime", "（无参数：预填充在 .LHC 协议里）", 60_000)
                .method("LHC_RunProtocol", "Run", "", 60_000)
                .method("LHC_GetProtocolStatus", "GetProtocolStatus", "", 300)
                .method("LHC_GetProtocolStatus", "GetStatus", "", 300)
                .method("LHC_PauseProtocol", "PauseProtocol", "", 1_000)
                .method("LHC_PauseProtocol", "Pause", "", 1_000)
                .method("LHC_ResumeProtocol", "ResumeProtocol", "（必须先 Pause 成功）", 1_000)
                .method("LHC_ResumeProtocol", "Resume", "", 1_000)
                .method("LHC_AbortProtocol", "AbortProtocol", "（必须先 Pause，或运行中直接中止）", 5_000)
                .method("LHC_AbortProtocol", "Abort", "", 5_000)
                .method("LHC_AbortProtocol", "Stop", "", 5_000)
                // ---- 验证开关 ----
                .method("LHC_ValidateProtocol", "ValidateProtocol",
                        "Bool getSettingsFirst：必须传 true（false 只按协议文件里的设置校验，"
                                + "不保证能在本机跑通）", 5_000)
                .method("LHC_OverrideValidation", "OverrideValidation",
                        "Int16：0=每次都验证 1=跳过验证", 300)
                .method("LHC_LeaveVacuumPumpOn", "LeaveVacuumPumpOn",
                        "Int16：0=程序结束关泵 1=保持开泵", 300)
                .method("LHC_SetRunnerThreading", "SetRunnerThreading",
                        "Int16：0=在调用线程里跑 1=Runner 自建线程", 300)
                // ---- 读回 / 诊断 ----
                .method("LHC_GetLastErrorCode", "GetLastErrorCode", "", 300)
                .method("LHC_GetErrorString", "GetErrorString", "Int16 错误码（Runner 或 IS 的）", 300)
                .method("LHC_GetProductName", "GetProductName", "", 300)
                .method("LHC_GetProductSerialNumber", "GetSerialNumber",
                        "（ELx405 不支持）", 300)
                .method("LHC_GetWellCount", "GetWellCount",
                        "（须先 LoadProtocol；ELx405/MicroFlo 不支持；返回 6/12/20/24/48/96/384/1536 或 0）",
                        300)
                .method("LHC_GetOnBoardProtocolCount", "GetOnBoardProtocolCount", "", 500)
                .method("LHC_GetOnBoardProtocolName", "GetOnBoardProtocolName",
                        "Int16 索引（0 起，须先取 Count）", 300)
                // ---- 50 TS 专用 ----
                .method("LHC_SetFirstStrip", "SetFirstStrip",
                        "Byte 1..12（起始列；须与 SetNumberOfStrips 成对使用）", 300)
                .method("LHC_SetNumberOfStrips", "SetNumberOfStrips",
                        "Byte 1..12（起始列 + 列数不得超过板宽）", 300)
                // ---- Verify Manifold（405 TS/LS + 液面检测）----
                .method("LHC_RunVerifyManifoldTest", "RunVerifyManifoldTest", "", 60_000)
                .method("LHC_GetVerifyManifoldRunStatus", "GetVerifyManifoldRunStatus", "", 300)
                .method("LHC_GetVerifyManifoldTestResults", "GetVerifyManifoldResults",
                        "返回 \"PASSED\" / \"FAILED_Step_x\" / \"ERROR xxxx\"", 5_000)
                .method("LHC_CreateVerifyManifoldTestReport", "CreateVerifyManifoldReport",
                        "返回报告文件全路径", 5_000)
                // ---- Runner_ReturnCode（手册 §6.2，十进制 0..6；1=eOK 不是错误，不登记）----
                .errorAs("0", "EC-08-42-00", "eError：LHC Runner 通用失败",
                        Disposition.MANUAL, "看 GetErrorString 的原文；多为前置条件没满足")
                .errorAs("2", "EC-08-42-02", "eRegistration_Failure：LHC 软件未注册/试用期已过",
                        Disposition.MANUAL, "现场注册 LHC（试用期过后功能受限，不是通讯问题）")
                .errorAs("3", "EC-08-42-03", "eInterface_Failure：仪器接口软件失败",
                        Disposition.MANUAL, "查 LHCInterface_*.INF 是否存在、对应型号驱动是否安装")
                .errorAs("4", "EC-08-42-04", "eInvalid_Product_Type：产品名不被识别",
                        Disposition.FIX_PARAM, "核对 SetProductName 的字符串（50 TS 要带空格）")
                .errorAs("5", "EC-08-42-05", "eOpen_File_Error：协议文件打不开",
                        Disposition.FIX_PARAM, "核对 .LHC 路径与文件名（SiLA 模式下还要看 SpConfig 的 ProtocolFilePath）")
                .errorAs("6", "EC-08-42-06", "ePre_Run_Error：运行前校验失败",
                        Disposition.FIX_PARAM, "协议与本机不匹配：重新 ValidateProtocol(true) 看具体原因")
                // ---- 适配层码（时序/部署问题，不是仪器回的）----
                .adapterError("EC-05-2201", "RunStatus=eNotReady(2)：新步骤没能启动",
                        Disposition.ABORT, "Run 已判定失败，不要再轮询；查 GetLastErrorCode")
                .adapterError("EC-05-2202", "RunStatus=eError(4)：协议运行中报错",
                        Disposition.MANUAL, "调 GetLastErrorCode → GetErrorString 取原文，按码处置")
                .adapterError("EC-05-2203", "RunStatus 是 eUninitialized(0)/eIncomplete(6)：协议未正常结束",
                        Disposition.ABORT, "手册标注这两个值「不应出现」；先 Abort 再 Reset 通道，不要重试")
                .adapterError("EC-05-2204", "没做初始化就下了命令（缺 SetProductName/SetCommunications/TestCommunications）",
                        Disposition.ABORT, "按运行场景先走完初始化序列")
                .adapterError("EC-05-2205", "没有装载协议就 Run/Wash/Dispense",
                        Disposition.ABORT, "先 LoadProtocol（或 LoadProtocolFromFlash）")
                .adapterError("EC-05-2206", "装载后未验证就 Run，且未设 OverrideValidation(1)",
                        Disposition.FIX_PARAM, "先 ValidateProtocol(true)，或显式 OverrideValidation(1)")
                .adapterError("EC-05-2207", "50 TS 的条（strip）范围越界",
                        Disposition.FIX_PARAM, "firstStrip + numberOfStrips - 1 不得超过板宽（96 孔=12 列）")
                .adapterError("EC-05-2208", "找不到 LHC Runner COM 组件（BTILHCRunner.dll 未注册）",
                        Disposition.MANUAL, "在装 LHC 的机器上跑 sidecar，或用 regsvr32 注册；核对 LHC 版本 ≥ 2.12")
                .adapterError("EC-05-2209", "LHC_Installation_Folder.inf 缺失或指向错误目录",
                        Disposition.MANUAL,
                        "该文件必须与 BTILHCRunner.dll 同目录，且内容是 LHC 的安装路径")
                .adapterError("EC-05-2210", "上一条协议还在运行，拒绝了新命令",
                        Disposition.RETRY, "等 GetProtocolStatus 变成 eReady 后重试；或用 Abort 中止"));

        // ================================================================
        // 酶标仪：BioTek Gen5（OLE Automation 宿主）
        //   手册：Gen5 OLE Automation Programmer's Guide（117 页，含 BTIStatusCodes.h）
        //   我方解读：docs/设备/酶标仪-BioTek-Gen5.md
        //
        //   协议形态：COM/OLE Automation，**本机进程内**。Gen5 自己独占仪器的
        //   USB/串口 ⇒ LabOS 不与仪器直接通讯，只与 Gen5 对话。
        //   与 LHC 的关键差别：Gen5 是"实验（Experiment）→ 板（Plate）→ 读数集"的对象模型，
        //   不是"下发一条指令等一个回包"；一次读板是 StartRead + 轮询 + 取数据集三步。
        // ================================================================
        TABLE.put("biotek-gen5", new DeviceModel(
                "biotek-gen5", "reader",
                "BioTek（Agilent）", "Gen5（OLE Automation；适用 Synergy / Epoch / Cytation / ELx808 等）",
                "com-ole", "Gen5 OLE Automation（本机 COM）；仪器由 Gen5 独占，走 USB/串口",
                "docs/设备/酶标仪-BioTek-Gen5.md",
                Evidence.MANUAL,
                /* 门数 */ 1,
                /* 动作超时 */ 600_000, // 动力学读板可达数十分钟
                "① 必须先 Configure*Reader + TestReaderCommunication，否则后面每条都失败；"
                        + "② 一个 Gen5 实例只能有一个 Experiment，Experiment 只能有一个 Plates；"
                        + "③ 释放顺序是硬约束：Plate → Plates → Experiment（提前关会抛异常）；"
                        + "④ **读板期间不要做**：删板、导出、打印报告、CarrierIn/Out、Configure*Reader；"
                        + "⑤ 读板是异步的：StartRead 立刻返回 PlateReadMonitor，要轮询 ReadInProgress，"
                        + "再看 Plate.ReadStatus（成功与否看它，不是看有没有抛异常）；"
                        + "⑥ 原始数据要先置 Application.DataExportEnabled=true，否则 GetRawData 拿不到；"
                        + "⑦ 路径分两种存储模式：数据库模式写 \"DB:\\\\Protocols\\\\x.prt\"，"
                        + "文件模式写 \"C:\\\\...\" —— 用错模式必失败；"
                        + "⑧ Gen5 Secure/Clinical 若开启 OLE 登录，NewExperiment/OpenExperiment 会弹登录框，"
                        + "无人值守场景会一直卡住；"
                        + "⑨ 报错有两层：方法抛异常/返回负数码（BTI_*），失败码 -601 表示「仪器自己报错」，"
                        + "要去 PlateReadMonitor.GetReaderError 取仪器码。",
                /* 错误码块 */ 0x43)
                // ---- 配置与自检 ----
                .method("GetSupportedReaders", "GetSupportedReaders", "", 300)
                .method("ConfigureSerialReader", "ConfigureSerialReader",
                        "long ReaderType（见文档的机型表）, long ComPort, long BaudRate", 1_000)
                .method("ConfigureUSBReader", "ConfigureUSBReader",
                        "long ReaderType（-1=只接了一台时自动选）, string 序列号（可空）", 1_000)
                .method("ConfigureOleReader", "ConfigureOleReader", "long ReaderType", 1_000)
                .method("TestReaderCommunication", "TestReaderCommunication", "", 5_000)
                .method("TestReaderCommunication", "Connect", "", 5_000)
                .method("GetReaderStatus", "GetReaderStatus", "", 300)
                .method("GetReaderStatus", "GetStatus", "", 300)
                .method("GetLastReaderError", "GetLastReaderError", "", 300)
                .method("GetReaderCharacteristics", "GetReaderCharacteristics",
                        "int 特征 id（0 吸收支持 1 最小波长 2 最大波长 5 是否可温控 10 序列号 11 机型名…）, "
                                + "int 索引, out variant 值", 300)
                .method("StartSystemTest", "StartSystemTest", "", 120_000)
                .method("StartSystemTest", "SelfCheck", "", 120_000)
                // ---- 实验 / 板 ----
                .method("NewExperiment", "NewExperiment", "string 协议路径（按存储模式写 DB:\\ 或 C:\\）", 3_000)
                .method("NewExperimentEx", "NewExperimentEx",
                        "enum 定义类型, string 协议定义 XML", 3_000)
                .method("OpenExperiment", "OpenExperiment", "string 实验文件路径", 3_000)
                .method("Close", "CloseExperiment", "（先释放 Plate/Plates）", 3_000)
                .method("Save", "SaveExperiment", "（实验须已存过盘）", 3_000)
                .method("SaveAs", "SaveExperimentAs", "string 目标路径", 3_000)
                .method("GetPlateTypeNames", "GetPlateTypeNames", "short 选择标志", 500)
                .method("SetPlateLayout", "SetPlateLayout", "string BTIPlateLayout XML", 1_000)
                .method("GetPlateLayout", "GetPlateLayout", "string ProtocolID", 1_000)
                // ---- 进出板（= 承载器）----
                .method("CarrierIn", "CarrierIn", "", 6_000)
                .method("CarrierIn", "Load", "", 6_000)
                .method("CarrierInEx", "CarrierInEx",
                        "string 板型名（空=承载器上没有板）, bool 是否带盖", 6_000)
                .method("CarrierOut", "CarrierOut", "", 6_000)
                .method("CarrierOut", "Unload", "", 6_000)
                // 面板类动作：只对"人看着屏幕"有意义，自动化里基本不用
                .method("RunReaderControlCommand", "RunReaderControl",
                        "long nCommand：0 面板/2 门与承载器/4 振荡/8 分液/16 预热/32 灯/64 预填充/"
                                + "512 承载器进/1024 承载器出/2048 清洁物镜", 3_000)
                // ---- 读板 ----
                .method("StartRead", "Read", "（返回 PlateReadMonitor）", 30_000)
                .method("StartRead", "ReadPlate", "", 30_000)
                .method("StartReadEx", "ReadEx", "bool 是否 180° 旋转", 30_000)
                .method("StartSimulatedRead", "SimulatedRead", "（不占用仪器，用于联调）", 30_000)
                .method("AbortRead", "AbortRead", "", 3_000)
                .method("AbortRead", "Stop", "", 3_000)
                .method("KeepPlateInAfterRead", "KeepPlateInAfterRead", "（读完不吐板）", 300)
                .method("SetPartialPlate", "SetPartialPlate",
                        "string BTIPartialPlate XML（协议须允许运行时选孔）", 1_000)
                .method("ImportSampleIDs", "ImportSampleIDs", "string 文本文件路径", 2_000)
                // ---- 取结果 ----
                .method("GetDataSetNames", "GetDataSetNames", "string ProtocolID", 500)
                .method("GetDataSetInfo", "GetDataSetInfo", "", 500)
                .method("GetDataSetResults", "GetResult",
                        "string ProtocolID, string 数据集名, string 索引（\"\"=全部）", 1_000)
                .method("GetRawData", "GetRawData",
                        "（须先置 DataExportEnabled=true；读板过程中即可取增量）", 1_000)
                .method("FileExportEx", "ExportFile", "string 导出器名, string 目标路径（空=按协议设置）", 5_000)
                .method("PrintReportEx", "PrintReport", "string 报表名, bool 是否弹打印对话框", 5_000)
                // ---- 温控 ----
                .method("GetCurrentTemperature", "GetTemperature", "", 300)
                .method("SetTemperatureSetPoint", "SetTemperature",
                        "bool 是否开启孵育, double 目标温度, double 梯度", 3_000)
                .method("GetTemperatureSetPoint", "GetTemperatureSetPoint", "", 300)
                // ---- Monitor 层（异步读板的进度与错误）----
                .method("PlateReadMonitor.ReadInProgress", "GetReadProgress", "", 200)
                .method("PlateReadMonitor.GetErrorCode", "GetReadErrorCode", "long 索引", 200)
                .method("PlateReadMonitor.GetErrorMessage", "GetReadErrorMessage", "long 索引", 200)
                .method("PlateReadMonitor.GetReaderError", "GetReaderError",
                        "long 索引（仅当 GetErrorCode 返回 -601 时有意义）", 200)
                // ---- BTIStatusCodes.h（负数，按段分；只登记"现场会遇到"的那些）----
                .errorAs("-1", "EC-08-43-001", "BTI_FAIL：通用失败",
                        Disposition.MANUAL, "看异常文本；多为前置条件没满足")
                .errorAs("-3", "EC-08-43-003", "BTI_USER_CANCELLED_OPERATION：用户取消",
                        Disposition.MANUAL, "有人在 Gen5 界面上点了取消；确认后重跑")
                .errorAs("-5", "EC-08-43-005", "BTI_INVALID_REQUEST：请求不合法",
                        Disposition.ABORT, "调用序列/参数类型不对：核对文档，不要重试")
                .errorAs("-13", "EC-08-43-013", "BTI_INSUFFICIENT_PERMISSIONS：权限不足",
                        Disposition.MANUAL, "Gen5 Secure/Clinical 的账号权限或未登录")
                .errorAs("-14", "EC-08-43-014", "BTI_DATABASE_ERROR：Gen5 数据库错误",
                        Disposition.MANUAL, "检查 Gen5 的 SharedDB 连通性；重启 Gen5 后重试")
                .errorAs("-100", "EC-08-43-100", "BTI_FILE_DOES_NOT_EXIST：文件不存在",
                        Disposition.FIX_PARAM, "核对协议/实验路径（注意 DB:\\ 与 C:\\ 两种模式）")
                .errorAs("-101", "EC-08-43-101", "BTI_UNABLE_TO_OPEN_FILE：文件打不开",
                        Disposition.FIX_PARAM, "文件被占用或权限不足")
                .errorAs("-103", "EC-08-43-103", "BTI_FILE_READ_ONLY：文件只读",
                        Disposition.FIX_PARAM, "换路径 SaveAs，或由人解除只读")
                .errorAs("-105", "EC-08-43-105", "BTI_FILE_LOCKED：文件被锁",
                        Disposition.RETRY, "另一个 Gen5 实例持有；等几秒重试")
                .errorAs("-106", "EC-08-43-106", "BTI_FILE_SHARING_VIOLOATION：共享冲突",
                        Disposition.RETRY, "同上；持续出现则关闭多余 Gen5 实例")
                .errorAs("-200", "EC-08-43-200", "BTI_INVALID_RECORD_FORMAT：记录格式不合法",
                        Disposition.FIX_PARAM, "传进去的 XML 与 schema 不符")
                .errorAs("-301", "EC-08-43-301", "BTI_FIELD_BELOW_MIN_RANGE：字段低于下限",
                        Disposition.FIX_PARAM, "参数越界（波长/体积/温度）")
                .errorAs("-302", "EC-08-43-302", "BTI_FIELD_ABOVE_MAX_RANGE：字段超上限",
                        Disposition.FIX_PARAM, "参数越界")
                .errorAs("-505", "EC-08-43-505", "BTI_SERIAL_WRITE_ERROR：串口写失败",
                        Disposition.RETRY, "重试 2 次；持续出现查线缆/USB 转换器")
                .errorAs("-506", "EC-08-43-506", "BTI_SERIAL_READ_ERROR：串口读超时",
                        Disposition.RETRY, "重试 2 次；确认仪器没被别的软件占用")
                .errorAs("-507", "EC-08-43-507", "BTI_CHECKSUM_ERROR：校验和错",
                        Disposition.RETRY, "线路干扰；重试后仍错查屏蔽/接地")
                .errorAs("-513", "EC-08-43-513", "BTI_PORT_NOT_OPEN：端口未打开",
                        Disposition.FIX_PARAM, "核对串口号与波特率")
                .errorAs("-522", "EC-08-43-522", "BTI_COM_PORT_ALREADY_IN_USE：串口被占用",
                        Disposition.MANUAL, "先关掉 Gen5 之外的串口占用程序（含上次残留的 Gen5）")
                .errorAs("-526", "EC-08-43-526", "BTI_CREATE_FILE_NO_PORT_ERROR：端口不存在",
                        Disposition.FIX_PARAM, "核对通讯设置里的串口号（USB 机型改用 ConfigureUSBReader）")
                .errorAs("-527", "EC-08-43-527", "BTI_CREATE_FILE_PORT_IN_USE：端口被别的用户锁住",
                        Disposition.MANUAL, "另一实例已占用该端口；同一端口只能有一个 Application")
                .errorAs("-529", "EC-08-43-529", "BTI_READER_NOT_CONNECTED：仪器未连接",
                        Disposition.RETRY, "查电源与线缆；恢复后 TestReaderCommunication 再继续")
                .errorAs("-531", "EC-08-43-531", "BTI_USB_DEVICE_ALREADY_IN_USE：USB 设备被占用",
                        Disposition.MANUAL, "关掉其它 Gen5/读板软件")
                .errorAs("-600", "EC-08-43-600", "BTI_UNKNOWN_READER：未知机型",
                        Disposition.FIX_PARAM, "ReaderType 不在支持列表里")
                .errorAs("-601", "EC-08-43-601", "BTI_READER_STATUS_ERROR：仪器报错",
                        Disposition.MANUAL,
                        "必须再取 PlateReadMonitor.GetReaderError 拿仪器原始码，按那个码处置")
                .errorAs("-602", "EC-08-43-602", "BTI_READER_NO_ACKNOWLEDGE：仪器不应答",
                        Disposition.RETRY, "重试 2 次；持续出现查线缆/固件")
                .errorAs("-606", "EC-08-43-606", "BTI_READ_TIMEOUT_ERROR：读板超时",
                        Disposition.RETRY,
                        "先 AbortRead；确认不是动力学读板（长读板要放大 sidecar 超时）")
                .errorAs("-608", "EC-08-43-608", "BTI_READER_INV_WAVELENGTH：波长不被支持",
                        Disposition.FIX_PARAM, "用 GetReaderCharacteristics(1/2) 取本机实际波长范围")
                // ---- 适配层码 ----
                .adapterError("EC-05-2301", "没配置读板机就调了读板/进出板",
                        Disposition.ABORT, "先 ConfigureSerialReader/ConfigureUSBReader + TestReaderCommunication")
                .adapterError("EC-05-2302", "没有打开的 Experiment 就操作 Plate",
                        Disposition.ABORT, "先 NewExperiment/OpenExperiment")
                .adapterError("EC-05-2303", "读板进行中还调了会冲突的方法（删板/导出/CarrierIn/配置）",
                        Disposition.FIX_PARAM, "等 ReadInProgress=false 再调")
                .adapterError("EC-05-2304", "找不到 Gen5 的 COM 组件（Gen5 未安装或未启动过一次）",
                        Disposition.MANUAL,
                        "装 Gen5 的机器上跑 sidecar；先手工启动一次 Gen5 完成初始化与注册")
                .adapterError("EC-05-2305", "Gen5 弹出了交互对话框（登录/选板型/文件选择），无人应答",
                        Disposition.MANUAL,
                        "Gen5 Secure/Clinical 请关闭 OLE 登录要求；或用 SetClientWindow 指定父窗口")
                .adapterError("EC-05-2306", "DataExportEnabled 未开启，取不到原始数据",
                        Disposition.FIX_PARAM, "读板前置 Application.DataExportEnabled = true")
                .adapterError("EC-05-2307", "承载器上没有板就启动读板",
                        Disposition.FIX_PARAM, "读板前先 CarrierIn（或 CarrierInEx 带板型）"));
    }

    // ---------------------------------------------------------------- 待适配清单

    /**
     * 已知型号、**手册还没拿到**的离心机。
     *
     * <p>为什么要列出来：客户问"这台能接吗"时，答案不是"能"或"不能"，
     * 而是"型号在清单里，缺 X"。补的时候只需在 {@link #TABLE} 加一条。
     */
    private static final List<Planned> PLANNED = List.of(
            // —— 下面两条是**从生产库里捞出来的真实设备**（labdb 库 device 表），
            //    它们现在就在产线上跑，但我们还没有它们的手册。
            //    注意：不要把它们硬套到 aspin-2k / s100 上——错误码会翻译成另一台机器的含义。
            //    dbModelAlias 填库里的 device.model 写法，用来统计"产线上有几台在跑"。
            //
            // ⚠ 2026-11-15：按厂商公开资料核过一轮，**规格**（转速/温控/时长）已补进
            //    `missing` 里；**通信接口**除 PlateLoc 外仍未找到公开文档。
            new Planned("hettich-rotanta-460r", "centrifuge", "Hettich Rotanta 460R（库里 model=360R）",
                    "缺通信接口说明；库里 1 台在用。拿到手册后按 aspin-2k 的结构补一条即可。"
                            + " 公开规格（2026-11-15 核）：50–15000 rpm / 24400 g，-20…+40 °C 主动制冷，"
                            + "4×1000 mL 或 12 块 SBS 板（转子 5622，≤6200 rpm/6446 g），"
                            + "计时 1 s–99h59m59s；同门 Robotic 升/减速 52 s / 38 s（手册）。"
                            + " ⚠ 库里的 '360R' 在 Hettich 现行目录中**查无此型号**（只有 460/460 R/460 RF）"
                            + "⇒ 需与现场确认是不是 ROTOFIX 46 / ROTINA 380 R 之类的另一个型号",
                    "360R"),
            new Planned("agilent-placeloc", "sealer", "Agilent PlateLoc（库里 model=PlateLoc）",
                    "库里 2 台在用；半自动热封，和 S100 协议不同源。"
                            + " ⚠ **通信接口已公开**（2026-11-15 核，手册 G5402-90014A）：RS-232 + ActiveX + VWorks"
                            + " ⇒ 六者里它最接近'能直接按手册实现'。"
                            + " 公开规格：每板约 8 s（手册），封膜温度 30–200 °C（0.5 步进），"
                            + "封膜驻留 0.5–12.0 s（0.5 步进），暖机 5 min、冷却 90 min；"
                            + "ANSI/SBS 96/384 孔、板高 ≤51 mm",
                    "PlateLoc"),
            new Planned("eppendorf-5810r", "centrifuge", "Eppendorf 5810 / 5810 R",
                    "缺通信接口说明（官方自动化接口在 epMotion/Bravo；5810 本身是键盘机型）。"
                            + " 公开规格（2026-11-15 核，厂商页）：200–14000 rpm（10 rpm 步进）、"
                            + "5810 R 温控 -9…+40 °C、计时 1–99 min + 连续 + 短转、10 加速/10 减速档；"
                            + "板转子 3486 g、4×750 mL 或 16 块 96 孔 MTP；384 孔**未找到**确认",
                    null),
            new Planned("thermo-multifuge-x1r", "centrifuge", "Thermo Multifuge X1/X3",
                    "缺通信接口说明。公开规格（2026-11-15 核）：定角最高 15200 rpm / 25830 g，"
                            + "冷冻型 -10…+40 °C，计时 9h59min + HOLD，9 加速/10 减速档，"
                            + "转子更换 3 s（Auto-Lock III）；X1 4×400 mL / X3 4×750 或 1000 mL；"
                            + "384 孔**未找到**确认",
                    null),
            new Planned("beckman-allegra-x15r", "centrifuge", "Beckman Coulter Allegra X-15R",
                    "缺通信接口说明。公开规格（2026-11-15 核）：摆篮 4750 rpm/5250 g、定角 10200 rpm/11400 g，"
                            + "冷冻 -10…+40 °C（1 °C 步进）、控温 ±2 °C、环境→4 °C <4 min、"
                            + "10 加速/11 减速档、计时 ≤99h59min；3 L 全流程约 14 min；"
                            + "微孔板容量官方口径**未确认**",
                    null),
            new Planned("domestic-centrifuge-generic", "centrifuge", "国产全自动离心机（通用占位）",
                    "缺具体厂商型号与通信协议；先用类型画像跑流程，接真机前必须补手册。"
                            + " 2026-11-15 核到两个真实可参考的国产型号："
                            + "① 湘仪 HT185（18,500 rpm/23900 g，角转子 6×50 mL 升速 26 s、24×1.5 mL 8 s，"
                            + "0 档自由停车 ≥600 s）；"
                            + "② 安亭（飞鸽牌）DL-5C / LXJ 系列自动脱盖（3600–5000 rpm，0–99 min 定时，"
                            + "TDL-5000cR 冷冻 -20…+30 °C 带 192 孔板转子）。"
                            + " 两家的**通讯接口均未找到公开文档**",
                    null),

            // —— 以下五条是「高通量核酸提取工作站」这条线要的设备类型（2026-11-15 核过公开资料）。
            //    **公开规格都有，通信协议都没有** ⇒ 只能用类型画像跑流程骨架，接真机前必须补手册。
            //    对应的**模拟画像**已落在 sim/DeviceProfile（carousel_extractor / stirrer / gantry /
            //    plate_stack），参数与时长按这里的公开规格填的。
            new Planned("carousel-extractor-generic", "extractor",
                    "转盘式磁珠核酸提取仪（96 孔，如 Thermo KingFisher Flex / 奥盛 Auto-Pure 96 / "
                            + "博一 BYHS-96ZP / 爱翱 PureSTAR 96）",
                    "缺通信接口说明与命令集（厂商未公开机器↔机械臂的握手协议）。"
                            + " 公开规格（2026-11-15 核）：**8 个板位、其中 2 个可加热**；"
                            + "处理体积 20–1000 µL；室温–120 ℃（KingFisher Flex 是室温+5–115 ℃）；"
                            + "整轮 15–60 min（国产 15–35 min，KingFisher <20 min）；"
                            + "磁珠回收率 >95%（KingFisher）~>99%（国产）；磁棒场强 >4800 Gs；"
                            + "分步时长参考 KingFisher 协议 PDF（结合孵育 8 min / 洗涤 3 min / 风干 10 min）。"
                            + " ⚠ '仪器主动请求换板'的**状态字串**未找到任何厂商公开文档",
                    null),
            new Planned("dispenser-plate-generic", "dispenser",
                    "96 孔板自动分液器（如 杭州奥铂 KX100-1 / 普睿博 Stab H1 / CHFY-600）",
                    "缺通信接口说明。公开规格（2026-11-15 核）：**96 孔整板分液 6–15 s**"
                            + "（KX100-1 8 s、CHFY-600 100 µL 仅 6 s、Stab H1 <30 s）；"
                            + "量程 5–200 µL（KX100）/ 20–9000 µL（CHFY-600）/ 最小 10 µL（Stab H1）；"
                            + "精度 <3 %@>50 µL ~ ≤±1 %；1–6 个板位；KX100-1 有 RS485/USB/CAN",
                    null),
            new Planned("stirrer-generic", "stirrer",
                    "搅拌单元（顶置搅拌器 / 磁力搅拌器 / 板级磁力搅拌，如 IKA RW 20、IKA RO 5-15、"
                            + "2mag MIXdrive 96 MTP）",
                    "缺通信接口说明。公开规格（2026-11-15 核）："
                            + "顶置（IKA RW 20）240–2000 rpm、20 L、扭矩 150 N·cm；"
                            + "多位磁力（IKA RO 5/10/15）0–1200 rpm（10 rpm 步进）、位距 90 mm；"
                            + "**板级磁力（2mag MIXdrive 96 MTP）96 个搅拌点、每点 ≤0.5 mL、10 W**；"
                            + "常规混匀保持 30–120 s（估算，厂商只给转速不给时长）。"
                            + " ⚠ 板级搅拌的实际 rpm 由外置控制器设，公开页未给",
                    null),
            new Planned("gantry-robot-generic", "robot",
                    "轨道式四轴机械臂（如 Brooks PreciseFlex 400 加直线导轨 / 埃斯顿 ER3-400-SR / "
                            + "建南和 UT-AFW4010NM）",
                    "缺通信接口说明与点位坐标（坐标由现场标定，不在公开资料里）。"
                            + " 公开规格（2026-11-15 核）：4 轴（J1/J2 水平 + J3 升降 + J4 转）；"
                            + "负载 0.5–3 kg；重复定位 ±0.01–0.09 mm；速度 500–2000 mm/s；"
                            + "夹持 2–23 N 或真空 −80 kPa；搬一块板 1–3 s + 扫码 0.3–1 s（估算）；"
                            + "末端工具可换（抓手 / 移液头 / 扫码枪）",
                    null),
            new Planned("plate-stack-generic", "plate_hotel",
                    "自动化耗材栈 / 板栈（如 上海汇像 XHarbor12 / Thermo Cytomat 5 C450）",
                    "缺通信接口说明与**槽位寻址**协议（按层/按叠板器编号怎么编，未找到公开文档）。"
                            + " 公开规格（2026-11-15 核）：XHarbor12 按板高分 24/20/15/10/8/4 层 ⇒ "
                            + "288/240/180/120/96/48 板，**平均取板 10 s**，自动扫码；"
                            + "Cytomat 5 C450 每叠板器 50 块深孔板 / 105 块 96 孔，**取板 <10 s**，"
                            + "4–50 ℃，内置条码阅读器",
                    null));

    // ---------------------------------------------------------------- 查询

    /** 按型号 id 查；查不到返回 null。 */
    public static DeviceModel of(String id) {
        return id == null || id.isBlank() ? null : TABLE.get(id);
    }

    /** 已适配的型号（按登记顺序）。 */
    public static Collection<DeviceModel> all() {
        return Collections.unmodifiableCollection(TABLE.values());
    }

    public static List<DeviceModel> byType(String type) {
        List<DeviceModel> out = new ArrayList<>();
        for (DeviceModel m : TABLE.values()) {
            if (m.type.equals(type)) {
                out.add(m);
            }
        }
        return out;
    }

    public static List<Planned> planned() {
        return PLANNED;
    }

    /**
     * 按 id 找 PLANNED 型号（已知型号、手册未到）。
     *
     * <p>为什么需要它：{@link #of(String)} 只认 MANUAL 型号表 ⇒ PLANNED 型号
     * （提取仪/分液器/搅拌/轨道臂/耗材栈）登记不了 ⇒ 那 5 类设备一台都建不出来。
     */
    public static Planned plannedOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Planned p : PLANNED) {
            if (p.id().equals(id.trim())) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 元数据 JSON

    /**
     * 型号库适配状态（纯逻辑，不碰库）：{@code GET /api/meta/device-models} 的数据基础。
     *
     * <p>回答现场/评审最关心的两个问题：
     * <ul>
     *   <li><b>哪些型号能对接真机</b>：只有 {@link Evidence#MANUAL} 且手册逐条可核的；
     *       每个型号给出指令数、其中"手册有依据"与"我们补的"各多少、错误码数、错误码块；</li>
     *   <li><b>哪些只有占位</b>：{@link #planned()} 清单 + 缺什么 + 生产库 {@code device.model} 写法。</li>
     * </ul>
     *
     * <p>部署台数（产线上有几台在跑）必须查库，由 API 层用 {@link #libraryJson(Map)} 叠加；
     * 本方法保持纯函数，供自测 / 离线导出复用。
     */
    public static String libraryJson() {
        return libraryJson(null);
    }

    /**
     * 同上，并把 {@code deployedByModel}（device.model → 台数）叠加到 planned 条目上：
     * 每条 planned 增加 {@code deployedCount}，totals 增加 {@code plannedDeployed}。
     *
     * @param deployedByModel 产线 device 表的 model→台数；{@code null} = 不叠加（纯逻辑口径）
     */
    public static String libraryJson(Map<String, Integer> deployedByModel) {
        List<String> manual = new ArrayList<>();
        for (DeviceModel m : TABLE.values()) {
            List<DeviceModel.Method> methods = m.methods();
            int documented = 0;
            for (DeviceModel.Method mm : methods) {
                if (mm.documented()) {
                    documented++;
                }
            }
            manual.add(Jsons.obj(
                    "id", m.id,
                    "type", m.type,
                    "vendor", m.vendor,
                    "model", m.model,
                    "protocol", m.protocol,
                    "transport", m.transport,
                    "manual", m.manual,
                    "evidence", m.evidence.name(),
                    "doorCount", m.doorCount,
                    "actionTimeoutMs", m.actionTimeoutMs,
                    "methodCount", methods.size(),
                    "documentedMethodCount", documented,
                    "unverifiedMethodCount", methods.size() - documented,
                    "errorCount", m.errorTable().size(),
                    "errorBlock", m.errorBlock,
                    "note", m.note));
        }
        List<String> planned = new ArrayList<>();
        int plannedDeployed = 0;
        for (Planned p : PLANNED) {
            String alias = p.dbModelAlias();
            int deployed = 0;
            if (alias != null && deployedByModel != null) {
                deployed = deployedByModel.getOrDefault(alias, 0);
            }
            plannedDeployed += deployed;
            if (deployedByModel == null) {
                planned.add(Jsons.obj(
                        "id", p.id(),
                        "type", p.type(),
                        "vendorModel", p.vendorModel(),
                        "missing", p.missing(),
                        "dbModelAlias", alias));
            } else {
                planned.add(Jsons.obj(
                        "id", p.id(),
                        "type", p.type(),
                        "vendorModel", p.vendorModel(),
                        "missing", p.missing(),
                        "dbModelAlias", alias,
                        "deployedCount", deployed));
            }
        }
        String totals = deployedByModel == null
                ? Jsons.obj("manual", TABLE.size(), "planned", PLANNED.size())
                : Jsons.obj("manual", TABLE.size(), "planned", PLANNED.size(),
                        "plannedDeployed", plannedDeployed);
        return Jsons.obj(
                "totals", new Jsons.Raw(totals),
                "manual", new Jsons.Raw(Jsons.arr(manual)),
                "planned", new Jsons.Raw(Jsons.arr(planned)));
    }

    public static java.util.Set<String> knownIds() {
        return TABLE.keySet();
    }
}
