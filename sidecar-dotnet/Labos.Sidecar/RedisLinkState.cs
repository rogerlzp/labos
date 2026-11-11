using System.Net.Sockets;
using System.Text;

namespace Labos.Sidecar;

/// <summary>
/// 把「到设备的连接状态」写进 Redis —— 中控（内核）从 Redis 读它来判定设备在线。
///
/// <para><b>为什么是 Redis 而不是中控自己探</b>：设备那头是 tcp client（就是本 sidecar）
/// 维持的**长连接**；中控自己每次心跳都新建一条短连接去 connect 一下，
/// 设备侧看到的是「客户端接入 → 断开」反复出现，既不是真在线，也把连接状态
/// 搞成了两套口径。</para>
///
/// <para><b>key 约定</b>（与内核 <c>HeartbeatService.linkKey</c> 一致）：
/// {@code labos:link:<设备ID>:<设备IP>:<端口>}，值 {@code online} / {@code offline}，
/// 带 TTL（断了不刷新就自然过期 ⇒ 中控那边自动判离线）。
/// 带设备ID 是为了让判定粒度到设备级 —— 一个驱动名下挂多台设备时，
/// 光按 ip:port 会把 A 设备的状态串到 B 设备上。</para>
///
/// <para>刻意**不引 NuGet 包**（Redis 客户端）：sidecar 的依赖越少越好，
/// 这里手写最小 RESP（SET/DEL）就够，几十行、零下载。</para>
/// </summary>
public sealed class RedisLinkState : IDisposable
{
    private readonly string _host;
    private readonly int _port;

    public RedisLinkState(string host, int port)
    {
        _host = host;
        _port = port;
    }

    /// <summary>
    /// 长连接状态在 Redis 里的 key（与内核侧同名约定）。
    /// 设备ID + 设备IP + 端口 —— 全部来自中控的设备清单。
    /// </summary>
    public static string LinkKey(long deviceId, string host, int port) =>
        $"labos:link:{deviceId}:{host}:{port}";

    /// <summary>写一次状态（online/offline，带 TTL）。失败只返回 false，不抛。</summary>
    public bool Report(string key, bool online, int ttlSeconds)
    {
        try
        {
            using var c = new TcpClient { NoDelay = true, SendTimeout = 2000 };
            c.Connect(_host, _port);
            using var s = c.GetStream();
            s.ReadTimeout = 2000;
            string val = online ? "online" : "offline";
            string ttl = ttlSeconds.ToString();
            // RESP 数组：SET key value EX ttl
            var cmd = $"*5\r\n$3\r\nSET\r\n${key.Length}\r\n{key}\r\n${val.Length}\r\n{val}\r\n$2\r\nEX\r\n${ttl.Length}\r\n{ttl}\r\n";
            var bytes = Encoding.UTF8.GetBytes(cmd);
            s.Write(bytes, 0, bytes.Length);
            s.Flush();
            var buf = new byte[64];
            return s.Read(buf, 0, buf.Length) > 0;   // 读回 +OK
        }
        catch
        {
            return false;   // Redis 不可用不该影响 sidecar 主流程
        }
    }

    /// <summary>
    /// 读一个 key 的值（Manager 用它判"这台设备现在在线吗"）。
    ///
    /// <para>读不到（连不上 / key 不存在）返回 null —— 调用方据此判离线，
    /// 与内核的口径一致（在线状态只有一个来源，就是 Redis）。</para>
    /// </summary>
    public string? Get(string key)
    {
        try
        {
            using var c = new TcpClient { NoDelay = true, SendTimeout = 2000 };
            c.Connect(_host, _port);
            using var s = c.GetStream();
            s.ReadTimeout = 2000;
            var cmd = $"*2\r\n$3\r\nGET\r\n${key.Length}\r\n{key}\r\n";
            var bytes = Encoding.UTF8.GetBytes(cmd);
            s.Write(bytes, 0, bytes.Length);
            s.Flush();

            // RESP 批量字符串：$5\r\nonline\r\n
            //
            // **不能只 read 一次就当拿到整个响应** —— TCP 是流，一次 read 可能
            // 只拿到半截（"$5\r\nonli"）。按 RESP 声明的长度读满才是对的。
            using var reader = new StreamReader(s, Encoding.UTF8, false, 1024, leaveOpen: true);
            var head = reader.ReadLine();
            if (head == null || !head.StartsWith("$"))
            {
                return null;   // 简单字符串 / 错误 / 连不上
            }
            if (!int.TryParse(head.Substring(1), out var len) || len < 0)
            {
                return null;   // $-1 ⇒ key 不存在
            }
            var buf = new char[len];
            var read = 0;
            while (read < len)
            {
                var n = reader.Read(buf, read, len - read);
                if (n <= 0)
                {
                    break;
                }
                read += n;
            }
            reader.ReadLine();   // 吃掉结尾的 \r\n
            return read == len ? new string(buf, 0, len) : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>主动删掉 key（sidecar 退出时用：别让内核一直以为设备在线）。</summary>
    public bool Clear(string key)
    {
        try
        {
            using var c = new TcpClient { NoDelay = true, SendTimeout = 2000 };
            c.Connect(_host, _port);
            using var s = c.GetStream();
            s.ReadTimeout = 2000;
            var cmd = $"*2\r\n$3\r\nDEL\r\n${key.Length}\r\n{key}\r\n";
            var bytes = Encoding.UTF8.GetBytes(cmd);
            s.Write(bytes, 0, bytes.Length);
            s.Flush();
            var buf = new byte[64];
            return s.Read(buf, 0, buf.Length) > 0;
        }
        catch
        {
            return false;
        }
    }

    public void Dispose() { }
}
