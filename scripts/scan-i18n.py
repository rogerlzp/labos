#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n 台账扫描器（英文版工程的可复跑体检脚本）。

做四件事，每件都给出**可复现的数字**，不是"大概翻译了多少"：

1. **语言包对齐**：zh / en 两个包 key 是否一一对应；英文包里有没有漏掉的中文；
   同一 key 的插值占位符（{count} 之类）是否两边一致。
2. **领域 code 覆盖**：后端词表 {@code StatusVocabulary.java} 里的每个状态 code /
   暂停类型 / 扣留类型 / 扣留作用域，英文表 {@code i18n/codes.ts} 里是否都有条目。
   ★ 这是"英文界面显示裸 code"的唯一防线 —— 后端加一个状态而前端没补英文，
   现场会看到 `paused_unknown` 这种机器码。
3. **残留中文台账**：webapp/src 里还剩多少**面向用户**的中文字符（注释不算）。
   给出每个文件的数量，用来排后续批次。
4. **退出码**：有硬错误（语言包不对齐 / code 没覆盖 / 英文包混进中文）就非 0 退出，
   可以直接进 CI 门禁。

用法：
    python3 scripts/scan-i18n.py                # 打印台账
    python3 scripts/scan-i18n.py --md out.md    # 同时写一份 Markdown 台账
    python3 scripts/scan-i18n.py --strict       # 残留中文 > 0 也算失败

设计取舍：
- 语言包用 **esbuild + node 实跑导入**，不用正则解析 TS —— 正则会漏（转义、
  模板串、注释），而"编译不报错"不等于"键对齐"。
- 残留统计要区分注释与文案：注释里的中文是给人看的，翻译它没有意义，
  混在一起会让台账失真（实测注释中文 14031 字 vs 文案中文 8501 字）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEBAPP = ROOT / "webapp"
SRC = WEBAPP / "src"
JAVA_VOCAB = ROOT / "labos-kernel/src/main/java/com/labos/kernel/workflow/StatusVocabulary.java"

CJK = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
# 只认真正的占位符：${变量} 是界面上的字面提示（提示用户可写 ${var}），
# 不是要被替换的占位符 —— 所以排除 $ 前缀。
PLACEHOLDER = re.compile(r"(?<!\$)\{(\w+)\}")

SCOPES = ["run", "node", "step", "experiment", "batch", "control"]


# ---------------------------------------------------------------- 语言包载入


PROBE_ENTRY = """
import { zhCN } from '__ZH__';
import { enUS } from '__EN__';
import { CODE_TEXT_EN } from '__CODES__';
import { FALLBACK, localizeVocabulary } from '__STATUS__';
import { roleLabel } from '__AUTH__';

const en = localizeVocabulary(FALLBACK, 'en-US');
const zh = localizeVocabulary(FALLBACK, 'zh-CN');

// 把英文侧真正会显示的文案全导出来（label / hint / 角色 / 文案包）
const shown = [];
for (const scope of Object.keys(en.scopes)) {
  for (const e of en.scopes[scope]) {
    shown.push({ kind: 'status', scope: scope, code: e.code, text: e.label });
  }
}
for (const e of en.pauseKinds) {
  shown.push({ kind: 'pause', scope: '', code: e.code, text: e.label });
  shown.push({ kind: 'pauseHint', scope: '', code: e.code, text: e.hint });
}
for (const e of en.holdKinds) {
  shown.push({ kind: 'hold', scope: '', code: e.code, text: e.label });
}
for (const e of en.holdScopes) {
  shown.push({ kind: 'holdScope', scope: '', code: e.code, text: e.label });
}
for (const role of ['admin', 'operator', 'viewer', null]) {
  shown.push({ kind: 'role', scope: '', code: String(role), text: roleLabel(role, 'en-US') });
}
for (const kv of Object.entries(enUS)) {
  shown.push({ kind: 'message', scope: '', code: kv[0], text: kv[1] });
}

const zhShown = [];
for (const scope of Object.keys(zh.scopes)) {
  for (const e of zh.scopes[scope]) {
    zhShown.push({ kind: 'status', scope: scope, code: e.code, text: e.label });
  }
}

console.log(JSON.stringify({ zh: zhCN, en: enUS, codes: CODE_TEXT_EN, shown: shown, zhShown: zhShown }));
"""


def run_probe() -> dict:
    """用 esbuild + node 真正跑一遍 i18n，导出英文界面**真正会显示**的文案。"""
    esbuild = WEBAPP / "node_modules/.bin/esbuild"
    if not esbuild.exists():
        sys.exit("找不到 esbuild：先在 webapp/ 跑 npm install")

    def abs_import(rel: str) -> str:
        return (WEBAPP / rel).as_posix()

    # 用 __X__ 占位再替换：JS 代码里全是花括号，str.format 会被当成字段名报错
    entry = (PROBE_ENTRY
             .replace("__ZH__", abs_import("src/i18n/messages/zh-CN"))
             .replace("__EN__", abs_import("src/i18n/messages/en-US"))
             .replace("__CODES__", abs_import("src/i18n/codes"))
             .replace("__STATUS__", abs_import("src/utils/status"))
             .replace("__AUTH__", abs_import("src/auth")))
    with tempfile.TemporaryDirectory() as td:
        entry_file = Path(td) / "entry.ts"
        entry_file.write_text(entry, encoding="utf-8")
        out = Path(td) / "bundle.mjs"
        subprocess.run(
            [str(esbuild), str(entry_file), "--bundle", "--format=esm",
             "--platform=node", "--jsx=automatic",
             '--define:import.meta.env.VITE_API_BASE=""',
             f"--outfile={out}"],
            check=True, capture_output=True,
        )
        res = subprocess.run(
            ["node", str(out)], check=True, capture_output=True, text=True,
        )
        return json.loads(res.stdout)


# ---------------------------------------------------------------- 后端词表解析


def parse_backend_vocab() -> dict:
    """从 StatusVocabulary.java 抽出 code 全集（正则够用：格式固定且是本仓库自有文件）。"""
    if not JAVA_VOCAB.exists():
        sys.exit(f"找不到后端词表：{JAVA_VOCAB}")
    text = JAVA_VOCAB.read_text(encoding="utf-8")

    out = {"status": {}, "pauseKinds": [], "holdKinds": [], "holdScopes": []}

    # SCOPES.put(SCOPE_RUN, List.of( new StatusDef("code", ... ) ... ))
    scope_const = {
        "SCOPE_RUN": "run", "SCOPE_NODE": "node", "SCOPE_STEP": "step",
        "SCOPE_EXPERIMENT": "experiment", "SCOPE_BATCH": "batch",
        "SCOPE_CONTROL": "control",
    }
    for const, scope in scope_const.items():
        # 形如：SCOPES.put(SCOPE_RUN, List.of( new StatusDef(...) ... )));
        # 结尾是 `)));`（StatusDef / List.of / put 三层），不要求单独成行。
        m = re.search(
            r"SCOPES\.put\(\s*" + const + r"\s*,\s*List\.of\((.*?)\)\);",
            text, re.S,
        )
        if not m:
            out["status"][scope] = []
            continue
        codes = re.findall(r'new StatusDef\(\s*"([^"]+)"', m.group(1))
        out["status"][scope] = codes

    def codes_of(marker: str, ctor: str) -> list[str]:
        m = re.search(marker + r"\s*=\s*List\.of\((.*?)\)\);", text, re.S)
        if not m:
            return []
        return re.findall(r'new ' + ctor + r'\(\s*"([^"]+)"', m.group(1))

    out["pauseKinds"] = codes_of("PAUSE_KINDS", "PauseKindDef")
    out["holdKinds"] = codes_of("HOLD_KINDS", "HoldKindDef")
    out["holdScopes"] = codes_of("HOLD_SCOPES", "HoldScopeDef")
    return out


# ---------------------------------------------------------------- 残留中文扫描

# 按设计保留中文的文件：翻译源本身，不是"漏翻"。
EXCLUDE = {
    "webapp/src/i18n/": "The message packs — the Chinese pack is the master, the English one is derived from it",
    "webapp/src/utils/status.ts": "FALLBACK is the Chinese-side fallback vocabulary (the English side comes from i18n/codes.ts)",
}

# 按设计保留的中文字面量：语言名在语言切换器里用本族文字写（中文界面写"中文"，
# 英文界面也写"中文" —— 这是语言列表的惯例，不是漏翻）。
ALLOWED_CJK = [
    "中文",
    # 仓库内文件路径：指向中文命名的文档，是引用不是文案，翻译它反而点不开
    "docs/设备/M10-手册到型号库接入准备清单-v0.1.md",
]


def excluded(path: str) -> str | None:
    for prefix, reason in EXCLUDE.items():
        if path.startswith(prefix):
            return reason
    return None


def strip_comments(line: str, in_block: bool) -> tuple[str, bool]:
    """去掉注释，返回 (代码部分, 是否仍在块注释里)。

    要认得三种注释：// 行注释、/* */ 块注释、JSX 里的 {/* */}。
    JSX 注释容易漏：它以 `{/*` 开头，不是以 `/*` 开头，
    漏掉的话会把大段中文注释算进"待翻译文案"，台账直接失真。
    """
    s = line.strip()
    if in_block:
        if "*/" in s:
            in_block = False
            # 只改 s 不改 line 会漏：块注释结束后的行内容仍会被计入
            line = s.split("*/", 1)[1]
        else:
            return "", True
    if s.startswith("//"):
        return "", False
    # JSX 注释 {/* ... */}
    if "{/*" in line:
        pre, _, post = line.partition("{/*")
        if "*/}" in post:
            line = pre + post.split("*/}", 1)[1]
        else:
            line = pre
            in_block = True
    # 行内块注释
    elif "/*" in line:
        pre, _, post = line.partition("/*")
        if "*/" in post:
            line = pre + post.split("*/", 1)[1]
        else:
            line = pre
            in_block = True
    # 行尾 // 注释
    if "//" in line:
        line = line.split("//", 1)[0]
    return line, in_block


def scan_residual() -> tuple[list[tuple[str, int]], list[tuple[str, int, str]]]:
    """返回 (待翻译清单, 按设计保留清单)。"""
    todo: list[tuple[str, int]] = []
    kept: list[tuple[str, int, str]] = []
    for p in sorted(SRC.rglob("*.ts")) + sorted(SRC.rglob("*.tsx")):
        n = 0
        in_block = False
        for line in p.read_text(encoding="utf-8").splitlines():
            code, in_block = strip_comments(line, in_block)
            for lit in ALLOWED_CJK:
                code = code.replace(lit, "")
            n += len(CJK.findall(code))
        if not n:
            continue
        rel = str(p.relative_to(ROOT))
        reason = excluded(rel)
        if reason:
            kept.append((rel, n, reason))
        else:
            todo.append((rel, n))
    todo.sort(key=lambda x: -x[1])
    return todo, kept


# ---------------------------------------------------------------- 检查项


def check_packs(zh: dict, en: dict) -> list[str]:
    errs: list[str] = []
    zh_keys, en_keys = set(zh), set(en)
    for k in sorted(zh_keys - en_keys):
        errs.append(f"[语言包] 英文包缺 key：{k}（中文：{zh[k]}）")
    for k in sorted(en_keys - zh_keys):
        errs.append(f"[语言包] 英文包多 key：{k}（中文包没有，不该存在）")
    for k in sorted(zh_keys & en_keys):
        if CJK.search(en[k]):
            errs.append(f"[语言包] 英文文案里混进中文：{k} = {en[k]!r}")
        zh_ph = set(PLACEHOLDER.findall(zh[k]))
        en_ph = set(PLACEHOLDER.findall(en[k]))
        if zh_ph != en_ph:
            errs.append(
                f"[语言包] 占位符不一致：{k} 中文{zh_ph or '{}'} / 英文{en_ph or '{}'}"
            )
    return errs


def check_codes(backend: dict, codes: dict) -> tuple[list[str], list[str]]:
    errs, warns = [], []
    expected: set[str] = set()
    for scope in SCOPES:
        for c in backend["status"].get(scope, []):
            expected.add(f"status.{scope}.{c}")
    for c in backend["pauseKinds"]:
        expected.add(f"pause.{c}")
        expected.add(f"pause.{c}.hint")
    for c in backend["holdKinds"]:
        expected.add(f"hold.{c}")
    for c in backend["holdScopes"]:
        expected.add(f"holdScope.{c}")

    for k in sorted(expected):
        if k not in codes:
            errs.append(f"[领域code] 后端词表有、英文表没有：{k}")
    for k in sorted(set(codes) - expected):
        warns.append(f"[领域code] 英文表里有多余条目（后端词表已无此 code）：{k}")
    return errs, warns


def check_shown(shown: list[dict]) -> list[str]:
    """英文界面**真正会显示**的文案：不允许有中文，也不允许回落成裸 code。"""
    errs: list[str] = []
    for item in shown:
        text = item["text"]
        where = f"{item['kind']}.{item['scope']}.{item['code']}".strip(".")
        if CJK.search(text):
            errs.append(f"[英文界面] 出现中文：{where} = {text!r}")
        # 状态/暂停/扣留类：文案等于 code 说明英文表缺这条（回落了）
        if item["kind"] in ("status", "pause", "pauseHint", "hold", "holdScope"):
            if text == item["code"]:
                errs.append(f"[英文界面] 文案回落成裸 code：{where}")
    return errs


# ---------------------------------------------------------------- 输出


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--md", help="把台账写成 Markdown 文件")
    ap.add_argument("--strict", action="store_true", help="残留中文 > 0 也算失败")
    args = ap.parse_args()

    probe = run_probe()
    zh, en, codes = probe["zh"], probe["en"], probe["codes"]
    backend = parse_backend_vocab()
    residual, kept = scan_residual()

    errs, warns = check_codes(backend, codes)
    errs += check_packs(zh, en)
    errs += check_shown(probe["shown"])

    total_residual = sum(n for _, n in residual)

    # ---------------------------------------------------------------- 报告
    print("=" * 68)
    print("i18n 台账（英文版工程）")
    print("=" * 68)
    print(f"语言包 key 数：zh={len(zh)}  en={len(en)}")
    print(f"领域 code 英文条目：{len(codes)}")
    print(f"后端词表 code 数：{sum(len(v) for v in backend['status'].values())} 个状态 + "
          f"{len(backend['pauseKinds'])} 暂停类型 + {len(backend['holdKinds'])} 扣留类型 + "
          f"{len(backend['holdScopes'])} 扣留作用域")
    print(f"英文界面实跑校验：{len(probe['shown'])} 条会显示的文案")
    print(f"前端残留中文（面向用户，注释不计）：{total_residual} 字 / {len(residual)} 个文件")
    print("-" * 68)

    if errs:
        print(f"硬错误 {len(errs)} 条：")
        for e in errs:
            print("  ✗ " + e)
    else:
        print("硬错误：0（语言包对齐 + 领域 code 全覆盖 + 英文界面无中文）")

    if warns:
        print(f"提示 {len(warns)} 条：")
        for w in warns:
            print("  ! " + w)

    print("-" * 68)
    print("按设计保留的中文（不翻译）：")
    for f, n, reason in kept:
        print(f"  {n:5d}  {f}  —— {reason}")

    print("-" * 68)
    print("残留中文 Top 15（后续批次按这个顺序推）：")
    for f, n in residual[:15]:
        print(f"  {n:5d}  {f}")
    if len(residual) > 15:
        print(f"  …另有 {len(residual) - 15} 个文件")

    if args.md:
        # 注意：--md 落到 docs/en/（英文文档树）里，所以这一份用英文输出；
        # stdout 仍保持中文，供团队日常在看门禁时阅读。
        md = ["# i18n ledger (generated)", "",
              f"- Message keys: zh={len(zh)} / en={len(en)}",
              f"- Domain codes with an English entry: {len(codes)}",
              f"- English texts reachable in the UI (executed, not parsed): {len(probe['shown'])}",
              f"- Residual user-facing Chinese: {total_residual} characters / {len(residual)} files",
              f"- Hard errors: {len(errs)}", ""]
        md += ["## Residual Chinese ledger", "", "| File | Chinese characters |", "|---|---:|"]
        md += [f"| `{f}` | {n} |" for f, n in residual]
        if kept:
            md += ["", "## Chinese kept by design (not translated)", "",
                   "| File | Chinese characters | Why |", "|---|---:|---|"]
            md += [f"| `{f}` | {n} | {r} |" for f, n, r in kept]
        if errs:
            md += ["", "## Hard errors", ""] + [f"- {e}" for e in errs]
        Path(args.md).write_text("\n".join(md) + "\n", encoding="utf-8")
        print(f"\n台账已写入 {args.md}")

    if errs:
        return 1
    if args.strict and total_residual:
        print("\n(strict 模式：残留中文不为 0，判定失败)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
