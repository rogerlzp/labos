# 全自动离心机 ASpin 2K（上海爱翱生物科技）

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 厂商 | 上海爱翱生物科技有限公司 |
| 型号 | ASpin 2K 全自动离心机 |
| 手册 | `AC离心机接口协议使用说明V2.0F.pdf`（V2.0，2023-06-30，内部资料） |
| 模块节点名 | `PlateCtri`（配置在 `CanCfg.ini`：`PlateCtri=0X0A,0xE0`） |
| 型号库 | `aspin-2k`（`DeviceModels`，依据等级 `MANUAL`） |
| 模拟器 | `--model aspin-2k` |

> 这是**第一台**适配的离心机。离心机还有几十种型号，都按同样的方式一台一台加：
> 见 [`型号库与错误码映射.md`](型号库与错误码映射.md) 的"加一台设备五步"。

## 2. 物理与通讯

**不是网口，是 CAN 卡或串口。**

| 运行模式 | 配置 | 说明 |
|---|---|---|
| `RunMode=1` | CAN 卡 | `CanKind=4`（周立功 USB 转 CAN）/ `16`（PCI9820I） |
| `RunMode=2` | 串口 | 注册表 `CanKind=6`，`DevIndex` = COM 口号（十六进制，15 = `F`），`CANBaud=0x1C200` |

**接口形态**：不是直接收发字节，而是**调用 `Cluster.dll`**：

```delphi
TSendData = function(AInCmd: PChar; ASizeIn: Integer;
                     AOutResult: PChar; var ASizeOut: Integer): Integer; stdcall;
TPcInitEx   = procedure (); stdcall;
TPcUnInitEx = procedure (); stdcall;
```

即：**LoadLibrary("Cluster.dll") → PcInitEx → SendData(cmdXml, result)**。

`SendData` **成功返回 1，失败返回 0**；具体错误在 `AOutResult` 的 XML 里。

## 3. 指令集

指令是 **XML**，统一外壳：

```xml
<DispatchCmd>
  <strNodeName>{模块名}</strNodeName>
  <strCmdName>{命令名}</strCmdName>
  <lpCmdArgs>{参数XML}</lpCmdArgs>
</DispatchCmd>
```

| `strCmdName` | 模块名 | 参数 | 含义 | 型号库方法名（别名） |
|---|---|---|---|---|
| `PcCreateModules` | `Cluster` | `<PcRefreshModulesArgs nCreateModulesMode="0"/>` | **创建模块实例（必须先做）** | `CreateModules` |
| `Init` | `PlateCtri` | `<PlateCentrifugeInitArgs nLength="0" byInitMode="0"/>` | 初始化（回原点） | `Home` |
| `ControlCover` | `PlateCtri` | `<PlateCentrifugeSetCoverModeArgs nLength="0" byOpenCoverMode="{0\|1\|2}"/>` | 门：**0=关 / 1=开 A / 2=开 B** | `CloseDoor` / `OpenDoorA` / `OpenDoorB`（`OpenDoor` 兼容现有行为，等同 A） |
| `Start` | `PlateCtri` | `<PlateCentrifugeSetStateArgs nLength="0" byCentriMode="0" wCentriSec="{time}" wCentriSpeed="{speed}" byCentriRamp="{ramp}"/>` | 开始离心（**时间/速度/加速度**） | `RunProgram` |
| `Stop` | `PlateCtri` | 空 | 停止离心 | `Stop` |
| `CurrSpeed` | `PlateCtri` | 空 | 查当前转速（结果在 `nRetValue`） | `CurrSpeed` |
| `CurrTime` | `PlateCtri` | 空 | 查剩余时间（结果在 `nRetValue`） | `CurrTime` |
| — | — | — | **手册没有查询状态的指令** | `GetStatus`（标了 `documented=false`） |

> 手册示例的函数名：`InitPlateCentri` / `ControlPlateCentrin` / `StartPlateCentri` /
> `StopPlateCentri` / `GetCentriCurrSpeed` / `GetCentriCurrLeftTime`。
> **实际下发的是 `strCmdName`**，函数名只是 SDK 封装。

### 三个必须知道的坑

1. **`ControlCover` 是一扇门三条指令**，不是三条独立指令。
   按"Open/Close 两条指令"建模，还编了个 `ControlCoverClose` —— 那是错的，已删。
2. **两扇门（A/B）**。手册的操作界面就是"打开 A 门 / 打开 B 门"。
   流程里写 `OpenDoor` 只开 A 门；要开 B 门必须写 `OpenDoorB`。
3. **`Start` 没有 rpm 名义值**，是 `wCentriSpeed`（示例 1500）。
   原来流程里写 `RunProgram(rpm=12000, minutes=5)` 到现场是发不出去的。

## 4. 错误处理

手册**没有错误码表**，只有"`SendData` 成功返回 1、失败返回 0，错误在 `AOutResult` 的 XML 里"。

所以这台设备的错误码分两类：

| 码 | 来源 | 含义 | 处置 |
|---|---|---|---|
| `EC-05-2001` | 适配层 | `SendData` 返回 0（设备拒绝或通讯异常） | MANUAL：看 XML 错误、查 CAN/串口 |
| `EC-05-2002` | 适配层 | 未建模块实例就下发指令 | ABORT：先 `PcCreateModules` |
| `EC-05-2003` | 适配层 | 未 `Init` 就下动作指令 | ABORT：先回原点 |
| `EC-06-0006` | 内核通用 | 状态不允许（门没关、运行中开门） | MANUAL |

**注意**：动作类命令（`Start`）**超时后不要自动重发** ——
手册没写重复命令怎么处理，重复离心可能损坏样本。
宁可转人工确认。这条已写进型号 `note`。

## 5. 超时：手册给的答案

手册示例代码里：

```delphi
SendData(sCmdText, sResult);
WaitForLastReply(10000);                    // 先等 10 秒"收到"
WaitForEndOfAction(120000 + wCentriTime*1000);  // 再等 120 秒 + 离心秒数
```

即：**离心 5 分钟，要等 120s + 300s = 7 分钟**。
中控若按默认 15/30 秒超时，**每次离心都会判超时**，然后触发重试（＝重复离心）。

型号库里已记 `actionTimeoutMs = 120000`；中控侧的超时策略应按
`120s + 离心时长` 配，不能用默认值。

## 6. 模拟器（`--model aspin-2k`）

**已实现**（`sim/AspinBehavior.java`）：

| 真实行为 | 模拟器表现 |
|---|---|
| 先 `PcCreateModules` | 未建模块实例 → `EC-05-2002` |
| 先 `Init` | 未初始化就 `Start` → `EC-05-2003` |
| 两扇门 A/B | `ControlCover(1/2)`；回包区分 `doorA` / `doorB` |
| 门没关不能离心 | `Start` → `EC-06-0006` |
| 运行中不能开门 | `OpenDoorA/B` → `EC-06-0006`（手册未写，**安全假设**） |
| `Start` 三个参数 | 回显 `timeSec` / `speed` / `ramp`；缺参数 → `EC-06-0005` |
| `CurrSpeed` / `CurrTime` | 回 `nRetValue` |
| 回包用真实指令名 | 发 `OpenDoorB`，回包 `"method":"ControlCover"` |

```bash
# GUI（每台设备一个窗口）
java -cp out com.labos.kernel.sim.SimulatorApp --model aspin-2k --device 601 --port 19502
# 无界面
java -cp out com.labos.kernel.sim.DeviceSimulator --model aspin-2k --device 601 --port 19502
```

## 7. 未解问题（接真机前必须确认）

- **`nControType`/`byOpenCoverMode` 的取值**：手册写 0=关、1=开 A、2=开 B（V2.0 明确）。
  "开=1/关=0 需现场确认"的疑点，**V2.0 已给出答案**，不必再问。
- **`Start` 的加速度单位与范围**（示例 `byCentriRamp=150`）—— 手册没给单位。
- **离心过程中能否 `ControlCover`**（安全互锁）—— 手册没写。
  模拟器按"拒绝"处理，现场必须验证一次。
- **断电恢复后设备处于什么状态**（门开/关？程序是否继续）—— 手册没写。
  保守做法：恢复后先 `Init` 再动作。
- **`CurrSpeed`/`CurrTime` 的 `nRetValue` 单位** —— 手册只说"通过 `nRetValue` 返回"，
  转速是 rpm 还是 0.1rpm、时间是秒还是分，需现场对一次。
- **多台离心机时模块节点名冲突** —— 每台的 `PlateCtri` 节点号（`0x0A`）不能重复，
  接第二台时 `CanCfg.ini` 要配不同节点号，中控侧一个驱动端点只能对一台。
