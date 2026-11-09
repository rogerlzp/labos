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

**仍未完成**：
- 约 128 条 SQL 按聚合收进 Mapper（`run_node` / `run_command` / `experiment` /
  `device` / `command_ledger` …）；
- 46 处显式事务语句的物理删除（前置：5 个 DB 自测容器化）；
- 把 9 个 `*Api` 逐个拆成注解式 `@RestController`，届时删除
  `ServletHttpExchange` 适配层；
- `ServletHttpExchange` 是**迁移期适配层**，不是长期架构。

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
