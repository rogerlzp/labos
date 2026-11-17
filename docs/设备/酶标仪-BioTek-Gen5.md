# 酶标仪（BioTek Gen5 · OLE Automation）

> **依据等级：MANUAL**（有厂商官方 117 页编程手册 + 错误码头文件，方法/参数逐条可核）
> 型号库 id：`biotek-gen5`｜类型：`reader`｜错误码块：`0x43`
> 落库：`pg_rebuild/migration/20261117_device_register_biotek_lhc_gen5.sql`
> 模拟器：`Gen5Behavior`（型号行为）+ `DeviceProfile["reader"]`（参数边界）

---

## 1. 基本信息

| 项 | 值 |
|---|---|
| 厂商 | BioTek（Agilent） |
| 覆盖机型 | Gen5 软件支持的读板机/成像仪：Synergy H1/H4/HTX/Mx/Neo/Neo2、Epoch、Epoch 2、Cytation1/3/5、Lionheart FX、800TS、ELx800/808、Powerwave/PowerwaveXS 2、FLx800、Eon |
| 手册 | **Gen5 OLE Automation Programmer's Guide**（117 页，© 2006-2018 BioTek）+ **`BTIStatusCodes.h`**（546 个 `#define`） |
| 手册原件 | `private/协议文档/OLE Automation/`（含 C# / C++ MFC / VB6 三套示例，**不入库**） |
| 本文性质 | 我方解读；与原件冲突以原件为准 |

### 1.1 关键认识：**中控不和仪器说话，和 Gen5 说话**

Gen5 是厂商的上位机软件，它**独占**仪器的 USB/串口。所以：

* 中控 → `Gen5.Application`（本机 COM）→ 仪器；
* 仪器配置（机型/串口/波特率）在 Gen5 里，不在我们这里；
* 一台仪器只能有一个 Application 实例配置它（手册 §1.2.1.1：同 COM1 只能被一个实例配置）。

⇒ sidecar 必须**和 Gen5 装在同一台 Windows 机器上**。

---

## 2. 物理与通讯

```
中控 ──TCP/JSON──▶ sidecar ──OLE Automation(COM)──▶ Gen5.exe ──USB/串口──▶ 读板机
```

* 传输档：`--transport gen5`（`Gen5OleTransport`），ProgID `Gen5.Application`。
* 用**晚绑定**（`Type.GetTypeFromProgID` + `InvokeMember`），不引用 `Interop.Gen5.dll`：
  否则整个 sidecar 工程只能在装了 Gen5 的 Windows 上编译（开发机 macOS 编不过）。

### 2.1 两条容易踩死的"路径"规则（手册 §1.2.3）

Gen5 有两种存储模式，路径**不能混用**：

| 模式 | 协议/实验路径写法 | 判断方法 |
|---|---|---|
| 数据库（SharedDB） | `DB:\Protocols\example1.prt` | `Application.DatabaseFileStorage == true` |
| Windows 文件系统 | `C:\Protocols\example1.prt` | 同上为 `false` |

用错模式必然 `-100/-101/-104`。多个实例同库共存是支持的（手册 §1.2.3.3），但性能会下降。

### 2.2 ReaderType（手册 §2.1.2.3/2.1.2.4）

`ConfigureUSBReader(ReaderType, 序列号)`，`-1` = 只接一台时自动选：

| 值 | 机型 | 值 | 机型 | 值 | 机型 |
|---|---|---|---|---|---|
| 2 | ELx800 | 10 | Synergy2 | 17 | Eon |
| 3 | ELx808 | 11 | PowerwaveXS 2 | 18 | Synergy Neo |
| 6 | Synergy HT | 13 | Synergy Mx | 19 | Cytation3 |
| 7 | FLx800 | 14 | Epoch | 20 | Synergy HTX |
| 8 | Powerwave | 15 | Synergy H4 | 21 | Cytation5 |
| 9 | Clarity（**Gen5 已不支持**） | 16 | Synergy H1 | 22 | Epoch 2 |
| | | | | 23 | Synergy Neo2 |
| | | | | 24 | Lionheart FX |
| | | | | 25 | 800TS |
| | | | | 26 | Cytation1 |

> 手册提醒：如果配置了一个**物理上没接**的机型，Gen5 会配置成功但**只能做模拟读板**。
> 所以 `Configure*` 之后必须 `TestReaderCommunication`，不能只看配置没报错。

---

## 3. 指令集

### 3.1 对象模型（这是和洗板机最大的区别）

```
Application
 ├─ Configure*Reader / TestReaderCommunication / GetReaderStatus / 温控 / CarrierIn|Out
 └─ NewExperiment / OpenExperiment ──▶ Experiment
                                          ├─ Plates ──▶ Plate ─StartRead─▶ PlateReadMonitor
                                          │            (ReadStatus / GetDataSetResults)
                                          └─ Close / Save / SaveAs / PlateLayout
```

**释放顺序是硬约束**：`Plate → Plates → Experiment`（手册 §1.2.1.3~1.2.1.4）。顺序错了会抛异常。

### 3.2 方法表（43 条，型号库里的 49 条记录 = 43 真方法 + 6 别名）

| 层 | 型号库 cmd | LabOS 别名 | 参数 | 返回 |
|---|---|---|---|---|
| App | `GetSupportedReaders` | `GetSupportedReaders` | — | 机型/类型/通讯方式数组 |
| App | `ConfigureSerialReader` | `ConfigureSerialReader` | `ReaderType, ComPort, BaudRate` | — |
| App | `ConfigureUSBReader` | `ConfigureUSBReader` | `ReaderType, 序列号（单台可空）` | — |
| App | `ConfigureOleReader` | `ConfigureOleReader` | `ReaderType` | — |
| App | `TestReaderCommunication` | `TestReaderCommunication` / `Connect` | — | `long`（1=BTI_OK） |
| App | `GetReaderStatus` | `GetReaderStatus` / `GetStatus` | — | `long` |
| App | `GetLastReaderError` | `GetLastReaderError` | — | `long` |
| App | `GetReaderCharacteristics` | `GetReaderCharacteristics` | `id 0..11, index, out Value` | variant |
| App | `StartSystemTest` | `StartSystemTest` / `SelfCheck` | — | `SystemTestMonitor` |
| App | `RunReaderControlCommand` | `RunReaderControl` | `long command`（0/2/4/8/16/32/64/128/512/1024/2048） | — |
| App | `CarrierIn` | `CarrierIn` / `Load` | — | — |
| App | `CarrierInEx` | `CarrierInEx` | `板型名, 是否带盖` | — |
| App | `CarrierOut` | `CarrierOut` / `Unload` | — | — |
| App | `GetCurrentTemperature` | `GetTemperature` | `out 值, out 状态` | — |
| App | `SetTemperatureSetPoint` | `SetTemperature` | `bool 孵育, double 目标, double 梯度` | — |
| App | `GetTemperatureSetPoint` | `GetTemperatureSetPoint` | `out 目标, out 梯度` | — |
| Exp | `NewExperiment` | `NewExperiment` | `协议路径` | `Experiment` |
| Exp | `NewExperimentEx` | `NewExperimentEx` | `enum 定义类型, 定义 XML` | `Experiment` |
| Exp | `OpenExperiment` | `OpenExperiment` | `实验路径` | `Experiment` |
| Exp | `Close` | `CloseExperiment` | — | — |
| Exp | `Save` / `SaveAs` | `SaveExperiment` / `SaveExperimentAs` | 目标路径（可空） | — |
| Exp | `GetPlateLayout` / `SetPlateLayout` | 同名 | `ProtocolID` / `BTIPlateLayout XML` | XML |
| App | `GetPlateTypeNames` | `GetPlateTypeNames` | `short 选择标志` | 名字数组 |
| Plate | `StartRead` | `Read` / `ReadPlate` | — | `PlateReadMonitor` |
| Plate | `StartReadEx` | `ReadEx` | `bool 180° 旋转` | `PlateReadMonitor` |
| Plate | `StartSimulatedRead` | `SimulatedRead` | — | `PlateReadMonitor` |
| Plate | `AbortRead` | `AbortRead` / `Stop` | — | — |
| Plate | `KeepPlateInAfterRead` | `KeepPlateInAfterRead` | — | — |
| Plate | `SetPartialPlate` | `SetPartialPlate` | `BTIPartialPlate XML` | — |
| Plate | `ImportSampleIDs` | `ImportSampleIDs` | 文本文件路径 | — |
| Plate | `GetDataSetNames` | `GetDataSetNames` | `ProtocolID` | 数据集名数组 |
| Plate | `GetDataSetInfo` | `GetDataSetInfo` | — | 名称/小数位/单位/溢出 |
| Plate | `GetDataSetResults` | `GetResult` | `ProtocolID, 数据集名, 索引` | XML |
| Plate | `GetRawData` | `GetRawData` | — | 原始数据（须先开 `DataExportEnabled`） |
| Plate | `FileExportEx` | `ExportFile` | `导出器名, 路径` | 文件名 |
| Plate | `PrintReportEx` | `PrintReport` | `报表名, 是否弹框` | — |
| Monitor | `ReadInProgress` | `GetReadProgress` | — | `bool` |
| Monitor | `GetErrorCode` | `GetReadErrorCode` | `index` | `long`（-601 = 仪器报错） |
| Monitor | `GetErrorMessage` | `GetReadErrorMessage` | `index` | `string` |
| Monitor | `GetReaderError` | `GetReaderError` | `index` | `long`（仅 -601 时有意义） |

`GetReaderCharacteristics` 的 id 语义（手册 §2.1.2.6）：

| id | 含义 | id | 含义 |
|---|---|---|---|
| 0 | 是否支持吸收读 | 6 | 最低孵育温度 |
| 1 | 最小波长 (nm) | 7 | 最高孵育温度 |
| 2 | 最大波长 (nm) | 8 | 最大梯度（0=不支持） |
| 3 | 吸收读法数量 | 9 | 是否支持振荡 |
| 4 | 读法名（按 index） | 10 | 序列号 |
| 5 | 是否支持温控 | 11 | 机型名 |

### 3.3 一条读板命令为什么是"启动 + 轮询 + 取数"

手册 §2.4.2.1：`StartRead` **立刻返回**一个 `PlateReadMonitor`，不阻塞。
中控的一条节点命令要"读完才算完"，所以 sidecar 把它收成一次阻塞调用：

1. `Plate.StartRead()` → `PlateReadMonitor`；
2. 每 500 ms 读 `monitor.ReadInProgress`，直到 `false` 或超时（型号 `actionTimeoutMs` = 600 s）；
3. 看 `monitor.ErrorsCount`：>0 则取 `GetErrorCode/GetErrorMessage`；
   **若 code == `-601`，必须再取 `GetReaderError` 才是仪器真正的错误码**（手册明确要求）；
4. 成功时回 `Plate.ReadStatus`（原样整数）。

> **成功与否看 `Plate.ReadStatus`，不是看有没有抛异常** —— 手册 §1.2.2.2 原话。

### 3.4 sidecar 替你做的两件事（免得每个流程都记）

1. **开读前自动置 `Application.DataExportEnabled = true`**（幂等）。
   手册要求这个开关在**读板之前**打开——读完再置已经来不及，数据不会回填。忘了它，
   `GetRawData` 就是空的。所以放在 `Read` 里统一处理，而不是让流程自己记。
2. **多出参方法按手册给足槽位**。COM 的 `VARIANT*` 出参个数是**硬契约**，
   少给一个就直接抛参数错误：
   * `GetSupportedReaders` 3 个出参（名字 / 类型 / 通讯方式）；
   * `GetDataSetInfo` 11 个出参（数据集名 / 小数位 / 单位 / 动力学次数与间隔 / 光谱参数 / 扫描行列…）；
   * `GetRawData` 1 入参 + 9 出参（行 / 列 / 动力学索引 / 波长索引 / 水平与垂直索引 / 值 / 一级与二级状态）。

### 3.5 读板期间禁止做的事（手册多处重复）

删板、导出、打印报告、`CarrierIn/Out`、`Configure*Reader`、关闭实验。
我们的 sidecar **直接挡成 `EC-05-2303`**，不去试探厂商组件的容错 —— 试探的代价是"数据静默错"。

---

## 4. 错误码

`BTIStatusCodes.h` 是**负数分段编号**（546 条），不是 `0x8000000C` 那种带结构的十六进制码。
所以**不能**用它取低字节拼中控码（`-526` 与 `-782` 低字节都是 `0xF2`，会撞码）⇒
型号库用 `errorAs(...)` 显式给出中控码：`EC-08-43-<绝对码>`。

### 4.1 已登记（现场会遇到的那些）

| 厂商码 | 名称 | 中控码 | 处置 |
|---|---|---|---|
| `-1` | `BTI_FAIL` | `EC-08-43-001` | MANUAL |
| `-3` | `BTI_USER_CANCELLED_OPERATION` | `EC-08-43-003` | MANUAL（有人在界面上点取消） |
| `-5` | `BTI_INVALID_REQUEST` | `EC-08-43-005` | ABORT |
| `-13` | `BTI_INSUFFICIENT_PERMISSIONS` | `EC-08-43-013` | MANUAL（Secure/Clinical 未登录） |
| `-14` | `BTI_DATABASE_ERROR` | `EC-08-43-014` | MANUAL |
| `-100` | 文件不存在 | `EC-08-43-100` | FIX_PARAM |
| `-101` | 文件打不开 | `EC-08-43-101` | FIX_PARAM |
| `-103` | 文件只读 | `EC-08-43-103` | FIX_PARAM |
| `-105` | 文件被锁 | `EC-08-43-105` | RETRY |
| `-106` | 共享冲突 | `EC-08-43-106` | RETRY |
| `-200` | 记录格式不合法 | `EC-08-43-200` | FIX_PARAM |
| `-301/-302` | 字段低于下限/超上限 | `EC-08-43-301/302` | FIX_PARAM |
| `-505/-506/-507` | 串口写失败/读超时/校验和错 | `EC-08-43-505/506/507` | RETRY |
| `-513` | 端口未打开 | `EC-08-43-513` | FIX_PARAM |
| `-522` | 串口被占用 | `EC-08-43-522` | MANUAL |
| `-526` | 端口不存在 | `EC-08-43-526` | FIX_PARAM |
| `-527` | 端口被别的用户锁住 | `EC-08-43-527` | MANUAL |
| `-529` | 仪器未连接 | `EC-08-43-529` | RETRY |
| `-531` | USB 设备被占用 | `EC-08-43-531` | MANUAL |
| `-600` | 未知机型 | `EC-08-43-600` | FIX_PARAM |
| `-601` | 仪器报错（**要再取 ReaderError**） | `EC-08-43-601` | MANUAL |
| `-602` | 仪器不应答 | `EC-08-43-602` | RETRY |
| `-606` | 读板超时 | `EC-08-43-606` | RETRY |
| `-608` | 波长不支持 | `EC-08-43-608` | FIX_PARAM |

分段结构（读码时先看段，能缩小范围）：`-100..-199` 文件 / `-200..-299` 记录 / `-300..-399` 字段 /
`-400..-449` 对象 / `-450..-499` OLEDispatch / `-500..-599` 串口 / `-600..-699` 读板机专属。

> 未登记的码统一 `EC-08-9001`（转人工），原始码保留在 `device_raw_response`。

### 4.2 适配层码

| 中控码 | 含义 | 处置 |
|---|---|---|
| `EC-05-2301` | 没配置读板机就进出板/读板 | ABORT |
| `EC-05-2302` | 没有打开的 Experiment / 没有读数集 | ABORT |
| `EC-05-2303` | 读板进行中调了冲突方法 | FIX_PARAM |
| `EC-05-2304` | 找不到 Gen5 的 COM 组件 | MANUAL |
| `EC-05-2305` | Gen5 弹了交互框（登录/选板型/选文件），无人应答 | MANUAL |
| `EC-05-2306` | `DataExportEnabled` 未开启，取不到原始数据 | FIX_PARAM |
| `EC-05-2307` | 承载器上没有板就读 | FIX_PARAM |

---

## 5. 点位

酶标仪同样是**单口设备**（承载器进出共用一个口）：

| 点位 code | `kind` | `point_role` | `is_entry` | `is_exit` | `robot_reachable` | 说明 |
|---|---|---|---|---|---|---|
| `GEN5-BOTH` | `storage` | `both` | ✅ | ✅ | ✅ | 承载器进出口 |

* 型号库 `doorCount=1`（有前门），但**门由 Gen5 软件控制**（`CarrierIn/CarrierOut` 或 `RunReaderControlCommand(2/512/1024)`），
  所以点位不设 `requires_door`，`device_access` 也不必建 —— 机械臂只和承载器打交道。
* 板型/带盖信息通过 `CarrierInEx(plateTypeName, lidOnPlate)` 传给 Gen5（机型老于 Synergy2 会忽略）。
* 载板台槽位数：`DeviceProfile["reader"].slots = 4`（多槽位机型的边界值；单槽机型相当于只用 1 个）。
* 机械臂坐标 `robot_point` 现场示教。

---

## 6. 模拟器如何对齐

| 能力 | 实现位置 |
|---|---|
| 方法集 | `DeviceModels`（`models.json`）；有型号时型号是方法集权威 |
| 对象树与时序 | `sim/Gen5Behavior.java` |
| 参数边界 | `DeviceProfile["reader"]`（波长 190–850 nm、温度 20–50 °C、slots=4、分方法耗时） |

`Gen5Behavior` 实现的约束：

| 场景 | 模拟器行为 |
|---|---|
| 未 Configure / 未 Test 就进出板或读板 | `EC-05-2301` |
| 没有 Experiment 就 CarrierIn | `EC-05-2302`（手册：Plate 属于 Experiment） |
| 承载器没板就读 | `EC-05-2307` |
| 读板中再 Read / 进出板 / 关实验 / 导出 | `EC-05-2303` |
| `NewExperiment` 缺路径 | `-100` |
| `SetTemperature` 超出 20–50 °C | `-302` |
| 取结果时没有可用读数集 | `EC-05-2302` |
| 轮询 3 次后 `readInProgress=false` | 故意让"不轮询就当成功"暴露 |
| `GetResult` | 返回 **96 孔确定性模拟 OD**（结构正确，值不可用于任何定量结论） |
| `GetReaderCharacteristics` | 按 id 返回模拟值（0→true、1→200、2→999、10→序列号…） |

**不模拟**：真实光学（吸光度分布/线性/串扰）、成像、动力学曲线的时间形状。
模拟值是按板号算的确定性函数 —— 只为验证"数据能不能取到、结构对不对"。

---

## 7. 未解问题（接真机前必须确认）

1. **`Gen5ReadStatus` 的 6 个数值**：手册正文只给了枚举名、没给完整数值表 ⇒ sidecar 原样回传整数，
   不做"猜名字"（猜错比不回更糟）。拿到 `Interop` 或现场读数后补。
2. **`-601` 双跳**：仪器码要用 `PlateReadMonitor.GetReaderError` 取。我们的 sidecar 已实现，
   但**模拟器造不出真实的仪器码**，只能在现场验。
3. **登录弹窗**：Gen5 Secure/Clinical 若要求 OLE 登录，`NewExperiment/OpenExperiment` 会弹框 ⇒
   无人值守会**永久挂住**（`EC-05-2305`）。现场必须关掉 OLE 登录要求，或用 `SetClientWindow`。
4. **动力学读板总时长**：取决于协议（可上小时级）。型号 `actionTimeoutMs=600 s` 是保守值，
   需要按最长协议调大，否则会被判 `-606`。
5. **多实例**：手册允许同库多实例，但一台仪器只能被一个实例配置 ⇒ 多台读板机要多个 sidecar 进程。
6. **`CarrierInEx` 的板型名**：必须是 Gen5 认识的板型（`GetPlateTypeNames` 查），名字错会抛异常。

---

## 8. 怎么接入（五步）

1. **型号库**：已完成（`DeviceModels.TABLE["biotek-gen5"]`）→ 重导 `models.json`。
2. **错误码字典**：`java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql` → 替换 `95_seed_data.sql` 一节。
3. **入库**：`psql -v ON_ERROR_STOP=1 -f pg_rebuild/migration/20261117_device_register_biotek_lhc_gen5.sql`
   （型号 + 43 个方法 + 点位模板）。
4. **部署到岛 + 起 sidecar**（在装了 Gen5 的 Windows 上）：
   ```bash
   Labos.Sidecar.exe --model biotek-gen5 --transport gen5 --port 8502 --poll-ms 500
   ```
   开发机（无 COM）用 `--transport sim --sim 127.0.0.1:19652`：
   ```bash
   java -cp "out:lib/*" com.labos.kernel.sim.DeviceSimulator \
        --port 19652 --profile reader --model biotek-gen5 --device SIM-GEN5 --delay 20
   ```
5. **流程顺序**（照抄即可）：
   ```
   ConfigureUSBReader → TestReaderCommunication → NewExperiment
   → CarrierIn → Read → GetReadProgress → GetResult → CarrierOut → CloseExperiment
   ```

## 9. 自检记录（2026-11-17）

* `DeviceModelSelfTest`：**通过 446 / 失败 0**（含 Gen5 的 34 个错误码全部进了种子 SQL）。
* 时序自检（可复跑）：`python3 scripts/verify-biotek-models.py` → **通过 43 / 失败 0**
  （含未配置/未开实验/无板/读板中冲突/温度越界/96 孔取数）；`--chain` 模式同样 **43/0**。
* 迁移文件：本地 dev 库事务内跑两遍 + ROLLBACK，方法 43 条、点位 1 个、模板 1 行。
