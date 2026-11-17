#!/usr/bin/env python3
"""搬运（机械臂）容量体检：**能同时跑几个流程**。

## 为什么要有这个脚本

全部搬运路径在 `transfer_rail_zone` 里都含同一个分区 `RAIL-X231-1`
（resource 200231 = `ID-X231` XRobot A10LR-1）——
**它就是那台唯一的机械臂**，不是"分区配错了"。
⇒ 所有搬运物理上只能串行，`RailZoneGuard` 等锁超过
`LABOS_RAIL_WAIT_TIMEOUT_MS`（默认 120s，2026-11-17 从 60s 抬高）就返回
`EC-RESOURCE-BUSY`。

于是「4 台提取仪并行」到底行不行，等价于一个排队问题：

    单臂容量 = 1 / 单次搬运耗时
    需求     = 并行 run 数 × 每个 run 的搬运频率
    需求 > 容量 ⇒ 队列无限增长 ⇒ 迟早撞等待超时

本脚本把这三个数算出来，并给出**可并行 run 数上限**，避免靠感觉猜。

## 用法

    python3 scripts/analyze-transfer-capacity.py
    python3 scripts/analyze-transfer-capacity.py --workflow-code EXT-WORKFLOW-01
    python3 scripts/analyze-transfer-capacity.py --days 7 --wait-ms 120000

需要 `LABOS_DB_*`（脚本会自己读 /tmp/labos-env.txt，也可 `set -a; . ...; set +a`）。
只读，不写库。
"""
import argparse
import os
import re
import sys

ENV_FILE_CANDIDATES = ("/tmp/labos-env.txt",)


def parse_env_file(path):
    out = {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#") or "=" not in s:
                    continue
                k, v = s.split("=", 1)
                k = k.strip()
                if k.startswith("export "):
                    k = k[len("export "):].strip()
                out[k] = v.strip().strip('"').strip("'")
    except OSError:
        return {}
    return out


def load_settings():
    file_vals = {}
    for p in (os.environ.get("LABOS_ENV_FILE"), *ENV_FILE_CANDIDATES):
        if p and os.path.isfile(p):
            file_vals = parse_env_file(p)
            break

    def pick(*names, default=""):
        for n in names:
            v = os.environ.get(n) or file_vals.get(n)
            if v:
                return v
        return default

    return {
        "host": pick("LABOS_DB_HOST", default="192.168.124.2"),
        "port": pick("LABOS_DB_PORT", default="5432"),
        "name": pick("LABOS_DB_NAME", default="labdb"),
        "user": pick("LABOS_DB_USER", default="labos"),
        "pass": pick("LABOS_DB_PASS", "LABOS_DB_PASSWORD", "PGPASSWORD"),
    }


# 搬运类协议：节点属性是 protocolName="Transfer"（不是 protocol="…"）。
# MoveInLabware/MoveOutLabware 是**设备内部**搬运（板进出提取仪），不占机械臂 ⇒ 不计。
TRANSFER_PROTOCOL_RE = re.compile(r'protocolName="(Transfer[^"]*)"')


def count_transfer_nodes(xml):
    """BPMN XML 里数搬运节点（按 protocol 属性，不按 name 猜）。"""
    return len(TRANSFER_PROTOCOL_RE.findall(xml or ""))


def fmt(x, nd=2):
    return "—" if x is None else f"{x:.{nd}f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow-id", type=int, default=0)
    ap.add_argument("--workflow-code", default="EXT-WORKFLOW-01")
    ap.add_argument("--days", type=int, default=7, help="统计 run_command 的窗口（天）")
    ap.add_argument("--wait-ms", type=int, default=120000, help="RailZoneGuard 等待上限（毫秒）")
    ap.add_argument("--include-internal", action="store_true",
                    help="把 MoveInLabware/MoveOutLabware 也算作占臂（默认只算 Transfer）")
    args = ap.parse_args()

    try:
        import psycopg2
    except ImportError:
        print("需要 psycopg2：pip install psycopg2-binary", file=sys.stderr)
        return 2

    cfg = load_settings()
    if not cfg["pass"]:
        print("✗ 没有数据库密码（LABOS_DB_PASS / PGPASSWORD）。", file=sys.stderr)
        print("  env 文件是裸 KEY=value ⇒ 需要 set -a，或让本脚本自读（默认会读 /tmp/labos-env.txt）",
              file=sys.stderr)
        return 2
    conn = psycopg2.connect(host=cfg["host"], port=cfg["port"], dbname=cfg["name"],
                            user=cfg["user"], password=cfg["pass"], connect_timeout=10)
    cur = conn.cursor()

    # ---------- ① 流程侧：一批要搬几次 ----------
    if args.workflow_id > 0:
        cur.execute("SELECT id, code, name, graph->>'xml' FROM workflow WHERE id = %s",
                    (args.workflow_id,))
    else:
        cur.execute("SELECT id, code, name, graph->>'xml' FROM workflow "
                    "WHERE code = %s AND deleted_at IS NULL ORDER BY id DESC LIMIT 1",
                    (args.workflow_code,))
    row = cur.fetchone()
    if not row:
        print(f"✗ 找不到流程（id={args.workflow_id} code={args.workflow_code}）", file=sys.stderr)
        return 2
    wf_id, wf_code, wf_name, xml = row
    n_transfer = count_transfer_nodes(xml)

    # ---------- ② 机械臂侧：单次搬运耗时 ----------
    cur.execute("""
        SELECT count(*),
               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch from (finished_at - sent_at))),
               avg(extract(epoch from (finished_at - sent_at))),
               max(extract(epoch from (finished_at - sent_at)))
          FROM run_command
         WHERE method = 'Transfer' AND finished_at IS NOT NULL AND sent_at IS NOT NULL
           AND finished_at > now() - (%s || ' days')::interval
    """, (args.days,))
    n_cmd, p50, avg_s, max_s = cur.fetchone()

    # ---------- ③ run 侧：单 run 的搬运频率与并发 ----------
    cur.execute("""
        WITH rc AS (SELECT run_id, count(*) n, min(sent_at) t0, max(finished_at) t1
                      FROM run_command
                     WHERE method = 'Transfer' AND finished_at IS NOT NULL
                       AND finished_at > now() - (%s || ' days')::interval
                  GROUP BY 1)
        SELECT count(*) FILTER (WHERE n >= 3),
               percentile_cont(0.5) WITHIN GROUP (
                   ORDER BY rc.n / GREATEST(extract(epoch from (rc.t1 - rc.t0)) / 60.0, 0.01)),
               max(rc.n / GREATEST(extract(epoch from (rc.t1 - rc.t0)) / 60.0, 0.01))
          FROM rc WHERE n >= 3
    """, (args.days,))
    runs_with_transfer, run_rate_p50, run_rate_max = cur.fetchone()

    # ⚠ 并发度不能用「actual_start/actual_end 重叠」直接算：批量终止（upsert actual_end = now()）
    #   会让一批历史 run 结束时间挤在同一毫秒，算出假的几十并发（实测 33，真值 2）。
    #   ⇒ 只统计时长合理（< 8 小时）的 run，并单独报告被剔除的异常条数。
    cur.execute("""
        WITH r AS (SELECT id, actual_start, actual_end FROM run
                    WHERE actual_start > now() - (%s || ' days')::interval
                      AND actual_end IS NOT NULL
                      AND actual_end - actual_start < interval '8 hours'),
             bad AS (SELECT count(*) c FROM run
                    WHERE actual_start > now() - (%s || ' days')::interval
                      AND actual_end IS NOT NULL
                      AND actual_end - actual_start >= interval '8 hours')
        SELECT (SELECT max(c) FROM (
                  SELECT t, (SELECT count(*) FROM r r2 WHERE r2.actual_start <= t AND r2.actual_end >= t) c
                    FROM (SELECT actual_start t FROM r UNION SELECT actual_end FROM r) x) y),
               (SELECT c FROM bad)
    """, (args.days, args.days))
    max_concurrent, bulk_abort_artifact = cur.fetchone()

    # ---------- ④ 分区锁：确认"是否真的全在一个分区" ----------
    cur.execute("""
        SELECT zone_resource_id, count(*) FROM transfer_rail_zone GROUP BY 1 ORDER BY 2 DESC LIMIT 1
    """)
    zrow = cur.fetchone()
    cur.execute("SELECT count(DISTINCT path_key) FROM transfer_rail_zone")
    paths_total = cur.fetchone()[0]
    cur.execute("SELECT count(DISTINCT path_key) FROM transfer_rail_zone WHERE zone_resource_id = %s",
                (zrow[0] if zrow else 0,))
    paths_in_top_zone = cur.fetchone()[0]
    cur.execute("SELECT code, name FROM resource_node WHERE id = %s", (zrow[0] if zrow else 0,))
    zres = cur.fetchone()
    conn.close()

    # ---------- ⑤ 算账 ----------
    print("═══ 搬运（机械臂）容量体检 ═══")
    print()
    print("【流程】%s / %s（id=%s）" % (wf_code, wf_name, wf_id))
    print("  搬运节点（protocol=Transfer*）：%d 个" % n_transfer)
    if args.include_internal:
        print("  ⚠ --include-internal 未实现细分，当前仍只统计 Transfer*")
    print()
    print("【单次搬运】近 %d 天 %d 条 Transfer 命令" % (args.days, n_cmd or 0))
    print("  p50 %s s / 均值 %s s / 最大 %s s" % (fmt(p50), fmt(avg_s), fmt(max_s)))
    print()
    print("【分区锁】最多路径共用的分区 = %s（%s），%d / %d 条路径都含它"
          % (zrow[0] if zrow else "—", (zres[1] if zres else "?"),
             paths_in_top_zone, paths_total))
    print("  ⇒ 该分区就是唯一的机械臂 ⇒ 所有搬运串行")
    print()

    if not avg_s:
        print("数据不足（近 %d 天没有 Transfer 记录），无法算容量。" % args.days)
        return 0

    capacity_per_min = 60.0 / float(avg_s)
    batch_serial_sec = n_transfer * float(avg_s)
    print("【容量】单臂 ≈ %.2f 次/分钟（60 / 均值 %.2f s）" % (capacity_per_min, float(avg_s)))
    print("【一批】%d 次搬运串行执行 ≈ %.1f 秒（不含设备内部工序）" % (n_transfer, batch_serial_sec))
    if run_rate_p50:
        print("【单 run 需求】实测搬运频率 p50 ≈ %.2f 次/分钟（最密 %.2f）"
              % (float(run_rate_p50), float(run_rate_max or 0)))
        max_parallel = capacity_per_min / float(run_rate_p50)
        print("【可并行 run 数】容量 / 单 run 需求 ≈ **%.1f 个**（>此数队列会无限增长）"
              % max_parallel)
    print()
    print("【等待超时】当前 LABOS_RAIL_WAIT_TIMEOUT_MS = %d ms ≈ %.0f 秒"
          % (args.wait_ms, args.wait_ms / 1000.0))
    if run_rate_p50:
        # 极端情况：K 个 run 同时要搬，第 K 个的平均排队 = (K-1) * 单次搬运耗时
        k_fit = int(args.wait_ms / 1000.0 / float(avg_s)) + 1
        print("  单次搬运 %.2fs ⇒ 排到第 %d 位仍能等到（%d × %.2fs ≈ %.0fs < %.0fs）"
              % (float(avg_s), k_fit, k_fit, float(avg_s), k_fit * float(avg_s),
                 args.wait_ms / 1000.0))
        print("  ⚠ 但这是**单轮**排队；若 K 个 run 持续供货（每个都在跑多段），队列只增不减 ⇒ 看上面的「可并行 run 数」")
    print()
    print("【实测并发】近 %d 天同时最多 %s 个 run 在跑；有搬运的 run %s 个"
          % (args.days, max_concurrent if max_concurrent is not None else "—",
             runs_with_transfer if runs_with_transfer is not None else "—"))
    if bulk_abort_artifact:
        print("  （另有 %d 个 run 时长 ≥8 小时、且 actual_end 挤在同一毫秒 —— 那是批量终止"
              "统一写成 now() 的产物，已剔除，否则会算出几十的假并发）" % bulk_abort_artifact)
    print()
    print("结论：")
    if run_rate_p50:
        demand2 = 2 * float(run_rate_p50)
        if max_parallel >= 4:
            print("  ✅ 按实测均值，4 个 run 并行仍在容量内；但要盯 Transfer 长尾（最大 %ss）。" % fmt(max_s))
        elif max_parallel >= 2:
            print("  ⚠ 只能稳定支持约 %.1f 个 run 并行；4 路并行会排队，建议限制并发或抬高等待超时。"
                  % max_parallel)
        else:
            print("  🔴 只能稳定支持约 **%.1f 个** run 并行：2 个 run 的需求 ≈ %.2f 次/分钟，"
                  "已超过容量 %.2f 次/分钟" % (max_parallel, demand2, capacity_per_min))
            print("     ⇒ 要么串行跑（并发门禁 WorkflowRunGate 默认就是这么做的），"
                  "要么降频（减少 Transfer 次数 / 合并搬运），单靠抬高等待超时治标不治本。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
