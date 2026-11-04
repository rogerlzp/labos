using System.Collections.Concurrent;

namespace LabOS.Sidecar.Drivers;

/// <summary>一个驱动端点的配置（来自 appsettings.json）。</summary>
public sealed class DriverEndpointConfig
{
    public string Code { get; set; } = "";
    /// <summary>形如 tcp://192.0.2.10:8501</summary>
    public string Endpoint { get; set; } = "";
    public List<long> Devices { get; set; } = new();
    public List<string> Capabilities { get; set; } = new();

    public (string host, int port) Parse()
    {
        string s = Endpoint;
        int scheme = s.IndexOf("://", StringComparison.Ordinal);
        if (scheme >= 0)
        {
            s = s[(scheme + 3)..];
        }
        int colon = s.LastIndexOf(':');
        if (colon <= 0)
        {
            throw new FormatException($"endpoint 格式非法（应为 tcp://host:port）: {Endpoint}");
        }
        string host = s[..colon];
        int port = int.Parse(s[(colon + 1)..]);
        return (host, port);
    }
}

/// <summary>
/// 驱动连接池：deviceInstanceId → 端点 → TCP 连接。
///
/// <para>与 Java 侧 DriverRegistry 的分工：那边管"能力 → 设备"的匹配，
/// 这边只管"设备 → 连接"的路由。</para>
/// </summary>
public sealed class DriverPool : IAsyncDisposable
{
    private readonly ConcurrentDictionary<string, TcpDriverConnection> _byCode = new();
    private readonly ConcurrentDictionary<long, string> _deviceToCode = new();
    private readonly ConcurrentDictionary<string, DriverEndpointConfig> _configs = new();
    private readonly ILogger<DriverPool> _log;

    public DriverPool(ILogger<DriverPool> log)
    {
        _log = log;
    }

    public void Register(DriverEndpointConfig cfg)
    {
        if (string.IsNullOrWhiteSpace(cfg.Code) || string.IsNullOrWhiteSpace(cfg.Endpoint))
        {
            throw new ArgumentException("驱动配置缺少 code 或 endpoint");
        }

        var (host, port) = cfg.Parse();
        _configs[cfg.Code] = cfg;
        _byCode[cfg.Code] = new TcpDriverConnection(cfg.Code, host, port, _log);

        foreach (long d in cfg.Devices)
        {
            _deviceToCode[d] = cfg.Code;
        }

        _log.LogInformation("注册驱动 {Code} -> {Host}:{Port}，设备 {Count} 台",
            cfg.Code, host, port, cfg.Devices.Count);
    }

    public TcpDriverConnection? ForDevice(long deviceInstanceId)
    {
        return _deviceToCode.TryGetValue(deviceInstanceId, out string? code)
               && _byCode.TryGetValue(code, out TcpDriverConnection? conn)
            ? conn
            : null;
    }

    public TcpDriverConnection? ByCode(string code) =>
        _byCode.TryGetValue(code, out TcpDriverConnection? c) ? c : null;

    public IEnumerable<DriverEndpointConfig> All => _configs.Values;

    public IEnumerable<long> DevicesOf(string code) =>
        _configs.TryGetValue(code, out DriverEndpointConfig? cfg) ? cfg.Devices : Array.Empty<long>();

    public async ValueTask DisposeAsync()
    {
        foreach (TcpDriverConnection c in _byCode.Values)
        {
            await c.DisposeAsync().ConfigureAwait(false);
        }
        _byCode.Clear();
    }
}
