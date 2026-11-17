# 洗板机 / 分液机（BioTek LHC 统一接口：EL406 · 405 TS/LS · ELx405 · MultiFlo FX · 50 TS）

> **依据等级：MANUAL**（有厂商官方接口手册，方法/参数/返回码逐条可核）
> 型号库 id：`biotek-lhc`｜类型：`washer`｜错误码块：`0x42`
> 落库：`pg_rebuild/migration/20261117_device_register_biotek_lhc_gen5.sql`
> 模拟器：`LhcBehavior`（型号行为）+ `DeviceProfile["washer"]`（参数边界）

---

## 1. 基本信息

| 项 | 值 |
|---|---|
| 厂商 | BioTek（Agilent） |
| 覆盖机型 | EL406、405 TS/LS、ELx405、MultiFlo、MultiFloFX、MicroFlo Select、50 TS |
| 手册 1 | **LHC Runner User Application Interface Specification**，文档号 `5336208-SP`，Revision G（2017-02-14） |
| 手册 2 | **LHC SiLA Service Provider Interface Installation & Programmers Guide**，文档号 `5330207-SP`，Revision A |
| 手册原件 | `private/协议文档/洗板机LHC软件通信协议/`（含两个示例包 `5330203_V2.20.4.zip`、`5330207_V1.04.zip`；**不入库**） |
| 本文性质 | 我方解读，供开发/现场使用；与原件冲突时**以原件为准** |
| 厂商示例代码 | `5330203` 里含 LHC_Caller 的 .NET 源码与 COM 包装 `BTILHCRunnerWrapper`；`5330207` 里含 SiLA SP/SC 二进制与三份配置 XML |

### 1.1 为什么"一台机器一条定义"在这里退化成一条

型号库的原则是"每个型号一条定义"，但这台设备是**例外**：LHC 对上面 7 个机型给出的是**同一套 29 个方法**，
差异只有三处：

1. `LHC_SetProductName` 的**字符串**（`"EL406"` / `"405 TS/LS"` / `"ELx405"` / `"50 TS"` …，`50` 与 `TS` 之间必须有空格）；
2. 少数方法**不支持**：ELx405 不支持 `OverrideValidation` / `PerformSelfCheck` / `GetProductSerialNumber` / `ValidateProtocol` / `GetWellCount`；
3. 专属方法：只有 50 TS 有 `SetFirstStrip`/`SetNumberOfStrips`；只有带液面检测的 405 TS/LS 有 Verify Manifold 四件套。

所以机型做成**参数 + note**，而不是把 29 条指令复制 7 遍（复制七遍必然漂移）。

---

## 2. 物理与通讯

```
中控 ──TCP/JSON──▶ sidecar ──COM──▶ BTILHCRunner.dll ──USB/串口──▶ 洗板机
                              （LHC 软件安装时注册）
```

* **中控不与仪器直接通讯**：串口/USB 被 LHC 软件独占，sidecar 只调它的 COM 组件。
* 传输档：`--transport lhc`（`LhcRunnerTransport`），ProgID `BTILHCRunner.ClassLHCRunner`。
* 前置条件（缺一不可，缺了就是 `EC-05-2208` / `EC-05-2209`）：
  * 工控机上装了 **LHC ≥ 2.12**（SiLA 通道要 ≥ 2.17）；
  * `BTILHCRunner.dll` 已注册；
  * 与 dll **同目录**有 `LHC_Installation_Folder.inf`，内容是 LHC 的安装路径。

### 2.1 另一条通道：SiLA（**已调研，未实现**）

`5330207` 的 SiLA SP 是一个独立的 .NET 进程，把 LHC Runner 包装成 **SiLA 1.1** 服务：

| 项 | 值（来自 `serviceProviderConfig.xml`） |
|---|---|
| 监听 | `<service-provider-port>8080</service-provider-port>` |
| 路径 | `/BioTekLHCDevice` |
| WSDL | `http://<SP主机>:8080/BioTekLHCDevice/wsdl` |
| 设备类 | `SiLADeviceClass=3`（洗板机；分液机是 1） |
| 调用方 | SC（`SiLAExampleGUI.exe`）在**另一台机器**也可以 —— 这是它相对 COM 的唯一优势 |

**为什么没实现**：SiLA 走 SOAP，报文的 action 头/信封形状要靠现场抓 WSDL 才能确定，
手册正文没给。**凭空写一个 SOAP 客户端等于造假**（本项目明确反对"看起来实现了"）。
要用时按这个顺序做：① 现场起 SP；② `curl http://<SP>:8080/BioTekLHCDevice/wsdl` 存下来；
③ 照 WSDL 生成客户端；④ 把 §3 的方法名映射到 SiLA 命令（`GetStatus/Reset/Initialize/SetParameters/GetParameters/PrepareForInput/Wash|Dispense|Prime/PrepareForOutput/Pause/DoContinue/Abort`）。

SiLA 通道的**调用顺序**（手册 §3.4 原话，记下来省一次现场试错）：

```
启动：      Get Status → Reset → Initialize → SetParams → GetParams
跑协议：    Prepare Input → Wash/Dispense/Prime → Prepare Output
换协议：    SetParams → GetParams → Prepare Input → Wash/Dispense/Prime → Prepare Output
出错恢复：  Get Status → Reset → Initialize → SetParams → GetParams
```

注意 SiLA 侧只有一个参数 `cStringProtocolFilename` —— **洗涤体积/次数/浸泡时间全在 .LHC 文件里**，
这一点和 COM 通道完全一致（见 §3.2）。

---

## 3. 指令集

### 3.1 完整方法表（29 条，手册 §5）

`型号库 cmd` 是手册里的真名；`LabOS 别名` 是流程里写的名字（**同一个方法可以有多个别名**）。

| # | 型号库 cmd | LabOS 别名 | 参数 | 返回 | 说明 |
|---|---|---|---|---|---|
| 1 | `LHC_SetProductName` | `SetProductName` | `String productName` | `Int16` | 手册 §5.24。**替代已废弃的 SetProductType** |
| 2 | `LHC_SetCommunications` | `SetCommunications` | `String "COMn"` 或 `"USB <产品> sn:<序列号>"` | `Int16` | §5.2 |
| 3 | `LHC_TestCommunications` | `TestCommunications` | — | `Int16` | §5.3。不通过就不能跑协议 |
| 4 | `LHC_PerformSelfCheck` | `SelfCheck` / **`Home`** | — | `Int16` | §5.6。`Home` 是语义近似（自检会让仪器回原点）；ELx405 不支持 |
| 5 | `LHC_LoadProtocolFromFile` | `LoadProtocol` | `String` .LHC 绝对路径 | `Int16` | §5.7 |
| 6 | `LHC_LoadProtocolFromFlash` | `LoadProtocolFromFlash` | `String` 机内程序名 | `Int16` | §5.8。50 TS 的维护程序名要加 `@@` 前缀 |
| 7 | `LHC_RunProtocol` | `RunProtocol` / `Wash` / `Dispense` / `Prime` / `Run` | — | `RunStatus` | §5.9。**只有返回 `eBusy(3)` 才算启动成功** |
| 8 | `LHC_GetProtocolStatus` | `GetProtocolStatus` / `GetStatus` | — | `RunStatus` | §5.10。轮询，**不要快于 500 ms** |
| 9 | `LHC_PauseProtocol` | `PauseProtocol` / `Pause` | — | `Int16` | §5.11。停在哪一步由固件决定 |
| 10 | `LHC_ResumeProtocol` | `ResumeProtocol` / `Resume` | — | `Int16` | §5.12。须先暂停成功 |
| 11 | `LHC_AbortProtocol` | `AbortProtocol` / `Abort` / `Stop` | — | `Int16` | §5.13。暂停后可中止；运行中也可直接中止 |
| 12 | `LHC_GetErrorString` | `GetErrorString` | `Int16 code` | `String` | §5.14 |
| 13 | `LHC_GetLastErrorCode` | `GetLastErrorCode` | — | `Int16`（IS 码） | §5.15 |
| 14 | `LHC_GetOnBoardProtocolCount` | `GetOnBoardProtocolCount` | `ref Int16` | `Int16` | §5.16 |
| 15 | `LHC_GetOnBoardProtocolName` | `GetOnBoardProtocolName` | `Int16 index`（0 起） | `String` | §5.17。须先取 Count |
| 16 | `LHC_OverrideValidation` | `OverrideValidation` | `Int16 0/1` | `Int16` | §5.18。ELx405 / MicroFlo 不支持 |
| 17 | `LHC_LeaveVacuumPumpOn` | `LeaveVacuumPumpOn` | `Int16 0/1` | `Int16` | §5.19。连续多板时省掉泵重启等待 |
| 18 | `LHC_RunVerifyManifoldTest` | `RunVerifyManifoldTest` | — | `RunStatus` | §5.20。仅 405 TS/LS + 液面检测 |
| 19 | `LHC_GetVerifyManifoldRunStatus` | `GetVerifyManifoldRunStatus` | — | `RunStatus` | §5.21 |
| 20 | `LHC_GetVerifyManifoldTestResults` | `GetVerifyManifoldResults` | — | `String` | §5.22。`PASSED` / `FAILED_Step_x` / `ERROR xxxx` |
| 21 | `LHC_CreateVerifyManifoldTestReport` | `CreateVerifyManifoldReport` | — | `String`（报告全路径） | §5.23 |
| 22 | `LHC_GetProductName` | `GetProductName` | `ref String` | `Int16` | §5.4 |
| 23 | `LHC_GetProductSerialNumber` | `GetSerialNumber` | `ref String` | `Int16` | §5.5。ELx405 不支持 |
| 24 | `LHC_GetWellCount` | `GetWellCount` | — | `Int16` 孔数 | §5.25。须先 LoadProtocol；0 = 板型未确定 |
| 25 | `LHC_ValidateProtocol` | `ValidateProtocol` | `Bool getSettingsFirst` | `Int16` | §5.26。**恒传 true** |
| 26 | `LHC_SetRunnerThreading` | `SetRunnerThreading` | `Int16 0/1` | `Int16` | §5.27。0=调用方线程 1=Runner 自建线程 |
| 27 | `LHC_SetFirstStrip` | `SetFirstStrip` | `Byte 1..12` | `Int16` | §5.28。**仅 50 TS** |
| 28 | `LHC_SetNumberOfStrips` | `SetNumberOfStrips` | `Byte 1..12` | `Int16` | §5.29。**仅 50 TS**，与 27 成对 |
| — | `LHC_SetProductType` | （**不登记**） | `Int16` | `Int16` | §5.1 手册标注 **NO LONGER USED**，一律用 SetProductName |

计数校验：型号库 `biotek-lhc` 导出 **38 条**记录 = 29 个真方法 + 9 个额外别名。

### 3.2 三条"必须知道"的语义

**(1) 洗 / 分液 / 预填充是同一件事。**
`Wash` / `Dispense` / `Prime` 在型号库里都指向 `LHC_RunProtocol`，**不接受 volume 之类的入参** ——
洗涤体积、次数、浸泡时间全写在上位机编好的 `.LHC` 协议文件里。
⇒ 流程里想改洗涤参数，做法是**换一个 .LHC 文件**，不是给 `Wash` 传参。
（`DeviceProfile["washer"]` 保留 50–300 µL 的 volume 边界，只是为了"老流程把体积配错"仍能在仿真阶段报错。）

**(2) 每次 Load 都作废验证。**
手册原话："Each time you load a protocol, all the validation information is removed"。
所以同一协议第二次跑之前，必须 `ValidateProtocol(true)` 或 `OverrideValidation(1)`，否则
`RunProtocol` 每次都要重新验证（慢几秒）；反过来，**没验证就 Run 会得到 `ePre_Run_Error(6)`**。

**(3) 厂商示例代码里有个 bug。**
`BTILHCRunnerWrapper.cs` 的 `LHC_AbortProtocol()` 函数体误写成 `Runner.LHC_PauseProtocol()`。
照抄那份样例会变成"点了中止，设备只是暂停"。我们的 sidecar 直接调真名，不用他们的包装类。

---

## 4. 错误码

### 4.1 `Runner_ReturnCode`（手册 §6.2，**十进制** 0..6）

| 厂商码 | 中控码 | 含义 | 处置 | 现场怎么办 |
|---|---|---|---|---|
| `0` | `EC-08-42-00` | `eError` 通用失败 | MANUAL | 看 `GetErrorString` 原文 |
| `1` | —（不是错误） | `eOK` | — | — |
| `2` | `EC-08-42-02` | `eRegistration_Failure` | MANUAL | **LHC 软件未注册**（试用期过了功能受限，不是通讯问题） |
| `3` | `EC-08-42-03` | `eInterface_Failure` | MANUAL | 查 `LHCInterface_*.INF` 与型号驱动 |
| `4` | `EC-08-42-04` | `eInvalid_Product_Type` | FIX_PARAM | 产品名写错（`50 TS` 要带空格） |
| `5` | `EC-08-42-05` | `eOpen_File_Error` | FIX_PARAM | .LHC 路径/文件名错 |
| `6` | `EC-08-42-06` | `ePre_Run_Error` | FIX_PARAM | 协议与本机不匹配，重新 Validate |

> `IS_ReturnCode`（仪器接口软件返回）手册**没有码表**，只能原样透传 ⇒ 落到 `EC-08-9001`（未登记，转人工）。

### 4.2 `RunStatus`（手册 §6.4）—— 它**不是**错误码，是状态

| 值 | 名称 | 轮询时怎么解释 |
|---|---|---|
| 0 | `eUninitialized` | 不应出现 |
| 1 | `eReady` | **跑完了，成功**，停止轮询 |
| 2 | `eNotReady` | 新步骤没能启动 ⇒ 失败（`EC-05-2201`） |
| 3 | `eBusy` | 还在跑，继续轮询 |
| 4 | `eError` | 出错 ⇒ 取 `GetLastErrorCode`（`EC-05-2202`） |
| 5 | `eDone` | 当前步完成、下一步自动开始，继续轮询 |
| 6 | `eIncomplete` | 手册标"未使用" |
| 7 | `ePaused` | 已暂停，继续轮询 |
| 8/9 | `eStopRequested`/`eStopping` | 正在停，继续轮询 |
| 10 | `eNotRequired` | 未使用 |

### 4.3 适配层码（我们判断出来的时序/部署问题）

| 中控码 | 含义 | 处置 |
|---|---|---|
| `EC-05-2201` | RunStatus=eNotReady：新步骤没启动 | ABORT |
| `EC-05-2202` | RunStatus=eError：协议运行中报错 | MANUAL（按 GetLastErrorCode） |
| `EC-05-2203` | RunStatus=0/6：协议未正常结束 | ABORT |
| `EC-05-2204` | 没做初始化（缺 SetProductName/SetCommunications/TestCommunications） | ABORT |
| `EC-05-2205` | 没装载协议就 Run | ABORT |
| `EC-05-2206` | 装载后未验证就 Run，且未 OverrideValidation | FIX_PARAM |
| `EC-05-2207` | 50 TS 条范围越界 | FIX_PARAM |
| `EC-05-2208` | 找不到 LHC Runner COM 组件 | MANUAL |
| `EC-05-2209` | `LHC_Installation_Folder.inf` 缺失/指错 | MANUAL |
| `EC-05-2210` | 上一条协议还在跑 / 等待完成超时 | RETRY |

---

## 5. 点位

洗板机是**单口设备**：一块板由机械臂放到承载器上，洗完再从同一个口取走。

| 点位 code | `resource_node.kind` | `device_point.point_role` | `is_entry` | `is_exit` | `robot_reachable` | 说明 |
|---|---|---|---|---|---|---|
| `EL406-BOTH` | `storage` | `both` | ✅ | ✅ | ✅ | 承载器进出口（进出共用） |

* 编号遵循 [`docs/点位编号规范.md`](../点位编号规范.md) 的"单口"形态 `{设备}-BOTH`。
* 落库：`resource_node`（parent = 设备节点）+ `device_point` + `position_state`（初始空行），
  以及 `device_point_template`（按型号带出属性：`point_type=generic`、`approach_axis=front`、`safe_z_offset=50`、`max_stack=1`）。
* **没有门的点位**：洗板机的"门"是承载器动作，不是物理门 ⇒ 型号库 `doorCount=0`，不需要 `device_access`。
* 机械臂坐标（`robot_point`）**现场示教**，系统不假设顺序（见点位规范 §2）。
* 步骤：先"部署到岛"（生成设备节点），再跑迁移文件补点位；文件里已做存在性判断，缺设备节点会打 NOTICE 跳过。

---

## 6. 模拟器如何对齐

| 能力 | 实现位置 | 说明 |
|---|---|---|
| 方法集 | `DeviceModels` → `models.json` | 有型号时**型号是方法集权威**，画像只做参数边界 |
| 时序约束 | `sim/LhcBehavior.java` | 见下表 |
| 参数边界 | `DeviceProfile["washer"]` | 54 个方法名 + 分方法耗时（洗 60 s / 分液 8 s / 预填 30 s） |

`LhcBehavior` 实现的约束（**这些不实现，模拟器就只是回声器**）：

| 场景 | 模拟器行为 | 对应真机 |
|---|---|---|
| 没 SetProductName/SetCommunications 就下命令 | `EC-05-2204` | 通讯没建立，什么都发不出去 |
| 产品名不在 §6.5 列表 | `4`（eInvalid_Product_Type） | 同上 |
| 没 TestCommunications 就 Load/自检 | `EC-05-2204` | 同上 |
| 没 Load 就 Run | `EC-05-2205` | 手册 §4.3 顺序 |
| Load 后没验证就 Run | `EC-05-2206` | 手册"每次 Load 清验证信息" |
| Run 时已有协议在跑 | `EC-05-2210` | 单任务设备 |
| Run 的返回 | `runStatus=3`（eBusy） | 手册"只有 eBusy 才算启动" |
| 轮询 3 次后 | `runStatus=1`（eReady） | 真机是真实进度；模拟器固定 3 次，**故意让"不轮询就当成功"暴露** |
| 空闲时 Abort / 非暂停时 Resume | `EC-05-2201` | 手册 §5.11~5.13 |
| ELx405 读序列号 / OverrideValidation | `0`（eError） | 手册"该机型不支持" |
| 非 50 TS 调 SetFirstStrip | `0`（eError） | 手册"用于其它仪器会返回错误" |
| 50 TS：firstStrip + 条数 > 12 | `EC-05-2207` | 手册 §3.8 |
| 50 TS：先 SetFirstStrip 后 SetNumberOfStrips | 允许（合法顺序） | 手册只说"成对使用"，没说先后；交叉校验放在 SetNumberOfStrips 与 Run |

制造故障：`--fail-method Wash --fail-code 0x...`（通用故障注入），或面板上直接改状态（`DeviceCore.manualModelCommand`）。

**模拟器不模拟的部分（诚实标注）**：真实吸/排/浸泡动作、孔内残留、真空泵压力曲线。
这些对 LabOS 不可见（参数全在 .LHC 里），硬编一套假曲线只会误导。

---

## 7. 未解问题（接真机前必须确认）

1. **一块板洗完要多久**：手册没给。模拟器按 60 s；现场实测后应把 `RunProtocol` 的 `delayMs` 改掉。
2. **IS_ReturnCode 码表**：手册只给了 Runner 侧 6 个码，仪器接口侧的码表没有 ⇒ 只能转人工。
3. **`GetWellCount` 与 384 孔**：手册举的例子到 1536，但 EL406 的实际板型支持要现场试。
4. **Prompt 指令**：50 TS 协议里可以有 `Prompt`，运行时会停下来等人按键 ⇒ 无人值守流程必须确认协议里没有它。
5. **SiLA 通道未实现**：需要现场抓 WSDL（见 §2.1）。
6. **多台同型号共存**：`SetCommunications` 用 `USB <产品> sn:<序列号>` 区分；一台工控机接两台同型号洗板机时，
   两个 sidecar 进程必须用不同 ProgID 实例/不同串口，否则会互相抢端口（`-527` 类问题）。

---

## 8. 怎么接入（五步）

1. **型号库**：已完成（`DeviceModels.TABLE["biotek-lhc"]`）。改完重导 `models.json`：
   ```bash
   cd labos-kernel && java -cp out com.labos.kernel.device.DeviceModelExport > ../sidecar-dotnet/models.json
   ```
2. **错误码字典**：改型号库后重新生成并替换 `pg_rebuild/schema/95_seed_data.sql` 那一节：
   ```bash
   cd labos-kernel && java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql
   ```
3. **入库**：`psql -v ON_ERROR_STOP=1 -f pg_rebuild/migration/20261117_device_register_biotek_lhc_gen5.sql`
   （型号 + 33 个方法 + 点位模板；点位要等设备节点存在后重跑一次）。
4. **部署到岛 + 起 sidecar**：
   ```bash
   Labos.Sidecar.exe --model biotek-lhc --transport lhc --port 8501 \
                     --progid BTILHCRunner.ClassLHCRunner --poll-ms 500
   ```
   开发机（macOS，无 COM）用 `--transport sim --sim 127.0.0.1:19651`，模拟器起：
   ```bash
   java -cp "out:lib/*" com.labos.kernel.sim.DeviceSimulator \
        --port 19651 --profile washer --model biotek-lhc --device SIM-LHC --delay 20
   ```
5. **影子探针**：`POST /api/shadow/probe` 先把 `SetProductName` 一条发出去，确认能拿到 `returnCode=1`。

## 9. 自检记录（2026-11-17）

* `DeviceModelSelfTest`：**通过 446 / 失败 0**（含"型号库与 95_seed_data.sql 同步"）。
* 时序自检（可复跑）：`python3 scripts/verify-biotek-models.py` → **通过 43 / 失败 0**
  （含未初始化/未装载/未验证/运行中重复下发/空闲中止/50TS 条范围）；
  加 `--chain` 再跑一遍（经 sidecar 转发）同样 **43/0**，证明 `models.json` 的别名分发没问题。
* 迁移文件：在本地 dev 库事务内跑**两遍**（验幂等）+ ROLLBACK，型号 2 条、方法 33 条、点位 1 个/台。
