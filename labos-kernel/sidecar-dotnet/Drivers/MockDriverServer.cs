using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using LabOS.Sidecar.Protocol;

namespace LabOS.Sidecar.Drivers;

/// <summary>
/// 模拟驱动进程：TCP 服务端 + JSON + #!LABSEP!# 分帧。
///
/// <para>用于在没有真实 .NET 驱动的环境里验证 Sidecar 的协议转换。
/// 接真机时把这个类删掉即可。</para>
/// </summary>
public sealed class MockDriverServer : IAsyncDisposable
{
    private static readonly byte[] DelimBytes = Encoding.UTF8.GetBytes(Framing.Delimiter);

    private readonly TcpListener _listener;
    private readonly ILogger _log;
    private readonly Dictionary<string, string> _faults = new();
    private readonly List<string> _received = new();
    private readonly CancellationTokenSource _cts = new();

    public MockDriverServer(int port, ILogger log)
    {
        _listener = new TcpListener(IPAddress.Loopback, port);
        _log = log;
    }

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

    public void Start()
    {
        _listener.Start();
        _ = Task.Run(AcceptLoopAsync);
        _log.LogInformation("模拟驱动已启动 tcp://127.0.0.1:{Port}", Port);
    }

    /// <summary>注入故障：该设备该方法的指令返回指定错误码。</summary>
    public void Fault(string equipmentId, string method, string errorCode)
    {
        lock (_faults)
        {
            _faults[$"{equipmentId}:{method}"] = errorCode;
        }
    }

    public List<string> Received()
    {
        lock (_received)
        {
            return _received.ToList();
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                TcpClient client = await _listener.AcceptTcpClientAsync(_cts.Token);
                _ = Task.Run(() => HandleAsync(client));
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                _log.LogWarning("accept 失败: {Err}", ex.Message);
                return;
            }
        }
    }

    private async Task HandleAsync(TcpClient client)
    {
        using (client)
        {
            NetworkStream stream = client.GetStream();
            var acc = new List<byte>(256);
            var one = new byte[1];

            while (!_cts.IsCancellationRequested)
            {
                int n;
                try
                {
                    n = await stream.ReadAsync(one.AsMemory(0, 1), _cts.Token);
                }
                catch
                {
                    return;
                }

                if (n == 0)
                {
                    return;
                }

                acc.Add(one[0]);
                if (acc.Count >= DelimBytes.Length && EndsWith(acc, DelimBytes))
                {
                    int len = acc.Count - DelimBytes.Length;
                    string frame = Encoding.UTF8.GetString(acc.GetRange(0, len).ToArray());
                    acc.Clear();

                    string reply = Process(frame);
                    byte[] bytes = Encoding.UTF8.GetBytes(reply + Framing.Delimiter);
                    await stream.WriteAsync(bytes, _cts.Token);
                    await stream.FlushAsync(_cts.Token);
                }
            }
        }
    }

    /// <summary>每条指令的人为延迟（毫秒），用于演示"运行中"状态。</summary>
    private static readonly int DelayMs =
        int.TryParse(Environment.GetEnvironmentVariable("MOCK_DELAY_MS"), out int d) ? d : 0;

    private string Process(string frame)
    {
        MethodMessage? msg;
        try
        {
            msg = JsonSerializer.Deserialize<MethodMessage>(frame);
        }
        catch (JsonException ex)
        {
            _log.LogWarning("无法解析指令: {Err}", ex.Message);
            msg = null;
        }

        if (msg is null)
        {
            return BuildFinish("", "", "", 3, "EC-09-0001", "报文非法");
        }

        lock (_received)
        {
            _received.Add($"{msg.EquipmentId}:{msg.Method}");
        }

        if (DelayMs > 0)
        {
            Thread.Sleep(DelayMs);
        }

        string key = $"{msg.EquipmentId}:{msg.Method}";
        string? fault;
        lock (_faults)
        {
            _faults.TryGetValue(key, out fault);
            // 通配：*:Method 对任意设备生效（联调时方便）
            if (fault is null)
            {
                _faults.TryGetValue("*:" + msg.Method, out fault);
            }
        }

        return fault is not null
            ? BuildFinish(msg.MessageId, msg.Method, msg.EquipmentId, 3, fault,
                $"模拟设备故障 {fault}")
            : BuildFinish(msg.MessageId, msg.Method, msg.EquipmentId, 2, null, null);
    }

    private static string BuildFinish(string id, string method, string equip,
        int methodStatus, string? errorCode, string? errorText)
    {
        object error = errorCode is null
            ? new { }   // 成功时驱动给的是空对象
            : new { error_code = errorCode, troubleshoot = 0, error_text = errorText };

        var finish = new
        {
            message_id = id,
            message_type = MessageTypes.Finish,
            method,
            equipment_id = equip,
            equipment_status = 1,
            method_status = methodStatus,
            error,
            data = new { position = "A1", plate = "PL20261029" },
            process_info = new { },
            timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),
            checksum = "",
            connected = 1
        };

        return JsonSerializer.Serialize(finish);
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

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        _listener.Stop();
        await Task.CompletedTask;
    }
}
