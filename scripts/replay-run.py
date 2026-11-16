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

# 方法 → 客户语言（演示解说词 / 视频字幕直接用）
METHOD_CN = {
    "PickTipComb": "取磁套",
    "DropTipComb": "卸磁套",
    "Transfer": "机械臂搬运",
    "Run": "执行分液/洗涤protocol",
    "RunProtocol": "执行protocol",
    "Incubate": "孵育/计时",
    "Bind": "磁吸结合",
    "Wash": "洗涤",
    "Elute": "洗脱",
    "GetStatus": "读设备状态",
    "SetTemperature": "设温",
    "OpenDoor": "开门",
    "CloseDoor": "关门",
}

def method_cn(m):
    return METHOD_CN.get(m, m or "")


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

    # ---- 设备实例 id → 设备名（命令流水里只有数字 id，演示/视频必须说人话）
    #      注意 id 空间：命令里的 deviceId 是 **device_instance.id**，
    #      而 /api/devices/pool 给的是 resource_id（另一套 id）⇒ 用 registry。
    dev_by_id = {}
    try:
        reg = call("GET", "/api/devices/registry")
        for d in reg.get("items", []):
            dev_by_id[d.get("deviceId")] = {
                "code": d.get("code"), "name": d.get("name"),
                "capability": d.get("capability")}
    except Exception as e:
        print(f"  ! 设备名映射拉取失败（视频里将显示 id）：{e}")

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

    # ---- 相对时间（毫秒，从 run 开始算）—— 视频渲染要靠它把画面和真实时间对齐
    #
    # ⚠ **时区坑（2026-11-16 踩过）**：
    #   · run 的 startedAt 是 **UTC**（"2026-11-15T09:08:10.416490Z"）
    #   · 命令流水的时间戳是**库里存的本地时间**（naive，"2026-11-15 17:08:16.3652"）
    #   ⇒ 直接相减会差一个时区（本地是 UTC+8 ⇒ 差 8 小时 ⇒ 全部画到画布外，
    #     命令刻度整条看不见，而且**不报错**）。
    # ⇒ 这里**自校准**：拿「命令时间」和「节点相对 offsetMs」对齐，反推时差。
    #    不写死 +8 —— 部署环境时区可能不是 UTC+8。
    local_offset = None
    _pairs = []
    for c in commands:
        cs = c.get("sentAt")
        if not cs:
            continue
        try:
            s = dt.datetime.fromisoformat(str(cs).replace("Z", "+00:00"))
        except Exception:
            continue
        if s.tzinfo is not None:
            continue                      # 带时区的不用校准
        s_as_utc = s.replace(tzinfo=dt.timezone.utc)
        parent = next((n for n in top_nodes if n.get("id") == c.get("parentNodeId")), None)
        if parent and parent.get("offsetMs") is not None and t0d:
            _pairs.append((s_as_utc - t0d) - dt.timedelta(milliseconds=parent["offsetMs"]))
    if _pairs:
        _pairs.sort()
        local_offset = _pairs[len(_pairs) // 2]      # 中位数，抗个别异常
    else:
        local_offset = dt.datetime.now().astimezone().utcoffset() or dt.timedelta(0)
    if total_ms and local_offset:
        print(f"  · 时间戳时差自校准：{local_offset}（命令流水是本地时间，run 起止是 UTC）")

    def rel_ms(iso):
        """ISO/naive 时间 → 相对 run 起点的毫秒；解析不了返回 None。"""
        if not iso or not t0d:
            return None
        try:
            d = dt.datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
        except Exception:
            return None
        if d.tzinfo is None:
            d = d.replace(tzinfo=dt.timezone.utc) - local_offset
        return int((d - t0d).total_seconds() * 1000)

    # ---- 自检：换算出来的相对时间必须落在 run 的时间窗内
    #      （时区搞错时，相对时间会整体偏移一个时区 ⇒ 这里直接拦住）
    _rel = [rel_ms(c.get("sentAt")) for c in commands if c.get("sentAt")]
    _rel = [x for x in _rel if x is not None]
    if _rel and total_ms:
        _out = [x for x in _rel if x < -1000 or x > total_ms + 60000]
        if len(_out) > len(_rel) * 0.2:
            raise SystemExit(
                f"✘ 命令时间换算异常：{len(_out)}/{len(_rel)} 条落在 run 时间窗"
                f"（0..{total_ms}ms）之外 ⇒ 多半是时区/校准出了问题，不要拿这份数据出视频")

    # ---- 问题清单（**四类都要抓**，只扫节点状态会漏）
    #
    #   ① 节点失败 / 转人工 / 中止   —— 节点自己说了不算
    #   ② 命令失败                   —— 设备回了错
    #   ③ 命令卡在 running           —— 设备**没吭声**（等事件等到超时）
    #      ⚠ 实测踩过：run 1001668 全部节点 completed，
    #        但 `__awaitEvent` 一直 running（设备没上报 waiting_interaction），
    #        run 本身 failed ⇒ 只看节点状态会把这次失败整个漏掉。
    #   ④ run 本身不是 completed      —— 上面都没抓到时的兜底
    problems = []
    for n in top_nodes:
        if n.get("status") in ("failed", "manual", "aborted", "skipped"):
            problems.append({
                "kind": "node", "nodeKey": n.get("nodeKey"), "name": n.get("name"),
                "status": n.get("status"), "errorCode": n.get("errorCode"),
                "atMs": n.get("offsetMs") or 0,
                "say": f"环节「{n.get('name')}」状态 {n.get('status')}",
            })
    failed_cmds = [c for c in commands if c.get("status") in ("failed", "manual", "aborted")]
    for c in failed_cmds:
        problems.append({
            "kind": "command", "nodeKey": c.get("nodeKey"),
            "name": f"{c.get('method')}（设备 {c.get('deviceId')}）",
            "status": c.get("status"),
            "errorCode": (c.get("result") or "")[:120],
            "atMs": rel_ms(c.get("sentAt")) or 0,
            "say": f"设备命令 {c.get('method')} 返回 {c.get('status')}",
        })
    for c in commands:
        if c.get("status") == "running" and not c.get("finishedAt"):
            problems.append({
                "kind": "stuck", "nodeKey": c.get("nodeKey"),
                "name": f"{c.get('method')}（设备 {c.get('deviceId')}）",
                "status": "running",
                "errorCode": (c.get("result") or "")[:120],
                "atMs": rel_ms(c.get("sentAt")) or 0,
                "say": f"卡在 {c.get('method')}：设备一直没回话"
                        + ("（在等设备主动上报事件）" if c.get("method") == "__awaitEvent" else ""),
            })
    if run.get("status") != "completed" and not problems:
        problems.append({
            "kind": "run", "nodeKey": None, "name": f"运行状态 {run.get('status')}",
            "status": run.get("status"), "errorCode": run.get("errorCode"),
            "atMs": 0,
            "say": f"整批运行以 {run.get('status')} 结束",
        })

    # ---- 每台设备的"时间线"：命令区间 + 事件点
    #      ⇒ 视频里"此刻这台设备在干什么"可以离线重放（不需要新增任何存储）
    dev_tl = defaultdict(lambda: {"commands": [], "events": []})
    for c in commands:
        did = c.get("deviceId")
        if not did:
            continue
        s = rel_ms(c.get("sentAt"))
        e = rel_ms(c.get("finishedAt"))
        dev_tl[did]["commands"].append({
            "method": c.get("method"),
            "nodeKey": c.get("nodeKey"),
            "status": c.get("status"),
            "startMs": s if s is not None else 0,
            "endMs": e if e is not None else (s or 0),
            "errorCode": (c.get("result") or "")[:120],
        })
    # 把事件挂到设备时间线上（事件端点给的是设备 **code**，不是实例 id ⇒ 按 code 关联）
    # ⚠ 必须在 dev_list 构造**之前**挂：dev_list 里是对 dev_tl 的快照，晚挂就漏了。
    dev_id_by_code = {meta.get("code"): did
                      for did, meta in dev_by_id.items() if meta.get("code")}
    for ed, e in run_events:
        did = e.get("deviceId") or dev_id_by_code.get(e.get("device"))
        if did is None or did not in dev_tl:
            continue
        dev_tl[did]["events"].append({
            "atMs": rel_ms(e.get("createdAt")) or 0,
            "eventType": e.get("eventType"),
        })
    dev_list = []
    for did in sorted(dev_tl):
        meta = dev_by_id.get(did, {})
        dev_list.append({
            "deviceId": did,
            "code": meta.get("code") or f"device-{did}",
            "name": meta.get("name") or "",
            "capability": meta.get("capability") or "",
            "commands": sorted(dev_tl[did]["commands"], key=lambda x: x["startMs"]),
            "events": sorted(dev_tl[did]["events"], key=lambda x: x["atMs"]),
        })

    # ---- 每个节点一句"人话"（演示解说词 / 视频字幕直接用）
    def node_say(n, cs):
        """节点的一句话说明：谁 · 做什么 · 落到哪个方法。"""
        methods = [c.get("method") for c in cs if c.get("method")]
        name = n.get("name") or ""
        if not methods:
            return f"{name}（等待设备上报，不发命令）"
        parts = []
        for c in cs[:2]:
            dmeta = dev_by_id.get(c.get("deviceId"), {})
            who = dmeta.get("code") or f"设备{c.get('deviceId')}"
            parts.append(f"{who} 执行 {method_cn(c.get('method'))}")
        tail = f" 等 {len(cs)} 个动作" if len(cs) > 2 else ""
        return f"{name} ⇒ " + "、".join(parts) + tail

    def stage_gaps(ns, expect):
        """这一阶段里，客户 §3 要求发生、但这次**没对上**的事（= 还没建模完整的地方）。

        判据：客户期望的关键动作，要能在「本次跑过的方法名」或「本阶段节点名」里找到。
        """
        if not ns:
            return [d for d, _ in expect]
        hay = " ".join(
            [c.get("method") or "" for n in ns for c in cmd_by_parent.get(n.get("id"), [])]
            + [(n.get("name") or "") for n in ns])
        return [desc for desc, keys in expect if not any(k in hay for k in keys)]

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
        missing = stage_gaps(ns, expect)
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
        A("| 时刻 | 类型 | 问题 | 说明 |")
        A("|---|---|---|---|")
        KIND_CN = {"node": "环节失败", "command": "命令失败",
                   "stuck": "卡住不回话", "run": "整批中止"}
        for n in problems:
            A(f"| +{(n.get('atMs') or 0)/1000:.1f}s | {KIND_CN.get(n.get('kind'), n.get('kind'))} "
              f"| {n.get('name')} | {n.get('say') or ''} |")
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
        # ---- 阶段 → 节点（**这里必须用函数参数，不能引用外层的 ns**）
        #      2026-11-16 修：原来这里写的是 `for n in ns`，
        #      而 ns 是上面 .md 循环留下的**最后一个阶段**的节点
        #      ⇒ 8 个阶段全装上了同一批节点（机读剧本整个是错的）。
        #      现在显式取各阶段的节点，并**自检**：各阶段互不相交、并集 = 全部顶层节点。
        def build_stage(sid, title, expect):
            ns_ = by_stage.get(sid, [])
            return {
                "id": sid,
                "title": title,
                "expect": [{"desc": d, "keys": k} for d, k in expect],
                "gaps": stage_gaps(ns_, expect),
                "nodes": [
                    {
                        "nodeKey": n.get("nodeKey"),
                        "name": n.get("name"),
                        "status": n.get("status"),
                        "offsetMs": n.get("offsetMs") or 0,
                        "durationMs": n.get("durationMs") or 0,
                        "say": node_say(n, cmd_by_parent.get(n.get("id"), [])),
                        "commands": [
                            {
                                "method": c.get("method"),
                                "methodCn": method_cn(c.get("method")),
                                "deviceId": c.get("deviceId"),
                                "deviceCode": (dev_by_id.get(c.get("deviceId"), {}) or {}).get("code"),
                                "status": c.get("status"),
                                "sentAt": c.get("sentAt"),
                                "finishedAt": c.get("finishedAt"),
                                "errorCode": (c.get("result") or "")[:120],
                            }
                            for c in cmd_by_parent.get(n.get("id"), [])],
                    }
                    for n in ns_],
            }

        stages_json = [build_stage(sid, title, expect)
                       for sid, title, _, expect in STAGES if by_stage.get(sid)]

        # ---- 自检：阶段之间不重不漏（这个 bug 就是这么漏出去的）
        seen = set()
        dup = []
        for s in stages_json:
            for n in s["nodes"]:
                if n["nodeKey"] in seen:
                    dup.append(n["nodeKey"])
                seen.add(n["nodeKey"])
        if dup:
            raise SystemExit(
                "✘ 机读剧本自检失败：同一个节点落进了多个阶段 → "
                + ", ".join(sorted(set(dup))[:5]))
        missed = [n.get("nodeKey") for n in top_nodes if n.get("nodeKey") not in seen]
        if missed:
            raise SystemExit(
                "✘ 机读剧本自检失败：有节点没落进任何阶段 → "
                + ", ".join(str(x) for x in missed[:5]))

        payload = {
            "runId": run_id, "status": run.get("status"),
            "startedAt": started, "totalMs": total_ms,
            "peakConcurrency": peak, "deviceCount": len(devices),
            "nodeCount": len(top_nodes),
            "stages": stages_json,
            # 设备时间线：视频里"此刻这台设备在干什么"靠它离线重放
            "devices": dev_list,
            "events": [
                {"at": ed.isoformat(),
                 "atMs": rel_ms(e.get("createdAt")) or 0,
                 "device": e.get("device"),
                 "deviceId": e.get("deviceId"),
                 "eventType": e.get("eventType"),
                 "say": EVENT_CN.get(e.get("eventType"), "")}
                for ed, e in run_events],
            "problems": [
                {"kind": p.get("kind"), "nodeKey": p.get("nodeKey"),
                 "name": p.get("name"), "status": p.get("status"),
                 "errorCode": p.get("errorCode"), "atMs": p.get("atMs"),
                 "say": p.get("say")}
                for p in problems],
        }
        with open(out_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print(f"✓ 机读剧本已写：{out_json}"
              f"（阶段 {len(stages_json)} · 节点 {len(seen)} · 设备 {len(dev_list)}）")

    return 0


if __name__ == "__main__":
    sys.exit(main())
