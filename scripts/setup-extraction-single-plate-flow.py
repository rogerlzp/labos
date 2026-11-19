#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单台提取仪 · 单块样品板的**完整板链**流程（2026-11-19）。

**为什么要这个（背景）**
《说明书》§3 的 9 个阶段，本质是**同一批板在 6 台分液器与提取仪之间往返**：
每台提取仪要用 **1 块样本板 + 1 块磁套板 + 4 块空深孔板（W1/W2/W3/洗脱）= 6 块板**，
6 台分液器各加一种液，加完的板送回提取仪做反应。
而现有 57 节点流程（EXT-WORKFLOW-02）把"6 台分液器各加一次液"做成了 6 个**互不相干**的
并行动作，**板没有在设备之间串起来** ⇒ 证明不了真实工艺。

**本脚本做什么**：按《说明书》阶段 2~8 还原**单台**（P_AUTO-1）的板链：
    磁套加载+磁珠分装 → 裂解 → W1 → W2 → W3 → 洗脱 → 收尾回栈
共 40 个节点、6 块板、6 个板变量。

**先做 1 台，跑通了再谈 4 台并行**（用户 2026-11-19 指示）：
并行度涉及机械臂独占资源与板位竞争，等单台跑通再评估。

用法：
    python3 scripts/setup-extraction-single-plate-flow.py            # 只建（流程+绑定+变量+板+位置）
    python3 scripts/setup-extraction-single-plate-flow.py --run      # 建完直接起跑
    python3 scripts/setup-extraction-single-plate-flow.py --export   # 顺带导出流程图与执行轨迹

复用：scripts/setup-extraction-flow.py 的 BPMN 生成器与绑定写入口径（不重写一套）。
"""
import argparse
import importlib.util
import json
import os
import subprocess
import sys
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from extraction_config import ISLAND_CODE, ext_codes, disp_codes  # noqa: E402


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


flow = _load("extract_flow", os.path.join(HERE, "setup-extraction-flow.py"))

WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-SINGLE")
WF_NAME = "高通量核酸提取工作站 · 单样品板标准流程（P_AUTO-1）"
TPL_NAME = os.environ.get("LABOS_TPL_NAME", "提取线 · 单样品板模板")
BATCH_NAME = os.environ.get("LABOS_BATCH_NAME", "单板第 1 批")
DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_DB_USER", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")

DOLLAR = chr(36)      # 拼扫码期望值用（避免脚本里出现会被 shell 展开的记号）

EXT = ext_codes()[0]
DISP = disp_codes()
ARM = "ARM1"
STACK = "STACK1"

PLATES = [
    ("plateSample", "SAMPLE", "SIM-PLATE-S1-SAMPLE", "样本板（96 孔，含样本）"),
    ("plateMagnet", "MAGNET", "SIM-PLATE-S1-MAGNET", "磁套板（装磁珠套）"),
    ("plateW1", "W1", "SIM-PLATE-S1-W1", "W1 洗涤用空深孔板"),
    ("plateW2", "W2", "SIM-PLATE-S1-W2", "W2 洗涤用空深孔板"),
    ("plateW3", "W3", "SIM-PLATE-S1-W3", "W3 洗涤用空深孔板"),
    ("plateElute", "ELUTE", "SIM-PLATE-S1-ELUTE", "洗脱用空深孔板（洗脱后即产物板）"),
]
LYSIS_SLOT_LABEL = "LYSIS"

WASH_STAGES = [
    ("W1", 2, "plateW1", "Wash", "W1 洗涤"),
    ("W2", 3, "plateW2", "Wash", "W2 洗涤"),
    ("W3", 4, "plateW3", "Wash", "W3 洗涤"),
    ("ELUTE", 5, "plateElute", "Elute", "洗脱"),
]


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [ln for ln in p.stdout.strip().splitlines() if ln]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="store_true")
    ap.add_argument("--export", action="store_true")
    args = ap.parse_args()

    print("=" * 70)
    print(f"{ISLAND_CODE} · 单样品板流程（{EXT} 一台 / {len(PLATES)} 块板）")
    print("=" * 70)
    flow.login()

    st, topo = flow.call("GET", "/api/topology")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit("✘ 找不到岛 %s" % ISLAND_CODE)
    island_id = island["id"]
    flow.ok(f"岛 {ISLAND_CODE}（id={island_id}）")

    st, pos = flow.call("GET", f"/api/positions?islandId={island_id}")
    points = pos.get("items") or []
    by_code = {p.get("code"): p for p in points}
    dev_codes = [EXT] + DISP + [ARM, STACK]
    # ⚠ 设备信息要从**拓扑**取：拓扑项里才有 instanceId（绑定的 deviceInstanceId 要它），
    #   /api/devices/registry 里没有这个字段 ⇒ 用它写绑定会报
    #   「每条绑定至少一个 positionId / deviceInstanceId / poolId」。
    devs = {d.get("code"): d for d in (island.get("devices") or [])
            if d.get("code") in dev_codes}
    missing = [c for c in dev_codes if c not in devs]
    if missing:
        sys.exit("✘ 设备清单里没有：%s" % ", ".join(missing))
    flow.ok(f"设备 {len(devs)} 台就位")

    method_id_by = {}
    for code, d in devs.items():
        st, m = flow.call("GET", f"/api/devices/{d['resourceId']}/methods")
        for it in m.get("items", []):
            if it.get("methodId") and it.get("method"):
                method_id_by[(code, it["method"])] = it["methodId"]
    need = [(EXT, "PickTipComb"), (EXT, "DropTipComb"), (EXT, "Bind"), (EXT, "Wash"),
            (EXT, "Elute"), (EXT, "GetStatus"), (ARM, "Transfer"), (ARM, "BarcodeScan")]
    need += [(d, "Run") for d in DISP]
    lack = [f"{c}.{m}" for c, m in need if (c, m) not in method_id_by]
    if lack:
        sys.exit("✘ 这些方法没授权：%s" % ", ".join(lack))
    flow.ok(f"方法授权就位（{len(method_id_by)} 个可用）")

    # 槽位选择必须**稳定**（2026-11-19 实测踩坑）：
    #   原来「每个分区挑第一个空槽」。板一旦被 locate 记在某槽位，那个槽就不再是空槽，
    #   下次跑就会跳到别的槽；而 locate 又搬不动（possible_locations 候选集限制，
    #   报「不在候选集内，不写位置账本」）⇒ 板位账本与流程声明的 sourcePosition 对不上
    #   ⇒ 第一段搬运直接 EC-POSITION-OCCUPIED（实测 run 1001814 节点 Activity_0002）。
    #   口径：**优先用「已经放着这块板」的槽位**；没有再退到分区里的第一个空槽。
    # ---- 重新播种（**必须在选槽位之前**）----
    #   把板从它上一次的落位清干净（人工把板取出来、准备重新上架）。
    #   为什么必须先清：/api/positions 报的是**账本上的在位**，
    #   上一轮失败后磁套板还挂在交互位 P_AUTO-1-EXT、样本板还挂在 DISP1-PLATE，
    #   若先选槽位就会把「板当前所在位」当成它的家 ⇒ 源位声明成交互位
    #   ⇒ 板位账本与流程声明对不上 ⇒ 搬运 EC-POSITION-OCCUPIED
    #   （实测 run 1001818/1001819 两次都栽在这里）。
    for var, label, bc, desc in PLATES:
        psql("UPDATE position_state SET plate_id=NULL, content_kind=NULL, content_id=NULL, "
             "occupied_by_run=NULL, occupied_since=NULL, "
             "reserved_until=NULL, reserved_by_run=NULL, updated_at=now() "
             f"WHERE tenant_id=1 AND plate_id='{bc}'")
        psql("UPDATE plate SET location_id=NULL, updated_at=now() "
             f"WHERE tenant_id=1 AND barcode='{bc}'")
    # ⚠ 清理之后**必须重新取一次点位**：上面那份 points 是清理前的快照，
    #   仍把刚腾出来的槽位算成「占用」⇒ 选位跳到下一个槽 ⇒ 板位漂移
    #   （实测：磁套板暂存从 S17 跳到 S18，阶段暂存整体后移一位）。
    st, pos = flow.call("GET", f"/api/positions?islandId={island_id}")
    points = pos.get("items") or []

    slots_by_label = {}
    slots_by_plate = {}
    for p in points:
        lab = (p.get("positionLabel") or "").upper()
        if not lab:
            continue
        slots_by_label.setdefault(lab, []).append(p)
        pid = p.get("plateId")
        if pid:
            slots_by_plate.setdefault(pid, []).append(p)
    for v in slots_by_label.values():
        v.sort(key=lambda p: p.get("code") or "")
    plate_slot = {}
    for var, label, bc, desc in PLATES:
        # 只认**同一分区**里放着这块板的槽：跨分区的位（如交互位）不是它的家。
        held = [p for p in (slots_by_plate.get(bc) or [])
                if (p.get("positionLabel") or "").upper() == label]
        if held:
            plate_slot[var] = held[0]
            continue
        free = [p for p in (slots_by_label.get(label) or []) if not p.get("plateId")]
        if not free:
            sys.exit("✘ %s 分区没有空槽（%s 放不下）" % (label, var))
        plate_slot[var] = free[0]
    # 暂存槽：每个「从交互位取回的板」都要有**自己**的槽位 ——
    #   同一个槽不能放两次（putPlateOnPosition 的 ON CONFLICT 要求目标位无板，
    #   第二次放板会 0 行 ⇒ EC-POSITION-OCCUPIED）。
    #   《说明书》阶段4~7 每一步都是「取出交互位的板 → 送回耗材栈暂存/回收」，
    #   4 个洗涤阶段各一块板 ⇒ 4 个暂存槽；磁套板（阶段3「磁珠分装完成板暂存」）再占 1 个。
    lysis_free = [p for p in (slots_by_label.get(LYSIS_SLOT_LABEL) or [])
                  if not p.get("plateId")]
    need_hold = len(WASH_STAGES) + 1
    if len(lysis_free) < need_hold:
        sys.exit("✘ %s 分区只有 %d 个空槽（需要 %d 个：磁套板 1 + 洗涤阶段 %d）"
                 % (LYSIS_SLOT_LABEL, len(lysis_free), need_hold, len(WASH_STAGES)))
    magnet_hold_slot = lysis_free[0]
    stage_hold = lysis_free[1:1 + len(WASH_STAGES)]
    flow.ok("槽位：" + " · ".join(
        "%s→%s" % (v, plate_slot[v]["code"]) for v, _, _, _ in PLATES)
        + " · 磁套板暂存→" + magnet_hold_slot["code"]
        + " · 阶段暂存→" + "/".join(h["code"] for h in stage_hold))

    b = flow.Bpmn("Proc_Single", WF_NAME)
    node_bind = {}
    # 扫码方法名：必须是 ARM1（型号 GANTRY_ROBOT_GENERIC）在 device_method 里
    # **真实登记**的名字 —— 写画像里的名字会「节点找不到方法」⇒ 派发失败 ⇒ 转人工。
    SCAN_METHOD = "BarcodeScan"

    def transfer(name, src_dev, src_pos, dst_dev, dst_pos, barcode_var):
        """一次搬运 = **抓板前扫码 + 搬运 + 到位扫码**（P2 §4.1/§4.3 口径）。

        ⚠ 抓板闸门是 fail-closed 的：搬运节点按「run + 源位 + 未消费」找扫码证据，
          找不到就 EC-SCAN-EVIDENCE-MISSING 转人工（实测 run 1001817 节点 Activity_0002）。
          ⇒ 每段搬运前面必须有**扫码节点**，且它的 sourcePosition 必须与搬运的
          sourcePosition 是**同一个点位**、barcode 是同一块板。
        ⚠ 到位扫码不能省：板物理到位了但**位置账本还没写**（未确认），
          下一次从那个位抓板时要靠这次扫码提供证据。
        ⚠ 扫码节点**不绑点位**：被扫点位属于别的设备（耗材栈/分液器/提取仪），
          平台的绑定模型要求「节点绑的点位属于该节点自己的设备」⇒ 只能用
          method_params.sourcePosition 表达被扫点位。
        """
        bc = DOLLAR + "{%s}" % barcode_var
        n_scan = b.task(name + "（抓板前扫码）", protocol=SCAN_METHOD,
                        resource=devs[ARM]["resourceId"],
                        params={"barcode": bc, "sourcePosition": src_pos},
                        icon="/devices/TRANSFER.svg")
        node_bind[n_scan] = (ARM, SCAN_METHOD)
        n_move = b.task(name, protocol="Transfer", resource=devs[ARM]["resourceId"],
                        params={"source": src_dev, "target": dst_dev,
                                "barcode": bc,
                                "sourcePosition": src_pos, "targetPosition": dst_pos},
                        icon="/devices/TRANSFER.svg")
        node_bind[n_move] = (ARM, "Transfer")
        n_done = b.task(name + "（到位扫码）", protocol=SCAN_METHOD,
                        resource=devs[ARM]["resourceId"],
                        params={"barcode": bc, "sourcePosition": dst_pos},
                        icon="/devices/TRANSFER.svg")
        node_bind[n_done] = (ARM, SCAN_METHOD)
        return [n_scan, n_move, n_done]

    def device_task(name, dev, method, params=None, icon=None):
        nid = b.task(name, protocol=method, resource=devs[dev]["resourceId"],
                     params=params or {}, icon=icon)
        node_bind[nid] = (dev, method)
        return nid

    def await_event(dev, into, timeout=900):
        nid = b.task("%s 等【等待交互】" % dev, protocol="__awaitEvent",
                     resource=devs[dev]["resourceId"],
                     params={"device": dev, "eventType": "waiting_interaction",
                             "timeout": timeout, "into": into},
                     icon="/devices/EXTRACT.svg")
        node_bind[nid] = (dev, "__awaitEvent")
        return nid

    ext_pos = "%s-EXT" % EXT
    start = b.start("开始")

    a1 = transfer("机械臂 取磁套板（耗材栈 → %s 交互位）" % EXT, STACK,
                  plate_slot["plateMagnet"]["code"], EXT, ext_pos, "plateMagnet")
    a2 = device_task("%s 取磁套（磁套装到转盘）" % EXT, EXT, "PickTipComb",
                     {"plateIndex": 0}, "/devices/EXTRACT.svg")
    a3 = await_event(EXT, "%s_wait_magnet" % EXT)
    a4 = device_task("%s 卸磁套（磁套板下转盘，送分液器分装磁珠）" % EXT, EXT,
                     "DropTipComb", {"plateIndex": 0}, "/devices/EXTRACT.svg")
    a5 = transfer("机械臂 磁套板 → %s 板位" % DISP[1], EXT, ext_pos,
                  DISP[1], "%s-PLATE" % DISP[1], "plateMagnet")
    a6 = device_task("%s 磁珠分装（顶置搅拌）" % DISP[1], DISP[1], "Run", {},
                     "/devices/WASH.svg")
    a7 = transfer("机械臂 磁套板 → 回 %s 交互位" % EXT, DISP[1], "%s-PLATE" % DISP[1],
                  EXT, ext_pos, "plateMagnet")
    a8 = device_task("%s 磁套再装转盘" % EXT, EXT, "PickTipComb",
                     {"plateIndex": 0}, "/devices/EXTRACT.svg")
    # ⚠ 磁套板必须**离开交互位**（《说明书》阶段3：「磁珠分装完成板暂存」）：
    #   交互位同时只能停一块板（§1.6 互锁2「同一板同一时间仅允许在一个工位」的反面：
    #   一个工位同一时间只能有一块板）。磁套板若留在交互位，后面裂解板送不进来
    #   ⇒ EC-POSITION-OCCUPIED（实测 run 1001818 节点「机械臂 裂解板 → 交互位」）。
    a9 = transfer("机械臂 磁套板 → 回耗材栈暂存（%s 位）" % magnet_hold_slot["code"],
                  EXT, ext_pos, STACK, magnet_hold_slot["code"], "plateMagnet")

    b1 = transfer("机械臂 取样本板（耗材栈 → %s 板位）" % DISP[0], STACK,
                  plate_slot["plateSample"]["code"], DISP[0], "%s-PLATE" % DISP[0],
                  "plateSample")
    b2 = device_task("%s 裂解液+蛋白酶K 分液" % DISP[0], DISP[0], "Run", {},
                     "/devices/WASH.svg")
    b3 = transfer("机械臂 裂解板 → %s 交互位" % EXT, DISP[0], "%s-PLATE" % DISP[0],
                  EXT, ext_pos, "plateSample")
    b4 = device_task("%s 结合（加热+振荡+磁化＝内部裂解计时）" % EXT, EXT, "Bind",
                     {"plateIndex": 0}, "/devices/EXTRACT.svg")
    b5 = await_event(EXT, "%s_wait_lysis" % EXT)

    stage_nodes = []
    # 「取回上一块板」搬的是**上一阶段留在交互位的那块板**，不是本阶段要送去洗涤的新板。
    #   阶段1 交互位上是裂解板(plateSample)；阶段2 是 W1 板；阶段3 是 W2 板；阶段4 是 W3 板。
    #   ⚠ 之前这里误写成"本阶段的新板"（var）⇒ 扫码证据按**位置账本**比对时
    #     期望码（账本：裂解板）与实扫码（节点要的是 W1 板）对不上
    #     ⇒ EC-SCAN-MISMATCH（实测 run 1003819 节点 Activity_0029）。
    prev_var = "plateSample"
    for si, (tag, dno, var, act, act_cn) in enumerate(WASH_STAGES):
        disp = DISP[dno]
        hold = stage_hold[si]
        c1 = transfer("机械臂 取回上一块板 → 回耗材栈暂存（%s 位）" % hold["code"],
                      EXT, ext_pos, STACK, hold["code"], prev_var)
        c2 = transfer("机械臂 调新空板（%s → %s 板位）" % (plate_slot[var]["code"], disp),
                      STACK, plate_slot[var]["code"], disp, "%s-PLATE" % disp, var)
        c3 = device_task("%s %s 洗涤液分液" % (disp, tag), disp, "Run", {}, "/devices/WASH.svg")
        c4 = transfer("机械臂 %s 板 → %s 交互位" % (tag, EXT), disp, "%s-PLATE" % disp,
                      EXT, ext_pos, var)
        c5 = device_task("%s %s" % (EXT, act_cn), EXT, act, {"plateIndex": 0},
                         "/devices/EXTRACT.svg")
        c6 = await_event(EXT, "%s_wait_%s" % (EXT, tag.lower()))
        stage_nodes.append([c1, c2, c3, c4, c5, c6])
        prev_var = var        # 本阶段这块板成为"下一阶段要取回的那块"

    g1 = transfer("机械臂 产物板 → 回耗材栈（%s 位）" % plate_slot["plateElute"]["code"],
                  EXT, ext_pos, STACK, plate_slot["plateElute"]["code"], "plateElute")
    g2 = device_task("%s 读状态" % EXT, EXT, "GetStatus", {}, "/devices/EXTRACT.svg")
    end = b.end("结束")

    def flat(nodes):
        out = []
        for n in nodes:
            if isinstance(n, list):
                out.extend(flat(n))
            else:
                out.append(n)
        return out

    seq = flat([start, a1, a2, a3, a4, a5, a6, a7, a8, a9,
                b1, b2, b3, b4, b5])
    for chain in stage_nodes:
        seq.extend(flat(chain))
    seq.extend(flat([g1, g2, end]))
    for i in range(len(seq) - 1):
        b.flow(seq[i], seq[i + 1])

    xml = b.to_xml()
    flow.ok("流程图：%d 个节点 / %d 条流" % (len(b.nodes), len(b.flows)))

    st, lst = flow.call("GET", "/api/workflows", query={"limit": "300", "q": WF_CODE})
    wf = next((w for w in (lst.get("items") or []) if w.get("code") == WF_CODE), None)
    if wf is None:
        st, j = flow.call("POST", "/api/workflows",
                          {"code": WF_CODE, "name": WF_NAME, "islandId": island_id})
        if st != 200:
            sys.exit("✘ 建流程失败（%s）：%s" % (st, j))
        wf_id = j.get("id") or (j.get("workflow") or {}).get("id")
        flow.ok(f"流程已建：{WF_CODE}（id={wf_id}）")
    else:
        wf_id = wf.get("id")
        flow.ok(f"流程已存在：{WF_CODE}（id={wf_id}）")
    st, j = flow.call("PUT", f"/api/workflows/{wf_id}/graph", raw=xml,
                      content_type="application/xml")
    if st != 200:
        sys.exit("✘ 写流程图失败（%s）：%s" % (st, j))
    flow.ok("流程图已写入")

    for var, label, bc, desc in PLATES:
        st, j = flow.call("POST", f"/api/workflows/{wf_id}/variables",
                          {"name": var, "type": "string", "defaultValue": bc,
                           "description": desc})
        if st != 200:
            flow.bad(f"建变量 {var} 失败（{st}）", str(j)[:150])
        else:
            flow.ok(f"变量 {var} = {bc}")

    def position_for(code):
        if code.startswith("P_AUTO-"):
            return by_code.get("%s-EXT" % code)
        if code.startswith("DISP"):
            return by_code.get("%s-PLATE" % code)
        return None

    written = cleared = 0
    for nid, tag, name, attrs in b.nodes:
        target = node_bind.get(nid)
        if target is None:
            st, r = flow.bind_node(wf_id, nid, None, None, None, None)
            if st == 200:
                cleared += 1
            continue
        code, method = target[0], target[1]
        pos_override = target[2] if len(target) > 2 else None
        pos = by_code.get(pos_override) if pos_override else position_for(code)
        st, r = flow.bind_node(wf_id, nid, code, devs[code],
                                (pos or {}).get("pointId"),
                                method_id_by.get((code, method)))
        if st == 200:
            written += 1
        else:
            flow.bad("节点绑定失败：%s %s（%s）" % (nid, name, st), str(r)[:160])
    flow.ok(f"节点绑定：写入 {written} · 清空 {cleared}")

    flow.section("播种 6 块板并放进耗材栈")
    for var, label, bc, desc in PLATES:
        psql("INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols, status, "
             "identity_status, created_at, updated_at) "
             f"VALUES (1, '{bc}', 'unknown', 96, 12, 'active', 'PROVISIONAL', now(), now()) "
             "ON CONFLICT (tenant_id, barcode) DO NOTHING;")
        slot = plate_slot[var]
        st, j = flow.call("POST", f"/api/plates/{bc}/locate",
                          {"actualLocation": str(slot.get("resourceId"))})
        flow.ok(f"{bc} → {slot.get('code')}" if st == 200
                else f"✘ {bc} 放板失败（{st}）：{str(j)[:120]}")

    # ⚠ 平台缺陷（2026-11-19 实测）：POST /api/plates/{bc}/locate 走「人工盘点」，
    #   内部 putPlateOnPosition 把归属写成 occupied_by_run = 0（runId 为空时的占位），
    #   而 claimPosition 只认 NULL / 本 run / 已终结 run ⇒ 0 是永远认领不走的毒值：
    #   板被人工放进耗材栈后，本轮 run 的「起点引导」认领不到 ⇒ 第一段搬运就
    #   EC-POSITION-OCCUPIED（实测 run 1001812 节点 Activity_0002）。
    #   平台侧修复：DeviceStateMapper.xml 的 putPlateOnPosition（runId<=0 写 NULL）
    #   + claimPosition（把 0 也当「无归属」）。平台修复上线前，这里先清成 NULL。
    poison = psql("UPDATE position_state SET occupied_by_run=NULL, occupied_since=NULL "
                  "WHERE tenant_id=1 AND occupied_by_run=0 RETURNING resource_id")
    flow.ok("清除人工盘点的归属毒值（occupied_by_run=0）：%d 个位" % len(poison))

    flow.section("模板 → 实例化 → 配位置 → 核验")
    st, lst = flow.call("GET", "/api/experiment-templates", query={"limit": "100"})
    tpl = next((t for t in (lst.get("items") or []) if t.get("name") == TPL_NAME), None)
    items = []
    for var, label, bc, desc in PLATES:
        rows = psql(f"SELECT id FROM plate WHERE tenant_id=1 AND barcode='{bc}'")
        pid = int(rows[0]) if rows else 0
        items.append({"roleCode": var, "materialKind": "plate",
                      "materialRef": pid, "quantity": 1, "required": True})
    payload = {"name": TPL_NAME, "islandId": island_id,
               "workflowIds": [{"workflowId": wf_id}],
               "manifestItems": items, "pointWhitelist": []}
    if tpl is None:
        st, j = flow.call("POST", "/api/experiment-templates", payload)
    else:
        st, j = flow.call("DELETE", f"/api/experiment-templates/{tpl.get('id')}")
        if st == 200:
            st, j = flow.call("POST", "/api/experiment-templates", payload)
    if st != 200:
        sys.exit("✘ 建模板失败（%s）：%s" % (st, j))
    tpl_id = j.get("id") or (j.get("template") or {}).get("id")
    flow.ok(f"模板已建：{TPL_NAME}（id={tpl_id}）")
    st, chk = flow.call("GET", f"/api/experiment-templates/{tpl_id}/check")
    flow.ok(f"模板自检：ok={chk.get('ok')} issues={len(chk.get('issues') or [])}")

    st, j = flow.call("POST", f"/api/experiment-templates/{tpl_id}/instantiate",
                      {"name": BATCH_NAME, "experimentNo": "", "spec": {}})
    if st != 200:
        sys.exit("✘ 实例化失败（%s）：%s" % (st, j))
    run_id = j.get("runId")
    exp_id = j.get("experimentId")
    flow.ok(f"实验已建：experimentId={exp_id} runId={run_id}")

    # ⚠ 实例化不会把流程编排复制到实验 ⇒ 调度器报「实验没有编排任何流程」
    #   （实测 run 1003793 = IllegalArgumentException）。这里显式补一条编排。
    st, j2 = flow.call("POST", f"/api/experiments/{exp_id}/processes",
                       {"workflowIds": [wf_id]})
    if st != 200:
        sys.exit("✘ 绑定实验编排失败（%s）：%s" % (st, j2))
    flow.ok("实验编排已绑定：workflow %d" % wf_id)

    st, man = flow.call("GET", f"/api/experiment-runs/{run_id}/manifest")
    by_role = {p.get("roleCode"): p for p in (man.get("items") or [])}
    for var, label, bc, desc in PLATES:
        pl = by_role.get(var)
        if not pl:
            flow.bad(f"物料配置行里没有 {var}")
            continue
        slot = plate_slot[var]
        st, j = flow.call("PUT",
                           f"/api/experiment-runs/{run_id}/placements/{pl['id']}",
                           {"positionId": slot.get("pointId")})
        if st != 200:
            flow.bad(f"{var} 配位置失败（{st}）", str(j)[:150])
            continue
        st, j = flow.call("POST",
                         f"/api/experiment-runs/{run_id}/placements/{pl['id']}/verify")
        flow.ok(f"{var}（{bc}）→ {slot.get('code')}"
                + ("（已核验）" if st == 200 else f" ✘ 核验失败 {st}"))

    st, rd = flow.call("GET", f"/api/experiment-runs/{run_id}/readiness")
    flow.ok(f"readiness：ready={rd.get('ready')} checks={rd.get('checks')}")

    if args.run:
        flow.section("起跑")
        # 板链静态推演（起跑前自查）：板号/板位对不对得上，纸上先走一遍。
        #   这三种错（取回板号写错 / 交互位没让出 / 目标位没腾空）都要跑十几分钟才暴露。
        r = subprocess.run([sys.executable,
                            os.path.join(HERE, "verify-extraction-plate-chain.py"),
                            "--strict"], cwd=os.path.dirname(HERE))
        if r.returncode != 0:
            sys.exit("✘ 板链推演没过（板号/板位不自洽），不起跑")
        # 搬运路径的**轨道分区登记**（RailZoneGuard 是 fail-closed 的）：
        #   路径键是「源位置 resourceId->目标位置 resourceId」⇒ 换了板位/暂存槽
        #   就换了一批路径键，没登记的路径直接 EC-INTERLOCK-RAIL 转人工
        #   （实测 run 1003819 节点「取回上一块板 → 回耗材栈暂存（STACK1-S18 位）」）。
        #   ⇒ 每次改板位都必须重登记，登记口径见 setup-extraction-rail-zones.py。
        subprocess.run([sys.executable,
                        os.path.join(HERE, "setup-extraction-rail-zones.py"), "--apply"],
                       check=True, cwd=os.path.dirname(HERE))
        # 清现场扣留：**上一次失败会留下岛级 runtime_hold**，把整个岛的实验派发挡住
        #   （实测 18:32/18:45/18:48 三次各留一条 ⇒ 调度器一直不派发）。
        #   口径同 scripts/core-scenarios.py：扣留清掉后，之前等着人工确认的实验
        #   **不会自动恢复**（它们的状态仍是 paused），只影响本次新起的 run。
        held = psql("UPDATE runtime_hold SET released_at=now(), released_by=1, "
                    "release_note='单样品板流程起跑前清理' "
                    "WHERE tenant_id=1 AND released_at IS NULL AND scope='island' "
                    f"AND scope_id={island_id} RETURNING id")
        flow.ok("清除岛级现场扣留：%d 条" % len(held))
        st, j = flow.call("POST", f"/api/experiment-runs/{run_id}/start")
        if st != 200:
            sys.exit("✘ 起跑失败（%s）：%s" % (st, j))
        flow.ok("已启动：%s" % json.dumps(j, ensure_ascii=False))
        # 调度队列是内存态 ⇒ 光 start 不会让调度器拾取，必须显式重新入队。
        st, j = flow.call("POST", f"/api/experiment-runs/{run_id}/dispatch")
        if st != 200:
            sys.exit("✘ 入队失败（%s）：%s" % (st, j))
        flow.ok("已入队：%s" % json.dumps(j, ensure_ascii=False))
    else:
        flow.ok("（没起跑；要跑加 --run）")
    flow.ok(f"盯盘：GET /api/runs/{run_id}")

    if args.export:
        subprocess.run([sys.executable,
                       os.path.join(HERE, "export-extraction-flow-diagram.py"),
                       "--run", str(run_id)], cwd=os.path.dirname(HERE))


if __name__ == "__main__":
    main()

