using Labos.Sidecar.Manager;

// ============================================================================
// sidecar 管理台 —— **独立应用**，跑在工位机上，浏览器打开就能用。
//
// 它管的是「每台设备一个 tcp client 子进程」：
//   上线 = 起一个 tcp client（维持到设备的**长连接**，把状态写进 Redis）
//   下线 = 停掉它（Redis key 过期 ⇒ 中控判这台设备离线）
//
// 设备清单来自中控（唯一真源），在线状态来自 Redis（唯一权威）。
// 管理台自己**不维护**任何设备表 —— 那正是"两份事实"的来源。
//
// 用法：
//   dotnet Labos.Sidecar.Manager.dll --urls http://0.0.0.0:8600
//   （配置项：Manager:RegistryUrl / Manager:RedisHost / Manager:RedisPort
//             Manager:SidecarDll / Manager:DotnetPath，也可用环境变量
//             Manager__RegistryUrl 等）
// ============================================================================

// ContentRoot / WebRoot 显式钉死到程序所在目录。
// 为什么必须写：静态文件默认从 **ContentRoot/wwwroot** 找，而直接
// `dotnet <dll>` 跑时 ContentRoot = **当前工作目录** ⇒ 从别处启动就 404
// （现场表现为「管理台打开了是空白页」）。钉到 AppContext.BaseDirectory 后，
// 从哪个目录启动都一样。
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
    WebRootPath = "wwwroot",
});
builder.Services.AddSingleton<DeviceSupervisor>();
builder.Services.AddCors(o => o.AddDefaultPolicy(p =>
    p.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod()));

var app = builder.Build();
app.UseCors();
app.UseDefaultFiles();
app.UseStaticFiles();

// ---- 设备列表（含在线状态）----
app.MapGet("/api/devices", async (DeviceSupervisor sup) =>
{
    try
    {
        var list = await sup.SnapshotAsync();
        return Results.Ok(new
        {
            registryUrl = sup.RegistryUrl,
            redis = sup.RedisEndpoint,
            sidecarDll = sup.SidecarDll,
            devices = list,
        });
    }
    catch (Exception e)
    {
        return Results.Problem(e.Message, statusCode: 502);
    }
});

// ---- 上线 / 下线 ----
app.MapPost("/api/devices/{id:long}/start", async (long id, DeviceSupervisor sup) =>
{
    var (ok, msg) = await sup.StartAsync(id);
    return ok ? Results.Ok(new { ok = true, message = msg })
              : Results.BadRequest(new { ok = false, message = msg });
});

app.MapPost("/api/devices/{id:long}/stop", async (long id, DeviceSupervisor sup) =>
{
    var (ok, msg) = await sup.StopAsync(id);
    return ok ? Results.Ok(new { ok = true, message = msg })
              : Results.BadRequest(new { ok = false, message = msg });
});

// ---- 批量 ----
app.MapPost("/api/devices/start-all", async (DeviceSupervisor sup) =>
{
    var (n, errs) = await sup.StartAllAsync();
    return Results.Ok(new { ok = true, started = n, errors = errs });
});

app.MapPost("/api/devices/stop-all", async (DeviceSupervisor sup) =>
{
    var (n, errs) = await sup.StopAllAsync();
    return Results.Ok(new { ok = true, stopped = n, errors = errs });
});

// ---- 健康检查（门禁 / 脚本用）----
app.MapGet("/health", (DeviceSupervisor sup) => Results.Ok(new
{
    ok = true,
    registryUrl = sup.RegistryUrl,
    redis = sup.RedisEndpoint,
}));

app.Logger.LogInformation(
    "sidecar 管理台已启动：中控清单 {Registry}，Redis {Redis}，sidecar {Dll}",
    builder.Configuration["Manager:RegistryUrl"] ?? "http://127.0.0.1:18080",
    builder.Configuration["Manager:RedisHost"] ?? "127.0.0.1",
    builder.Configuration["Manager:SidecarDll"] ?? "(默认)");

app.Run();
