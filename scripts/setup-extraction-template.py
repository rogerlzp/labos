#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取线的**最后一段**：建模板 → 实例化 → 配位置 → 核验 → 启动。

**为什么必须有这一段**（2026-11-17 从零建岛实测踩到）：
直接 `POST /api/workflows/{id}/run` 起跑 ⇒ 流程能跑，但**搬运节点没有板可搬** ⇒
第一段搬运就报 `EC-POSITION-OCCUPIED`（源位无板）⇒ 节点转人工、run 暂停。
原因：**板在哪个槽位**是实验级的事（manifest_placement），不经实验模板就没有 ⇒
新岛的点位账本全是空的。
（老岛能跑是因为栈里还留着上一轮跑剩的板 —— **靠残留兜底，不是靠配置**。）

所以正确的顺序是：
    岛 → 驱动 → 设备 → 点位 → 方法授权 → 流程(+绑定) → 变量 → **模板** → 实例化
    → 配位置 → 核验 → 启动

用法（**设备编号用现场编号 ⇒ DEV_TAG 留空**；只有现场编号已被别的岛占用时才加标记）：
    LABOS_ISLAND_CODE=LAB-EXT-02 LABOS_DEV_TAG= \\
    LABOS_WF_CODE=EXT-WORKFLOW-02 \\
    python3 scripts/setup-extraction-template.py

⚠ 幂等：模板按名字找、实例化每次新建一个实验批次。
"""

import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import ISLAND_CODE, stack_code  # noqa: E402

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-01")
TPL_NAME = os.environ.get("LABOS_TPL_NAME", "提取线 · 单批标准模板")
BATCH_NAME = os.environ.get("LABOS_BATCH_NAME", "第 1 批")

TOKEN = None


def call(method, path, body=None, query=None):
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"error": raw[:300]}


def ok(msg):
    print(f"  ✔ {msg}")


def bad(msg, detail=""):
    print(f"  ✘ {msg}")
    if detail:
        print(f"      {detail}")


def section(t):
    print()
    print(f"── {t} ──")


def main():
    print("=" * 68)
    print(f"{ISLAND_CODE} · 模板 → 实例化 → 配位置 → 核验 → 启动")
    print("=" * 68)
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    global TOKEN
    TOKEN = j["token"]

    # ---- 岛与流程
    section("1. 取岛与流程")
    st, topo = call("GET", "/api/topology")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}")
    island_id = island["id"]
    st, lst = call("GET", "/api/workflows", query={"limit": "300", "q": WF_CODE})
    wf = next((w for w in lst.get("items", []) if w.get("code") == WF_CODE), None)
    if wf is None:
        sys.exit(f"✘ 找不到流程 {WF_CODE}")
    wf_id = wf["id"]
    ok(f"岛 {ISLAND_CODE}（id={island_id}）· 流程 {WF_CODE}（id={wf_id}）")

    # ---- 磁套槽（按标签 MAGNET 推导，流程图用它取磁套板）
    st, pos = call("GET", "/api/positions", query={"islandId": island_id})
    magnet = [p for p in (pos.get("items") or [])
              if (p.get("positionLabel") or "").upper() == "MAGNET"]
    magnet.sort(key=lambda p: p.get("code") or "")
    if len(magnet) < 4:
        sys.exit(f"✘ 耗材栈 MAGNET 槽只有 {len(magnet)} 个，不够 4 台提取仪各一块")
    slots = magnet[:4]
    ok(f"磁套槽 4 个：{', '.join(s['code'] for s in slots)}")

    # ---- 物料清单引用的板：**按槽位实际在位的板**取，不能写死 id。
    # ⚠ 2026-11-18 实测踩到：原来写死 1000266~1000269（老岛 LAB-EXT-01 的板），
    #   老岛删掉后这 4 个 id 已不存在 ⇒ 模板引用空板 ⇒ 搬运节点找不到板
    #   ⇒ 现场表现是"模板自检 ok、位置也核验通过，起跑后搬运才炸"。
    mag_plates = []
    for s in slots:
        # ⚠ 位置接口里 plateId 是**条码字符串**、plateUid 才是数字 id
        #   （2026-11-18 实测踩到：直接 int(plateId) 报 ValueError）
        pid = s.get("plateUid")
        if pid is None:
            pid = s.get("plateId")
        try:
            pid = int(pid)
        except (TypeError, ValueError):
            sys.exit(f"✘ 磁套槽 {s['code']} 上没有板（plateUid={s.get('plateUid')}）"
                     f"⇒ 先放板（磁套板）再起跑")
        mag_plates.append(pid)
    ok(f"磁套板 4 块（按槽位实取）：{mag_plates}")

    # ---- 模板
    section("2. 建模板（物料清单 = 4 块磁套板）")
    st, lst = call("GET", "/api/experiment-templates", query={"limit": "100"})
    tpl = next((t for t in (lst.get("items") or []) if t.get("name") == TPL_NAME), None)
    if tpl is None:
        st, j = call("POST", "/api/experiment-templates", {
            "name": TPL_NAME, "islandId": island_id,
            # ⚠ workflowIds 必须是**对象数组** [{workflowId: id}]：
            #   后端读的是节点里的 "workflowId" 字段（insertTemplateProcess 用
            #   longOf(w, "workflowId")）⇒ 传数字数组会取到 0 ⇒ 外键失败 ⇒
            #   异常被笼统报成「templateNo 可能重复」（而模板行其实已经插进去了）。
            "workflowIds": [{"workflowId": wf_id}],
            "manifestItems": [
                {"roleCode": "magPlate", "materialKind": "plate",
                 "materialRef": pid, "quantity": 1, "required": True}
                for pid in mag_plates
            ],
            "pointWhitelist": [],
        })
        if st != 200:
            sys.exit(f"✘ 建模板失败（{st}）：{j}")
        tpl_id = j.get("id") or (j.get("template") or {}).get("id")
        ok(f"模板已建：{TPL_NAME}（id={tpl_id}）")
    else:
        tpl_id = tpl.get("id")
        ok(f"模板已存在：{TPL_NAME}（id={tpl_id}）")
        # 已有模板的物料清单可能还指着**已删的板**（老岛那批 id）
        # ⇒ 物料自检会报错 ⇒ 这里直接重建一份，别拿旧的凑合。
        st, chk0 = call("GET", f"/api/experiment-templates/{tpl_id}/check")
        issues = chk0.get("issues") or []
        if any("板" in str(i) or "material" in str(i).lower() for i in issues):
            st, j = call("DELETE", f"/api/experiment-templates/{tpl_id}")
            if st != 200:
                sys.exit(f"✘ 旧模板（物料指向已删的板）删不掉（{st}）：{j}")
            st, j = call("POST", "/api/experiment-templates", {
                "name": TPL_NAME, "islandId": island_id,
                "workflowIds": [{"workflowId": wf_id}],
                "manifestItems": [
                    {"roleCode": "magPlate", "materialKind": "plate",
                     "materialRef": pid, "quantity": 1, "required": True}
                    for pid in mag_plates
                ],
                "pointWhitelist": [],
            })
            if st != 200:
                sys.exit(f"✘ 重建模板失败（{st}）：{j}")
            tpl_id = j.get("id") or (j.get("template") or {}).get("id")
            ok(f"旧模板物料指向已删的板 ⇒ 已重建：id={tpl_id}")
    st, chk = call("GET", f"/api/experiment-templates/{tpl_id}/check")
    ok(f"模板自检：ok={chk.get('ok')} errors={len(chk.get('issues') or [])} 条 issue")

    # ---- 实例化
    section("3. 实例化（建实验批次 + 物料配置行）")
    st, j = call("POST", f"/api/experiment-templates/{tpl_id}/instantiate",
                 {"name": BATCH_NAME, "experimentNo": "", "spec": {}})
    if st != 200:
        sys.exit(f"✘ 实例化失败（{st}）：{j}")
    run_id = j.get("runId")
    ok(f"实验已建：experimentId={j.get('experimentId')} runId={run_id}")

    # ---- 配位置 + 核验
    section("4. 配位置（4 块磁套板 → 4 个磁套槽）")
    st, man = call("GET", f"/api/experiment-runs/{run_id}/manifest")
    placements = man.get("items") or []
    if len(placements) < 4:
        bad(f"物料配置行只有 {len(placements)} 行，不够 4 块磁套板")
        sys.exit(1)
    for pl, slot in zip(placements, slots):
        st, j = call("PUT",
                     f"/api/experiment-runs/{run_id}/placements/{pl['id']}",
                     {"positionId": slot.get("pointId")})
        if st != 200:
            bad(f"配位置失败（{st}）：{j}")
            continue
        ok(f"placement {pl['id']}（{pl.get('roleCode')} #{pl.get('materialRef')}）→ {slot.get('code')}")
        st, j = call("POST",
                     f"/api/experiment-runs/{run_id}/placements/{pl['id']}/verify")
        if st != 200:
            bad(f"核验失败（{st}）：{j}")
        else:
            ok(f"placement {pl['id']} 已核验")

    # ---- 就绪 + 启动
    section("5. 就绪检查 + 启动")
    st, rd = call("GET", f"/api/experiment-runs/{run_id}/readiness")
    ok(f"readiness：ready={rd.get('ready')} checks={rd.get('checks')}")
    if not rd.get("ready"):
        bad("还没就绪，不启动", json.dumps(rd.get("reasons"), ensure_ascii=False))
        sys.exit(1)
    st, j = call("POST", f"/api/experiment-runs/{run_id}/start")
    if st != 200:
        bad(f"启动失败（{st}）", str(j)[:300])
        sys.exit(1)
    ok(f"已启动：{json.dumps(j, ensure_ascii=False)}")
    print()
    print(f"盯盘：GET /api/runs/{run_id} ｜ 页面：/runs/{run_id}")


if __name__ == "__main__":
    main()
