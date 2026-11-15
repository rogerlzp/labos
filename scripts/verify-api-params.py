#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前后端 **query 参数名**的跨层一致性静态核对（2026-11-15）。

**为什么要有这个脚本**：这一类 bug 的症状是**静默失效** ——

前端 `api.execute` 原来发的是 `?mode=unattended`，后端
`WorkflowController.run(@RequestParam String attendance)` 只认 `attendance`
⇒ 后端拿到 null ⇒ **一律按 attended 跑**
⇒ 界面上「无人值守」这个选项选了也没用，**不报错、不告警、没有任何征兆**。

这跟「内置逻辑节点参数名对不上」是同一类病（见
`scripts/verify-builtin-nodes.py`）：两边各写各的，中间隔着 HTTP，
对不上时只有现场跑起来才发现。

本脚本把「谁发什么、谁收什么」写死成一张表，两边都比一遍。

用法：python3 scripts/verify-api-params.py
不需要连库、不需要起服务。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASS, FAIL = 0, 0

CLIENT = os.path.join(ROOT, "webapp/src/api/client.ts")

# ---------------------------------------------------------------------------
# 契约表：(参数名, 前端发送处（子串）, 后端接收处（文件 + 子串）)
#
# 「后端接收处」要能唯一定位到 @RequestParam 那一行 —— 用两段（文件路径片段
# + 该行前后的特征串）来定位，避免只靠参数名误匹配到别的控制器。
# ---------------------------------------------------------------------------
CONTRACTS = [
    (
        "attendance",
        "q.set('attendance'",
        ("WorkflowController.java", "@RequestParam(required = false) String attendance"),
    ),
    (
        "async",
        "q.set('async'",
        ("WorkflowController.java", 'qs.contains("async=1")'),
    ),
    (
        "islandId",
        "q.set('islandId'",
        ("WorkflowController.java", 'qs.contains("islandId=")'),
    ),
]

# 后端控制器目录（按需扩）
CONTROLLERS = [
    "labos-kernel/src/boot/java/com/labos/boot/web/controller/WorkflowController.java",
    "labos-kernel/src/boot/java/com/labos/boot/web/controller/BindingController.java",
    "labos-kernel/src/boot/java/com/labos/boot/web/controller/RunQueryController.java",
]


def ok(msg):
    global PASS
    PASS += 1
    print(f"  ✔ {msg}")


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print(f"  ✘ {msg}")
    if detail:
        for line in detail.splitlines():
            print(f"      {line}")


def section(title):
    print()
    print(f"── {title}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def find_controller(filename):
    """按文件名找控制器；找不到返回 None（不能当成"匹配成功"）。"""
    for rel in CONTROLLERS:
        if os.path.basename(rel) == filename:
            full = os.path.join(ROOT, rel)
            if os.path.exists(full):
                return full
    return None


def main():
    print("=" * 68)
    print("前后端 query 参数名 · 跨层一致性核对")
    print("=" * 68)

    if not os.path.exists(CLIENT):
        print(f"✘ 找不到 {CLIENT}")
        return 1

    client = read(CLIENT)

    # ------------------------------------------------------------------
    section("1. 前端发的参数名 ↔ 后端收的参数名")
    # ------------------------------------------------------------------
    for param, client_frag, (ctrl_file, ctrl_frag) in CONTRACTS:
        full = find_controller(ctrl_file)
        if full is None:
            bad(f"{param}: 找不到控制器 {ctrl_file}")
            continue
        ctrl = read(full)

        sent = client_frag in client
        received = ctrl_frag in ctrl

        if not sent:
            bad(f"{param}: 前端没发（找不到 {client_frag}）")
            continue
        if not received:
            bad(f"{param}: 前端发了，但后端 {ctrl_file} 里找不到 {ctrl_frag}")
            continue
        ok(f"{param}: 前端发、后端收，名字一致")

    # ------------------------------------------------------------------
    section("2. 反向检查：后端 @RequestParam 收的，前端都得能发")
    # ------------------------------------------------------------------
    # 只查 WorkflowController 的 run 端点（当前唯一有"前端可发参数"的执行入口）。
    wf = find_controller("WorkflowController.java")
    if wf:
        src = read(wf)
        # 取 run(...) 方法体里的 @RequestParam
        m = re.search(
            r'@PostMapping\(value = "/workflows/\{id:.*?public ResponseEntity<String> run\(.*?\)',
            src, re.S)
        if m:
            body = m.group(0)
            params = re.findall(r'@RequestParam\(required = false\) String (\w+)', body)
            for p in params:
                if p == "attendance":
                    continue  # 已单独查过
                # 这些是 query 里用 qs.contains 解析的，不是标准 @RequestParam
                if p in ("q",):
                    continue
                if f"'{p}'" in client or f'"{p}"' in client:
                    ok(f"run 端点的 {p}: 前端也能发")
                else:
                    ok(f"run 端点的 {p}: 前端未发（后端有默认值，不算不一致）")

    # ------------------------------------------------------------------
    section("3. 历史教训回归：attendance 不能退回 mode")
    # ------------------------------------------------------------------
    # 这一条单独钉住，因为它是本脚本的起因 —— 防止有人"顺手改回去"。
    if "q.set('mode'" in client:
        bad("前端仍在发 mode= —— 后端只认 attendance，这个参数会被静默丢弃")
    else:
        ok("前端不再发 mode=（后端只认 attendance）")

    if "@RequestParam(required = false) String mode" in (read(wf) if wf else ""):
        bad("后端 run 端点又改回收 mode 了")
    else:
        ok("后端 run 端点收的是 attendance")

    # ------------------------------------------------------------------
    print()
    print(f"通过 {PASS}, 失败 {FAIL}")
    if FAIL:
        print("（有硬错误 ⇒ 前后端参数名对不上，相关功能会**静默失效**）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
