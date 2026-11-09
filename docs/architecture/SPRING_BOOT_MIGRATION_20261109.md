# Spring Boot 迁移：容器 / DataSource / 声明式事务（阶段一）

日期：2026-11-09　范围：`labos-kernel`　状态：**阶段一已完成并验证**

---

## 1. 为什么迁

迁移前的事实（2026-11-09 盘点）：

| 项 | 值 |
|---|---|
| `db.open()` 调用点 | **396 处** |
| 显式 `setAutoCommit(false)` | 46 处（约 12%） |
| 手写连接池 | `DbConfig` 里自研 8 连接队列 + 动态代理 |
| Spring / MyBatis / JPA / `@Transactional` | **0 处** |
| `pom.xml` 运行时依赖 | 0 个（Postgres 驱动靠手动 `-cp`） |
| HTTP 层 | JDK 内置 `com.sun.net.httpserver.HttpServer` |
| 启动 | `java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.api.ApiServer 18080` |

用户裁决：**直接用 Spring Boot 处理**，不再在裸 JDBC 上补手写事务模板。

## 2. 阶段一落地了什么

| 文件 | 作用 |
|---|---|
| `labos-kernel/pom.xml` | 引入 `spring-boot-starter-jdbc` 3.2.5（Java 17）、PostgreSQL、JUnit |
| `src/boot/java/com/labos/boot/LabosApplication.java` | `@SpringBootApplication`，`WebApplicationType.NONE`（不启 Tomcat） |
| `.../DataSourceConfiguration.java` | **HikariDataSource**（替代手写池）、`DataSourceTransactionManager`、`TransactionTemplate` |
| `.../KernelRunner.java` | 容器就绪后拉起原有 `ApiServer`，40+ 端点与启动收尾逻辑零改动 |
| `.../SpringTxSelfTest.java` | 8 条断言，证明框架真的在管事务 |
| `src/main/.../persistence/DbConfig.java` | **桥接层**（见 §3） |
| `scripts/build-kernel.sh` / `start-kernel-spring.sh` | 构建与正式启动入口 |

### 3. 桥接原理（为什么不改 396 处调用点）

全仓只有 `DbConfig.open()` 一个取连接入口。容器启动时调用
`DbConfig.bindDataSource(ds)` 把 Spring 的 DataSource 注入静态字段，之后 `open()`：

```
有 Spring 容器  → DataSourceUtils.getConnection(ds)
                   → 事务内返回同一个线程绑定连接
                   → close() 交给 releaseConnection（事务内不真关）
无 Spring 容器  → 回落原有 8 连接池（DB 自测 / 旧启动方式行为零变化）
```

`DbConfig` 用**反射**调用 `DataSourceUtils`，编译期与旧 classpath 都不需要 spring jar ——
这是让 396 处调用点、全部自测、门禁 4.13 的重启命令零改动的关键。

对遗留显式事务代码，代理连接会拦下越界操作：

| 遗留调用 | 事务内的处理 | 原因 |
|---|---|---|
| `setAutoCommit(false)` / `commit()` | no-op | 交给 Spring 统一提交，防止提前提交半截事务 |
| `rollback()` | `setRollbackOnly()` | 防止遗留代码 catch 住异常不抛，却把写入提交了 |
| `close()` | `releaseConnection` | 事务内不能真关连接 |

`TransactionTemplate` 由容器包装，把当前 `TransactionStatus` 暴露给桥接层
（编程式事务下 `TransactionAspectSupport.currentTransactionStatus()` 不可用）。

## 4. 实测证据

`./scripts/build-kernel.sh --verify`（8/0）：

```
✓ DataSource 已由 Spring 容器绑定 HikariDataSource (labos-spring-pool)
✓ 事务内两次 db.open() 是同一物理连接 336499 / 336499   ← pg_backend_pid 相同
✓ 事务抛异常应向外传播
✓ 回滚后一条都不剩 0
✓ 提交后两条都在 2
✓ 遗留 commit() 未提前提交（rollback-only 仍生效） 0
✓ 遗留 rollback() 吞异常后仍不提交 0
✓ 解绑 Spring 后原连接池仍可用                        ← 旧启动方式零回归
```

真实启动（`./scripts/start-kernel-spring.sh`）：

- `HikariPool - Start completed` → `Started LabosApplication in 0.76 seconds`
  → `[Spring Boot] 内核在 labos-http-kernel 线程启动（端口 18080）`
- 登录 200；`/api/topology` 30 岛、`/api/workflows` 200、`/api/devices/pool` 247 设备
- `POST /api/workflows` 200，新流程 `id=1000044`（1,000,000 新号段），可读、已清理
- `test-all.sh` 全量 **1310/0**；`ci-gate.sh --fast` 通过（718/0、tsc 0、产物同源）

> 期间出现过一次 `1238/失败1`，是同时跑 API 验证 + 内核启动收尾造成的并发干扰；
> 单独连续重跑两次均为 `1310/0`。

## 5. 启动与构建

```bash
./scripts/build-kernel.sh              # 拉依赖（~/.m2 或自动下载 maven）+ 编译 main/boot
./scripts/build-kernel.sh --verify     # 额外跑 Spring 事务桥接自测
./scripts/start-kernel-spring.sh       # 正式入口：Spring Boot 容器 + 内核
```

依赖落在 `labos-kernel/lib/spring/`（已 gitignore，26 个 jar 约 11 MB，不提交二进制）。
旧的 `java -cp out:lib/postgresql-42.7.3.jar ...ApiServer 18080` **仍然可用**：
探测不到 Spring 时自动回落原连接池。门禁 4.13 的 `kill -9 → 重启` 因此不需要改动。

## 6. 还没做的（阶段二 / 三，不冒充「整个做完」）

| 阶段 | 内容 | 为什么单独做 |
|---|---|---|
| 二 | 把 `WorkflowService` / `QueryService` / `ExperimentExecutor` 等注册为 Spring Bean，再用 `@Transactional` 改写 P-02 的三条写路径（`createWorkflow`、`saveGraph`+`backupGraph`、`updateNodeBinding`），并去掉「回落全设备池」等宽松校验 | 这些类目前是 `new` 出来的，**不走 AOP 代理**，直接贴 `@Transactional` 会静默失效。改成 Bean 会动到构造与启动链，需逐条重验 |
| 二 | 46 处显式 `setAutoCommit(false)/commit()` 收编为声明式事务 | 桥接已保证它们不越界，但代码仍在；逐步替换，每批单独回归 |
| 二 | 4.13 崩溃恢复段改用 Spring 启动 | `verify-s3-2-residuals.py` 的 `api_pids()` / `detect_api_log()` 按 `com.labos.kernel.api.ApiServer` 匹配进程，Spring Boot 进程命令行是 `LabosApplication`，需同步改成按端口反查并重验整段 |
| 三 | HTTP 层从 JDK `HttpServer` 迁到 Spring MVC Controller | 40+ 端点、全部 e2e 与门禁按现有路由写，属独立大改 |

**当前真实状态**：容器、连接池、事务管理器、桥接都由 Spring 管，
`TransactionTemplate` 与 `@Transactional`（对 Spring Bean）已经可用；
业务写路径尚未声明式化，HTTP 路由仍在容器外的 JDK HttpServer 里。
