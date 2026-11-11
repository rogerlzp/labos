using System.Diagnostics;
using System.Text.Json;

namespace Labos.Sidecar.Manager;

/// <summary>管理台上的一台设备（字段全部来自中控的设备清单）。</summary>
public sealed record DeviceView(
    long DeviceId,
    string Code,
    string Name,
    string Ip,
    int Port,
    string? Capability,
    string? SimProfile,
    string? ModelId,
    /// <b>在线</b>：Redis 里有这条长连接状态（在线状态的唯一权威）。
    bool Online,
    /// <b>本机托管</b>：这个 Manager 起着它的 tcp client 子进程。
    bool Managed,
    /// 本机托管时的进程号。
    int? Pid,
    /// 给界面看的一句说明（为什么没在线 / 为什么起不来）。
    string? Note);

/// <summary>
/// 设备监管：**每台设备一个 tcp client 子进程**，起 = 上线，停 = 下线。
///
/// <para><b>为什么由本机管</b>：在线状态来自 tcp client 持有的长连接，
/// 而那条长连接只能在<b>设备所在的工位机</b>上 —— 远程去"停一个设备"这件事
/// 本身就得有个本地代理。管理台就是这个代理。</para>
///
/// <para><b>为什么判在线只认 Redis</b>：与内核同一口径。管理台自己
/// "以为"设备在线没用，中控认的是 Redis 里那条 key。</para>
/// </summary>
public sealed class DeviceSupervisor
{
    private readonly string _registryUrl;
    private readonly string _redisHost;
    private readonly int _redisPort;
    private readonly string _sidecarDll;
    private readonly string _dotnetPath;
    private readonly RedisLinkState _redis;
    private readonly Dictionary<long, Process> _children = new();
    private readonly Dictionary<long, DeviceEntry> _lastEntries = new();
    private readonly object _lock = new();
    private DateTime _lastAdopt = DateTime.MinValue;

    public DeviceSupervisor(IConfiguration cfg)
    {
        _registryUrl = cfg["Manager:RegistryUrl"] ?? "http://127.0.0.1:18080";
        _redisHost = cfg["Manager:RedisHost"] ?? "127.0.0.1";
        _redisPort = int.TryParse(cfg["Manager:RedisPort"], out var rp) ? rp : 6379;
        _sidecarDll = cfg["Manager:SidecarDll"] ?? DefaultSidecarDll();
        _dotnetPath = cfg["Manager:DotnetPath"] ?? "dotnet";
        _redis = new RedisLinkState(_redisHost, _redisPort);
    }

    /// <summary>sidecar 的默认位置：与本项目同级的 Labos.Sidecar 输出目录。</summary>
    private static string DefaultSidecarDll()
    {
        var dir = AppContext.BaseDirectory;                      // .../Labos.Sidecar.Manager/bin/Debug/net10.0/
        var sidecar = Path.GetFullPath(Path.Combine(
            dir, "..", "..", "..", "..", "Labos.Sidecar", "bin", "Debug", "net10.0", "Labos.Sidecar.dll"));
        return File.Exists(sidecar) ? sidecar : Path.Combine(dir, "Labos.Sidecar.dll");
    }

    public string RegistryUrl => _registryUrl;
    public string RedisEndpoint => $"{_redisHost}:{_redisPort}";
    public string SidecarDll => _sidecarDll;

    /// <summary>读中控清单 + 查 Redis ⇒ 当前所有设备的状态。</summary>
    public async Task<List<DeviceView>> SnapshotAsync()
    {
        List<DeviceEntry> items;
        try
        {
            items = await DeviceRegistry.FetchAsync(_registryUrl).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            throw new InvalidOperationException($"读中控设备清单失败（{_registryUrl}）：{e.Message}", e);
        }

        // 接管已经在跑的 tcp client（Manager 重启后不接管 ⇒ 这些设备就永远
        // 停不掉 —— 界面显示"不是本机托管"，下线按钮是灰的）。
        AdoptExisting(items);

        lock (_lock)
        {
            _lastEntries.Clear();
            foreach (var e in items)
            {
                _lastEntries[e.DeviceId] = e;
            }
        }

        var outp = new List<DeviceView>();
        foreach (var e in items)
        {
            var key = RedisLinkState.LinkKey(e.DeviceId, e.Ip, e.Port);
            var val = _redis.Get(key);
            bool online = val != null &&
                (val.Equals("online", StringComparison.OrdinalIgnoreCase) || val == "1");
            int? pid = null;
            bool managed;
            lock (_lock)
            {
                managed = _children.TryGetValue(e.DeviceId, out var p) && p is not null;
                if (managed && p is not null)
                {
                    try
                    {
                        if (p.HasExited)
                        {
                            managed = false;
                            _children.Remove(e.DeviceId);
                        }
                        else
                        {
                            pid = p.Id;
                        }
                    }
                    catch
                    {
                        managed = false;
                        _children.Remove(e.DeviceId);
                    }
                }
            }
            string? note = null;
            if (!online && managed)
            {
                note = "tcp client 在跑，但还没上报在线（可能设备没连上）";
            }
            else if (online && !managed)
            {
                note = "Redis 里有在线状态，但不是本机托管的进程";
            }
            outp.Add(new DeviceView(e.DeviceId, e.Code, e.Code, e.Ip, e.Port,
                e.Capability, e.SimProfile, e.ModelId, online, managed, pid, note));
        }
        return outp;
    }

    /// <summary>
    /// 接管已经在跑的 tcp client 子进程。
    ///
    /// <p><b>为什么必须有这一步</b>：Manager 重启（升级、崩溃、人工重启）后，
    /// 之前起的 tcp client 还活着，但新 Manager 不知道它们 ⇒
    /// 界面上这些设备显示"不是本机托管"、<b>下线按钮是灰的</b> ⇒
    /// 设备永远停不下来（Redis key 一直续期，中控一直判在线）。
    /// 所以每次刷新状态都扫一遍进程表，把认得出的收编过来。</p>
    ///
    /// <p>认领依据：命令行里的 {@code --link-only} + {@code --sim <ip::<port>>}，
    /// 与清单里的设备端点对上就是同一台设备（端点就是设备口径的一部分）。</p>
    /// </summary>
    private void AdoptExisting(List<DeviceEntry> items)
    {
        // 节流：ps 每次都起进程，5 秒一次就够，别把机器打满
        var now = DateTime.UtcNow;
        lock (_lock)
        {
            if (now - _lastAdopt < TimeSpan.FromSeconds(5))
            {
                return;
            }
            _lastAdopt = now;
        }

        foreach (var (pid, args) in ListSidecarProcesses())
        {
            if (!args.Contains("--link-only", StringComparison.Ordinal))
            {
                continue;
            }
            var sim = ExtractArg(args, "--sim");
            if (sim is null)
            {
                continue;
            }
            var dev = items.FirstOrDefault(i =>
                string.Equals($"{i.Ip}:{i.Port}", sim, StringComparison.OrdinalIgnoreCase));
            if (dev is null)
            {
                continue;
            }
            lock (_lock)
            {
                if (_children.ContainsKey(dev.DeviceId))
                {
                    continue;
                }
                try
                {
                    var p = Process.GetProcessById(pid);
                    _children[dev.DeviceId] = p;
                }
                catch
                {
                    // 进程刚好退了：忽略，下一轮再看
                }
            }
        }
    }

    /// <summary>扫系统进程表，取出所有 sidecar 的 (pid, 命令行)。</summary>
    private static List<(int Pid, string Args)> ListSidecarProcesses()
    {
        var outp = new List<(int, string)>();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "ps",
                RedirectStandardOutput = true,
                UseShellExecute = false,
            };
            psi.ArgumentList.Add("-eo");
            psi.ArgumentList.Add("pid=,args=");
            using var p = Process.Start(psi);
            if (p is null)
            {
                return outp;
            }
            var text = p.StandardOutput.ReadToEnd();
            p.WaitForExit(3000);
            foreach (var line in text.Split('\n'))
            {
                var t = line.Trim();
                if (t.Length == 0)
                {
                    continue;
                }
                if (!t.Contains("Labos.Sidecar", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                var sp = t.IndexOf(' ');
                if (sp <= 0 || !int.TryParse(t[..sp].Trim(), out var pid))
                {
                    continue;
                }
                outp.Add((pid, t[(sp + 1)..]));
            }
        }
        catch
        {
            // ps 不可用（Windows 等）：放弃接管，界面仍显示在线状态
        }
        return outp;
    }

    /// <summary>从命令行里取 {@code --flag value} 的 value。</summary>
    private static string? ExtractArg(string args, string flag)
    {
        var parts = args.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        for (int i = 0; i < parts.Length - 1; i++)
        {
            if (parts[i].Equals(flag, StringComparison.OrdinalIgnoreCase))
            {
                return parts[i + 1];
            }
        }
        return null;
    }

    /// <summary>上线：起一个 tcp client 子进程（保活 + 写 Redis）。</summary>
    public async Task<(bool ok, string message)> StartAsync(long deviceId)
    {
        var items = await DeviceRegistry.FetchAsync(_registryUrl).ConfigureAwait(false);
        // 按**设备ID**找（不是按编号）—— 编号会改，设备ID 才是三端统一的那个值。
        var dev = items.FirstOrDefault(i => i.DeviceId == deviceId);
        if (dev is null)
        {
            return (false, $"中控清单里没有设备 {deviceId}");
        }
        if (!File.Exists(_sidecarDll))
        {
            return (false, $"找不到 sidecar 程序：{_sidecarDll}（用 Manager:SidecarDll 配置指定）");
        }

        lock (_lock)
        {
            if (_children.TryGetValue(deviceId, out var exist) && !exist.HasExited)
            {
                return (false, "这台设备的 tcp client 已经在跑了");
            }
            _children.Remove(deviceId);
        }

        var psi = new ProcessStartInfo
        {
            FileName = _dotnetPath,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            WorkingDirectory = Path.GetDirectoryName(_sidecarDll) ?? ".",
        };
        // --link-only：只维持长连接并上报 Redis，不监听端口、不转发命令。
        // 这样**任何**设备都能当 tcp client，不用先给它配一个型号。
        psi.ArgumentList.Add(_sidecarDll);
        psi.ArgumentList.Add("--link-only");
        psi.ArgumentList.Add("--transport");
        psi.ArgumentList.Add("sim");
        psi.ArgumentList.Add("--sim");
        psi.ArgumentList.Add($"{dev.Ip}:{dev.Port}");
        psi.ArgumentList.Add("--redis-host");
        psi.ArgumentList.Add(_redisHost);
        psi.ArgumentList.Add("--redis-port");
        psi.ArgumentList.Add(_redisPort.ToString());
        psi.ArgumentList.Add("--registry-url");
        psi.ArgumentList.Add(_registryUrl);

        var proc = Process.Start(psi);
        if (proc is null)
        {
            return (false, "起 tcp client 失败");
        }
        // 子进程的输出丢进文件：管理台不消费它，但出了错要能看到。
        var logDir = Path.Combine(Path.GetTempPath(), "labos-sidecar-manager");
        Directory.CreateDirectory(logDir);
        var log = Path.Combine(logDir, $"sidecar-{dev.DeviceId}.log");
        try
        {
            // 子进程的输出丢进文件：管理台不消费它，但出了错要能看到。
            // 不 await —— 子进程可能活很久，管理台不能被它拖住。
            _ = proc.StandardOutput.BaseStream.CopyToAsync(File.Create(log));
        }
        catch { /* 日志失败不影响主流程 */ }

        lock (_lock)
        {
            _children[deviceId] = proc;
        }
        return (true, $"已上线：{dev.Code}（设备ID {dev.DeviceId} @ {dev.Ip}:{dev.Port}），pid={proc.Id}");
    }

    /// <summary>下线：停掉本机托管的 tcp client ⇒ Redis key 清除 ⇒ 中控判离线。</summary>
    public async Task<(bool ok, string message)> StopAsync(long deviceId)
    {
        Process? proc = null;
        lock (_lock)
        {
            if (_children.TryGetValue(deviceId, out var p))
            {
                proc = p;
                _children.Remove(deviceId);
            }
        }
        if (proc is null)
        {
            // 本机没托管 ⇒ 可能是别的机器上的 sidecar。Redis key 自然会过期，
            // 但那要等 TTL。这里直接告诉你"管不到"。
            return (false, "这台设备不是本机托管的，停不掉");
        }
        // 缓存里没有这台设备（页面还没刷新过）⇒ 拉一次清单，否则清不掉 key。
        await EnsureEntryAsync(deviceId).ConfigureAwait(false);
        try
        {
            if (!proc.HasExited)
            {
                proc.Kill(entireProcessTree: true);
                proc.WaitForExit(3000);
            }
            // **顺手把 Redis key 删掉**。
            // 为什么不能指望 sidecar 自己删：Kill() 发的是 SIGKILL，
            // 进程没有机会执行清理代码 ⇒ key 会一直留到 TTL 过期（默认 30 秒）。
            // 那 30 秒里中控仍然判这台设备在线 —— "点了下线却还在线"，
            // 现场会以为按钮没生效。Manager 知道 key 在哪，直接清掉更干脆。
            var key = LockedKey(deviceId);
            var cleared = key is not null && _redis.Clear(key);
            return (true, cleared
                ? "已下线（Redis 状态已清除，中控即刻判这台设备离线）"
                : "已下线（Redis 状态将在 TTL 内过期）");
        }
        catch (Exception e)
        {
            return (false, "停 tcp client 失败：" + e.Message);
        }
    }

    /// <summary>确保清单里有这台设备（缓存缺就拉一次）。</summary>
    private async Task EnsureEntryAsync(long deviceId)
    {
        lock (_lock)
        {
            if (_lastEntries.ContainsKey(deviceId))
            {
                return;
            }
        }
        try
        {
            var items = await DeviceRegistry.FetchAsync(_registryUrl).ConfigureAwait(false);
            lock (_lock)
            {
                foreach (var e in items)
                {
                    _lastEntries[e.DeviceId] = e;
                }
            }
        }
        catch
        {
            // 拉不到就算了：下面清 key 会失败，退化成"等 TTL 过期"
        }
    }

    /// <summary>取某设备的 Redis key（从最近一次清单快照里查）。</summary>
    private string? LockedKey(long deviceId)
    {
        lock (_lock)
        {
            return _lastEntries.TryGetValue(deviceId, out var e)
                ? RedisLinkState.LinkKey(e.DeviceId, e.Ip, e.Port)
                : null;
        }
    }

    /// <summary>全下线：把本机托管的 tcp client 全部停掉。</summary>
    public async Task<(int stopped, List<string> errors)> StopAllAsync()
    {
        long[] ids;
        lock (_lock)
        {
            ids = _children.Keys.ToArray();
        }
        int n = 0;
        var errs = new List<string>();
        foreach (var id in ids)
        {
            var (ok, msg) = await StopAsync(id).ConfigureAwait(false);
            if (ok)
            {
                n++;
            }
            else
            {
                errs.Add($"设备 {id}：{msg}");
            }
        }
        return (n, errs);
    }

    /// <summary>全上线：把清单里的设备全部起一遍。</summary>
    public async Task<(int started, List<string> errors)> StartAllAsync()
    {
        List<DeviceEntry> items;
        try
        {
            items = await DeviceRegistry.FetchAsync(_registryUrl).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            return (0, new List<string> { "读中控清单失败：" + e.Message });
        }
        int n = 0;
        var errs = new List<string>();
        foreach (var dev in items)
        {
            var (ok, msg) = await StartAsync(dev.DeviceId).ConfigureAwait(false);
            if (ok)
            {
                n++;
            }
            else
            {
                errs.Add($"{dev.Code}：{msg}");
            }
        }
        return (n, errs);
    }
}
