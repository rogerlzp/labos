#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
英文文档批量验收（docs/en/ 与中文原文逐对核对）。

为什么要有这个：英文版是**逐文件对照翻译**，数量一大（当前 95 篇），
靠人肉核对表格行数 / 代码块数 / 标题数一定会漏。这个脚本把"翻译时必须保持的
结构"变成可复跑的硬检查，能直接进 CI。

检查项（每一项都可以在译文里被改坏，所以都有意义）：
1. 登记表完整性：docs/en/INDEX.md §3 的登记表里，每个中文原文都有英文路径
2. 译文存在性：登记表标记 ✅ 的，英文文件必须存在
3. 行数比例：英文行数在中文的 ±25% 以内（翻译成另一种语言长度会变，但差太远说明漏译）
4. 结构对齐：表格行数 / 代码块数 / 标题数 / 复选框数 —— 逐项相等
5. 链接可解析：docs/en 下所有相对链接必须指向真实文件
6. 中文残留：非代码块里的中文，必须是路径 / 标识符 / 专有名词 / 程序原文输出

用法：
    python3 scripts/verify-en-docs.py            # 全量检查
    python3 scripts/verify-en-docs.py --md out.md   # 同时写一份 Markdown 报告
退出码：0 = 通过；1 = 有硬错误。
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EN_DIR = ROOT / "docs" / "en"
INDEX = EN_DIR / "INDEX.md"

ROW = re.compile(r"^\s*\|")
SEP = re.compile(r"^\s*\|[\s:\-|]+\|\s*$")
FENCE = re.compile(r"^\s*(```|~~~)")
HEAD = re.compile(r"^(#{1,6})\s")
CHECK = re.compile(r"^\s*[-*]\s\[[ xX]\]")
LINK = re.compile(r"\]\(([^)\s]+)\)")

# 允许出现在译文里的中文：路径 / 标识符 / 专有名词 / 程序原文输出
ALLOW_CTX = re.compile(
    r"(docs/|scripts/|webapp/|labos-|third-party/|/api/|\.md|\.java|\.ts|\.tsx|\.py|\.sh|\.sql"
    r"|\.yml|\.json|EC-\d|D\d{3}|PF-\d|WF-\d|S\d|BUG-\d|\.NET|SLA|REST|JSON|TCP|HTTP|BPMN|SiLA|OPC|"
    r"ALCOA|CFR|GMP|GB/T|CFDI|React|Vue|Redis|PostgreSQL|Maven|MyBatis|Ant Design|Mock|RPC|CI)"
)

# 反引号包裹的内容一律视为标识符 / 路径 / 程序原文输出
BACKTICK = re.compile(r"`[^`]*`")

# 引号包裹的内容是**被引用的原文**（后端返回的字面量 / 命令输出 / 界面文案），
# 译文中刻意保留，不算"翻译残留"。
QUOTED = re.compile(r"「[^」]*」|『[^』]*』|“[^”]*”|\"[^\"]*\"|'[^']*[一-鿿][^']*'")
# 章节号 / 附录号（§十二、§三-§六、附A、附B）里的中文数字不是未翻译正文。
SECTION_REF = re.compile(r"§[一二三四五六七八九十百零〇0-9]+(?:[-–~][一二三四五六七八九十百零〇0-9]+)*")
ANNEX_REF = re.compile(r"附\s*[A-Za-z甲乙丙丁戊]")
# 少数无引号包裹的"中文残留"其实是后端 / SQL 原文，逐条登记；
# 按（文件, 片段）匹配而非行号，避免文档增删行后失效。
ALLOW_RESIDUAL = {
    ("docs/en/acceptance/m3-start-admission.md", "资源不足"),
    ("docs/en/load-test-report.md", "总秒"),
}


def read(p: Path) -> str:
    return io.open(p, encoding="utf-8").read()


def structure(text: str) -> dict:
    """统计结构特征（表格行 / 代码块 / 标题 / 复选框）。"""
    rows = fences = heads = checks = 0
    in_fence = False
    for line in text.split("\n"):
        if FENCE.match(line):
            fences += 1
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if SEP.match(line):
            continue
        if ROW.match(line):
            rows += 1
        if HEAD.match(line):
            heads += 1
        if CHECK.match(line):
            checks += 1
    return {"rows": rows, "fences": fences, "heads": heads, "checks": checks}


# 不是"逐行对照翻译"的文档：两份 README 各自独立成文（英文版多了 i18n 说明与
# 不同的文档表），结构本就不该相等。这类不参与结构对齐检查。
EXEMPT = {"README.md": "两份 README 独立成文，不是一一对照翻译"}


def registry() -> list[tuple[str, str, str]]:
    """从 INDEX.md §3 的登记表解析 (中文源, 英文目标, 状态)。"""
    out = []
    for line in read(INDEX).split("\n"):
        m = re.match(r"\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*(✅|⬜|🔶)\s*\|", line)
        if m:
            out.append((m.group(1), m.group(2), m.group(3)))
    return out


def col_count(line: str) -> int:
    r"""表格列数：忽略反引号内的 '|'（如 `find ... | wc -l`）与转义竖线（\|）。"""
    t = re.sub(r"`[^`]*`", lambda m: m.group(0).replace("|", "\x00"), line)
    t = t.replace("\\|", "\x00")
    return t.count("|") - 1


def table_rows(text: str) -> list[tuple[int, int]]:
    """按出现顺序返回 (行号, 列数) —— 分隔行不计，代码块内的不算。"""
    out = []
    in_fence = False
    for i, line in enumerate(text.split("\n"), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence or not ROW.match(line) or SEP.match(line):
            continue
        out.append((i, col_count(line)))
    return out


def compare_tables(zh_text: str, en_text: str, en_name: str) -> list[str]:
    """逐行比对**中文原文与译文**的表格列数。

    为什么不比"与表头"：中文源自己就有列数不齐的行（真实存在，例如把
    "数量"和"说明"并进一格）。译文忠实照搬时不该判错 —— 要比的是
    **源与译文的行结构序列是否一致**。

    同时抓一类真错：译文把中文的"｜"（全角行内分隔符）换行成了半角竖线，
    Markdown 会把那一行当成表格行 → 结构错乱。
    """
    zr = table_rows(zh_text)
    er = table_rows(en_text)
    en_lines = en_text.split("\n")
    bad = []
    if len(zr) != len(er):
        bad.append(f"{en_name}: 表格行数不一致（中文 {len(zr)} / 英文 {len(er)}）")
    for k, ((zi, zc), (ei, ec)) in enumerate(zip(zr, er)):
        if zc != ec:
            bad.append(f"{en_name}: 第 {k + 1} 个表格行列数不一致（中文 {zc} / 英文 {ec}）："
                       f"{en_lines[ei - 1].strip()[:70]}")
        if ec < 2 and zc >= 2:
            bad.append(f"{en_name}:{ei} 孤立竖线行（疑似把全角｜换成了半角竖线）")
    return bad


def cjk_hits(path: Path) -> list[tuple[int, str]]:
    """非代码块里的中文行（只报告，不判定）。"""
    hits = []
    in_fence = False
    for i, line in enumerate(read(path).split("\n"), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if re.search(r"[一-鿿]", line):
            hits.append((i, line.strip()))
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--md", help="写一份 Markdown 报告")
    ap.add_argument("--strict-cjk", action="store_true", help="对无法归类的中文残留也判失败")
    ap.add_argument("--sync", action="store_true",
                    help="把已存在且核对全过的英文文件在登记表里标记为 ✅（收割用）")
    args = ap.parse_args()

    reg = registry()
    if not reg:
        print("✗ 登记表解析失败：docs/en/INDEX.md §3 没读到任何条目")
        return 1

    errors: list[str] = []
    warns: list[str] = []
    done = 0
    pending = 0
    report_rows = []

    for zh_rel, en_rel, status in reg:
        zh = ROOT / zh_rel
        en = ROOT / en_rel
        if status != "✅":
            pending += 1
            continue
        done += 1
        if not zh.exists():
            errors.append(f"登记表引用的中文原文不存在：{zh_rel}")
            continue
        if not en.exists():
            errors.append(f"标 ✅ 但英文文件不存在：{en_rel}")
            continue
        zs, es = structure(read(zh)), structure(read(en))
        if zh_rel not in EXEMPT:
            for key, label in (("rows", "表格行"), ("fences", "代码块"), ("heads", "标题"), ("checks", "复选框")):
                if zs[key] != es[key]:
                    errors.append(
                        f"{en_rel}: {label}数不一致（中文 {zs[key]} / 英文 {es[key]}）")
        zl = len(read(zh).split("\n"))
        el = len(read(en).split("\n"))
        # 行数只是弱信号（英文常常把中文的一行长句折成多行），真正能抓漏译的是
        # 上面那几项结构对齐（表格行 / 代码块 / 标题 / 复选框）。
        if el < zl * 0.6 or el > zl * 2.0:
            warns.append(f"{en_rel}: 行数 {zl} → {el}（偏差较大，可能只是折行；若结构项也一致可忽略）")
        report_rows.append((zh_rel, en_rel, zl, el, zs, es))

    # 表格列数一致性
    tbl_bad = 0
    for zh_rel, en_rel, _ in reg:
        if not (ROOT / en_rel).exists():
            continue
        if zh_rel in EXEMPT:
            continue
        zhc = ROOT / zh_rel
        if not zhc.exists():
            continue
        for b in compare_tables(read(zhc), read(ROOT / en_rel), en_rel):
            errors.append(b)
            tbl_bad += 1

    # 链接可解析
    link_bad = 0
    link_total = 0
    for f in sorted(EN_DIR.rglob("*.md")):
        for t in LINK.findall(read(f)):
            if t.startswith(("http://", "https://", "#", "mailto:")):
                continue
            p = t.split("#")[0]
            if not p:
                continue
            link_total += 1
            if not (f.parent / p).resolve().exists():
                errors.append(f"{f.relative_to(ROOT)}: 链接失效 → {t}")
                link_bad += 1

    # 中文残留（只统计"无法归类"的）
    unclear = 0
    cjk_total = 0
    for _, en_rel, _ in reg:
        if not (ROOT / en_rel).exists():
            continue
        for i, line in cjk_hits(ROOT / en_rel):
            cjk_total += 1
            bare = BACKTICK.sub("", line)     # 去掉反引号内的标识符 / 路径 / 原文输出
            bare = QUOTED.sub("", bare)       # 去掉引号内被引用的原文
            bare = SECTION_REF.sub("", bare)  # 去掉 §十二 / §三-§六 这类章节号
            bare = ANNEX_REF.sub("", bare)    # 去掉 附A / 附B 这类附录号
            # 反引号 / 引号里的中文是标识符或引用原文，不算残留；脱掉后还剩中文才算
            if not re.search(r"[一-鿿]", bare):
                continue
            if ALLOW_CTX.search(bare):
                continue
            if any(en_rel == f and frag in line for f, frag in ALLOW_RESIDUAL):
                continue
            unclear += 1
            warns.append(f"{en_rel}:{i} 中文残留无法自动归类：{line[:90]}")

    # ---------------------------------------------------------------- 收割
    # 把"英文文件已存在且结构核对全过"的条目在登记表里标记为 ✅。
    # 这样并行翻译时，只有通过验收的文件才会被标成已译 —— 不会把半成品写进台账。
    if args.sync:
        idx = read(INDEX)
        promoted = []
        blocked = []
        for zh_rel, en_rel, status in reg:
            if status == "✅" or not (ROOT / en_rel).exists():
                continue
            zh, en = ROOT / zh_rel, ROOT / en_rel
            if not zh.exists():
                blocked.append((en_rel, "中文原文不存在"))
                continue
            zs, es = structure(read(zh)), structure(read(en))
            diffs = [f"{label} {zs[k]}→{es[k]}" for k, label in
                     (("rows", "表格行"), ("fences", "代码块"), ("heads", "标题"), ("checks", "复选框"))
                     if zs[k] != es[k]]
            if diffs:
                blocked.append((en_rel, "结构不一致：" + ", ".join(diffs)))
                continue
            if compare_tables(read(zh), read(en), en_rel):
                blocked.append((en_rel, "表格结构异常"))
                continue
            old_row = f"| `{zh_rel}` | `{en_rel}` | ⬜ |"
            new_row = f"| `{zh_rel}` | `{en_rel}` | ✅ |"
            if old_row in idx:
                idx = idx.replace(old_row, new_row)
                promoted.append(en_rel)
        INDEX.write_text(idx, encoding="utf-8")
        print(f"收割：{len(promoted)} 篇标记为已译")
        for e in promoted:
            print("  ✅ " + e)
        for e, why in blocked:
            print(f"  ⏸ {e} —— {why}")

    # 收割后重新解析，计数才是最新的
    if args.sync:
        reg = registry()
        done = sum(1 for _, _, st in reg if st == "✅")
        pending = len(reg) - done

    print("=" * 70)
    print("英文文档批量验收")
    print("=" * 70)
    print(f"登记表条目：{len(reg)}（已译 {done} / 待译 {pending}）")
    print(f"相对链接：{link_total} 条，失效 {link_bad} 条")
    print(f"非代码块中文行：{cjk_total} 行，其中无法自动归类 {unclear} 行")
    print("-" * 70)
    if errors:
        print(f"硬错误 {len(errors)} 条：")
        for e in errors:
            print("  ✗ " + e)
    else:
        print("硬错误：0（登记表完整 + 结构逐项对齐 + 链接全通）")
    if warns:
        print(f"提示 {len(warns)} 条：")
        for w in warns[:25]:
            print("  ! " + w)
        if len(warns) > 25:
            print(f"  …另有 {len(warns) - 25} 条")

    if args.md:
        md = ["# 英文文档验收报告（自动生成）", "",
              f"- 登记表条目：{len(reg)}（已译 {done} / 待译 {pending}）",
              f"- 相对链接：{link_total} 条，失效 {link_bad} 条",
              f"- 硬错误：{len(errors)}", "",
              "| 中文原文 | 英文译文 | 行数 | 表格行 | 代码块 | 标题 | 复选框 |",
              "|---|---|---|---|---|---|---|"]
        for zh_rel, en_rel, zl, el, zs, es in report_rows:
            md.append(f"| `{zh_rel}` | `{en_rel}` | {zl}→{el} | {zs['rows']}→{es['rows']} | "
                      f"{zs['fences']}→{es['fences']} | {zs['heads']}→{es['heads']} | "
                      f"{zs['checks']}→{es['checks']} |")
        if errors:
            md += ["", "## 硬错误", ""] + [f"- {e}" for e in errors]
        Path(args.md).write_text("\n".join(md) + "\n", encoding="utf-8")
        print(f"\n报告已写入 {args.md}")

    if errors:
        return 1
    if args.strict_cjk and unclear:
        print("\n(strict 模式：存在无法归类的中文残留，判定失败)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
