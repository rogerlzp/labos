#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成设计器 / 设备面板用的本地设备示意图（按 capability 一套一张）。

为什么需要它：库里 `device.icon` 有 62/76 个型号，但绝大多数是**源服务器**
`/image/device/<uuid>.png` 路径，换环境必然 404；仓库里原本只有 2 张占位 SVG。
前端 `webapp/src/pages/Designer.tsx` 的 `iconFor()` 会把这些失效路径按设备能力
映射到本脚本产出的 `/devices/<CAP>.svg`，未知能力回落 `DEVICE.svg`。

这些是**示意图不是实物照片**；现场导入真实照片时，把 `<cap>.svg` 换成
同名文件即可，无需改代码（或用 DeviceRegistrar --image 写 `/devices/...`）。

产物目录：webapp/public/devices/。可重复运行（幂等覆盖）。
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "webapp", "public", "devices")

# capability -> (主色, 中文名, 设备轮廓 SVG 片段)
CAPS = {
    "TRANSFER":    ("#0ea5e9", "机械臂 / 搬运", '<path d="M60 132 L118 66 L196 92" stroke-width="9" fill="none" stroke-linecap="round"/><circle cx="118" cy="66" r="13" fill="none" stroke-width="7"/><path d="M196 92 L196 122 M180 122 L212 122" stroke-width="7" fill="none" stroke-linecap="round"/><rect x="176" y="126" width="40" height="12" rx="3"/>'),
    "INCUBATE":    ("#f59e0b", "培养箱", '<rect x="74" y="42" width="172" height="106" rx="8" fill="none" stroke-width="7"/><path d="M74 76 H246 M74 108 H246" stroke-width="5" fill="none"/><rect x="196" y="56" width="18" height="12" rx="2"/>'),
    "ALIQUOT":     ("#8b5cf6", "移液工作站", '<rect x="62" y="58" width="196" height="78" rx="8" fill="none" stroke-width="7"/><path d="M92 72 V116 M128 72 V116 M164 72 V116 M200 72 V116 M236 72 V116" stroke-width="5" fill="none" stroke-linecap="round"/><path d="M72 136 H248" stroke-width="6" fill="none"/>'),
    "CENTRIFUGE":  ("#ef4444", "离心机", '<circle cx="160" cy="94" r="52" fill="none" stroke-width="7"/><circle cx="160" cy="94" r="12"/><path d="M160 42 V70 M160 118 V146 M108 94 H136 M184 94 H212" stroke-width="5" fill="none" stroke-linecap="round"/>'),
    "SEAL":        ("#10b981", "封膜机", '<rect x="70" y="66" width="180" height="62" rx="7" fill="none" stroke-width="7"/><path d="M70 96 H250" stroke-width="5" fill="none" stroke-dasharray="10 8"/><path d="M160 46 V66" stroke-width="6" fill="none"/>'),
    "STORE":       ("#64748b", "储板栈", '<rect x="86" y="42" width="148" height="106" rx="6" fill="none" stroke-width="7"/><path d="M86 76 H234 M86 110 H234" stroke-width="5" fill="none"/><circle cx="212" cy="59" r="5"/>'),
    "ANALYZE":     ("#06b6d4", "分析仪", '<rect x="76" y="52" width="168" height="92" rx="8" fill="none" stroke-width="7"/><path d="M96 118 L128 92 L156 108 L196 70 L224 92" stroke-width="6" fill="none" stroke-linecap="round"/>'),
    "WASH":        ("#3b82f6", "洗板机", '<rect x="70" y="62" width="180" height="76" rx="8" fill="none" stroke-width="7"/><path d="M104 84 V118 M136 84 V118 M168 84 V118 M200 84 V118" stroke-width="5" fill="none" stroke-linecap="round"/><path d="M96 74 H224" stroke-width="5" fill="none"/>'),
    "SENSE":       ("#84cc16", "传感器", '<circle cx="160" cy="96" r="46" fill="none" stroke-width="7"/><path d="M160 96 A24 24 0 0 1 184 72 M160 96 A40 40 0 0 1 200 56" stroke-width="5" fill="none" stroke-linecap="round"/><circle cx="160" cy="96" r="8"/>'),
    "DECAP":       ("#f97316", "开盖机", '<rect x="82" y="76" width="156" height="62" rx="6" fill="none" stroke-width="7"/><path d="M112 76 V48 H208 V76" stroke-width="7" fill="none"/><path d="M82 108 H238" stroke-width="5" fill="none"/>'),
    "SCAN":        ("#14b8a6", "扫码器", '<rect x="78" y="58" width="164" height="90" rx="8" fill="none" stroke-width="7"/><path d="M104 78 V128 M120 78 V128 M132 78 V128 M156 78 V128 M172 78 V128 M188 78 V128 M204 78 V128" stroke-width="4" fill="none"/>'),
    "PEEL":        ("#eab308", "撕膜机", '<rect x="76" y="70" width="168" height="68" rx="7" fill="none" stroke-width="7"/><path d="M92 104 Q140 62 188 104 T244 104" stroke-width="6" fill="none" stroke-linecap="round"/>'),
    "READ_PLATE":  ("#a855f7", "读板机", '<rect x="80" y="54" width="160" height="96" rx="8" fill="none" stroke-width="7"/><circle cx="120" cy="88" r="9"/><circle cx="160" cy="88" r="9"/><circle cx="200" cy="88" r="9"/><circle cx="120" cy="122" r="9"/><circle cx="160" cy="122" r="9"/><circle cx="200" cy="122" r="9"/>'),
    "PICK":        ("#ec4899", "挑克隆", '<path d="M160 42 V112" stroke-width="7" fill="none" stroke-linecap="round"/><path d="M144 112 L160 136 L176 112" stroke-width="6" fill="none"/><circle cx="108" cy="132" r="7"/><circle cx="140" cy="140" r="7"/><circle cx="184" cy="140" r="7"/><circle cx="216" cy="132" r="7"/>'),
    "DEVICE":      ("#94a3b8", "通用设备", '<rect x="84" y="54" width="152" height="96" rx="9" fill="none" stroke-width="7"/><circle cx="160" cy="94" r="26" fill="none" stroke-width="6"/><path d="M112 132 H208" stroke-width="5" fill="none"/>'),
}

TPL = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" '
    'viewBox="0 0 320 200">'
    '<rect width="320" height="200" rx="14" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="2"/>'
    '<rect x="0" y="0" width="320" height="8" rx="4" fill="{color}"/>'
    '<g stroke="{color}" fill="{color}">{art}</g>'
    '<text x="160" y="176" text-anchor="middle" font-family="sans-serif" '
    'font-size="16" font-weight="bold" fill="#334155">{label}</text>'
    '<text x="160" y="193" text-anchor="middle" font-family="sans-serif" '
    'font-size="10" fill="#94A3B8">{cap} · 示意圖</text>'
    '</svg>'
)


def main():
    os.makedirs(OUT, exist_ok=True)
    for cap, (color, label, art) in CAPS.items():
        path = os.path.join(OUT, cap + ".svg")
        with open(path, "w", encoding="utf-8") as f:
            f.write(TPL.format(color=color, label=label, cap=cap, art=art))
    print(f"生成 {len(CAPS)} 张设备示意图 -> {os.path.abspath(OUT)}")


if __name__ == "__main__":
    main()
