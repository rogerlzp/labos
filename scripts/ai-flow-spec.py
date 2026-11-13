#!/usr/bin/env python3
"""自然语言 → **流程规格（Flow Spec）** JSON。

这是「AI 建流程」链路的**第一步**：只负责把一句人话变成一份**业务视角**的图
（哪些设备、哪些方法、怎么连），不碰画布、不碰数据库。

为什么不让 AI 直接生成 BPMN XML：
  XML 里塞满了与业务无关的样板（DI 坐标、flow id、incoming/outgoing 引用），
  模型生成它极易漏引用、坐标乱、连线断 —— 而 Flow Spec 只有节点和边，
  画布怎么摆、图标怎么来、网关叫什么，全部由页面那一层翻译。

设备接地（grounding）：
  规格里的每个设备节点必须来自**真实设备池**（code + method），
  否则 AI 会编出"离心机 ID-CENTRIFUGE-9"这种不存在的设备，
  建图时才炸。这里先拉设备目录喂给模型，建完再校验一遍。

用法：
  python3 scripts/ai-flow-spec.py --text "读码 → 离心 → 封膜"
  python3 scripts/ai-flow-spec.py --text "..." --island LAB-BASE-01 --out /tmp/spec.json
  python3 scripts/ai-flow-spec.py --text "..." --model minimax/MiniMax-M3
  python3 scripts/ai-flow-spec.py --text "..." --catalog-only     # 只看设备目录，不调模型
  python3 scripts/ai-flow-spec.py --validate /tmp/spec.json       # 只校验已有规格

环境变量：
  LABOS_API   后端地址（默认 http://127.0.0.1:18080）
  LABOS_USER  账号（默认 admin）
  LABOS_PASS  密码（默认 admin123）
  LABOS_LLM_SCRIPT  llm_call.py 路径（默认自动探测）
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

# ---------------------------------------------------------------- LLM 脚本定位

_LLM_CANDIDATES = [
    os.environ.get("LABOS_LLM_SCRIPT", ""),
    str(Path.home() / ".minimax/.builtin-skills/llm-call/scripts/llm_call.py"),
    str(Path.home() / ".minimax/skills/llm-call/scripts/llm_call.py"),
]


def find_llm_script() -> str | None:
    """找到 llm_call.py。找不到就返回 None（调用方给出可操作的提示）。"""
    for c in _LLM_CANDIDATES:
        if c and Path(c).is_file():
            return c
    return None


# ---------------------------------------------------------------- API 基础


def api(path: str, method: str = "GET", body: Any = None,
        token: str | None = None, timeout: int = 60, retries: int = 3) -> Any:
    """调后端 API。

    <p><b>为什么要重试</b>：拉 249 台设备的方法时会并发十几路打后端，
    Tomcat 偶尔直接甩连接（RemoteDisconnected / 5xx）。这种瞬时失败重试一次
    通常就好，不必让整个脚本崩掉。
    """
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
                text = resp.read().decode()
            break
        except urllib.error.HTTPError as exc:
            # 4xx 是真错（重试没用）；5xx 才重试
            detail = exc.read().decode()[:300]
            if exc.code < 500:
                raise RuntimeError(
                    f"{method} {path} → HTTP {exc.code}: {detail}") from exc
            last = exc
        except (urllib.error.URLError, ConnectionError, OSError) as exc:
            last = exc
        time.sleep(0.4 * (attempt + 1))
    else:
        raise RuntimeError(f"{method} {path} 重试 {retries} 次仍失败：{last}")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def login() -> str:
    out = api("/api/auth/login", "POST",
              {"username": USER, "password": PWD}, timeout=30)
    if not isinstance(out, dict) or not out.get("token"):
        raise RuntimeError(f"登录失败：{out}")
    return out["token"]


# ---------------------------------------------------------------- 设备目录


def fetch_catalog(token: str, island_code: str | None = None) -> list[dict[str, Any]]:
    """拉设备目录（岛 → 设备 → 方法），并算出每个方法**实际会被判成什么能力**。

    为什么要算"判成什么能力"：
      后端给节点标能力时，**不看设备、只看方法名**，按全库同名方法的
      **多数表决**决定（见后端 `selectMethodCapabilityVotes`：
      `row_number() OVER (PARTITION BY method ORDER BY count(*) DESC, capability_id)`）。
      于是 `ReadBarcode` 在 249 台设备里多数属于 ALIQUOT ⇒ 就算你绑的是读码机
      （READ_PLATE），节点也会被判成 ALIQUOT ⇒ 预检 PF-13 直接拦
      「岛内没有具备该能力的设备」。

      也就是说：**方法名有歧义时，选错设备/方法，流程看着对却跑不起来**。
      这里按后端同一口径把票数算出来，喂给模型、并在校验时拦住 ——
      AI 生成的流程要能真跑，不是能画出来就算数。
    """
    # ① 全库设备池（多数表决是**全库**口径，不是岛内口径）
    pool = api("/api/devices/pool", token=token).get("items") or []

    def methods_of(dev: dict[str, Any]) -> list[dict[str, Any]]:
        return (api(f"/api/devices/{dev['resourceId']}/methods",
                    token=token).get("items") or [])

    # ② 每台设备的方法（带**方法级**能力）——249 台，并发拉取约 0.5s
    with ThreadPoolExecutor(max_workers=8) as ex:
        per_device = list(ex.map(methods_of, pool))

    # ③ 复刻后端多数表决：method → 票数最高的能力（平票取小 id，本脚本取能力码字典序近似）
    votes: Counter = Counter()
    for methods in per_device:
        for m in methods:
            cap = m.get("capability")
            if cap:
                votes[(m.get("method"), cap)] += 1
    majority: dict[str, tuple[str, int]] = {}
    for (meth, cap), v in votes.items():
        cur = majority.get(meth)
        if cur is None or v > cur[1]:
            majority[meth] = (cap, v)

    # ④ 岛内设备目录（给模型看的候选）
    topo = api("/api/topology", token=token)
    islands = topo.get("islands") or []
    out: list[dict[str, Any]] = []
    for isl in islands:
        if island_code and isl.get("code") != island_code:
            continue
        for dev in isl.get("devices") or []:
            methods = api(f"/api/devices/{dev['resourceId']}/methods",
                          token=token).get("items") or []
            # 设备在线状态：topology 的 `online` 是 `COALESCE(ds.online,false)` ——
            # **没有 device_state 行**的设备也报 false。但后端的能力池
            # （`selectCapabilityPools`）只排除「**有行**且 online=false/error」的设备，
            # 没行的仍在池里（NOT EXISTS 对无行成立，注释里明写"不该直接判死"）。
            # ⇒ API 层**分不清**"显式离线"与"从未心跳" ⇒ 这里给三态，不硬猜：
            #     ok       = 在线，且能力一致（确定能跑）
            #     unknown  = 拓扑报不在线（离线 / 从未心跳，二者 API 不可分）
            #     ambiguous= 能力被判到别的能力上去（必被 PF-13 拦）
            online = dev.get("online")
            status = dev.get("status")
            not_online = (online is not True) or (status == "error")
            out.append({
                "resourceId": dev["resourceId"],
                "code": dev["code"],
                "name": dev.get("name") or dev["code"],
                "capability": dev.get("capability") or "",
                "islandCode": isl.get("code"),
                "online": online,
                "notOnline": not_online,
                "methods": [
                    {
                        "method": m.get("method"),
                        "name": m.get("name"),
                        "requiredParams": [
                            p.get("name") for p in (m.get("params") or [])
                            if p.get("required")
                        ],
                        # 这个方法名实际会被判成什么能力
                        "judgedCapability": majority.get(m.get("method"), ("", 0))[0],
                        # 三态：ok / unknown（设备不在线）/ ambiguous（能力歧义）
                        "state": (
                            "ambiguous"
                            if majority.get(m.get("method"), ("", 0))[0]
                            != (dev.get("capability") or "")
                            else ("unknown" if not_online else "ok")
                        ),
                    }
                    for m in methods
                ],
            })
    return out


def catalog_text(catalog: list[dict[str, Any]]) -> str:
    """把设备目录压成给模型看的紧凑文本。

    方法标记：
      ✓  = 在线且能力一致（确定能跑）
      ?  = 设备当前不在线（离线的不在能力池里 ⇒ 预检可能拦；
           但"从未上报心跳"的仍在池里，API 分不清这两种）
      ✗  = 能力歧义（同名方法在全库多数属于别的能力 ⇒ 必被预检拦）
    **模型应优先选 ✓；禁用 ✗。**
    """
    lines = []
    for d in catalog:
        ms = []
        for m in d["methods"]:
            if not m.get("method"):
                continue
            st = m.get("state")
            if st == "ok":
                ms.append(f"{m['method']}✓")
            elif st == "unknown":
                ms.append(f"{m['method']}?(设备不在线)")
            else:
                ms.append(f"{m['method']}✗(会被判成{m['judgedCapability']})")
        lines.append(
            f"- {d['code']} | {d['name']} | 能力={d['capability']} | "
            f"岛={d['islandCode']} | 方法: {', '.join(ms) or '（无方法）'}"
        )
    return "\n".join(lines)


# ---------------------------------------------------------------- 规格校验


VALID_TYPES = {"start", "end", "method", "logic",
               "exclusive", "parallel", "inclusive"}
LOGIC_METHODS = {"__setVar", "__expr", "__sleep", "__wait"}


def validate_spec(spec: Any, catalog: list[dict[str, Any]]) -> tuple[list[str], list[str]]:
    """校验规格：结构 + 设备/方法是否真实存在 + 能力歧义。

    返回 (错误列表, 警告列表)：
      - 错误 = 一定会出问题（结构不合法、设备/方法不存在、能力歧义）→ 必须拦下；
      - 警告 = 可能出问题（设备当前不在线）→ 只提醒，最终以预检为准。

    **建图之前必须过这一关** —— 宁可在这里说"方法名不存在"，
    也不要建出一张跑不起来的图。
    """
    errors: list[str] = []
    warnings: list[str] = []
    if not isinstance(spec, dict):
        return ["规格顶层必须是对象"], warnings
    nodes = spec.get("nodes")
    edges = spec.get("edges")
    if not isinstance(nodes, list) or not nodes:
        return ["nodes 必须是非空数组"], warnings
    if not isinstance(edges, list):
        return ["edges 必须是数组"], warnings

    by_code = {d["code"]: d for d in catalog}
    seen: set[str] = set()
    for i, n in enumerate(nodes):
        if not isinstance(n, dict):
            errors.append(f"nodes[{i}] 不是对象")
            continue
        nid = str(n.get("id") or "").strip()
        if not nid:
            errors.append(f"nodes[{i}] 缺少 id")
            continue
        if nid in seen:
            errors.append(f"节点 id 重复：{nid}")
            continue
        seen.add(nid)
        ntype = n.get("type")
        if ntype not in VALID_TYPES:
            errors.append(f"节点 {nid} 的 type 不认识：{ntype}")
            continue
        if ntype == "method":
            method = n.get("method")
            if not method:
                errors.append(f"设备节点 {nid} 缺 method")
                continue
            code = n.get("code")
            if not code:
                errors.append(f"设备节点 {nid} 缺 code（设备编码）")
                continue
            dev = by_code.get(code)
            if dev is None:
                errors.append(f"设备节点 {nid} 的设备 {code} 不在设备目录里")
                continue
            names = {m.get("method") for m in dev["methods"]}
            if method not in names:
                errors.append(
                    f"设备节点 {nid}：{code} 没有方法 {method}"
                    f"（可用：{', '.join(sorted(x for x in names if x))}）"
                )
                continue
            # 必填参数检查：少了就存不进去，运行时才炸就太晚了
            meta = next((m for m in dev["methods"] if m.get("method") == method), {})
            # 必填参数检查：少了就存不进去，运行时才炸就太晚了
            required = meta.get("requiredParams") or []
            given = n.get("params") or {}
            missing = [p for p in required if p not in given]
            if missing:
                errors.append(
                    f"设备节点 {nid}（{code}.{method}）缺必填参数：{', '.join(missing)}"
                )
            # 能力歧义 = 必被预检 PF-13 拦（节点会被贴上不属于本设备的能力）。
            # 设备不在线 = 可能被拦（离线设备不在能力池里；但"从未心跳"的仍在，
            # API 分不清这两种 ⇒ 只警告，最终以预检为准）。
            st = meta.get("state")
            if st == "ambiguous":
                errors.append(
                    f"设备节点 {nid}（{code}.{method}）有**能力歧义**："
                    f"同名方法在全库多数被判成 {meta.get('judgedCapability')} 能力，"
                    f"与该设备自身能力（{dev.get('capability')}）不符 ⇒ 节点会被贴错能力，"
                    f"预检 PF-13 会拦下。换个方法，或换一台同能力的设备"
                )
            elif st == "unknown":
                warnings.append(
                    f"设备节点 {nid}（{code}.{method}）所在的设备 {code} 当前不在线 ⇒ "
                    f"若它已离线，预检 PF-13 会拦（离线设备不在能力池里）；"
                    f"若只是从未上报心跳则仍可跑。以预检结果为准。"
                )
        if ntype == "logic":
            method = n.get("method")
            if method not in LOGIC_METHODS:
                errors.append(
                    f"逻辑节点 {nid} 的 method 不认识：{method}"
                    f"（可选：{', '.join(sorted(LOGIC_METHODS))}）"
                )

    for i, e in enumerate(edges):
        if not isinstance(e, dict):
            errors.append(f"edges[{i}] 不是对象")
            continue
        for side in ("from", "to"):
            ref = e.get(side)
            if ref not in seen:
                errors.append(f"edges[{i}].{side} 指向不存在的节点：{ref}")
    if not any(n.get("type") == "start" for n in nodes if isinstance(n, dict)):
        errors.append("规格里没有开始节点（type=start）")
    if not any(n.get("type") == "end" for n in nodes if isinstance(n, dict)):
        errors.append("规格里没有结束节点（type=end）")
    return errors, warnings


# ---------------------------------------------------------------- LLM 调用

SYSTEM_PROMPT = """你是实验室自动化流程设计器的"流程规划器"。
你的任务：把用户的一句流程描述，转成一份**流程规格（Flow Spec）** JSON。

只输出 JSON，不要任何解释、不要 Markdown 代码块、不要前后缀。

Flow Spec 的形状：
{
  "name": "流程名",
  "nodes": [
    {"id": "S", "type": "start"},
    {"id": "T1", "type": "method", "code": "设备编码", "method": "方法名", "params": {}},
    {"id": "V1", "type": "logic", "method": "__setVar", "params": {"var": "x", "value": "1"}},
    {"id": "G1", "type": "exclusive"},
    {"id": "E", "type": "end"}
  ],
  "edges": [
    {"from": "S", "to": "T1"},
    {"from": "T1", "to": "E"}
  ]
}

规则（**必须遵守**）：
1. 节点 type 只能是 start / end / method / logic / exclusive / parallel / inclusive。
2. method 节点的 code 与 method **只能从下面给的设备目录里选**，不许编。
3. 逻辑节点 type=logic，method 只能是 __setVar / __expr / __sleep / __wait。
4. 分支用网关：exclusive=异或（只走一条）、parallel=并行（全走）、inclusive=条件（成立的都走）。
   分叉用 exclusive/parallel，汇合用同类型的网关。
5. 必须有且只有一个 start、至少一个 end，所有节点都要被边连上（不能有孤立节点）。
6. 边用节点的 id 引用；不要写 x/y 坐标（页面会自动布局）。
7. 节点的 id 用简短英文大写标识（如 T1、T2、G1）。
8. method 节点若该方法有必填参数，必须在 params 里给上（值用设备编码/常见板位编码）。
9. 板子换了设备就要在中间加一个搬运节点：code 用岛上的机械臂编码，
   method 用 "Transfer"，params 给 {"source": "源设备编码", "target": "目标设备编码"}。
10. **方法标记的含义**（目录里每个方法后面都带一个）：
    - ✓ = 设备在线且能力一致，放心用；
    - ? = 设备当前不在线（离线设备不在后端能力池里，预检可能拦 PF-13；
          但"从未上报心跳"的设备其实仍在池里）—— 能用，但优先换台在线的；
    - ✗ = **能力歧义**：同名方法在全库多数属于别的能力 ⇒ 节点会被贴上不属于
          本设备的能力 ⇒ **必被预检拦下**，绝对不要用。
    优先用 ✓；实在没有合适的再选 ?；**禁用 ✗**。

可用设备目录在用户消息里。"""


def extract_json(text: str) -> Any:
    """从模型输出里抠出 JSON。

    模型经常带 Markdown 围栏或前后废话 —— 这里用括号配平扫描，
    比正则稳（JSON 里可能有嵌套对象/字符串里的花括号）。
    """
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if fence:
        text = fence.group(1).strip()
    start = text.find("{")
    if start < 0:
        raise RuntimeError(f"模型输出里找不到 JSON：{text[:300]}")
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(text)):
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return json.loads(text[start:i + 1])
    raise RuntimeError(f"模型输出的 JSON 不完整：{text[:300]}")


def llm_config_status(model: str) -> tuple[bool, str]:
    """先看本地 LLM 配置能不能用，别等跑了 30 秒才报 401。

    常见坏情况：
      ① `~/.minimax/config.yaml` 里 provider 的 apiKey 还是占位符（sk-xxx）；
      ② 模型名写错（provider/model 不存在）；
      ③ 代理变量指向 SOCKS 而 httpx 没装 socksio。
    """
    script = find_llm_script()
    if not script:
        return False, ("找不到 llm_call.py。请设置 LABOS_LLM_SCRIPT 指向它，"
                       "或改用 --spec 直接喂一份已生成好的规格。")
    cfg = Path.home() / ".minimax" / "config.yaml"
    if not cfg.is_file():
        return False, f"找不到 {cfg}（llm_call.py 靠它找 provider 配置）。"
    try:
        import yaml
        data = yaml.safe_load(cfg.read_text()) or {}
    except Exception as e:                       # noqa: BLE001
        return False, f"读 {cfg} 失败：{e}"
    provider = (model.split("/", 1)[0] if "/" in model else "")
    pconf = (data.get("provider") or {}).get(provider)
    if pconf is None:
        return False, (f"{cfg} 里没有 provider「{provider}」"
                       f"（可用：{', '.join((data.get('provider') or {}).keys())}）")
    key = str((pconf.get("options") or {}).get("apiKey") or "")
    if not key or key.lower() in ("sk-xxx", "xxx", "your-api-key", "changeme"):
        return False, (f"{cfg} 里 provider「{provider}」的 apiKey 还是占位符"
                       f"（{key or '空'}）—— 换成真实 key 才能用 LLM 生成。"
                       f"（不填也可以：直接用 --spec 喂一份已生成好的规格。）")
    return True, ""


def call_llm(prompt: str, model: str, max_tokens: int = 4096,
             timeout: int = 300) -> str:
    """调 LLM。按 provider 前缀分派：

      - ``deepseek/xxx``  → 直连 DeepSeek（OpenAI 兼容协议），key 读环境变量
        ``DEEPSEEK_APIKEY``（**不落盘**，不写进任何仓库文件）；
      - 其它（``minimax/...`` 等）→ 走本地 ``llm_call.py``（读
        ``~/.minimax/config.yaml`` 的 provider 配置）。

    两种后端都是 OpenAI/Anthropic 兼容的，返回的文本再交给
    {@link extract_json} 抠 JSON。
    """
    provider = model.split("/", 1)[0] if "/" in model else ""
    if provider == "deepseek":
        return call_deepseek(prompt, model.split("/", 1)[1], max_tokens, timeout)

    ok, why = llm_config_status(model)
    if not ok:
        raise RuntimeError(why)

    script = find_llm_script()
    assert script is not None
    cmd = [sys.executable, script, "--model", model,
           "--system", SYSTEM_PROMPT,
           "--prompt", prompt, "--max-tokens", str(max_tokens),
           "--timeout", str(timeout)]
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in ("all_proxy",)}
    import subprocess
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=timeout + 60)
    if proc.returncode != 0:
        # llm_call.py 失败时会打 `Error <code>: <body>`，那才是有用的信息；
        # 完整 traceback 对使用者没意义（多半是 401/超时）。
        err = proc.stderr.strip()
        m = re.search(r"^Error\s+(\d+):\s*(.+)$", err, re.M)
        detail = f"LLM 调用失败（HTTP {m.group(1)}）：{m.group(2)}" if m \
            else f"LLM 调用失败：{err.splitlines()[-1] if err else '未知原因'}"
        raise RuntimeError(detail)
    return proc.stdout


# DeepSeek：OpenAI 兼容的 /chat/completions，key 从环境变量读（不落盘）
DEEPSEEK_BASE = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")


def call_deepseek(prompt: str, model: str, max_tokens: int = 4096,
                  timeout: int = 300) -> str:
    """直连 DeepSeek。**key 只从环境变量读**，绝不写进仓库文件或日志。

    <p><b>推理模型会吃掉 max_tokens</b>：DeepSeek 的 pro 系列返回
    {@code reasoning_content}（推理 token 也算进 completion_tokens），
    预算给小了正文就会被截断成半个 JSON（{@code finish_reason:"length"}）。
    ⇒ 这里检测到截断就**加倍预算重试**，最多两次。
    """
    key = os.environ.get("DEEPSEEK_APIKEY", "").strip()
    if not key:
        raise RuntimeError(
            "没有 DEEPSEEK_APIKEY 环境变量。示例："
            "DEEPSEEK_APIKEY=sk-xxx python3 scripts/ai-flow-spec.py --text \"...\" "
            "--model deepseek/deepseek-flash"
        )
    url = DEEPSEEK_BASE.rstrip("/") + "/chat/completions"
    budget = max_tokens
    last_text = ""
    for attempt in range(3):
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
            "max_tokens": budget,
            "temperature": 0.2,
            "stream": False,
        }
        req = urllib.request.Request(
            url, data=json.dumps(body).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + key,
                "User-Agent": "labos-ai-flow-spec/1.0",
            },
            method="POST",
        )
        # 代理：**只用 http/https 代理，明确不带 socks**。
        # 本机出网要靠 http://127.0.0.1:1082；ALL_PROXY 指向 socks5://127.0.0.1:1080，
        # 那条链路对本脚本不可用（httpx 缺 socksio），所以不把 'all' 交给 ProxyHandler。
        pmap: dict[str, str] = {}
        for scheme, env_keys in (("https", ("https_proxy", "HTTPS_PROXY")),
                                  ("http", ("http_proxy", "HTTP_PROXY"))):
            for k in env_keys:
                v = os.environ.get(k)
                if v:
                    pmap[scheme] = v
                    break
        opener = urllib.request.build_opener(urllib.request.ProxyHandler(pmap))
        try:
            with opener.open(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode()[:400]
            raise RuntimeError(f"DeepSeek 调用失败（HTTP {exc.code}）：{detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(
                f"DeepSeek 连不上（{exc.reason}）。本机出网需要代理时，"
                f"请设 https_proxy（当前 {pmap.get('https', '未设置')}）。"
            ) from exc
        try:
            choice = data["choices"][0]
            text = choice["message"]["content"] or ""
            finish = choice.get("finish_reason")
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(
                f"DeepSeek 返回格式异常：{json.dumps(data, ensure_ascii=False)[:300]}"
            ) from exc
        last_text = text
        if finish != "length":
            return text
        # 截断了 ⇒ 推理 token 吃掉了预算，加倍再来
        budget = min(budget * 3, 32768)
        print(f"  (输出被截断，重试：max_tokens → {budget})", file=sys.stderr)
    raise RuntimeError(
        f"DeepSeek 连续 3 次都把输出截断（finish_reason=length，最后一次 "
        f"max_tokens={budget}）。这通常说明 max_tokens 太小或提示词太长；"
        f"最后一段输出：{last_text[-200:]}"
    )


# ---------------------------------------------------------------- 主流程


def main() -> int:
    ap = argparse.ArgumentParser(description="自然语言 → 流程规格 JSON")
    ap.add_argument("--text", help="流程的自然语言描述")
    ap.add_argument("--island", help="限定岛（如 LAB-BASE-01）；默认所有岛")
    ap.add_argument("--model", default="minimax/MiniMax-M3", help="provider/model")
    ap.add_argument("--max-tokens", type=int, default=4096)
    ap.add_argument("--out", help="把规格写到文件（默认打到 stdout）")
    ap.add_argument("--validate", help="只校验一份已有规格文件，不调模型")
    ap.add_argument("--catalog-only", action="store_true",
                    help="只打印设备目录（调试用）")
    ap.add_argument("--allow-unknown", action="store_true",
                    help="目录里没有的设备也放行（只警告不报错）")
    args = ap.parse_args()

    if not args.text and not args.validate and not args.catalog_only:
        ap.error("需要 --text 或 --validate 或 --catalog-only")

    token = login()
    catalog = fetch_catalog(token, args.island)
    if not catalog:
        print(f"设备目录为空（island={args.island}）—— 换个岛或检查后端", file=sys.stderr)
        return 2

    if args.catalog_only:
        print(catalog_text(catalog))
        return 0

    if args.validate:
        spec = json.loads(Path(args.validate).read_text())
        errors, warnings = validate_spec(spec, catalog)
        for w in warnings:
            print("!", w, file=sys.stderr)
        if errors:
            for e in errors:
                print("✗", e, file=sys.stderr)
            return 1
        print("✓ 规格合法", file=sys.stderr)
        print(json.dumps(spec, ensure_ascii=False, indent=2))
        return 0

    # 目录 + 描述一起喂给模型（目录放在用户消息里，避免 system 里的占位符没替换）
    prompt = (
        f"设备目录：\n{catalog_text(catalog)}\n\n"
        f"用户要的流程：{args.text}\n\n"
        "请只输出流程规格 JSON。"
    )
    try:
        raw = call_llm(prompt, args.model, args.max_tokens)
    except RuntimeError as e:
        print(f"✗ {e}", file=sys.stderr)
        return 3
    try:
        spec = extract_json(raw)
    except RuntimeError as e:
        print(f"✗ {e}", file=sys.stderr)
        return 3

    errors, warnings = validate_spec(spec, catalog)
    for w in warnings:
        print("  !", w, file=sys.stderr)
    if errors and not args.allow_unknown:
        print("规格校验没通过：", file=sys.stderr)
        for e in errors:
            print("  ✗", e, file=sys.stderr)
        print("\n模型原始输出（便于排查）：", file=sys.stderr)
        print(raw[:2000], file=sys.stderr)
        return 1
    for e in errors:
        print("  !", e, file=sys.stderr)

    out = json.dumps(spec, ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).write_text(out)
        print(f"规格已写入 {args.out}", file=sys.stderr)
    else:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
