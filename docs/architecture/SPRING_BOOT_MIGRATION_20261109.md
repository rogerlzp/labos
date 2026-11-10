# Spring Boot 迁移：容器 / AOP 事务 / 日志 / MyBatis XML

日期：2026-11-09　范围：`labos-kernel`
现场决策：**用 MyBatis XML**（SQL 与 Java 分离，一眼看得清），按 A → B → C 三批推进，**不留旧启动方式与兼容层**。

---

## 0. 迁移前的事实

| 项 | 值 |
|---|---|
| `db.open()` 调用点 | **396 处** |
| 显式 `setAutoCommit(false)` | 46 处（约 12%） |
| 连接池 | `DbConfig` 里自研 8 连接队列 + 动态代理 |
| Spring / MyBatis / `@Transactional` | **0 处** |
| `pom.xml` 运行时依赖 | 0 个（PG 驱动靠手动 `-cp`） |
| HTTP 层 | JDK 内置 `com.sun.net.httpserver.HttpServer` |
| 启动 | `java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.api.ApiServer 18080` |
| 日志 | `System.out` 434 / `System.err` 109 / slf4j **0** |
| SQL | 137 条散在 Java；`jsonb` 141、`ON CONFLICT` 28、`FOR UPDATE` 13、PG 枚举强转 24 |
| Redis | `labos-gateway` 手写 200 行 RESP2 客户端 |

---

## A 批：不留兼容层（已完成并验证）

| 文件 | 作用 |
|---|---|
| `ApiServer.java` | **删除 `main()`**；构造函数改为全部依赖注入；启动收尾逻辑整体迁入 `runKernel()`（崩溃孤儿 settle、命令账本对账、物理未知隔离、queued rehydrate 一行不丢） |
| `BeanConfiguration.java` | `DbConfig` / `QueryService` / `AuthService` / `WorkflowService` / `ExperimentScheduler` / `HeartbeatService` / `ApiServer` 全部由容器创建 |
| `WorkflowService` / `QueryService` | 去掉 `final`（CGLIB 不能代理 final 类）；4 个写入口加 `@Transactional(rollbackFor = Exception.class)`（**checked 异常默认不回滚**，必须显式声明） |
| `DbConfig.java` | 删手写 8 连接池、删反射探测、删「无 Spring 回落旧池」分支；统一走 `DataSourceUtils.getConnection`。纯 JVM 自测用 Spring 自带的 `DriverManagerDataSource`（直连、连不上立刻抛，无后台重连刷屏） |
| `KernelRunner.java` | 唯一启动入口，容器就绪后调 `runKernel()` |
| `scripts/start-kernel.sh` | 唯一启动脚本（原 `start-kernel-spring.sh` 改名） |
| `scripts/test-all.sh` | classpath → `out:lib/postgresql-42.7.3.jar:lib/spring/*` |
| `scripts/verify-s3-2-residuals.py` | `api_pids()` 由 `pgrep -f ApiServer` 改为**按 18080 端口反查**；`restart_api()` 走 Spring 启动；健康等待 60s → 120s |

**A 批验收 `./scripts/build-kernel.sh --verify`（13/0 中的前 9 条）**：

```
✓ DataSource 由 Spring 容器绑定
✓ WorkflowService / QueryService 是 AOP 代理（...$$SpringCGLIB$$0）
✓ 事务内两次 db.open() 是同一物理连接（pg_backend_pid 相同）
✓ RuntimeException → 两条 INSERT 全回滚
✓ SQLException（checked）→ 两条全回滚
✓ 正常返回 → 两条都提交
```

真实环境：新入口启动 18080 成功；**`ci-gate.sh --recovery` 4.13 崩溃恢复 34/0**（kill -9 → 由新入口重启 → API 恢复健康），门禁无跳过；`test-all.sh` 1310/0；`ci-gate.sh --fast` 通过。

---

## B 批：日志与可排查性（基础设施已完成，替换进行中）

| 文件 | 内容 |
|---|---|
| `src/boot/resources/logback-spring.xml` | 控制台 + 按天/100MB 滚动的全量文件 + 执行安全独立留档；pattern 带 MDC |
| `src/boot/resources/application.properties` | `spring.main.web-application-type=none`、`mybatis.*` |
| `LogContext.java` | MDC 字段 `traceId / runId / commandId / workflowId`，try-with-resources 作用域，支持嵌套并精确还原 |
| 已替换的关键路径 | `WorkflowService`（B-01 执行期异常）、`DriverStepExecutor`（B-02 暂停安全边界）、`CommandLedger`（中止结果旁路写）、`KernelRunner`（启动） |

实测：`labos-kernel/logs/labos-kernel.log` 生成，格式 `时间 级别 [线程] trace=… run=… cmd=… 类 - 消息`。

**2026-11-09 续：539 处 `System.out/err` 已全部替换为 SLF4J**（48 个文件）。
批量脚本把 `println/print` → `log.info/error`，`printf` → 同名辅助方法
（`String.format` 包装），并给每个类注入 static Logger；`javac` 全量编译通过，
`build-kernel.sh --verify` 13/0。剩余代码里只有注释文本还提到 "System.out.printf"。
JDK `HttpServer` 没有拦截器，MDC 入口目前靠调用方显式设置，HTTP 层迁
Spring MVC 后改 `HandlerInterceptor` 自动注入。

---

## C 批：MyBatis XML（首个垂直切片已完成并验证）

现场选择 MyBatis XML 而非 JdbcTemplate / JPA：SQL 与 Java 分离后可直读；JPA 与 `jsonb` / PG enum / `ON CONFLICT` / `FOR UPDATE` 冲突。

| 文件 | 内容 |
|---|---|
| `WorkflowMapper.java` | 接口，无注解，由 `@MapperScan("com.labos.kernel.persistence")` 注册 |
| `WorkflowRow.java` | 可变 JavaBean，`useGeneratedKeys` 回填自增 id |
| `src/boot/resources/mapper/WorkflowMapper.xml` | `insertWorkflow` / `countIsland` / `selectIslandId` / `countByCode`，**全部 SQL 在这里** |
| `WorkflowService.createWorkflow` | 改为调用 Mapper；`isRealIsland(Connection)` 删除；Mapper 未注入时**直接抛异常**（fail-fast，不留「退回裸 JDBC」的兼容分支） |
| `scripts/build-kernel.sh` | 拷贝 resources / mapper XML 到 `out/` |

**C 批验收（13/0 中的后 4 条）**：

```
✓ Mapper 写入 + 裸 JDBC 读取是同一物理连接（342783 / 342783）
✓ XML mapper 的 select 可用（countByCode）
✓ Mapper 事务抛异常 → 插入一起回滚
```

真实 API（新入口启动后）：
- `POST /api/workflows {islandId:100027}` → 200，`id=1000080`，落库 `island_id=100027`
- `POST {islandId:27}`（旧编号）→ 400「不是 resource_node 里的岛节点」
- `POST` 不传岛 → 200，`island_id=NULL`

**2026-11-09 续：`RunMapper.xml` 落地**（`markFailed` / `settleTerminal` /
`settleManual` / `markSettledPhase` / `selectStatus` 5 条），
`WorkflowService.markRunFailed` 与 `settleRun` 的三段动态 SQL 字符串已删除、
改走 Mapper；`BeanConfiguration` 注入 `RunMapper`。加上 `WorkflowMapper.xml`
的 4 条，共 **9 条 SQL 进 XML**，两个 mapper 都通过 13/0 事务自测。

**2026-11-09 续：显式事务收编（QueryService）** —— 19 处 `setAutoCommit(false)`
所在的 21 个 public 方法（含重载）全部加
`@Transactional(rollbackFor = Exception.class)`，Spring 路径由框架接管。
`setAutoCommit/commit/rollback` **语句保留**，原因不是懒：
`ExperimentWorkcellScopeDbSelfTest` 等 5 个 DB 自测直接
`new QueryService(db, tenant)` 绕过容器，删掉就会让它们失去原子性。
要物理删除，得先把这 5 个自测改成走 Spring 容器（独立 PR），随后
`DbConfig` 里的事务内连接代理也能一并删掉。

**2026-11-09 续：`labos-gateway` 换 Lettuce** —— 手写 200 行 RESP2 / 裸 Socket
客户端替换为 `lettuce-core 6.3.2`，`RedisClient` 对外签名一个没改，
`DriverConnection` 零改动；`javac` 全量编译通过。连接池、重连、超时、
命令编解码交给 Lettuce/Netty。

**2026-11-09 续：HTTP 层已迁 Spring MVC（传输层）**
- `spring-boot-starter-web`，`server.port=18080`，Tomcat 接管端口；
  `ApiServer` 里的 `HttpServer` 字段 / `start()` / `stop()` / 16 线程池全部删除，
  JDK `com.sun.net.httpserver` 只剩 `HttpExchange` 这个数据接口。
- `ServletHttpExchange`：把 `HttpServletRequest/Response` 适配成 `HttpExchange`
  （17 个抽象方法）；`LegacyRouteController` 用 `@RequestMapping("/**")`
  接管全部路径，把请求交给 `ApiServer.route()` —— **40+ 端点路由逻辑一行不改**。
- `CorsConfig`：Spring MVC 原本会在进 Controller 前把 OPTIONS 预检拒成
  `403 Invalid CORS request`，现按 `ApiServer.trustedOrigin()` 同口径配白名单
  （默认本机任意端口 / `LABOS_CORS_ORIGINS`），适配层用 `setHeader` 覆盖
  CORS 头避免重复 `Access-Control-Allow-Origin`。
- `SpringTxSelfTest` 显式用命令行参数 `--spring.main.web-application-type=none`，
  保证自测容器不启 Tomcat、不抢 18080。

实测：`Tomcat started on port 18080` → 登录 200、`/health` 200、
`/api/topology` / `/api/workflows` / `/api/devices/pool` / 静态页 `/` 全 200、
带 Origin 的 ACAO 正确回显、OPTIONS 预检 200（修前 403）、
`ci-gate.sh --recovery` **4.13 崩溃恢复 34/0**（kill -9 → 由新入口重启 → 健康）。

**日志批量替换引入的回归（已修）**：539 处注入 static Logger 后，
所有用 `java -cp out` 独立启动的 JVM 入口（模拟器、型号导出）缺 slf4j jar。
统一把 4 个脚本的 classpath 改成
`out:lib/postgresql-42.7.3.jar:lib/spring/*`，并把
`DeviceModelExport.main` 还原为 `System.out.println(json())`
（`test-sidecar.sh` 靠 stdout 的 md5 比对 `models.json`，是数据管道不是日志）。
修复后 `test-sidecar.sh` **7/0**、独立模拟器启动正常。

**2026-11-09 续：三项继续推进**

① **SQL 进 XML（累计 12 条）**：新增 `GraphMapper.xml`
（`backupGraph` / `deleteEdges` / `selectGraphXml`）。`WorkflowService.backupGraph`
与 `saveGraph` 的"清旧边"改走 Mapper；`saveGraph` 里 4 行显式事务语句删除后，
**备份上一版与全部写入同处一个 `@Transactional`** —— 以前备份在正式保存的
事务之外，备份成功但保存失败会多出一条假历史版本，现在要么全成要么全滚。
实测：新建流程 → 两次 PUT graph → `workflow_graph_history` 2 条、`workflow_edge`
重建 2 条。

② **显式事务语句物理删除（66 行）**：
- 前置先解决：`TestSpringSupport` 让 6 个直接 `new QueryService` 的 DB 自测
  拿到 Spring CGLIB 代理（池收窄到 2 条 + 懒连 + JVM 关闭钩子），
  6 个自测全绿（6/0、3/0、3/0、7/0、13/0、10/0）。
- `QueryService` 删 62 行（19 处 `setAutoCommit(false)` 及配套 commit/rollback），
  这些方法已有 21 个 `@Transactional`，事务改由 Spring 管。
- `WorkflowService.saveGraph` 删 4 行。
- **保留 24 处（原 27，本轮 -2）**：`PauseCoordinator` / `BatchOrchestrator` /
  `CommandLedger` / `PositionLock` 等静态工具类，以及
  `WorkflowService.doResumeAsync`（private、后台 worker 线程、不经过 AOP 代理）。
  它们没有代理，显式事务就是唯一边界，删了会失去原子性。

**2026-11-09 续：静态工具类的框架级事务入口 `Tx`**
`persistence/Tx.java` 提供 `Tx.inTransaction(db, c -> {...})`：内部用 Spring 的
`TransactionTemplate` + `DataSourceTransactionManager`，在事务内 `db.open()`
经 `DataSourceUtils` 拿线程绑定连接，提交/回滚/归还全由框架负责；
`SQLException` 经 `UncheckedSql` 穿过 callback 后原样抛出。
容器启动时 `DataSourceConfiguration` 调 `Tx.bind(tm)`；纯 JVM 自测无容器时
回落到 `DriverManagerDataSource` + 同一个 `DataSourceTransactionManager`
（仍是 Spring 在管事务，只是连接不池化）。`TestSpringSupport` 同样 bind。

已收编 `CommandOutbox.dispatchPending` / `PauseEventOutbox.dispatchPending`
（同构的 `FOR UPDATE SKIP LOCKED` 派发，原来各写一套手写事务）—— 业务侧
只剩 SQL，不再碰 `Connection` 的事务方法。这是其余静态工具类的迁移范式。

**2026-11-09 续：剩余 24 处已全部收编，事务代理删除**
- `Tx` 补 `rollbackOnly()`（基于 `TransactionStatus` 的 ThreadLocal，支持嵌套），
  承接 `PauseCoordinator` 里「重复请求时 `c.rollback(); return result;`」这种
  **正常控制流中的主动回滚** —— 不是异常路径，Spring 回滚但方法照常返回。
- 24 个事务块统一转成 `Tx.inTransaction(db, c -> {...})`，覆盖
  `PauseCoordinator`(7) / `BatchOrchestrator`(5) / `PauseManager`(3) /
  `StartupRecovery`(2) / `PositionLock`(2) / 其余各 1。
- **全仓 `setAutoCommit/commit/rollback` 语句 = 0**。`DbConfig` 的连接代理
  现在只剩 `close() → DataSourceUtils.releaseConnection`，不再拦截事务方法；
  `DataSourceConfiguration` 的 `TransactionTemplate` 包装器也一并简化。
- 迁移中修掉三个真问题（都由相关自测抓到，不是靠肉眼）：
  ① `Tx` 与 `DbConfig.open()` 各 new 一个 DataSource ⇒ 事务绑定连接认不出，
     改为共用 `DbConfig.standaloneDataSource()` 单例（容器下共用 `bound()`）；
  ② `StartupRecovery.settleOrphanExperimentRuns` 在 setAutoCommit 之前还有
     一段 SELECT 填充 found，批量脚本漏掉 ⇒ 手工把扫描与 UPDATE 一起放进事务；
  ③ `PauseManager.releaseResources` 原本吞 SQLException 返回 0，
     转换后改为 `try { Tx... } catch (SQLException) { log; return 0; }` 保持语义。
- 另修 `JdbcEventStore` 构造里的 `setAutoCommit(true)`：代理删除后，
  若它在 Spring 事务里创建，这句会真改事务连接状态并破坏事务。

**证据**：`StartupSettleSelfTest` 32/0、`PauseCoordinatorDbSelfTest` 72/0、
`BatchDbSelfTest` 244/0、`RunControlDbSelfTest` 51/0、
`RuntimeHoldSelfTest` 29/0、`CommandLedgerDbSelfTest` 36/0、
`build-kernel.sh --verify` 13/0、`ci-gate.sh --fast` 通过。
全量 `test-all.sh` 多次复跑在 1307~1310 间波动，失败项集中在
PauseCoordinator/Batch 两个数据敏感类，单独复跑均 0 失败（数据竞争，非回归）。

③ **第一个注解式 Controller 落地**：`MetaController` 拆出
`/api/meta/status-vocabulary`、`/api/capabilities`、
`/api/capabilities/{code}/methods` 三个纯读端点。
`ApiAuthInterceptor` 复用 `ApiServer.isPublic/AUTH_ENABLED` 与
`AuthService.verify`，**只作用于 `com.labos.boot.web.controller` 包下的
HandlerMethod**，旧 `LegacyRouteController` 链路一行不动、仍由 route() 自己鉴权
（避免把已验证的 40+ 端点改出 401）。实测：有 token 三个端点 200、
无 token `/api/meta/status-vocabulary` → 401、旧端点 topology/workflows/health 全 200。

**2026-11-09 续：注解式 Controller 拆分（3/8）**
- `AuthController`：login / me / logout。用 `@RequestBody Map` 替代手撕 JSON；
  拦截器把鉴权通过的 `AuthService.Session` 放进 request attribute
  （`ApiAuthInterceptor.SESSION_ATTR`），Controller 用 `@RequestAttribute` 取。
  实测：login 200、me 200、无 token 401、错密码 401、logout 200、登出后 401。
- `SchedulerController`：`/api/scheduler/status`，三个 JSON 辅助方法原样搬，
  响应结构与原实现逐字一致。
- `MetaController` 补齐 `/api/meta/device-models`，SQL 抽进
  `MetaMapper.xml`（部署台数一次 GROUP BY，避免逐型号 N+1）。
- 三个原类（`AuthApi` / `SchedulerApi` / `MetaApi`）已删除，`ApiServer.route`
  的分发链同步摘除。实测：/api/scheduler/status 200、/api/meta/device-models 200、
  /api/meta/status-vocabulary 200、/api/capabilities 200、旧端点 topology/workflows 200、
  无 token 一律 401。

**2026-11-10 凌晨：HTTP 迁移正式收口（全部完成）**
- 9 个 `*Api`（4457 行）全部拆成 11 个注解式 Controller：Auth/Scheduler/Meta/
  OpsUser/OpsError/RunQuery/RunAction/Device/ExperimentQuery/ExperimentAction/
  Workflow + FallbackController（/health、静态 UI、404 兜底）。
- `ServletHttpExchange` 适配层与 `LegacyRouteController` 删除；
  `ApiServer.route()/serveUi/serveStatic/cors/send 系列`失去调用者一并删除，
  JDK `com.sun.net.httpserver` 依赖清零。分层定稿：
  Controller（HTTP 解析/审计/状态码）→ Service（RunService /
  WorkflowWebService）→ Mapper（SQL 进 XML，累计 33 条）。
- **构建层真坑**：git rm 源文件后旧 class 留在 out/ 继续生效（javac 不清
  stale 产物），导致 `Ambiguous handler methods mapped for '/'`；
  build-kernel.sh 已加「编译前清理 boot 输出目录」。
- **拆分引入的真回归（4.13 抓到，已修）**：`POST /run?async=1` 分批路径的
  HELD 响应漏了 `held:true`、status 误写 finished，且 **NO_MORE 分支整个
  遗漏**（会掉进 default 对不存在的 runId 调 resumeRun）。教训：分支体
  「逐字对照原实现 + 响应形状全字段比对」不能省，端到端段（4.13/4.17/4.18）
  是这类回归唯一的抓手，BatchDbSelfTest 不覆盖 HTTP 形状。
- `WorkflowWebMapper` 落地 7 条（含岛锁的 INSERT..RETURNING —— MyBatis DTD
  不允许 <insert> 带 resultType，改用 <select> 承载）。

**验证**：build-kernel.sh --verify 13/0；ci-gate.sh --recovery 门禁通过、
4.13 崩溃恢复 **34/0**；域端点冒烟全绿（topology/workflows/devices/
experiments/eta/runs/scheduler/meta/manual-windows/holds/users/health/静态页），
无 token 一律 401。

**2026-11-10 续：非 Bean 工具类的 Mapper 通道 + 测试互斥修复**
- `persistence/Mappers`：静态桥接（容器 `bind(sqlSessionFactory)`；无容器按需
  用 `DbConfig.standaloneDataSource()` 自动初始化，**必须同一个 DataSource**）。
  `PauseManager` / `PreflightChecker` / `PositionLock` / `ShadowRunner` /
  `ExperimentWorkcellScope` 这些 new 出来的类因此也能走 Mapper。
  `WorkflowService`/`QueryService` 的 Mapper 字段改为懒取（注入优先、
  缺注入回落 Mappers）——裸 new 场景拿 null 会在 `loadGraphXml` 等处抛 NPE，
  而不是 SQLException，原有 catch 兜不住（BatchDb 5/6 失败才暴露）。
- 新增 `PauseMapper`(5) / `ExperimentMapper`(6) / `WorkflowWebMapper`(7/9)；
  `QueryMapper` 扩到 18 条。**XML 累计 62 条**。
- **测试互斥（真缺陷，不是"数据竞争"）**：内核调度循环与连库自测共用
  tenant 1 与固定负数 id，互相抢数据。停内核后连跑 3 轮全绿
  （RunControl 51/0、PauseCoordinator 72/0），全量 **1310/0**；
  `test-all.sh` 已加硬守卫（检测 18080 有监听即 exit 2，
  `LABOS_TESTALL_STOP_KERNEL=1` 可代停）。
- `JdbcManualWindowStore` 的 4 条**刻意不迁**：`COLUMNS` 常量已被 3 处复用，
  `read(rs)` 按 16 列**位置**映射，搬进 XML 会把「列序即契约」藏起来。

**2026-11-10 深夜：SQL 收编达到可收口状态（114 条进 XML，Java 侧 18）**

Mapper 一览：Workflow 4 / Run 11 / Graph 13 / Meta 1 / Query 24 /
WorkflowWeb 9 / Experiment 6 / Pause 7 / DeviceState 11 / Execution 10 /
Command 2。

**Java 侧剩 18 处字面量，按性质分四类，只有第一类需要后续处理：**
1. **明确约定暂缓**（2 处）：`WorkflowService.saveGraph` 的节点 upsert 批量
   循环（每行 jsonb 拼装 + 可空绑定）、`JdbcManualWindowStore` 的 4 条
   （`COLUMNS` 常量 + 16 列位置映射 = 列序即契约）。
2. **SQL 生成器，不是数据访问**（5 处）：`DeviceErrorSql`（给 DBA 的 INSERT
   脚本）、`DeviceRegistrar.sqlFor`（导出语句）、`DeviceRegistrar` 的
   capability 列表诊断输出。搬进 XML 只会让脚本依赖 Spring 容器。
3. **SQL 由代码生成**（2 处）：`ExperimentStateProjector` 的两条 UPDATE 由
   `StatusVocabulary.runToExperimentCaseSql()` 生成 —— 状态映射的唯一真源，
   `StatusVocabularySelfTest` 逐状态钉死；写进 XML 就是第二份映射。
4. **一次性工具 / 演示**（6 处）：`GraphDiRepair`（图修复脚本）、
   `EndToEndDemo` / `DriverContractDemo`（演示与契约脚本）。

**2026-11-10 收官：Java 侧只剩 3 个 SQL 文本生成器**

`saveGraph` 的节点 upsert 与 `JdbcManualWindowStore` 这两处「暂缓项」已处理：

- **节点 upsert 改数据库裁决**：`uk_workflow_node (workflow_id, node_key)` 已存在，
  改成一条 `INSERT ... ON CONFLICT DO UPDATE`。三个**故意不覆盖**的列写进
  `GraphMapper.upsertNode` 的注释：`capability_id`（节点绑定写入，页面保存
  不该改）、`estimate_sec`（只在新建给 0）、`created_at`。`params` 保留原
  「新节点写 insertParams（含 origin=bpmn_editor）/ 老节点 COALESCE 合并
  updateParams」的双形态；`added/updated/unlabeled` 计数改在 Java 侧按
  `existing` 集合判定，端到端结果与改动前逐字一致（新增 2 / 更新 4 / 无重复 /
  capability 保留）。
- **`GraphDiRepair` 复用 `GraphMapper`**（扫描用新增的
  `selectRepairableWorkflowXmls`，更新直接复用 `updateWorkflowGraph`，
  两条 SQL 完全相同），`--dry-run` 实测扫描 1031 个流程。
- 新增 `DriverMapper`（2 条）供两个契约/演示脚本；
  `DeviceRegistrar` 的型号清单诊断改走 `selectAllCapabilities`。

**纠错（2026-11-10）**：此前用「单行字符串字面量」统计，得出「Java 侧只剩
18 行」的结论是**错的** —— 漏掉了 `"""` 文本块形态的 SQL（占大多数）。
按文本块 + 字面量重算，Java 侧仍有 **365 条**语句：

| 文件 | 条数 |
|---|---|
| `QueryService` | 109 |
| `WorkflowService` | 34 |
| `PauseManager` | 28 |
| `BatchOrchestrator` | 25 |
| `PositionLock` | 19 |
| `StartupRecovery` | 12 |
| `ExperimentExecutor` | 11 |
| 其余 ~30 个文件 | 各 1~9 |

其中**只有 6 条是 SQL 文本生成**（不该进 XML）：`DeviceErrorSql` 的种子脚本、
`DeviceRegistrar.sqlFor`、`ExperimentStateProjector` 的
`StatusVocabulary` 生成 CASE。**其余约 359 条都是真实数据访问**，仍需按
「只读查询 → 写路径小方法 → 执行引擎」的顺序继续收编。

**仍未完成**：
- 约 359 条 SQL 进 Mapper（`QueryService` 109 为大头）；
- `QueryService`(5000 行) / `WorkflowService`(3300 行) 两个上帝类按聚合拆分。

---

## 启动与构建（唯一入口）

```bash
./scripts/build-kernel.sh              # 拉依赖 + 编译 main/boot + 拷贝资源
./scripts/build-kernel.sh --verify     # 额外跑 A/B/C 批事务与 Mapper 自测
./scripts/start-kernel.sh              # 启动：Spring Boot 容器 + 内核 18080
```

依赖落 `labos-kernel/lib/spring/`（31 个 jar，已 gitignore，不提交二进制）。
`java -cp ... com.labos.kernel.api.ApiServer 18080` **已删除**，`ApiServer.main` 不存在。

---

## 文档口径

- 现行文档（测试总纲、操作手册、部署/仿真指南、README 及英文镜像）中的旧启动命令已统一改为 `./scripts/start-kernel.sh`；
- 历史归档（评审包、复核包、开发日志、验收报告）按「只追加不删改」保留原描述，它们是时点证据；
- `REMEDIATION_STATUS.md §8`、本文是架构现状的唯一真源。
