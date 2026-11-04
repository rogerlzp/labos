using LabOS.Sidecar.Drivers;
using LabOS.Sidecar.Services;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddGrpc(o =>
{
    o.MaxReceiveMessageSize = 16 * 1024 * 1024;
});
builder.Services.AddSingleton<DriverPool>();
builder.Services.AddSingleton<LabOS.Sidecar.Services.IdempotentCache>();

var app = builder.Build();

int grpcPort = app.Configuration.GetValue("Sidecar:GrpcPort", 50051);
app.Urls.Add($"http://0.0.0.0:{grpcPort}");

// ---------- 可选：本地起一个模拟驱动，用于无真机环境联调 ----------
bool selfTest = args.Contains("--selftest");
bool withMock = args.Contains("--mock") || selfTest;
MockDriverServer? mock = null;
if (withMock)
{
    var mockLog = app.Services.GetRequiredService<ILoggerFactory>()
        .CreateLogger("MockDriver");
    mock = new MockDriverServer(19301, mockLog);
    mock.Start();

    // MOCK_FAULT="*:CloseDoor:EC-08-0001" 或 "171:Transfer:EC-08-0001"
    string? faultSpec = Environment.GetEnvironmentVariable("MOCK_FAULT");
    if (!string.IsNullOrWhiteSpace(faultSpec))
    {
        string[] parts = faultSpec.Split(':');
        if (parts.Length == 3)
        {
            mock.Fault(parts[0], parts[1], parts[2]);
            mockLog.LogInformation("注入故障: {Device}:{Method} -> {Code}", parts[0], parts[1], parts[2]);
        }
    }
}

// ---------- 注册驱动端点 ----------
var pool = app.Services.GetRequiredService<DriverPool>();
var configs = app.Configuration.GetSection("Drivers").Get<List<DriverEndpointConfig>>() ?? new();
foreach (DriverEndpointConfig cfg in configs)
{
    // --mock 模式下把配置里的端点重定向到本地 mock
    if (withMock && mock is not null)
    {
        cfg.Endpoint = $"tcp://127.0.0.1:{mock.Port}";
    }
    pool.Register(cfg);
}

app.MapGrpcService<DriverService>();
app.MapGet("/", () => "LabOS Sidecar v1 · gRPC 契约见 proto/driver.proto");

var log = app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("Startup");
log.LogInformation("Sidecar 启动，gRPC 端口 {Port}，驱动 {Count} 个", grpcPort, configs.Count);
foreach (DriverEndpointConfig c in configs)
{
    log.LogInformation("  {Code} -> {Endpoint} ({Devices} 台设备)",
        c.Code, c.Endpoint, c.Devices.Count);
}

app.Lifetime.ApplicationStopping.Register(() =>
{
    mock?.DisposeAsync().AsTask().Wait(1000);
    pool.DisposeAsync().AsTask().Wait(1000);
});

// ---------- 自测模式：起服务 -> 用真实 gRPC 客户端调自己 -> 退出 ----------
if (selfTest)
{
    await app.StartAsync();
    mock?.Fault("200387", "Transfer", "EC-08-0001");   // 给自测准备一台"坏设备"
    await LabOS.Sidecar.SelfTest.RunAsync(grpcPort);

    // 用「驱动侧实际收到几条指令」验证幂等——比看返回状态可靠
    if (mock is not null)
    {
        var rec = mock.Received();
        Console.WriteLine();
        Console.WriteLine(new string('=', 72));
        Console.WriteLine("  驱动侧实际收到的指令");
        Console.WriteLine(new string('=', 72));
        foreach (string r in rec)
        {
            Console.WriteLine("    " + r);
        }
        int dup = rec.Count(x => x == "200383:Transfer");
        Console.WriteLine();
        Console.WriteLine($"    200383:Transfer 出现 {dup} 次" +
                          (dup == 1 ? "  ✓ 幂等生效（第 2 次请求被缓存拦截）" : "  ✗ 幂等失效"));
    }

    await app.StopAsync();
    return;
}

app.Run();
