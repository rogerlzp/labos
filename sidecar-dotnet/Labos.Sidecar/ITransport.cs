namespace Labos.Sidecar;

/// <summary>一条命令的落地结果（与 HTTP 无关，纯设备层）。</summary>
public sealed record TransportReply(bool Ok, string DataJson, string? VendorCode, string? ErrorText)
{
    public static TransportReply Success(string dataJson) => new(true, dataJson, null, null);

    /// <summary>设备拒绝：<paramref name="vendorCode"/> 必须是**设备原始码**。</summary>
    public static TransportReply Fail(string vendorCode, string text) =>
        new(false, "{}", vendorCode, text);
}

/// <summary>
/// 传输层：中控说的"方法名"到这里变成设备的电平/报文。
///
/// <para>
/// 三种实现，**同一个 TcpServer 换一条传输就换一种接法**：
/// <list type="bullet">
///   <item><see cref="S100SerialTransport"/> —— 二进制串口帧（S100 封膜机）</item>
///   <item><see cref="AspinClusterTransport"/> —— 调 Cluster.dll 发 XML（ASpin 2K 离心机）</item>
///   <item><see cref="SimBridgeTransport"/> —— 没有硬件时转发给 Java 模拟器</item>
/// </list>
/// </para>
///
/// <para>
/// **为什么要有 sim 这一档**：真机在实验室，开发在工位。没有 sim 档就只能
/// "写完上现场试"，一次往返几个小时。有了它，中控 → sidecar → 模拟器这条链
/// 在工位上就能跑通，上现场只剩"把 sim 换成 serial/dll"这一步。
/// </para>
/// </summary>
public interface ITransport : IDisposable
{
    string Name { get; }

    TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model);

    /// <summary>
    /// 取一条设备<b>主动上报</b>的帧（<b>没有 message_id</b> 的那种）。
    ///
    /// <para><b>为什么需要</b>：有些设备是"自驱"的 —— 它自己跑到某个状态就停下，
    /// 主动上报一声（例：分盘式提取仪跑到交换点，报【等待交互】等上位机换板）。
    /// 这类帧<b>不是</b>对任何命令的回应 ⇒ 不能混进回包队列，
    /// 否则下一条命令会拿到上一条的陈旧回包。</para>
    ///
    /// <para>默认不支持：串口/dll 传输是"问一句答一句"，拿不到自发帧。
    /// 支持的传输（现在只有 sim 档）覆写它。</para>
    /// </summary>
    bool TryDequeueSpontaneous(out string frame)
    {
        frame = "";
        return false;
    }
}

/// <summary>
/// 传输层能自报"到设备的链路还活着吗"。
///
/// <para>在线状态的权威是 <b>Redis</b>，写入方是 tcp client（就是本 sidecar）：
/// 它持有到设备的**长连接**，把状态写进 Redis，中控只读。
/// 所以 sidecar 必须能回答"我这条长连接现在活着吗" —— 这就是本接口。</para>
///
/// <para>没实现本接口的传输（串口 / dll）会在保活线程里按"进程在跑即视为链路可用"
/// 上报，并打一行警告 —— 那不是真的探测，只是"我没能力探"。</para>
/// </summary>
public interface ILinkProbe
{
    /// <summary>建立/维持到设备的连接；成功返回 true（失败不抛）。</summary>
    bool TryEnsureLink();
}
