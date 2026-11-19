# 并行执行设计 · 第六轮交叉评审回执

- 基线：`fde13fb9e43793d91b3e4fe4ee14d0e995975de3`（本地 checkout 干净，HEAD = fde13fb）
- 读取范围：七份文档全文（2820 行）+ 相关源码；**未运行测试、未连库、未上真机**
- 事实核对：`git diff --stat bd6fa6f fde13fb` 只含 `docs/*`，源码零变化 ⇒ ① 的 `bd6fa6f` 行号在 `fde13fb` 仍可用；但文首应统一写 `fde13fb`，否则回执无法复算
- 结论口径：本文的"支持/反对"只针对设计文本与源码事实，不代表能力可用；并行度 2 仍不批准
- 编号约定（沿用总纲附录）：① 源码核查清单 / ② 设计基线 / ③ 表设计 / ④ 入口统一 / ⑤ 跨类锁 / ⑥ 暂停与恢复

---

## 0. 先给结论

方向支持：不新建调度器、先准入后并行、命令/事件双身份，这三条判断是对的，且七份文档的自我纠错质量高于一般设计稿。

但"设计通过"不成立，有四处在写代码前必须改：

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| P0-1 | ⑤ §3.1/§3.2 | rank 单调模型与真实顺序（设备→位置→轨道）自相矛盾，按文档实现会让**每一次正常搬运**触发断言（K-01 必红） | 阻断 |
| P0-2 | ③ §2.4 | "先无锁判唯一、再加锁"仍可被"新 waiter 插入"和"waiter 未提交"两个窗口击穿；UNATTRIBUTED 与整体回滚在同一事务里自相矛盾 | 阻断 |
| P0-3 | ④ §3.2/§6 | "check 移进事务"不等于互斥；且会把现有 fail-open 强制变成 fail-closed，V-11 不是可选项 | 阻断 |
| P0-4 | ③ §1.3/§1.7 | `command_id` 单列模型在"不去重设备"档位自相矛盾；`loop_index` 无持久化，resume 后圈次会重算 | 高 |

P1（可同批修）：暂停设计漏掉已存在的 `control_state`；`syncPausedByRuns` 是"任一"不是"全部"；核查清单 B-07/V-05、入口统一 E-03 的现状判定有事实错误；总纲与④对 E-02 的响应形状冲突。

---

## Q1 总纲 §3.0 的四个决策

**结论：A 方向对但没定到可实现；B 不成立（缺运行时兜底）；C 不成立（DDL 不完整且会污染历史）；D 是伪命题（要拆成两张表两个答案）。**

**位置**：总纲 §3.0.2、§5；④ §3.3；② §4.2.2；③ §1.3/§7。

**依据**

- **A**：PG 默认 READ COMMITTED，普通 `SELECT` 不加锁；两个事务可以同时读到"无 active"再同时 INSERT。"同一事务"只保证读到本事务快照，不保证别人不能同时读。现有 `WorkflowRunGate.check` 用 `db.open()` 自开连接（`WorkflowRunGate.java:123`），所以 `checkInTx` 是**新方法**，不是改调；且 `concurrentAllowed()` 直接放行（`:95-97`）。
- **B**：`ExperimentScheduler.rehydrateQueued` 只在启动调一次（`:509-526`，注释明写"启动时只调一次，运行中不调"）；`StartupRecovery` 对 `queued/pending` 等 1 小时（`:84`）；E-01/E-02 根本不经过 scheduler。提交后唤醒丢失 = 僵尸 queued，⑹ 的 D-05 已经承认"现在没有兜底"。
- **C**：总纲 §3.3 只加列，没有 `command_id` 索引（§4.2 的对账 SQL 要按它 JOIN）、没有 `(tenant, device_code, boot_id, seq)` 唯一键（③ §7 自己说需要）、`attribution_state DEFAULT 'PENDING'` 会让**全部历史事件**变成"未判定"，第一天的告警就是全量噪声。
- **D**：② §4.2.2 明确 `experiment_batch_run` 的 active 集合是 `('queued','running')`（排除 paused）；④ §3.3 明确 `run` 的 active 集合是 `('queued','running','paused')`（排除 quarantined）。这是两张表、两个不变量，不能合成一个 H-09。且 `WorkflowRunGate` 读的是 `StatusVocabulary` 的 `active=true`，paused 本来就在内（`StatusVocabulary.java:124-127`）⇒ "run 含 paused"不是新决策，是现状。

**风险**：并发（A 未定则阶段 1 的"原子"是假的）；兼容性（B 的僵尸 queued、D 的 paused 语义）；事实边界（C 的历史数据）；测试有效性（V-09 在低并发下可能假绿）。

**建议**

1. **A 选 `pg_advisory_xact_lock`**：键 = `(tenant_id, workflow_id)`（若 id 超出 int4，用 `hashtext` 或两个 int 的复合键），在事务第一句取，提交/回滚自动释放。不要锁 workflow 行——它会把"准入"和"编辑流程"绑在一起，还会和 `BatchOrchestrator.lockExperimentRow`（experiment 行）形成第二把锁，需要额外证明全序。唯一索引做兜底，直接写：
   ```sql
   CREATE UNIQUE INDEX uk_run_active ON run (tenant_id, workflow_id)
     WHERE status IN ('queued','running','paused') AND deleted_at IS NULL;
   ```
   **不要 active_slot 列**：它需要所有状态写路径同步维护（pause/resume/cancel/startup recovery 都改 status），漏一处就是永久占位或双 active；用 status 谓词的部分索引没有这个维护面。N>1 时再换 active_slot，不要现在引入。
   **allow 开关与索引互斥**：`LABOS_WORKFLOW_CONCURRENT_RUN=allow` 会让 gate 放行但索引拒绝（R-12）。阶段 1 必须二选一：要么废掉开关（推荐），要么索引不上线——不能同时保留两个真相。
2. **B 加运行时兜底**：`queued_at` + `dispatch_attempts`，30 秒扫一次"queued 且无 worker lease"的 run；复用现成的 `claimRunWorkerSlotOrThrow` 作为 lease。这样既解决 B，也给"执行进展监测"一个现成抓手（lease 心跳）。
3. **C 合并成一份 DDL**，同批补：`device_event(tenant_id, command_id)` 索引、`(tenant, device_code, boot_id, seq)` 部分唯一索引、历史 `attribution_state` 回填为 `ATTRIBUTED`（或 `LEGACY`）、回滚脚本。
4. **D 拆成 D1（run 含 paused）/ D2（batch_run 不含 paused）**，各自写清楚；总纲 §5 的 H-09 改成两条。

**验收**：A——两个 `async=1` 并发打同一 workflow 100 次，恰好一个成功；**注释掉 advisory lock 后该测试必须变红**（证明测试有效）。B——commit 后 kill -9，重启 ≤30 秒被拾起；不重启时 30 秒扫描也能拾起。C——迁移前后 `attribution_state='PENDING'` 的行数不因历史数据暴涨；对账 SQL 的 EXPLAIN 走索引。D——两张表的并发插入分别验证；paused run 存在时新 run 仍被拒（现状保持）。

---

## Q2 四字段分离能否覆盖四种场景

**结论：覆盖不了。至少两种第五场景，且 `command_id` 的单列模型在"不去重设备"档位自相矛盾。**

**位置**：③ §1.1、§1.3、§1.7、§3.3；总纲 §3.3。

**依据**

1. **人工干预后 resume（第五场景 A）**：`paused_unknown` 必须提供 corrections 才能恢复（⑹ §1.3 的 P-08；`PauseManager.resumeTx`）。操作员可能换了板、补了液、复位了设备，然后 resume。此时同一 run/node/step/device、同 generation、同 command_id 重发——去重设备会按"已见过"拒绝或返回旧结果，流程要么卡死、要么误判完成。设计把 generation 只绑在"人工重跑"，没绑"人工干预后继续"。
2. **同一 step 内多次物理下发（第五场景 B）**：`exec` 每次 `execute()` 递增（`DriverStepExecutor.java:206`），commandId 含 `-e{exec}`（`:1474-1477`）——这个字段的存在本身就说明同一 `(run,node,step,device)` 可能有多条物理命令。去掉 `exec` 后它们会撞同一个 key；文档没有证明"一个 step 最多一条物理命令"。
3. **循环圈次无持久化（第五场景 C）**：`loop_index` 来自内存 `visits`（`GraphRunner.java:705,738`），每次 `run()` 从空 Map 开始；resume 会删未完成节点并重跑图（`WorkflowService.resumeAsync:805-833`）。所以"循环第二轮 = `|l2`"在 resume 后不成立——同一圈可能重新算成 `l1`，与上一圈已完成的 logical_operation 撞 key，然后被"已结算则跳过"。暂停点常在循环体内，这不是理论问题。
4. **command_id 单列 vs 分档（模型自相矛盾）**：③ §1.7 说"不去重设备每次传输生成新 id"，但 §1.3 的 `logical_operation.command_id` 是单列、`uk_logical_operation_cmd` 也是单列唯一；同一 logical_operation 的多次传输无法落在同一行。要么把 command_id 拆成 `logical_command_id`（行级）+ `device_command_id`（ledger 级），要么承认该档位不支持这套表。

**风险**：安全（重复/漏做物理动作）；并发（同一 key 的并发插入没有 upsert 语义，`先查后插` 会撞唯一键或双执行）；事实边界（"循环圈次"没有持久化证据）。

**建议**

- generation 的递增条件改成"**任何人工改变了物理世界且需要重做**"：resume 时若 `pause_kind=paused_unknown` 且有 corrections，或存在人工干预记录，`generation+1`；或者把 `intervention_id` 纳入 key。
- `loop_index` 用 `run_node` 已持久化的圈次行回填，不要用内存 `visits`；`operation_key` 里的 `l{n}` 必须来自持久化值。
- `command_id` 拆两层：`logical_command_id = f(run,node,step,device,loop,generation)` 稳定；`device_command_id` 每次传输一行，去重档位取 logical，不去重档位每次新生成；`uk_logical_operation_cmd` 改为 `(tenant, logical_command_id)`。
- 建表时明确并发创建同一 `(operation_key,generation)` 的语义：`INSERT ... ON CONFLICT DO NOTHING` + 回读，或先 `SELECT ... FOR UPDATE`，不要"先查后插"。

**验收**：在 L-01~L-14 之外加 L-15（paused_unknown corrections 后 resume，去重设备上必须 generation+1 或显式人工确认沿用旧结果）、L-16（同一 step 两次物理下发，key 必须不同）、L-17（循环第 2 圈暂停后 resume，key 必须仍是 l2）。

---

## Q3 §2.4 先无锁判唯一、再加锁更新

**结论：不充分，反对。整体回滚本身不过严；"永久 UNATTRIBUTED 且不再重试"过严。**

**位置**：③ §2.4、§2.6、§5.2 W-05/W-07/W-10。

**依据（四种可复现交错）**

1. **count 与 update 之间插入新 waiter**：late waiter 是设计允许的（`base_event_id` 继承 run 级基准，新 waiter 的基准更老，能匹配已到事件）。count=1 后、update 前，另一个 run 的 waiter 提交 ⇒ 实际 2 个，但已按 1 个匹配 ⇒ 事件归错 run。这正是 v3 想修的 `SKIP LOCKED` 问题的另一种形态。
2. **waiter 事务未提交时事件到达**：count=0 ⇒ UNATTRIBUTED；waiter 随后提交，事件已被标死且无人再匹配（设计里匹配只由"事件到达"触发）。W-05"事件先于 waiter 到达不丢"在"waiter 正在提交"这个最常见窗口不成立。
3. **"多行 → 全部回滚 + 事件 UNATTRIBUTED"在同一事务里自相矛盾**：回滚会把 UNATTRIBUTED 一起回滚；要落这个标记必须另开事务。文档没有写这个事务边界。
4. **count 没过滤 `deadline_at > now()`**：过期但未扫的 WAITING 行会参与唯一性判定，可能把事件判成"多行"或让死 waiter 抢到事件。

**风险**：并发（错归/丢事件）；安全（错归会推进错误的物理流程）；运维（一条 stale waiter 会永久毒化该设备后续所有事件）。

**建议**

- 在 `(tenant, device_code, event_type)` 上取 `pg_advisory_xact_lock`，**waiter 插入和事件匹配取同一把锁**；count 与 update 在同一锁内。不要用 `SKIP LOCKED` 判唯一（v3 已对），也不要用"无锁 count"。
- 0 行不要立即 UNATTRIBUTED：给一个短 grace（如 5 秒）或"建 waiter 时回扫已到事件"；把"到达但无 waiter"的事件保持 PENDING，允许后续匹配；超过 grace 才 UNATTRIBUTED。W-05 才真正闭环。
- 多行时：回滚匹配、用**独立事务**写 UNATTRIBUTED + 告警；保留事件可重匹配（到 deadline 前）或提供人工归属入口。不要"一条 stale waiter 永久毒化"。
- count 加 `deadline_at > now()`；定期把过期 waiter 标 EXPIRED。

**验收**：W-10 改成"3 个 waiter 中 2 个在别的未提交事务里"；加 W-11（waiter 提交与事件到达并发 1000 次，0 次错归/丢事件）、W-12（多行回滚后 UNATTRIBUTED 仍在库里）。

---

## Q4 入口统一 §3.2 是否必须先定互斥点

**结论：支持"必须先定"，且比文档说的更硬：不是"最好先定"，是"不定就不能写第 6 步"。**

**位置**：④ §3.2、§3.3、§6 第 6–7 步；总纲 R-28。

**依据**

- PG READ COMMITTED 下 `SELECT` 不加锁；两个事务都读到 0 行，都能 INSERT。把 check 移进事务只保证"读到本事务快照"，不保证"别人不能同时读"。
- 现有 `WorkflowRunGate.check` 用 `db.open()` 自开连接（`:123`），所以 `checkInTx` 是新代码；文档写成"改调"会低估工作量。
- **更隐蔽**：check 现在 catch SQLException 后 allow（`:147-150`）；一旦放进事务，PG 在 SQLException 后事务已 aborted，不能继续 INSERT（除非 SAVEPOINT）⇒ **fail-open 自动变成 fail-closed**。④ §5 V-11 把这个当"可选的新语义"，其实**是移进事务的必然后果**。要么用 SAVEPOINT 保住 fail-open（但 fail-open 正是 G0-01 要修的），要么明确改 fail-closed 并改验收。建议后者，并在同一提交里写清楚。
- 唯一索引第 7 步才加，第 6 步到第 7 步之间是"看起来原子、实际不原子"的窗口；如果这期间上线，V-09 可能因低并发假绿。

**风险**：并发（双 active 仍在）；兼容性（fail-open 变 fail-closed）；测试有效性（低并发假绿）；交付范围（把"改调"估成 1 天，实际要重写 gate + 事务 + 错误码）。

**建议**：把 A 并入第 6 步（advisory lock + 部分唯一索引一起），第 7 步只做迁移/观察；不要在两者之间发布。若坚持分两步，第 6 步的提交信息必须写"本步不提供并发正确性"。

**验收**：并发测试必须能"去掉锁就变红"；EXPLAIN 确认索引被用；fail-closed 的错误码与前端兼容（V-08/V-11）。

---

## Q5 暂停与恢复 §4.1

**结论：语义可行，但"实验暂停 = 请求，不是命令"不是新设计——run 级的同一语义已经落地；设计文档的"现状"漏了第三套机制，导致重复造概念。"点了没反应"的风险是真的，解药也已经在代码里，只是没扩到实验/批次层。**

**位置**：⑹ §1.1、§4.1、§4.2、R-26；`StatusVocabulary.java:186-190`；`99c_pause_control.sql`；`RunControl.markControlRequested`；`ExperimentProjectorMapper.xml:40-48`。

**依据**

- `RunControl.markControlRequested` 注释原文："不再立即写 `status='paused'`……只写 `control_state=PAUSE_REQUESTED` + pause_kind/reason，status 保持 running/queued；真正收敛来自动作边界"。
- `markPausing` 已有"受理超过 N 秒仍在 running → PAUSING"的可观测中间态；`SCOPE_CONTROL` 词表是 `NONE/PAUSE_REQUESTED/PAUSING/RESUMING`。
- 但 `syncPausedByRuns` 的 SQL 是"任一 run paused → experiment paused"（`ExperimentProjectorMapper.xml:40-48`），不是"全部确认"。多批并行下这会**提前**把实验标 paused——正好是设计要修的"假收敛"，但设计没提这条现有 SQL。
- 批次层没有 status/control_state 的对应物（`experiment_batch` 只有 status），所以"逐批确认"需要新增列或复用 `experiment_batch_run`。

**风险**：安全（实验显示 paused 但批 2 还在离心）；兼容性（UI 已有 control_state 读方）；交付范围（重造一套"请求"语义，和现有 RunControl 打架）。

**建议**

- 实验/批次层复用 control_state 语义：`PAUSE_REQUESTED`（受理）→ `PAUSING`（n/m 确认中）→ `paused`（全部确认）；resume 时 `RESUMING` → `NONE`。
- HTTP 立即返回"已受理 + 当前 n/m"，不要阻塞到全部确认；UI 显示"暂停中 2/4，等待离心结束（≤20 分钟）"。
- 定义超时：超过 T（按最长方法超时）仍未确认的 run 标 `paused_fault`，实验进 `paused` 但带 fault 标记，不允许永远 PAUSING。
- 处理 pause/resume 竞态：每次请求带 request_id/generation，后到的请求作废前一个；已确认 paused 的 run 不会因为 Hold 撤销自动复活，必须显式 resume。
- 把 `syncPausedByRuns` 改为"所有活跃 run 都 paused 才同步"（或按批次确认数），并补测试。

**验收**：R-01 加"确认过程中 UI 的 n/m 正确、实验状态不是 paused"；R-11（新）pause→resume 竞态；R-12（新）某个 run 崩溃不确认时，T 后实验能收敛且标 fault。

---

## Q6 跨类锁 §3 的"按 kind 推导 + 单变量最高 rank"

**结论：反对。模型错误，按文档实现会让每一次正常搬运触发断言；且不能覆盖跨 step 持有、跨线程释放、批量申请/释放。更简单的是"rank 由锁实现决定 + 按 run 记持有集合"，或干脆用静态约束。**

**位置**：⑤ §3.1、§3.2、§3.3、K-01/K-02/R-20。

**依据**

1. **真实顺序是 设备(1) → 位置(2) → 轨道(1)**（`DriverStepExecutor:540-614`；设备锁在 `onStepStart`；⑤ §1.2 自己也这么写）。单变量 max 在轨道申请时 last=2、want=1 ⇒ 抛异常 ⇒ **K-01 在真实搬运路径上必失败**。这不是边界，是主路径。
2. **类型映射错**：`INCUBATOR/STORAGE/TRANSFER_POINT` 是位置（`PositionLock.java:206,244` 明确 kind 必须是这三个），文档却映射到 DEVICE_STATE；轨道分区是 `kind=virtual`（`20261115_p2_rail_zone.sql:15` 的注释），switch 里没有；`POSITION_LIKE` 不是 `resource_kind` 的枚举值（枚举是 island/station/device/robot/storage/transfer_point/incubator/operator/virtual/workstation）。
3. **单变量不递减**：position 释放后（`safeReleaseTarget`）线程仍记着 rank2；同 step 后续再拿 device 会误报。反过来，"进 step 重置"又把"跨 step 仍持有 position"抹掉，后续拿 device 不报——正好漏掉 R-18 的场景。所以它既误报又漏报。
4. **跨线程**：`ThreadLocal` 在 resume 的新线程上是空的；而 `DeviceLock.release` 在非持有线程上直接 log + return，不释放 DB 行（`DeviceLock.java:201-211`），所以跨线程释放本身就有洞，断言层看不见也修不了。
5. **批量**：`RailZoneGuard` 一次拿多个 zone、失败全释放；单变量不记录"拿了哪些"，释放后无法回退，重试期间的状态不可判。

**风险**：并发（误报导致合法路径被阻断；漏报导致反向申请没护栏）；安全（把 position 误判成 device 会让最需要保护的顺序断言失效）；交付范围（K-01 先红，团队会先怀疑测试而不是模型）。

**建议（按优先级）**

1. **最小改动**：rank 由锁实现决定（`DeviceLock=1`，`PositionLock=2`；`RailZoneGuard` 复用 DeviceLock 天然=1），不由调用点传、不由 kind 查。这样不会把 position 误判成 device，也不依赖"开发者记得传对"。
2. **若保留运行期断言**：把 `ThreadLocal<Integer>` 换成 `Map<runId, EnumMap<ResourceType,Integer>>`（按 run，不按线程），acquire +1、release -1，归零才清；这样能表达"再次进入已持有的 device_state 组"，覆盖跨 step 与跨线程。代价是每次锁操作多一次 map 更新，可接受。
3. **更简单**：不做运行期断言，改用 ArchUnit/结构约束——`PositionLock` 只允许在 `TransferPlan`/`DriverStepExecutor` 调用，`DeviceLock` 只允许在 `WorkflowService.onStepStart`/`RailZoneGuard` 调用；零误报、零运行时代价，且能挡住"新路径反向申请"。
4. 两者都做时，先静态约束，运行期断言只作为 dev 模式兜底。

**验收**：K-01 必须在真实搬运路径上通过（现在按文档会失败）；K-02 反向用例要真的抛；加 K-12（position 跨 step 持有后拿 device 必须报）、K-13（resume 新线程释放旧锁不误报）、K-14（批量 zone 部分失败重试不误报）。

---

## 附：其他必须改的事实与验收项

| ID | 位置 | 问题 | 依据 | 建议 |
|---|---|---|---|---|
| F-01 | ① B-07 | "runAll 失败 break"错 | `BatchOrchestrator:416-419` 的 break 是 paused/aborted；失败批继续，V-04 才是对的 | 把 B-07 改成"失败已继续；break 是暂停/终止语义"，不要把它算成并行缺口 |
| F-02 | ① V-05 / ④ §5 | "扣留时后续批 paused"错 | `:410-413` 只把**当前批**标 paused，然后 break | 等价性测试的预期值改成"当前批 paused，后续批原状"，否则测试会逼出行为变化 |
| F-03 | ④ §1 | "E-03 只经过实验级预检，不覆盖流程级"错 | `ExperimentExecutor:176-188` 对每个 workflow 调 `WorkflowRunGate.check` | 改成"E-03 过同一道流程级门禁，但 check 与 insert 非原子"；"六条绕过"改为"四条完全不经过，两条非原子" |
| F-04 | 总纲 §3.1 vs ④ §4.2 | E-02 被拒"非 2xx" vs "200 + JSON" | ④ 以 `WorkflowWebService:289-298` 为准 | 总纲改成 200 + JSON，"零变化"才自洽 |
| F-05 | 总纲 §5 H-09 | 一个决策，实际两张表两个答案 | ② §4.2.2 vs ④ §3.3 | 拆成 H-09a/H-09b（见 Q1-D） |
| F-06 | 邀请/① §0 | "39 已有 / 4 部分 / 10 没有"与 ① 表格对不上 | 按判定列统计为 46 已有 / 2 部分 / 2 部分确认 / 6 没有 / 1 待确认 | 数字要能复算；作为排期输入请重算 |
| F-07 | ② §3.5 | "同类并发分支今天就存在（ParallelGateway）" | `GraphRunner` 的 ready 是单线程队列（`:736-738`），同 run 分支不是并发执行 | 改成"今天真正的并发来自多个 run（MAX_CONCURRENT=4）"，或给出并发的证据 |
| F-08 | ① §1.2 vs ② §4.4.3 | `command_ledger.attempt` 到底是"节点轮次"还是"传输次数" | ① 说继承 `run_node.attempt`，② 说每次传输一行 | 统一口径，否则 §4.2 对账 SQL ④ 的 `attempt_no` 对不上 |

---

## 附：最小修复顺序

1. **P0-1** ⑤ 的 rank 模型（改锁实现定 rank + 按 run 记集合/静态约束）——半天设计、一天改，先让 K-01 能过。
2. **P0-2** ③ §2.4 的锁与触发（advisory lock + grace/回扫 + 独立事务写 UNATTRIBUTED）——设计先定，代码可后做。
3. **P0-3** A 决策并入 ④ 第 6 步（advisory lock + 部分唯一索引），并明确 fail-closed。
4. **P0-4** command_id 拆两层 + loop_index 持久化 + generation 的人工干预规则。
5. **P1** 暂停层复用 control_state、改 `syncPausedByRuns` 聚合口径；F-01~F-08 的事实更正。

---

## 边界声明

- 本文只读了文档与源码，**未运行测试、未连库、未上真机**；所有"会失败/会撞"都是静态推理，需要按验收项复现后才能定性。
- "评审通过"不等于"可并行"，更不等于"能力可用"。并行度 2 仍不批准；第一阶段只做准入统一、HTTP 行为零变化。
- 本文引用的行号均基于 `fde13fb9e43793d91b3e4fe4ee14d0e995975de3`；源码在 `bd6fa6f..fde13fb` 之间无变化，但 ① 文首的 `bd6fa6f` 应改为基线 commit。
