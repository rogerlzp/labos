#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""pre-push 选段：按「这次推了什么」决定跑哪些门禁。

为什么要有这个：.githooks/pre-push 原来无条件跑 ./scripts/ci-gate.sh --all
（40 分钟级；设备段还要 30–45 秒/动作的仿真机），等于把"发布前全量"降级成
"每次 push"。这里按改动路径选档：

  只有文档          → 只跑 verify-en-docs.py（秒级）
  任何代码          → T0 恒跑（ci-gate.sh 无参：阈值自检/纯逻辑/tsc/schema）
  相关段 T1        → 按 §4 路径映射命中即**默认自动跑**（可用 --no-t1 / LABOS_GATE_T1=0 关）

原则（2026-11-09）：**只测改动面及相关部分，不跑全量**。
`ci-gate.sh --all` 不作为任何 push、任何工作项的验收条件；它只是可选的跨轨复核手段。
单一真源仍是 docs/测试总纲.md §4；本脚本是它的可执行版（阶段 1 换成 test-map.yaml）。

用法：
  python3 scripts/gate-select.py                    # 只打印将跑什么
  python3 scripts/gate-select.py --run              # 实际执行（pre-push 用）
  python3 scripts/gate-select.py --run --no-t1      # 只跑 T0，跳过相关段
  python3 scripts/gate-select.py --paths a b        # 用给定路径测试选择逻辑
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

DOC_RE = "docs/"


def is_doc(p: str) -> bool:
    if p.startswith(DOC_RE):
        return True
    if "/" in p:
        # 根目录的说明文档 / 模板
        return p in {".github/PULL_REQUEST_TEMPLATE.md"} or p.startswith(".github/")
    return p.upper().endswith(".MD")


# 路径特征 → T1 段（顺序无关，命中即加；同一条命令只跑一次）
T1_RULES = [
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/",
     "python3 scripts/verify-flow-matrix.py --force-online"),
    ("labos-kernel/src/main/java/com/labos/kernel/engine/",
     "python3 scripts/verify-flow-matrix.py --force-online"),
    ("labos-kernel/src/main/java/com/labos/kernel/expr/",
     "python3 scripts/verify-flow-matrix.py --force-online"),
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/",
     "python3 scripts/e2e-flow-matrix.py"),
    ("labos-kernel/src/main/java/com/labos/kernel/driver/",
     "./scripts/verify-device-chain.sh"),
    ("labos-kernel/src/main/java/com/labos/kernel/device/",
     "./scripts/verify-device-chain.sh"),
    ("labos-kernel/src/main/java/com/labos/kernel/sim/",
     "./scripts/verify-device-chain.sh"),
    ("labos-kernel/src/main/java/com/labos/kernel/persistence/",
     "./scripts/check-schema-sync.sh"),
    ("pg_rebuild/schema/",
     "./scripts/check-schema-sync.sh"),
    ("sidecar-dotnet/",
     "./scripts/verify-device-chain.sh"),
    ("webapp/src/i18n/",
     "python3 scripts/scan-i18n.py"),
    ("scripts/e2e-pause",
     "python3 scripts/e2e-pause-resume-nodes.py"),
    ("scripts/e2e-pause",
     "python3 scripts/e2e-pause-during-experiment.py"),
    ("scripts/e2e-pause",
     "python3 scripts/e2e-interrupt-during-device.py"),
    ("labos-kernel/src/main/java/com/labos/kernel/api/",
     "python3 scripts/e2e-smoke.py"),
    ("webapp/src/pages/Alerts",
     "python3 scripts/e2e-alerts.py"),
    ("webapp/src/pages/Policies",
     "python3 scripts/e2e-policies.py"),
    ("webapp/src/pages/PausedRuns",
     "python3 scripts/e2e-paused.py"),
    ("webapp/src/pages/ExperimentCanvas",
     "python3 scripts/e2e-canvas.py"),
    ("webapp/src/pages/Designer",
     "./scripts/ci-gate.sh --designer"),
    ("webapp/src/bpmn/",
     "./scripts/ci-gate.sh --designer"),
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/PauseCoordinator",
     "./scripts/ci-gate.sh --pause"),
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/RunControl",
     "./scripts/ci-gate.sh --pause"),
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/StartupRecovery",
     "./scripts/ci-gate.sh --recovery"),
    ("labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowResumeRecovery",
     "./scripts/ci-gate.sh --recovery"),
    ("webapp/",
     "python3 scripts/e2e-smoke.py"),
]


def changed_paths(base: str | None) -> list[str]:
    if base is None:
        for cand in (["origin/main"], ["@{upstream}"], ["HEAD~1"]):
            try:
                base = subprocess.check_output(
                    ["git", "merge-base", "HEAD"] + cand,
                    text=True, stderr=subprocess.DEVNULL).strip()
                if base:
                    break
            except Exception:  # noqa: BLE001
                continue
    if not base:
        base = "HEAD~1"
    out = subprocess.check_output(
        ["git", "diff", "--name-only", base + "..HEAD"], text=True)
    return [line.strip() for line in out.splitlines() if line.strip()]


def select(paths: list[str]) -> tuple[list[str], list[str], list[str]]:
    """返回 (必跑命令, 相关段命令, 说明)。"""
    if not paths:
        return [], [], ["没有改动（或无法取 diff）→ 不跑"]
    docs = [p for p in paths if is_doc(p)]
    code = [p for p in paths if not is_doc(p)]
    if not code:
        return ["python3 scripts/verify-en-docs.py"], [], [f"{len(docs)} 个文档改动"]
    cmds = ["./scripts/ci-gate.sh"]
    t1 = []
    pause_only = code and all(
        ("PauseCoordinator" in p or "RunControl" in p or "api/ExperimentApi" in p)
        for p in code
    )
    recovery_only = code and all(
        ("StartupRecovery" in p or "WorkflowResumeRecovery" in p)
        for p in code
    )
    for needle, cmd in T1_RULES:
        if any(needle in p for p in code) and cmd not in t1:
            if cmd in {
                "python3 scripts/verify-flow-matrix.py --force-online",
                "python3 scripts/e2e-flow-matrix.py",
            } and (pause_only or recovery_only):
                continue
            t1.append(cmd)
    notes = [f"代码改动 {len(code)} 个 → T0 必跑"]
    if docs:
        notes.append(f"另有 {len(docs)} 个文档改动 → 加跑 verify-en-docs")
        cmds.append("python3 scripts/verify-en-docs.py")
    return cmds, t1, notes


def run(cmds: list[str], label: str) -> int:
    failed = 0
    for c in cmds:
        print(f"\n=== [{label}] {c}")
        rc = subprocess.call(c, shell=True)
        if rc != 0:
            failed += 1
            print(f"✗ 退出码 {rc}：{c}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=None, help="对比基准 ref（默认 merge-base origin/main）")
    ap.add_argument("--paths", nargs="*", default=None, help="直接给路径测试选择逻辑")
    ap.add_argument("--run", action="store_true", help="实际执行（默认只打印）")
    ap.add_argument("--t1", action="store_true", help="兼容旧参数：相关段现在默认就跑")
    ap.add_argument("--no-t1", action="store_true", help="跳过相关段（只跑 T0）")
    args = ap.parse_args()

    paths = args.paths if args.paths is not None else changed_paths(args.base)
    cmds, t1, notes = select(paths)
    if args.paths is None:
        print("本次改动：" + (", ".join(paths[:12]) + (" …" if len(paths) > 12 else "")
                             if paths else "（无）"))
    for n in notes:
        print("  · " + n)
    print("\n必跑：")
    for c in cmds or ["（无）"]:
        print("  " + c)
    if t1:
        print("\n相关段（按总纲 §4 映射表，默认自动跑）：")
        for c in t1:
            print("  " + c)
    else:
        print("\n相关段：本次改动未命中映射表，无需加跑")
    print()

    if not args.run:
        return 0
    rc = run(cmds, "必跑")
    if not args.no_t1 and os.environ.get("LABOS_GATE_T1") != "0":
        rc = run(t1, "相关段") or rc
    return rc


if __name__ == "__main__":
    sys.exit(main())
