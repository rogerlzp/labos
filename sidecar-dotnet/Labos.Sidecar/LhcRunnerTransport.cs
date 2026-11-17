using System.Text;

namespace Labos.Sidecar;

/// <summary>
/// BioTek LHC 洗板/分液机：**调 COM 组件 <c>BTILHCRunner.dll</c>**。
///
/// <para>手册：<c>LHC Runner User Application Interface Specification 5336208-SP Rev G</c>
/// （我方解读：<c>docs/设备/洗板机-BioTek-LHC.md</c>）。</para>
///
/// <para><b>为什么不是"发字节"</b>：这台设备的通讯细节全被 LHC 软件封装了，
/// 对外只有 29 个方法（SetProductName/LoadProtocol/RunProtocol/…）。
/// 所以本传输要做的不是拼帧，而是
/// <b>① 按手册顺序调方法 ② 把返回值翻译成"成功/失败+厂商码" ③ 把轮询收敛成一次阻塞调用</b>。</para>
///
/// <para><b>两个必须自己补的语义</b>（手册只给了原料）：</para>
/// <list type="number">
///   <item><b>RunProtocol 只是"启动"</b>：手册要求调用方轮询 <c>LHC_GetProtocolStatus</c>
///         直到 eReady/eNotReady/eError。中控的一条节点命令是"跑完才算完"，
///         所以 <see cref="RunProtocolBlocking"/> 把"启动+轮询"收成一个回包；
///         同时保留 <c>GetProtocolStatus</c> 这个非阻塞方法给需要自己控节奏的流程。</item>
///   <item><b>Abort 之前必须 Pause</b>（手册 §4.3）。本传输不替调用方补 Pause ——
///         补了会把"流程写错"掩盖成"设备慢"；只把厂商码原样带回。</item>
/// </list>
///
/// <para><b>厂商示例代码的坑</b>：BioTek 提供的 <c>ClassLHCRunnerWrapper.cs</c> 里
/// <c>LHC_AbortProtocol()</c> 的函数体误写成调 <c>LHC_PauseProtocol()</c>。
/// 照抄那份样例会变成"点了中止，设备只是暂停" —— 这里直接调真正的成员名，不用他们的包装。</para>
///
/// <para><b>平台</b>：COM 只在 Windows；非 Windows 返回
/// <c>EC-05-2208</c>，不假装连上。</para>
/// </summary>
public sealed class LhcRunnerTransport : ITransport, ILinkProbe
{
    /// <summary>手册 §6.2 Runner_ReturnCode。</summary>
    private const short E_OK = 1;

    /// <summary>手册 §6.4 RunStatus。</summary>
    private const int ST_READY = 1;
    private const int ST_NOT_READY = 2;
    private const int ST_BUSY = 3;
    private const int ST_ERROR = 4;
    private const int ST_PAUSED = 7;

    private readonly string _progId;
    private readonly int _pollMs;
    private ComDispatch? _com;
    private string? _lastError;

    public LhcRunnerTransport(string progId = "BTILHCRunner.ClassLHCRunner", int pollMs = 500)
    {
        _progId = progId;
        // 手册 §6.4 原话："There is no benefit to polling more frequently than once every 500 msec"
        _pollMs = Math.Max(500, pollMs);
    }

    public string Name => $"LHC Runner COM({_progId})";

    /// <summary>上次链路错误（<see cref="ILinkProbe"/> 之外的排障信息）。</summary>
    public string? LastError => _lastError;

    public bool TryEnsureLink()
    {
        var (com, err) = EnsureCom();
        _lastError = err;
        if (com == null)
        {
            return false;
        }
        try
        {
            // 用 TestCommunications 探活（"仪器在不在"），但**不改产品名** ——
            // 有些机型 TestCommunications 之前必须先 SetProductName，
            // 所以这里的探活失败不算"链路死"，只算"还没初始化"，仍返回 true。
            com.CallShort("LHC_TestCommunications");
            return true;
        }
        catch (ComCallException e)
        {
            _lastError = e.Message;
            // 组件在、只是仪器没准备好 ⇒ 链路对象本身是活的
            return true;
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
        var (com, err) = EnsureCom();
        if (com == null)
        {
            return TransportReply.Fail("EC-05-2208", err ?? "LHC Runner 组件不可用");
        }

        try
        {
            return def.Cmd switch
            {
                "LHC_RunProtocol" => RunProtocolBlocking(com, model),
                "LHC_GetProtocolStatus" => DescribeStatus(com.CallInt("LHC_GetProtocolStatus")),
                "LHC_RunVerifyManifoldTest" => StartManifoldTest(com),
                "LHC_GetVerifyManifoldRunStatus" => DescribeStatus(com.CallInt("LHC_GetVerifyManifoldRunStatus")),
                "LHC_GetVerifyManifoldTestResults" =>
                    TransportReply.Success(Json("PASSED", com.CallString("LHC_GetVerifyManifoldTestResults"))),
                "LHC_CreateVerifyManifoldTestReport" =>
                    TransportReply.Success(Json("report",
                        com.CallString("LHC_CreateVerifyManifoldTestReport"))),
                "LHC_GetProductName" => TransportReply.Success(
                    Json("productName", com.CallWithRefString("LHC_GetProductName"))),
                "LHC_GetProductSerialNumber" => TransportReply.Success(
                    Json("serialNumber", com.CallWithRefString("LHC_GetProductSerialNumber"))),
                "LHC_GetWellCount" => WellCount(com),
                "LHC_GetOnBoardProtocolCount" => OnBoardCount(com),
                "LHC_GetOnBoardProtocolName" => TransportReply.Success(Json("protocolName",
                    com.CallString("LHC_GetOnBoardProtocolName",
                        (short)(Protocol.Int(req.Parameters, "index") ?? 0)))),
                "LHC_GetLastErrorCode" => LastErrorCode(com),
                "LHC_GetErrorString" => TransportReply.Success(Json("message",
                    com.CallString("LHC_GetErrorString",
                        (short)(Protocol.Int(req.Parameters, "errorCode") ?? 0)))),
                _ => CallReturnCode(com, def, req)
            };
        }
        catch (ComCallException e)
        {
            return TransportReply.Fail("EC-05-2208", e.Message);
        }
        catch (Exception e)
        {
            return TransportReply.Fail("EC-05-2208", "调用 LHC Runner 异常：" + e.Message);
        }
    }

    // ---------------------------------------------------------------- 调用形态

    /// <summary>普通方法：返回 Int16，1=eOK，其它即 Runner_ReturnCode 故障码。</summary>
    private static TransportReply CallReturnCode(ComDispatch com, ModelLibrary.MethodDef def,
                                                 Protocol.Request req)
    {
        object?[] args = def.Cmd switch
        {
            "LHC_SetProductName" => new object?[] { Str(req, "productName") ?? "" },
            "LHC_SetCommunications" => new object?[] { Str(req, "port") ?? "" },
            "LHC_LoadProtocolFromFile" => new object?[] { Str(req, "protocolPath") ?? "" },
            "LHC_LoadProtocolFromFlash" => new object?[] { Str(req, "protocolName") ?? "" },
            // 手册 §5.18/5.19/5.27：0/1 两个取值
            "LHC_OverrideValidation" => new object?[] { (short)(Protocol.Int(req.Parameters, "override") ?? 0) },
            // 中控侧可能传 vacPumpOn，也可能传更短的 on（历史流程）—— 两种都认
            "LHC_LeaveVacuumPumpOn" => new object?[] { VacPumpOn(req) },
            "LHC_SetRunnerThreading" => new object?[] { (short)(Protocol.Int(req.Parameters, "threadingModel") ?? 0) },
            // 手册 §5.26：bool bGetSettingsFirst，建议恒 true
            "LHC_ValidateProtocol" => new object?[] { Protocol.Int(req.Parameters, "getSettingsFirst") != 0 },
            // 手册 §5.28/5.29：byte，仅 50 TS
            "LHC_SetFirstStrip" => new object?[] { (byte)(Protocol.Int(req.Parameters, "firstStrip") ?? 1) },
            "LHC_SetNumberOfStrips" => new object?[] { (byte)(Protocol.Int(req.Parameters, "numberOfStrips") ?? 12) },
            _ => Array.Empty<object?>()
        };

        var rc = com.CallShort(def.Cmd, args);
        return rc == E_OK
            ? TransportReply.Success("{\"returnCode\":1}")
            : TransportReply.Fail(rc.ToString(), $"{def.Cmd} 返回 {rc}（{RunnerCodeText(rc)}）");
    }

    /// <summary>
    /// 启动协议并**阻塞到结束**（中控的一条命令 = 一次完整的洗板）。
    ///
    /// <para>判据严格照手册 §6.4：只有 <c>eBusy(3)</c> 才算启动成功；
    /// 其它值一律"没启动"，直接失败，<b>不进轮询</b>（否则会白等到超时）。</para>
    /// </summary>
    private TransportReply RunProtocolBlocking(ComDispatch com, ModelLibrary.ModelDef model)
    {
        var started = com.CallInt("LHC_RunProtocol");
        if (started != ST_BUSY)
        {
            var code = com.CallShort("LHC_GetLastErrorCode");
            var text = SafeErrorString(com, code);
            return TransportReply.Fail(code.ToString(),
                $"RunProtocol 返回 RunStatus={started}（{RunStatusText(started)}），协议未启动；"
                + $"GetLastErrorCode={code} {text}");
        }

        var deadline = DateTime.UtcNow.AddMilliseconds(
            model.ActionTimeoutMs > 0 ? model.ActionTimeoutMs : 600_000);
        var polls = 0;
        while (true)
        {
            Thread.Sleep(_pollMs);
            polls++;
            var st = com.CallInt("LHC_GetProtocolStatus");
            switch (st)
            {
                case ST_READY:
                    return TransportReply.Success(
                        $"{{\"runStatus\":{st},\"runStatusText\":\"eReady\",\"polls\":{polls}}}");
                case ST_NOT_READY:
                    return TransportReply.Fail("EC-05-2201",
                        $"RunStatus=eNotReady：新步骤没能启动（已轮询 {polls} 次）");
                case ST_ERROR:
                {
                    var code = com.CallShort("LHC_GetLastErrorCode");
                    var text = SafeErrorString(com, code);
                    return TransportReply.Fail(code.ToString(),
                        $"协议运行中出错：GetLastErrorCode={code} {text}");
                }
                case ST_BUSY:
                case ST_PAUSED:
                    break;
                default:
                    // eUninitialized(0) / eIncomplete(6) 手册标"不应出现"
                    return TransportReply.Fail("EC-05-2203",
                        $"RunStatus={st}（{RunStatusText(st)}）：协议未正常结束");
            }

            if (DateTime.UtcNow > deadline)
            {
                // 超时后**不自动 Abort**：中止与否是现场决策，sidecar 只如实上报
                return TransportReply.Fail("EC-05-2210",
                    $"等待协议完成超时（{model.ActionTimeoutMs}ms，已轮询 {polls} 次）；"
                    + "设备可能仍在运行，请用 GetProtocolStatus 确认后再决定 Abort");
            }
        }
    }

    private static TransportReply StartManifoldTest(ComDispatch com)
    {
        var st = com.CallInt("LHC_RunVerifyManifoldTest");
        return st == ST_BUSY
            ? TransportReply.Success($"{{\"runStatus\":{st}}}")
            : TransportReply.Fail("EC-05-2201",
                $"RunVerifyManifoldTest 返回 RunStatus={st}（{RunStatusText(st)}），测试未启动");
    }

    private static TransportReply WellCount(ComDispatch com)
    {
        var n = com.CallInt("LHC_GetWellCount");
        if (n <= 0)
        {
            return TransportReply.Fail("EC-05-2205",
                "GetWellCount 返回 0：手册说这表示「板型未确定」，通常是还没 LoadProtocol");
        }
        return TransportReply.Success($"{{\"wellCount\":{n}}}");
    }

    private static TransportReply OnBoardCount(ComDispatch com)
    {
        var args = new object?[] { (short)0 };
        var rc = com.CallShort("LHC_GetOnBoardProtocolCount", args);
        if (rc != E_OK)
        {
            return TransportReply.Fail(rc.ToString(), $"GetOnBoardProtocolCount 返回 {rc}");
        }
        return TransportReply.Success($"{{\"count\":{Convert.ToInt32(args[0] ?? (short)0)}}}");
    }

    private static TransportReply LastErrorCode(ComDispatch com)
    {
        var code = com.CallShort("LHC_GetLastErrorCode");
        return TransportReply.Success(
            $"{{\"errorCode\":{code},\"message\":{Quote(SafeErrorString(com, code))}}}");
    }

    private static string SafeErrorString(ComDispatch com, short code)
    {
        try
        {
            return com.CallString("LHC_GetErrorString", code) ?? "";
        }
        catch (Exception)
        {
            return "（GetErrorString 取不到描述）";
        }
    }

    private static TransportReply DescribeStatus(int st) =>
        TransportReply.Success(
            $"{{\"runStatus\":{st},\"runStatusText\":\"{RunStatusText(st)}\"}}");

    // ---------------------------------------------------------------- 工具

    private (ComDispatch? Com, string? Error) EnsureCom()
    {
        if (_com != null)
        {
            return (_com, null);
        }
        var (com, err) = ComDispatch.Create(_progId);
        if (com == null)
        {
            _lastError = err;
            return (null, err);
        }
        _com = com;
        return (_com, null);
    }

    private static short VacPumpOn(Protocol.Request req) =>
        (short)(Protocol.Int(req.Parameters, "vacPumpOn")
                ?? Protocol.Int(req.Parameters, "on")
                ?? 0);

    private static string? Str(Protocol.Request req, string key) =>
        req.Parameters.TryGetValue(key, out var v) && v.ValueKind == System.Text.Json.JsonValueKind.String
            ? v.GetString() : null;

    private static string Json(string key, string? value) =>
        $"{{\"{key}\":{Quote(value)}}}";

    private static string Quote(string? s) => s == null ? "null" : System.Text.Json.JsonSerializer.Serialize(s);

    /// <summary>手册 §6.2 Runner_ReturnCode 的文字，写进错误文本给人看。</summary>
    private static string RunnerCodeText(short rc) => rc switch
    {
        0 => "eError",
        1 => "eOK",
        2 => "eRegistration_Failure（LHC 未注册）",
        3 => "eInterface_Failure（接口软件问题）",
        4 => "eInvalid_Product_Type（产品名不被识别）",
        5 => "eOpen_File_Error（协议文件打不开）",
        6 => "ePre_Run_Error（运行前校验失败）",
        _ => "IS_ReturnCode（仪器接口软件返回，手册未给码表）"
    };

    /// <summary>手册 §6.4 RunStatus 的文字。</summary>
    private static string RunStatusText(int s) => s switch
    {
        0 => "eUninitialized",
        1 => "eReady",
        2 => "eNotReady",
        3 => "eBusy",
        4 => "eError",
        5 => "eDone",
        6 => "eIncomplete",
        7 => "ePaused",
        8 => "eStopRequested",
        9 => "eStopping",
        10 => "eNotRequired",
        _ => "unknown"
    };

    public void Dispose()
    {
        _com?.Dispose();
        _com = null;
    }
}
