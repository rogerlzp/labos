# AI-native LabOS 产品战略与总体架构评审包

日期：2026-11-18  
分支：`docs/ai-native-labos-strategy-20261118`  
基线：`bef848e47131356494c3b20460fc77ce33232020`（main，2026-11-17 最新提交）

## 文档目录

1. [产品战略 V1.0](AI-NATIVE-LABOS-PRODUCT-STRATEGY-V1.0.md)：定位、用户、产品层次、优先级、验收指标和决策。
2. [参考架构 V1.0](../architecture/AI-NATIVE-LABOS-REFERENCE-ARCHITECTURE-V1.0.md)：分层架构、领域对象、双事实流、Workflow Compiler、Capability 和安全边界。
3. [Agent 与 Capability 平台设计 V1.0](../architecture/AGENT-AND-CAPABILITY-PLATFORM-DESIGN-V1.0.md)：个人/项目虚拟团队、Agent Runtime、工具权限、Skill 验证等级和记忆治理。
4. [12 个月路线图 V1.0](../roadmap/AI-NATIVE-LABOS-12-MONTH-ROADMAP-V1.0.md)：现状盘点、只读助手、编译器、Agent、执行辅助和 DBTL 阶段门禁。
5. [Labillion、AutoDNA 与国际进展对标](../benchmark/LABILLION-AUTODNA-AND-INTERNATIONAL-LANDSCAPE-2026-11.md)：上传彩页分析、论文与国际产品/标准对标。

## 核心结论

LabOS 的目标不是“给中控加一个聊天框”，而是演进为 AI-native Laboratory Operating System：

- LabOS Core：可信实验执行底座；
- LabOS AI：科研人员与项目的虚拟 AI 团队；
- LabOS Applications：垂直实验应用和经过验证的方法模板。

关键架构原则：AI 负责推理和建议；中控负责校验、授权、执行、状态和事实。自然语言不能直接变成设备命令；必须经过 Workflow DSL、Compiler、静态校验、资源规划、仿真、审批和执行快照。

## 本评审包范围与限制

- 本次新增为产品/架构文档，没有修改业务代码。
- Labillion 内容基于用户本次上传的 16 页彩页；彩页不能代替现场验收。
- AutoDNA 和海外平台内容基于文末公开论文、标准和官方技术文档。
- 对当前中控实现的映射是目标架构和核查清单，不是已经完成的全仓源码审计。
- 下一步应将目标架构映射到真实代码入口、API、数据表、状态机、事件和测试，形成 PASS/FAIL/UNKNOWN 实现矩阵。

## 建议评审顺序

1. 产品战略：确认目标用户、首个场景和非目标。
2. 参考架构：重点评审 L2 事实边界、命令/物理事实双流、DSL/Execution Snapshot。
3. Agent 平台：重点评审工具权限、记忆隔离、Skill 验证等级。
4. 路线图：确认 G0 源码盘点与 G1 只读助手优先级。
5. 对标报告：校正竞品事实与差异化策略。

## 当前建议的下一步

先开展 G0：现有中控核心模块源码盘点，特别是 Experiment/Run/Workflow/Node、Device/Driver/Capability、Command、Material/Sample/Position、Scheduler/Interlock、Permission/Audit/Recovery。完成后再决定哪些 Capability API 直接复用、哪些需要补齐，避免为 AI 平台重复造业务底座。
