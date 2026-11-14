#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""内置逻辑节点的**跨层一致性**静态核对（2026-11-15）。

**为什么要有这个脚本**：内置逻辑节点横跨三层 ——
  · 前端设计器（Designer.tsx / flowSpec.ts / builtinLabels.ts / nodeRoleLabel.ts）
  · 前端文案（i18n zh-CN + en-US）
  · 内核引擎（GraphRunner 的 executeBuiltin / describeBuiltin / 各等待方法的参数）
这三层任何一处对不上，症状都是**现场跑到一半停住**，而且报错信息很难指向真凶
（例：前端把参数存成 `event_type`，内核读 `eventType` ⇒ 永远读不到 ⇒ 等到超时）。

本脚本把「契约」写死成一张表，两边都比一遍：
  1. 六个内置方法名在 6 个前端位置 + 2 个内核位置**完全一致**；
  2. 每个方法的**参数名**前端会存、内核会读 —— 两边都覆盖；
  3. i18n 两个语种键集一致（zh-CN 是 MessageKey 的真源，en-US 必须全有）。

用法：python3 scripts/verify-builtin-nodes.py
不需要连库、不需要起服务。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASS, FAIL = 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✘\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def region(text, start_re, end_marker="\n    private "):
    """取一段源码：从 start_re 命中处到下一个同级 private 方法之前。"""
    m = re.search(start_re, text)
    if not m:
        return ""
    rest = text[m.start():]
    e = rest.find(end_marker, 1)
    return rest if e < 0 else rest[:e]


# ── 契约：方法 → (内核读到的参数, 前端会存的参数) ────────────────────────────────
# 只列**这个方法特有**的参数；timeout/interval 这类共用的在下面单独查。
CONTRACT = {
    "__setVar":    (["var", "value"], ["var", "value"]),
    "__expr":      (["var", "expr"], ["var", "expr"]),
    "__sleep":     (["sec"], ["sec"]),
    "__wait":      (["var", "value"], ["var", "value"]),
    "__waitAny":   (["vars", "value", "into"], ["vars", "value", "into"]),
    "__awaitEvent": (["device", "eventType", "into", "deviceInto"],
                     ["device", "eventType", "into", "deviceInto"]),
}
# 共用参数：内核必须能读，前端要么能存要么明确不暴露
COMMON = ["timeout", "interval", "consume"]

# 内核里每个方法的实现落在哪个方法体里
KERNEL_REGION = {
    "__setVar":     r"private String executeBuiltin\(",
    "__expr":       r"private String executeBuiltin\(",
    "__sleep":      r"private void sleepSeconds\(",
    "__wait":       r"private void waitForVariable\(",
    "__waitAny":    r"private void waitForAnyVariable\(",
    "__awaitEvent": r"private void waitForDeviceEvent\(",
}


def save_branch(designer, method):
    """精确取设计器的**保存分支**：`} else if (kind === 'X') {` 到下一个 `} else` 之前。

    不能用"从 kind === 'X' 到下一个 } else if"那种宽区间 ——
    那样会把下面的**属性编辑 JSX** 也圈进来，参数名在里面也出现 ⇒
    前端存错参数名照样查不出来（变异测试证明过：那样是漏的）。
    """
    m = re.search(r"} else if \(kind === '%s'\) \{" % re.escape(method), designer)
    if not m:
        return ""
    rest = designer[m.end():]
    e = rest.find("\n      } else")
    return rest[:e] if e >= 0 else rest


def has_param_key(text, param):
    """参数是否**作为对象的键**出现（简写 `{p}` / `{p: v}` / `wp.p =` 都算）。"""
    if re.search(r"(?<![\w$])%s\s*:" % re.escape(param), text):
        return True
    return bool(re.search(r"\.%s\s*=" % re.escape(param), text))


def main():
    print("════ 内置逻辑节点 · 跨层一致性核对 ════")
    print()

    flow_spec = read("webapp/src/bpmn/flowSpec.ts")
    builtin_labels = read("webapp/src/bpmn/builtinLabels.ts")
    node_role = read("webapp/src/bpmn/nodeRoleLabel.ts")
    designer = read("webapp/src/pages/Designer.tsx")
    zh = read("webapp/src/i18n/messages/zh-CN.ts")
    en = read("webapp/src/i18n/messages/en-US.ts")
    runner = read("labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java")
    preflight = read(
        "labos-kernel/src/main/java/com/labos/kernel/workflow/PreflightChecker.java")

    methods = sorted(CONTRACT.keys())

    print("── 1. 方法名在各处一致")
    # flowSpec.LOGIC_METHODS
    m = re.search(r"LOGIC_METHODS\s*=\s*\[([^\]]*)\]", flow_spec)
    spec_methods = re.findall(r"'(__[a-zA-Z]+)'", m.group(1)) if m else []
    for meth in methods:
        if meth in spec_methods:
            ok("flowSpec.LOGIC_METHODS 含 %s" % meth)
        else:
            bad("flowSpec.LOGIC_METHODS 缺 %s" % meth, str(spec_methods))

    # builtinLabels
    m = re.search(r"BUILTIN_LABEL[^{]*\{(.*?)\}", builtin_labels, re.S)
    labels = re.findall(r"(__[a-zA-Z]+)\s*:", m.group(1)) if m else []
    for meth in methods:
        if meth in labels:
            ok("builtinLabels 含 %s" % meth)
        else:
            bad("builtinLabels 缺 %s" % meth, str(labels))

    # nodeRoleLabel
    m = re.search(r"BUILTIN_CODE_TO_KEY[^{]*\{(.*?)\}", node_role, re.S)
    roles = re.findall(r"(__[a-zA-Z]+)\s*:", m.group(1)) if m else []
    for meth in methods:
        if meth in roles:
            ok("nodeRoleLabel 含 %s" % meth)
        else:
            bad("nodeRoleLabel 缺 %s" % meth, str(roles))

    # Designer：类型、类型下拉、画板
    m = re.search(r"type BuiltinKind\s*=\s*([^;]+);", designer)
    kinds = re.findall(r"'(__[a-zA-Z]+)'", m.group(1)) if m else []
    for meth in methods:
        if meth in kinds:
            ok("Designer BuiltinKind 含 %s" % meth)
        else:
            bad("Designer BuiltinKind 缺 %s" % meth, str(kinds))

    dropdown = re.findall(r"\{\s*value:\s*'(__[a-zA-Z]+)'", designer)
    for meth in methods:
        if meth in dropdown:
            ok("Designer 类型下拉含 %s" % meth)
        else:
            bad("Designer 类型下拉缺 %s" % meth, str(sorted(set(dropdown))))

    palette = re.findall(r"<LogicNodeRow\s+method=\"(__[a-zA-Z]+)\"", designer)
    for meth in methods:
        if meth in palette:
            ok("Designer 画板含 %s" % meth)
        else:
            bad("Designer 画板缺 %s" % meth, str(sorted(set(palette))))

    print()
    print("── 2. 内核：能分发、能描述、参数读得到")
    for meth in methods:
        if '"%s"' % meth in runner:
            ok("GraphRunner 认识 %s" % meth)
        else:
            bad("GraphRunner 不认识 %s" % meth)

    disp = region(runner, r"private String executeBuiltin\(")
    for meth in methods:
        if '"%s"' % meth in disp:
            ok("executeBuiltin 分发 %s" % meth)
        else:
            bad("executeBuiltin 不分发 %s" % meth)

    desc = region(runner, r"public static String describeBuiltin\(")
    for meth in methods:
        if '"%s"' % meth in desc:
            ok("describeBuiltin 能描述 %s" % meth)
        else:
            bad("describeBuiltin 描述不了 %s" % meth)

    for meth in methods:
        body = region(runner, KERNEL_REGION[meth])
        if not body:
            bad("找不到 %s 的内核实现" % meth)
            continue
        need = CONTRACT[meth][0]
        # 参数可能**直接**读（p.get("x")），也可能经由取参助手读
        # （requireVar(p) / requireWaitVar(p) / parseWaitAnyTargets(p)）——
        # 两种都算"读到了"，否则会把正确的实现判成没读。
        ALTS = {
            ("__setVar", "var"): ["p.get(\"var\")", "requireVar(p)"],
            ("__expr", "var"): ["p.get(\"var\")", "requireVar(p)"],
            ("__wait", "var"): ["p.get(\"var\")", "requireWaitVar(p)", "requireVar(p)"],
            ("__waitAny", "vars"): ["p.get(\"vars\")", "parseWaitAnyTargets(p)"],
            ("__waitAny", "value"): ["p.get(\"value\")", "parseWaitAnyTargets(p)"],
        }
        missing = []
        for p in need:
            alts = ALTS.get((meth, p), ["p.get(\"%s\")" % p])
            if not any(a in body for a in alts):
                missing.append(p)
        if missing:
            bad("%s 内核没读参数 %s" % (meth, ",".join(missing)))
        else:
            ok("%s 内核读到了自己的参数" % meth)

    for meth in ("__wait", "__waitAny", "__awaitEvent"):
        body = region(runner, KERNEL_REGION[meth])
        missing = [p for p in ("timeout", "interval") if 'p.get("%s")' % p not in body]
        if missing:
            bad("%s 内核没读共用参数 %s" % (meth, ",".join(missing)))
        else:
            ok("%s 内核读到了 timeout/interval" % meth)

    print()
    print("── 3. 前端：存的参数名 == 内核读的参数名")
    for meth in methods:
        body = save_branch(designer, meth)
        if not body:
            # __setVar / __expr / __sleep 在最后的 else 分支里
            body = region(designer, r"\} else \{\n\s*params = kind === '__setVar'",
                          end_marker="\n      \}")
        if not body:
            bad("找不到 %s 的前端保存分支" % meth)
            continue
        need = CONTRACT[meth][1]
        missing = [p for p in need if not has_param_key(body, p)]
        if missing:
            bad("%s 前端不存参数 %s" % (meth, ",".join(missing)))
        else:
            ok("%s 前端存的参数与内核一致" % meth)

    for meth in ("__wait", "__waitAny", "__awaitEvent"):
        body = save_branch(designer, meth)
        if not has_param_key(body, "timeout"):
            bad("%s 前端没存 timeout" % meth)
        else:
            ok("%s 前端存了 timeout" % meth)

    print()
    print("── 4. 预检：六个内置方法都收了必填参数")
    pf = region(preflight, r"private static java.util.List<String> missingBuiltinParams\(")
    for meth in methods:
        if '"%s"' % meth in pf:
            ok("PreflightChecker 覆盖 %s" % meth)
        else:
            bad("PreflightChecker 没覆盖 %s" % meth)

    print()
    print("── 5. i18n：两个语种键集一致")
    zh_keys = set(re.findall(r"^\s*'([^']+)':", zh, re.M))
    en_keys = set(re.findall(r"^\s*'([^']+)':", en, re.M))
    only_zh = sorted(k for k in zh_keys - en_keys if k.startswith("designer."))
    only_en = sorted(k for k in en_keys - zh_keys if k.startswith("designer."))
    if only_zh:
        bad("en-US 缺 %d 个 designer.* 键" % len(only_zh), ", ".join(only_zh[:5]))
    else:
        ok("en-US 的 designer.* 键与 zh-CN 一致")
    if only_en:
        bad("zh-CN 缺 %d 个 designer.* 键" % len(only_en), ", ".join(only_en[:5]))
    else:
        ok("zh-CN 的 designer.* 键与 en-US 一致")

    print()
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
