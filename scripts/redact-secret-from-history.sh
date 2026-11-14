#!/usr/bin/env bash
# ============================================================================
# 历史脱敏 · **在隔离克隆里重写**（不碰当前工作区、不自动推送）
#
# 为什么要做：明文生产库口令曾经进过提交历史。工作区与 HEAD 已清零，
# 但**历史里还在** ⇒ 拿到仓库的人翻历史就能看到。
# 正确做法是重写历史 —— 而重写会改 SHA，所以**必须在隔离克隆里做**，
# 当前工作区（可能有并行会话的未提交改动）一个字节都不动。
#
# ⚠⚠ **本脚本里绝不写死要脱敏的字面量** —— 那等于又留一份明文！
#   字面量从环境变量 **LABOS_REDACT_SECRETS** 传入（多个用换行分隔）：
#
#     LABOS_REDACT_SECRETS=$'口令A\n口令B' ./scripts/redact-secret-from-history.sh
#
# ⚠ **本脚本只做到"生成新历史"为止，不推送**。推送是 owner 的决定
#   （owner 已裁定"生产口令轮换不再作为待办"，但历史里的明文仍在）。
#   后续步骤脚本会打印出来，人工确认后再执行。
#
# 用法：
#   ./scripts/redact-secret-from-history.sh --show     # 只盘点，不改任何东西
#   ./scripts/redact-secret-from-history.sh            # 生成新历史（不推送）
#
# 做完之后（owner 决定要推送时）：
#   1) 用脚本打印的步骤 force-push
#   2) 通知所有协作者：历史已重写，请重新 clone
#   3) 轮换生产库口令（旧口令已视为泄露）
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${LABOS_REDACT_WORK:-/tmp/labos-redact-$$}"
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
REMOTE="${LABOS_REDACT_REMOTE:-origin}"
PLACEHOLDER="${LABOS_REDACT_PLACEHOLDER:-***REDACTED***}"

# 要脱敏的字面量：**只从环境变量读**（多个用换行分隔）。绝不写死在脚本里。
SECRETS=()
if [[ -n "${LABOS_REDACT_SECRETS:-}" ]]; then
  while IFS= read -r line; do
    [[ -n "$line" ]] && SECRETS+=("$line")
  done <<< "$LABOS_REDACT_SECRETS"
fi

note() { printf '\n\033[1m%s\033[0m\n' "$1"; }
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }

if [[ ${#SECRETS[@]} -eq 0 ]]; then
  note "用法"
  cat <<'EOF'
  要脱敏的字面量通过环境变量传入（**不要写进脚本** —— 那等于又留一份明文）：

    LABOS_REDACT_SECRETS=$'口令A\n口令B' ./scripts/redact-secret-from-history.sh --show
    LABOS_REDACT_SECRETS=$'口令A\n口令B' ./scripts/redact-secret-from-history.sh
EOF
  exit 2
fi

note "0. 当前命中盘点（不改任何东西）"
for s in "${SECRETS[@]}"; do
  n=$(git -C "$ROOT" grep -l -e "$s" HEAD -- 2>/dev/null | wc -l | tr -d ' ' || true)
  printf '  （字面量 %d 个字符）→ HEAD 命中 %s 个文件\n' "${#s}" "$n"
done

if [[ "${1:-}" == "--show" ]]; then
  exit 0
fi

note "1. 建隔离克隆（当前工作区不动）"
rm -rf "$WORK"
git clone --no-local --mirror "$ROOT" "$WORK" >/dev/null 2>&1 \
  || git clone "$ROOT" "$WORK" >/dev/null 2>&1
if [[ ! -d "$WORK/.git" ]]; then
  warn "克隆失败：$WORK"
  exit 4
fi
pass "隔离克隆：$WORK"

note "2. 检查 git-filter-repo"
if ! command -v git-filter-repo >/dev/null 2>&1 && ! python3 -c "import git_filter_repo" 2>/dev/null; then
  warn "没装 git-filter-repo —— 请先执行："
  warn "  python3 -m pip install git-filter-repo"
  warn "装完重跑本脚本。"
  exit 3
fi

note "3. 重写（把字面量替换成占位符）"
RULES="$WORK/../filter-rules.txt"
: > "$RULES"
for s in "${SECRETS[@]}"; do
  printf 'literal:%s==>%s\n' "$s" "$PLACEHOLDER" >> "$RULES"
done
chmod 600 "$RULES"

git -C "$WORK" filter-repo --force --replace-text "$RULES" --refs "$BRANCH" >/dev/null
pass "重写完成"

note "4. 核对（重写后不应再有命中）"
for s in "${SECRETS[@]}"; do
  n=$(git -C "$WORK" grep -l -e "$s" "$BRANCH" -- 2>/dev/null | wc -l | tr -d ' ' || true)
  if [[ "$n" == "0" ]]; then
    pass "重写后 0 命中"
  else
    warn "重写后仍有 $n 个命中（检查 --refs 是否覆盖了目标分支）"
  fi
done

note "5. 产出的新提交"
git -C "$WORK" log --oneline -3 "$BRANCH" 2>/dev/null || true

cat <<EOF

\033[1m后续步骤（owner 决定，本脚本不做）\033[0m
─────────────────────────────────────────────────────────────
新历史在隔离克隆里：$WORK

  # ① 推新历史（会改远端 SHA —— 确认后再做）
  git -C "$WORK" push --force-with-lease "$REMOTE" "refs/heads/$BRANCH:refs/heads/$BRANCH"

  # ② 通知协作者：历史已重写，请重新 clone
  #    （已经拉过的人，本地旧历史里仍有明文 ⇒ 建议彻底删掉重 clone）

  # ③ 轮换生产库口令（旧口令已视为泄露）

  # ④ 确认远端已干净（用同一个环境变量复核）：
  git fetch "$REMOTE"
  LABOS_REDACT_SECRETS='...' git grep -l -e "\$LABOS_REDACT_SECRETS" "$REMOTE/$BRANCH" -- || echo "远端已无明文"

  # ⑤ 清理隔离克隆
  rm -rf "$WORK"
EOF
