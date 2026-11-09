# P-01 只读盘点：workflow 的「岛归属」现在到底在哪

日期：2026-11-09　脚本：`scripts/workflow_island_inventory.py`（**全部 SELECT，零写入**）
范围：`tenant_id=1`、`deleted_at IS NULL` 的 workflow。

> 依据：`ADR-ISLAND-001`（Workflow 应有权威 Workcell）、`ADR-ISLAND-005`
> （`experiment.island_id` 只是内部分区键/岛名展示，不承担「主 Workcell」语义）、
> `LABOS_ISLAND_BOUNDARY_IMPLEMENTATION_SPEC_20261107.md` §2.1/§2.3
> （写路径应单事务、先校验权威岛）。
> 整改计划明确要求「**先出只读盘点，不自动推断、不破坏性迁移**」——本文只交付
> 盘点结论与待决策项，**没有加列、没有加 FK、没有回填、没有改任何写入路径**。

---

## 1. 结论先行

`workflow` 表**没有** `island_id` 列（`pg_rebuild/schema/20_kernel.sql:189-205`）。
目前唯一的归属信息是 `workflow.attr->>'island_id'`，而它是：

1. **一个没人读的镜像**：全仓 `grep` 后端 `attr.*island` 命中 0 处。写入方只有
   `WorkflowService.createWorkflow`；设计器的岛过滤取的是 **URL query
   `?islandId=`**（`webapp/src/pages/Designer.tsx:809-810`），不是 attr。
2. **另一套 id 空间**：993 个数值里 **991 个不是** `resource_node.id`。
3. **写入不原子**：`createWorkflow` 先 INSERT workflow、`saveGraph` 再开一条连接，
   最后 UPDATE attr 又开一条连接 —— 三次连接、三个事务，任意一步失败都会留下
   半张流程（§2.3 点名的正是这条）。

所以「加权威列 + FK + 双写」**不能**直接照字面做：照字面加 FK 会让 991 张流程
瞬间变成非法数据。

## 2. 盘点数字（2026-11-09 实跑）

| 项 | 值 |
|---|---|
| workflow 总数 | **1027** |
| `attr` 有 `island_id` 键 | 994 |
| 无该键 | 33 |
| 键在但值为空 / JSON null | 34 |
| 值为纯数字 | 993 |
| 数值能直接解析到 `resource_node(kind='island')` | **2** |
| 数值**悬空**（对不上任何权威岛） | **991** |
| 有设备绑定但没有任何岛声明（走「回落全部岛」） | **27** |
| 声明岛与所绑设备的父岛不同的流程 | 0（只有 2 对能比，都是相同） |

`attr.island_id` 取值 Top：

| 历史值 | 张数 | 历史值 | 张数 |
|---|---|---|---|
| 27 | 334 | 31 | 254 |
| 33 | 155 | 32 | 67 |
| 37 | 64 | 35 | 57 |
| (空) | 34 | 46 | 21 |
| 52 | 13 | 49 / 40 | 各 6 |

权威 `resource_node(kind='island')` 的 id 是 **100027 / 100028 / 100029 /
100030 / 100031 / 100032 / 100033 / 100034 / 100040 / 100046 / 100049 /
100052 …**（名称 CMS、CC、Pre-NGS、Imaging、Device_Test、HTS…）。

`experiment.island_id` 又是第三种口径：空 650 条、`100030` 19 条、
`100033` 8 条、`100027` 1 条，另有一个 `504821`（8 条）。

## 3. 一个「看起来很对」但不能自动采用的映射

把历史值 **+100000** 之后：

| 历史值 | 候选权威 id | 岛名 | 张数 |
|---|---|---|---|
| 27 | 100027 | CMS | 334 |
| 31 | 100031 | CC | 254 |
| 33 | 100033 | Pre-NGS | 155 |
| 32 | 100032 | Imaging | 67 |
| 37 | 100037 | SIQC | 64 |
| 35 | 100035 | Post-NGS | 57 |
| 46 | 100046 | 流动相岛屿 | 21 |
| 52 | 100052 | 分子克隆岛屿 | 13 |

**991 / 993** 能对上，且岛名与业务流程命名自洽。另外 2 条本身已经是
1000xx 全量 id（所以直接解析成功的也是 2 条）。

⚠ 但这是**推断**：没有任何代码/文档/外键证明 `+100000` 是设计约定，可能只是
某次数据迁移的巧合。按「不自动推断、不破坏性迁移」，本轮**不回填**，
把它作为待用户裁决的候选方案。

## 4. 写入路径现状（`createWorkflow`，`WorkflowService.java:2890`）

```
INSERT workflow (...) RETURNING id        -- 连接①，事务①
saveGraph(id, blankGraphXml(...))          -- 连接②，事务②
UPDATE workflow SET attr = jsonb_set(...   -- 连接③，事务③，仅 islandId>0 时
         '{island_id}', to_jsonb(?)) 
```

- 岛归属放在 `attr` 里，`attr` 是「差异化属性（SOP/LIMS 关联）」，
  语义上是展示偏好不是权威事实；注释也写明「避免为展示偏好改 DDL」。
- 三连接三事务 ⇒ 中途失败会留下「有流程、没图、没岛」的半成品。
- `islandId` 为空/0 时**不写 attr**，设计器据此「回落全部岛」；
  这正是 P-01 要去掉的语义，但直接去掉会让 27 张已有设备绑定的流程无岛可选。

## 5. 待裁决（决定 P-01 后续 3 个 PR 的形状）

1. **权威 id 空间**：`workflow.island_id` 指向 `resource_node(id)` 吗？
   历史 991 条是否按 `+100000` 迁移？（还是保留 NULL、只对新流程生效？）
2. **存量策略**：迁移 / 保留 NULL 由设计器兜底 / 标 `UNKNOWN` 阻断（P-02 的
   `IslandBoundaryValidator` 会把 UNKNOWN 当失败）。
3. **「回落全部岛」的替代**：27 张无声明流程怎么处理；创建流程时是否强制选岛。
4. **N-05 的接口扩展顺序**：`BoundaryResult` 加 `boundaryKind/scopeType/evidence`
   是 P-02 的前置，需与上面的裁决一并定。

## 6. 本轮已做 / 未做

- 已做：`scripts/workflow_island_inventory.py`（只读、可重复执行、`--json`）；
  本文；状态表 P-01 标「只读盘点完成，迁移待裁决」。
- 未做：任何 DDL、任何 FK、任何回填、任何写入路径改动、任何 UI 改动。

复现：

```bash
LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/workflow_island_inventory.py
```
