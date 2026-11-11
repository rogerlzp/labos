#!/usr/bin/env python3
# ============================================================================
# 基线播种：在基线岛上建「一条流程 + 一个实验」，供新门禁与测试用例反复用。
#
# 为什么要有播种脚本（而不是把 ID 写死进门禁）：
#   流程 / 实验是**会变的数据**（每次重置基线 id 都不同），而门禁要能反复跑。
#   把"长什么样的流程"写成代码，门禁只负责"跑它、断言它"，
#   两边不会因为 id 变了就互相找不着。
#
# 基线流程刻意只有 5 个节点、5 台设备各一个 —— 覆盖 5 类能力
# （机器人 / 孵育箱 / 离心机 / 读板机 / 封膜机），一条流程跑完就知道
# 哪一类能力坏了。方法必须是「设备方法集 ∩ 模拟器画像方法集」里的：
#   X231        机器人  Home        （画像 robot）
#   X229        孵育箱  Deposit1Ex  （画像 incubator）
#   ASPIN_2K-1 离心机  CloseDoor   （画像 centrifuge）
#   ID1021      读板机  OpenDoor    （画像 reader）
#   S100-1      封膜机  Seal        （画像 sealer）
#
# 用法：
#   python3 scripts/baseline-seed.py                # 打印 JSON
#   python3 scripts/baseline-seed.py --json out.json # 顺手存一份
# ============================================================================
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
ISLAND_CODE = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

# 基线流程：5 个节点串行，每台设备一个。
# resource 用 resource_node.id（设备节点），protocolName 用方法名。
GRAPH = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_base" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_base" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="机器人归位" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:task id="T2" name="孵育箱放板" protocolName="Deposit1Ex" resource="__INCUBATOR__"/>
    <bpmn:task id="T3" name="离心机关门" protocolName="CloseDoor" resource="__CENTRIFUGE__"/>
    <bpmn:task id="T4" name="读板机开门" protocolName="OpenDoor" resource="__READER__"/>
    <bpmn:task id="T5" name="封膜" protocolName="Seal" resource="__SEALER__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="T3"/>
    <bpmn:sequenceFlow id="f4" sourceRef="T3" targetRef="T4"/>
    <bpmn:sequenceFlow id="f5" sourceRef="T4" targetRef="T5"/>
    <bpmn:sequenceFlow id="f6" sourceRef="T5" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_base">
    <bpmndi:BPMNPlane id="P_base" bpmnElement="Proc_base">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="290" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T3_di" bpmnElement="T3"><dc:Bounds x="430" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T4_di" bpmnElement="T4"><dc:Bounds x="570" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T5_di" bpmnElement="T5"><dc:Bounds x="710" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="860" y="160" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if raw is not None:
        data = raw.encode()
        req.add_header("Content-Type", "application/xml")
    elif body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s" % (method, path, e.code, e.read().decode()[:300]))


def q(sql):
    """跑一条 SQL。**查询失败必须抛错**，不能返回空串 ——
    空串会被上层当成"库里没有"，于是"数据库连不上"被误报成"环境没设备"，
    门禁就会静默跳过（这就是历史上的假绿）。"""
    import subprocess
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql.strip()[:200], out.stderr.strip()[:300]))
    return out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", help="把结果写到这个文件")
    args = ap.parse_args()

    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    # 岛 + 5 台设备的 resource_node.id（流程里的 resource 用它）
    rows = q("""
        SELECT d.code || '|' || d.id FROM resource_node isl
          JOIN resource_node d ON d.parent_id = isl.id AND d.kind = 'device'
         WHERE isl.code = '%s' AND isl.deleted_at IS NULL AND d.deleted_at IS NULL
    """ % ISLAND_CODE).split("\n")
    res = {}
    for line in rows:
        if not line.strip():
            continue
        code, rid = line.split("|")
        res[code] = rid
    if not res:
        print("基线岛 %s 下没有设备（先跑 sim-up.sh 或 baseline-build.sql）" % ISLAND_CODE,
              file=sys.stderr)
        return 2

    def pick(*candidates):
        for c in candidates:
            for code, rid in res.items():
                if code == c:
                    return rid
        raise RuntimeError("基线岛里找不到设备 %s（现有：%s）"
                           % (candidates, list(res)))

    graph = (GRAPH
             .replace("__ROBOT__", pick("X231"))
             .replace("__INCUBATOR__", pick("X229"))
             .replace("__CENTRIFUGE__", pick("ASPIN_2K-1"))
             .replace("__READER__", pick("ID1021"))
             .replace("__SEALER__", pick("S100-1")))

    island_id = q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'"
                  % ISLAND_CODE)

    wf = api("/api/workflows", "POST", {"name": "基线·五设备串行"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=graph, token=token)
    exp = api("/api/experiments", "POST",
              {"name": "基线·五设备实验",
               "workflowIds": [wf], "islandId": int(island_id)}, token)["id"]

    out = {"islandId": int(island_id), "islandCode": ISLAND_CODE,
           "workflowId": wf, "experimentId": exp,
           "devices": res}
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.json:
        with open(args.json, "w") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
