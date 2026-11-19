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
- 旧 bigint tenant 到 Runtime UUID 的稳定映射。
- GraphPlan 按租户与 workflow 标识，创建 Run 时固化 snapshot。
- 保留 Kernel 既有 javac/out 构建方式，构建脚本先安装 Runtime JAR。

## 不要误判为已完成

这不是 Runtime 全量投产 PR。GraphCoordinator、条件表达式求值、真实 EventIngress、OutboxDispatcher、DeviceAdapter、EventDispatcher、Mock E2E 和真机 readback 仍未完成。RuntimeHost 尚未被装配为实际启动服务，也未切换旧 GraphRunner 流量。

## 审核结论格式

每项必须标注：
- PASS：有源码/测试证据；
- FAIL：明确缺陷，给出文件、行号、复现方式；
- UNKNOWN：缺少证据，不能推断通过。

不要仅根据本评审包文字给 PASS。必须检查 PR 的实际 diff、相关基线代码、CI 日志和 SQL。
