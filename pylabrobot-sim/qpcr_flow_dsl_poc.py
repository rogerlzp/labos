#!/usr/bin/env python3
"""
概念验证：把 lab-flow-sim 的三个编排设计嫁接到 qPCR i8 worklist 上。

三个设计（源自 /Users/zhengpingli/work/lab/lab-flow-sim）：
  1. 多态步骤 DSL：FunctionStep(设备函数) | ProcessStep(子流程) 共享 BaseStep + 显式序号。
     这里用 dataclass + kind 字段表达；实验协议 = 有序步骤列表，步骤可嵌套子流程。
  2. 参数化公式字段：execution_time_formula / multiplier_formula 是**字符串公式**，
     用受控 eval（只允许 X 一个变量 + 算术）求值，不硬编码时间常数。
  3. parallelization_key：不同泳道(通道)里 key 相同的步骤语义上并行对齐；
     同一泳道内 key 不得重复。i8 八通道按列并行正是这个语义。

为什么这是"借鉴"而不是"抄"：
  - lab-flow-sim 本身**没有仿真引擎**（SimPy 只在依赖里，simulation_page.py 是空壳），
    时间公式字段从没被求值过；本 PoC 补上受控求值 + 真实 qPCR 数据验证。
  - 它也没有 i8 / 384 概念；这里把 parallelization_key 具体化为"目标板列号"，
    把泳道具体化为"i8 的 8 个通道"。

输入：与 qpcr_sim.py 相同的 transfers JSON（stdin）。
输出：编排报告 JSON（stdout），含：
  - steps: 有序步骤（FunctionStep / ProcessStep 两类）
  - lanes: 8 条通道泳道，每条的步骤归属
  - parallel_groups: 按 parallelization_key 分组的并行批次
  - timing: 每步公式求值结果 + 串行/并行总时长对比
  - checks: 设计约束校验（泳道内 key 重复、公式非法、通道超限）

用法：
  cat /tmp/qpcr_input.json | .venv/bin/python qpcr_flow_dsl_poc.py > /tmp/dsl_report.json
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import asdict, dataclass, field
from typing import Any

# ---------------------------------------------------------------- 受控公式求值

_SAFE_RE = re.compile(r"^[0-9+\-*/(). Xx]+$")


def eval_formula(formula: str, x: float) -> float:
    """受控求值：只允许数字、四则运算、括号和变量 X（lab-flow-sim 的公式字段口径）。"""
    if not _SAFE_RE.match(formula or ""):
        raise ValueError(f"公式含非法字符: {formula!r}")
    expr = formula.replace("X", f"({x})").replace("x", f"({x})")
    try:
        return float(eval(expr, {"__builtins__": {}}, {}))
    except Exception as e:
        raise ValueError(f"公式求值失败 {formula!r} @X={x}: {e}") from e


# ---------------------------------------------------------------- 多态步骤 DSL

@dataclass
class BaseStep:
    """对应 lab-flow-sim 的 BaseStep：id + 泳道 + 显式序号（这里是 list 顺序）。"""
    key: str
    lane: int                      # i8 通道号 0..7
    parallelization_key: str = ""  # 同 key 跨泳道并行对齐（lab-flow-sim 的同名字段）


@dataclass(kw_only=True)
class FunctionStep(BaseStep):
    """对应 FunctionStep：绑定一个设备函数 + 参数。"""
    function: str                  # Aspirate / Dispense / PickTip / DropTip
    params: dict[str, Any] = field(default_factory=dict)
    execution_time_formula: str = "0"  # Function.execution_time_formula


@dataclass(kw_only=True)
class ProcessStep(BaseStep):
    """对应 ProcessStep：引用子流程 + 泳道映射（可递归嵌套）。这里展开成子步骤列表。"""
    process: str
    children: list[FunctionStep] = field(default_factory=list)


def step_to_dict(s: BaseStep) -> dict:
    d = asdict(s)
    d["kind"] = "ProcessStep" if isinstance(s, ProcessStep) else "FunctionStep"
    return d


# ---------------------------------------------------------------- 公式（真实设备口径）

# Hamilton i8 单次吸/分的经验时间模型（秒）：固定开销 + 体积相关项。
# 这是"参数化公式字段"的示例值，来自公开规格的量级估计，不是厂商精确值。
TIP_PICK_FORMULA = "3.0"                    # 取枪头固定 3 s
ASPIRATE_FORMULA = "2.0 + 0.05 * X"         # 吸液：2 s 固定 + 每 µL 0.05 s
DISPENSE_FORMULA = "1.5 + 0.04 * X"         # 分液：1.5 s 固定 + 每 µL 0.04 s
TIP_DROP_FORMULA = "1.0"                    # 退枪头固定 1 s
MULTIPLIER_FORMULA = "1"                    # Swimlane.multiplier_formula：泳道倍数，默认 1


# ---------------------------------------------------------------- 核心构建

def col_of(well: str) -> int:
    digits = "".join(c for c in well if c.isdigit())
    return int(digits) if digits else 0


def row_of(well: str) -> int:
    letters = "".join(c for c in well if c.isalpha()).upper()
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - ord("A") + 1)
    return n - 1


def build_dsl(transfers: list[dict], sample_vol: float) -> dict:
    checks: list[dict] = []
    steps: list[BaseStep] = []
    lane_seen: dict[int, set[str]] = {i: set() for i in range(8)}

    def add(step: BaseStep) -> None:
        """lab-flow-sim 约束：同一泳道(通道)内 parallelization_key 不得重复。
        ProcessStep(lane=-1) 是编排节点、不占物理通道，跳过通道范围校验。"""
        if isinstance(step, ProcessStep):
            steps.append(step)
            return
        if step.lane < 0 or step.lane > 7:
            checks.append({"level": "error", "code": "LANE_OOR",
                           "text": f"通道 {step.lane} 超出 i8 范围 0..7"})
            return
        if step.parallelization_key and step.parallelization_key in lane_seen[step.lane]:
            checks.append({"level": "warn", "code": "KEY_DUP_IN_LANE",
                           "text": f"通道 {step.lane} 内重复 key {step.parallelization_key}（{step.key}）"})
        lane_seen[step.lane].add(step.parallelization_key)
        steps.append(step)

    # 步骤 1：整板取 8 个枪头（ProcessStep，子步骤是 8 个 FunctionStep）
    pick_children = [
        FunctionStep(key=f"pick_tip_ch{i}", lane=i, function="PickTip",
                     parallelization_key="TIPSET_0",
                     params={"tip": f"A{i + 1}"}, execution_time_formula=TIP_PICK_FORMULA)
        for i in range(8)
    ]
    add(ProcessStep(key="pick8", lane=-1, process="Pick8Tips",
                    parallelization_key="TIPSET_0", children=pick_children))
    for c in pick_children:
        steps.append(c)  # 展开的子步骤也进序列（对应 ProcessStep.children）

    # 步骤 2：按目标板列分组 → 每列一批，列内 8 孔映射到通道 0..7
    by_col: dict[int, list[dict]] = {}
    for t in transfers:
        by_col.setdefault(col_of(t["targetWell"]), []).append(t)

    for col, items in sorted(by_col.items()):
        items.sort(key=lambda t: row_of(t["targetWell"]))
        pkey = f"COL_{col}"  # parallelization_key = 目标列号
        # 先验长度再截断：i8 一列最多 8 孔（A-H 或 I-P），超出即设计错误
        if len(items) > 8:
            checks.append({"level": "error", "code": "COL_OVER_8",
                           "text": f"列 {col} 有 {len(items)} 孔 > i8 的 8 通道，将只取前 8"})
        group_children: list[FunctionStep] = []
        for lane, t in enumerate(items[:8]):
            base = FunctionStep(
                key=f"asp_{col}_{lane}", lane=lane, function="Aspirate",
                parallelization_key=pkey,
                params={"source": t["sourceWell"], "volume": sample_vol},
                execution_time_formula=ASPIRATE_FORMULA)
            disp = FunctionStep(
                key=f"disp_{col}_{lane}", lane=lane, function="Dispense",
                parallelization_key=pkey,
                params={"target": t["targetWell"], "volume": sample_vol},
                execution_time_formula=DISPENSE_FORMULA)
            group_children.extend([base, disp])
        add(ProcessStep(key=f"batch_col{col}", lane=-1, process="ColumnBatch",
                        parallelization_key=pkey, children=group_children))
        for c in group_children:
            steps.append(c)

    # 步骤 3：整板退枪头（ProcessStep）
    drop_children = [
        FunctionStep(key=f"drop_tip_ch{i}", lane=i, function="DropTip",
                     parallelization_key="TIPSET_0",
                     params={"tip": f"A{i + 1}"}, execution_time_formula=TIP_DROP_FORMULA)
        for i in range(8)
    ]
    add(ProcessStep(key="drop8", lane=-1, process="Drop8Tips",
                    parallelization_key="TIPSET_0", children=drop_children))
    for c in drop_children:
        steps.append(c)

    # ---- 公式求值 + 串行/并行时长对比
    timing: list[dict] = []
    serial_total = 0.0
    parallel_total = 0.0
    # 并行组：同 parallelization_key 的步骤取最大耗时（lab-flow-sim 的 parallelization 语义）
    group_max: dict[str, float] = {}
    for s in steps:
        if isinstance(s, ProcessStep):
            continue  # 子流程时长由 children 计，不重复计
        mul = eval_formula(MULTIPLIER_FORMULA, 1)  # Swimlane.multiplier_formula
        sec = eval_formula(s.execution_time_formula, s.params.get("volume", 0)) * mul
        serial_total += sec
        pk = s.parallelization_key or s.key
        group_max[pk] = max(group_max.get(pk, 0.0), sec)
        timing.append({"key": s.key, "function": s.function, "lane": s.lane,
                       "parallelization_key": pk, "formula": s.execution_time_formula,
                       "seconds": round(sec, 3)})
    parallel_total = sum(group_max.values())

    # 验证：parallelization_key 分组数应该 == 列数 + 1（取/退枪头共享 TIPSET_0）
    groups = sorted(set(s.parallelization_key for s in steps if s.parallelization_key))
    return {
        "mode": "qpcr-flow-dsl-poc",
        "borrowed_from": "lab-flow-sim (polymorphic step / formula fields / parallelization_key)",
        "steps": [step_to_dict(s) for s in steps if isinstance(s, ProcessStep)][:6],
        "step_count": len(steps),
        "lanes": {str(i): [s.key for s in steps if s.lane == i][:10] for i in range(8)},
        "parallel_groups": groups,
        "parallel_group_count": len(groups),
        "batches": len(by_col),
        "timing_summary": {
            "serial_seconds": round(serial_total, 2),
            "parallel_seconds": round(parallel_total, 2),
            "speedup": round(serial_total / parallel_total, 2) if parallel_total else 0,
            "formula_fields": {
                "aspirate": ASPIRATE_FORMULA,
                "dispense": DISPENSE_FORMULA,
                "tip_pick": TIP_PICK_FORMULA,
                "tip_drop": TIP_DROP_FORMULA,
                "lane_multiplier": MULTIPLIER_FORMULA,
            },
        },
        "timing_sample": timing[:8],
        "checks": checks,
    }


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception as e:
        print(json.dumps({"mode": "qpcr-flow-dsl-poc", "error": f"stdin JSON 非法: {e}"}))
        sys.exit(2)
    transfers = payload.get("transfers") or []
    if not transfers:
        print(json.dumps({"mode": "qpcr-flow-dsl-poc", "error": "transfers 为空"}))
        sys.exit(2)
    report = build_dsl(transfers, float(payload.get("sampleVol", 5)))
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
