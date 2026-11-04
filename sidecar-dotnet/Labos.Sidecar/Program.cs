using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 设备 sidecar：**中控只认这一个端点，换设备只换传输**。
///
/// <para>
/// 中控侧的驱动配置一条就够（{@code tcp://工控机:8501}），
/// 它不知道对面是串口封膜机、还是 Cluster.dll 离心机 —— 这是"新接一台设备不改中控代码"
/// 的最后一公里：中控不知道设备型号，sidecar 知道。
/// </para>
///
/// <para><b>三种传输</b>：</para>
/// <list type="bullet">
///   <item><c>--transport sim</c>    转发给 Java 模拟器（工位上跑，不需要硬件）</item>
///   <item><c>--transport serial</c> S100 封膜机串口（12 字节帧）</item>
///   <item><c>--transport dll</c>    ASpin 2K 经 Cluster.dll 发 XML</item>
/// </list>
///
/// <para><b>常用命令</b>：</para>
/// <code>
///   # 工位：把 sidecar 挂在模拟器前面（等价于真机链路）
///   dotnet run -- --model s100 --transport sim --sim 127.0.0.1:19501 --port 8501
///
///   # 现场：真串口
///   Labos.Sidecar.exe --model s100 --transport serial --serial-port COM3 --baud 115200
///
///   # 调试：发一条就退出（不占用端口）
///   dotnet run -- --model s100 --transport sim --sim 127.0.0.1:19501 --once Seal
/// </code>
/// </summary>
public static class Program
{
    public static int Main(string[] args)
    {
        var opt = ParseArgs(args);

        if (opt.ContainsKey("list"))
        {
            var lib0 = ModelLibrary.Load(ModelsPath(opt));
            Console.WriteLine("已适配型号（sidecar 能直接接的）：");
            foreach (var m in lib0.Models)
            {
                Console.WriteLine($"  {m.Id,-14} {m.Type,-10} {m.Vendor} {m.Model}"
                                  + $"  [{m.Evidence}]  {m.Protocol} / {m.Transport}");
            }
            Console.WriteLine();
            Console.WriteLine("待适配（只有型号、没有手册）：");
            foreach (var pl in lib0.Planned)
            {
                Console.WriteLine($"  {pl.Id,-24} {pl.Type,-10} {pl.Model}  缺：{pl.Missing}");
            }
            return 0;
        }

        var modelId = Get(opt, "model");
        if (string.IsNullOrEmpty(modelId))
        {
            Console.Error.WriteLine("必须指定 --model（如 --model s100）。用 --list 看有哪些。");
            return 2;
        }

        var lib = ModelLibrary.Load(ModelsPath(opt));
        var model = lib.Get(modelId);
        if (model == null)
        {
            Console.Error.WriteLine($"未知型号 {modelId}。可用："
                + string.Join(", ", lib.Models.Select(m => m.Id)));
            return 2;
        }
        if (model.Evidence == "PLANNED")
        {
            Console.Error.WriteLine($"{modelId} 还在待适配清单里（没有手册），不能对接真机。");
            return 2;
        }

        var transportKind = Get(opt, "transport") ?? "sim";
        using var transport = BuildTransport(transportKind, opt);

        if (opt.ContainsKey("once"))
        {
            return RunOnce(Get(opt, "once")!, opt, model, transport);
        }

        var port = int.TryParse(Get(opt, "port"), out var p) ? p : 8501;
        var bind = Get(opt, "bind") ?? "0.0.0.0";
        Console.WriteLine($"型号库：{ModelsPath(opt)}（{lib.Models.Count} 个型号）");
        Console.WriteLine($"注意：错误码由中控侧翻译（设备码 → EC-xx），sidecar 只带回原始码。");

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
        new TcpServer(bind, port, model, transport).Run(cts.Token);
        return 0;
    }

    /// <summary>调试用：发一条命令、打印回包，不监听端口。</summary>
    private static int RunOnce(string spec, Dictionary<string, string> opt,
                               ModelLibrary.ModelDef model, ITransport transport)
    {
        // 形式：Seal   或   MoveTray{"value":10000}
        var method = spec;
        var paramsJson = "{}";
        var i = spec.IndexOf('{');
        if (i > 0)
        {
            method = spec[..i].Trim();
            paramsJson = spec[i..];
        }

        var raw = $"{{\"message_id\":\"once\",\"message_type\":1,\"method\":\"{method}\","
                + $"\"equipment_id\":\"{Get(opt, "device") ?? "SIDECAR-1"}\","
                + $"\"parameters\":{paramsJson}}}";
        var req = Protocol.Parse(raw)!;
        var r = transport.Send(req, model);
        Console.WriteLine(r.Ok
            ? $"OK   {r.DataJson}"
            : $"FAIL {r.VendorCode} {r.ErrorText}");
        return r.Ok ? 0 : 1;
    }

    private static ITransport BuildTransport(string kind, Dictionary<string, string> opt) =>
        kind.ToLowerInvariant() switch
        {
            "sim" => new SimBridgeTransport(
                Get(opt, "sim-host") ?? "127.0.0.1",
                int.TryParse(Get(opt, "sim-port"), out var sp) ? sp
                    : SimPort(Get(opt, "sim"))),
            "serial" => new S100SerialTransport(
                Get(opt, "serial-port") ?? throw new ArgumentException("--transport serial 需要 --serial-port COM3"),
                int.TryParse(Get(opt, "baud"), out var b) ? b : 115200,
                int.TryParse(Get(opt, "read-ms"), out var rm) ? rm : 3000),
            "dll" => new AspinClusterTransport(
                Get(opt, "dll") ?? "Cluster.dll",
                Get(opt, "node") ?? "PlateCtri"),
            _ => throw new ArgumentException($"未知传输 {kind}（sim / serial / dll）"),
        };

    private static int SimPort(string? v)
    {
        if (string.IsNullOrEmpty(v)) return 19501;
        var i = v.LastIndexOf(':');
        return i >= 0 && int.TryParse(v[(i + 1)..], out var p) ? p : 19501;
    }

    private static string ModelsPath(Dictionary<string, string> opt) =>
        Get(opt, "models") ?? Path.Combine(AppContext.BaseDirectory, "models.json");

    private static string? Get(Dictionary<string, string> opt, string key) =>
        opt.TryGetValue(key, out var v) ? v : null;

    /// <summary>解析 --key value / --flag 两种形式。</summary>
    private static Dictionary<string, string> ParseArgs(string[] args)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < args.Length; i++)
        {
            var a = args[i];
            if (!a.StartsWith("--"))
            {
                continue;
            }
            var key = a[2..];
            if (i + 1 < args.Length && !args[i + 1].StartsWith("--"))
            {
                d[key] = args[++i];
            }
            else
            {
                d[key] = "";
            }
        }
        return d;
    }
}
