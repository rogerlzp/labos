using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 中控设备清单里的一台设备（{@code GET /api/devices/registry} 的一项）。
///
/// <para><b>口径</b>：一台设备 = <b>设备ID</b>（device_instance.id）
/// + <b>设备IP</b>（device_instance.ip）+ 端口。三端（模拟端 / 中控 / tcp client）
/// 认的必须是同一组值，所以这里的值**全部取自中控的表**，sidecar 自己不推断。</para>
/// </summary>
/// <param name="DeviceId">设备ID（device_instance.id）—— Redis key 里就是它。</param>
/// <param name="Code">设备编号（人工可读，认领设备时用它兜底匹配）。</param>
/// <param name="Ip">设备IP。</param>
/// <param name="Port">设备端口。</param>
/// <param name="Endpoint">中控 driver.endpoint 原样（tcp://host:port）。</param>
/// <param name="Capability">设备能力（INCUBATE / TRANSFER …）。</param>
/// <param name="SimProfile">模拟器行为画像（robot / incubator …）。</param>
/// <param name="ModelId">型号库 id（如 s100 / aspin-2k），没有则 null。</param>
/// <param name="DriverCode">中控侧的驱动编码。</param>
public sealed record DeviceEntry(
    long DeviceId,
    string Code,
    string Ip,
    int Port,
    string Endpoint,
    string? Capability,
    string? SimProfile,
    string? ModelId,
    string? DriverCode);

/// <summary>
/// 从中控读设备清单 —— **清单真源在中控的表里**，sidecar 不自己维护一份设备表。
///
/// <para>sidecar 启动时要做一件事：**认领"我是哪台设备的 tcp client"**。
/// 认领不靠人工填设备号（填错了 Redis 里的长连接状态就挂到别的设备名下，
/// 表现为"这台明明在线，页面显示离线"），而是拿中控的清单来对：</para>
/// <list type="number">
///   <item>sim 传输：按设备端点（ip + port）对 —— 那是它真正连着的设备；</item>
///   <item>其余传输（串口 / dll）：按设备编号（{@code --device-code}）对。</item>
/// </list>
///
/// <para>刻意**不引 NuGet 包**（HTTP 客户端也是 BCL 自带的），依赖越少越好。</para>
/// </summary>
public static class DeviceRegistry
{
    /// <summary>清单接口路径（中控侧免鉴权，只含设备端点与型号）。</summary>
    public const string Path = "/api/devices/registry";

    /// <summary>拉一次清单。失败抛异常（调用方决定重试还是放弃）。</summary>
    public static async Task<List<DeviceEntry>> FetchAsync(
        string baseUrl, CancellationToken ct = default)
    {
        var url = baseUrl.TrimEnd('/') + Path;
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        using var resp = await http.GetAsync(url, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        return Parse(body);
    }

    /// <summary>解析清单 JSON。</summary>
    public static List<DeviceEntry> Parse(string body)
    {
        var result = new List<DeviceEntry>();
        using var doc = JsonDocument.Parse(body);
        if (!doc.RootElement.TryGetProperty("items", out var items)
            || items.ValueKind != JsonValueKind.Array)
        {
            return result;
        }
        foreach (var it in items.EnumerateArray())
        {
            long id = it.TryGetProperty("deviceId", out var d) && d.TryGetInt64(out var dv) ? dv : 0;
            if (id <= 0)
            {
                continue;   // 没有设备ID的条目没法写 key，跳过
            }
            result.Add(new DeviceEntry(
                DeviceId: id,
                Code: Str(it, "code"),
                Ip: Str(it, "ip"),
                Port: (it.TryGetProperty("port", out var p) && p.TryGetInt32(out var pv)) ? pv : 0,
                Endpoint: Str(it, "endpoint"),
                Capability: Str(it, "capability"),
                SimProfile: Str(it, "simProfile"),
                ModelId: Str(it, "modelId"),
                DriverCode: Str(it, "driverCode")));
        }
        return result;
    }

    /// <summary>按设备端点（ip + port）认领 —— sim 传输用。</summary>
    public static DeviceEntry? MatchByEndpoint(List<DeviceEntry> items, string ip, int port)
    {
        if (port <= 0)
        {
            return null;
        }
        foreach (var e in items)
        {
            if (e.Port == port && SameHost(e.Ip, ip))
            {
                return e;
            }
        }
        return null;
    }

    /// <summary>按设备编号认领 —— 串口 / dll 传输用（中控认的是它自己的端点）。</summary>
    public static DeviceEntry? MatchByCode(List<DeviceEntry> items, string code)
    {
        if (string.IsNullOrWhiteSpace(code))
        {
            return null;
        }
        foreach (var e in items)
        {
            if (string.Equals(e.Code, code, StringComparison.OrdinalIgnoreCase))
            {
                return e;
            }
        }
        return null;
    }

    /// <summary>主机名比较：大小写不敏感，忽略尾部点。</summary>
    private static bool SameHost(string a, string b)
    {
        if (string.IsNullOrWhiteSpace(a) || string.IsNullOrWhiteSpace(b))
        {
            return false;
        }
        return string.Equals(a.Trim().TrimEnd('.'), b.Trim().TrimEnd('.'),
                            StringComparison.OrdinalIgnoreCase);
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? (v.GetString() ?? "")
            : "";
}
