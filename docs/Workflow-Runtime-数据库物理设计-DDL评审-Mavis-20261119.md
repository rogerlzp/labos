# Workflow Runtime 数据库物理设计与 DDL 评审（第四轮）

- 日期：2026-11-19
- 评审人：Mavis（MiniMax Code，独立评审方）
- 评审对象：分支 `docs/workflow-runtime-architecture-v2-20261119`
  - `92e0896` docs: 数据库物理设计 v1.0（`docs/LabOS-Workflow-Runtime-数据库物理设计-v1.0-20261119.md`）
  - `d4200e9` / `15669e7` DDL（`db/workflow_runtime/001_init.sql`，572 行 / 31 表）
- 本轮方法升级：**本评审包含真实执行验证**——在本机 PostgreSQL 17.11（Homebrew，aarch64）以 initdb 建临时实例、空库执行 DDL（`ON_ERROR_STOP=1`）、并运行 10 项约束行为探针。这直接补位了作者声明的能力边界（"没有在真实 PostgreSQL 17 实例中执行这份 DDL"）。
- 性质：只做评审；未改动分支上的任何文件

---

## 一、执行验证结果（本轮最有价值的部分）

作者验证状态表里的"PostgreSQL 17 初始化 DDL：已编写（尚未在 PG17 执行）"可以升级为：**已在 PG17.11 空库执行通过**。

| 探针 | 内容 | 结果 |
|---|---|---|
| init | 空库执行 001_init.sql，`ON_ERROR_STOP=1` | ✅ 一次通过，COMMIT 成功 |
| catalog | 表 / 部分唯一索引 | 31 表 / **5 个部分唯一索引** |
| P1 | 同一 Token 第二个 live Activation | ✅ 数据库拒绝（`activation_one_live_per_token_uq`） |
| P2 | 资源第二个 live Lease | ✅ 数据库拒绝（`resource_one_live_lease_uq`） |
| P3 | arrived > expected_branch_count | ✅ CHECK 拒绝 |
| P4 | 同一 Run 两个未完成 Drain | ✅ 拒绝（`drain_one_open_per_run_uq`） |
| P5 | 同一 Split 两个 RELEASED Join | 🔴 **允许并存（=2）**，违反规范 §27"一个 Split 最多释放一个 Join" |
| P6 | 同一 Activation 两个 ACTIVE Wait | 🔴 **允许并存（=2）**，违反规范不变量 §34.3"唯一 ACTIVE WaitRegistration" |
| P7 | 同一 Activation 两个 SCHEDULED Retry | 🔴 **允许并存（=2）**，违反规范不变量 §34.2"唯一 ACTIVE RetryRegistration" |
| P8a | 新 Attempt 复用同一 commandId | 🔴 **不可能**（`command_ledger_pkey` 拦截），与规范 §23.4.4"安全重试复用 commandId"冲突 |
| P8b | 同一 Attempt 两条命令 | ✅ 拒绝（`UNIQUE(tenant_id, attempt_id)`，作者声明的 V1 限制生效） |
| P9 | 同一 Run event_seq 重复 | ✅ PK 拒绝 |
| P10 | scheduler_capacity 超卖 | ✅ CHECK 拒绝 |

**结论：DDL 语法可执行、声称的 8 项约束全部实证生效；但规范要求的 3 个唯一性不变量（P5/P6/P7）没有被数据库强制，1 个规范要求的能力（P8a commandId 复用）被数据库禁死。** 这正是作者自己说的"部分跨表业务不变量需要事务实现与数据库测试共同证明"的具体落点——现在有了逐项证据。

---

## 二、A 类问题：DDL 状态集合与 v2.1 规范错位（P0，进 T01–T23 SQL 实现前必须闭合）

**总体判断：这份 DDL 是按旧口径（§4.2 与 §23.3 的混合）写的，不是按 §31.2 的统一口径写的。** 五个状态枚举逐一对不上：

| 表 | DDL 枚举 | 规范要求（§31.2/§23.3/§23.4） | 判定 |
|---|---|---|---|
| execution_token | `READY/RESERVED/RUNNING/WAITING/CONSUMED/INVALIDATED/FAILED` | `CREATED/READY/RESERVED/IN_FLIGHT/WAITING/CONSUMED/TERMINAL/INVALIDATED` + waitKind | 🔴 `RUNNING` 正是 §23.3 明令禁止的"Token 表达 Worker 执行状态"；缺 `CREATED/IN_FLIGHT/TERMINAL`；`FAILED` 不在集合中 |
| node_activation | `READY/RESERVED/RUNNING/WAITING/SUCCEEDED/FAILED/CANCELLED/ABANDONED_SAFE` | `CREATED/READY/ACTIVE/SUSPENDED/RETRY_WAIT/COMPLETED/FAILED/MANUAL_REQUIRED/UNKNOWN/QUARANTINED/CANCELLED` | 🔴 用 `WAITING` 把 Token 等待与 Activation 挂起混为一谈（§23 开篇明令"禁止用一个 status 表达多维"）；缺 `UNKNOWN/QUARANTINED/MANUAL_REQUIRED/RETRY_WAIT` ⇒ §31.2.4 的 UNKNOWN 核验三分类在 Activation 层**无法落库**；`RESERVED/SUCCEEDED/ABANDONED_SAFE` 是别的对象的态 |
| node_attempt | `CREATED/DISPATCHED/ACCEPTED/STARTED/SUCCEEDED/FAILED/CANCELLED/ABANDONED_SAFE/UNKNOWN` | + `DISPATCH_PENDING/SUSPENDED/RETRYABLE_FAILED/PERMANENT_FAILED` | 🔴 把 RETRYABLE/PERMANENT 合并成 `FAILED`，直接违反 T06"不得把所有异常统一映射成 FAILED"；缺 `DISPATCH_PENDING` 使 §32.1 的 Dispatch Commit 事务（Attempt→DISPATCH_PENDING）无法实现 |
| workflow_run | `PAUSING/STOPPING`，缺 `STARTING` | `PAUSE_DRAINING/STOP_DRAINING`，含 `STARTING` | 🟡 语义等价但命名与规范章节不一致；`STARTING` 缺失使 T02 的两段启动（CREATED→STARTING→RUNNING）只能一步跳 |
| command_ledger | 含 `UNKNOWN/DELIVERY_UNKNOWN`，缺 `PREPARED/REJECTED/QUARANTINED` | §31.5.1 集合无 `UNKNOWN`，有 `PREPARED/REJECTED/QUARANTINED` | 🟡 有趣的是 DDL 的双 UNKNOWN 恰好是我第三轮 F1 的一种解法（投递未知/执行未知分开），但规范章节没改 ⇒ 现在**两边各说各话**，必须择一统一 |

另有 `runtime_outbox` 状态集合 `PENDING/CLAIMED/DELIVERED/DEAD_LETTER` **缺 `SEND_STARTED`**：规范 §32.4 把 SEND_STARTED 定义为"物理调用可能发生的线性化前沿"、不变量 §34.8 规定 SEND_STARTED 后不得自动重发——这个设备安全的关键状态在 DDL 里无处落（`command_dispatch_attempt` 有 SEND_STARTED，但那是派发尝试记录，不是 CommandOutbox 状态机）。规范还要求独立的 CommandOutbox（T12 与 Command Ledger 同事务创建），DDL 用 runtime_outbox 的 message_kind 合并了，这个合并可以作为裁决接受，但 SEND_STARTED 必须补上。

**修法建议**：不必推翻重做——状态枚举是 CHECK 约束，改起来便宜；但**必须逐个枚举按 §31.2 重写，并让规范 §31.5.1 与 DDL 双向对齐（命令账本的 UNKNOWN 语义顺势把 F1 关掉）**。这条应作为 T01–T23 SQL 实现的第 0 步，否则 SQL 实现会照着错的枚举写。

---

## 三、B 类问题：缺失约束与缺失对象（P1）

1. **P5/P6/P7 的三个部分唯一索引缺失**（本轮探针实证）：
   - `join_activation(tenant_id, split_id) WHERE join_state='RELEASED'`——防双重释放，这是设备/物料安全的最后防线，不能只靠事务代码；
   - `wait_registration(tenant_id, activation_id) WHERE wait_state='ACTIVE'`；
   - `retry_registration(tenant_id, activation_id) WHERE retry_state='SCHEDULED'`。
   一条 `CREATE UNIQUE INDEX ... WHERE` 各解决一个，成本极低。
2. **commandId 复用与 UNIQUE(attempt_id) 的语义冲突（P8a 实证）**：规范 §23.4.4 要求"同一逻辑命令的安全重试复用 commandId"，但重试按 §31.2.2 是**新 attemptId**——而 command_ledger 主键在 command_id、UNIQUE 在 attempt_id，两个约束合起来把"跨 attempt 复用 commandId"变成不可能。**这必须 ADR 裁决**，二选一：(a) 命令挂 activation 不挂 attempt（UNIQUE 改 `(tenant_id, activation_id)` + command_id 保留，跨 attempt 复用可行）；(b) 保持现状并修订规范——"复用 commandId 仅限同 attempt 的多次派发（dispatch_no 递增），新 attempt 必须新 commandId"，但 (b) 有设备安全代价：UNKNOWN 后经核验可重试的场景会被迫换 commandId，恰好是规范明令防止的"用新命令身份掩盖未知结果"。倾向 (a)。
3. **UNATTRIBUTED 无落点**：规范 §14.3/§24.3/T14 要求无法按 commandId 关联的设备事件进隔离队列、不推进节点。inbox_receipt 的 PROTOCOL_VIOLATION 只覆盖"同身份不同 payload"，无 commandId 可归的事件目前只能塞 run_id=NULL 的行，没有状态/队列语义。建议 inbox_receipt 加 `unattributed boolean` 或独立表。
4. **flow_variable 缺写溯源列**：规范 §7.2 的写入接口带 `activationId + idempotencyKey`，DDL 的 flow_variable 只有 value/value_version——CAS 冲突后按幂等键去重、审计"谁写的"都做不到。补 `last_write_activation_id`、`last_write_idempotency_key` 两列即可。
5. **resource_lease 缺 heartbeat_at / owner 细分列**：规范 §10.5 的 ResourceLease 有 heartbeatAt、ownerActivationId、ownerCommandId；DDL 用泛化 owner_kind/owner_id 可接受，但 heartbeat 缺失使"租约续期"事务无落点。
6. **command_ledger.payload_hash 无不可变保护**：规范 §27 要求 payload_hash immutable，当前无触发器/权限阻止 UPDATE。V1 可靠事务纪律，但建议加一个廉价触发器兜底。
7. **wait_kind 无 CHECK**：规范 §31.2 要求 waitKind ∈ {RETRY, EXTERNAL_EVENT, TIMER, JOIN,...}，DDL 是自由 text。顺手补 CHECK。

---

## 四、C 类问题：文档与 DDL 互相矛盾（文档过期，P2）

设计文档 §7（170 行）写："每 Token 同时最多一个有效 Activation / 每 Activation 最多一个活动 Attempt……本版 DDL 用唯一 activation_no/attempt_no，**不足以独立证明此不变量，实施时须补部分唯一索引**"。**实际 DDL 已有这两个部分唯一索引**（114–115、146–147 行，`15669e7` 提交加入），且 P1 探针实证生效。这一段是文档落后于 DDL 的典型——作者把"补索引"记成了待办，实际已完成。**修法**：更新 §7，把这两项从"需要事务代码实现"移入"已由 DDL 结构约束"，并把本轮 P5/P6/P7/P8a 的实证结果补进 §8 验证状态表（"真实 DB 约束验证"可以从 UNKNOWN 部分升级：8 项 PASS、4 项 FAIL/冲突）。

---

## 五、做得好的（值得保持）

1. **独立建模 resource_fence_counter**："不能只依赖 Lease 唯一索引假设 fencing token 单调递增"——这个裁决正确，且与第三轮 UNKNOWN-03（fencing 落点）形成呼应。
2. **租户复合外键贯穿所有引用**（防止跨租户引用）+ UUIDv7 优先/UUIDv4 兜底的务实取舍。
3. **run 行双游标（commit_version + event_seq）+ RETURNING 分配**与规范 §31.7.2 完全同构；"禁止全局 sequence 作为 Run event_seq"写进了文档。
4. **inbox_protocol_violation 独立表**——"保留原 receipt 不覆盖"在结构层成立（我探针未测此项，但约束形态正确）。
5. **control_gate、recovery_state、run_request_dedup、drain_record/drain_item**：都是规范里散落的语义第一次有了物理落点。
6. **文档 §7 把"DDL 能证明"与"必须事务代码保证"分开列出**——这个诚实分层与作者一贯的 UNKNOWN 纪律一致，只是内容有一处过期（§四）。
7. **验证命令直接给在文档末尾**（createdb + psql ON_ERROR_STOP + catalog 查询）——本轮我就是照着它执行的，可复现性设计到位。

---

## 六、结论与下一步

**结论：DDL 作为第一版物理基线成立（PASS with conditions）。** 执行通过 + 8 项声称约束实证生效是真实的成果；但 A 类状态集合错位（5 个枚举）和 B 类 3 个缺失唯一约束必须在 T01–T23 SQL 实现之前闭合，否则 SQL 会把错位固化进实现。

**给作者的排序建议（在其自己五步计划上修订）：**

1. **DDL 修订一轮（小）**：A 类 5 个枚举对齐 §31.2 + B 类 3 个部分唯一索引 + SEND_STARTED + wait_kind CHECK + flow_variable 溯源列；同时规范侧把 §31.5.1 命令状态集合与 DDL 双向对齐（顺手关掉我第三轮的 F1）。
2. **ADR 裁决 commandId↔attempt 归属**（P8a，§三.2）——这是本轮唯一的结构性设计决策，不要让它溜进 SQL 实现里被默认决定。
3. 然后才进入作者的第 2 步（T01–T23 SQL 规格）和第 3 步（并发测试）。空库初始化验证（作者的第 1 步）**本轮已完成**，catalog 检查一并做掉了。
4. 作者的第 5 步（独立反向评审）应包含**约束行为探针**——本轮 P1–P10 的 10 个探针可以直接纳入其测试清单；探针脚本可以入库到 `db/workflow_runtime/probe_001.sql`（本评审未入库，如需我可提供）。

---

## 七、验证环境与复现

- PostgreSQL 17.11（Homebrew，aarch64-apple-darwin25），initdb 临时实例，unix socket /tmp，端口 55432
- 流程：initdb → createdb → `psql -v ON_ERROR_STOP=1 -f 001_init.sql` → 种子数据（显式 UUID）→ P1–P10 探针 → catalog 统计 → pg_ctl stop
- 探针样例已在 §一 表格中给出判定依据（具体约束名），全部结果可复现
- 本评审未在仓库留探针脚本；临时实例已停止，未改动仓库任何文件

---

*Mavis · 2026-11-19 · 本评审不改任何代码与既有文档*
