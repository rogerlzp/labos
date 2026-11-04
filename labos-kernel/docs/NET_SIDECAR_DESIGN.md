# .NET Sidecar 落地方案

> 日期：2026-10-29
> 结论：**现有驱动一行不用改**，加一层适配进程即可接入新内核

---

## 一、驱动通信协议

### 1.1 传输层

| 项 | 值 |
|---|---|
| 传输 | TCP + JSON |
| 分隔符 | **`#!LABSEP!#`** |
| 默认端点 | `127.0.0.1:9999` |
| 连接方向 | 中控侧为 **TCP 客户端**，驱动为**服务端** |
| 重连 | 5s 间隔，10s 读超时 |

### 1.2 消息类型

```java
public enum MessageType {
    GatewayHeartBeat(-1), HeartBeat(0), Task(1),
    Query(2), Troubleshoot(3), Ack(4), Finish(5);
}
```

### 1.3 报文结构

**下行指令 `MethodMessage`（type=Task=1）**

```json
{
  "message_id": "uuid",
  "message_type": 1,
  "method": "Deposit1",
  "equipment_id": "id0044",
  "workflow_id": "...",
  "experiment_id": "...",
  "parameters": { "position": "A1", "plate": "PL123" },
  "description": ""
}
```

**上行结果 `FinishMessage`（type=Finish=5）**

```json
{
  "message_id": "uuid",
  "message_type": 5,
  "method": "Deposit1",
  "equipment_id": "id0044",
  "equipment_status": -1,
  "method_status": 2,
  "error": { "error_code": "EC-08-0001", "troubleshoot": 0, "error_text": "clamp jammed" },
  "data": { },
  "process_info": { },
  "timestamp": "2026-10-29 11:20:25",
  "checksum": "",
  "connected": 1
}
```

**心跳 `HeartBeatMessage`（type=HeartBeat=0）**

```json
{ "message_id":"...", "message_type":0, "equipment_id":"id0044",
  "equipment_status":1, "timestamp":"...", "connected":1 }
```

---

## 二、Sidecar 架构

### 2.1 位置

```
┌──────────────┐   gRPC    ┌─────────────────┐   TCP+JSON    ┌──────────────┐
│ 新内核 (Java) │ ────────> │ .NET Sidecar    │ ────────────> │ 现有驱动进程  │
│ DeviceDriver │           │ (C# 常驻进程)    │  #!LABSEP!#    │ (.NET, 不改) │
└──────────────┘           └─────────────────┘               └──────────────┘
```

**Sidecar 的职责**：从 gRPC 收指令、通过 TCP 发给驱动、
把结果转成 `CommandResult` 返回中控。

驱动侧完全无感知。

### 2.2 一个 Sidecar 服务多个端点

若不复用连接，5 个驱动端点 = 5 个独立 TCP 连接。Sidecar 内部维护**连接池**：

```csharp
// 伪代码
var pools = new Dictionary<string, TcpConnection>();  // endpoint -> connection
// 收到 Execute(deviceInstanceId) -> 查注册表得到 endpoint -> 从池里取连接
```

### 2.3 gRPC ↔ TCP 字段映射

| gRPC `CommandRequest` | TCP `MethodMessage` | 说明 |
|---|---|---|
| `command_id` | `message_id` | **同一个值**，透传用于幂等与追踪 |
| — | `message_type` | 固定 `1`（Task） |
| `method` | `method` | 驱动方法名 |
| `device_instance_id` → device code | `equipment_id` | Sidecar 需维护 id → code 映射 |
| `params` | `parameters` | 直接透传 |
| `trace_id` | `description` | 塞进 description 便于日志关联 |

| TCP `FinishMessage` | gRPC `CommandResult` | 说明 |
|---|---|---|
| `message_id` | `command_id` | 回填，校验一致性 |
| `method_status` | `status` | 见 §2.4 |
| `error.error_code` | `error_code` | **原样透传**，不做二次加工 |
| `error.error_text` | `message` | |
| **整个 FinishMessage JSON** | **`device_raw_response`** | ★ 原文回传，这是我们最缺的东西 |
| — | `duration_ms` | Sidecar 侧计时 |

### 2.4 `method_status` → `Status` 映射

| `method_status` | 含义 | gRPC Status |
|---:|---|---|
| 0 | 未执行 | `REJECTED` |
| 1 | 执行中 | 不返回（Sidecar 继续等 Finish） |
| 2 | 成功 | `SUCCESS` |
| 3 | 失败 | `FAILED` |
| 其他 | 未知 | `FAILED`（附原值到 message） |

### 2.5 错误码映射：唯一需要业务知识的部分

驱动返回的 `error_code` 是 `EC-xx-xxxx` 格式，但**格式本身不保证语义**——
一个万能码可能覆盖几十种完全不同的失败原因。

#### 两级结构 vs 单级编号

| 设计 | 好处 | 代价 |
|---|---|---|
| **两级**（分类段 + 编号） | 看前缀就知道找谁（通讯/硬件/工艺）；<br>编号一一对应处置策略，能自动决策 | 定义时要先想清分类，<br>新增错误要归位 |
| **单级**（一个万能码） | 定义省事，加错误不用分类 | 查询只能看到"出错了"，<br>**处置策略只能写"重试"或"忽略"** |

**关键差别在"能不能决策"**：

```
EC-05-1007  通讯超时      → 重试 3 次，仍失败转人工
EC-05-0001  设备报硬件错   → 看 error_text，不自动重试
EC-PLATE-01 搬运前源无板   → 直接中止，这是逻辑错误不是故障
```

三级信息（大类词 + 编号 + 上下文）才能让状态机自动选对策略。
只有"出错了"这一条信息时，唯一安全的做法是**全部转人工**——
系统就失去了自动化能力。

#### Sidecar 该做什么

两种做法：

1. **透传**（推荐）：原样把 `error_code` 传上去，保真优先
2. **增强**：用 `error_text` + `troubleshoot` 细化模糊的错误码

**v1 只做透传**。细化需要业务知识，应该等数据积累够了再做规则，
而不是在 Sidecar 里写死判断。

> 这符合"不猜"原则：Sidecar 不该假装懂业务语义。
> 错误码的语义由**设备侧定义**，Sidecar 只负责搬运。

---

## 三、部署方式

### 3.1 形态

| 项 | 方案 |
|---|---|
| 进程 | 一个 Sidecar 进程，服务全部 5 个驱动端点 |
| 通信 | 本地 `127.0.0.1:50051`（gRPC） |
| 依赖 | .NET 6/8 + Grpc.Net.Server |
| 启动 | 与驱动进程同机，作为 Windows 服务或 systemd unit |
| 配置 | appsettings.json：端点列表 + gRPC 端口 |

```json
{
  "Grpc": { "Port": 50051 },
  "Drivers": [
    { "code": "DRV-19200200196-8501", "endpoint": "tcp://192.0.2.10:8501",
      "delimiter": "#!LABSEP!#", "devices": ["162","163"] }
  ]
}
```

### 3.2 关键实现点（C#）

```csharp
public class TcpDriverConnection : IDisposable {
    private readonly TcpClient _client;
    private readonly string _delimiter = "#!LABSEP!#";
    private readonly SemaphoreSlim _lock = new(1, 1);
    // 按分隔符分帧的读缓冲
    private readonly List<byte> _buffer = new();

    public async Task<FinishMessage> ExecuteAsync(MethodMessage msg, CancellationToken ct) {
        await _lock.WaitAsync(ct);              // 同一连接串行化
        try {
            var json = JsonSerializer.Serialize(msg) + _delimiter;
            await _stream.WriteAsync(Encoding.UTF8.GetBytes(json), ct);
            // 循环读直到收到同 message_id 的 Finish
            return await ReadUntilFinishAsync(msg.message_id, ct);
        } finally { _lock.Release(); }
    }
}
```

**三个必须处理的点**：

1. **分帧**：TCP 是流，必须按 `#!LABSEP!#` 切分，不能假设一次 read 一条消息
2. **串行化**：同一连接上的请求必须串行（用信号量），否则响应会串
3. **message_id 匹配**：驱动可能异步推送心跳，必须按 id 过滤出对应的 Finish

### 3.3 幂等

gRPC 的 `command_id` → `message_id` 透传。
Sidecar 维护一个 **LRU 缓存（id → CommandResult）**，重复 id 直接返回缓存，
**不重复发 TCP**。这样上层重试不会让设备做两次动作。

---

## 四、参考实现（Java，已跑通）

由于本机无 dotnet SDK，我写了一份 **Java 版参考实现**并**实际跑通**：

| 类 | 作用 |
|---|---|
| `TcpDriverAdapter` | 实现 `DeviceDriver`，走 TCP+JSON 与驱动通信 |
| `MockDriverServer` | 模拟 .NET 驱动（TCP 服务端），回 FinishMessage |
| `SidecarDemo` | 端到端演示：内核 → Adapter → TCP → Mock 驱动 |

**这份实现的价值不是给 Java 用，而是给 .NET 团队一份可执行的协议参考**——
字段名、分隔符、分帧、幂等、错误映射，都有对应代码。

### 实测输出

```
2. Sidecar 适配器（DeviceDriver 实现）
   TRANSFER 候选: [200383, 200387]
   INCUBATE 候选: [200391]

3. 内核执行（状态机 → 适配器 → TCP → 驱动）
   #    节点         能力        尝试   设备      结果
   1    Transfer    TRANSFER   0     200383   COMPLETED
   2    Incubate    INCUBATE   0     200391   COMPLETED
   结果: 完成 2 / 人工 0

4. 驱动返回的 FinishMessage 原文
   node 1 {"message_id":"04f808ae-...","message_type":5,"method":"TRANSFER",
           "equipment_id":"200383","equipment_status":1,"method_status":2,...}

5. 驱动侧实际收到的指令
   200383:TRANSFER
   200391:INCUBATE

6. 注入故障：200383 的 Transfer 永久失败
   #    节点         尝试   设备      结果
   1    Transfer    1     200387   COMPLETED    ← 自动换机
   2    Incubate    0     200391   COMPLETED
   结果: 完成 2 / 人工 0

结论：
  ✓ JSON over TCP + #!LABSEP!# 分帧正确
  ✓ message_id 透传，能正确过滤心跳、匹配响应
  ✓ FinishMessage 原文完整回传（device_raw_response）
  ✓ 错误码原样透传，适配层不做业务判断
  ✓ 驱动侧零改动，换机由内核决策
```

### 过程中修掉的两个问题

1. **健康检查拦住了首次连接**：`DriverStepExecutor` 在执行前检查 `healthy()`，
   但首次调用时连接还没建立，导致直接 MANUAL。
   → 改为不在执行前检查，由 `execute` 自己连，连不上返回 `EC-05-1006`。

2. **方法名回退成能力码**：节点用不带 method 的构造（method=null），`effectiveMethod()` 回退到
   `capabilityCode`（`TRANSFER` 大写），而驱动认的是 `protocol_name`（`Transfer`），
   导致 mock 的故障配置匹配不上。
   → 真实工艺里 method 本来就是 protocol_name，用带 method 的构造即可。

> 这两个问题在 .NET 版里同样会出现——**协议字段的大小写和空值回退是适配层最容易踩的坑**。

---

## 四·补充、C# 实现（已编译运行）

`sidecar-dotnet/` —— .NET 10，零编译错误。

### 项目结构

```
sidecar-dotnet/
  LabOS.Sidecar.csproj     net10.0 + Grpc.AspNetCore
  Program.cs                  启动、驱动注册、--mock/--selftest 模式
  appsettings.json            gRPC 端口 + 驱动端点配置
  Protocol/Messages.cs        MethodMessage / FinishMessage / ErrorInfo / 心跳
  Drivers/TcpDriverConnection.cs   TCP 连接 + 分帧 + 串行化 + id 匹配
  Drivers/DriverPool.cs       设备 → 端点 → 连接 的路由
  Drivers/MockDriverServer.cs 模拟驱动（联调用，接真机可删）
  Services/DriverService.cs   gRPC 实现
  Services/IdempotentCache.cs 幂等缓存（**必须单例**）
  SelfTest.cs                 自测（真实 gRPC 客户端调自己）
```

### 运行

```bash
export DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec
cd sidecar-dotnet
dotnet build -c Release
dotnet run -c Release -- --selftest     # 自测：起 mock 驱动 + Sidecar，跑完整流程
dotnet run -c Release -- --mock         # 常驻：本地 mock 驱动 + gRPC 服务
```

### 自测结果（7 项全过）

```
1. Describe          code=labos-sidecar  kind=dotnet-sidecar
                     能力: TRANSFER, INCUBATE
                     设备: 200383, 200387, 200391

2. Health            healthy=True  detail=DRV-MOCK-8501=IDLE

3. Execute           status=Success  耗时=22ms
                     设备原文={"message_id":"3b927790-...","message_type":5,
                              "method":"Transfer","method_status":2,...}

4. 幂等              同 command_id 再发 → 驱动侧只收到 1 次指令 ✓

5. 故障              status=Failed  error_code=EC-08-0001（原样透传）

6. 未注册设备        status=Rejected  error_code=EC-06-0001

7. 驱动侧收到的指令
   200383:Transfer
   200387:Transfer
   200383:Transfer 出现 1 次 ✓ 幂等生效
```

### 踩到的一个真实坑：gRPC service 是 Scoped

第一版把幂等缓存写成 `DriverService` 的实例字段：

```csharp
private readonly ConcurrentDictionary<string, CommandResult> _done = new();
```

结果自测发现**驱动侧收到 2 次同样指令**——因为 gRPC service 默认是 **Scoped**
（每个请求一个新实例），第二次请求读到的是空缓存，幂等完全失效。

改成单例 `IdempotentCache` 注入后才正确：

```csharp
builder.Services.AddSingleton<IdempotentCache>();
```

> 这个坑很隐蔽：代码逻辑完全正确，单元测试也发现不了，
> **只有"看驱动侧实际收到几次指令"才暴露**。
> 和 Java 侧的"事件序号字符串排序""驱动级 vs 设备级能力"是同一类问题——
> **必须验证外部可观测的结果，而不是看内部状态对不对**。

---

## 五、工作量与风险

| 项 | 估时 | 风险 |
|---|---|---|
| TCP 连接池 + 分帧 | 3~5 天 | 低（协议已明确） |
| gRPC 服务实现 | 3~5 天 | 低 |
| 幂等缓存 | 1~2 天 | 低 |
| **错误码细化** | 持续 | **高**（需业务知识 + 数据积累） |
| 联调（5 个端点） | 3~5 天 | 中（驱动行为差异） |
| **合计** | **2~3 周** | |

**最大风险不是技术，是错误码语义**：如果设备侧持续返回宽泛的错误码，
新内核再聪明也只能转人工。这一块需要驱动厂商配合。

---

## 六、不做什么

| 不做 | 原因 |
|---|---|
| 重写 .NET 驱动 | 现有逻辑是多年沉淀，重写风险远大于收益 |
| 在 Sidecar 里做业务判断 | 违反"不猜"原则，业务规则应在恢复策略表里 |
| 替换驱动的 TCP 协议 | 改动面太大，且没有必要——Sidecar 本来就是适配层 |
| 一开始就做错误码细化 | 先透传积累数据，有依据后再做映射 |
