# 设备 sidecar（.NET）

**中控只认一个端点（`tcp://工控机:8501`），换设备只换传输。**

中控不知道对面是串口封膜机还是 `Cluster.dll` 离心机 —— 这是"新接一台设备不改中控代码"
的最后一公里：中控不知道设备型号，sidecar 知道。

---

## 一、它解决什么问题

```
labos-kernel ──TCP(JSON+SEP)──> sidecar ──┬── 串口 12 字节帧      (S100 封膜机)
                                          ├── Cluster.dll + XML   (ASpin 2K 离心机)
                                          └── 转发给 Java 模拟器   (没有硬件的工位)
```

- **协议差异**全在这层吸收：JSON ↔ 二进制帧 / XML
- **设备知识**不写死：启动时读 `models.json`（由 Java 型号库导出）
- **错误码不翻译**：设备说 `0x8000000C`，原样带回中控；翻译（→ `EC-08-400C`）是
  `labos-kernel` 里 `DeviceErrorMapper` 的职责

> 为什么错误码不在 sidecar 翻译：现场的 sidecar 是 .NET 写的、可能还有别的语言实现，
> 而"这个码该转人工还是重试"是**业务策略**，必须只有一处定义。
> sidecar 多带一份翻译表 = 两份一定漂移。

---

## 二、跑起来

### 2.1 工位（没有硬件）—— 挂在模拟器前面

```bash
# 1) 起模拟器（设备侧）
cd labos-kernel
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --model s100 --device 501 --port 19501 --delay 1

# 2) 起 sidecar（中控侧）
cd sidecar-dotnet/Labos.Sidecar
dotnet run -- --model s100 --transport sim \
             --sim-host 127.0.0.1 --sim-port 19501 \
             --port 8501 --device 501

# 3) 中控照常连 127.0.0.1:8501 —— 和接真机完全一样
```

一键自检（自动起停、按手册时序断言，退出码即结果）：

```bash
./scripts/test-sidecar.sh
```

### 2.2 现场 —— 真设备

```bash
# S100 封膜机（Windows 工控机，USB 转串口）
Labos.Sidecar.exe --model s100 --transport serial --serial-port COM3 --baud 115200 --port 8501

# ASpin 2K 离心机（必须 Windows + 32 位，Cluster.dll 是 32 位）
Labos.Sidecar.exe --model aspin-2k --transport dll --dll "C:\Lab\Cluster.dll" --node PlateCtri --port 8501
```

### 2.3 调试单条命令

```bash
dotnet run -- --model s100 --transport sim --sim-port 19501 --device 501 \
             --once "MoveTray{\"value\":0}"
```

---

## 三、参数

| 参数 | 说明 |
|---|---|
| `--model <id>` | **必填**。型号 id（`s100` / `aspin-2k`），见 `--list` |
| `--models <path>` | 型号库路径，默认 exe 同目录 `models.json` |
| `--port <n>` | 监听端口，默认 8501 |
| `--transport sim\|serial\|dll` | 传输方式，默认 `sim` |
| `--sim-host/--sim-port` | sim 档转发目标（默认 127.0.0.1:19501） |
| `--serial-port` / `--baud` | 串口档：`COM3` / 115200 |
| `--read-ms` | 串口档最小读超时（默认 3000ms；每条命令会按型号的耗时自动放宽） |
| `--dll` / `--node` | dll 档：`Cluster.dll` 路径与模块节点名（默认 `PlateCtri`） |
| `--device` | 调试用设备号；sim 档要**和模拟器的 `--device` 一致** |
| `--list` | 列出已适配 / 待适配型号后退出 |
| `--once <Method[{json}]>` | 发一条命令后退出 |

---

## 四、`models.json` 从哪来

**不要手改**。它由型号库导出：

```bash
cd labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -cp out com.labos.kernel.device.DeviceModelExport > ../sidecar-dotnet/models.json
```

生成的 `models.json` **进版本库**（工控机上没有 Java 环境）。
`scripts/test-sidecar.sh` 会比对导出结果与仓库里的文件，不一致直接报错 ——
防止"改了型号库忘了导出，现场 sidecar 还在用过时的指令表"。

---

## 五、三种传输的边界

| 传输 | 能干什么 | 不能干什么 |
|---|---|---|
| `sim` | 把整条链（中控→sidecar→设备）在工位跑通 | 验证不了帧格式/字节序 |
| `serial` | S100 的 12 字节帧、低字节在前、错误帧第 5 字节 | 没接过真机，`ReadTimeout` 只能靠手册估 |
| `dll` | ASpin 的 XML + `Cluster.dll` | 只能在 Windows 跑；DLL 内部阻塞无法超时 |

### 已知限制（写在代码注释里，别指望它消失）

1. **`Cluster.dll` 是 32 位**：sidecar 必须以 32 位进程跑，否则 `DllNotFoundException`。
2. **DLL 调用阻塞不可超时**：`SendData` 是同步的，离心 10 分钟就一直阻塞。
   所以中控侧的超时必须 ≥ `120s + 离心秒数`，这也是型号里 `actionTimeoutMs` 的用途。
3. **`GetStatus` 是 sidecar 自己编的**：两台设备的手册都没有状态查询指令。
   sidecar 回的是"我活着"，**不是设备状态**；真要设备状态只能用 `CurrSpeed`/`CurrTime`。
4. **串口档没在真机上验证过**：帧是按手册拼的，字节序/报尾都照抄，
   但第一次接真机必须开 `--read-ms 8000` 并看日志里的原始帧。

---

## 六、日志怎么读

```
[sidecar 19:22:18] 监听 0.0.0.0:8501  型号=s100  传输=sim-bridge 127.0.0.1:19501  证据=MANUAL
[sidecar 19:22:18] 收到 Seal  id=4
[sidecar 19:22:18] 失败 Seal（4ms）：0x8000001A 托盘不在原点，无法封膜
```

- 每行都有**方法名 + 耗时 + 原始设备码**；
- 失败不吞：中控那侧看到的就是这一行的 `0x8000001A`；
- 证据等级不是 `MANUAL` 时启动会打 `⚠`（只能跑流程骨架，不能对接真机）。
