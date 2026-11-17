package com.labos.kernel.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BioTek Gen5 酶标仪（OLE Automation）的真实行为。
 *
 * <p>依据：{@code Gen5 OLE Automation Programmer's Guide}（117 页）+ {@code BTIStatusCodes.h}
 * （我方解读见 {@code docs/设备/酶标仪-BioTek-Gen5.md}）。
 *
 * <p>与洗板机（{@link LhcBehavior}）最大的不同：Gen5 <b>不是"下指令等回包"</b>，
 * 而是「配置读板机 → 开实验 → 承载器进板 → 开始读 → 轮询 → 取数据集」的对象模型。
 * 所以这里维护的是一棵很小的对象树：
 * <pre>
 *   Application(configured / commTested) → Experiment(protocol) → Plate(承载器 / 读数集)
 * </pre>
 *
 * <p>手册里能确定的约束：
 * <ol>
 *   <li><b>没 Configure + TestReaderCommunication</b> 就进出板/读板 ⇒ {@code EC-05-2301}；</li>
 *   <li><b>没有打开的 Experiment</b> 就动 Plate ⇒ {@code EC-05-2302}；</li>
 *   <li><b>承载器上没有板</b>就读 ⇒ {@code EC-05-2307}；</li>
 *   <li><b>读板期间</b>禁止关实验/导出/打印/进出板/重配置 ⇒ {@code EC-05-2303}；</li>
 *   <li>读板是异步的：{@code Read} 立即返回，进度看 {@code ReadInProgress}，
 *       成功与否看 {@code Plate.ReadStatus}（不是看有没有抛异常）；</li>
 *   <li>原始数据要先开 {@code DataExportEnabled} ⇒ 否则 {@code EC-05-2306}。</li>
 * </ol>
 *
 * <p><b>不模拟的部分</b>（诚实标注）：光学。OD 值是按板号生成的**确定性**模拟值，
 * 用途只是"让下游拿到结构正确的数据集"，**不能用于任何定量结论**。
 */
final class Gen5Behavior implements DeviceBehavior {

    private static final int READ_IDLE = 0;
    private static final int READ_IN_PROGRESS = 1;
    private static final int READ_DONE = 2;
    private static final int READ_ABORTED = 3;

    /** Configure*Reader 是否已调用；-1 = 未配置。 */
    private int readerType = -1;
    private boolean commTested;

    private String protocol;
    private boolean experimentOpen;
    private boolean dataExportEnabled;
    private boolean keepPlateIn;

    private boolean plateOnCarrier;
    private String plateBarcode;

    private int readState = READ_IDLE;
    private int polls;
    /** 轮询几次后完成：真机看协议，模拟器固定 3 次（够暴露"不轮询就当成功"）。 */
    private final int pollsToFinish = 3;
    private int lastReaderError;

    private double temperature = 25.0;
    private boolean incubation;
    private double setPoint = 37.0;

    @Override
    public String modelId() {
        return "biotek-gen5";
    }

    @Override
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readerType", readerType);
        m.put("commTested", commTested);
        m.put("experimentOpen", experimentOpen);
        m.put("protocol", protocol);
        m.put("plateOnCarrier", plateOnCarrier);
        m.put("plateBarcode", plateBarcode);
        m.put("readInProgress", readState == READ_IN_PROGRESS);
        m.put("readState", readState);
        m.put("dataExportEnabled", dataExportEnabled);
        m.put("incubation", incubation);
        m.put("temperature", temperature);
        m.put("setPoint", setPoint);
        return m;
    }

    @Override
    public Reply handle(DeviceCore core, String method, String msg) {
        switch (method) {

            // ------------------------------------------------ 读板机配置与通讯
            case "ConfigureSerialReader", "ConfigureUSBReader", "ConfigureOleReader" -> {
                if (readState == READ_IN_PROGRESS) {
                    return Reply.err("EC-05-2303", "读板进行中不能重新配置读板机");
                }
                Integer type = DeviceCore.intParam(msg, "readerType");
                // 手册：-1 = 只接了一台时自动选
                readerType = type == null ? -1 : type;
                commTested = false;
                return Reply.ok("{\"readerType\":" + readerType + ",\"configured\":true}");
            }
            case "TestReaderCommunication", "Connect" -> {
                if (readerType < 0 && readerType != -1) {
                    return Reply.err("-600", "未知机型 ReaderType=" + readerType);
                }
                commTested = true;
                return Reply.ok("{\"connected\":true,\"readerType\":" + readerType + "}");
            }
            case "GetReaderStatus", "GetStatus" ->
                    Reply.ok("{\"readerStatus\":\""
                            + (readState == READ_IN_PROGRESS ? "busy" : "idle") + "\""
                            + ",\"readerType\":" + readerType
                            + ",\"plateOnCarrier\":" + plateOnCarrier
                            + ",\"temperature\":" + temperature + "}");
            case "GetLastReaderError" -> Reply.ok("{\"readerError\":" + lastReaderError + "}");
            case "GetReaderCharacteristics" -> {
                Integer id = DeviceCore.intParam(msg, "id");
                return Reply.ok("{\"value\":" + characteristic(id == null ? -1 : id) + "}");
            }
            case "GetSupportedReaders" ->
                    Reply.ok("{\"readers\":[\"Synergy H1\",\"Epoch 2\",\"Cytation 5\",\"ELx808\"]}");
            case "StartSystemTest", "SelfCheck" -> {
                Reply bad = requireComm();
                if (bad != null) {
                    return bad;
                }
                return Reply.ok("{\"systemTest\":\"passed\"}");
            }

            // ------------------------------------------------ 实验（Experiment）
            case "NewExperiment", "NewExperimentEx", "OpenExperiment" -> {
                String p = DeviceCore.strParam(msg, "protocolPathName");
                if (p == null || p.isBlank()) {
                    p = DeviceCore.strParam(msg, "experimentPathName");
                }
                if (p == null || p.isBlank()) {
                    // 手册：路径分 DB:\ 与 C:\ 两种模式，用错必失败
                    return Reply.err("-100",
                            "缺少协议/实验路径（数据库模式 DB:\\Protocols\\x.prt，文件模式 C:\\...）");
                }
                protocol = p;
                experimentOpen = true;
                return Reply.ok("{\"experimentOpen\":true,\"protocol\":\"" + esc(p) + "\"}");
            }
            case "Close", "CloseExperiment" -> {
                if (readState == READ_IN_PROGRESS) {
                    return Reply.err("EC-05-2303", "读板进行中不能关闭实验");
                }
                experimentOpen = false;
                plateOnCarrier = false;
                return Reply.ok("{\"experimentOpen\":false}");
            }
            case "Save", "SaveAs", "SaveExperiment", "SaveExperimentAs" -> {
                if (!experimentOpen) {
                    return Reply.err("EC-05-2302", "没有打开的 Experiment");
                }
                return Reply.ok("{\"saved\":true}");
            }
            case "SetPlateLayout" -> Reply.ok("{\"plateLayout\":\"set\"}");
            case "GetPlateLayout", "SetPartialPlate", "ImportSampleIDs" ->
                    Reply.ok("{\"ok\":true}");
            case "GetPlateTypeNames" ->
                    Reply.ok("{\"plateTypes\":[\"96 Well Flat\",\"96 Well Round\",\"384 Well Flat\"]}");

            // ------------------------------------------------ 承载器（= 点位进出）
            case "CarrierIn", "CarrierInEx", "Load" -> {
                Reply bad = requireForPlate();
                if (bad != null) {
                    return bad;
                }
                String barcode = DeviceCore.strParam(msg, "barcode");
                plateOnCarrier = true;
                plateBarcode = (barcode == null || barcode.isBlank())
                        ? "SIM-GEN5-" + Math.abs(("plate" + protocol).hashCode())
                        : barcode;
                return Reply.ok("{\"carrier\":\"in\",\"plateOnCarrier\":true"
                        + ",\"barcode\":\"" + esc(plateBarcode) + "\"}");
            }
            case "CarrierOut", "Unload" -> {
                Reply bad = requireForPlate();
                if (bad != null) {
                    return bad;
                }
                plateOnCarrier = false;
                return Reply.ok("{\"carrier\":\"out\",\"plateOnCarrier\":false}");
            }
            case "RunReaderControlCommand", "RunReaderControl" -> Reply.ok("{\"panel\":\"ok\"}");
            case "ClearTipPrimeTrough" -> Reply.ok("{\"trough\":\"cleared\"}");

            // ------------------------------------------------ 读板
            case "StartRead", "Read", "ReadPlate", "StartReadEx", "ReadEx",
                 "StartSimulatedRead", "SimulatedRead" -> {
                Reply bad = requirePlateLoaded();
                if (bad != null) {
                    return bad;
                }
                if (readState == READ_IN_PROGRESS) {
                    return Reply.err("EC-05-2303", "上一次读板还没结束");
                }
                polls = 0;
                readState = READ_IN_PROGRESS;
                return Reply.ok("{\"readInProgress\":true,\"plate\":\"" + esc(plateBarcode) + "\"}");
            }
            case "GetReadProgress", "ReadInProgress" -> {
                advanceRead();
                return Reply.ok("{\"readInProgress\":" + (readState == READ_IN_PROGRESS)
                        + ",\"readStatus\":\"" + readStatusText() + "\"}");
            }
            case "AbortRead", "Stop" -> {
                readState = READ_ABORTED;
                return Reply.ok("{\"aborted\":true,\"readStatus\":\"aborted\"}");
            }
            case "KeepPlateInAfterRead" -> {
                keepPlateIn = true;
                return Reply.ok("{\"keepPlateIn\":true}");
            }

            // ------------------------------------------------ 取结果
            case "GetDataSetNames" ->
                    Reply.ok("{\"dataSets\":[\"Raw Data\",\"500 nm\"]}");
            case "GetDataSetInfo" ->
                    Reply.ok("{\"dataSet\":\"500 nm\",\"decimalPlaces\":3,\"unit\":\"OD\"}");
            case "GetRawData" -> {
                if (!dataExportEnabled) {
                    return Reply.err("EC-05-2306",
                            "DataExportEnabled=false：读板前要先置 true 才能取原始数据");
                }
                return Reply.ok(rawDataJson());
            }
            case "GetDataSetResults", "GetResult" -> {
                Reply bad = requireReadDone();
                if (bad != null) {
                    return bad;
                }
                return Reply.ok(rawDataJson());
            }
            case "GetReadErrorCode" ->
                    Reply.ok("{\"errorCode\":" + (lastReaderError == 0 ? 0 : -601) + "}");
            case "GetReadErrorMessage" ->
                    Reply.ok("{\"message\":\"" + (lastReaderError == 0
                            ? "" : "仪器报错 " + lastReaderError) + "\"}");
            case "GetReaderError" -> Reply.ok("{\"readerError\":" + lastReaderError + "}");
            case "FileExportEx", "ExportFile", "PrintReportEx", "PrintReport" -> {
                if (readState == READ_IN_PROGRESS) {
                    return Reply.err("EC-05-2303", "读板进行中不能导出/打印");
                }
                return Reply.ok("{\"path\":\"C:\\\\Gen5Export\\\\SIM.txt\"}");
            }

            // ------------------------------------------------ 温控
            case "GetCurrentTemperature", "GetTemperature" ->
                    Reply.ok("{\"temperature\":" + temperature
                            + ",\"status\":\"" + (incubation ? "at_setpoint" : "off") + "\"}");
            case "SetTemperatureSetPoint", "SetTemperature" -> {
                Integer on = DeviceCore.intParam(msg, "incubate");
                incubation = on == null || on == 1;
                Double sp = DeviceCore.doubleParam(msg, "setPoint");
                if (sp != null) {
                    if (sp < 20 || sp > 50) {
                        return Reply.err("-302", "设定温度 " + sp + " °C 超出 Gen5 允许范围 20–50 °C");
                    }
                    setPoint = sp;
                }
                temperature = incubation ? setPoint : 25.0;
                return Reply.ok("{\"incubation\":" + incubation + ",\"setPoint\":" + setPoint + "}");
            }
            case "GetTemperatureSetPoint" ->
                    Reply.ok("{\"setPoint\":" + setPoint + ",\"gradient\":0}");

            // 没登记在这台型号行为里的方法：交回通用状态机
            default -> {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 内部

    /** 读板轮询推进：Busy 累计 3 次后完成。 */
    private void advanceRead() {
        if (readState != READ_IN_PROGRESS) {
            return;
        }
        if (++polls >= pollsToFinish) {
            readState = READ_DONE;
        }
    }

    private String readStatusText() {
        return switch (readState) {
            case READ_IN_PROGRESS -> "reading";
            case READ_DONE -> "completed";
            case READ_ABORTED -> "aborted";
            default -> "not_read";
        };
    }

    private Reply requireComm() {
        if (readerType == -1 && !commTested) {
            return Reply.err("EC-05-2301",
                    "读板机未配置/未连通：先 ConfigureSerialReader|ConfigureUSBReader 再 TestReaderCommunication");
        }
        if (!commTested) {
            return Reply.err("EC-05-2301", "还没做 TestReaderCommunication（或刚重配置过）");
        }
        return null;
    }

    private Reply requireForPlate() {
        Reply bad = requireComm();
        if (bad != null) {
            return bad;
        }
        if (readState == READ_IN_PROGRESS) {
            return Reply.err("EC-05-2303", "读板进行中不能进出板");
        }
        if (!experimentOpen) {
            return Reply.err("EC-05-2302", "没有打开的 Experiment：先 NewExperiment/OpenExperiment");
        }
        return null;
    }

    private Reply requirePlateLoaded() {
        Reply bad = requireForPlate();
        if (bad != null) {
            return bad;
        }
        if (!plateOnCarrier) {
            return Reply.err("EC-05-2307", "承载器上没有板：先 CarrierIn");
        }
        return null;
    }

    private Reply requireReadDone() {
        if (readState == READ_IN_PROGRESS) {
            return Reply.err("EC-05-2303", "读板还没结束，等 ReadInProgress=false");
        }
        if (readState != READ_DONE) {
            return Reply.err("EC-05-2302", "没有可用的读数集：先 Read 成功");
        }
        return null;
    }

    /**
     * 确定性模拟读数集（8×12=96 孔）。
     *
     * <p>只是结构正确、值可复现，<b>不代表任何真实光学行为</b>。
     */
    private String rawDataJson() {
        int seed = Math.abs((plateBarcode == null ? "SIM" : plateBarcode).hashCode());
        StringBuilder sb = new StringBuilder("{\"plate\":\"");
        sb.append(esc(plateBarcode == null ? "" : plateBarcode)).append("\",\"dataSet\":\"500 nm\"");
        sb.append(",\"wells\":[");
        for (int i = 0; i < 96; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // 0.050 ~ 3.500 OD，带一点"高值孔"的分布形状
            int v = (seed / (i + 7) + i * 37) % 3450;
            sb.append(String.format(java.util.Locale.ROOT, "%.3f", 0.05 + v / 1000.0));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 手册 §2.1.2.6 的 ReaderCharacteristicsID。 */
    private Object characteristic(int id) {
        return switch (id) {
            case 0 -> true;          // eAbsorbanceReadSupported
            case 1 -> 200;           // 最小波长
            case 2 -> 999;           // 最大波长（Synergy 系列到 999）
            case 3 -> 3;             // 吸收读法数量（Normal/Rapid/Sweep）
            case 5 -> true;          // 支持温控
            case 6 -> 20;            // 最低孵育温度
            case 7 -> 50;            // 最高孵育温度
            case 8 -> 0;             // 无梯度
            case 9 -> true;          // 支持振荡
            case 10 -> "SIM-" + Math.abs(readerType);
            case 11 -> "Synergy H1 (simulated)";
            default -> "unknown-characteristic-id:" + id;
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
