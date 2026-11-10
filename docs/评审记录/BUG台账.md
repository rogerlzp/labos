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
