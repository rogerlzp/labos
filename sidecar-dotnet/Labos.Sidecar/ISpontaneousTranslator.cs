using System.Xml.Linq;

namespace Labos.Sidecar;

/// <summary>
/// 型号专属的**自发帧翻译器**：把该型号设备自己说的话，翻译成统一形状。
///
/// <para><b>为什么需要按型号</b>：真实设备的自发帧长什么样**目前没有厂商手册**，
/// 而且大概率**不是一个型号一种格式** ——
/// 有的走 XML（ASpin 2K 经 Cluster.dll 就是 XML）、有的是定长二进制帧（S100 串口 12 字节）、
/// 有的干脆就是 JSON。<br/>
/// 判据"没有 message_id 的帧 = 设备主动上报"是<b>通用</b>的（内核与侧车必须一致），
/// 但<b>怎么把这一帧读成「设备码 / 事件类型 / 载荷」是型号专属的</b>。</para>
///
/// <para><b>所以接真机要做的事被收敛成</b>：给该型号写一个翻译器并注册，
/// 其余（转发、入站、等待节点）一行不用改。没有翻译器的型号走
/// {@link JsonSpontaneousTranslator}（通用 JSON 那条路，模拟器就靠它）。</para>
///
/// <para><b>翻译器不得抛异常</b>：翻译失败只是"这一帧不认识"，
/// 不能把命令下发也带崩（与转发失败不致命同一条原则）。</para>
/// </summary>
public interface ISpontaneousTranslator
{
    /// 这个翻译器负责哪个型号（型号库 id，如 {@code aspin-2k} / {@code s100}）。
    string ModelId { get; }

    /// 翻译一帧原始报文；不认识 / 不是自发帧 ⇒ 返回 {@code null}（不是错误）。
    Protocol.SpontaneousFrame? Translate(string rawFrame);
}

/// <summary>
/// 自发帧翻译器的登记处（按型号）。
///
/// <para><b>为什么是注册表而不是 switch</b>：每接一台新设备就往里加一条，
/// 不改任何既有型号的代码 —— 这正是 sidecar 存在的意义
/// （"新接一台设备不改中控代码"的最后一公里）。</para>
/// </summary>
public static class SpontaneousTranslators
{
    private static readonly Dictionary<string, ISpontaneousTranslator> ByModel =
        new(StringComparer.OrdinalIgnoreCase);

    /// <summary>登记一个型号专属的翻译器（同名覆盖最后一个）。</summary>
    public static void Register(ISpontaneousTranslator translator)
    {
        if (translator == null)
        {
            return;
        }
        ByModel[translator.ModelId] = translator;
    }

    /// <summary>
    /// 取某型号的翻译器：**优先型号专属**，没有就用通用 JSON 那条路。
    /// </summary>
    public static ISpontaneousTranslator For(string? modelId)
    {
        if (!string.IsNullOrWhiteSpace(modelId)
            && ByModel.TryGetValue(modelId.Trim(), out var t))
        {
            return t;
        }
        return JsonSpontaneousTranslator.Instance;
    }

    /// <summary>已登记的型号专属翻译器（观测用）。</summary>
    public static IReadOnlyCollection<string> RegisteredModels()
        => ByModel.Keys.ToList();
}

/// <summary>
/// 通用 JSON 翻译器（默认）：帧是 JSON、没有 message_id ⇒ 就是自发帧。
///
/// <para>模拟器走的就是这条；真实设备若也是 JSON 帧，它也能直接用。</para>
/// </summary>
public sealed class JsonSpontaneousTranslator : ISpontaneousTranslator
{
    public static readonly JsonSpontaneousTranslator Instance = new();

    /// <summary>通用翻译器不对应具体型号（登记表按型号查不到它时用它）。</summary>
    public string ModelId => "*";

    public Protocol.SpontaneousFrame? Translate(string rawFrame)
    {
        try
        {
            return Protocol.ParseSpontaneous(rawFrame);
        }
        catch (Exception)
        {
            // 翻译失败只是"不认识这一帧"，不是错误
            return null;
        }
    }
}

/// <summary>
/// **模拟的** XML 风格自发帧翻译器 —— 用来证明"按型号翻译"这个扩展点真的能用。
///
/// <para>⚠ <b>这不是任何真机的协议</b>：帧形状是照着"XML 型设备"的样子编的
/// （ASpin 2K 经 Cluster.dll 走的就是 XML），真机来了要按手册重写。
/// 它只活在自测里，<b>不注册进生产</b>。</para>
///
/// <para>模拟的帧形状：
/// <code>
/// &lt;DeviceFrame&gt;&lt;EquipmentId&gt;ID-ASPIN&lt;/EquipmentId&gt;
/// &lt;EventType&gt;plate_detected&lt;/EventType&gt;
/// &lt;Payload&gt;&lt;Slot&gt;2&lt;/Slot&gt;&lt;Plate&gt;PL001&lt;/Plate&gt;&lt;/Payload&gt;
/// &lt;/DeviceFrame&gt;
/// </code></para>
/// </summary>
public sealed class SampleXmlSpontaneousTranslator : ISpontaneousTranslator
{
    public string ModelId => "sample-xml";

    public Protocol.SpontaneousFrame? Translate(string rawFrame)
    {
        if (string.IsNullOrWhiteSpace(rawFrame))
        {
            return null;
        }
        try
        {
            var doc = XDocument.Parse(rawFrame);
            var root = doc.Root;
            if (root == null)
            {
                return null;
            }
            var equipmentId = (string?)root.Element("EquipmentId") ?? "";
            var eventType = (string?)root.Element("EventType") ?? "";
            if (equipmentId.Length == 0 || eventType.Length == 0)
            {
                return null;   // 认不出是谁报的 / 什么事件 ⇒ 不发
            }
            var payload = root.Element("Payload");
            var payloadJson = "{}";
            if (payload != null)
            {
                // XML 载荷 → 统一形状里的 JSON 对象
                var parts = payload.Elements()
                    .Select(e => $"\"{e.Name.LocalName}\":\"{e.Value}\"");
                payloadJson = "{" + string.Join(",", parts) + "}";
            }
            return new Protocol.SpontaneousFrame(equipmentId, eventType, payloadJson);
        }
        catch (Exception)
        {
            return null;
        }
    }
}

/// <summary>
/// **模拟的** 定长二进制风格自发帧翻译器 —— 同样只活在自测里。
///
/// <para>⚠ <b>这不是任何真机的协议</b>：照着"二进制定长帧设备"的样子编的
/// （S100 串口就是 0xAA 开头、0xFF 结尾的定长帧），真机来了要按手册重写。</para>
///
/// <para>模拟的帧形状（6 字节起、变长载荷）：
/// <code>
/// AA F0 &lt;eventCode&gt; &lt;len&gt; &lt;payload…&gt; FF
/// </code>
/// 事件码表（模拟）：01=idle / 02=plate_detected / 03=process_done。</para>
/// </summary>
public sealed class SampleBinarySpontaneousTranslator : ISpontaneousTranslator
{
    private static readonly Dictionary<byte, string> EventCodes = new()
    {
        [0x01] = "idle",
        [0x02] = "plate_detected",
        [0x03] = "process_done",
    };

    public string ModelId => "sample-binary";

    public Protocol.SpontaneousFrame? Translate(string rawFrame)
    {
        if (string.IsNullOrWhiteSpace(rawFrame))
        {
            return null;
        }
        try
        {
            var bytes = Convert.FromBase64String(rawFrame.Trim());
            if (bytes.Length < 6)
            {
                return null;
            }
            if (bytes[0] != 0xAA || bytes[^1] != 0xFF)
            {
                return null;   // 不是本协议的帧
            }
            if (bytes[1] != 0xF0)
            {
                return null;   // 不是自发帧（是回包）
            }
            if (!EventCodes.TryGetValue(bytes[2], out var eventType))
            {
                return null;   // 未知事件码 ⇒ 不认领
            }
            var len = bytes[3];
            if (bytes.Length < 5 + len)
            {
                return null;
            }
            // 载荷：设备码 + 自由文本（模拟：前若干字节是设备码，其余是载荷）
            var payloadText = System.Text.Encoding.UTF8.GetString(bytes, 4, len);
            var sep = payloadText.IndexOf('|');
            var equipmentId = sep >= 0 ? payloadText[..sep] : payloadText;
            var detail = sep >= 0 ? payloadText[(sep + 1)..] : "";
            var payloadJson = "{\"detail\":\"" + detail.Replace("\"", "\\\"") + "\"}";
            return new Protocol.SpontaneousFrame(equipmentId, eventType, payloadJson);
        }
        catch (Exception)
        {
            return null;
        }
    }
}
