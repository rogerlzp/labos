# LabOS AI Capability / Skill / Agent 验证与成熟度标准

- 日期：2026-11-18
- 状态：设计规范；尚未通过任何具体能力验收
- 适用范围：Capability、Skill、Agent、设备/驱动、实验场景及其版本

## 1. 目的

建立统一、可审计的成熟度表达，避免“代码已写”“单测通过”“模拟器可跑”被误报为真实实验室能力已可靠。成熟度是逐对象、逐版本、逐场景的，不是平台整体营销标签。

## 2. 五级成熟度

| 等级 | 名称 | 含义 | 最低证据 |
|---|---|---|---|
| L0 | Defined | 需求、输入输出、风险、权限和失败语义已定义 | 版本化契约、Schema、风险分析、验收用例 |
| L1 | Implemented | 有可运行实现和自动化测试 | 源码定位、迁移/配置、单元/集成测试原始结果 |
| L2 | Bench Validated | 在台架/模拟设备/受控测试环境验证 | 环境版本、设备/模拟器配置、故障注入、原始日志 |
| L3 | Lab Validated | 在指定真实实验室、指定设备/方法/耗材组合完成现场验证 | 现场记录、校准/维护状态、操作员签核、重复运行结果 |
| L4 | Production Qualified | 在明确生产边界内完成可靠性、安全、运维和变更控制验收 | 运行统计、SLO、恢复演练、审计、培训、变更与回滚证据 |

禁止跳级。某个设备的 L3 不代表其所有方法、耗材、固件或实验场景均为 L3。

## 3. 各类对象的独立评级

### 3.1 Capability

至少记录：
- capability_id、version、owner、purpose；
- request/response schema 与兼容策略；
- 读/写副作用、风险等级、权限和审批要求；
- 幂等键、超时、重试、取消和补偿语义；
- 依赖的设备/驱动/数据对象及其最低成熟度；
- 错误码、证据引用、审计和监控；
- 当前成熟度、适用环境和不适用边界。

### 3.2 Skill

除 Capability 元数据外，还需记录：
- 依赖的 Capability 版本锁；
- 输入上下文和输出格式；
- 提示注入、错误工具选择、幻觉和越权测试；
- 模型/provider/version 适用范围；
- 人工复核点、置信表达和 UNKNOWN 处理；
- 回归样本集与版本变更评估。

### 3.3 Agent

还需记录：
- Agent Template / Runtime 版本；
- 角色、项目委托范围、工具白名单；
- 任务分解与恢复策略；
- 跨用户/跨项目隔离；
- 失败停止条件、预算/超时、审计链；
- Agent 不能提升其所调用 Capability 的成熟度。

### 3.4 设备/驱动/实验场景

评级必须绑定设备型号、序列号或受控资产标识、固件、驱动版本、方法版本、耗材/样本条件、实验室环境和操作规程。对物理位置、液体处理、温控、开盖和样本移动等风险单独定义故障模式及现场验收。

## 4. 证据分层

每个验收声明必须标注证据类型：
- E0：设计/文档；
- E1：源码与静态分析；
- E2：自动化测试原始结果；
- E3：模拟器/台架结果；
- E4：真实实验室现场记录；
- E5：生产运行和运维数据。

证据不能跨级替代：E0 不证明实现；E1 不证明运行；E2 不证明真实设备；E3 不证明现场；E4 不自动证明生产级稳定。

## 5. 通过规则

- 每项能力声明使用 PASS / PARTIAL / FAIL / UNKNOWN。
- PASS 必须绑定具体版本、环境、测试范围和证据 ID。
- PARTIAL 必须列出未覆盖场景；不得在产品界面只显示“已验证”。
- UNKNOWN 表示没有足够证据，不能默认通过。
- 任何安全/物理事实关键测试失败，能力不得晋级。
- 依赖项成熟度不足时，上层 Agent/Skill/Capability 不得高于其关键依赖的可证明等级。
- 发生模型、提示词、Schema、驱动、固件、设备方法或安全策略变更时，按影响范围重新验证。

## 6. 建议能力登记卡

```yaml
id: material.location.verify
version: 1.0.0
owner: lab-control
scope:
  environment: bench
  project: synthetic-project
dependencies:
  - device.driver.example@2.1.0
risk: high
side_effect: write
authorization:
  roles: [operator]
  project_membership_required: true
idempotency:
  required: true
  key_scope: tenant-project-capability
verification:
  status: PARTIAL
  maturity: L1
  evidence:
    - type: E2
      id: test-report-2026-11-18
limitations:
  - no real-lab verification
```

示例仅说明登记格式，不代表该 Capability 已存在或已达到 L1。

## 7. 晋级验收清单

- [ ] 契约、Schema、风险和权限边界已版本化
- [ ] 源码实现与依赖版本已定位
- [ ] 正向、负向、并发、超时、取消、重试和恢复测试已执行
- [ ] 租户/项目越权测试通过
- [ ] 审计与证据链可追溯
- [ ] 台架故障注入与原始日志已归档（L2+）
- [ ] 指定真实实验室现场重复验证已签核（L3+）
- [ ] 生产 SLO、告警、恢复、回滚和变更管理通过（L4）

## 8. 实施状态

本标准只定义如何证明能力成熟，不证明任何能力已成熟。每次晋级应形成独立验收记录，并将版本、环境和证据写入 Capability Catalog。
