#!/usr/bin/env bash
# 一次推送到主、备两个远端：Gitee（主）+ AtomGit（备）。
#
# 渠道拓扑（2026-10-16 起）：
#   gitee    -> Gitee    gitee.com/westanke/dsh-companion.git        （主站）
#   atomgit  -> AtomGit  atomgit.com/westanke163/dsh-companion.git  （备站）
#   origin   -> GitHub   github.com/westanke/dsh-companion.git       （从站，自动同步）
#
# GitHub 不再手工推送：本机到 github.com:443 长期被阻断，且 GitHub 端由
# .github/workflows/mirror-sync.yml 定时 + 手动从 Gitee 自动同步（含 tag）。
# 确有需要手工补推 GitHub 时加 INCLUDE_GITHUB=1（链路不通会独立失败，不影响主备）。
#
# 为什么分开推而不是给一个 remote 塞多个 pushurl：分开推各自独立报告，
# 一个站挂了不拖死另一个。
#
# 用法：
#   bash scripts/push-all.sh                     # 推当前分支（默认 main）到 Gitee + AtomGit
#   PUSH_TAGS=1 bash scripts/push-all.sh          # 推全部 tag
#   BRANCH=dev bash scripts/push-all.sh           # 换分支
#   FORCE=1 bash scripts/push-all.sh              # 历史分叉时 --force-with-lease（默认绝不强推）
#   INCLUDE_GITHUB=1 bash scripts/push-all.sh     # 同时尽力推 GitHub
#
# 首次配置远端：bash scripts/setup-remotes.sh
set -uo pipefail

BRANCH="${BRANCH:-main}"
PUSH_TAGS="${PUSH_TAGS:-0}"
FORCE="${FORCE:-0}"
INCLUDE_GITHUB="${INCLUDE_GITHUB:-0}"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; }

# ── 远端自检 ────────────────────────────────────────────────────────────
missing=0
for r in gitee atomgit; do
  if ! git remote get-url "$r" >/dev/null 2>&1; then
    bad "尚未配置 $r remote"
    missing=1
  fi
done
if [ "$INCLUDE_GITHUB" = "1" ] && ! git remote get-url origin >/dev/null 2>&1; then
  bad "INCLUDE_GITHUB=1 但未配置 origin remote"
  missing=1
fi
if [ "$missing" -ne 0 ]; then
  say ""
  say "先配远端：bash scripts/setup-remotes.sh"
  exit 1
fi

# ── 推送清单（顺序 = 主 → 备 → [从]）────────────────────────────────────
PAIRS=("gitee:Gitee" "atomgit:AtomGit")
[ "$INCLUDE_GITHUB" = "1" ] && PAIRS+=("origin:GitHub")

force_args=()
[ "$FORCE" = "1" ] && force_args=(--force-with-lease)

fail=0

if [ "$PUSH_TAGS" = "1" ]; then
  say "推送全部 tag（${#PAIRS[@]} 个远端，各自独立）…"
  [ "$FORCE" = "1" ] && say "（FORCE=1：走 --force-with-lease 强制覆盖）"
  say ""
  for pair in "${PAIRS[@]}"; do
    r="${pair%%:*}"; label="${pair##*:}"
    say "→ $label ($r)"
    if git push "${force_args[@]}" "$r" --tags; then
      ok "$label tag 推送成功"
    else
      bad "$label tag 推送失败"
      fail=1
    fi
    say ""
  done
else
  say "推送分支 $BRANCH（${#PAIRS[@]} 个远端，各自独立）…"
  [ "$FORCE" = "1" ] && say "（FORCE=1：走 --force-with-lease 强制覆盖）"
  say ""
  for pair in "${PAIRS[@]}"; do
    r="${pair%%:*}"; label="${pair##*:}"
    say "→ $label ($r)"
    if git push "${force_args[@]}" "$r" "$BRANCH"; then
      ok "$label 推送成功"
    else
      bad "$label 推送失败"
      if git rev-parse --verify -q "$r/$BRANCH" >/dev/null 2>&1 \
         && ! git merge-base --is-ancestor "$r/$BRANCH" HEAD 2>/dev/null; then
        say "    ⚠️ 检测到历史分叉：$r/$BRANCH 不是本地 $BRANCH 的祖先"
        say "    确认内容一致后可用 FORCE=1 强制覆盖：FORCE=1 bash scripts/push-all.sh"
      fi
      fail=1
    fi
    say ""
  done
fi

if [ "$fail" -eq 0 ]; then
  if [ "$INCLUDE_GITHUB" = "1" ]; then
    ok "三端都已更新"
  else
    ok "主备两站已更新（GitHub 从站等自动同步）"
  fi
else
  bad "至少有一个远端推送失败，见上方明细"
fi
exit "$fail"
