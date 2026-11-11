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

        // --link-only：**只做长连接保活**（维持到设备的连接 + 把状态写进 Redis），
        // 不监听端口、不需要型号知识。
        //
        // 为什么要有这一档：在线状态的权威是 Redis，写入方是 tcp client。
        // 但型号库里只有能对接真机的那几个型号（s100 / aspin-2k），
        // 而**每一台**设备都需要一个 tcp client 才有"在线"这回事 ——
        // 让没有型号的设备也必须先学会 s100 的指令集，是把"上报在线"和
        // "转发命令"两件事耦死了。这一档把两件事拆开：
        //   保活（任何设备都能做，不需要型号） vs 转发命令（需要型号）。
        bool linkOnly = opt.ContainsKey("link-only");

        var modelId = Get(opt, "model");
        ModelLibrary.ModelDef? model = null;
        var lib = ModelLibrary.Load(ModelsPath(opt));
        if (!linkOnly)
        {
            if (string.IsNullOrEmpty(modelId))
            {
                Console.Error.WriteLine("必须指定 --model（如 --model s100）。用 --list 看有哪些。");
                return 2;
            }

            model = lib.Get(modelId);
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
        }

        var transportKind = Get(opt, "transport") ?? "sim";
        using var transport = BuildTransport(transportKind, opt);

        // 认领设备：**设备ID / 设备IP / 端口全部取自中控的设备清单**
        // （清单真源在中控的表里，sidecar 不自己维护一份设备表）。
        // 认领失败不致命：保活线程会继续重试，只是这台设备暂时不会被判在线。
        string registryUrl = Get(opt, "registry-url") ?? "http://127.0.0.1:18080";
        string? deviceCode = Get(opt, "device-code");
        string? simHost = transport is SimBridgeTransport
            ? Get(opt, "sim-host") ?? "127.0.0.1" : null;
        int simPort = transport is SimBridgeTransport
            ? (int.TryParse(Get(opt, "sim-port"), out var sp) ? sp : SimPort(Get(opt, "sim")))
            : 0;
        var device = ResolveDevice(registryUrl, simHost, simPort, deviceCode);
        if (device is not null)
        {
            Console.WriteLine($"已认领设备：{device.Code}（设备ID {device.DeviceId}）"
                + $" @ {device.Ip}:{device.Port}（来源：中控设备清单）");
        }

        // 长连接保活：sidecar 主动连上设备，把「连上了 / 断了」写进 Redis
        // ⇒ 中控（内核）从 Redis 读这个状态来判定设备在线，不再自己开短连接去探。
        StartLinkKeeper(transport, opt, device);

        if (opt.ContainsKey("once"))
        {
            if (model is null)
            {
                Console.Error.WriteLine("--once 需要 --model（要按型号校验指令）。");
                return 2;
            }
            return RunOnce(Get(opt, "once")!, opt, model, transport, device);
        }

        if (linkOnly)
        {
            // 只保活：不监听端口、不接命令。进程退出即"这台设备下线"。
            Console.WriteLine("link-only 模式：只维持长连接并上报 Redis 在线状态"
                + "（不监听端口、不转发命令）。");
            Console.CancelKeyPress += (_, e) => { e.Cancel = true; };
            new ManualResetEventSlim(false).Wait();
            return 0;
        }

        var port = int.TryParse(Get(opt, "port"), out var p) ? p : 8501;
        var bind = Get(opt, "bind") ?? "0.0.0.0";
        Console.WriteLine($"型号库：{ModelsPath(opt)}（{lib.Models.Count} 个型号）");
        Console.WriteLine($"注意：错误码由中控侧翻译（设备码 → EC-xx），sidecar 只带回原始码。");

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
        new TcpServer(bind, port, model!, transport).Run(cts.Token);
        return 0;
    }

    /// <summary>调试用：发一条命令、打印回包，不监听端口。</summary>
    private static int RunOnce(string spec, Dictionary<string, string> opt,
                               ModelLibrary.ModelDef model, ITransport transport,
                               DeviceEntry? device)
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

        // equipment_id 用**设备ID**（和中控下发的一致，也是模拟器校验的那个）
        var equipment = device?.DeviceId.ToString()
                       ?? Get(opt, "device")
                       ?? "SIDECAR-1";
        var raw = $"{{\"message_id\":\"once\",\"message_type\":1,\"method\":\"{method}\","
                + $"\"equipment_id\":\"{equipment}\","
                + $"\"parameters\":{paramsJson}}}";
        var req = Protocol.Parse(raw)!;
        var r = transport.Send(req, model);
        Console.WriteLine(r.Ok
            ? $"OK   {r.DataJson}"
            : $"FAIL {r.VendorCode} {r.ErrorText}");
        return r.Ok ? 0 : 1;
    }

    /// <summary>
    /// 起一个后台线程：维持到设备的**长连接**，并把连接状态写进 Redis。
    ///
    /// <para>两件事，缺一不可：</para>
    /// <list type="number">
    ///   <item><b>认领设备</b>：从中控的设备清单里找到"我是哪台设备的 tcp client"
    ///         （sim 按设备端点对，串口/dll 按设备编号对）。认不到就一直重试 ——
    ///         认错了会把在线状态挂到别的设备名下，表现为"这台明明在线，页面显示离线"。</item>
    ///   <item><b>保活 + 上报</b>：维持长连接，把 online/offline 写进
    ///         {@code labos:link:<设备ID>:<设备IP>:<端口>}（带 TTL，断了自己过期）。</item>
    /// </list>
    ///
    /// <para>设备ID / 设备IP / 端口**全部取自中控的清单**，不取命令行 ——
    /// 命令行是"我这台进程连到哪"，清单是"中控认为设备在哪"，两个都得对。</para>
    /// </summary>
    private static Thread? StartLinkKeeper(ITransport transport, Dictionary<string, string> opt,
                                          DeviceEntry? seed)
    {
        string redisHost = Get(opt, "redis-host") ?? "127.0.0.1";
        int redisPort = int.TryParse(Get(opt, "redis-port"), out var rp) ? rp : 6379;
        int ttl = int.TryParse(Get(opt, "redis-ttl"), out var tt) ? tt : 30;
        int every = int.TryParse(Get(opt, "redis-refresh"), out var er) ? er : 10;
        string registryUrl = Get(opt, "registry-url") ?? "http://127.0.0.1:18080";
        string? deviceCode = Get(opt, "device-code");

        // 认领方式：sim 传输按"设备端点"对（它真正连着的那台设备）；
        // 串口 / dll 传输按"设备编号"对（中控认的是它自己那端的 ip:port）。
        string? simHost = null;
        int simPort = 0;
        if (transport is SimBridgeTransport)
        {
            simHost = Get(opt, "sim-host") ?? "127.0.0.1";
            simPort = int.TryParse(Get(opt, "sim-port"), out var sp) ? sp : SimPort(Get(opt, "sim"));
        }

        if (transport is not ILinkProbe)
        {
            Console.Error.WriteLine(
                $"警告：传输 {transport.Name} 没有链路探测能力，"
                + "在线状态将按\"sidecar 进程在跑\"上报（不是真的探测到设备）。");
        }

        var redis = new RedisLinkState(redisHost, redisPort);
        var stop = 0;   // 后台线程退出标记（Thread.IsInterrupted 在 .NET 里不是这个语义）
        var th = new Thread(() =>
        {
            DeviceEntry? entry = seed;
            // key 必须**先算出来**再进循环：seed 已经认领过时不会走下面的解析分支，
            // 早先的写法把 key 留在 null ⇒ 一次 Redis 都没写（中控侧全是离线）。
            string? key = seed is null
                ? null
                : RedisLinkState.LinkKey(seed.DeviceId, seed.Ip, seed.Port);
            bool claimedWarned = false;   // "还没认领到设备"只警告一次
            bool alivePrinted = false;    // 保活信息只打印一次
            while (Volatile.Read(ref stop) == 0)
            {
                if (entry is null)
                {
                    entry = ResolveDevice(registryUrl, simHost, simPort, deviceCode);
                    if (entry is null)
                    {
                        if (!claimedWarned)
                        {
                            Console.Error.WriteLine(
                                $"还没从中控认领到设备（{registryUrl}{DeviceRegistry.Path}）。"
                                + "在线状态暂时不上报（中控会判这台设备离线），稍后自动重试。");
                            claimedWarned = true;
                        }
                        Thread.Sleep(every * 1000);
                        continue;
                    }
                    key = RedisLinkState.LinkKey(entry.DeviceId, entry.Ip, entry.Port);
                }
                if (key is not null && !alivePrinted)
                {
                    Console.WriteLine($"长连接保活：设备 {entry!.Code}（ID {entry.DeviceId}）"
                        + $" ⇒ Redis {redisHost}:{redisPort} key={key}"
                        + $"（每 {every}s 刷新，TTL {ttl}s）");
                    alivePrinted = true;
                }

                bool linked = transport is ILinkProbe probe ? probe.TryEnsureLink() : true;
                if (key is not null)
                {
                    redis.Report(key, linked, ttl);
                }
                Thread.Sleep(every * 1000);
            }
            // 退出时把状态清掉：别让内核一直以为设备还在线
            if (key is not null)
            {
                redis.Clear(key);
            }
        })
        { IsBackground = true, Name = "link-keeper" };
        th.Start();
        return th;
    }

    /// <summary>
    /// 从中控的设备清单里认领"我是哪台设备的 tcp client"；认不到返回 null。
    ///
    /// <p>失败不抛：认领不到只意味着"在线状态暂时没法上报"，
    /// 不该让 sidecar 整个起不来 —— 中控起来之后下一轮就认到了。</p>
    /// </summary>
    private static DeviceEntry? ResolveDevice(string registryUrl, string? simHost,
                                              int simPort, string? deviceCode)
    {
        try
        {
            var items = DeviceRegistry.FetchAsync(registryUrl).GetAwaiter().GetResult();
            if (simHost != null)
            {
                return DeviceRegistry.MatchByEndpoint(items, simHost, simPort);
            }
            return DeviceRegistry.MatchByCode(items, deviceCode ?? "");
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("读中控设备清单失败：" + e.Message);
            return null;
        }
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
