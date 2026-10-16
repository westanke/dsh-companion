#!/usr/bin/env bash
# CI 专用（.github/workflows/mirror-sync.yml）：
# 把主源 Gitee（不可达时回退备源 AtomGit）以 push --mirror 方式同步到 GitHub 本仓。
#
# 为什么是 --mirror：GitHub 端定位为「从站镜像」，refs 集合必须与主源逐字节一致——
# 包括「主源删掉的分支/tag，从站也要删掉」。普通 push 做不到删除面。
# GitHub Actions 的 GITHUB_TOKEN 触发的推送不会再触发别的 workflow（防递归，见 GitHub 文档）。
#
# 本地不要跑这个脚本：它强推当前 GitHub 仓库；本地日常推送用 scripts/push-all.sh。
set -euo pipefail

: "${GITHUB_TOKEN:?GITHUB_TOKEN 未注入}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY 未注入}"

# 顺序即优先级：Gitee 主站 → AtomGit 备站
SOURCES=(
  "Gitee|https://gitee.com/westanke/dsh-companion.git"
  "AtomGit|https://atomgit.com/westanke163/dsh-companion.git"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

src_label=""
src_url=""
for entry in "${SOURCES[@]}"; do
  label="${entry%%|*}"
  url="${entry#*|}"
  echo "::group::探测源站：$label"
  if git ls-remote --heads "$url" >/dev/null 2>&1; then
    src_label="$label"
    src_url="$url"
    echo "$label 可达，选作本次同步源"
    echo "::endgroup::"
    break
  fi
  echo "$label 不可达，尝试下一个源"
  echo "::endgroup::"
done

if [ -z "$src_url" ]; then
  echo "::error::Gitee 与 AtomGit 均不可达，本次跳过（不改动 GitHub）"
  exit 1
fi

echo "::group::从 $src_label 裸克隆（--mirror，含全部分支与 tag）"
git clone --mirror "$src_url" "$WORK/mirror.git"
echo "::endgroup::"

cd "$WORK/mirror.git"
git remote set-url --push origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

echo "::group::push --mirror → github.com/${GITHUB_REPOSITORY}"
git push --mirror origin
echo "::endgroup::"

echo "✅ 镜像完成（源：$src_label）"
