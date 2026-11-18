# AutoDNA 与国际公开进展对标（截至 2026-11-18）

- 日期：2026-11-18
- 目的：为 LabOS 产品与技术路线提供外部参照
- 证据类型：公开论文、产品文档与标准网页
- 限制：厂商公开页面与产品文档属于自述，不能替代客户现场验收、第三方审计或源码评估。

## 1. 对标结论

行业正在从“单台设备自动化”向“实验室级工作流自动化 + AI 科研协作 + 数据闭环”发展。竞争焦点逐渐转向：
1. 跨设备互操作；
2. 从实验意图到可执行计划的编译；
3. 设备、样本、物料、位置和数据的端到端谱系；
4. 真实执行中的异常诊断与恢复；
5. AI 方案与真实实验结果之间的闭环。

LabOS 不需要复制所有竞品模块，而应以现有中控执行底座为锚点，强化“可靠执行 + 可信事实 + 可审计 AI”。

## 2. AutoDNA（Nature, 2026-11-04）

论文：Wu et al., “An AI-native experimental laboratory for autonomous biomolecular operations”, Nature (2026), DOI: 10.1038/s41586-026-11228-6。

论文摘要公开信息：
- 面向多目标生物分子工程实验；
- 多智能体与实验/仪器共同设计；
- 协调 20 多类仪器和 11,000+ 硬件步骤；
- 核酸合成、转录、扩增和测序等核心操作；
- 报告四类代表性应用，包括酶促 DNA 合成优化、DNA 数据存储和 KRAS G13D 检测；
- 论文报告并发协议执行加速 3.2 倍，并在 DNA 数据存储任务中恢复原始数字内容。

Nature News & Views《AI agents enter the ‘wet lab’》强调 AI 开始缩短“提出实验”与“真正执行实验”之间的距离。Nature 新闻稿也说明设备工程和多类仪器整合是挑战。论文补充材料列出硬件错误恢复视频，说明恢复机制是平台组成部分，不是外围功能。

对 LabOS 的启发：
- AI 原生架构应在实验、模型和仪器协同设计时考虑，而非只在界面外挂 LLM。
- 原子实验操作（如 transfer、mix）需要能力化、组合化，并有明确完成证据。
- 多 Agent 协作必须由统一实验上下文与权威状态支撑。
- 自动恢复是核心能力，但恢复边界和人工接管仍须明示。
- 论文成果限定在特定平台与实验范围，不意味着通用实验室完全自治已经解决。

## 3. 国际平台与标准

### 3.1 Synthace
公开文档描述：
- 科学家定义数字实验 Workflow；
- Planner 根据 Workflow 和设备模型生成可执行计划；
- 计划形成 Simulation 快照，供调试与检查；
- 用户认可后才创建 Execution；
- Execution 关联实际执行任务和测量数据；
- 支持自动、半自动和人工执行模式。

借鉴：将 Workflow Definition、Simulation/Plan Snapshot、Execution 分离；让计划在执行前可视化、可检查、可批准；将测量数据关联到实验实体。

需要注意：公开文档指出手动/半自动执行状态追踪能力存在差异，因此不能把“数字化流程”默认等同于所有物理执行均被实时验证。

### 3.2 Emerald Cloud Lab
公开产品材料强调统一软件入口、实验方法脚本化、手动与自动技术整合、数据分析和实验元数据/数据完整性管理。其启发是实验室执行、知识、数据与分析可以作为一个整体产品体验。厂商自述不构成独立性能验证。

### 3.3 Opentrons
公开文档提供 Python Protocol API 和 HTTP API，覆盖 Labware、模块、移液器、夹爪、吸液/分液、机器人状态、校准和运行管理；Flex 是模块化系统，可通过 API 控制。其启发是开放 API、设备开发者生态和可替换模块有助于降低集成门槛。设备接口仍需要具体适配与验证。

### 3.4 SiLA 2
SiLA 2 是设备互操作标准，公开规范使用 HTTP/2、Protocol Buffers/gRPC 等技术，核心概念包括 Feature、Command、Property、数据类型、错误处理、安全和发现机制。适合用于统一设备能力暴露与连接，不负责完整实验语义、样本谱系或实验级审批。

建议：LabOS 内部定义自己的 Capability Domain Model，设备适配器可映射 SiLA 2；对现有厂商协议继续使用 Adapter，不强迫所有设备立即迁移。

### 3.5 Autoprotocol
Autoprotocol 的设计目标包括灵活、可组合、可合成到硬件、平台无关和“Just Data”；协议用精确步骤表达实验操作，避免依赖自然语言解释。它不包含生物学知识本身，强调实验步骤的确定性描述。

建议：借鉴其数据化、可组合、可编译思想；LabOS DSL 还需增加实验身份、权限、安全、物料位置、质量控制、异常和执行证据等中控所需语义。

### 3.6 自驱动实验室研究趋势
2026 年 Nature Reviews Chemistry 综述将自驱动实验室下一阶段关键问题概括为 scalability、generalizability 和 provenance-complete experimentation，并强调数据/元数据标准、模块化硬件和可信 AI Agent。对 LabOS 而言，跨实验室泛化不应靠 LLM 自由发挥，而应靠明确的设备能力、方法版本、实验数据上下文和适用条件。

## 4. 能力对照矩阵

| 能力 | AutoDNA 论文 | Synthace 文档 | Opentrons/SiLA/Autoprotocol | LabOS 建议 |
|---|---|---|---|---|
| AI Agent | 多 Agent 端到端实验 | 文档重点是数字实验工作流 | 非核心重点 | 主 Agent + 受控专业 Agent |
| 实验 DSL | 实验/硬件步骤共同设计 | Workflow → Planner | Autoprotocol 精确协议 | 版本化 DSL + Compiler |
| 仿真/计划 | 平台整体执行 | Simulation 快照 | 设备仿真/API 取决于生态 | 静态/资源/物理分级仿真 |
| 设备互操作 | 20+ 仪器协同 | 自动化设备集成 | API、SiLA 2 | Capability API + Adapter |
| 样本谱系 | 生物样本流程 | 实验实体与测量关联 | 协议层提供部分引用 | 样本/物料/位置/数据全链 |
| 异常恢复 | 论文补充材料展示恢复 | 依执行模式而异 | 设备 API/厂商能力 | 双事实流、Reconciler、人工接管 |
| DBTL | 有优化实验应用 | DoE/设计分析 | 依应用构建 | 单一场景先闭环 |
| 证据可审计 | 论文/补充材料/代码可查 | Simulation/Execution 模型 | 标准和 API 文档 | 计划、命令、观察、结果全审计 |

## 5. LabOS 的差异化定位

不建议用“Agent 更多、模型更多、接设备更多”作为竞争策略。建议把差异化聚焦为：

1. **可信执行状态**：实验、节点、命令、设备、物料和位置之间的状态约束明确。
2. **计划与执行可分离**：AI 计划先校验、仿真、审批，再冻结执行。
3. **命令事实与物理事实双流**：设备报告完成与物料实际到位分别取证。
4. **跨设备能力抽象**：统一 Capability API，保留厂商协议原始信息。
5. **样本级数据谱系**：将每个结果追溯到样本、试剂、方法、设备、参数和偏差。
6. **AI 工作流可治理**：每个 Agent 有权限、预算、工具、记忆范围、模型版本和审批策略。
7. **从执行到科学改进**：在特定实验域形成可复现 DBTL，而非泛化宣传“自主科研”。

## 6. 来源

### 公开论文/研究
- Wu, M. et al. “An AI-native experimental laboratory for autonomous biomolecular operations.” Nature, 2026-11-04. https://doi.org/10.1038/s41586-026-11228-6
- Ledford, H. “AI agents run this automated biology lab that sequences DNA and more.” Nature News, 2026-11-04. https://www.nature.com/articles/d41586-026-03552-8
- Zou, J. “AI agents enter the ‘wet lab’.” Nature News & Views, 2026-11-04. https://www.nature.com/articles/d41586-026-03270-1
- Canty, R. B. & Abolhasani, M. “The past, present and future of self-driving laboratories.” Nature Reviews Chemistry, 2026-07-31. https://www.nature.com/articles/s41570-026-00847-2

### 产品文档/标准
- Synthace Developer Documentation: https://developer.synthace.bio/documentation
- Emerald Cloud Lab: https://www.emeraldcloudlab.com/
- Opentrons Flex Documentation: https://docs.opentrons.com/flex/
- SiLA 2 Standards: https://sila-standard.com/standards/
- Autoprotocol Specification: https://autoprotocol.org/specification/

## 7. 证据等级声明

- **A：** 论文、标准规范、官方技术文档中明确描述的内容。
- **B：** 厂商官网或公开产品文档中的功能自述。
- **C：** 基于上述材料对 LabOS 的架构推论和建议。
- **UNKNOWN：** 需要现场测试、源码或第三方验证才能确认的能力。

本文没有把厂商自述提升为独立验收结论。
