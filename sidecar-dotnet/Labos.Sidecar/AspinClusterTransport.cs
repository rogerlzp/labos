using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Labos.Sidecar;

/// <summary>
/// ASpin 2K 离心机：**不是发字节，是调 Windows 的 Cluster.dll**。
///
/// <para>手册 §0 给的接口（Delphi）：</para>
/// <code>
///   TSendData = function(AInCmd: PChar; ASizeIn: Integer;
///                        AOutResult: PChar; var ASizeOut: Integer): Integer; stdcall;
/// </code>
/// <para>成功返回 1，失败返回 0，错误在 AOutResult 的 XML 里。</para>
///
/// <para>指令是 XML，外壳固定：</para>
/// <code>
///   &lt;DispatchCmd&gt;
///     &lt;strNodeName&gt;PlateCtri&lt;/strNodeName&gt;
///     &lt;strCmdName&gt;Start&lt;/strCmdName&gt;
///     &lt;lpCmdArgs&gt;...&lt;/lpCmdArgs&gt;
///   &lt;/DispatchCmd&gt;
/// </code>
///
/// <para>
/// 参数 XML 模板**从型号库读**（<c>args</c> 字段里已经带 <c>{time}/\{speed}/\ {ramp}</c> 占位符），
/// 这样手册更新时只改 Java 侧，sidecar 不用跟着改。
/// </para>
///
/// <para>
/// <b>平台限制</b>：Cluster.dll 是 32 位 Windows DLL，所以 sidecar 必须以
/// <c>x86</c> 或 <c>AnyCPU(32 位优先)</c> 运行在 Windows 上。本类在非 Windows 上
/// 只在真正调用时报错，便于在 Mac 上把整条 TCP 链路跑通（用 sim 档）。
/// </para>
/// </summary>
public sealed class AspinClusterTransport : ITransport
{
    private readonly string _dllPath;
    private readonly string _nodeName;
    private readonly object _lock = new();
    private bool _initialized;

    [DllImport("Cluster.dll", CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Ansi)]
    private static extern int SendData(string aInCmd, int aSizeIn, StringBuilder aOutResult, ref int aSizeOut);

    [DllImport("Cluster.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern void PcInitEx();

    [DllImport("Cluster.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern void PcUnInitEx();

    public AspinClusterTransport(string dllPath, string nodeName)
    {
        _dllPath = dllPath;
        _nodeName = nodeName;
    }

    public string Name => $"Cluster.dll({_dllPath}) node={_nodeName}";

    public TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model)
    {
        var def = model.Find(req.Method);
        if (def == null)
        {
            return TransportReply.Fail("EC-06-0004", $"型号 {model.Id} 没有指令 {req.Method}（手册里没有）");
        }
        if (string.Equals(def.Cmd, "GetStatus", StringComparison.OrdinalIgnoreCase))
        {
            // 手册没有状态查询。CurrSpeed/CurrTime 是真正的状态来源。
            return TransportReply.Success("{\"connected\":1,\"source\":\"sidecar\"}");
        }
        if (!OperatingSystem.IsWindows())
        {
            return TransportReply.Fail("EC-05-2001",
                "Cluster.dll 只能在 Windows 上加载（请在工控机上跑 sidecar，开发机用 --transport sim）");
        }

        var xml = BuildDispatchXml(def, req);
        lock (_lock)
        {
            try
            {
                EnsureInit();
                var outBuf = new StringBuilder(4096);
                var outSize = outBuf.Capacity;
                var rc = SendData(xml, xml.Length, outBuf, ref outSize);
                var resultXml = outBuf.ToString();

                if (rc != 1)
                {
                    // 手册：失败时错误在 AOutResult 的 XML 里
                    return TransportReply.Fail("EC-05-2001",
                        string.IsNullOrWhiteSpace(resultXml) ? "SendData 返回 0（无错误详情）" : resultXml);
                }
                return ParseResult(def, resultXml);
            }
            catch (DllNotFoundException)
            {
                return TransportReply.Fail("EC-05-2001",
                    $"加载不到 {_dllPath}：确认 Cluster.dll 在 PATH 或工作目录，且位数与进程一致（32 位）");
            }
            catch (Exception e)
            {
                return TransportReply.Fail("EC-05-2001", "调用 Cluster.dll 异常：" + e.Message);
            }
        }
    }

    /// <summary>拼 &lt;DispatchCmd&gt; 外壳；节点名从型号库的参数模板无法推断，按手册固定。</summary>
    private string BuildDispatchXml(ModelLibrary.MethodDef def, Protocol.Request req)
    {
        // 手册示例里 PcCreateModules 的节点名是 Cluster，其余是 PlateCtri
        var node = string.Equals(def.Cmd, "PcCreateModules", StringComparison.OrdinalIgnoreCase)
            ? "Cluster" : _nodeName;

        var args = def.Args ?? "";
        foreach (var key in new[] { "time", "speed", "ramp", "mode" })
        {
            var v = Protocol.Int(req.Parameters, key);
            args = args.Replace("{" + key + "}", (v ?? 0).ToString());
        }

        return $"<DispatchCmd><strNodeName>{node}</strNodeName>"
             + $"<strCmdName>{def.Cmd}</strCmdName>"
             + $"<lpCmdArgs>{args}</lpCmdArgs>"
             + "</DispatchCmd>";
    }

    /// <summary>
    /// 解析回包 XML。手册只说"结果在 nRetValue 里"，
    /// 所以这里**宽松提取**：有 nRetValue 就回它，没有就把整段 XML 作为 data 带回，
    /// 不做"猜字段"的事（猜错了比不回更糟）。
    /// </summary>
    private static TransportReply ParseResult(ModelLibrary.MethodDef def, string xml)
    {
        if (string.IsNullOrWhiteSpace(xml))
        {
            return TransportReply.Success("{}");
        }
        var err = Regex.Match(xml, @"<(?<tag>\w*[Ee]rror\w*)>(?<v>[^<]*)</\k<tag>>");
        if (err.Success && !string.IsNullOrWhiteSpace(err.Groups["v"].Value))
        {
            return TransportReply.Fail("EC-05-2001", err.Groups["v"].Value);
        }

        var n = Regex.Match(xml, @"nRetValue\s*=\s*""(?<v>-?\d+)""");
        if (!n.Success)
        {
            n = Regex.Match(xml, @"<nRetValue>(?<v>-?\d+)</nRetValue>");
        }
        if (n.Success && int.TryParse(n.Groups["v"].Value, out var value))
        {
            return TransportReply.Success($"{{\"nRetValue\":{value}}}");
        }
        return TransportReply.Success(JsonSerializer.Serialize(new { raw = xml }));
    }

    private void EnsureInit()
    {
        if (_initialized)
        {
            return;
        }
        // 手册：先 PcInitEx() 再 SendData；退出时 PcUnInitEx()
        PcInitEx();
        _initialized = true;
    }

    public void Dispose()
    {
        if (!_initialized || !OperatingSystem.IsWindows())
        {
            return;
        }
        try { PcUnInitEx(); } catch { /* 退出阶段，忽略 */ }
        _initialized = false;
    }
}
