using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 没有硬件时：把请求**原样转发**给 Java 模拟器，再把回包原样带回来。
///
/// <para>
/// 这一档存在的意义：中控 → sidecar → 设备 这条链里，中间那跳最容易出问题
/// （帧怎么拼、参数怎么转、错误码怎么回）。用 sim 档可以把它和"设备本身对不对"
/// 分开验证 —— 上现场时只换传输，链路逻辑一行不用改。
/// </para>
///
/// <para>
/// <b>设备号必须和模拟器一致</b>：模拟器会校验 <c>equipment_id</c>，
/// 对不上直接回 {@code EC-06-0001}。所以 sidecar 的 {@code --device}
/// 要和 {@code DeviceSimulator --device} 填同一个值。
/// </para>
/// </summary>
public sealed class SimBridgeTransport : ITransport, ILinkProbe
{
    private readonly string _host;
    private readonly int _port;
    private readonly object _lock = new();
    private TcpClient? _client;
    private NetworkStream? _stream;
    private readonly List<byte> _buf = new();

    public SimBridgeTransport(string host, int port)
    {
        _host = host;
        _port = port;
    }

    public string Name => $"sim-bridge {_host}:{_port}";

    public TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model)
    {
        lock (_lock)
        {
            // 连一次可能踩在"对端刚重启"上（残余连接还挂在半开状态）。
            // 这类失败**重试一次**就好，不要让一次抖动变成一条业务失败记录。
            var first = SendOnce(req);
            if (first.Ok || first.VendorCode != "EC-05-1006")
            {
                return first;
            }
            CloseQuietly();
            return SendOnce(req);
        }
    }

    private TransportReply SendOnce(Protocol.Request req)
    {
        {
            try
            {
                EnsureConnected();
                var payload = Encoding.UTF8.GetBytes(req.Raw + Protocol.Sep);
                _stream!.Write(payload, 0, payload.Length);
                _stream.Flush();

                var frame = ReadFrame(TimeSpan.FromSeconds(60));
                if (frame == null)
                {
                    // 回包丢了几乎都是"连接已死"（模拟器重启过/被 kill）：
                    // 必须**丢掉连接**，下一次调用才会重连。
                    // 不丢的话 TcpClient.Connected 仍然是 true（半开连接），
                    // 后续每条命令都写进黑洞，一直到 sidecar 重启为止
                    // —— 2026-11-02 实测踩到：重启模拟器后整条链路"静默失败"。
                    CloseQuietly();
                    return TransportReply.Fail("EC-05-1006", "模拟器没有回包（连接可能已断，下次自动重连）");
                }
                using var doc = JsonDocument.Parse(frame);
                var root = doc.RootElement;
                var status = root.TryGetProperty("method_status", out var st) ? st.GetInt32() : -1;

                if (status == 2)
                {
                    var data = root.TryGetProperty("data", out var d) ? d.GetRawText() : "{}";
                    return TransportReply.Success(data);
                }
                // 失败：把设备原始码原样带回（翻译是中控的事）
                if (root.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.Object)
                {
                    var code = err.TryGetProperty("error_code", out var c) ? c.GetString() : null;
                    var text = err.TryGetProperty("error_text", out var t) ? t.GetString() : null;
                    return TransportReply.Fail(code ?? "EC-05-1006", text ?? "模拟器返回失败");
                }
                return TransportReply.Fail("EC-05-1006", $"模拟器返回 method_status={status}");
            }
            catch (Exception e)
            {
                CloseQuietly();
                return TransportReply.Fail("EC-05-1006", "转发到模拟器失败：" + e.Message);
            }
        }
    }

    /// <summary>
    /// 长连接是否还在（给保活线程判状态用）。
    ///
    /// <p>注意：<c>TcpClient.Connected</c> 对**半开连接**仍返回 true ⇒
    /// 真正的"还活着"要看有没有读到东西（见 Send 里的读超时判断）。
    /// </summary>
    public bool IsLinked => _client is { Connected: true } && _stream != null;

    /// <summary>主动建立/维持到设备的连接；失败返回 false（不抛给调用方）。</summary>
    public bool TryEnsureLink()
    {
        try
        {
            EnsureConnected();
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>建立到设备的连接（保活线程调用）。</summary>
    public void EnsureConnected()
    {
        if (_client is { Connected: true } && _stream != null)
        {
            return;
        }
        CloseQuietly();
        _client = new TcpClient { NoDelay = true };
        _client.Connect(_host, _port);
        _stream = _client.GetStream();
        _buf.Clear();
    }

    /// <summary>读到分隔符为止；超时返回 null（不能无限等，否则中控那边先超时）。</summary>
    private string? ReadFrame(TimeSpan timeout)
    {
        var sep = Encoding.UTF8.GetBytes(Protocol.Sep);
        var deadline = DateTime.UtcNow + timeout;
        var one = new byte[4096];
        while (DateTime.UtcNow < deadline)
        {
            var idx = IndexOf(_buf, sep);
            if (idx >= 0)
            {
                var s = Encoding.UTF8.GetString(_buf.GetRange(0, idx).ToArray());
                _buf.RemoveRange(0, idx + sep.Length);
                return s;
            }
            _stream!.ReadTimeout = 500;
            try
            {
                var n = _stream.Read(one, 0, one.Length);
                if (n <= 0)
                {
                    return null;
                }
                _buf.AddRange(one.AsSpan(0, n).ToArray());
            }
            catch (IOException)
            {
                // 读超时：继续等，直到总 deadline
            }
        }
        return null;
    }

    private static int IndexOf(List<byte> hay, byte[] needle)
    {
        for (var i = 0; i + needle.Length <= hay.Count; i++)
        {
            var ok = true;
            for (var j = 0; j < needle.Length; j++)
            {
                if (hay[i + j] != needle[j]) { ok = false; break; }
            }
            if (ok) { return i; }
        }
        return -1;
    }

    private void CloseQuietly()
    {
        try { _stream?.Dispose(); } catch { /* 关不上不影响结论 */ }
        try { _client?.Dispose(); } catch { /* 同上 */ }
        _stream = null;
        _client = null;
    }

    public void Dispose() => CloseQuietly();
}
