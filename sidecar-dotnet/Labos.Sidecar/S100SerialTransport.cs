using System.IO.Ports;
using System.Text;

namespace Labos.Sidecar;

/// <summary>
/// S100 封膜机：**二进制 12 字节帧**，串口 115200 8N1。
///
/// <para>帧格式（手册 §2）：</para>
/// <code>
///   位置  1     2      3     4       5–10          11     12
///   内容  报头  指令码  索引  预留    数据(低字节先)  预留   报尾
///   值    0xAA  0x04   0x00  0x00    00 00 00 00 00 00  0x00  0xFF
/// </code>
/// <para>
/// 应答 {@code 0xAC} / 错误 {@code 0xEE}；错误帧**只有第 5 字节是识别字节**，
/// 完整设备码 = {@code 0x8000000 | byte5}（手册 §4）。
/// </para>
///
/// <para>
/// <b>sidecar 不做语义翻译</b>：设备说 {@code 0x8000000C}，回给中控的就是
/// {@code 0x8000000C}；"无纸 → 转人工"由 labos-kernel 的型号库决定。
/// 这样换一台封膜机，只改型号库，sidecar 一行不用动。
/// </para>
/// </summary>
public sealed class S100SerialTransport : ITransport
{
    private const byte Head = 0xAA;
    private const byte Ack = 0xAC;
    private const byte Nack = 0xEE;
    private const byte Tail = 0xFF;

    private readonly SerialPort _port;
    private readonly object _lock = new();
    private readonly int _minReadMs;

    public S100SerialTransport(string portName, int baud, int minReadMs = 3000)
    {
        _minReadMs = minReadMs;
        _port = new SerialPort(portName, baud, Parity.None, 8, StopBits.One)
        {
            ReadTimeout = minReadMs,
            WriteTimeout = 3000,
        };
        _port.Open();
    }

    /// <summary>
    /// 这条命令该等多久。
    ///
    /// <para>
    /// **不能用一个固定值**：封膜一次 12 秒（手册没写实际耗时，先用 12 秒），
    /// 固定 3 秒会让每一次封膜都超时 → 中控判失败 → 触发重试 → 重复封膜。
    /// 所以按型号库的 {@code delayMs} 与型号的 {@code actionTimeoutMs} 取大者。
    /// </para>
    /// </summary>
    private int WaitMsFor(ModelLibrary.MethodDef def, ModelLibrary.ModelDef model)
    {
        var ms = Math.Max(def.DelayMs, model.ActionTimeoutMs);
        if (ms <= 0)
        {
            ms = _minReadMs;
        }
        // 多给 2 秒余量：设备忙时回包会慢一点
        return (int)Math.Min(int.MaxValue, ms + 2000);
    }

    public string Name => $"serial {_port.PortName}@{_port.BaudRate}";

    public TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model)
    {
        // 指令表以型号库为准（sidecar 不自己认方法名）
        var def = model.Find(req.Method);
        if (def == null)
        {
            return TransportReply.Fail("EC-06-0004", $"型号 {model.Id} 没有指令 {req.Method}（手册里没有）");
        }
        if (string.Equals(def.Cmd, "GetStatus", StringComparison.OrdinalIgnoreCase))
        {
            // 手册没有查询状态的指令。这里回的是**sidecar 自己的状态**，
            // 不是设备状态 —— 真去问设备只能靠 CurrSpeed/CurrTime 这类。
            return TransportReply.Success("{\"connected\":1,\"source\":\"sidecar\"}");
        }

        byte cmd, index = 0;
        long value = 0;
        switch (def.Cmd)
        {
            case "SetParam":
                cmd = 0x00;
                index = (byte)(Protocol.Int(req.Parameters, "index", "paramIndex") ?? 0);
                value = Protocol.Int(req.Parameters, "value", "paramValue") ?? 0;
                break;
            case "ReadParam":
                cmd = 0x01;
                index = (byte)(Protocol.Int(req.Parameters, "index", "paramIndex") ?? 0);
                break;
            case "SaveParam": cmd = 0x02; break;
            case "PrintParams": cmd = 0x03; break;
            case "Seal": cmd = 0x04; break;
            case "SealWithPlateType":
                cmd = 0x04; index = 0xA5;
                value = Protocol.Int(req.Parameters, "plateType", "value") ?? 0;
                if (value is < 1 or > 8)
                {
                    return TransportReply.Fail("0x80000003", $"孔板类型必须是 1–8，收到 {value}");
                }
                break;
            case "QueryPlateType": cmd = 0x04; index = 0x5A; break;
            case "Reset": cmd = 0x05; break;
            case "ClearFault": cmd = 0x06; break;
            case "GetVersion": cmd = 0x07; index = 0x08; break;
            case "MoveTray":
                cmd = 0x0C;
                value = Protocol.Int(req.Parameters, "value", "position") ?? 0;
                break;
            default:
                return TransportReply.Fail("0x80000008", $"S100 不认识指令 {def.Cmd}（收到错误指令）");
        }

        var frame = BuildFrame(cmd, index, value);
        lock (_lock)
        {
            try
            {
                _port.ReadTimeout = WaitMsFor(def, model);
                _port.DiscardInBuffer();
                _port.Write(frame, 0, frame.Length);

                var reply = ReadExact(12, out var got);
                if (reply == null || got < 5)
                {
                    return TransportReply.Fail("0x8000000E", "设备没有回包（等待超时）");
                }
                return ParseReply(reply);
            }
            catch (TimeoutException)
            {
                return TransportReply.Fail("0x8000000E", "等待设备回包超时");
            }
            catch (Exception e)
            {
                return TransportReply.Fail("0x80000004", "串口异常：" + e.Message);
            }
        }
    }

    /// <summary>拼 12 字节帧：报头/指令/索引/预留/数据 6 字节(低字节先)/预留/报尾。</summary>
    public static byte[] BuildFrame(byte cmd, byte index, long value)
    {
        var f = new byte[12];
        f[0] = Head;
        f[1] = cmd;
        f[2] = index;
        f[3] = 0x00;
        for (var i = 0; i < 6; i++)
        {
            f[4 + i] = (byte)((value >> (8 * i)) & 0xFF);
        }
        f[10] = 0x00;
        f[11] = Tail;
        return f;
    }

    /// <summary>解析回包：0xAC 成功，0xEE 失败（第 5 字节是识别字节）。</summary>
    public static TransportReply ParseReply(byte[] reply)
    {
        if (reply[0] == Nack)
        {
            var b = reply[4];
            var code = $"0x800000{b:X2}";   // 0x8000000 | 第5字节
            var meaning = S100Errors.Meaning(code);
            return TransportReply.Fail(code, meaning);
        }
        if (reply[0] != Ack)
        {
            return TransportReply.Fail("0x80000002", $"回包报头异常 0x{reply[0]:X2}（错误消息类型）");
        }

        // 数据是 6 字节低字节在前；指令码决定怎么解释
        long v = 0;
        for (var i = 0; i < 6; i++)
        {
            v |= (long)reply[4 + i] << (8 * i);
        }
        var cmd = reply[1];
        var idx = reply[2];
        return cmd switch
        {
            0x04 when idx == 0x5A => TransportReply.Success($"{{\"plateType\":{v}}}"),
            0x07 => TransportReply.Success(
                $"{{\"version\":\"{Encoding.ASCII.GetString(reply, 4, 6).Trim('\0', ' ')}\"}}"),
            0x01 => TransportReply.Success($"{{\"index\":{idx},\"value\":{v}}}"),
            _ => TransportReply.Success($"{{\"value\":{v},\"cmd\":{cmd},\"index\":{idx}}}"),
        };
    }

    private byte[]? ReadExact(int n, out int got)
    {
        var buf = new byte[n];
        got = 0;
        var deadline = DateTime.UtcNow.AddMilliseconds(_port.ReadTimeout);
        while (got < n && DateTime.UtcNow < deadline)
        {
            try
            {
                var r = _port.Read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            catch (TimeoutException)
            {
                break;
            }
        }
        return got > 0 ? buf : null;
    }

    public void Dispose()
    {
        try { _port.Close(); } catch { /* 关不上不影响结论 */ }
        _port.Dispose();
    }
}

/// <summary>S100 手册的 14 个错误码（只用于**给人看的说明**，代号才是契约）。</summary>
public static class S100Errors
{
    private static readonly Dictionary<string, string> Table = new(StringComparer.OrdinalIgnoreCase)
    {
        ["0x80000001"] = "信号量溢出",
        ["0x80000002"] = "错误消息类型",
        ["0x80000003"] = "参数范围有误",
        ["0x80000004"] = "CAN 通信错误",
        ["0x80000005"] = "电机堵转",
        ["0x80000006"] = "未检测到孔板",
        ["0x80000007"] = "刀片脱落",
        ["0x80000008"] = "收到错误指令",
        ["0x80000009"] = "数值错误",
        ["0x8000000A"] = "存储参数出错",
        ["0x8000000B"] = "传感器失效",
        ["0x8000000C"] = "无纸",
        ["0x8000000E"] = "事件等待超时",
        ["0x8000001A"] = "托盘不在原点，无法封膜",
    };

    public static string Meaning(string code) =>
        Table.TryGetValue(code, out var m) ? m : $"未在手册中登记的错误码 {code}";
}
