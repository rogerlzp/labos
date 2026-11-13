# PyLabRobot 仿真器（headless · LabOS 设备契约适配）

把 PyLabRobot 0.2.2 的**无硬件移液仿真**跑成本地设备：不弹浏览器、可自动化、结果可复现；对外按本仓库既有约定执行 `liquid_handler` 命令。

- 命令/结果：对齐 `labos-kernel/src/main/java/com/labos/kernel/driver/DriverCommand.java`、`DriverResult.java`（`command_id` 幂等、带回包原文、语义化 `error_code`、`duration_ms`）
- 状态/事件：对齐 `StatusVocabulary`（`completed/failed/aborted/skipped`，**不用 succeeded**）与 `EventType`
- 线上帧：对齐 `sidecar-dotnet/Labos.Sidecar/Protocol.cs` 和 `labos-kernel/.../sim/DeviceCore.java` —— 一行 JSON + `#!LABSEP!#`，`message_type=5`、`method_status` 2/3、`error.error_code` 为设备原始码
- 方法集与参数边界：对齐 `DeviceProfile.liquid_handler` 与 `docs/模拟器设备库.md` §三（0.5–5000 µL、未取枪头、板位不存在）

## 与网上 demo 的差异（本机 0.2.2 实测）

1. **没有 `SimulatorBackend`**：`from pylabrobot.liquid_handling.backends import SimulatorBackend` 直接 ImportError。仿真实际 = `LiquidHandler` + 无硬件后端 `LiquidHandlerChatterboxBackend` + `STARDeck`；可视化是独立的 `pylabrobot.visualizer.Visualizer`。
2. `Visualizer` 自带 websocket + 静态网页（`ws_port=2121` / `fs_port=1337`），`open_browser=False` 即 headless；本地 `http://127.0.0.1:1337/` 就是仿真界面，不依赖公网预览站。
3. 🔴 **体积/枪头追踪默认全局关闭**：不显式 `set_volume_tracking(True)` / `set_tip_tracking(True)`，吸 100 µL 后液位仍是 200、分液后仍是 0，仿真等于没跑。
4. 基础包足够，仿真**不需要** `pylabrobot[hamilton]`（那是真机驱动）；`requirements.txt` 已锁 0.2.2。
5. `plate["A1"]` 在 0.2.2 恒返回 list，单个孔位用 `get_well("A1")` / `get_item("A1")`；旧工厂名 `Cor_96_wellplate_360ul_Fb` 已弃用。

## 运行

虚拟环境在 `pylabrobot-sim/.venv/`，**不在仓库根目录**。在根目录直接执行：

```bash
pylabrobot-sim/.venv/bin/python pylabrobot-sim/run_simulator.py --browser
```

或先进入子目录：

```bash
cd pylabrobot-sim
.venv/bin/python run_simulator.py                    # 默认 demo 作业（headless，跑完即退出）
.venv/bin/python run_simulator.py --browser         # 打开浏览器看台面，保持服务到 Ctrl+C
.venv/bin/python run_simulator.py --hold            # 不弹浏览器，但保持可视化服务
.venv/bin/python run_simulator.py --self-test       # 16 项自检（正常流程 + 负例 + 幂等）
.venv/bin/python run_simulator.py --tcp-port 19501  # 以 LabOS TCP/JSON 设备协议常驻
.venv/bin/python run_simulator.py --no-visualizer   # 纯 CI：完全不占端口
```

`--browser` / `--hold` 会让进程在作业跑完后继续驻留（作业本身是毫秒级的，否则浏览器刚打开服务就关了），按 Ctrl+C 退出；后台运行时用 `kill -TERM <pid>`，两者都会先关服务再退出。首次重建环境：`uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt`。

作业文件 `jobs/demo.json` 是内置演示流程，可用 `--job` 换成自己的 JSON。每次运行写一份 `out/*.json` 报告（含每步结果、事件流、液位快照），`out/` 已被仓库忽略。

## 编排并执行一个流程

流程就是一个 JSON：`commands` 数组里每步写 `method` + `parameters`，顺序即执行顺序。模板见 `jobs/dilution.json`（源板 A1 分两次转移到 B1/B2，再混匀）。

**方式 A：一次性跑（最简单）** —— 改好 JSON 后直接指定，浏览器会保持到 Ctrl+C：

```bash
pylabrobot-sim/.venv/bin/python pylabrobot-sim/run_simulator.py --job jobs/dilution.json --browser
```

**方式 B：服务常驻，外部逐条下发（浏览器里看流程推进）** —— 终端 A 起服务，终端 B 可反复换流程下发：

```bash
# 终端 A
pylabrobot-sim/.venv/bin/python pylabrobot-sim/run_simulator.py --tcp-port 19501 --browser
# 终端 B
pylabrobot-sim/.venv/bin/python pylabrobot-sim/send_job.py --job jobs/dilution.json --port 19501
```

⚠️ `--browser` 的 job 模式只保持页面，**不接收外部命令**；要下发必须用 `--tcp-port` 常驻模式。

| method | parameters | 说明 |
| --- | --- | --- |
| `Home` | — | 回原点 |
| `GetTips` | `tipBox`, `count` (1~8) | 取枪头 |
| `DropTips` | — | 退枪头 |
| `Aspirate` | `volume`, `labware`, `well` | 吸液 |
| `Dispense` | 同上 | 分液（枪头里须先有液） |
| `Transfer` | `srcLabware`/`srcWell`, `dstLabware`/`dstWell`, `volume` | 一步转移 |
| `Mix` | `labware`, `well`, `volume`, `cycles` (1~20) | 吸-分为一轮 |
| `GetStatus` | — | 设备状态与液位 |
| `Stop` | — | 停机（常驻模式默认跳过） |

板位：`src_plate`（A1/A2/A3 初始 200 µL）、`dst_plate`（空）、`tips_300ul`（枪头盒）。`volume` 画像量程 0.5–5000 µL，但 96 孔板 360 µL 会先触发物理上限。

三个坑：① `message_id` 是幂等键，`send_job.py` 每次运行自动加唯一 `run_id` 前缀，手写客户端复用同一 id 会被跳过；② 液位会累积，第二次跑同一流程时源孔已不足会报 `TooLittleLiquidError`，要回到初始状态请重启服务；③ `equipment_id` 必须与 `--device` 一致（默认 `LH-SIM-01`），否则回 `EC-06-0001`。

## 三种模式

| 模式 | 触发 | 用途 |
| --- | --- | --- |
| Job | 默认 | 一次跑完一串命令，按 LabOS 状态词表给结论，退出码 0/1；加 `--browser`/`--hold` 则保持服务 |
| Self-test | `--self-test` | 回归门禁：液位变化、错误码、帧形状、幂等、停机后 aborted |
| TCP | `--tcp-port` | 常驻设备端，`SimBridgeTransport` / `TcpDriverAdapter` 可直接连 |

TCP 请求示例（`--device` 必须与中控登记的设备号一致，否则回 `EC-06-0001`）：

```
{"message_id":"cmd-1","message_type":1,"method":"GetTips","equipment_id":"LH-SIM-01","parameters":{"tipBox":"tips_300ul","count":1}}#!LABSEP!#
```

成功回帧 `method_status=2`，失败 `method_status=3` 且 `error={"error_code":"EC-06-0004","error_text":"..."}`。`message_id` 幂等（对齐 `CommandLedger`），重复下发不重复执行、记一条 `NODE_SKIPPED`。

## 边界与证据等级

- 仿真只有运动逻辑、液位、枪头状态；**没有**压力传感、液面探测、移液 CV 误差模型（与网上说明一致）。
- `SetTemp` 未实现，明确回 `EC-06-0004`；吸分液固定走 0 号通道，`GetTips` 最多 8 通道。
- 孔板容量 360 µL 是比画像量程 5000 µL 更早生效的物理约束，超量由 PyLabRobot 抛 `TooLittleVolumeError` → 转成设备侧失败并保留原始异常文本。
- 方法集/量程来自 `DeviceProfile`（`SPEC`）与 `docs/模拟器设备库.md`（`SPEC`），**不是设备手册（`MANUAL`）**；仓库里移液工作站手册仍缺，接入真机前须补手册与型号库。

---

**English summary**: A headless PyLabRobot 0.2.2 liquid-handler simulator wired to LabOS's device contract (command idempotency, status vocabulary, TCP/JSON `#!LABSEP!#` frames, vendor-style error codes). Run `run_simulator.py`, `--self-test`, or `--tcp-port` to use it as a drop-in simulated device for the Java kernel / .NET sidecar.
