package com.labos.kernel.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备类型画像：一类设备"该有哪些方法、参数范围是多少、默认要跑多久"。
 *
 * <p><b>为什么要这个</b>：改动前的模拟器只有 4 种类型、方法集写死在 switch 里、
 * 参数完全不校验、延迟固定 150ms。结果是中控把离心机配成 20000 rpm、
 * 把酶标仪波长配成 2000 nm，模拟器照样回 OK —— <b>错到现场才发现</b>。
 *
 * <p>这里按 {@code docs/模拟器设备库.md} 整理的厂商参数做边界校验，
 * 让"配错了"在模拟阶段就暴露。
 *
 * <p><b>参数值来源</b>：厂商公开规格（Eppendorf 5810、SpectraMax 190/ABS、
 * Tecan Freedom EVO、Biomek i3/i5/i7、BioTek 405 等）。
 * <b>仅用于边界校验</b>，不是采购依据。
 */
public final class DeviceProfile {

    /** 类型名（命令行 --profile 的值）。 */
    public final String name;
    /** 没显式给 --delay 时的默认耗时。真实设备的量级差异很大。 */
    public final long defaultDelayMs;
    public final Set<String> methods;

    /** 离心机转速范围（rpm）。 */
    public final int minRpm;
    public final int maxRpm;
    /** 移液量程（µL）。 */
    public final double minVolume;
    public final double maxVolume;
    /** 酶标仪波长范围（nm）。 */
    public final int minWavelength;
    public final int maxWavelength;
    /** 温控范围（°C）。 */
    public final double minTemp;
    public final double maxTemp;
    /** 板位/层位数量；0 表示不限制。 */
    public final int slots;

    /** 是否"有门"（离心机/孵育箱/酶标仪/板库）。 */
    public final boolean hasDoor;
    /** 是否"持板"型设备（机械臂：Grip/Release）。 */
    public final boolean holdsPlate;

    /**
     * **步骤时长表**：方法 → 模拟耗时（毫秒）。
     *
     * <p><b>为什么需要</b>：整类设备共用一个 {@code defaultDelayMs} 是不够的 ——
     * 转盘式提取仪的「转盘切换」2 秒、「磁分离」1 分钟、「孵育」5 分钟，差三个数量级。
     * 全用同一个值 ⇒ 流程要么跑得不像现场，要么直接超时。
     *
     * <p>查表优先于通用规则（见 {@link #methodDelayMs}）。
     */
    private Map<String, Long> stepTimings = Map.of();

    /**
     * **结果字段表**：方法 → 回包 {@code data} 里要带哪些字段。
     *
     * <p><b>为什么需要</b>：模拟器以前对绝大多数方法回 {@code data:{}} ——
     * 流程里"读设备状态""读板号""读剩余板数"这些**结果**拿不到，
     * 于是流程要么不敢用设备的输出，要么只能另开一条路去查库。
     *
     * <p>字段名取自模拟器认识的**约定键**（见 {@link #resultValue}），
     * 模拟器按约定填一个**合理的模拟值**（不是实测值）。
     */
    private Map<String, List<String>> resultFields = Map.of();

    /**
     * **时长由参数决定**的规则：方法 → （参数名, 最小秒, 最大秒）。
     *
     * <p><b>为什么需要</b>：离心 30 秒和离心 30 分钟是同一台设备的同一���方法，
     * 差别只在**参数**。模拟器若用一个固定耗时 ⇒ 流程要么跑得不像现场，要么直接超时。
     * ⇒ 这类设备的模拟耗时应当**跟着请求参数走**（再按机型允许的范围夹一下）。
     *
     * <p>例：离心机 {@code RunProgram} 的 {@code time}、搅拌器 {@code Start} 的 {@code duration}、
     * 提取仪 {@code StartProcess} 的 {@code duration}。
     */
    private Map<String, DurationSpec> durationParams = Map.of();

    /** 时长参数的规则：参数名 + 允许范围（秒）。 */
    public record DurationSpec(String param, int minSec, int maxSec) {
    }

    private DeviceProfile(String name, long defaultDelayMs, Set<String> methods,
                          int minRpm, int maxRpm,
                          double minVolume, double maxVolume,
                          int minWavelength, int maxWavelength,
                          double minTemp, double maxTemp, int slots,
                          boolean hasDoor, boolean holdsPlate) {
        this.name = name;
        this.defaultDelayMs = defaultDelayMs;
        this.methods = methods;
        this.minRpm = minRpm;
        this.maxRpm = maxRpm;
        this.minVolume = minVolume;
        this.maxVolume = maxVolume;
        this.minWavelength = minWavelength;
        this.maxWavelength = maxWavelength;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.slots = slots;
        this.hasDoor = hasDoor;
        this.holdsPlate = holdsPlate;
    }

    private static final Set<String> HOME = Set.of("Home", "GetStatus", "Stop");

    // ---------------------------------------------------------------- 画像表

    private static final Map<String, DeviceProfile> TABLE = Map.ofEntries(

            // 机械臂：Brooks PreciseFlex c3 / KUKA。搬一块板 2 秒量级。
            Map.entry("robot", new DeviceProfile("robot", 2_000,
                    Set.of("Home", "Transfer", "TransferTo", "GetPosition", "GetPoints",
                            "SyncPoints", "Grip", "Release", "GetStatus", "Stop"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    true, true)),

            // 移液工作站：Tecan Freedom EVO / Biomek i3-i7。0.5–5000 µL。
            Map.entry("liquid_handler", new DeviceProfile("liquid_handler", 1_500,
                    Set.of("Home", "GetTips", "DropTips", "Aspirate", "Dispense",
                            "Transfer", "Mix", "SetTemp", "GetStatus", "Stop"),
                    0, 0, 0.5, 5000, 0, 0, 4, 60, 45,
                    false, false)),

            // 离心机：Eppendorf 5810，200–14000 rpm，-9~40 °C，板转子 4 位。
            //  资料：eppendorf.com 5810/5810R 产品页（转速 200–14000 rpm / 10 rpm 步进，
            //  温度 -9…+40 °C；计时 1–99 min + 连续 + 短转；10 加速/10 减速档）。
            //  **离心时长是参数不是固定值** ⇒ 由 durationParam 表达（1–3600 秒）。
            Map.entry("centrifuge", DeviceProfile.builder("centrifuge")
                    .delayMs(300_000)
                    .methods(Set.of("Home", "OpenDoor", "CloseDoor", "Load", "Unload",
                            "RunProgram", "Centrifuge", "SetTemp", "GetStatus", "Stop"))
                    .rpm(200, 14_000)
                    .temp(-9, 40)
                    .slots(4)
                    .door(true)
                    .durationParam("RunProgram", "time", 1, 3_600)
                    .durationParam("Centrifuge", "time", 1, 3_600)
                    .result("GetStatus", "state", "rpm", "temperature")
                    .build()),

            // 酶标仪：SpectraMax 190（190–850 nm）。
            //   2026-11-17 补 BioTek Gen5 的**真实方法名**（依据 Gen5 OLE Automation
            //   Programmer's Guide）：机型由 Gen5 独占，中控只与 Gen5 对话，
            //   所以方法集既有 LabOS 习惯名（Load/Unload/Read），也有 Gen5 真名
            //   （CarrierIn/CarrierOut/StartRead/GetDataSetResults…）。
            Map.entry("reader", DeviceProfile.builder("reader")
                    .delayMs(5_000)
                    .methods(Set.of(
                            // —— Gen5 真名 ——
                            "ConfigureSerialReader", "ConfigureUSBReader", "ConfigureOleReader",
                            "TestReaderCommunication", "GetReaderStatus", "GetLastReaderError",
                            "GetReaderCharacteristics", "StartSystemTest",
                            "NewExperiment", "NewExperimentEx", "OpenExperiment", "Close",
                            "Save", "SaveAs", "SetPlateLayout", "GetPlateLayout",
                            "GetPlateTypeNames",
                            "CarrierIn", "CarrierInEx", "CarrierOut", "RunReaderControlCommand",
                            "StartRead", "StartReadEx", "StartSimulatedRead", "AbortRead",
                            "KeepPlateInAfterRead", "SetPartialPlate", "ImportSampleIDs",
                            "GetDataSetNames", "GetDataSetInfo", "GetDataSetResults", "GetRawData",
                            "ExportFile", "PrintReport",
                            "GetCurrentTemperature", "SetTemperatureSetPoint",
                            "GetTemperatureSetPoint",
                            // —— LabOS 习惯名 ——
                            "Home", "OpenDoor", "CloseDoor", "Load", "Unload",
                            "Read", "ReadPlate", "ReadEx", "SimulatedRead", "Stop",
                            "GetResult", "GetStatus", "SelfCheck", "Connect",
                            "GetTemperature", "SetTemperature"))
                    .wavelength(190, 850)
                    .temp(20, 50)
                    .slots(4)
                    .door(true)
                    .timing("CarrierIn", 4_000)
                    .timing("CarrierOut", 4_000)
                    .timing("Load", 4_000)
                    .timing("Unload", 4_000)
                    .timing("Read", 15_000)          // 96 孔单波长静态读板
                    .timing("ReadPlate", 15_000)
                    .timing("ReadEx", 15_000)
                    .timing("SimulatedRead", 3_000)
                    .timing("StartSystemTest", 60_000)
                    .timing("SelfCheck", 60_000)
                    .timing("NewExperiment", 2_000)
                    .timing("OpenExperiment", 2_000)
                    .result("GetStatus", "readerStatus", "plateOnCarrier", "temperature")
                    .result("GetReaderStatus", "readerStatus", "readerType", "plateOnCarrier")
                    .result("GetTemperature", "temperature", "status")
                    .result("GetResult", "plate", "dataSet")
                    .build()),

            // 孵育箱：Thermo Heracell，室温+5 ~ 55 °C，CO2 0–20%。
            Map.entry("incubator", new DeviceProfile("incubator", 3_000,
                    // Deposit1Ex = Deposit1 的扩展版（真机 S100 系列的扩展进板）。
                    // 之前模拟器只认 Deposit1 ⇒ 库里登记了 Deposit1Ex 的设备
                    // 一接模拟器就被判"不支持该方法"⇒ 节点失败。
                    Set.of("Home", "OpenDoor", "CloseDoor", "Deposit1", "Deposit1Ex",
                            "Withdraw1", "Withdraw1Ex",
                            "SetTemp", "SetCO2", "GetTemp", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 5, 55, 20,
                    true, false)),

            // 洗板机：BioTek 405 TS / EL406（LHC 软件；2026-11-17 按手册补真方法集）。
            //   **关键认识**：LHC 下"洗 / 分液 / 预填充"是同一件事 —— 跑已装载的 .LHC 协议，
            //   体积/次数/浸泡全在协议文件里，所以这些方法**不接受 volume 参数**。
            //   ⇒ 画像的 volume 边界只对历史方法名（Wash 带 volume）兜底；
            //     真正的量程校验在协议文件与 LHC 内部（模拟器不假装知道）。
            Map.entry("washer", DeviceProfile.builder("washer")
                    .delayMs(8_000)
                    .methods(Set.of(
                            // —— LHC Runner 真名 ——
                            "LHC_SetProductName", "LHC_SetCommunications", "LHC_TestCommunications",
                            "LHC_PerformSelfCheck", "LHC_LoadProtocolFromFile",
                            "LHC_LoadProtocolFromFlash", "LHC_RunProtocol",
                            "LHC_GetProtocolStatus", "LHC_PauseProtocol", "LHC_ResumeProtocol",
                            "LHC_AbortProtocol", "LHC_ValidateProtocol", "LHC_OverrideValidation",
                            "LHC_LeaveVacuumPumpOn", "LHC_SetRunnerThreading",
                            "LHC_GetLastErrorCode", "LHC_GetErrorString", "LHC_GetProductName",
                            "LHC_GetProductSerialNumber", "LHC_GetWellCount",
                            "LHC_GetOnBoardProtocolCount", "LHC_GetOnBoardProtocolName",
                            "LHC_SetFirstStrip", "LHC_SetNumberOfStrips",
                            "LHC_RunVerifyManifoldTest", "LHC_GetVerifyManifoldRunStatus",
                            "LHC_GetVerifyManifoldTestResults",
                            "LHC_CreateVerifyManifoldTestReport",
                            // —— LabOS 习惯名 ——
                            "SetProductName", "SetCommunications", "TestCommunications",
                            "Home", "SelfCheck", "LoadProtocol", "LoadProtocolFromFlash",
                            "RunProtocol", "Run", "Wash", "Dispense", "Prime",
                            "GetProtocolStatus", "Pause", "PauseProtocol",
                            "Resume", "ResumeProtocol", "Abort", "AbortProtocol", "Stop",
                            "ValidateProtocol", "OverrideValidation", "LeaveVacuumPumpOn",
                            "GetLastErrorCode", "GetErrorString", "GetProductName",
                            "GetSerialNumber", "GetWellCount", "GetOnBoardProtocolCount",
                            "GetOnBoardProtocolName", "SetFirstStrip", "SetNumberOfStrips",
                            "RunVerifyManifoldTest", "GetVerifyManifoldRunStatus",
                            "GetVerifyManifoldResults", "CreateVerifyManifoldReport",
                            "GetStatus", "Load", "Unload"))
                    // 历史方法名 Wash(volume) 的量程兜底：LHC 的洗涤体积在协议里，
                    // 这里保留 50–300 µL 只是让"老流程把体积配错"仍能在仿真阶段暴露。
                    .volume(50, 300)
                    .timing("RunProtocol", 60_000)   // 96 孔整板洗一遍是分钟级
                    .timing("Wash", 60_000)
                    .timing("Dispense", 8_000)
                    .timing("Prime", 30_000)
                    .timing("Run", 60_000)
                    .timing("LoadProtocol", 2_000)
                    .timing("LoadProtocolFromFlash", 2_000)
                    .timing("ValidateProtocol", 5_000)
                    .timing("SelfCheck", 30_000)
                    .timing("Home", 30_000)
                    .timing("TestCommunications", 3_000)
                    .timing("RunVerifyManifoldTest", 60_000)
                    .result("GetStatus", "runStatus", "runStatusText", "protocol")
                    .result("GetProtocolStatus", "runStatus", "runStatusText", "protocol")
                    .result("GetProductName", "productName")
                    .result("GetWellCount", "wellCount")
                    .build()),

            // 分液器：Thermo Multidrop，0.5–2500 µL。
            //  资料：Hamilton Microlab STAR 1–5000 µL；杭州奥铂 KX100-1（**96 孔 8 s**，
            //  5–200 µL，RS485/USB/CAN）；CHFY-600（96 孔 100 µL 仅 6 s/板，20–9000 µL）；
            //  普睿博 Stab H1（96 孔 <30 s，最小 10 µL）。
            //  ⇒ 整板分液是**秒级**，不是分钟级 —— 之前 4 秒偏短。
            Map.entry("dispenser", DeviceProfile.builder("dispenser")
                    .delayMs(8_000)
                    .methods(Set.of("Home", "Dispense", "Prime", "GetStatus"))
                    .volume(0.5, 2_500)
                    .timing("Dispense", 8_000)   // 96 孔整板分液 6–15 s
                    .timing("Prime", 30_000)     // ★ 预填充/排空
                    .result("Dispense", "volume", "plate_present")
                    .result("GetStatus", "state")
                    .build()),

            // 板存储：LiCONiC StoreX / Thermo Cytomat。
            Map.entry("plate_hotel", new DeviceProfile("plate_hotel", 6_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Store", "Retrieve", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 200,
                    true, false)),

            // 封膜机：Agilent PlateLoc，165 °C / 3.5 s 量级。
            //  资料：agilent.com PlateLoc 产品页 + G5402-90014A 快速指南：
            //  **每板约 8 s**（手册）、封膜驻留 0.5–12.0 s（可设）、封膜温度 30–200 °C（0.5 步进）、
            //  暖机 5 min、冷却 90 min、RS-232 + ActiveX + VWorks（**六者里接口证据最完整**）。
            //  ⇒ 之前按"手册没写、按 12 秒"估的；现在有手册值了。
            Map.entry("sealer", DeviceProfile.builder("sealer")
                    .delayMs(8_000)
                    .methods(Set.of("Home", "Load", "Unload", "Seal", "GetStatus"))
                    .temp(30, 200)
                    .timing("Seal", 8_000)          // 每板约 8 s（PlateLoc 手册）
                    .timing("Load", 1_500)
                    .timing("Unload", 1_500)
                    .durationParam("Seal", "dwell", 1, 12)   // 封膜驻留 0.5–12 s
                    .result("Seal", "temperature", "duration_ms")
                    .result("GetStatus", "state", "temperature")
                    .build()),

            // 撕膜机。
            Map.entry("peeler", new DeviceProfile("peeler", 5_000,
                    Set.of("Home", "Load", "Unload", "Peel", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false)),

            // 条码扫描。
            Map.entry("barcode", new DeviceProfile("barcode", 400,
                    Set.of("Home", "Scan", "GetStatus"),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false)),

            // PCR 仪：4–100 °C。
            Map.entry("thermocycler", new DeviceProfile("thermocycler", 60_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "RunProgram", "SetTemp",
                            "GetStatus", "Stop"),
                    0, 0, 0, 0, 0, 0, 4, 100, 0,
                    true, false)),

            // 摇床：0–3000 rpm。
            Map.entry("shaker", new DeviceProfile("shaker", 30_000,
                    Set.of("Home", "Start", "Stop", "GetStatus"),
                    0, 3_000, 0, 0, 0, 0, 4, 60, 0,
                    false, false)),

            // 低温存储：-80 °C 量级。
            Map.entry("freezer", new DeviceProfile("freezer", 8_000,
                    Set.of("Home", "OpenDoor", "CloseDoor", "Store", "Retrieve",
                            "SetTemp", "GetTemp", "GetStatus"),
                    0, 0, 0, 0, 0, 0, -90, -20, 100,
                    true, false)),

            // ===== 以下为 2026-11-15 按厂商公开资料补的画像 =====
            //
            // 参数值来源（**仅用于边界校验与模拟时序，不是采购依据**）：
            //  · Thermo KingFisher Flex（磁珠提取，96 深孔）：thermofisher.cn 产品页
            //  · 杭州奥盛 Auto-Pure 96（磁棒圆周转盘，8 板位 / 2 加热位）：allsheng.com.cn
            //  · 深圳博一 BYHS-96ZP：szbyswkj.com　· 爱翱 PureSTAR 96：i-omics.com
            //  · KingFisher 协议 PDF（**分步时长的主要来源**）：
            //    apps.thermoscientific.com/media/LPG/KingFisher%20Protocols/Genomic/Q_RNA_tissue_mL_1.pdf
            //    （结合孵育 8 min / 洗涤 3 min / DNase 15 min / 再孵育 5 min / 风干 10 min / 全流程 ≈55 min）
            //  · 板式磁分离 ≈30 s（二手：96 孔磁力架产品页）

            // 转盘式核酸提取仪（"分段自驱"那类：设备自己跑到交换点就停下等上位机换板）。
            // 方法名是**我们的抽象**（厂商没公开命令集）⇒ 对应客户文档 §1.2 的
            // 【启动提取流程】/【继续信号】/【等待交互】。
            Map.entry("carousel_extractor", DeviceProfile.builder("carousel_extractor")
                    .delayMs(20_000)
                    .methods(Set.of("Home", "StartProcess", "Continue", "GetStatus",
                            "ReadPlate", "Load", "Unload", "Stop"))
                    .volume(20, 1_000)      // 处理体积 20–1000 µL（Auto-Pure 96 / MM96 / PureSTAR 96）
                    .temp(25, 120)          // 室温–120 ℃（国产机）；KingFisher Flex 是室温+5–115 ℃
                    .slots(8)               // 8 个板位（Auto-Pure 96 / PureSTAR 96 / 爱普 NE-Super96）
                    .door(true)
                    // 分步时长（KingFisher 协议 PDF 为主，★=估算）
                    .timing("Home", 5_000)
                    .timing("RotateCarousel", 2_000)        // ★ 转盘切换
                    .timing("Mix", 60_000)                  // ★ 上下振荡混匀
                    .timing("MagnetSeparate", 30_000)       // 板式磁分离 ≈30 s（二手）
                    .timing("BindIncubate", 480_000)        // 结合孵育 8 min（协议 PDF）
                    .timing("Wash", 180_000)                // 洗涤 3 min（协议 PDF）
                    .timing("Dry", 600_000)                 // 风干 10 min（协议 PDF）
                    .timing("Elute", 300_000)                // ★ 洗脱（协议 PDF 未给，取 5 min）
                    .durationParam("StartProcess", "duration", 60, 3_600)
                    .result("GetStatus", "state", "carousel_position", "plate_present", "temperature")
                    .result("ReadPlate", "plate_barcode", "well_count")
                    .build()),

            // 搅拌单元（顶置搅拌器 / 磁力搅拌器 / 板级磁力搅拌）。
            // 来源：IKA RW 20（顶置，240–2000 rpm，20 L）、IKA RO 5/10/15（多位磁力，
            // 0–1200 rpm，位距 90 mm）、2mag MIXdrive 96 MTP（**板级**：96 个搅拌点、
            // 每点 ≤0.5 mL）、QINSTRUMENTS BioShake D30（轨道振荡 200–3000 rpm）。
            Map.entry("stirrer", DeviceProfile.builder("stirrer")
                    .delayMs(60_000)
                    .methods(Set.of("Home", "Start", "Stop", "SetRpm", "GetStatus"))
                    .rpm(300, 1_500)        // 板级磁力搅拌的建议区间（MIXdrive 的 rpm 由外置控制器设，页面未给）
                    .slots(96)              // 板级搅拌：96 个搅拌点（MIXdrive 96 MTP）
                    .timing("Home", 3_000)
                    .timing("Start", 60_000) // ★ 常规混匀保持 30–120 s
                    .durationParam("Start", "duration", 10, 600)
                    .result("GetStatus", "state", "rpm", "plate_present")
                    .build()),

            // 轨道式四轴机械臂：和通用 robot 的区别是**每次抓板都要扫码**（客户文档 §1.1）。
            // 来源：Brooks PreciseFlex 400（可加 1.0/2.0 m 直线导轨 → 轨道式；4 轴 J1/J2/J3/J4，
            // 负载 0.5 kg，速度 500 mm/s，重复 ±0.09 mm，夹持 2–23 N）、埃斯顿 ER3-400-SR
            // （SCARA 4 轴，负载 3 kg，重复 ±0.01 mm）、建南和 UT-AFW4010NM
            // （X 1.4 s/770 mm、θ 2.2 s/340°、Z 1.7 s/300 mm，真空 −80 kPa）。
            Map.entry("gantry", DeviceProfile.builder("gantry")
                    .delayMs(2_000)
                    .methods(Set.of("Home", "Transfer", "TransferTo", "Grip", "Release",
                            "Scan", "GetPosition", "GetPoints", "SyncPoints", "GetStatus", "Stop"))
                    .holdsPlate(true)
                    .timing("Home", 5_000)
                    .timing("Transfer", 2_000)   // 搬一块板 1–3 s ★
                    .timing("Grip", 1_500)
                    .timing("Release", 1_500)
                    .timing("Scan", 800)         // ★ 扫码 0.3–1 s（厂商未给）
                    .result("Scan", "plate_barcode")
                    .result("GetStatus", "state")
                    .build()),

            // 耗材栈（自动化板栈）：把通用 plate_hotel 按真实资料收紧。
            // 来源：汇像 XHarbor12（按板高 24/20/15/10/8/4 层 ⇒ 288/240/180/120/96/48 板，
            // **平均取板 10 s**，自动扫码）、Thermo Cytomat 5 C450（每叠板器 50 深孔板 /
            // 105 块 96 孔，**取板 <10 s**，4–50 ℃，内置条码阅读器）。
            Map.entry("plate_stack", DeviceProfile.builder("plate_stack")
                    .delayMs(10_000)
                    .methods(Set.of("Home", "OpenDoor", "CloseDoor", "Store", "Retrieve",
                            "GetStatus", "GetPosition"))
                    .slots(288)               // XHarbor12 最大 288 板
                    .door(true)
                    .timing("Home", 5_000)
                    .timing("Store", 10_000)   // 取/放板 10 s（XHarbor12 / Cytomat）
                    .timing("Retrieve", 10_000)
                    .result("GetStatus", "state", "remaining_plates", "slot")
                    .result("GetPosition", "slot")
                    .build())
    );

    /** generic：不校验方法也不校验参数（行为与画像引入前一致，用于临时调试）。 */
    public static final DeviceProfile GENERIC = new DeviceProfile("generic", 150,
            Set.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

    public static DeviceProfile of(String name) {
        if (name == null || name.isBlank() || "generic".equals(name)) {
            return GENERIC;
        }
        return TABLE.get(name);
    }

    public static Set<String> knownNames() {
        return TABLE.keySet();
    }

    // ---------------------------------------------------------------- 构造器

    /** 新画像的构造器入口。 */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 画像构造器：给<b>新画像</b>用。
     *
     * <p><b>为什么另起一个</b>：老画像的构造器已经 14 个位置参数，
     * 再加"步骤时长表""结果字段表"就变成 16 个 —— 谁都记不住谁是谁。
     * 老画像**一个都不动**（零风险），新画像走这个。
     */
    public static final class Builder {
        private final String name;
        private long defaultDelayMs = 1_000;
        private Set<String> methods = Set.of();
        private int minRpm;
        private int maxRpm;
        private double minVolume;
        private double maxVolume;
        private int minWavelength;
        private int maxWavelength;
        private double minTemp;
        private double maxTemp;
        private int slots;
        private boolean hasDoor;
        private boolean holdsPlate;
        private final Map<String, Long> stepTimings = new LinkedHashMap<>();
        private final Map<String, List<String>> resultFields = new LinkedHashMap<>();
        private final Map<String, DurationSpec> durationParams = new LinkedHashMap<>();

        public Builder(String name) {
            this.name = name;
        }

        /** 该类设备的默认耗时（没给步骤时长表时的兜底）。 */
        public Builder delayMs(long v) {
            this.defaultDelayMs = v;
            return this;
        }

        public Builder methods(Set<String> v) {
            this.methods = v == null ? Set.of() : v;
            return this;
        }

        /** 转速范围（rpm）。 */
        public Builder rpm(int min, int max) {
            this.minRpm = min;
            this.maxRpm = max;
            return this;
        }

        /** 移液量程（µL）。 */
        public Builder volume(double min, double max) {
            this.minVolume = min;
            this.maxVolume = max;
            return this;
        }

        /** 波长范围（nm）。 */
        public Builder wavelength(int min, int max) {
            this.minWavelength = min;
            this.maxWavelength = max;
            return this;
        }

        /** 温控范围（°C）。 */
        public Builder temp(double min, double max) {
            this.minTemp = min;
            this.maxTemp = max;
            return this;
        }

        /** 板位/层位数量；0 = 不限制。 */
        public Builder slots(int v) {
            this.slots = v;
            return this;
        }

        /** 是否"有门"（离心机/孵育箱/酶标仪/板库）。 */
        public Builder door(boolean v) {
            this.hasDoor = v;
            return this;
        }

        /** 是否"持板"型设备（机械臂：Grip/Release）。 */
        public Builder holdsPlate(boolean v) {
            this.holdsPlate = v;
            return this;
        }

        /** 某方法的模拟耗时（毫秒）—— 比"整类一个默认耗时"精确得多。 */
        public Builder timing(String method, long ms) {
            if (method != null && !method.isBlank()) {
                stepTimings.put(method.trim(), ms);
            }
            return this;
        }

        /** 某方法回包 {@code data} 里要带哪些字段（约定键，见 {@link #resultValue}）。 */
        public Builder result(String method, String... fields) {
            if (method != null && !method.isBlank() && fields != null && fields.length > 0) {
                resultFields.put(method.trim(), List.of(fields));
            }
            return this;
        }

        /**
         * 某方法的<b>时长由参数决定</b>：{@code param} 里的秒数就是模拟耗时。
         *
         * <p>例：离心机 {@code RunProgram} 的 {@code time}（1–3600 秒）、
         * 搅拌器 {@code Start} 的 {@code duration}（10–600 秒）。
         */
        public Builder durationParam(String method, String param, int minSec, int maxSec) {
            if (method != null && !method.isBlank() && param != null && !param.isBlank()) {
                durationParams.put(method.trim(),
                        new DurationSpec(param.trim(), minSec, Math.max(minSec, maxSec)));
            }
            return this;
        }

        public DeviceProfile build() {
            DeviceProfile p = new DeviceProfile(name, defaultDelayMs, methods,
                    minRpm, maxRpm, minVolume, maxVolume,
                    minWavelength, maxWavelength, minTemp, maxTemp, slots,
                    hasDoor, holdsPlate);
            p.stepTimings = Map.copyOf(stepTimings);
            p.resultFields = Map.copyOf(resultFields);
            p.durationParams = Map.copyOf(durationParams);
            return p;
        }
    }

    /** 某方法的模拟耗时（毫秒）；没登记就用通用规则。 */
    public long timingOf(String method) {
        if (method == null) {
            return -1L;
        }
        Long t = stepTimings.get(method.trim());
        return t == null ? -1L : t;
    }

    /** 某方法的结果字段（约定键）；没登记返回空表。 */
    public List<String> resultFieldsOf(String method) {
        if (method == null) {
            return List.of();
        }
        List<String> f = resultFields.get(method.trim());
        return f == null ? List.of() : f;
    }

    /**
     * 某方法的模拟耗时（秒）：若该方法的时长**由参数决定**、且请求里带了这个参数
     * ⇒ 取它（夹在机型允许范围内）；否则返回 -1（"没有时长参数，按别的规则算"）。
     */
    public long durationSecondsOf(String method, String msg) {
        if (method == null) {
            return -1L;
        }
        DurationSpec spec = durationParams.get(method.trim());
        if (spec == null) {
            return -1L;
        }
        Double v = number(msg, spec.param());
        if (v == null) {
            return -1L;
        }
        long sec = Math.round(v);
        return Math.min(spec.maxSec(), Math.max(spec.minSec(), sec));
    }

    /** 已登记步骤时长表的方法（观测用）。 */
    public Map<String, Long> stepTimings() {
        return stepTimings;
    }

    /**
     * 校验一条命令。
     *
     * @return null 表示通过；否则是给中控看的错误文本
     */
    public String validate(String method, String msg) {
        return validate(method, msg, true);
    }

    /**
     * 校验一条命令。
     *
     * @param checkMethods 是否校验"该方法属不属于这类设备"。
     *                     <b>有具体型号时要传 false</b>：型号才是方法集的权威
     *                     （S100 的 {@code MoveTray}/{@code SetParam} 不在通用
     *                     封膜机画像里，按画像校验会被误拒），
     *                     画像此时只用来做参数边界校验。
     */
    public String validate(String method, String msg, boolean checkMethods) {
        if (this == GENERIC) {
            return null;
        }
        if (checkMethods && !methods.contains(method)) {
            return "设备类型 " + name + " 不支持方法 " + method;
        }

        // ---- 转速（离心机 / 摇床 / 搅拌器）----
        if (maxRpm > 0 && ("RunProgram".equals(method) || "Centrifuge".equals(method)
                || "Start".equals(method) || "SetRpm".equals(method))) {
            Double rpm = number(msg, "rpm");
            if (rpm == null) {
                rpm = number(msg, "speed");
            }
            if (rpm != null && (rpm < minRpm || rpm > maxRpm)) {
                return String.format("转速 %.0f 超出机型范围 %d–%d rpm", rpm, minRpm, maxRpm);
            }
        }

        // ---- 时长参数（离心时间 / 搅拌时间 / 提取流程时长…）----
        for (Map.Entry<String, DurationSpec> e : durationParams.entrySet()) {
            if (!e.getKey().equals(method)) {
                continue;
            }
            Double v = number(msg, e.getValue().param());
            if (v != null && (v < e.getValue().minSec() || v > e.getValue().maxSec())) {
                return String.format("%s %.0f 超出机型允许时长 %d–%d 秒",
                        e.getValue().param(), v, e.getValue().minSec(), e.getValue().maxSec());
            }
        }

        // ---- 量程（移液/分液/洗板/提取）----
        if (maxVolume > 0 && ("Aspirate".equals(method) || "Dispense".equals(method)
                || "Transfer".equals(method) || "Mix".equals(method)
                || "Wash".equals(method)
                // 提取仪的样本体积也是量程（20–1000 µL），配错了要在模拟阶段就暴露
                || "StartProcess".equals(method))) {
            Double v = number(msg, "volume");
            if (v != null && (v < minVolume || v > maxVolume)) {
                return String.format("体积 %.2f µL 超出机型量程 %.1f–%.0f µL",
                        v, minVolume, maxVolume);
            }
        }

        // ---- 波长（酶标仪）----
        if (maxWavelength > 0 && "Read".equals(method)) {
            Double w = number(msg, "wavelength");
            if (w != null && (w < minWavelength || w > maxWavelength)) {
                return String.format("波长 %.0f nm 超出机型范围 %d–%d nm",
                        w, minWavelength, maxWavelength);
            }
        }

        // ---- 温度 ----
        if (minTemp != 0 && maxTemp != 0 && "SetTemp".equals(method)) {
            Double t = number(msg, "temp");
            if (t == null) {
                t = number(msg, "celsius");
            }
            if (t != null && (t < minTemp || t > maxTemp)) {
                return String.format("温度 %.1f °C 超出机型范围 %.0f–%.0f °C",
                        t, minTemp, maxTemp);
            }
        }

        // ---- CO2 ----
        if ("SetCO2".equals(method)) {
            Double co2 = number(msg, "co2");
            if (co2 != null && (co2 < 0 || co2 > 20)) {
                return String.format("CO2 %.1f%% 超出范围 0–20%%", co2);
            }
        }

        // ---- 板位/层位 ----
        if (slots > 0) {
            Double s = number(msg, "slot");
            if (s == null) {
                s = number(msg, "position");
            }
            if (s != null && (s < 1 || s > slots)) {
                return String.format("板位 %.0f 超出机型容量 1–%d", s, slots);
            }
        }
        return null;
    }

    /**
     * 单个方法的模拟耗时。
     *
     * <p><b>为什么不能全用一个延迟</b>：真实设备开门 2 秒、回原点 3 秒、
     * 离心 30 分钟。改动前所有方法共用一个 delay，导致"开门"也要等 5 分钟，
     * 自动化测试直接超时 —— 而且完全不符合现场观感。
     *
     * <p>规则：
     * <ul>
     *   <li>查询类（GetStatus/GetPoints/Scan）—— 几百毫秒</li>
     *   <li>门/装夹类（OpenDoor/CloseDoor/Load/Unload/Grip/Release）—— 1.5 秒</li>
     *   <li>工艺类（RunProgram/Read/Wash/Seal…）—— 用该设备类型的默认耗时</li>
     * </ul>
     */
    public long methodDelayMs(String method) {
        if (this == GENERIC) {
            return defaultDelayMs;
        }
        // 步骤时长表优先：通用规则是"按动作性质猜"（门 1.5 秒、Home 3 秒），
        // 对老画像够用；但同一类设备里不同工艺步骤差着数量级
        // （转盘切换 2 秒 vs 孵育 5 分钟）⇒ 新画像用步骤时长表精确指定。
        long t = timingOf(method);
        if (t >= 0) {
            return t;
        }
        return switch (method == null ? "" : method) {
            case "GetStatus", "GetPoints", "GetPosition", "Scan", "GetResult", "GetTemp" -> 300L;
            case "OpenDoor", "CloseDoor", "Load", "Unload", "Grip", "Release" -> 1_500L;
            case "Home" -> 3_000L;
            case "SyncPoints", "SetTemp", "SetCO2", "Prime", "Mix" -> 2_000L;
            default -> defaultDelayMs;
        };
    }

    /**
     * 结果字段的**约定键** → 一个合理的模拟值。
     *
     * <p><b>这些是模拟值，不是实测值</b> —— 模拟器的作用是让流程跑起来、
     * 让"结果字段存在且形状对"，不是替真机做测量。
     *
     * <p>认识的键：
     * {@code state} / {@code plate_present} / {@code plate_barcode} /
     * {@code carousel_position} / {@code slot} / {@code temperature} /
     * {@code rpm} / {@code volume} / {@code duration_ms} / {@code step} /
     * {@code well_count} / {@code remaining_plates} / {@code cycle_count}。
     */
    public String resultValue(String key) {
        if (key == null) {
            return "";
        }
        return switch (key.trim()) {
            case "state" -> "idle";
            case "plate_present" -> "true";
            case "plate_barcode" -> "SIM-" + name.toUpperCase(java.util.Locale.ROOT) + "-0001";
            case "carousel_position", "slot", "position" -> "1";
            case "temperature", "temp" ->
                    (minTemp != 0 || maxTemp != 0) ? String.valueOf((int) minTemp) : "25";
            case "rpm" -> maxRpm > 0 ? String.valueOf(minRpm > 0 ? minRpm : 1000) : "0";
            case "volume" -> maxVolume > 0 ? String.valueOf(minVolume > 0 ? minVolume : 100.0) : "0";
            case "duration_ms" -> String.valueOf(defaultDelayMs);
            case "step" -> "idle";
            case "well_count" -> "96";
            case "remaining_plates" -> slots > 0 ? String.valueOf(slots) : "0";
            case "cycle_count" -> "1";
            default -> "";
        };
    }

    /** 从 JSON 片段里取一个数字字段；取不到返回 null。 */
    private static Double number(String msg, String key) {
        if (msg == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(msg);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
