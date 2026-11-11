# BUG 台账 · LabOS-Kernel

> **用途**：登记历轮评审发现的缺陷，逐条跟踪到关闭。**只追加不删改历史判定**，状态变更写在"处置"列。
> **维护约定**：编号 `BUG-<日期>-<序号>`；严重级 `阻断 / 高 / 中 / P2(自洽)`；修复由执行方完成，评审方只登记与复核。
> **关联**：每条的原始证据见对应评审记录（`docs/评审记录/`）。

---

## 一、总览

| 编号 | 严重级 | 摘要 | 位置 | 状态 | 发现于 |
|---|---|---|---|---|---|
| `BUG-20261103-01` | **阻断** | 建库产物 `90_all_in_one.sql` 未随 M6 再生：缺 `run_node.loop_index`、缺 `parent_node_id` 外键、`uk_run_node` 仍是 3 列 | `pg_rebuild/schema/90_all_in_one.sql` | ✅ **已关闭**（评审方复核 2026-11-03 13:21，见 `20261103-02` §七） | `20261103-02` |
| `BUG-20261103-02` | P2(自洽) | 测试文档 §八 设备链断言写 **34**，同文其余 4 处写 **37** | `docs/评审包-20261103/04-测试文档.md` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-03` | P2(自洽) | 技术文档 §2.1 包文件数失实：driver/workflow/sim/device/expr 全偏大，分项合计 **113 ≠ 107** | `docs/评审包-20261103/02-技术文档.md` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-04` | P2(自洽) | 技术文档 §2 引用"设计文档 **§6**"——该章节不存在 | `docs/评审包-20261103/02-技术文档.md` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-05` | P2(自洽) | 索引/技术文档把 `run_node` 唯一键写成 `(run_id,node_key,attempt)`，缺 `loop_index`，与同文"已加 loop_index"自相矛盾 | `docs/评审包-20261103/00-索引与评审须知.md`、`02-技术文档.md` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-06` | P2(自洽) | 设计文档 **D15** 排在 §三/§四/§五 **之后**，ADR 顺序错乱 | `docs/评审包-20261103/03-设计文档.md` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-07` | P2(自洽) | 索引 §5.1 #2 与设计文档 §五 p2 仍把**已完成的 M6**（`loop_index` 每圈一行）列为未做 | `docs/评审包-20261103/00-…`、`03-…` | ✅ 已修 | `20261103-02` |
| `BUG-20261103-08` | **阻断** | `90_all_in_one.sql` 在**全新库**上跑不干净：22 条 `already exists`（4 个 identity 序列重复创建、5 个 `multiple primary keys`、若干约束重复）。`deploy.sh:112` 用 `ON_ERROR_STOP=1`，会在第一条处中断 | `pg_rebuild/schema/30_business.sql`（pg_dump 风格尾部与手写 DDL 重复定义同一批表） | ✅ **已关闭**（评审方复核 2026-11-03 13:21；"连库 exit=0"为执行方实测，评审方本机无 PG 未复现，见 `20261103-02` §七） | `20261103-02` 复核过程发现（执行方实测） |
| `BUG-20261103-09` | P2(维护) | 产物里 5 张表被声明两次（`plate_well` / `run_variable` / `run_artifact` / `experiment_sample` / `resource_booking`）。**列集等价，无副作用**；隐患是"改错那一份不报错" | `pg_rebuild/schema/30_business.sql` | ✅ **已修**（2026-11-03 14:0x，结构 diff 为 0） | `20261103-02` 复核 |
| `BUG-20261103-10` | **高** | `ExperimentExecutor` 把 `experiment_run.id` 写进 `experiment.run_id`，而该列外键指向 `run(id)` —— 两表各自独立自增、不是一个 id 空间；`experiment_run.id` 一旦落到 `run` 的 id 空洞处即抛 `23503` | `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java` | ✅ 已修（`9c1f65d`） | 执行方自测发现（2026-11-03） |
| `BUG-20261103-11` | **中** | 前端 e2e 主链路 4「实验编排」自 `051f4d1`（菜单改名「实验列表」+ 拆列表/详情两层）起**一直失败**：脚本仍在找「实验编排」菜单。而门禁从不带 `--e2e` 跑，等于"5 条主链路"长期不可信 | `scripts/e2e-smoke.py` | ✅ 已修（`f335a10`：改为 列表→点行进详情，并覆盖「现场扣留」/运行控制列；16 条全过） | 执行方本轮自测发现（2026-11-03） |
| `BUG-20261103-12` | **中** | 实验级暂停接口回**请求的**作用域而非**实际生效**的：未分配岛的实验请求 `island` 时后端退化为 `experiment`，响应仍写 `"scope":"island"` —— 界面提示"作用域：岛"、库里只有实验级扣留，值班员会误判整岛已停 | `RunControl.java` `RunControl.pause`、`ApiServer.java` EXP_RUN_ACTION pause 分支 | ✅ 已修（`046836d`：`pause` 返回实际生效作用域，接口回该值） | 执行方本轮联调发现（2026-11-03） |
| `BUG-20261103-13` | P2(自洽) | 状态词表两套混用：`experiment_run.status` 用 `completed`，`experiment.status` 用 `finished`；分批执行把**聚合实验状态**写在 `POST /experiments/{id}/run` 顶层 `status` 后，前端 `runExp` 只认 `completed`，全部成功会弹 **warning**（"分批全成功却报黄"） | `webapp/src/pages/Experiments.tsx` `runExp` | ✅ 已修（`fd59d90`：`completed` / `finished` 都判成功，分批提示附带批数） | 执行方联调发现（2026-11-03） |
| `BUG-20261103-14` | **中** | 既有 `experiment_run.status='running'`（EXP0812 的 run#2/#3，2026-11-01 启动）让新的「实验预计等待」把早已结束的实验误判成**运行中**：wait=0，且排在它后面的实验全按"它占着岛"估算。根因是 ETA 按 `experiment_run.status` 判 running，而 run 状态本身可能过期 | `labos-kernel/src/main/java/com/labos/kernel/workflow/SchedulerEta.java`、`docs/多实验并行调度-设计.md` §7.2 | ✅ **已关闭**：判定 `f7120a2`（running=调度器岛锁持有者 + `experiment.status` 兜底，不看 run.status）+ 启动兜底 settle `3aa4837`（首次重启自动把 run#2/#3 落 failed，二次重启 0 条幂等）；现存 running=0、EXP0812 不再误判 | 执行方联调发现（2026-11-03） |
| `BUG-20261103-15` | **中** | 设备链测试脚本**进程泄漏**：`test-sidecar.sh` 用 `( cd … && java … ) &` 启动（`$!` 是子 shell，kill 杀不到 java）、`dotnet run` 还会再起子进程；`verify-device-chain.sh` 同样 → 每跑一次自测/验收漏模拟器+sidecar，本次清理累积 **150 个孤儿进程**（PPID=1，最早 19:02） | `scripts/test-sidecar.sh`、`scripts/verify-device-chain.sh` | ✅ 已修（`e378b91`：一律 exec 启动真进程、sidecar 改 `exec dotnet <dll>` 不依赖 apphost/DOTNET_ROOT、cleanup 加 `pkill -P` 兜底；实测跑完 test-all 前后进程数不变、清理后 0 且不复生） | 执行方环境清理发现（2026-11-03） |
| `BUG-20261103-16` | **中** | 权限收口三缺口：① 角色无白名单（可建 role=boss 的账号）；② 无最后管理员保护（可停用/降级唯一 admin → 系统锁死）；③ 前端无角色感知（viewer 点写操作只吃 403、用户管理菜单仍可见） | `labos-kernel/.../api/RbacRules.java`、`QueryService.java`、`webapp/src/auth.tsx` | ✅ 已修（`afddf5b` 后端 RbacRules+白名单+最后管理员保护；`3339606` 前端 AuthProvider/角色 Header/只读体验；`19bb67b` 联调脚本 20/20；实测真实 admin 降级/停用均 400 且 DB 未变；收尾 `4e3baec` 事务锁 + `ec28d02` 会话失败重试/只读覆盖补齐） | 执行方权限收口盘点（2026-11-03） |
| `BUG-20261104-01` | **阻断** | e2e「24/0」不可复现（实测 **23/1**，两次同结果）：实验列表首行（`ORDER BY id DESC`）为残留实验 962（**0 条 run**），执行历史表无可展开行 → 断言失败；脚本对列表首行数据分布**强依赖** | `scripts/e2e-smoke.py` | ✅ **已关闭**（2026-11-04：脚本第 4 步改为按行遍历取“有可展开执行历史”的实验，最多试 8 行，消除首行依赖；清残留后复跑 **24/0**） | `20261104-01` |
| `BUG-20261104-02` | **高** | RBAC 联调残留污染库：`sys_user` = 5（多 `rbac_op_22244` / `rbac_viewer_22244`）+ 残留实验 962；违反 `01` §9「无 rbac 测试残留」；根因 `verify-rbac.py` 清理只按 `created_exp` 列表删实验、进程被强杀即漏删（实验未做按名兜底） | `scripts/verify-rbac.py`、`docs/评审包-20261104/01-本轮交付清单与验证.md` §9 | ✅ **已关闭**（2026-11-04：`verify-rbac.py` finally 加按名兜底删实验；并已清残留 → `sys_user` 5→**3**、`experiment` 679→**678**，与 `01` §9 快照一致） | `20261104-01` |
| `BUG-20261104-03` | P2(自洽) | 评审包写“`3aa4837..ec28d02`，**25 个提交**”；`A..B` git 语义**不含 A**，实际 **24**（含基座才 25） | `docs/评审包-20261104/00-索引与评审指引.md`、`01-本轮交付清单与验证.md` | ✅ **已关闭**（2026-11-04：`00` §1/§5、`01` §头 25→**24**；实测 `git rev-list --count 3aa4837..ec28d02` = 24） | `20261104-01` |
| `BUG-20261104-04` | P2(自洽) | 评审包写 `MetaApi 109`（合计 **3487**）；HEAD 上 MetaApi 已随 `f2d7e8e` 增至 **132**（合计 **3510**） | `docs/评审包-20261104/01-本轮交付清单与验证.md` §4 | ✅ **已关闭**（2026-11-04：`01` §4 改为 `MetaApi 132`（合计 **3510**），注明 109→132 由 `f2d7e8e` 引入；实测 8 控制器合计 3510） | `20261104-01` |
| `BUG-20261104-05` | P2(自洽) | go-live 复跑命令（`00` §2）**未带 DB env**，但 `01` §7 报的是**带 DB** 的 9/2；不带 env 实为 **8/3**（“绑定覆盖率”跳过），命令与结果不同口径 | `docs/评审包-20261104/00-索引与评审指引.md` §2、`01-本轮交付清单与验证.md` §7 | ✅ **已关闭**（2026-11-04：`00` §2 go-live 命令补 DB env 前置并标注不带 env 为 8/3、带 env 为 PASS 9 / WARN 2 / FAIL 0；两口径实测复现） | `20261104-01` |
| `BUG-20261107-01` | **高** | 实验级暂停停不住底层 workflow 级单流程 run：节点边界只查 `run.status` 看不到现场扣留（`runtime_hold`）⇒ 实验级两层标 paused，底层 run 照旧把节点跑完；修复后恢复时旧 `paused` run 会被误当可续跑而重做设备动作 | `WorkflowService.withPauseChecker` / `RuntimeHold.coveringForWorkflow` / `RunControl.resume` | ✅ **已修**（2026-11-07：节点边界判据扩到「run paused 或 有扣留」；恢复时作废本实验步仍 paused 的旧 run） | 执行方交付复核发现（2026-11-07） |
| `BUG-20261111-01` | **高** | **隔离/不可达设备仍在能力候选池里、且抢得到锁** ⇒ 节点挑中它后命令发不出去，**既不失败也不超时**，永远停在 `running` ⇒ 整单卡死（并行分支里必有一支卡住 ⇒ 汇合等不到） | `GraphMapper.selectCapabilityPools`（候选池不查 `device_state`）、`DeviceStateMapper.tryAcquireDevice`（抢锁不看设备状态） | ✅ **已修**（2026-11-11：候选池与抢锁两层都排除「既 `error` 又 `online=false`」的设备；`online` 恢复即自动重新可选，不会永久出局；详见下方处置） | 执行方独占窗口验收发现（2026-11-11） |
| `BUG-20261111-02` | **高** | **实验建好之后，界面上没有任何地方能把「已经建好的流程」加进这个实验** —— 后端 `PUT /api/experiments/{id}/processes` 是 409 只读（「流程编排表已改为只读镜像，请到画布编排保存」），前端详情页的编排表只有只读表格；只有「新建实验」弹窗能带流程 ⇒ 用户新建实验后**无处安放已建好的流程** | `ExperimentActionController.processesReadOnly()`、`webapp/src/pages/Experiments.tsx`（编排区） | ✅ **已修**（2026-11-11：后端放开 `PUT /processes`（已有画布编排的实验仍 409，画布是权威）；前端编排区加「编排流程」弹窗：流程库多选加入 + 上下移 + 移除 + 保存；详见下方处置） | 用户现场反馈（2026-11-11，实验 1000267） |
| `BUG-20261111-03` | **高** | **设备在 `device_state` 里没有状态行 ⇒ 抢锁永远失败** ⇒ 调用方只能等满 60 秒再报「设备被占用超过 60 秒：<id>」—— **误导：那台设备根本没人占**，是它压根没有状态行（库里 **43 台**有 `device_instance` 却没 `device_state` 行：多半没接驱动、心跳也覆盖不到） | `DeviceStateMapper.tryAcquireDevice`（纯 UPDATE，无行则匹配 0 行）、`DeviceLock.tryAcquire` | ✅ **已修**（2026-11-11：抢锁前 `ensureDeviceState` 补一行空闲态；顺带修掉 P-18 引入的「自己的重试被故障态挡住」；抢不到锁时补一句设备当前状态。详见下方处置） | 用户现场反馈（2026-11-11，实验 1000267 / 设备 229） |
| `BUG-20261111-04` | **高** | **`quarantined` 不被当终态** ⇒ ① 它留下的**设备锁永远不回收**（`releaseOrphanDeviceLocks` 只认 `completed/failed/aborted/cancelled`）⇒ 那台设备**永久不可用**；② 批次 attempt 永远没有 `finished_at`；③ 启动兜底还会把隔离中的 run **误标 failed**（丢掉「待人工确认」语义） | `DeviceStateMapper.releaseOrphanDeviceLocks`、`BatchMapper.updateAttemptFromRun` / `markRunFailed` | ✅ **已修**（2026-11-11：三处状态列表分别处理 —— IN 列表加 quarantined；`markRunFailed` 的 NOT IN 也加它，含义是**别把隔离中的 run 改成 failed**） | 执行方独占窗口实测发现（2026-11-11，设备 200206 被 quarantined 的老 run 占死） |
| `BUG-20261111-05` | **中** | **模拟器不认 `Deposit1Ex`**（`incubator` 只支持 `Deposit1`，Ex 是扩展版）⇒ 库里登记了 `Deposit1Ex` 的设备一接模拟器就被判「不支持该方法」⇒ 节点失败（`EC-99-0001`） | `labos-kernel/.../sim/DeviceProfile.java`（incubator 方法白名单） | ✅ **已修**（2026-11-11：incubator 同时支持 `Deposit1Ex` / `Withdraw1Ex`；配套补了 driver / device_method 登记并起了两个模拟器，设备随即 `online=true`，实验三个节点全 completed） | 执行方按用户要求「给这两台设备加模拟方法」时发现（2026-11-11） |

> **表结构修复（2026-11-03 晚）**：`-08` / `-09` 此前被空行与「处置」段隔出总表，`-09` / `-10` 两行没有表头，Markdown 渲染不成表格。本次只调行位置，判定/状态/证据文字未改。

#### 处置：BUG-20261103-08（执行方 · 2026-11-03）

| 项 | 结果 |
|---|---|
| 手段 | `merge.sh` 增加 `guard_dup_decl.py`：把**重复声明型**语句（`ALTER … ADD GENERATED`、`ALTER TABLE [ONLY] … ADD CONSTRAINT`）包成 `DO $$ … EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition`，命中即 `RAISE NOTICE` 跳过 |
| 为什么不是删重复定义 | 分片里手写 DDL 与 dump 尾部**交错**，删块容易误删只存在于尾部的那 23 张表；用守卫则"对象不存在照建、已存在不炸"，语义不变 |
| 全新库验证 | 空 schema 全量执行产物，`ON_ERROR_STOP=1`：**exit=0，ERROR=0**（原 22 条） |
| 建出对象 | 105 张表 + 5 个视图（线上 107 = 105 + 2 个 2026-09 历史月分区），`run_node.loop_index` / 4 列 uk / parent 外键均在 |
| 幂等 | 守卫只影响重复声明；新装与重复执行都不报错 |

#### 处置：BUG-20261103-11 / -12（执行方 · 2026-11-03）

| 项 | 结果 |
|---|---|
| -11 怎么坏的 | `051f4d1` 把菜单「实验编排」改成「实验列表」，并把实验折成"列表页 → 详情页"两层；`e2e-smoke.py` 的 T4 仍在点「实验编排」菜单，0 个匹配 → 15s 超时 |
| -11 为什么一直没人发现 | `ci-gate.sh` 的 e2e 段默认不跑（要 `--e2e` 且前后端在跑）；`051f4d1` 之后几轮门禁只跑「纯逻辑 + tsc」，"5 条主链路"只在文档里是绿的 |
| -11 修复 | T4 改为：实验列表 → 点行进详情 → 断言「现场扣留」卡片 / 运行控制列 → 展开执行历史；实测 16 条 / 失败 0（阈值 14，脚本 14 条） |
| -12 现象 | 未分配岛的实验请求 `island` 暂停：后端 `effectiveScope` 退化为 `experiment`，但接口把请求值 `island` 原样回给前端 |
| -12 修复与实测 | `pause` 返回实际生效作用域；联调该实验提示"作用域：本实验"，扣留表范围=「本实验 #813」，与库里那道实验级扣留一致 |
| 复跑方式 | `scripts/verify-exp-control.py`（本轮新增，`3ab54a3`）：自动借空实验、插 2 条 queued run、从界面走 暂停→恢复→停止，finally 删数据并还原状态；22 条断言 |

> 已关闭（✅）的条目为**文档自洽修正**，修复动作记录见 `docs/评审记录/20261103-02-评审包复核.md` §七。
> `BUG-20261103-01` / `-08` 已于 2026-11-03 13:21 由评审方复核关闭（见 `20261103-02` §七）；
> 其中 `-08` 的"全新库 exit=0/ERROR=0"为执行方实测，评审方本机无 PostgreSQL 未能复现，已如实记录。

---

## 二、明细

### 🟥 BUG-20261103-01（阻断）建库产物过期，被部署的那一路没跟上 M6

**现象**：`pg_rebuild/deploy.sh:27` 部署的是合并产物 `90_all_in_one.sql`，但该文件停留在 **M6 修复之前**。

**证据（git 时间戳 + 内容比对）**：

| 文件 | 最近提交 | `run_node` 的 `loop_index` | `uk_run_node` | `parent_node_id` 外键 |
|---|---|---|---|---|
| `schema/20_kernel.sql`（分片，正确） | `6fb38d3` 11-03 11:18 | 有（:309） | 4 列 `(…,loop_index)`（:310） | — |
| `schema/96_schema_fix.sql`（补丁，正确） | `6fb38d3` 11-03 11:18 | 有（:120） | 重建为 4 列（:127） | 有（:112-115） |
| **`schema/90_all_in_one.sql`（部署产物，过期）** | **`43317da` 11-02 21:33（早于 M6）** | **无** | **3 列**（:557） | **无**（:2488 仅加列） |

**根因**：M6（`6fb38d3`）改了分片与 `merge.sh`，但**未重跑 `merge.sh`** 再生合并产物。

**影响**：用新库启动后——
1. `https://github.com/rogerlzp/labos/blob/7524a94d853076a601bb87ad6a0e8bc45c31d8d2/labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java` 中约 22 处引用 `run_node.loop_index` 的查询（运行列表/详情/流程图/时间线）直接 SQL 报错（列不存在）；
2. `parent_node_id` 无外键 → 步骤行级联删除的结构前提缺失（H1 类孤儿行风险重现）；
3. `uk_run_node` 回到 3 列 → 循环场景下同一节点跨圈插入**撞唯一键**（M6 被抵消）。

这是项目反复强调的**"双套路径"**缺陷：代码链路已改，被部署的那一路没跟上。

**修复（执行方）**：
- 重跑 `pg_rebuild/schema/merge.sh` 再生 `90_all_in_one.sql`，并核对含 `loop_index` / 外键 / 4 列 uk；
- 在 CI/回归基线加一步"**merge 产物 vs 分片一致性**"断言，防止再次漂移。

**关闭条件**：`90_all_in_one.sql` 与 `20_kernel.sql + 96_schema_fix.sql` 同源；`deploy.sh` 建出的库含 `run_node.loop_index` 与 `uk_run_node(run_id,node_key,attempt,loop_index)`。**需连库验证**。

#### 处置（执行方 · 2026-11-03）

| 项 | 结果 |
|---|---|
| 重跑 `merge.sh` | ✅ 产物 2531→2536 行；与分片同源（`git diff` 仅 30 行：`loop_index` + 4 列 uk + 外键 DO 块 + 补回 5 处丢失的 `ALTER TABLE ONLY plate_well`） |
| 产物含 `loop_index` | ✅ `grep '^\s*loop_index\s+smallint'` 命中 |
| 产物含 4 列 uk | ✅ `CONSTRAINT uk_run_node UNIQUE (run_id, node_key, attempt, loop_index)` |
| 产物含 parent 外键 | ✅ `run_node_parent_node_id_fkey ... ON DELETE CASCADE` |
| **连库验证**（新 schema 全量执行产物） | ✅ `run_node.loop_index` 存在；`uk_run_node = UNIQUE (run_id,node_key,attempt,loop_index)`；`run_node_parent_node_id_fkey = FOREIGN KEY (parent_node_id) REFERENCES run_node(id) ON DELETE CASCADE` |
| 防漂移断言 | ✅ 新增 `scripts/check-schema-sync.sh`（重跑 merge 比对 + 断言三要素），已并入 `ci-gate.sh` 第 2.5 道门；负向测试通过（塞回过期产物即报错并还原工作区） |

> 附带：全新库执行的 22 条 `already exists` 已在 `BUG-20261103-08` 里一并修掉
> （`merge.sh` 加守卫），现在空库执行 **0 错误**，部署路径也干净了。

---

### ✅ BUG-20261103-02 ~ 07（文档自洽，已修）

| 编号 | 原值 → 现文 | 说明 |
|---|---|---|
| `-02` | 设备链断言 `34` → `37` | 与同文其余 4 处（37）及 `ci-gate.sh MIN_CHAIN=37` 对齐 |
| `-03` | driver 25→23 / workflow 21→20 / sim 8→7 / device 7→6 / expr 6→5 | 与逐包 `find … \| wc -l` 实测一致，分项合计 = 107 |
| `-04` | "设计文档 §6" → "设计文档 §五" | §五「如果要重构，我会先动哪里」即 ApiServer 拆分那条 |
| `-05` | `uk_run_node (run_id,node_key,attempt)` → 加 `loop_index` | 与 `20_kernel.sql:310`（M6）一致，消除同文矛盾 |
| `-06` | D15 从 §五之后 → 移到 D14 与 §三之间 | ADR 段连续、结论段在后 |
| `-07` | 00 §5.1 #2 / 03 §五 p2 标注 **已完成（M6）** | 消除与 M6 里程碑（00 §八）及代码的矛盾 |

---

**维护签署**：Trae AI（DeepSeek-V4.1-Flash） · 2026-11-03 12:50 CST

**复核签署（`-01` / `-08` 关闭裁定）**：Trae AI（底层模型家族：DeepSeek-V4.1-Flash） · 2026-11-03 13:21 CST · `main @ 39d352c`
> 复核方式：静态结构核验 + `check-schema-sync.sh` / `ci-gate.sh` 复跑；**未连库**（本机无 PostgreSQL 服务端）。裁定 **两条均关闭**；`-08` 连库项标注为执行方实测、评审方未复现。

---

### BUG-20261103-09 处置（执行方 · 2026-11-03 14:0x）

**做法**：删掉 5 张表在 `30_business.sql` 里的**重复定义**（各含 `CREATE TABLE` + 紧随的
`ALTER … ADD GENERATED` + 该表的 `pkey` 约束），保留正本：

| 表 | 正本位置 | 删除位置 |
|---|---|---|
| `plate_well` | `30_business.sql:78` | 其后的 dump 风格副本 |
| `experiment_sample` | `30_business.sql:253` | 其后的 dump 风格副本 |
| `resource_booking` / `run_variable` / `run_artifact` | `20_kernel.sql` | `30_business.sql` 里的副本 |

外键（`*_fkey`）**保留未动** —— 它们只在 dump 段出现，删了会丢约束。

**验证**：改动前后各从零建一个 schema，`pg_dump --schema-only` 比对，
**除 pg_dump 的随机 `\restrict` 标记外逐行相同**（结构零差异）；全新安装 `ON_ERROR_STOP=1` 仍 exit=0 / ERROR=0。

产物表定义数：86 → **80**（重复的 5 份消失，另有 1 处属实体表计数方式差异）。

---

### BUG-20261103-10 处置（执行方 · 2026-11-03）

**现象**：`labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java` 的 `markRunning` 与 `syncExperimentStatus` **两处**都把 `experiment_run.id` 写进 `experiment.run_id`。

**证据（连库实测 `192.0.2.10:5432/labdb`）**：

| 项 | 值 |
|---|---|
| 外键 | `experiment_run_id_fkey = FOREIGN KEY (run_id) REFERENCES run(id)` |
| `experiment_run` 行数 | 39，且**全部**都能在 `run` 找到同 id 行（`not exists` = 0） |
| `max(experiment_run.id)` | 39 |
| `max(run.id)` / `run_id_seq.last_value` | 205486 / 205494 |
| `run` 连续段 | id **1..197 连续**，**198 起有空洞**（`first_missing_run_id = 198`） |
| 触发条件 | `experiment_run.id` 涨到 198 时，起跑处即抛 `23503`（当前 39 → 再跑约 **158** 次实验） |

**根因**：`experiment_run` 与 `run` 两张表**各自独立自增**，不是一个 id 空间；`experiment_run.id` 恰好仍落在 `run` 的连续段内，所以线上一直没炸，属**延迟必炸**。由提交 `862f52a`（"feat: 错误码落库 + 实验状态同步"）引入，**非本轮工作**。

**修复**：两处均移除 `run_id = r.id`，保留 `status / started_at / finished_at / updated_at` 同步，并加注释说明。全仓检索确认无消费者读 `experiment.run_id`（`QueryService` / `webapp` / `docs` 均无命中），删除安全。

**验证**：`RunControlDbSelfTest` 新增断言"同步后 `experiment.run_id` 保持 NULL"，并去掉为绕开外键而插的占位 `run` 行；连库全量 `test-all.sh` **500 / 0**，`ci-gate.sh` 门禁通过（纯逻辑 423/0）。

**关闭条件**：`experiment.run_id` 在实验状态同步路径上恒为 `NULL`；若未来确需回填，必须写 `run(id)` 而非 `experiment_run(id)`。

---

### BUG-20261107-01 处置（执行方 · 2026-11-07）

**现象**：实验级暂停只把 `experiment_run` / `experiment` 两层置 `paused`（并抬一道现场扣留 `runtime_hold`），底层 workflow 级 `run` 不受影响；执行器 `GraphRunner` 在节点边界只查 `run.status`，看不到扣留，于是照旧把后续节点全部跑完。

**根因**：实验级暂停落的是**旁路状态**（扣留），与“某条 run 停没停”解耦；节点边界闸门只读了 `run.status`，没有把“现场被扣住”纳入判据。

**修复（两处，都在执行侧）**：

| 处 | 文件 | 做法 |
|---|---|---|
| ① 节点边界停住 | `WorkflowService` | `withPauseChecker` 判据由「`run.status='paused'`」扩为「`run.status='paused'` **或** `RuntimeHold.coveringForWorkflow(workflowId) != null`」；命中即 `pauseRunAtBoundary` 把底层 run 落 `paused`，后续节点停在 `pending`。**与 `interruptible` 无关，默认生效** |
| ② 恢复时作废旧 run | `RunControl.resume` | 实验级暂停会把“当前步的 workflow 级 run”一并落 `paused`；恢复时该步整段重跑、另起新 run，旧 `paused` run 成孤儿，挂在 `PauseManager.pausedRuns` 里会被误点「继续」重做设备动作（物理副作用不可撤销）。故恢复时把「本实验步且仍 `paused`」的 run 作废为 `aborted` |

**反查判据关键**：`RuntimeHold.coveringForWorkflow` 按 **`experiment_run_step.status='running'` + `workflow_id`** 反查归属，**不能**按 `experiment_run.status='running'` 过滤 —— 实验级暂停会先把 `experiment_run` 置 `paused`，用 `running` 过滤永远查不到（原 bug 直接成因）。

**验证**：

| 项 | 结果 |
|---|---|
| 自测（连库） | `RunControlDbSelfTest` 等 **43 / 0** |
| 门禁 4.17 段 | `scripts/e2e-pause-during-experiment.py` **5 / 0**（原阈值 2 → 5） |
| 实跑证据 | run #208222 / experiment_run #1868：暂停后 `ARM=completed / ARM2=pending`（后续节点停在 pending） |
| 纯逻辑单测 | 651 / 0（阈值 650 → 651） |

**两件事别混**：本 bug 修的是「**节点边界停住**」（默认生效）；「**动作中途真停**」是另一条路（`device_method.interruptible=true` + `DriverStepExecutor.pauseRequested` → `driver.abort`，需现场清单）。

**关闭条件**：实验级暂停后底层 workflow 级 run 在节点边界落 `paused`、后续节点保持 `pending`；恢复后旧 `paused` run 恒为 `aborted`，不再出现在 `pausedRuns` 可续跑集合中。

#### 处置：BUG-20261111-01（执行方 · 2026-11-11）

| 项 | 结果 |
|---|---|
| 现象 | 节点选中了一台**不可达**的设备 ⇒ 命令发不出去 ⇒ 节点**既不失败也不超时**，永远 `running` ⇒ 整单卡死（并行分支里必有一支卡住 ⇒ 汇合等不到） |
| 根因 | ① 候选池 `selectCapabilityPools` 只查 `resource_capability ⋈ capability`，**完全不查 `device_state`**；② 抢锁 `tryAcquireDevice` 只判 `current_run_id`，**不看设备自身状态** ⇒ 一台 `status='error'` 且 `online=false` 的设备照样被选中、照样抢得到锁 |
| 现场证据 | 设备 200165（CENTRIFUGE 能力）在候选池里按 `ORDER BY resource_id` **排第一**，而它 `status='error'`、`online=false`、`last_error='EC-99-0001'`（当天内核被 SIGTERM 杀掉、设备动作中断时的残留）⇒ **未显式绑定设备的节点只要需要该能力，就会稳定挑中它并卡死** |
| 修法 | 两层都排除「**既 `error` 又 `online=false`**」的设备：候选池 `NOT EXISTS` 子查询 + 抢锁 `AND NOT (status='error' AND online=false)` |
| 为什么是这两个条件一起 | ① 只有 `error` ⇒ 可能只是「这个方法不支持」（设备本身是好的，别的流程还能用）⇒ 一律排除会让设备**永久出局**；② 只有 `offline` ⇒ 库里大量存量设备心跳没刷新（实测 **168 台 `idle`+`offline`**）⇒ 一律排除等于把大半设备踢出候选池；③ 两个都中 ⇒ 命令**必然发不出去** ⇒ 必然卡死（这才是要治的） |
| 不会永久出局 | `online` 由心跳刷新（`upsertDeviceOnline`）⇒ 设备重新探测到就**自动重新可选**；且抢不到锁时节点按「无可用设备」处理（`RunStateMachine.withNoDeviceReason` 写明原因）⇒ 至少不会静默卡死 |
| 覆盖面 | 候选池那条只管**模拟路径**（库里的资源口径）；**抢锁那条两条路径共用**（driver 路径也走 `tryAcquireDevice`）⇒ driver 路径得到兜底保护 |

#### 处置：BUG-20261111-02（执行方 · 2026-11-11）

| 项 | 结果 |
|---|---|
| 现象 | 用户新建了实验（`exp1111` / id 1000267，岛 100034），想把这个**已经建好的流程**放进去 —— 界面上找不到地方 |
| 根因 | ① 后端 `PUT /api/experiments/{id}/processes` 的方法名就叫 `processesReadOnly()` ⇒ 固定回 **409**（编排表被设计成「画布编排」的编译产物，写入口唯一）；② 前端详情页的编排区只有**只读 Table**（列到「编辑流程」为止），既没有添加也没有上下移；③ 只有「新建实验」弹窗能带流程（`workflowIds`）⇒ **实验建好之后就再也不能加流程** |
| 修法（后端） | 放开 `PUT /api/experiments/{id}/processes`，接受 `{"workflowIds":[12,13]}` → `ExperimentActionService.replaceExperimentProcesses`（全量替换编排 + 偏序边）；**已有画布编排的实验仍回 409**，并明确说明「画布是权威的」 |
| 为什么保留这个例外 | 前端画布加载时 **graph 非空就用 graph、否则用编排表派生** ⇒ 两边都写会出现「画布一套、顺序一套」的分裂状态（画布还显示旧顺序）。没有画布的实验：保存编排后画布页会从编排表自动派生出一致的线性画布 ⇒ 两边不会打架 |
| 修法（前端） | 实验详情页编排区加「**编排流程**」按钮 → 弹窗里从**流程库多选**加入、上下移、移除 → 保存即 `PUT`；空态提示也指向这个入口 |
| 顺带改掉的旧文案 | 详情页原来的 Alert 写「流程编排表现在是只读镜像 / 请到画布编排里调整」⇒ 与新行为矛盾，已改成「这里是执行顺序（纯顺序）」+「点编排流程可以把已建好的流程加进来」 |

#### 处置：BUG-20261111-03（执行方 · 2026-11-11）

| 项 | 结果 |
|---|---|
| 现象 | 实验 1000267 失败于「第 1 步失败：设备被占用超过 60 秒：229」，而设备 229 **当时没人占**（`device_state` 里它连行都没有） |
| 根因 ① | 抢锁是 `UPDATE device_state SET current_run_id=... WHERE resource_id=...` ⇒ 设备**没有状态行**时匹配 **0 行** ⇒ 永远抢不到 ⇒ 只能等满超时再报"被占用" |
| 根因 ②（回归） | 同一次执行的**重试**（命令失败后重试同一台设备）被 P-18 那笔「排除 error+offline 设备」挡住 ⇒ **自己的重试也挡掉** ⇒ 命令 5 秒就失败了，却又在抢锁上空等 60 秒（实测 run 1001037） |
| 修法 ① | 抢锁前 `ensureDeviceState`（`INSERT ... ON CONFLICT DO NOTHING` 补一行空闲态）⇒ 已有行不动 ⇒ 让执行走到真正的失败点（发命令时报"没有驱动/设备不可达"） |
| 修法 ② | 排除条件加例外：**本 run 用过这台设备（=重试）就放行** ⇒ 重试立刻拿到失败，不再空等。⚠ 例外不能用 `current_run_id` 判（命令失败时锁已释放、该列变 NULL）⇒ 判据落在 `run_node` 上 |
| 修法 ③ | 抢不到锁时补一句设备当前状态（status/online/被谁占/上次错误）⇒ 区分「被别的 run 占用」与「设备离线/没有状态行」 |
| 验证 | 同一个流程 1000546、同一台设备 200229：修复前 **53~63 秒还在 running、79 秒才 failed**（报"被占用"）；修复后 **13 秒**落 `paused`，level=0 父节点落 **manual**、reason 写清「策略[*@COMMAND]已耗尽：attempt=1 >= maxAttempts=1，原意图=RETRY」，子步骤 S1/S2 failed（EC-99-0001 无驱动） |
| 遗留 | 「这条流程用的设备 200229 **没有接模拟器/驱动**」是**环境事实**（模拟器只有 7 台：206/171/214/407/210/408/409）⇒ 它本来就跑不了；本修复只是让失败**快且理由准**，不是让没驱动的设备能跑 |

#### 处置：BUG-20261111-04（执行方 · 2026-11-11）

| 项 | 结果 |
|---|---|
| 现象 | 设备 200206 被一个**已 quarantined** 的老 run（1001034）永久占着 ⇒ 抢不到锁 ⇒ 调度器一直 `stalled` ⇒ 队列里的实验永远派发不出去；只能手工清锁才恢复 |
| 根因 | `quarantined` 是「命令物理结果未知、隔离待人工确认」的**终态**（`StatusVocabulary.RUN_TO_EXPERIMENT` 就把它映射成 failed），但三处判「run 已终态」的 SQL 都只认 `completed/failed/aborted/cancelled` |
| 三处语义不同 | ① `releaseOrphanDeviceLocks`（**IN**）⇒ 要**加** quarantined，否则设备锁不回收；② `updateAttemptFromRun`（**IN**）⇒ 要**加**，否则批次永远没 finished_at；③ `markRunFailed`（**NOT IN**）⇒ 也要加，但含义是**别把隔离中的 run 改成 failed**（它是「待人工确认」，改成 failed 就丢了语义） |
| 验证 | 重启内核后 200206 的孤儿锁被**自动**回收（此前要手工清）；实验 1003103 在解除现场扣留后 3 秒派发，三个节点全 completed |
| 顺带澄清（不是缺陷） | 调度器 `stalled` 的**直接**原因是**现场扣留**（`RuntimeHold` 覆盖了实验所在的岛 100034）⇒ 按 ADR-FAULT-SCOPE-001「现场扣留优先于派发」**是设计如此**，不是调度器 bug；解除扣留后 3 秒即派发。但「上次执行转人工留下的扣留」没有明显提示，现场容易卡住 ⇒ 属体验缺口，记入待规划 |

#### 处置：BUG-20261111-05（执行方 · 2026-11-11）

| 项 | 结果 |
|---|---|
| 现象 | 设备 229（EQ143 Incubator_2C）登记的方法是 `Deposit1Ex`（真机 S100 系列的扩展进板），而模拟器 `incubator` 只认 `Deposit1` ⇒ 一接模拟器就被判「不支持该方法」⇒ 节点失败 `EC-99-0001` |
| 修法 | `DeviceProfile` 的 incubator 白名单同时支持 `Deposit1Ex` / `Withdraw1Ex`（一行代码，不涉及内核执行逻辑） |
| 配套 | 库里补了 `DRV-SIM-X229`(19510) / `DRV-SIM-X231`(19511) 两个模拟驱动、`device_method` 的 `143→Deposit1Ex`(cap 3) 与 `145→Home`(cap 2) 登记，并把两台 `device_instance.driver_id` 指向模拟驱动 ⇒ **两台设备随即 `online=true`** |
| 实测 | 实验 1003103 = **completed**：`Activity_16n7hh7`(Deposit1Ex@200229)、`Activity_1w5e00z`(`__sleep`)、`Activity_1vi15o6`(Home@200231) **三个节点全 completed**，子步骤 S1/S2 也 completed |
| 注意 | 模拟器是**进程**（本轮为 19510/19511，随进程结束就没了）；库里的 driver / device_method 登记是持久的 |
