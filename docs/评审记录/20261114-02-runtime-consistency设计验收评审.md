# 评审记录 · runtime-consistency/01-06 设计 + 系统可靠性验收/00-07（2026-11-14）

- **评审编号**：20261114-02
- **评审对象**：`docs/runtime-consistency/01-06`（设计要求）+ `docs/系统可靠性验收/00-07`（判定证据）
- **分支**：`review/runtime-consistency-20261114` @ `3d67a50`
- **评审方式**：13 份文档全量精读（789 行）+ HEAD 代码走读 + 生产库实测 + 独立复测（907/0 回归、verify-role-permissions 31/0 均为自跑）
- **评审重点**（按委托）：状态机与事件顺序是否正确
- **结论摘要**：**设计原则与主线全部成立**（事实/投影分层、UNKNOWN 合法终态、恢复=对账、四条铁律），可以进入实现期；但**暂停状态机有一处边语义错误、恢复缺对称定义、重启恢复步骤顺序有事件顺序缺陷**，三处必须改；验收文档**证据列有两处与 HEAD 代码不符（高估了 gap）**，实现前要先修基线。

---

## 0. 独立复测结果

| 项 | 方法 | 结果 |
|---|---|---|
| 纯逻辑回归 907/0 | 自跑 `./scripts/test-all.sh --pure-only` | ✅ 907/0（跳过 25 连库） |
| 权限收口 31/0 | 自跑 `verify-role-permissions.py` | ✅ 31/0 |
| 权限收口代码 | 走读 `ApiAuthInterceptor.java:70-79` | ✅ `PUT /api/settings/stale-pause`、`POST /api/scheduler/stale-check` 已收口 admin-only，designer/operator 403 |
| `plate.position_status` 现有值 | 生产库实测 | `KNOWN` 9 / **`IN_TRANSFER` 4**（与验收 04 的证据列有关，见 B-3） |

## 1. 设计文档（runtime-consistency 01-06）——原则层全部通过

五层事实/投影模型、"上一层的推进必须由下一层确认"、"UNKNOWN 是合法终态"、"恢复走对账不走推断"、单写者约定（S-03 延续）——这些主干**没有问题**，与 ADR-PAUSE-008、LABOS_STATE_MACHINE_MULTILAYER 的既有语义兼容。命令五态（PENDING→SENT→RECEIVED→EXECUTING→DONE）与 SiLA2 CommandStatus 语义同构，方向正确。

以下 6 处需要修改（按严重度）：

### A-1（必须改）暂停状态机的一条边语义错误：`设备报告仍在运行 → PauseUnknown`

`01 §2` 的图把两条性质不同的触发并入同一条出边：

- 「设备报告仍在运行」是一次**成功的确认**——确认的结果是"还没停"。按设计自己的铁律 3（UNKNOWN = **确认不到**，不是"确认到了但结果不理想"），这条边应该**自环回 PauseRequested 继续等待**（或允许操作员取消暂停），直到超时才落 PauseUnknown。
- 现在图上「仍在运行」直接进 PauseUnknown，等于把"已知未停"和"不可知"混为一谈：PauseUnknown 的消费者（人工对账）拿到的语义就脏了。

**改法**：`PauseRequested --(确认仍在运行)--▶ PauseRequested（自环）`；`PauseRequested --(确认超时/回包矛盾)--▶ PauseUnknown`。图中「超时无法确认」那半边是对的。

### A-2（必须改）重启恢复步骤顺序：rehydrate 跑在对账之前（事件顺序缺陷）

`05 §1` 的顺序是：① rehydrate 队列 → ② 清扫泄漏锁 → ③ 对账在途命令 → ④ 对账物料位置。

问题：①在③④之前 ⇒ 重启前**在途的命令还没对账**，rehydrate 的 queued run 就可能立刻派发新命令到同一台设备——设备上可能还挂着重启前没执行完的物理动作。这与 05 自己的总原则「先对账再推进，不靠内存残留推断」直接矛盾，也是委托里点名要查的"事件顺序"中最实质的一条。

**改法**：顺序改为 ③→④→②→①（命令对账 → 位置对账 → 锁清扫 → 队列 rehydrate），或者 rehydrate 先入 `reconciling` 状态、对账完成前阻断派发。二选一，写入 05 §1 和 06 场景五的判据。

### A-3（必须补）恢复（resume）缺对称状态机

`01 §2` 给暂停画了五态，恢复只有 `Paused → Running（恢复）` 一句话。恢复同样存在「发了恢复命令 ≠ 设备已恢复运行」，且 `control_state` 里**已有** `RESUMING` 位却未被纳入设计。至少补一个占位：`Paused → ResumeRequested →(设备确认运行)→ Running`，对称的 `ResumeUnknown` 可后置，但要在 01 §2 里声明"恢复确认与暂停确认同一套机制，实现期对称落地"。

### A-4（必须补）暂停状态机与 L5（在途搬运）的接口

`PauseRequested` 期间当前节点若是 Transfer（板处于 `IN_TRANSFER`），"设备确认已停"确认的是什么？机械臂中途停下时板的物理位置就是 UNKNOWN。`04 §1` 提了"调度/暂停涉及该板时先确认"，但**暂停状态机本身没有引用 L5**，两份文档之间的这个接口是空的。

**改法**：在 01 §2 或 04 增加一条规则：`Paused/PauseUnknown 落定前，当前在途板必须联动 markPlateUnknown（封锁源/目标/臂）；恢复前必须盘点归位`。否则 R3 实现期各写各的。

### A-5（建议）命令终态与打断归档

`02 §1`：DONE 含成功/失败，建议图上显式标 `DONE[completed] / DONE[failed]`（文字已说，图易误读）。另外 `02 §4` 说 Abort 可打断业务命令，但**被打断的旧命令在账本上归为什么态**没有定义——建议 resolution 增加 `aborted/cancelled`，否则 UNKNOWN 会被误用成"被打断"。

### A-6（建议）`IN_TRANSIT_UNCONFIRMED` 与既有 `IN_TRANSFER` 的关系要写清

生产库 `plate.position_status` 已有 `IN_TRANSFER`（取板后、放板前的在搬态，4 行在库）。设计的 `IN_TRANSIT_UNCONFIRMED` 语义不同（**搬运命令完成、位置确认未到**）。两者是并存、替换还是重命名，04 里必须显式说明——否则实现期很容易拿 `IN_TRANSFER` 凑数，四态模型又糊掉。

## 2. 验收文档（系统可靠性验收 00-07）——方法对，证据基线有两处失实

四态判定口径（已通过/已实现未验证/未覆盖/不适用）、"不把设计写了当已通过"、风险分级（产品裁决/实现/运维）都立得住。但**证据列必须修**，因为它直接决定 R3-R8 的工时与验收基线：

### B-1（必须改）02 §2.2 "`RunControl.pause()` 直接写 `experiment_run.status='paused'`（RunControl.java:141，GAP）" —— 与 HEAD 代码不符

已核对当前代码：`RunControl.pause()`（现 `:96-106`）只 `raise Hold + markControlRequested(PAUSE_REQUESTED)`，类注释明确写「**不再立即写 status='paused'**」；真正的 paused 由执行器在动作边界 settle（`markPausing` 注释：PAUSING 只是可观测中间态，"绝不写 paused"）。这段行为 main 上已有，不是本分支新改。

**后果**：GAP-1「暂停无设备确认链」的方向对（设计要的显式 QueryStatus 确认链确实没有），但基线描述错了——现状是"请求 → 中间态 → 动作边界收敛（推断式确认）"，比文档所述**好一档**。R3 的实际增量是「边界推断 → 显式设备确认 + PauseUnknown」，不是从零搭状态机，工时估计应下调。

**改法**：验收表"当前实现"列统一标注代码版本（commit 号）；这条改为 PARTIAL（有中间态链、无显式确认、无 PauseUnknown）。

### B-2（必须改）02 §2.3 / 03 §3.1 "run_command 只有 INSERT 无 UPDATE" —— 字面不成立

`RunMapper.xml:780 updateBuiltinCommandNote` 确有一处 UPDATE（只改说明文字 `device_raw_response/result`，不动状态，且有注释解释为什么）。准确表述是「**无状态推进 UPDATE；仅有一处说明文字 UPDATE**」。03 把它列为根因是对的，措辞要修，免得实现期有人把那句 note UPDATE 也删掉。

### B-3（建议）04 证据列补全枚举值

生产库实测 `plate.position_status ∈ {KNOWN(9), IN_TRANSFER(4)}`。04 的证据列应列出全部现有值再判「缺 IN_TRANSIT_UNCONFIRMED 态」，与 A-6 呼应。

### B-4（建议）编号缺口：验收树没有 01

目录实际是 `00, 02-07`。00 §2 自述"01-07"，附件也说"00-07"。要么补 01（建议：把 02 里属于"运行时状态模型"投影规则那部分拆出来对应设计的 01），要么全文改口"00+02-07"并说明 01 已并入 02。

### B-5（建议）R3 在 R4 完成前的临时验收口径要写清

07 定的实现顺序 R3→R4 可以接受（暂停确认先以"动作边界推断 + QueryStatus 查询"落地，命令五态后补），但**R3 验收时"设备确认"的判据是什么**必须现在写明，否则 R3 做完无法判定 PASS。建议：R3 验收口径 = "暂停请求后，在 N 秒内要么收到设备侧停止确认（查询或回包），要么落 PauseUnknown 转人工；绝不直接写 paused"。

## 3. 结论

| 维度 | 结论 |
|---|---|
| 设计原则（事实/投影、UNKNOWN 合法、恢复=对账、单写者） | ✅ 成立，不动 |
| 暂停状态机 | ⚠️ A-1 边语义错误 + A-3 恢复对称缺失 + A-4 L5 接口空缺，改完可用 |
| 事件顺序 | ⚠️ A-2 重启恢复顺序是唯一硬伤（rehydrate 必须在对账后） |
| 验收证据基线 | ⚠️ B-1/B-2 失实需修，否则 R3/R4 工时与验收判据都建立在错误基线上 |
| 权限收口与回归 | ✅ 代码核实 + 31/0 + 907/0 独立复测通过 |

**裁决：设计文档修改 A-1～A-4（A-5/A-6 建议同行）后进入实现期；验收文档先修 B-1/B-2 再作为实现期的验收依据。** 实现顺序维持 07 的 R3→R4→R5→R6→R7/R8，但 R3 按 B-5 补验收口径。
