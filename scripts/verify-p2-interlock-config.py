#!/usr/bin/env python3
"""P2 互锁配置完整性预检（上电/流程发布前跑一次）。

检查四类必需配置是否已落库，缺了打印「缺失」并以非 0 退出：
  1. rail_zone / transfer_rail_zone / rail_zone_exemption：轨道分区（路径→分区集，或豁免）
  2. stirrer_dispenser_pair：搅拌—分液配对
  3. device_fact(turntable)：提取仪转盘事实（L1）
  4. waiting_interaction：L5 等待交互投影（有交互节点时）

用法（两种都行，脚本会自己读 env 文件，不依赖 shell 的 export 习惯）：
  python3 scripts/verify-p2-interlock-config.py
  set -a; . /tmp/labos-env.txt; set +a; python3 scripts/verify-p2-interlock-config.py

2026-11-15 踩坑记录：/tmp/labos-env.txt 里是裸 `KEY=value`（**没有 export**）。
`source` 只设 shell 局部变量，子进程 python 读不到 ⇒ 密码为空 ⇒ 认证阶段报
`fe_sendauth: no password supplied`，看起来像"环境变量缺失"其实是没导出。
本脚本因此自己解析 env 文件；显式环境变量优先于文件值。
"""
import os
import sys

import psycopg2

# env 文件候选（先看 LABOS_ENV_FILE，再默认路径）
ENV_FILE_CANDIDATES = ("/tmp/labos-env.txt",)

# 每项的归属层：决定"缺了会拦"还是"缺了会跳过"
CHECKS = [
    ("L3", "轨道分区 rail_zone", "SELECT count(*) FROM rail_zone"),
    ("L3", "路径→分区集 transfer_rail_zone", "SELECT count(*) FROM transfer_rail_zone"),
    ("L4", "搅拌—分液配对 stirrer_dispenser_pair", "SELECT count(*) FROM stirrer_dispenser_pair"),
    ("L1", "设备事实 device_fact", "SELECT count(*) FROM device_fact"),
    ("L5", "有效等待投影 waiting_interaction", "SELECT count(*) FROM waiting_interaction"),
    # 关键语义检查（不是简单 count>0）
    ("L1", "提取仪转盘事实(turntable)",
     "SELECT count(*) FROM device_fact WHERE fact_type='turntable'"),
]

# 只提示、**不计入缺失**的项：0 行是正常状态，报成"缺失"会把好配置也说成缺。
#   rail_zone_exemption：只有"某条路径确实无冲突、不需要分区锁"时才要落一行；
#   路径已全部有分区集时，0 行恰恰是正确状态（2026-11-15 修：原先算作 L3 缺失，
#   导致 L3 明明已配置却仍打印"L3 fail-closed，把不该拦的路径全拦死"）。
INFOS = [
    ("L3", "分区豁免 rail_zone_exemption", "SELECT count(*) FROM rail_zone_exemption"),
]

# 缺配置时各层的实际行为 —— 必须与代码一致，别让现场按错误方向排查。
# 口径（2026-11-15 二次复核，含 abd5a70 把 finalRecheck 收窄到分液方法）：
#   L3 = fail-closed（RailZoneGuard 无分区集且无豁免 ⇒ EC-INTERLOCK-RAIL 拒绝）
#   L1 = 静默跳过（无 turntable 事实 ⇒ 非 L1 目标）
#   L5 = 静默跳过（无交互上下文 ⇒ 非 L5 目标）
#   L4 = **两段语义**：checkBeforeDispense 对分液/移液方法无配对 ⇒ 拒绝并 paused_fault；
#        finalRecheck（物理下发前）无配对 ⇒ 跳过。⇒ 配对表 0 行时客户流程会卡在
#        第一个分液节点，不是"静默空转"。
LAYER_EFFECT = {
    "L3": "L3 轨道分区 fail-closed：未配置且未豁免的搬运路径一律拒绝（EC-INTERLOCK-RAIL）",
    "L1": "L1 转盘守卫无 device_fact(turntable) ⇒ **静默跳过**（不拦、不告警）",
    "L4": ("L4 搅拌—分液未配对 ⇒ **分液/移液命令**（Aliquot/Dispense/Pipette/TransferLiquid）"
           "下发前**拒绝** EC-STATE-UNKNOWN 并 paused_fault 转人工；其它命令不拦"),
    "L5": "L5 无 waiting_interaction 投影 ⇒ **静默跳过**（不拦、不告警）",
}


def parse_env_file(path):
    """读 `KEY=VALUE` / `export KEY=VALUE` 两行式 env 文件（不执行、不展开）。"""
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
    """显式环境变量优先，其次 env 文件。"""
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
        # PGPASSWORD 是 psql/python 生态的通用兜底
        "pass": pick("LABOS_DB_PASS", "LABOS_DB_PASSWORD", "PGPASSWORD"),
        "env_file": next((p for p in (os.environ.get("LABOS_ENV_FILE"),
                                      *ENV_FILE_CANDIDATES)
                          if p and os.path.isfile(p)), None),
    }


def main():
    cfg = load_settings()
    if not cfg["pass"]:
        print("✗ 没有数据库密码：LABOS_DB_PASS / LABOS_DB_PASSWORD / PGPASSWORD 都为空。", file=sys.stderr)
        print("  env 文件里是裸 KEY=value（无 export），直接 source 不会传给子进程。二选一：", file=sys.stderr)
        print("    set -a; . /tmp/labos-env.txt; set +a; python3 scripts/verify-p2-interlock-config.py",
              file=sys.stderr)
        print("    LABOS_DB_PASS='...' python3 scripts/verify-p2-interlock-config.py", file=sys.stderr)
        return 2

    try:
        conn = psycopg2.connect(host=cfg["host"], port=cfg["port"], dbname=cfg["name"],
                                user=cfg["user"], password=cfg["pass"],
                                connect_timeout=10)
    except psycopg2.OperationalError as e:
        print(f"✗ 连不上数据库 {cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['name']}", file=sys.stderr)
        print(f"  原因：{str(e).strip()}", file=sys.stderr)
        print(f"  密码来源：{'环境变量' if os.environ.get('LABOS_DB_PASS') else (cfg['env_file'] or '默认兜底')}",
              file=sys.stderr)
        return 2

    gaps = []
    missing_layers = set()
    try:
        conn.autocommit = True
        print("── P2 互锁配置完整性预检 ──")
        for layer, label, sql in CHECKS:
            try:
                with conn.cursor() as cur:
                    cur.execute(sql)
                    n = cur.fetchone()[0]
                ok = n > 0
                print(f"  {'✓' if ok else '✗ 缺失'} [{layer}] {label}: {n}")
                if not ok:
                    gaps.append(label)
                    missing_layers.add(layer)
            except Exception as e:  # noqa: BLE001
                print(f"  ✗ 查询失败 [{layer}] {label}: {e}")
                gaps.append(label)
                missing_layers.add(layer)
        for layer, label, sql in INFOS:
            try:
                with conn.cursor() as cur:
                    cur.execute(sql)
                    n = cur.fetchone()[0]
                print(f"  · [{layer}] {label}: {n}（提示，不计入缺失）")
            except Exception as e:  # noqa: BLE001
                print(f"  · 查询失败 [{layer}] {label}: {e}")
    finally:
        conn.close()

    print()
    if not gaps:
        print("结论：基础配置已就绪（仍建议逐条核对真机分区覆盖完整运动包络）。")
        return 0

    print(f"结论：{len(gaps)} 项缺失。缺配置的效果**不是统一的 fail-closed**：")
    for layer in ("L3", "L4", "L1", "L5"):
        if layer in missing_layers:
            print(f"  · {LAYER_EFFECT[layer]}")
    if "L3" in missing_layers:
        print("  即：L3 还没配全 —— 未配置的搬运路径会被拦死（这是**该拦的**，不是 bug）。")
    if "L4" in missing_layers:
        print("  即：配对表 0 行 ⇒ 客户流程会卡在**第一个分液节点**（EC-STATE-UNKNOWN → 转人工）。")
    if "L1" in missing_layers or "L5" in missing_layers:
        print("  即：L1/L5 仍空转（拿不到事实/投影就跳过，该拦的没拦）。")
    print("  处置：落客户现场的分区映射/配对/事实；已人工验证无冲突的路径可落 rail_zone_exemption。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
