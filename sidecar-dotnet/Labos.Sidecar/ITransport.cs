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
}
