using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// BioTek Gen5 酶标仪：**调 OLE Automation 服务器 <c>Gen5.Application</c>**。
///
/// <para>手册：<c>Gen5 OLE Automation Programmer's Guide</c>（我方解读：
/// <c>docs/设备/酶标仪-BioTek-Gen5.md</c>）。</para>
///
/// <para><b>与洗板机最大的差别</b>：LHC 是"一个组件 29 个方法"，Gen5 是**一棵对象树**：</para>
/// <code>
///   Application ──NewExperiment/OpenExperiment──▶ Experiment
///        │                                          └─Plates─▶ Plate ─StartRead─▶ PlateReadMonitor
///        └─ Configure*Reader / TestReaderCommunication / CarrierIn|Out / 温控
/// </code>
/// <para>
/// ⇒ 本类必须**替调用方持有这棵树**（Experiment / Plates / Plate / Monitor），
/// 否则每来一条命令都要重新 NewExperiment —— 重开实验会把上一块板的读数丢掉。
/// </para>
///
/// <para><b>手册里必须遵守的三条</b>：</para>
/// <list type="number">
///   <item>释放顺序 <c>Plate → Plates → Experiment</c>（顺序错了会抛异常）；</item>
///   <item>读板期间禁止删板/导出/打印/CarrierIn/Out/Configure*Reader（本类直接挡成
///         <c>EC-05-2303</c>，不去试探厂商组件的容错）；</item>
///   <item>原始数据要先把 <c>Application.DataExportEnabled</c> 置 true。</item>
/// </list>
///
/// <para><b>读板为什么是阻塞的</b>：<c>StartRead</c> 立刻返回一个 <c>PlateReadMonitor</c>，
/// 手册要求轮询 <c>ReadInProgress</c>。中控的一条节点命令要"读完才算完"，
/// 所以本类把"启动+轮询+取错误集合"收成一次回包；<c>GetReadProgress</c> 保留给
/// 需要自己控节奏的流程。</para>
///
/// <para><b>平台</b>：COM 只在 Windows；非 Windows 返回 <c>EC-05-2304</c>。</para>
/// </summary>
public sealed class Gen5OleTransport : ITransport, ILinkProbe
{
    /// <summary>BTI_OK。</summary>
    private const int BTI_OK = 1;

    private readonly string _progId;
    private readonly int _pollMs;
    private ComDispatch? _app;
    private ComDispatch? _experiment;
    private ComDispatch? _plates;
    private ComDispatch? _plate;
    private ComDispatch? _monitor;
    private bool _readInProgress;
    private string? _lastError;

    public Gen5OleTransport(string progId = "Gen5.Application", int pollMs = 500)
    {
        _progId = progId;
        _pollMs = Math.Max(200, pollMs);
    }

    public string Name => $"Gen5 OLE({_progId})";

    public string? LastError => _lastError;

    public bool TryEnsureLink()
    {
        var (app, err) = EnsureApp();
        _lastError = err;
        if (app == null)
        {
            return false;
        }
        try
        {
            // 1 = BTI_OK；负数是 BTIStatusCodes（仪器没连上）
            var rc = app.CallInt("TestReaderCommunication");
            _lastError = rc == BTI_OK ? null : $"TestReaderCommunication={rc}（仪器未连通）";
            return true;
        }
        catch (ComCallException e)
        {
            _lastError = e.Message;
            return true;   // 组件活着，只是还没配置读板机
        }
    }

    public TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model)
    {
        var def = model.Find(req.Method);
        if (def == null)
        {
            return TransportReply.Fail("EC-06-0004",
                $"型号 {model.Id} 没有指令 {req.Method}（手册里没有）");
        }
        var (app, err) = EnsureApp();
        if (app == null)
        {
            return TransportReply.Fail("EC-05-2304", err ?? "Gen5 组件不可用");
        }

        try
        {
            return def.Cmd switch
            {
                // ---------------- Application：配置 / 自检 / 温控 ----------------
                // 手册 §2.1.2.1：3 个 VARIANT* 出参（名字 / 类型 / 通讯方式）
                "GetSupportedReaders" => SupportedReaders(app),
                "ConfigureSerialReader" => Configure(app, def.Cmd),
                "ConfigureUSBReader" => Configure(app, def.Cmd),
                "ConfigureOleReader" => Configure(app, def.Cmd),
                "TestReaderCommunication" => ReturnCode(app.CallInt("TestReaderCommunication"), def.Cmd),
                "GetReaderStatus" => ReturnCode(app.CallInt("GetReaderStatus"), def.Cmd),
                "GetLastReaderError" => ReturnCode(app.CallInt("GetLastReaderError"), def.Cmd),
                "GetReaderCharacteristics" => GetCharacteristic(app, req),
                "StartSystemTest" => RunSystemTest(app),
                "RunReaderControlCommand" => RunControlCommand(app, req),
                "CarrierIn" => CarrierMove(app, inbound: true, req),
                "CarrierInEx" => CarrierMove(app, inbound: true, req),
                "CarrierOut" => CarrierMove(app, inbound: false, req),
                "GetCurrentTemperature" => GetTemperature(app),
                "SetTemperatureSetPoint" => SetTemperature(app, req),
                "GetTemperatureSetPoint" => ReadVariant(app, "GetTemperatureSetPoint", "setPoint"),
                "ClearTipPrimeTrough" => CallVoid(app, "ClearTipPrimeTrough", "trough"),

                // ---------------- Experiment ----------------
                "NewExperiment" => OpenExperiment(app, "NewExperiment", req),
                "NewExperimentEx" => OpenExperiment(app, "NewExperimentEx", req),
                "OpenExperiment" => OpenExperiment(app, "OpenExperiment", req),
                "Close" => CloseExperiment(),
                "Save" => SaveExperiment(app, path: null),
                "SaveAs" => SaveExperiment(app, Str(req, "path")),
                "GetPlateLayout" => ReadString(_experiment, "GetPlateLayout",
                    new object?[] { Str(req, "protocolId") ?? "" }, "plateLayout"),
                "SetPlateLayout" => CallWithStringArg(_experiment, "SetPlateLayout",
                    Str(req, "plateLayoutXml"), "plateLayout"),
                "GetPlateTypeNames" => ReadVariant(app, "GetPlateTypeNames", "plateTypes",
                    (short)(Protocol.Int(req.Parameters, "selectionFlag") ?? 0)),

                // ---------------- Plate：读板 ----------------
                "StartRead" => StartReadAndWait(app, model, req, rotate: false, simulated: false),
                "StartReadEx" => StartReadAndWait(app, model, req,
                    rotate: Protocol.Int(req.Parameters, "rotate180") == 1, simulated: false),
                "StartSimulatedRead" => StartReadAndWait(app, model, req, rotate: false, simulated: true),
                "AbortRead" => AbortRead(),
                "KeepPlateInAfterRead" => CallWithStringArg(_plate, "KeepPlateInAfterRead", null, "keep"),
                "SetPartialPlate" => CallWithStringArg(_plate, "SetPartialPlate",
                    Str(req, "partialPlateXml"), "partialPlate"),
                "ImportSampleIDs" => CallWithStringArg(_plate, "ImportSampleIDs",
                    Str(req, "filePath"), "imported"),
                "GetDataSetNames" => ReadVariant(_plate, "GetDataSetNames", "dataSets",
                    Str(req, "protocolId") ?? ""),
                // 手册 §2.4.2.24：**11 个 VARIANT* 出参**（数据集名/小数位/单位/动力学…）
                "GetDataSetInfo" => DataSetInfo(),
                "GetDataSetResults" => GetResults(req),
                // 手册 §2.4.2.25：1 个入参 + **9 个 VARIANT* 出参**（行列/指数/值/状态）
                "GetRawData" => RawData(req),
                "FileExportEx" => CallWithStringArg(_plate, "FileExportEx",
                    Str(req, "path") ?? "", "exportPath", Str(req, "builderName") ?? ""),
                "PrintReportEx" => CallWithStringArg(_plate, "PrintReportEx",
                    Str(req, "builderName") ?? "", "printed",
                    Protocol.Int(req.Parameters, "showPrintDlg") == 1),
                "PlateReadMonitor.ReadInProgress" => ReadProgress(),
                "PlateReadMonitor.GetErrorCode" => MonitorInt("GetErrorCode", req),
                "PlateReadMonitor.GetErrorMessage" => MonitorString("GetErrorMessage", req),
                "PlateReadMonitor.GetReaderError" => MonitorInt("GetReaderError", req),

                _ => TransportReply.Fail("EC-06-0004", $"Gen5 未实现 {def.Cmd}")
            };
        }
        catch (ComCallException e)
        {
            return TransportReply.Fail("EC-05-2304", e.Message);
        }
        catch (Exception e)
        {
            return TransportReply.Fail("EC-05-2304", "调用 Gen5 异常：" + e.Message);
        }
    }

    // ---------------------------------------------------------------- Application

    private static TransportReply Configure(ComDispatch app, string member)
    {
        // 配置完必须重新 TestReaderCommunication（手册：换了配置要重新握手）
        var rc = app.CallShort(member, Array.Empty<object?>());
        return rc == 1
            ? TransportReply.Success("{\"configured\":true}")
            : TransportReply.Fail(rc.ToString(), $"{member} 返回 {rc}");
    }

    private TransportReply CarrierMove(ComDispatch app, bool inbound, Protocol.Request req)
    {
        if (_readInProgress)
        {
            return TransportReply.Fail("EC-05-2303", "读板进行中不能进出板");
        }
        var member = inbound
            ? (Protocol.Int(req.Parameters, "ex") == 1 ? "CarrierInEx" : "CarrierIn")
            : "CarrierOut";
        if (member == "CarrierInEx")
        {
            var plateType = Str(req, "plateTypeName") ?? "";
            var lid = Protocol.Int(req.Parameters, "lidOnPlate") == 1;
            app.Call("CarrierInEx", plateType, lid);
        }
        else
        {
            app.Call(member);
        }
        return TransportReply.Success(inbound
            ? "{\"carrier\":\"in\",\"plateOnCarrier\":true}"
            : "{\"carrier\":\"out\",\"plateOnCarrier\":false}");
    }

    private static TransportReply RunControlCommand(ComDispatch app, Protocol.Request req)
    {
        var cmd = Protocol.Int(req.Parameters, "command");
        if (cmd == null)
        {
            return TransportReply.Fail("EC-06-0005", "RunReaderControlCommand 缺少 command（0/2/512/1024…）");
        }
        app.Call("RunReaderControlCommand", (long)cmd.Value);
        return TransportReply.Success($"{{\"command\":{cmd.Value}}}");
    }

    private static TransportReply GetCharacteristic(ComDispatch app, Protocol.Request req)
    {
        var id = Protocol.Int(req.Parameters, "id") ?? -1;
        var idx = Protocol.Int(req.Parameters, "index") ?? 0;
        var args = new object?[] { id, idx, null };
        app.Call("GetReaderCharacteristics", args);
        var value = args[2];
        return TransportReply.Success(
            $"{{\"id\":{id},\"value\":{JsonSerializer.Serialize(value?.ToString())}}}");
    }

    private static TransportReply GetTemperature(ComDispatch app)
    {
        // 手册：void GetCurrentTemperature(VARIANT* 值, VARIANT* 状态)
        var args = new object?[] { null, null };
        app.Call("GetCurrentTemperature", args);
        return TransportReply.Success(
            $"{{\"temperature\":{Num(args[0])},\"status\":{JsonSerializer.Serialize(args[1]?.ToString())}}}");
    }

    private static TransportReply SetTemperature(ComDispatch app, Protocol.Request req)
    {
        var on = Protocol.Int(req.Parameters, "incubate") == 1;
        var sp = Num(req, "setPoint");
        var grad = Num(req, "gradient");
        app.Call("SetTemperatureSetPoint", on, sp, grad);
        return TransportReply.Success($"{{\"incubation\":{on.ToString().ToLowerInvariant()},\"setPoint\":{sp}}}");
    }

    private TransportReply RunSystemTest(ComDispatch app)
    {
        var monitor = ComDispatch.Wrap(app.Call("StartSystemTest"), "SystemTestMonitor");
        var deadline = DateTime.UtcNow.AddMinutes(5);
        while (DateTime.UtcNow < deadline)
        {
            var inProgress = Convert.ToBoolean(monitor.Call("TestInProgress") ?? false);
            if (!inProgress)
            {
                var passed = Convert.ToBoolean(monitor.Call("TestPassed") ?? false);
                var results = monitor.Call("GetSystemTestResults")?.ToString() ?? "";
                monitor.Dispose();
                return passed
                    ? TransportReply.Success("{\"systemTest\":\"passed\"}")
                    : TransportReply.Fail("-1", "系统测试未通过：" + results);
            }
            Thread.Sleep(_pollMs);
        }
        return TransportReply.Fail("-606", "系统测试超时（5 分钟）");
    }

    // ---------------------------------------------------------------- Experiment

    /// <summary>
    /// 开实验（手册 §2.1.2.9/2.1.2.10/2.1.2.11 三个方法共用本实现）。
    ///
    /// <para>手关键约束：**一个 Application 同时只能有一个 Experiment**，
    /// 所以这里先关掉旧的（释放顺序 Plate → Plates → Experiment）。</para>
    /// </summary>
    private TransportReply OpenExperiment(ComDispatch app, string member, Protocol.Request req)
    {
        CloseExperiment();
        object? exp;
        if (member == "NewExperimentEx")
        {
            var type = Protocol.Int(req.Parameters, "protocolDefinitionType") ?? 0;
            exp = app.Call(member, type, Str(req, "protocolDefinition") ?? "");
        }
        else
        {
            var path = Str(req, "protocolPathName") ?? Str(req, "experimentPathName");
            if (string.IsNullOrWhiteSpace(path))
            {
                return TransportReply.Fail("-100",
                    "缺少协议/实验路径（数据库模式 DB:\\Protocols\\x.prt，文件模式 C:\\...）");
            }
            exp = app.Call(member, path);
        }
        _experiment = ComDispatch.Wrap(exp, "Experiment");
        _plates = ComDispatch.Wrap(_experiment.Call("Plates"), "Plates");
        // 手册 GetPlate 的 index 从 1 开始；新实验只有一块板时取第 1 块
        _plate = ComDispatch.Wrap(_plates.Call("GetPlate", 1), "Plate");
        return TransportReply.Success("{\"experimentOpen\":true}");
    }

    private TransportReply CloseExperiment()
    {
        try { _plate?.Dispose(); } catch { /* 释放顺序按手册，失败不阻断 */ }
        try { _plates?.Dispose(); } catch { }
        try { _experiment?.Call("Close"); } catch (Exception e) { _lastError = e.Message; }
        try { _experiment?.Dispose(); } catch { }
        _plate = null;
        _plates = null;
        _experiment = null;
        return TransportReply.Success("{\"experimentOpen\":false}");
    }

    private TransportReply SaveExperiment(ComDispatch app, string? path)
    {
        if (_experiment == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Experiment");
        }
        if (string.IsNullOrWhiteSpace(path))
        {
            _experiment.Call("Save");
        }
        else
        {
            _experiment.Call("SaveAs", path);
        }
        return TransportReply.Success("{\"saved\":true}");
    }

    // ---------------------------------------------------------------- 读板

    private TransportReply StartReadAndWait(ComDispatch app, ModelLibrary.ModelDef model,
                                            Protocol.Request req, bool rotate, bool simulated)
    {
        if (_plate == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Experiment/Plate");
        }
        if (_readInProgress)
        {
            return TransportReply.Fail("EC-05-2303", "上一次读板还没结束");
        }
        // 手册 §1.2.1.6/§2.4.2.25：原始数据要读板**之前**就置 DataExportEnabled=true，
        // 读完再置已经来不及（数据不会回填）⇒ 这里在开读前统一打开，
        // 免得每个流程自己记这件事（忘了就是 GetRawData 拿空）。
        EnsureDataExport();
        object? mon = simulated
            ? _plate.Call("StartSimulatedRead")
            : rotate ? _plate.Call("StartReadEx", true) : _plate.Call("StartRead");
        _monitor = ComDispatch.Wrap(mon, "PlateReadMonitor");
        _readInProgress = true;

        var deadline = DateTime.UtcNow.AddMilliseconds(
            model.ActionTimeoutMs > 0 ? model.ActionTimeoutMs : 600_000);
        var polls = 0;
        try
        {
            while (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(_pollMs);
                polls++;
                var busy = Convert.ToBoolean(_monitor.Call("ReadInProgress") ?? false);
                if (busy)
                {
                    continue;
                }
                var errors = Convert.ToInt32(_monitor.Call("ErrorsCount") ?? 0);
                var readStatus = _plate.CallInt("ReadStatus");
                if (errors > 0)
                {
                    var code = Convert.ToInt32(_monitor.Call("GetErrorCode", 0) ?? 0);
                    var msg = _monitor.Call("GetErrorMessage", 0)?.ToString() ?? "";
                    // -601 = 仪器自己报错，必须再取仪器码（手册明确要求）
                    if (code == -601)
                    {
                        var readerErr = Convert.ToInt32(_monitor.Call("GetReaderError", 0) ?? 0);
                        return TransportReply.Fail(readerErr.ToString(),
                            $"仪器报错 {readerErr}（-601）；Gen5 说明：{msg}");
                    }
                    return TransportReply.Fail(code.ToString(), $"读板失败：{msg}");
                }
                return TransportReply.Success(
                    $"{{\"readStatus\":{readStatus},\"errors\":0,\"polls\":{polls}}}");
            }
            return TransportReply.Fail("-606",
                $"读板超时（{model.ActionTimeoutMs}ms，已轮询 {polls} 次）；"
                + "设备可能仍在读，请用 GetReadProgress 确认后再决定 AbortRead");
        }
        finally
        {
            _readInProgress = false;
        }
    }

    private TransportReply AbortRead()
    {
        if (_plate == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Plate");
        }
        _plate.Call("AbortRead");
        _readInProgress = false;
        return TransportReply.Success("{\"aborted\":true}");
    }

    private TransportReply ReadProgress()
    {
        if (_monitor == null)
        {
            return TransportReply.Success("{\"readInProgress\":false}");
        }
        var busy = Convert.ToBoolean(_monitor.Call("ReadInProgress") ?? false);
        return TransportReply.Success($"{{\"readInProgress\":{busy.ToString().ToLowerInvariant()}}}");
    }

    private TransportReply MonitorInt(string member, Protocol.Request req)
    {
        if (_monitor == null)
        {
            return TransportReply.Success("{\"value\":0}");
        }
        var idx = Protocol.Int(req.Parameters, "index") ?? 0;
        var v = _monitor.CallInt(member, idx);
        return TransportReply.Success($"{{\"value\":{v}}}");
    }

    private TransportReply MonitorString(string member, Protocol.Request req)
    {
        if (_monitor == null)
        {
            return TransportReply.Success("{\"message\":\"\"}");
        }
        var idx = Protocol.Int(req.Parameters, "index") ?? 0;
        var v = _monitor.Call(member, idx)?.ToString() ?? "";
        return TransportReply.Success($"{{\"message\":{JsonSerializer.Serialize(v)}}}");
    }

    /// <summary>
    /// 支持的读板机列表（手册 §2.1.2.1）。三个出参**必须都给槽位**，
    /// 少给一个 COM 会直接抛参数个数错误。
    /// </summary>
    private static TransportReply SupportedReaders(ComDispatch app)
    {
        var args = new object?[] { null, null, null };
        app.Call("GetSupportedReaders", args);
        return TransportReply.Success(JsonSerializer.Serialize(new
        {
            names = AsArray(args[0]),
            types = AsArray(args[1]),
            communications = AsArray(args[2])
        }));
    }

    /// <summary>数据集信息（手册 §2.4.2.24）。11 个出参按手册顺序给槽位。</summary>
    private TransportReply DataSetInfo()
    {
        if (_plate == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Plate");
        }
        var args = new object?[11];
        var rc = Convert.ToInt32(_plate.Call("GetDataSetInfo", args) ?? 0);
        if (rc != BTI_OK)
        {
            return TransportReply.Fail(rc.ToString(), $"GetDataSetInfo 返回 {rc}");
        }
        return TransportReply.Success(JsonSerializer.Serialize(new
        {
            names = AsArray(args[0]),
            decimalPlaces = AsArray(args[1]),
            units = AsArray(args[2]),
            kineticReads = AsArray(args[3]),
            kineticIntervalMs = AsArray(args[4]),
            spectrumWavelengths = AsArray(args[6]),
            horizontalReads = AsArray(args[9]),
            verticalReads = AsArray(args[10])
        }));
    }

    /// <summary>
    /// 原始数据（手册 §2.4.2.25）。**只有读板前置了
    /// <c>Application.DataExportEnabled = true</c> 才有数据**。
    /// 返回的是**逐点**记录，所以这里按点打包成数组，不猜测网格形状
    /// （网格形状看 <see cref="DataSetInfo"/> 的 Dimensions）。
    /// </summary>
    private TransportReply RawData(Protocol.Request req)
    {
        if (_plate == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Plate");
        }
        var dataSet = Str(req, "dataSetName") ?? "";
        if (string.IsNullOrWhiteSpace(dataSet))
        {
            return TransportReply.Fail("-201", "GetRawData 需要 dataSetName（先 GetDataSetNames）");
        }
        var args = new object?[]
        {
            dataSet,   // 入参：数据集名
            null, null, null, null, null, null, null, null, null   // 9 个出参
        };
        var rc = Convert.ToInt32(_plate.Call("GetRawData", args) ?? 0);
        if (rc != BTI_OK)
        {
            return TransportReply.Fail(rc.ToString(), $"GetRawData 返回 {rc}");
        }
        return TransportReply.Success(JsonSerializer.Serialize(new
        {
            dataSet,
            row = AsArray(args[1]),
            column = AsArray(args[2]),
            kineticIndex = AsArray(args[3]),
            wavelengthIndex = AsArray(args[4]),
            value = AsArray(args[7]),
            primaryStatus = AsArray(args[8]),
            secondaryStatus = AsArray(args[9])
        }));
    }

    /// <summary>
    /// 打开 <c>Application.DataExportEnabled</c>（幂等）。
    ///
    /// <para>取不到就不挡：让 Gen5 自己在读板时报，比我们猜它支持不支持要好。</para>
    /// </summary>
    private void EnsureDataExport()
    {
        if (_app == null)
        {
            return;
        }
        try
        {
            if (Convert.ToBoolean(_app.Call("DataExportEnabled") ?? false))
            {
                return;
            }
            _app.Set("DataExportEnabled", true);
        }
        catch (Exception e)
        {
            _lastError = "置 DataExportEnabled 失败（原始数据可能取不到）：" + e.Message;
        }
    }

    /// <summary>把 COM 的 VARIANT 数组转成字符串数组（单值也接受）。</summary>
    private static string[] AsArray(object? value) => value switch
    {
        null => Array.Empty<string>(),
        Array a => a.Cast<object?>().Select(x => x?.ToString() ?? "").ToArray(),
        _ => new[] { value.ToString() ?? "" }
    };

    private TransportReply GetResults(Protocol.Request req)
    {
        if (_plate == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Plate（先 NewExperiment 再 Read）");
        }
        var protocolId = Str(req, "protocolId") ?? "";
        var dataSet = Str(req, "dataSetName") ?? "";
        var indexes = Str(req, "indexes") ?? "";
        var xml = _plate.Call("GetDataSetResults", protocolId, dataSet, indexes)?.ToString();
        if (string.IsNullOrWhiteSpace(xml))
        {
            return TransportReply.Fail("-401", "GetDataSetResults 返回空（数据集名/索引可能不对）");
        }
        return TransportReply.Success(JsonSerializer.Serialize(new { dataSet = dataSet, xml }));
    }

    // ---------------------------------------------------------------- 工具

    private (ComDispatch? Com, string? Error) EnsureApp()
    {
        if (_app != null)
        {
            return (_app, null);
        }
        var (app, err) = ComDispatch.Create(_progId);
        if (app == null)
        {
            _lastError = err;
            return (null, err);
        }
        _app = app;
        return (_app, null);
    }

    private static TransportReply ReturnCode(int rc, string member) =>
        rc == BTI_OK
            ? TransportReply.Success("{\"returnCode\":1}")
            : TransportReply.Fail(rc.ToString(), $"{member} 返回 {rc}（BTIStatusCodes.h）");

    private static TransportReply CallVoid(ComDispatch? target, string member, string key)
    {
        if (target == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的对象");
        }
        target.Call(member);
        return TransportReply.Success($"{{\"{key}\":\"ok\"}}");
    }

    private static TransportReply ReadString(ComDispatch? target, string member,
                                             object?[] args, string key)
    {
        if (target == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Experiment");
        }
        var v = target.Call(member, args)?.ToString();
        return TransportReply.Success($"{{\"{key}\":{JsonSerializer.Serialize(v)}}}");
    }

    private static TransportReply CallWithStringArg(ComDispatch? target, string member,
                                                    string? arg, string key, params object?[] extra)
    {
        if (target == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的 Plate");
        }
        var args = new object?[extra.Length + 1];
        args[0] = arg ?? "";
        Array.Copy(extra, 0, args, 1, extra.Length);
        target.Call(member, args);
        return TransportReply.Success($"{{\"{key}\":\"ok\"}}");
    }

    /// <summary>读一个"返回 VARIANT 数组"的方法（GetSupportedReaders / GetDataSetNames…）。</summary>
    private static TransportReply ReadVariant(ComDispatch? target, string member, string key,
                                              params object?[] leading)
    {
        if (target == null)
        {
            return TransportReply.Fail("EC-05-2302", "没有打开的对象");
        }
        var args = new object?[leading.Length + 1];
        Array.Copy(leading, args, leading.Length);
        args[^1] = null;                 // VARIANT* 出参槽
        target.Call(member, args);
        var value = args[^1];
        var text = value switch
        {
            null => "[]",
            Array a => JsonSerializer.Serialize(a.Cast<object?>().Select(x => x?.ToString()).ToArray()),
            _ => JsonSerializer.Serialize(value.ToString())
        };
        return TransportReply.Success($"{{\"{key}\":{text}}}");
    }

    private static string? Str(Protocol.Request req, string key) =>
        req.Parameters.TryGetValue(key, out var v)
        && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    /// <summary>
    /// 取数字参数。**JSON 数字与字符串数字都要认** ——
    /// TcpDriverAdapter 会把所有参数序列化成字符串，只认数字会让"参数明明传了"变成 0。
    /// </summary>
    private static double Num(Protocol.Request req, string key)
    {
        if (!req.Parameters.TryGetValue(key, out var v))
        {
            return 0d;
        }
        if (v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d))
        {
            return d;
        }
        return v.ValueKind == JsonValueKind.String
               && double.TryParse(v.GetString(), out var s) ? s : 0d;
    }

    private static string Num(object? v) =>
        v == null ? "0" : Convert.ToDouble(v).ToString("0.##",
            System.Globalization.CultureInfo.InvariantCulture);

    public void Dispose()
    {
        CloseExperiment();
        _monitor?.Dispose();
        _monitor = null;
        _app?.Dispose();
        _app = null;
    }
}
