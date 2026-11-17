#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给客户流程的**方法级时长模型**填预估值（duration_model）。

**为什么要这一步**：用户口径「各阶段时长先预估一个，可以在页面上修改」。
- `duration_model` 原来**只有 GET、没有写口** ⇒ 现场标定值进不去；
- 更关键：它**没有任何运行时消费方** ⇒ 改了也不影响执行
  （节点时长走 `workflow_node.estimate_sec` 与 `device_method.estimate_sec`）。
  ⇒ 本轮两件事都补了：① 加写端点 + 页面可改 ② 把这张表接进节点时长的计算
  （`GraphMapper.selectWorkflowNodes`：duration_model > device_method > 节点列）。

**预估值从哪来**：**`device_method.estimate_sec`** ——
那是设备型号登记时按**公开规格**填的（画像派生，见 `sim/DeviceProfile` 的 timing 表，
来源标注在 `DeviceModels` 的 Planned 条目里，如 KingFisher 协议 PDF 的
结合孵育 8 min / 洗涤 3 min / 风干 10 min）。
⇒ **不是拍脑袋的数字**，是"厂商公开规格 + 保守取最大值"。

**口径**：
- p50 = 型号的 estimate_sec（公开规格级中位估计）
- p90 = p50 × 1.5（保守：现场实测通常比公开规格慢）
- min = p50 × 0.5，max = p50 × 3（现场波动范围）
- sample_count = 0（**预估值，不是实测** —— 页面会显示样本数，别把它当统计）

用法：
    LABOS_API=http://127.0.0.1:18082 python3 scripts/seed-extraction-durations.py
"""

import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")

# 岛编号在共享配置层（scripts/extraction_config.py）：能按岛分别播种时长。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import ISLAND_CODE  # noqa: E402

TOKEN = None


def call(method, path, body=None, query=None):
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read().decode("utf-8")
            return r.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, {"error": t[:300]}


def ok(m):
    print(f"  ✔ {m}")


def bad(m, d=""):
    print(f"  ✘ {m}")
    if d:
        print(f"      {d}")


def login():
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    TOKEN = j["token"]
    print(f"✓ 已登录 {USER}")


def main():
    print("=" * 68)
    print("填客户流程的时长预估（duration_model）")
    print("=" * 68)
    print(f"API: {API}")
    login()

    # ---- 取岛上的设备方法（公开规格级估值）
    print()
    print("── 1. 取设备方法的公开规格估值")
    st, topo = call("GET", "/api/topology")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}")
    # 这 5 个型号是这条流程线的（PLANNED 登记时带进来的）
    wanted_models = {"P_AUTO", "DISPENSER_PLATE_GENERIC", "GANTRY_ROBOT_GENERIC",
                     "PLATE_STACK_GENERIC"}
    est = {}
    for d in island.get("devices", []):
        st, m = call("GET", f"/api/devices/{d['resourceId']}/methods")
        for it in m.get("items", []):
            sec = it.get("estimateSec")
            if not sec or int(sec) <= 0:
                continue
            # 同一方法名跨设备可能不同（同名不同义）⇒ 取**最大**（保守）
            key = it["method"]
            est[key] = max(est.get(key, 0), int(sec))
    if not est:
        sys.exit("✘ 没取到任何方法估值 —— 设备方法还没登记？")
    ok(f"取到 {len(est)} 个方法的公开规格估值")

    # ---- 写入 duration_model
    print()
    print("── 2. 写 duration_model（p50=公开规格 / p90=×1.5 / min=×0.5 / max=×3）")
    written = skipped = failed = 0
    for method, sec in sorted(est.items()):
        p50 = sec * 1000
        p90 = int(p50 * 1.5)
        mn = int(p50 * 0.5)
        mx = int(p50 * 3)
        st, r = call("PUT", "/api/durations", {
            "method": method, "p50Ms": p50, "p90Ms": p90,
            "minMs": mn, "maxMs": mx, "sampleCount": 0,
        })
        if st == 200:
            written += 1
        elif "已存在" in str(r) or st == 409:
            skipped += 1
        else:
            failed += 1
            if failed <= 3:
                bad(f"写 {method} 失败（{st}）", str(r)[:150])
    ok(f"时长模型：写入 {written} · 跳过 {skipped} · 失败 {failed}")

    # ---- 验证
    print()
    print("── 3. 验证（回读 + 节点时长是否用上了）")
    st, j = call("GET", "/api/durations")
    items = j.get("items", [])
    ok(f"回读到 {len(items)} 条时长模型")
    for it in items[:5]:
        print(f"    {it['method']:<24} p50={it['p50Ms']}ms  p90={it['p90Ms']}ms"
              f"  样本={it['sampleCount']}")
    if items and items[0]["sampleCount"] == 0:
        ok("样本数是 0（= 预估值，不是实测）—— 页面会如实显示")

    print()
    print("=" * 68)
    print("时长预估填完。")
    print("  · 现在节点时长取自 duration_model（页面上改完直接生效）")
    print("  · 样本数 0 = 预估值；现场跑过之后由观测数据回填")
    print("=" * 68)


if __name__ == "__main__":
    main()
