using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using LabOS.Sidecar.Protocol;

namespace LabOS.Sidecar.Drivers;

/// <summary>
/// 与一个驱动端点（.NET DLL 进程）的 TCP 连接。
///
/// <para>
/// 三个必须处理的点（与 Java 参考实现 TcpDriverAdapter 一一对应）：
/// <list type="number">
///   <item>分帧：TCP 是流，必须按 #!LABSEP!# 切，不能假设一次 read 一条消息</item>
///   <item>串行化：同一连接上的请求必须串行，否则响应会串</item>
///   <item>按 message_id 过滤：驱动会异步推心跳，不能拿错响应</item>
/// </list>
/// </para>
/// </summary>
public sealed class TcpDriverConnection : IAsyncDisposable
{
    private static readonly byte[] DelimiterBytes = Encoding.UTF8.GetBytes(Framing.Delimiter);

    private readonly string _code;
    private readonly string _host;
    private readonly int _port;
    private readonly ILogger _log;

    /// <summary>同一连接串行化，防止请求/响应错配。</summary>
    private readonly SemaphoreSlim _gate = new(1, 1);

    private TcpClient? _client;
    private NetworkStream? _stream;
    private bool _connected;

    public TcpDriverConnection(string code, string host, int port, ILogger log)
    {
        _code = code;
        _host = host;
        _port = port;
        _log = log;
    }

    public string Code => _code;
    public bool Healthy => _connected && _stream != null && _client?.Connected == true;

    /// <summary>
    /// 发送一条指令并等待对应结果。
    /// </summary>
    public async Task<(FinishMessage? result, string? raw, string? error)>
        ExecuteAsync(MethodMessage msg, int timeoutMs, CancellationToken ct)
    {
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await EnsureConnectedAsync(ct).ConfigureAwait(false);

            string json = JsonSerializer.Serialize(msg) + Framing.Delimiter;
            byte[] bytes = Encoding.UTF8.GetBytes(json);
            await _stream!.WriteAsync(bytes, ct).ConfigureAwait(false);
            await _stream.FlushAsync(ct).ConfigureAwait(false);

            var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < deadline)
            {
                string? frame = await ReadFrameAsync(deadline, ct).ConfigureAwait(false);
                if (frame is null)
                {
                    _connected = false;
                    return (null, null, "EC-05-1006:连接中断");
                }

                FinishMessage? head;
                try
                {
                    head = JsonSerializer.Deserialize<FinishMessage>(frame);
                }
                catch (JsonException ex)
                {
                    _log.LogWarning("[{Code}] 无法解析的帧（已跳过）: {Err}", _code, ex.Message);
                    continue;
                }

                if (head is null)
                {
                    continue;
                }

                // 心跳/其他消息忽略，只认同 message_id 的 Finish
                if (head.MessageType == MessageTypes.Finish && head.MessageId == msg.MessageId)
                {
                    return (head, frame, null);
                }

                _log.LogDebug("[{Code}] 忽略非目标消息 type={Type} id={Id}",
                    _code, head.MessageType, head.MessageId);
            }

            return (null, null, "EC-05-1007:等待回包超时");
        }
        catch (IOException ex)
        {
            _connected = false;
            return (null, null, $"EC-05-1006:{ex.Message}");
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task EnsureConnectedAsync(CancellationToken ct)
    {
        if (_connected && _stream is not null && _client?.Connected == true)
        {
            return;
        }

        await DisposeConnectionAsync().ConfigureAwait(false);

        _client = new TcpClient { NoDelay = true };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(3));

        await _client.ConnectAsync(_host, _port, cts.Token).ConfigureAwait(false);
        _stream = _client.GetStream();
        _connected = true;
        _log.LogInformation("[{Code}] 已连接 {Host}:{Port}", _code, _host, _port);
    }

    /// <summary>
    /// 按分隔符读一帧。逐字节读性能一般，生产可换 System.IO.Pipelines。
    /// </summary>
    private async Task<string?> ReadFrameAsync(DateTime deadline, CancellationToken ct)
    {
        var acc = new List<byte>(256);
        var one = new byte[1];

        while (DateTime.UtcNow < deadline)
        {
            int remainMs = (int)Math.Max(1, (deadline - DateTime.UtcNow).TotalMilliseconds);
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(remainMs);

            int n;
            try
            {
                n = await _stream!.ReadAsync(one.AsMemory(0, 1), cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return null;
            }

            if (n == 0)
            {
                return null;   // 对端关闭
            }

            acc.Add(one[0]);

            if (acc.Count >= DelimiterBytes.Length && EndsWith(acc, DelimiterBytes))
            {
                int len = acc.Count - DelimiterBytes.Length;
                return Encoding.UTF8.GetString(acc.GetRange(0, len).ToArray());
            }
        }

        return null;
    }

    private static bool EndsWith(List<byte> buffer, byte[] suffix)
    {
        int offset = buffer.Count - suffix.Length;
        for (int i = 0; i < suffix.Length; i++)
        {
            if (buffer[offset + i] != suffix[i])
            {
                return false;
            }
        }
        return true;
    }

    private Task DisposeConnectionAsync()
    {
        try { _stream?.Dispose(); } catch { /* ignore */ }
        try { _client?.Dispose(); } catch { /* ignore */ }
        _stream = null;
        _client = null;
        _connected = false;
        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            await DisposeConnectionAsync().ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
            _gate.Dispose();
        }
    }
}
