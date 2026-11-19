# LabOS Workflow Runtime Kernel 集成评审包

本评审包用于独立审查 Workflow Runtime 嵌入 Kernel 的代码、构建链、事务边界和 BPMN 定义读取链路。

## 评审基线

- Repository: rogerlzp/labos
- PR: https://github.com/rogerlzp/labos/pull/5
- Base branch: docs/workflow-runtime-single-instance-20261119
- Base commit: e508e6e80f3ad28df1ecb3a7d0fb31723bcf1437
- Review branch: feat/workflow-runtime-t24-integration-20261119
- Review HEAD: 以 PR 页面最新 head SHA 为准；评审开始时先记录 SHA，评审期间若 HEAD 改变应停止并重新确认。
- 部署约束：一个 Kernel Spring Boot 进程、一个 Runtime Host、一个 PostgreSQL 17；不做多实例/分布式协调。

## 阅读顺序

1. 01-架构与决策边界.md
2. 02-关键代码与数据路径.md
3. 03-构建测试与复现.md
4. 04-独立评审任务书.md
5. 05-评审邀请-可直接转发.md

## 本轮改动主题

- 独立 Maven workflow-runtime 模块作为 JAR 嵌入 Kernel。
- Repository 显式注入 Kernel 同一个 DataSource 和 PlatformTransactionManager。
- Kernel MyBatis Adapter 读取原始 BPMN XML 和租户限定的节点元数据。
- 旧 bigint tenant 到 Runtime UUID 的稳定映射，以及 Kernel-facing Run 创建 Facade。
- 定义读取使用一致的 REPEATABLE_READ snapshot，Runtime Header/XML 排除软删除流程，能力元数据 join 按 tenant 隔离。
- GraphPlan 按租户与 workflow 标识，创建 Run 时固化 snapshot。
- Runtime 定义读取采用 REQUIRES_NEW + REPEATABLE_READ，Header 排除软删除，并强化 MyBatis BoundSql 参数与租户谓词测试。
- 保留 Kernel 既有 javac/out 构建方式，构建脚本先安装 Runtime JAR。

## 不要误判为已完成

这不是 Runtime 全量投产 PR。GraphCoordinator、条件表达式求值、真实 EventIngress、OutboxDispatcher、DeviceAdapter、EventDispatcher、Mock E2E 和真机 readback 仍未完成。RuntimeHost 尚未被装配为实际启动服务，也未切换旧 GraphRunner 流量。

## 当前实现基线

- 最近一次 Runtime PostgreSQL 17 全绿：Run #252，提交 `4e6dbc9dedba7d453ba6d49210a5da115013ed4c`；30 tests，0 failures/errors。
- Kernel CI #592：success，HEAD `703ff84b292ed0f6ece5d2b1b180e44ec2b00730`；Java 纯逻辑 1576/0、TypeScript 0 错误、DeviceEventApi 17/0、Sidecar 8/0。评审时仍需重新核对 PR 最新 HEAD 对应 Actions。
- 最近一次代码验证 SHA：`703ff84b292ed0f6ece5d2b1b180e44ec2b00730`（Kernel CI #592 success）；之后只有 Kernel/评审文档更新，未改 workflow-runtime 模块代码。当前 PR HEAD 仍以 PR 页面为准，评审开始时必须重新记录。

## 审核结论格式

每项必须标注：
- PASS：有源码/测试证据；
- FAIL：明确缺陷，给出文件、行号、复现方式；
- UNKNOWN：缺少证据，不能推断通过。

不要仅根据本评审包文字给 PASS。必须检查 PR 的实际 diff、相关基线代码、CI 日志和 SQL。
