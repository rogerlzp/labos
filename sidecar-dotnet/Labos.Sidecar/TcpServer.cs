using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Labos.Sidecar;

/// <summary>
/// 中控连进来的那一条 TCP 连接（默认 8501）。
///
/// <para>
/// 与模拟器、labos-kernel 的 <c>TcpDriverAdapter</c> 同一套分帧：
/// 一行 JSON + {@code #!LABSEP!#}。**没有长度前缀，也没有换行** ——
/// 所以必须"读到分隔符为止"，不能靠 readLine。
/// </para>
///
/// <para>
/// 关键点：一条连接上可以连续发多条命令（中控就是这么用的），
/// 每条各自回一帧；<b>不要关连接</b>，也<b>不要并发处理同一条连接</b> ——
/// 设备本身是串行的，并发只会把回包配错。
/// </para>
/// </summary>
public sealed class TcpServer
{
    private readonly int _port;
    private readonly string _bind;
    private readonly ModelLibrary.ModelDef _model;
    private readonly ITransport _transport;

    public TcpServer(string bind, int port, ModelLibrary.ModelDef model, ITransport transport)
    {
        _bind = bind;
        _port = port;
        _model = model;
        _transport = transport;
    }

    public void Run(CancellationToken token)
    {
        var listener = new TcpListener(IPAddress.Parse(_bind), _port);
        listener.Start();
        Log($"监听 {_bind}:{_port}  型号={_model.Id}  传输={_transport.Name}  "
            + $"证据={_model.Evidence}");
        if (_model.Evidence != "MANUAL")
        {
            Log("⚠ 该型号不是手册依据（Evidence!=MANUAL），只能跑流程骨架，不能对接真机");
        }

        var clients = 0;
        while (!token.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = listener.AcceptTcpClient();
            }
            catch (SocketException)
            {
                break;
            }
            var n = Interlocked.Increment(ref clients);
            Log($"中控接入 {client.Client.RemoteEndPoint}（当前 {n} 个）");
            var t = new Thread(() => Serve(client))
            {
                IsBackground = true,
                Name = "labos-sidecar-conn",
            };
            t.Start();
        }
    }

    private void Serve(TcpClient client)
    {
        using (client)
        {
            client.NoDelay = true;
            var stream = client.GetStream();
            var sep = Encoding.UTF8.GetBytes(Protocol.Sep);
            var buf = new List<byte>(8192);
            var chunk = new byte[8192];

            try
            {
                while (true)
                {
                    var idx = IndexOf(buf, sep);
                    while (idx < 0)
                    {
                        var read = stream.Read(chunk, 0, chunk.Length);
                        if (read <= 0)
                        {
                            Log("中控断开");
                            return;
                        }
                        buf.AddRange(chunk.AsSpan(0, read).ToArray());
                        idx = IndexOf(buf, sep);
                    }

                    var json = Encoding.UTF8.GetString(buf.GetRange(0, idx).ToArray());
                    buf.RemoveRange(0, idx + sep.Length);

                    var reply = Handle(json);
                    var payload = Encoding.UTF8.GetBytes(reply + Protocol.Sep);
                    stream.Write(payload, 0, payload.Length);
                    stream.Flush();
                }
            }
            catch (IOException e)
            {
                Log("连接异常：" + e.Message);
            }
            catch (ObjectDisposedException)
            {
                // 中控主动断开
            }
        }
    }

    /// <summary>一条请求 → 一帧回包。异常也要回包，**不能静默**（静默 = 中控等到超时）。</summary>
    private string Handle(string json)
    {
        var req = Protocol.Parse(json);
        if (req == null)
        {
            Log("收到无法解析的请求：" + Truncate(json));
            // 拿不到 message_id 时只能回一个占位 id，中控会按超时处理
            return "{\"message_id\":\"?\",\"message_type\":5,\"method\":\"?\","
                 + "\"equipment_id\":\"?\",\"equipment_status\":1,\"method_status\":3,"
                 + "\"error\":{\"error_code\":\"EC-06-0002\",\"error_text\":\"请求不是合法 JSON\"},"
                 + "\"data\":{},\"process_info\":{},\"connected\":1}";
        }

        Log($"收到 {req.Method}  id={req.MessageId}");
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var r = _transport.Send(req, _model);
        sw.Stop();

        if (r.Ok)
        {
            Log($"完成 {req.Method}（{sw.ElapsedMilliseconds}ms）");
            return Protocol.Ok(req, r.DataJson);
        }
        Log($"失败 {req.Method}（{sw.ElapsedMilliseconds}ms）：{r.VendorCode} {r.ErrorText}");
        return Protocol.Fail(req, r.VendorCode ?? "EC-05-2001", r.ErrorText ?? "设备失败");
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

    private static string Truncate(string s) => s.Length <= 200 ? s : s[..200] + "…";

    public static void Log(string msg) =>
        Console.WriteLine($"[sidecar {DateTime.Now:HH:mm:ss}] {msg}");
}
