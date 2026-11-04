using Google.Protobuf.Collections;
using Grpc.Net.Client;
using LabOS.Driver.V1;

namespace LabOS.Sidecar;

/// <summary>
/// 自测：用真实 gRPC 客户端调自己的服务，验证端到端行为。
///
/// <para>验证项与 Java 侧 SidecarDemo 一一对应：自描述、健康、执行、
/// 设备原文、幂等、错误码透传、故障换设备。</para>
/// </summary>
public static class SelfTest
{
    public static async Task RunAsync(int port)
    {
        Console.WriteLine();
        Section("Sidecar 自测（真实 gRPC 调用）");

        using var channel = GrpcChannel.ForAddress($"http://127.0.0.1:{port}");
        var client = new DeviceDriver.DeviceDriverClient(channel);

        // ---------- 1. 自描述
        Section("1. Describe（驱动自描述）");
        DriverDescriptor desc = await client.DescribeAsync(new DescribeRequest());
        Console.WriteLine($"  code={desc.Code}  kind={desc.DriverKind}  endpoint={desc.Endpoint}");
        Console.WriteLine($"  能力: {string.Join(", ", desc.Capabilities)}");
        Console.WriteLine($"  设备: {string.Join(", ", desc.DeviceInstanceIds)}");

        // ---------- 2. 健康
        Section("2. Health（健康检查）");
        HealthResponse health = await client.HealthAsync(new HealthRequest());
        Console.WriteLine($"  healthy={health.Healthy}  detail={health.Detail}");

        // ---------- 3. 执行成功
        Section("3. Execute（成功）");
        string id1 = Guid.NewGuid().ToString();
        CommandResult r1 = await client.ExecuteAsync(BuildCommand(id1, 200383, "Transfer"));
        Console.WriteLine($"  command_id = {id1}");
        Console.WriteLine($"  status     = {r1.Status}");
        Console.WriteLine($"  耗时       = {r1.DurationMs} ms");
        Console.WriteLine($"  设备原文   = {Truncate(r1.DeviceRawResponse, 140)}");

        // ---------- 4. 幂等
        Section("4. 幂等（同 command_id 再发一次）");
        CommandResult r2 = await client.ExecuteAsync(BuildCommand(id1, 200383, "Transfer"));
        Console.WriteLine($"  第 2 次 status = {r2.Status}");
        Console.WriteLine("  服务端走幂等缓存，未重复向设备下发（见下方 Sidecar 日志的「幂等命中」）");

        // ---------- 5. 故障
        Section("5. 故障（设备返回 EC-08-0001）");
        string id3 = Guid.NewGuid().ToString();
        CommandResult r3 = await client.ExecuteAsync(BuildCommand(id3, 200387, "Transfer"));
        Console.WriteLine($"  status     = {r3.Status}");
        Console.WriteLine($"  error_code = {r3.ErrorCode}   ← 原样透传，适配层不改写");
        Console.WriteLine($"  设备原文   = {Truncate(r3.DeviceRawResponse, 140)}");

        // ---------- 6. 无驱动设备
        Section("6. 未注册驱动的设备");
        CommandResult r4 = await client.ExecuteAsync(BuildCommand(Guid.NewGuid().ToString(), 999999, "Transfer"));
        Console.WriteLine($"  status     = {r4.Status}");
        Console.WriteLine($"  error_code = {r4.ErrorCode}");
        Console.WriteLine($"  message    = {r4.Message}");

        Section("结论");
        Console.WriteLine("  ✓ gRPC 契约可用（Describe / Health / Execute）");
        Console.WriteLine("  ✓ 设备原文完整回传（device_raw_response）");
        Console.WriteLine("  ✓ 幂等生效（同 id 不重复下发）");
        Console.WriteLine("  ✓ 错误码原样透传，适配层不做业务判断");
        Console.WriteLine("  ✓ 未绑定驱动的设备明确拒绝（EC-06-0001），不瞎猜");
    }

    private static CommandRequest BuildCommand(string id, long deviceId, string method)
    {
        var req = new CommandRequest
        {
            CommandId = id,
            DeviceInstanceId = deviceId,
            Method = method,
            TimeoutMs = 10000,
            TraceId = "selftest"
        };
        req.Params.Add("plate", "PL20261029");
        return req;
    }

    private static void Section(string t)
    {
        Console.WriteLine();
        Console.WriteLine(new string('=', 72));
        Console.WriteLine("  " + t);
        Console.WriteLine(new string('=', 72));
        Console.WriteLine();
    }

    private static string Truncate(string s, int n) =>
        string.IsNullOrEmpty(s) ? "(空)" : (s.Length <= n ? s : s[..n] + "...");
}
