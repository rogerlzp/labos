#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取线脚本的共享配置层：岛 / 设备编号 / 端口段。

**为什么要有这个文件**（2026-11-17）：
原来 5 个脚本各自把三样东西写死在代码里 ——
① 岛号 `LAB-EXT-01`；② 设备编号 `P_AUTO-1` / `DISP1` / `ARM1` / `STACK1`；
③ 端口 `19600` 起顺排。
⇒ 建**第二个**岛必然撞车：
   - 设备编号撞：`device_instance` 的唯一约束是 `UNIQUE (tenant_id, code)`，
     **不按岛隔离** ⇒ 两个岛不能用同名设备；
   - 端口撞：固定顺排**不查全局占用** ⇒ 实测撞过 4 组（DISP5 与 P_AUTO-1 同为 19608）
     ⇒ 两台设备连同一个端点 ⇒ 状态互相串台。
⇒ 抽成配置层并让端口**挑空闲的**，同一套脚本就能建多个岛。

环境变量：
  LABOS_ISLAND_CODE   岛编号（默认 LAB-EXT-01）
  LABOS_ISLAND_NAME   岛名称（默认 高通量核酸提取工作站岛）
  LABOS_DEV_TAG       设备编号的岛标记（默认空；例 `-02-` ⇒ P_AUTO-02-1 / DISP-02-1）
  LABOS_PORT_BASE     端口段起点（默认 19600；实际取端口时会跳过已被占用的）
"""

import os
import socket

ISLAND_CODE = os.environ.get("LABOS_ISLAND_CODE", "LAB-EXT-01")
ISLAND_NAME = os.environ.get("LABOS_ISLAND_NAME", "高通量核酸提取工作站岛")
DEV_TAG = os.environ.get("LABOS_DEV_TAG", "")
PORT_BASE = int(os.environ.get("LABOS_PORT_BASE", "19600"))
# 模拟器端口（设备实际被连的那个）与侧车自身监听端口 —— 两段都要避开别的岛。
SIM_PORT_BASE = int(os.environ.get("LABOS_SIM_PORT_BASE", "19620"))
SIDECAR_PORT_BASE = int(os.environ.get("LABOS_SIDECAR_PORT_BASE", "19700"))


def code_for(base, n=1):
    """设备编号 = 基础名 + 岛标记 + 序号。

    无标记时保持现场编号原样：P_AUTO-1 / DISP1 / ARM1 / STACK1；
    带标记（-02-）时：P_AUTO-02-1 / DISP-02-1 / ARM-02-1 / STACK-02-1。
    """
    if not DEV_TAG:
        return f"{base}{'-' if base == 'P_AUTO' else ''}{n}"
    return f"{base}{DEV_TAG}{n}"


def ext_codes():
    """4 台提取仪的设备编号。"""
    return [code_for("P_AUTO", i) for i in range(1, 5)]


def disp_codes():
    """6 台分液器的设备编号。"""
    return [code_for("DISP", i) for i in range(1, 7)]


def arm_code():
    return code_for("ARM", 1)


def stack_code():
    return code_for("STACK", 1)


def mag_plate_barcodes():
    """4 块磁套板的模拟条码（每个岛一套 ⇒ 两个岛不会抢同一块板）。

    ⚠ 为什么每岛一套：板在库里是**一板一位**（`uk_pos_plate`：UNIQUE(tenant_id, plate_id)）
    ⇒ 同一块板不能同时占两个岛的位置。原岛那 4 块板还占着原岛的槽位，
    新岛必须用**新条码**，否则放板会被唯一约束挡掉（实测：
    「[盘点] 板 … 位置账本写入失败」）。
    """
    tag = DEV_TAG.strip("-")
    mid = f"-{tag}" if tag else ""
    return [f"SIM-PLATE-MAG{mid}-{i}" for i in range(1, 5)]


def pool_code(base):
    """设备池编号：POOL-EXT / POOL-DISP-MAGNET / POOL-DISP-REAGENT。

    带岛标记时加后缀（POOL-EXT-02），避免与既有岛的同名池冲突。
    ⚠ 标记形如 `-02-`（前后都有横杠，给设备编号用的）⇒ 这里要**去掉尾横杠**，
    否则拼出 `POOL-EXT-02-` 这种带尾巴的编号。
    """
    tag = DEV_TAG.strip("-")
    return f"{base}-{tag}" if tag else base


def port_is_free(port):
    """这个端口现在能 bind 吗（OS 层面最权威）。"""
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def pick_ports(count, base=None, used=()):
    """挑 count 个**空闲**端口（跳过已登记的与 OS 上已占用的）。

    ⚠ 必须查占用：固定顺排会与既有岛撞端口（实测撞过 4 组 ⇒ 状态串台）。
    """
    base = PORT_BASE if base is None else base
    used = set(int(u) for u in used)
    out = []
    p = base
    while len(out) < count and p < base + 1000:
        if p not in used and port_is_free(p):
            out.append(p)
        p += 1
    if len(out) < count:
        raise RuntimeError(f"从 {base} 起找不到 {count} 个空闲端口")
    return out
