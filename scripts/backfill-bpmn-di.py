#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量补齐流程 XML 里缺失的 BPMNEdge（连线图形信息）。

**为什么需要**：BPMN 里「逻辑连线」是 `<bpmn:sequenceFlow>`，
「画出来的连线」是 DI 段的 `<bpmndi:BPMNEdge>`（带 waypoints）。
前端 bpmn-js 只渲染有 DI 的元素 ⇒ 缺 BPMNEdge 时画布上是一堆孤立节点、
一条线都没有；而后端存与跑都看 sequenceFlow ⇒ "看不见连线，但保存成功、还能跑"。

后端 `WorkflowService.saveGraph` 现在会自动补（`BpmnDiCompleter`），
所以**新存的一律没问题**；本脚本用于把**修复前已经存进去的**那些补上
（重新 PUT 一次它的 graph，走一遍 saveGraph 即可）。

用法：
  python3 scripts/backfill-bpmn-di.py            # 补所有缺 BPMNEdge 的流程
  python3 scripts/backfill-bpmn-di.py --dry-run  # 只看会补哪些
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")


def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = raw.encode() if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    if raw is not None:
        req.add_header("Content-Type", "application/xml")
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s" % out.stderr.strip()[:300])
    return out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    rows = q("""
        SELECT id || '|' || coalesce(name,'') || '|' ||
               (length(graph->>'xml') - length(replace(graph->>'xml', '<bpmndi:BPMNEdge', '')))
                 / length('<bpmndi:BPMNEdge') || '|' ||
               (length(graph->>'xml') - length(replace(graph->>'xml', '<bpmn:sequenceFlow', '')))
                 / length('<bpmn:sequenceFlow')
          FROM workflow WHERE tenant_id=1 AND graph->>'xml' IS NOT NULL
         ORDER BY id""")
    todo = []
    for ln in rows.split("\n"):
        if not ln.strip():
            continue
        wid, name, edges, flows = ln.rsplit("|", 3)
        if int(edges) < int(flows) and int(flows) > 0:
            todo.append((int(wid), name, int(edges), int(flows)))

    print("缺 DI 的流程 %d 个：" % len(todo))
    for wid, name, e, f in todo:
        print("  #%-8s %-28s 有 sequenceFlow %d 条 / BPMNEdge %d 条" % (wid, name[:28], f, e))
    if args.dry_run:
        return 0

    ok, bad = 0, 0
    for wid, name, e, f in todo:
        try:
            xml = q("SELECT graph->>'xml' FROM workflow WHERE id=%d" % wid)
            if not xml:
                continue
            api("/api/workflows/%d/graph" % wid, "PUT", raw=xml, token=token)
            ok += 1
            print("  ✓ #%d 已补" % wid)
        except Exception as e:  # noqa: BLE001
            bad += 1
            print("  ✗ #%d 补失败：%s" % (wid, e))
    print("\n补完 %d 个，失败 %d 个" % (ok, bad))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
