#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把一次**真实跑完的运行**渲染成演示影片（数据驱动回放，不用重跑）。

为什么这样做：
  · 客户/评审要看"这条线怎么动起来" —— 实时录屏只能看一次、还受环境抖动影响；
    回放可以反复放、可以加速、可以配字幕。
  · 数据全部来自**真实执行记录**（run_node 时间线 / transfer_transaction 搬运 /
    position_scan_evidence 扫码证据 / device_event 设备事件）⇒ 画面不是编的。

画面（1920×1080）：
  上：标题 + 统计（状态 / 已用时 / 节点完成 / 搬运次数 / 扫码证据）
  中：工位示意（耗材栈 · 4 台提取仪 · 6 台分液器 · 机械臂）＋ **板在设备间移动**
  下：阶段进度条（取磁套 → 6 路分液 → 内部洗脱 → 回取产物板）
  右：事件流（扫码确认 / 机械臂搬运 / 设备事件）+ 最近完成节点

实现要点：matplotlib 动画里 **artist 只创建一次、每帧只改属性**（每帧 add_patch 会无限叠加）。

用法：
  python3 scripts/make-run-film.py --run 1001706
  python3 scripts/make-run-film.py --run 1001706 --speed 90 --fps 24 \
          --out docs/media/提取线仿真回放.mp4
"""
import argparse
import os
import subprocess
import sys
from datetime import datetime, timedelta

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager, patches

BG, PANEL, LINE, FG, DIM = "#0d1014", "#161b22", "#30363d", "#e6edf3", "#8b949e"
BLUE, GREEN, YELLOW, RED = "#58a6ff", "#3fb950", "#d29922", "#f85149"


def pick_font():
    prefer = ["PingFang SC", "Heiti TC", "Heiti SC", "Songti SC", "Arial Unicode MS",
              "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Zen Hei", "SimHei"]
    have = {f.name for f in font_manager.fontManager.ttflist}
    for name in prefer:
        if name in have:
            return name
    return "DejaVu Sans"


FONT = pick_font()
plt.rcParams["font.family"] = FONT
plt.rcParams["axes.unicode_minus"] = False


def read_env_file(path="/tmp/labos-env.txt"):
    vals = {}
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
                vals[k] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    return vals


_ENV = read_env_file()


def _cfg(name, default=""):
    return os.environ.get(name) or _ENV.get(name) or default


DB = dict(host=_cfg("LABOS_DB_HOST", "192.168.124.2"),
          port=_cfg("LABOS_DB_PORT", "5432"),
          dbname=_cfg("LABOS_DB_NAME", "labdb"),
          user=_cfg("LABOS_DB_USER", "labos"),
          password=_cfg("LABOS_DB_PASS", ""))


def q(sql):
    env = dict(os.environ, PGPASSWORD=DB["password"])
    cmd = ["psql", "-h", DB["host"], "-p", DB["port"], "-U", DB["user"],
           "-d", DB["dbname"], "-t", "-A", "-F", "\t", "-c", sql]
    out = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("psql 失败：" + out.stderr.strip()[:300])
    return [l.split("\t") for l in out.stdout.strip().splitlines() if l]


def ts(s):
    return datetime.fromisoformat(s) if s else None


def load(run_id):
    r = q("SELECT r.id, w.code, r.status, r.actual_start, r.actual_end "
          "FROM run r JOIN workflow w ON w.id=r.workflow_id WHERE r.id=%d" % run_id)
    if not r:
        raise SystemExit("找不到 run %d" % run_id)
    run = {"id": r[0][0], "code": r[0][1], "status": r[0][2],
           "start": ts(r[0][3]), "end": ts(r[0][4])}
    # 用**步骤节点**（level=1）做进度统计：顶层节点（level=0）是并行分支的容器，
    # 它的 actual_end 要等整条分支跑完 ⇒ 用它统计会一直显示 0。
    nodes = [{"name": x[0], "status": x[1], "seq": int(x[2]), "start": ts(x[3]), "end": ts(x[4])}
             for x in q("SELECT coalesce(input->>'method', name), status, seq, "
                        "actual_start, actual_end FROM run_node "
                        "WHERE run_id=%d AND level=1 ORDER BY actual_start NULLS LAST" % run_id)]
    transfers = [{"plate": x[0], "src": int(x[1]), "dst": int(x[2]), "t0": ts(x[3]), "t1": ts(x[4])}
                 for x in q("SELECT plate_barcode, source_position_id, target_position_id, "
                            "created_at, confirmed_at FROM transfer_transaction "
                            "WHERE run_id=%d AND status='CONFIRMED' ORDER BY id" % run_id)]
    scans = [{"pos": int(x[0]), "plate": x[1], "at": ts(x[2])}
             for x in q("SELECT source_position_id, expected_barcode, observed_at "
                        "FROM position_scan_evidence WHERE run_id=%d ORDER BY id" % run_id)]
    events = [{"dev": x[0], "type": x[1], "at": ts(x[2])}
              for x in q("SELECT device_code, event_type, created_at FROM device_event "
                         "WHERE created_at >= '%s' AND created_at <= '%s' ORDER BY id"
                         % (run["start"].isoformat(), run["end"].isoformat()))]
    points = {int(x[0]): {"code": x[1], "dev": x[2]}
              for x in q("SELECT dp.point_resource_id, rn.code, dev.code "
                         "FROM device_point dp JOIN resource_node rn ON rn.id=dp.point_resource_id "
                         "JOIN resource_node dev ON dev.id=dp.device_resource_id "
                         "WHERE dev.parent_id=1000505")}
    return {"run": run, "nodes": nodes, "transfers": transfers, "scans": scans,
            "events": events, "points": points}


LAYOUT = {
    "STACK1": (0.075, 0.665), "P_AUTO-1": (0.215, 0.665), "P_AUTO-2": (0.335, 0.665),
    "P_AUTO-3": (0.455, 0.665), "P_AUTO-4": (0.575, 0.665), "D9608-1": (0.700, 0.665),
    "DISP1": (0.155, 0.395), "DISP2": (0.275, 0.395), "DISP3": (0.395, 0.395),
    "DISP4": (0.515, 0.395), "DISP5": (0.635, 0.395), "DISP6": (0.720, 0.395),
}
ARM_HOME = (0.075, 0.545)
PLATE_COLORS = ["#d29922", "#e3b341", "#f0b72f", "#bb8009"]


def dev_of(d, pos_id):
    return d["points"].get(pos_id, {}).get("dev")


def plate_state(d, plate, now):
    for tr in d["transfers"]:
        if tr["plate"] != plate:
            continue
        if tr["t0"] <= now <= tr["t1"]:
            sx, sy = LAYOUT.get(dev_of(d, tr["src"]), ARM_HOME)
            dx, dy = LAYOUT.get(dev_of(d, tr["dst"]), ARM_HOME)
            f = 0.0 if tr["t1"] == tr["t0"] else \
                (now - tr["t0"]).total_seconds() / (tr["t1"] - tr["t0"]).total_seconds()
            return sx + (dx - sx) * f, sy + (dy - sy) * f
        if now > tr["t1"]:
            return LAYOUT.get(dev_of(d, tr["dst"]), ARM_HOME)
    for tr in d["transfers"]:
        if tr["plate"] == plate:
            return LAYOUT.get(dev_of(d, tr["src"]), ARM_HOME)
    return ARM_HOME


def arm_xy(d, now):
    best = None
    for tr in d["transfers"]:
        if tr["t0"] <= now <= tr["t1"]:
            best = tr
            break
        if tr["t0"] <= now and (best is None or tr["t0"] > best["t0"]):
            best = tr
    if not best:
        return ARM_HOME
    sx, sy = LAYOUT.get(dev_of(d, best["src"]), ARM_HOME)
    dx, dy = LAYOUT.get(dev_of(d, best["dst"]), ARM_HOME)
    if best["t1"] == best["t0"]:
        return dx, dy
    f = max(0.0, min(1.0, (now - tr_t0(best)).total_seconds() /
                       (best["t1"] - best["t0"]).total_seconds()))
    return sx + (dx - sx) * f, sy + (dy - sy) * f


def tr_t0(tr):
    return tr["t0"]


def build_time_map(d, gap_max=45.0, gap_keep=6.0):
    """把"长时间没动作"的空档折叠掉。

    为什么：一次真实运行 26 分钟里，有一大段是纯等待（模拟器在跑 400 秒的工艺），
    等速压缩后画面几乎不动 ⇒ 演示片看不到"发生了什么"。
    ⇒ 保留所有**有动作的时刻**（搬运/扫码/节点边界），把超过 gap_max 的空档
    压成 gap_keep 秒 ⇒ 画面里每个动作都看得清，又不丢事件。
    """
    marks = {d["run"]["start"], d["run"]["end"]}
    for tr in d["transfers"]:
        marks.add(tr["t0"]); marks.add(tr["t1"])
    for sc in d["scans"]:
        marks.add(sc["at"])
    for nd in d["nodes"]:
        if nd["start"]:
            marks.add(nd["start"])
        if nd["end"]:
            marks.add(nd["end"])
    for ev in d["events"]:
        marks.add(ev["at"])
    marks = sorted(m for m in marks if m is not None)
    segs = []          # (real_start, real_end, video_start, video_end)
    v = 0.0
    for i in range(len(marks) - 1):
        a, b = marks[i], marks[i + 1]
        real = (b - a).total_seconds()
        vd = real if real <= gap_max else gap_keep
        segs.append((a, b, v, v + vd))
        v += vd
    if not segs:
        segs = [(marks[0], marks[-1], 0.0, 1.0)]
    return segs, v


def video_time(segs, t):
    """真实时刻 → 影片时刻。"""
    for a, b, va, vb in segs:
        if a <= t <= b:
            span = (b - a).total_seconds()
            f = 0.0 if span <= 0 else (t - a).total_seconds() / span
            return va + (vb - va) * f
    return segs[-1][3]


def load_script(path):
    """吃 replay-run.py 产出的**机读剧本**（另一条线的产出）。

    为什么接：那份 JSON 里有「客户 8 阶段的分界 + 每个环节在做什么 + 缺口清单」，
    是**讲解词**；本脚本产出的是**空间与运动画面**。两者互补：
    剧本给"说什么"，画面给"在哪动"。
    """
    import json as _json
    with open(path, "r", encoding="utf-8") as f:
        return _json.load(f)


def build(d, speed, fps, out, script=None):
    run = d["run"]
    t0, t1 = run["start"], run["end"]
    span = max(1.0, (t1 - t0).total_seconds())
    segs, video_span = build_time_map(d)
    # speed 语义：越大越快。dur = 折叠后时间线长度 / 倍速。
    dur = max(10.0, video_span / max(0.5, speed))
    frames = max(2, int(dur * fps))
    print("真实时长 %.1f 分钟 → 折叠后 %.1f 秒 → 影片 %.1f 秒（%d 帧 @ %dfps）"
          % (span / 60, video_span, dur, frames, fps))

    fig = plt.figure(figsize=(19.2, 10.8), dpi=100)
    fig.patch.set_facecolor(BG)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.axis("off")
    ax.set_facecolor(BG)

    def txt(x, y, s, size=13, color=FG, weight="normal", ha="left", va="center"):
        return ax.text(x, y, s, fontsize=size, color=color, weight=weight, ha=ha, va=va)

    def box(x, y, w, h, fc=PANEL, ec=LINE):
        p = patches.FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.004,rounding_size=0.008",
                                    facecolor=fc, edgecolor=ec, linewidth=1)
        ax.add_patch(p)
        return p

    txt(0.028, 0.958, "LabOS 中控 · 高通量核酸提取工作站 · 单批标准流程（仿真回放）",
        25, "#ffffff", "bold")
    txt(0.028, 0.921, "run %s · 流程 %s · 真实执行记录回放"
        "（run_node / transfer_transaction / position_scan_evidence / device_event）"
        % (run["id"], run["code"]), 13, DIM)

    stat_val = []
    for i, label in enumerate(["状态", "已用时", "节点完成", "搬运次数", "扫码证据"]):
        x = 0.028 + i * 0.142
        box(x, 0.835, 0.132, 0.068)
        txt(x + 0.010, 0.883, label, 12, DIM)
        stat_val.append(txt(x + 0.010, 0.858, "-", 19, BLUE, "bold"))

    box(0.020, 0.300, 0.725, 0.505)
    txt(0.034, 0.782, "工位 · LAB-EXT-01（高通量核酸提取工作站）", 13, DIM)
    ax.plot([0.045, 0.700], [0.572, 0.572], color=LINE, linewidth=4, solid_capstyle="round")
    ax.plot([0.120, 0.700], [0.330, 0.330], color=LINE, linewidth=4, solid_capstyle="round")

    for dev, (x, y) in LAYOUT.items():
        w, h = 0.082, 0.082
        box(x - w / 2, y - h / 2, w, h)
        ax.add_patch(patches.Rectangle((x - w / 2, y + h / 2 - 0.010), w, 0.010,
                                       facecolor=BLUE, edgecolor="none"))
        txt(x, y + 0.012, dev, 10.5, FG, "bold", "center")
        sub = ("耗材栈 8 槽" if dev == "STACK1" else
               "交互位" if dev.startswith("P_AUTO") else
               "分液器" if dev.startswith("DISP") else "加液分液")
        txt(x, y - 0.024, sub, 9, DIM, "normal", "center")

    box(0.020, 0.030, 0.725, 0.245)
    txt(0.034, 0.252, "阶段进度", 13, DIM)
    stages = [("取磁套", 0.06, 0.22), ("6 路分液", 0.28, 0.44),
              ("内部洗脱", 0.44, 0.70), ("回取产物板", 0.70, 0.98)]
    if script and script.get("stages"):
        # 用剧本的阶段与真实时间边界（offsetMs/durationMs）⇒ 进度条与讲解词对齐
        total = max(1.0, float(script.get("totalMs") or span * 1000))
        st = []
        for s_ in script["stages"]:
            ns_ = s_.get("nodes") or []
            if not ns_:
                continue
            a = min((n.get("offsetMs") or 0) for n in ns_) / total
            b = max((n.get("offsetMs") or 0) + (n.get("durationMs") or 0) for n_ in [ns_] for n in ns_) / total
            st.append((s_.get("title", "")[:10], max(0.0, a), min(1.0, max(b, a + 0.02))))
        if st:
            stages = st
    prog_bar = ax.add_patch(patches.Rectangle((0.035, 0.185), 0.0, 0.022,
                                              facecolor=BLUE, edgecolor="none"))
    prog_head, = ax.plot([0.035], [0.205], marker="v", color=RED, markersize=9)
    stage_txts = []
    for name, a, b in stages:
        txt(0.035 + 0.69 * a, 0.232, name, 11, DIM)
        stage_txts.append(txt(0.035 + 0.69 * a, 0.160, "", 10, DIM))
    done_line = txt(0.035, 0.075, "", 12, DIM)
    last_node = txt(0.035, 0.048, "", 12, FG)

    box(0.760, 0.030, 0.218, 0.805)
    txt(0.774, 0.810, "事件流", 13, DIM)
    ev_texts = [txt(0.774, 0.770 - k * 0.036, "", 10.5, DIM) for k in range(10)]
    cur_node = txt(0.774, 0.115, "", 12, FG, "bold")
    cur_label = txt(0.774, 0.085, "", 11, DIM)
    gap_title = txt(0.774, 0.055, "", 11, DIM)
    gap_texts = [txt(0.774, 0.038 - k * 0.0, "", 10, YELLOW) for k in range(3)]

    plates = sorted({tr["plate"] for tr in d["transfers"]})
    plate_art = []
    for i, p in enumerate(plates):
        col = PLATE_COLORS[i % len(PLATE_COLORS)]
        pc = patches.FancyBboxPatch((0, 0), 0.040, 0.024,
                                     boxstyle="round,pad=0.002,rounding_size=0.005",
                                     facecolor=col, edgecolor="#f0b72f", linewidth=1.2)
        ax.add_patch(pc)
        pt = txt(0, 0, "", 9, "#f0b72f", "normal", "center")
        plate_art.append((pc, pt, p))

    arm_c = plt.Circle((0, 0), 0.014, facecolor=BLUE, edgecolor="#79c0ff", linewidth=1.5)
    ax.add_patch(arm_c)
    arm_t = txt(0, 0, "ARM1", 9, BLUE, "normal", "center")

    def draw(frame):
        now = t0 + timedelta(seconds=video_span * frame / max(1, frames - 1))
        el = (now - t0).total_seconds()
        done = sum(1 for nd in d["nodes"] if nd["end"] and nd["end"] <= now)
        trs = sum(1 for tr in d["transfers"] if tr["t1"] and tr["t1"] <= now)
        scs = sum(1 for sc in d["scans"] if sc["at"] and sc["at"] <= now)
        finished = frame >= frames - 1
        stat_val[0].set_text(run["status"] if finished else "运行中")
        stat_val[0].set_color(GREEN if finished else BLUE)
        stat_val[1].set_text("%d:%02d" % divmod(int(el), 60))
        stat_val[2].set_text("%d/%d" % (done, len(d["nodes"])))
        stat_val[3].set_text("%d/%d" % (trs, len(d["transfers"])))
        stat_val[4].set_text(str(scs))

        for pc, pt, p in plate_art:
            x, y = plate_state(d, p, now)
            pc.set_x(x - 0.020)
            pc.set_y(y - 0.062)
            pt.set_position((x, y - 0.050))
            pt.set_text(p.replace("SIM-PLATE-MAG-", "磁套"))
        ax_, ay = arm_xy(d, now)
        arm_c.set_center((ax_, ay))
        arm_t.set_position((ax_, ay - 0.026))

        prog = min(1.0, frame / max(1, frames - 1))
        prog_bar.set_width(0.69 * prog)
        prog_head.set_data([0.035 + 0.69 * prog], [0.205])

        for t, (_name, a, b) in zip(stage_txts, stages):
            if prog >= b:
                t.set_text("● 完成")
                t.set_color(GREEN)
            elif prog >= a:
                t.set_text("进行中")
                t.set_color(YELLOW)
            else:
                t.set_text("未开始")
                t.set_color(DIM)
        done_line.set_text("节点完成 %d/%d · 搬运 %d/%d · 扫码证据 %d 条"
                           % (done, len(d["nodes"]), trs, len(d["transfers"]), scs))
        last_node.set_text("已用时 %d 分 %d 秒（真实 %d 分 %d 秒）"
                           % (int(el) // 60, int(el) % 60, int(span) // 60, int(span) % 60))

        lines = []
        for sc in d["scans"]:
            if sc["at"] and sc["at"] <= now:
                lines.append((sc["at"], "扫码确认 %s @ %s" % (
                    sc["plate"].replace("SIM-PLATE-MAG-", "磁套"),
                    d["points"].get(sc["pos"], {}).get("code", "?"))))
        for tr in d["transfers"]:
            if tr["t0"] and tr["t0"] <= now:
                lines.append((tr["t0"], "机械臂搬运 %s：%s → %s" % (
                    tr["plate"].replace("SIM-PLATE-MAG-", "磁套"),
                    d["points"].get(tr["src"], {}).get("code", "?"),
                    d["points"].get(tr["dst"], {}).get("code", "?"))))
        for ev in d["events"]:
            if ev["at"] and ev["at"] <= now:
                lines.append((ev["at"], "设备事件 %s · %s" % (ev["dev"], ev["type"])))
        lines.sort(key=lambda x: x[0])
        tail = lines[-10:]
        for k, t in enumerate(ev_texts):
            if k < len(tail):
                t.set_text("• " + tail[k][1])
                t.set_color(FG)
            else:
                t.set_text("")
        if script:
            gaps = []
            for s_ in script.get("stages", []):
                for g in (s_.get("gaps") or [])[:1]:
                    gaps.append("%s：%s" % (s_.get("title", "")[:6], str(g)[:26]))
            for p_ in (script.get("problems") or [])[:2]:
                gaps.append("问题 %s：%s" % (p_.get("errorCode") or "", str(p_.get("say") or "")[:24]))
            gap_title.set_text("剧本要点 / 缺口清单" if gaps else "")
            for k, t in enumerate(gap_texts):
                t.set_text("· " + gaps[k] if k < len(gaps) else "")
        recent = [nd for nd in d["nodes"] if nd["end"] and nd["end"] <= now]
        if recent:
            last = recent[-1]
            cur_node.set_text("最近完成：" + last["name"][:16])
            cur_label.set_text("（%s）" % ("成功" if last["status"] == "completed" else last["status"]))
        return []

    from matplotlib.animation import FuncAnimation
    anim = FuncAnimation(fig, draw, frames=frames, interval=1000 / fps, blit=False)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    tmp = out if out.endswith(".mp4") else out + ".mp4"
    print("渲染 %d 帧（%d 秒 @ %dfps）→ %s" % (frames, dur, fps, tmp))
    anim.save(tmp, writer="ffmpeg", fps=fps)
    plt.close(fig)
    print("✓ 完成：%s（%.1f MB）" % (tmp, os.path.getsize(tmp) / 1e6))
    return tmp


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", type=int, required=True)
    ap.add_argument("--speed", type=float, default=70.0, help="加速倍数")
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--out", default="docs/media/提取线仿真回放.mp4")
    ap.add_argument("--script", default="",
                    help="replay-run.py 产出的机读剧本 JSON（讲解词/阶段边界/缺口清单）")
    a = ap.parse_args()
    script = load_script(a.script) if a.script and os.path.isfile(a.script) else None
    if a.script and script is None:
        print("⚠ 剧本文件不存在：%s（继续用内置阶段标签）" % a.script)
    build(load(a.run), a.speed, a.fps, a.out, script)
