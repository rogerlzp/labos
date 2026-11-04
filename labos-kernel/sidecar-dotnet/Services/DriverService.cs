using Google.Protobuf.Collections;
using Grpc.Core;
using LabOS.Driver.V1;
using LabOS.Sidecar.Drivers;
using LabOS.Sidecar.Protocol;

namespace LabOS.Sidecar.Services;

/// <summary>
/// gRPC 服务实现：把 proto 契约翻译成驱动的 TCP/JSON 协议。
///
/// <para>核心约束：<b>适配层不做业务判断</b>。
/// 错误码原样透传，成功/失败只按 method_status 判；能不能重试、该不该换机，
/// 都由内核的恢复策略决定，不在这里猜。</para>
/// </summary>
public sealed class DriverService : DeviceDriver.DeviceDriverBase
{
    private readonly DriverPool _pool;
    private readonly ILogger<DriverService> _log;
    private readonly IConfiguration _cfg;

    /// <summary>幂等缓存（单例注入，不能放在 Scoped 的 service 里）。</summary>
    private readonly IdempotentCache _cache;

    public DriverService(DriverPool pool, ILogger<DriverService> log, IConfiguration cfg,
        IdempotentCache cache)
    {
        _pool = pool;
        _log = log;
        _cfg = cfg;
        _cache = cache;
    }

    public override Task<DriverDescriptor> Describe(DescribeRequest request, ServerCallContext context)
    {
        var d = new DriverDescriptor
        {
            Code = "labos-sidecar",
            Endpoint = "grpc://0.0.0.0:" + _cfg.GetValue("Sidecar:GrpcPort", 50051),
            Protocol = "grpc",
            DriverKind = "dotnet-sidecar"
        };

        foreach (DriverEndpointConfig c in _pool.All)
        {
            foreach (string cap in c.Capabilities)
            {
                if (!d.Capabilities.Contains(cap))
                {
                    d.Capabilities.Add(cap);
                }
            }
            foreach (long id in c.Devices)
            {
                d.DeviceInstanceIds.Add(id);
            }
            d.Metadata[c.Code] = c.Endpoint;
        }

        return Task.FromResult(d);
    }

    public override Task<HealthResponse> Health(HealthRequest request, ServerCallContext context)
    {
        var resp = new HealthResponse { Healthy = true, UptimeSeconds = Environment.TickCount64 / 1000 };
        foreach (DriverEndpointConfig c in _pool.All)
        {
            TcpDriverConnection? conn = _pool.ByCode(c.Code);
            if (conn is null)
            {
                resp.Healthy = false;
                resp.Detail += $"{c.Code}=MISSING;";
                continue;
            }
            // 懒连接：还没建过连接不算不健康，标 IDLE 即可
            resp.Detail += $"{c.Code}={(conn.Healthy ? "OK" : "IDLE")};";
        }
        return Task.FromResult(resp);
    }

    public override async Task<CommandResult> Execute(CommandRequest request, ServerCallContext context)
    {
        // 幂等：已成功过的同 id 直接返回缓存，不重复发 TCP
        if (_cache.TryGet(request.CommandId, out CommandResult? cached) && cached is not null)
        {
            _log.LogInformation("幂等命中 {Id}，不重复下发", request.CommandId);
            return cached;
        }

        TcpDriverConnection? conn = _pool.ForDevice(request.DeviceInstanceId);
        if (conn is null)
        {
            return new CommandResult
            {
                CommandId = request.CommandId,
                Status = CommandResult.Types.Status.Rejected,
                ErrorCode = "EC-06-0001",
                Message = $"设备 {request.DeviceInstanceId} 没有绑定驱动端点"
            };
        }

        var msg = new MethodMessage
        {
            MessageId = request.CommandId,       // 透传，用于匹配与追踪
            MessageType = MessageTypes.Task,
            Method = request.Method,
            EquipmentId = request.DeviceInstanceId.ToString(),
            Description = request.TraceId,
            Parameters = new Dictionary<string, string>(request.Params)
        };

        int timeout = request.TimeoutMs > 0
            ? (int)request.TimeoutMs
            : _cfg.GetValue("Sidecar:DefaultTimeoutMs", 30000);

        long t0 = Environment.TickCount64;
        var (finish, raw, error) = await conn.ExecuteAsync(msg, timeout, context.CancellationToken)
            .ConfigureAwait(false);
        long elapsed = Environment.TickCount64 - t0;

        if (error is not null)
        {
            bool timeoutFlag = error.StartsWith("EC-05-1007", StringComparison.Ordinal);
            return new CommandResult
            {
                CommandId = request.CommandId,
                Status = timeoutFlag ? CommandResult.Types.Status.Timeout : CommandResult.Types.Status.Failed,
                ErrorCode = error.Split(':')[0],
                Message = error,
                DurationMs = elapsed
            };
        }

        if (finish is null)
        {
            return new CommandResult
            {
                CommandId = request.CommandId,
                Status = CommandResult.Types.Status.Timeout,
                ErrorCode = "EC-05-1007",
                Message = "无响应",
                DurationMs = elapsed
            };
        }

        var result = new CommandResult
        {
            CommandId = request.CommandId,
            DurationMs = elapsed,
            DeviceRawResponse = raw ?? "",        // ★ 原文回传，复盘唯一依据
            Message = finish.Error?.ErrorText ?? ""
        };

        if (finish.IsSuccess)
        {
            result.Status = CommandResult.Types.Status.Success;
            _cache.Put(request.CommandId, result);
        }
        else
        {
            result.Status = CommandResult.Types.Status.Failed;
            // 错误码原样透传；驱动没给就退 EC-08-0001，绝不编造语义
            result.ErrorCode = string.IsNullOrWhiteSpace(finish.Error?.ErrorCode)
                ? "EC-08-0001"
                : finish.Error!.ErrorCode!;
        }

        return result;
    }

    public override Task<AbortResponse> Abort(AbortRequest request, ServerCallContext context)
    {
        // 协议没有 Abort 消息类型，只能靠上层超时
        return Task.FromResult(new AbortResponse
        {
            Accepted = false,
            Message = "协议不支持 Abort，请依赖超时"
        });
    }
}
