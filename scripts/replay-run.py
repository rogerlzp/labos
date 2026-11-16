#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把一次真实运行**回放**成"演示剧本"：每个环节在做什么、出了什么问题。

**为什么要有这个**（2026-11-16）：
客户演示要的不是"功能列表"，而是**让人看懂一次完整的实验是怎么跑起来的**。
而"每个环节在做什么 / 什么时候该看哪里 / 出问题怎么处理"这些，
**本来就是结构化数据**，不该靠人写：

    · `/api/runs/{id}/timeline`   ⇒ 每个环节（节点）**相对时间 + 耗时**（画布上的"骨架"）
    · `/api/runs/{id}/commands`   ⇒ **谁**在什么时候**做了什么方法**、成没成（设备侧的动作流水）
    · `/api/device-events`         ⇒ 设备**主动上报**了什么、什么时候（事件流水）

⇒ 三份流水合起来就能**离线重放**出任意时刻每台设备在干什么
   （**不需要新增任何存储** —— 业务状态是读时派生的，但"历史"可以从流水重算）。

**产出两份**：
    · Markdown 剧本（给人看：现场演示的讲解词 + 视频的镜头提示）
    · JSON（给机器看：后续渲染动态演示视频直接吃它）

用法：
    LABOS_API=http://127.0.0.1:18084 python3 scripts/replay-run.py <runId> \
        [--out 剧本.md] [--json 剧本.json]
"""

import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
TOKEN = None


def call(method, path, query=None):
    url = API + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    req = urllib.request.Request(url, method=method)
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode("utf-8")
            return json.loads(t) if t.strip() else {}
    except urllib.error.HTTPError as e:
        t = e.read().decode("utf-8", "replace")
        raise SystemExit(f"✘ 调 {path} 失败（{e.code}）：{t[:300]}")


# ---------------------------------------------------------------- 客户 §3 的阶段口径
#
# 每个阶段三件事：
#   · `title`  客户文档里这个阶段的原话（演示时用客户的语言，不说 node_key）
#   · `match`   本次 run 里属于这个阶段的**节点名正则**
#   · `expect`  客户文档 §3 要求这个阶段**应该发生**的事
#                ⇒ 与实际跑到的对照，就得出「这一段还缺什么」（演示时的诚实说明）
STAGES = [
    ("阶段1", "磁套加载（4 台提取仪依次上磁套）",
     [r"取磁套$", r"^等 .* 等待交互$", r"机械臂 放磁套板", r"卸磁套$"],
     [("4 台提取仪依次取磁套", ["PickTipComb"]),
      ("机械臂把磁套板放进交互位", ["放磁套板", "Transfer"]),
      ("提取仪卸磁套、磁套进入操作位", ["卸磁套", "DropTipComb"])]),
    ("阶段2", "裂解液+蛋白酶K 分液、上裂解（DISP1）",
     [r"^DISP1 "],
     [("DISP1 裂解液+蛋白酶K 分液", ["Run"]),
      ("4 台提取仪取裂解板", ["取裂解板"]),
      ("机械臂送裂解板进交互位", ["送裂解板", "裂解板"]),
      ("提取仪转盘切换、上裂解计时", ["Incubate", "转盘切换"])]),
    ("阶段3", "磁珠分装（DISP2，配套顶置搅拌）",
     [r"^DISP2 "],
     [("DISP2 磁珠分装", ["Run"]),
      ("磁珠板暂存", ["磁珠板"]),
      ("裂解完成后送入交互位", ["送磁珠板", "磁吸"]),
      ("转盘切换、磁吸", ["Bind", "磁吸"])]),
    ("阶段4", "裂解完成，磁吸 + W1 洗涤分液（DISP3，磁力搅拌）",
     [r"^DISP3 "],
     [("取出裂解板回耗材栈", ["取裂解板"]),
      ("DISP3 W1 分液", ["Run"]),
      ("W1 板送入交互位", ["W1板", "送 W1"]),
      ("转盘切换、W1 洗涤", ["Wash", "W1 洗涤"])]),
    ("阶段5", "W2 洗涤分液（DISP4）",
     [r"^DISP4 "],
     [("取出 W1 板回耗材栈", ["取 W1"]),
      ("DISP4 W2 分液", ["Run"]),
      ("W2 板送入交互位", ["W2板", "送 W2"]),
      ("转盘切换、W2 洗涤", ["Wash", "W2 洗涤"])]),
    ("阶段6", "W3 洗涤分液（DISP5）",
     [r"^DISP5 "],
     [("取出 W2 板回耗材栈", ["取 W2"]),
      ("DISP5 W3 分液", ["Run"]),
      ("W3 板送入交互位", ["W3板", "送 W3"]),
      ("转盘切换、W3 洗涤", ["Wash", "W3 洗涤"])]),
    ("阶段7", "洗脱液分液（DISP6）",
     [r"^DISP6 "],
     [("取出 W3 板回耗材栈", ["取 W3"]),
      ("DISP6 洗脱液分液", ["Run"]),
      ("洗脱板送入交互位", ["洗脱板", "送洗脱"]),
      ("转盘切换、洗脱", ["Elute", "洗脱"])]),
    ("阶段8", "批次收尾、板回栈",
     [r"洗脱$", r"等待交互（收尾）$", r"机械臂 从 .* 取产物板", r"收尾：读状态"],
     [("洗脱工序完成、上报等待交互", ["Elute", "洗脱"]),
      ("机械臂取板、区分废弃板与产物板", ["取产物板"]),
      ("板回栈、更新板状态", ["回栈"]),
      ("本批 6 块板全部流转完成", ["收尾"])]),
]

# 事件 → 客户语言（演示时说得出来的话）
EVENT_CN = {
    "waiting_interaction": "设备**主动上报【等待交互】**（停下来等人）",
    "process_done": "设备上报【流程完成】",
    "flow_completed": "整套流程结束",
    "plate_ready": "板已就位",
    "plate_removed": "板已取走",
    "stirrer_fault": "搅拌故障",
    "fault": "设备故障",
}


def stage_of(name):
    for sid, title, pats, _ in STAGES:
        for p in pats:
            if re.search(p, name or ""):
                return sid, title
    return None, None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    run_id = sys.argv[1]
    out_md = None
    out_json = None
    for i, a in enumerate(sys.argv):
        if a == "--out" and i + 1 < len(sys.argv):
            out_md = sys.argv[i + 1]
        if a == "--json" and i + 1 < len(sys.argv):
            out_json = sys.argv[i + 1]

    global TOKEN
    # 登录（call 只做 GET，登录用 POST ⇒ 单独写）
    req = urllib.request.Request(
        API + "/api/auth/login", method="POST",
        data=json.dumps({"username": USER, "password": PASSWORD}).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        TOKEN = json.loads(r.read().decode("utf-8"))["token"]

    print(f"▶ 拉取 run {run_id} 的三份流水 …")
    detail = call("GET", f"/api/runs/{run_id}")
    tl = call("GET", f"/api/runs/{run_id}/timeline")
    cmds = call("GET", f"/api/runs/{run_id}/commands")
    # 事件按**时间窗**拉（否则只能拿到"最新 N 条"，旧 run 的事件取不到）
    evs = {"items": []}
    try:
        tl_probe = call("GET", f"/api/runs/{run_id}/timeline")
        s0 = tl_probe.get("startedAt")
        t0d0 = dt.datetime.fromisoformat(s0.replace("Z", "+00:00"))
        t1d0 = t0d0 + dt.timedelta(milliseconds=(tl_probe.get("totalMs") or 0))
        evs = call("GET", "/api/device-events", {
            "since": t0d0.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "until": t1d0.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "limit": 500})
    except Exception as e:
        print(f"  ! 事件按时间窗拉取失败（退回全量）：{e}")
        evs = call("GET", "/api/device-events", {"limit": 500})
    run = detail.get("run", {})
    nodes = tl.get("items", [])
    commands = cmds.get("commands", [])
    events = evs.get("items", [])

    started = tl.get("startedAt")
    total_ms = tl.get("totalMs") or 0

    # ---- 节点 → 阶段
    top_nodes = [n for n in nodes if (n.get("level") or 0) == 0]
    by_stage = defaultdict(list)
    for n in top_nodes:
        sid, title = stage_of(n.get("name", ""))
        by_stage[sid or "其它"].append(n)

    # ---- 命令按父节点归组
    cmd_by_parent = defaultdict(list)
    for c in commands:
        cmd_by_parent[c.get("parentNodeId")].append(c)

    # ---- 事件按时间落窗（run 起止）
    t0 = started
    t1 = None
    try:
        t0d = dt.datetime.fromisoformat(started.replace("Z", "+00:00"))
        t1d = t0d + dt.timedelta(milliseconds=total_ms)
    except Exception:
        t0d = t1d = None
    run_events = []
    for e in events:
        ca = e.get("createdAt")
        if not ca or not t0d:
            continue
        try:
            ed = dt.datetime.fromisoformat(ca.replace("Z", "+00:00"))
        except Exception:
            continue
        if t0d - dt.timedelta(seconds=5) <= ed <= (t1d or ed) + dt.timedelta(seconds=5):
            run_events.append((ed, e))
    run_events.sort(key=lambda x: x[0])

    # ---- 问题清单（失败 / 转人工 / 中止）
    problems = []
    for n in top_nodes:
        if n.get("status") in ("failed", "manual", "aborted", "skipped"):
            problems.append(n)
    failed_cmds = [c for c in commands if c.get("status") in ("failed", "manual", "aborted")]

    # ---- 并发特征：每个时刻有几台设备同时在跑
    spans = []
    for c in commands:
        if not c.get("sentAt"):
            continue
        try:
            s = dt.datetime.fromisoformat(c["sentAt"].replace("Z", "+00:00"))
            e = dt.datetime.fromisoformat(c["finishedAt"].replace("Z", "+00:00")) if c.get("finishedAt") else s
        except Exception:
            continue
        spans.append((s, e, c.get("method")))
    peak = 0
    peak_at = None
    for t, _, _ in spans:
        n = sum(1 for s, e, _ in spans if s <= t < e)
        if n > peak:
            peak, peak_at = n, t
    devices = sorted({c.get("deviceId") for c in commands if c.get("deviceId")})

    # ---------------------------------------------------------------- 输出
    md = []
    A = md.append
    A(f"# 演示回放 · run {run_id}")
    A("")
    A(f"- 状态：**{run.get('status')}**　节点 {len(top_nodes)} 个　设备 {len(devices)} 台")
    A(f"- 总时长：**{total_ms/1000:.1f} 秒**"
      + (f"　并发峰值：**{peak} 台设备同时在动**" + (f"（{peak_at.strftime('%H:%M:%S')}）" if peak_at else "")
         if peak else ""))
    A(f"- 命令 {len(commands)} 条　设备事件 {len(run_events)} 条　"
      f"问题节点 {len(problems)} 个")
    A("")
    A("> 这份剧本是**从一次真实运行的流水自动生成**的（命令 + 事件 + 节点时间轴），"
      "不是手写的 ⇒ 每一句都能对回系统里的真实记录。")
    A("")
    A("---")
    A("")

    # ---- 逐阶段
    A("## 一、阶段剧本（每个环节在做什么）")
    A("")
    for sid, title, pats, expect in STAGES:
        ns = by_stage.get(sid, [])
        if not ns:
            A(f"### {sid} · {title}")
            A("")
            A("> ⚠ **本次运行没有跑到这一段**（流程图里还没有这些节点）")
            A("")
            continue
        A(f"### {sid} · {title}")
        A("")
        done_kinds = set()
        for n in ns:
            cs = cmd_by_parent.get(n.get("id"), [])
            methods = "、".join(f"{c.get('method')}" for c in cs) or "（无设备命令）"
            for c in cs:
                done_kinds.add(c.get("method"))
            off = n.get("offsetMs") or 0
            dur = n.get("durationMs") or 0
            A(f"- **{n.get('name')}**　`{n.get('nodeKey')}`"
              f"　+{off/1000:.1f}s　耗时 {dur/1000:.1f}s　→ {methods}"
              f"　`{n.get('status')}`")
        # 缺口：客户期望的动作里，这次**没有对应关键词**的
        # （关键词同时看：跑过的方法名 + 本阶段的节点名）
        missing = []
        for desc, keys in expect:
            hay = " ".join([m or "" for m in done_kinds]
                           + [(n.get("name") or "") for n in ns])
            if not any(k in hay for k in keys):
                missing.append(desc)
        if missing:
            A("")
            A("> ⚠ **这一段还没建模完整**（客户 §3 要求 vs 本次实际跑到）：")
            for m in missing:
                A(f"> - {m}")
        A("")

    # ---- 设备事件
    A("## 二、设备主动上报了什么（分段自驱的证据）")
    A("")
    if not run_events:
        A("> 本次运行没有设备事件。")
    else:
        A("| 时刻 | 设备 | 事件 | 意思 |")
        A("|---|---|---|---|")
        for ed, e in run_events:
            A(f"| {ed.strftime('%H:%M:%S')} | {e.get('device')} | `{e.get('eventType')}` | "
              f"{EVENT_CN.get(e.get('eventType'), '')} |")
    A("")

    # ---- 问题处理
    A("## 三、出了问题怎么处理")
    A("")
    if not problems and not failed_cmds:
        A("> 本次运行**没有失败/转人工/中止** —— 全部节点 completed。")
        A(">")
        A("> ⚠ 这本身也是要讲清的一点：**顺利跑通不代表流程完备**，"
          "现场真正会遇到的是下面这些（演示第三幕要主动演出来）：")
        A(">")
        A("> | 故障 | 系统怎么反应 | 演示怎么演 |")
        A("> |---|---|---|")
        A("> | 设备命令失败 | 自动重试 → 仍失败则**节点转人工、流程停下等人** | "
          "模拟器 `--fail-method=Elute --fail-code=EC-05-1007` |")
        A("> | 设备掉线 | 在线态转红 ⇒ 调度把它排除 | 模拟器 `--offline` |")
        A("> | 搅拌故障 | **对应分液器禁止启动分液**（§1.6 互锁④） | 改搅拌单元状态 |")
        A("> | 耗材不足 | 弹【耗材不足，请补料】，**在跑的批次继续跑完**（§3 阶段9-2） | 减少耗材栈可用板 |")
    else:
        A("| 时刻 | 环节 | 问题 | 状态 |")
        A("|---|---|---|---|")
        for n in problems:
            A(f"| +{(n.get('offsetMs') or 0)/1000:.1f}s | {n.get('name')} | "
              f"`{n.get('errorCode') or '—'}` | {n.get('status')} |")
        A("")
        for c in failed_cmds:
            A(f"- 失败命令：`{c.get('method')}` → `{c.get('errorCode') or '—'}`"
              f"　({c.get('sentAt')})")
    A("")

    # ---- 讲解词
    A("## 四、讲解词（现场照着讲 / 视频配音）")
    A("")
    A("| 幕 | 屏幕上发生什么 | 这一幕要让人看懂什么 |")
    A("|---|---|---|")
    for sid, title, _, _ in STAGES:
        ns = by_stage.get(sid, [])
        if not ns:
            continue
        A(f"| {sid} | {ns[0].get('name')} 等 {len(ns)} 个环节 | {title} |")
    A("")
    A("> **核心的一句**：注意这里**不是上位机在轮询等它** —— 是**设备自己停下来喊了一声**，"
      "我们只是接住了这一声。")
    A("")

    md_text = "\n".join(md)

    if out_md:
        with open(out_md, "w", encoding="utf-8") as f:
            f.write(md_text)
        print(f"✓ 剧本已写：{out_md}")
    else:
        print(md_text)

    if out_json:
        payload = {
            "runId": run_id, "status": run.get("status"),
            "startedAt": started, "totalMs": total_ms,
            "peakConcurrency": peak, "devices": len(devices),
            "stages": [
                {
                    "id": sid, "title": title,
                    "expect": expect,
                    "nodes": [
                        {"nodeKey": n.get("nodeKey"), "name": n.get("name"),
                         "status": n.get("status"),
                         "offsetMs": n.get("offsetMs"), "durationMs": n.get("durationMs"),
                         "commands": [
                             {"method": c.get("method"), "deviceId": c.get("deviceId"),
                              "status": c.get("status"), "sentAt": c.get("sentAt"),
                              "finishedAt": c.get("finishedAt"),
                              "errorCode": (c.get("result") or "")[:120]}
                             for c in cmd_by_parent.get(n.get("id"), [])]}
                        for n in ns],
                }
                for sid, title, _, expect in STAGES if by_stage.get(sid)
            ],
            "events": [
                {"at": ed.isoformat(), "device": e.get("device"),
                 "eventType": e.get("eventType")}
                for ed, e in run_events],
            "problems": [
                {"nodeKey": n.get("nodeKey"), "name": n.get("name"),
                 "status": n.get("status"), "errorCode": n.get("errorCode")}
                for n in problems],
        }
        with open(out_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print(f"✓ 机读剧本已写：{out_json}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
