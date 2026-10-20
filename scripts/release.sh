#!/usr/bin/env bash
# 一键发版（两阶段）：准备 → 提交 → 发布。
#
# ## 为什么是两阶段
#
# `docs/project/RELEASE.md` 的顺序铁律是：② 改文档与版本号 → ⑤ 打包并**回填真实数据**
# → ⑥ git 提交 → ⑦ 推送与发布。**回填发生在提交之前**，所以脚本不能一口气跑到底：
# 打包后 README 会变，那批改动必须先提交，才能打 tag 推送（否则 tag 指向的提交里
# 文档还是旧数字 —— 正是「文档描述一个不存在的状态」）。
#
# 所以：
#   bash scripts/release.sh             # 阶段 A：检查 → 门禁 → 打包 → 回填真实数据
#   git add … && git commit …           # 人工提交（README/SHA 数字已由 A 填好）
#   bash scripts/release.sh --publish   # 阶段 B：tag → 双推 → 两站 Release → 全渠道回读
#
# ## 从 bridge 抄来的教训
#
# 姊妹仓 v2.4.2 发版时漏了 npm（用户追问才发现），根因是「靠人记住清单」。
# 本仓对应的高频失分点是 **README 双站漂移**（`docsCheck` 不覆盖 README）：
# v2.2.33–v2.2.35 连续三版都漂过（中文停 2.2.32、英文停 24 类/256 用例）。
# 所以阶段 A 的**回填与校验**是本脚本的核心，不是附属功能。
#
# 用法：
#   bash scripts/release.sh                 # 阶段 A
#   bash scripts/release.sh --publish       # 阶段 B
#   DRY_RUN=1 …                             # 只打印计划
#   SKIP_CHECK=1 …                          # 跳过门禁（已在别处跑过）

set -euo pipefail
cd "$(dirname "$0")/.."

MODE="prepare"
[ "${1:-}" = "--publish" ] && MODE="publish"
DRY_RUN=${DRY_RUN:-0}

say() { printf '\033[36m%s\033[0m\n' "$*"; }
ok()  { printf '\033[32m  ✅ %s\033[0m\n' "$*"; }
warn(){ printf '\033[33m  ⚠️ %s\033[0m\n' "$*"; }
bad() { printf '\033[31m  ❌ %s\033[0m\n' "$*"; }
run() { if [ "$DRY_RUN" = "1" ]; then say "  [dry] $*"; else "$@"; fi; }

GRADLE="app/build.gradle.kts"
VER=$(grep -oP '^\s+versionName = "\K[^"]+' "$GRADLE" | tail -1)
CODE=$(grep -oP '^\s+versionCode = \K[0-9]+' "$GRADLE" | tail -1)
TAG="v${VER}"
APK_NAME="DSH伴侣-companion-${TAG}.apk"
APK="artifacts/${APK_NAME}"
REPO_GITEE="westanke/dsh-companion"
REPO_ATOMGIT="westanke163/dsh-companion"

[ -n "$VER" ] && [ -n "$CODE" ] || { bad "没能从 $GRADLE 解析出版本号"; exit 1; }

# ══════════════════════════════════════════════════════════════════
if [ "$MODE" = "prepare" ]; then
  say "═══ 阶段 A · 准备 ${TAG}（versionCode ${CODE}）═══"
  say ""

  say "0) 前置检查"
  [ -n "$(git status --porcelain)" ] && warn "工作区有待提交改动（阶段 A 允许，阶段 B 会再查）" || ok "工作区干净"
  # 版本号与逐版注释必须对得上：注释里最后一条 vX.Y.Z 应该就是当前 versionName。
  LAST_NOTE=$(grep -oP '^\s+// v\K[0-9]+\.[0-9]+\.[0-9]+' "$GRADLE" | tail -1)
  if [ "$LAST_NOTE" = "$VER" ]; then
    ok "逐版注释最后一条是 v$LAST_NOTE，与 versionName 一致"
  else
    bad "逐版注释最后一条是 v${LAST_NOTE:-（无）}，但 versionName 是 $VER —— 补上本版注释再发"
    exit 1
  fi
  ok "versionCode $CODE / versionName $VER / tag $TAG"
  say ""

  if [ "${SKIP_CHECK:-0}" != "1" ]; then
    say "1) 门禁（docsCheck + 单测 + lint）"
    run scripts/build.sh docsCheck
    run scripts/build.sh :app:testDebugUnitTest
    run scripts/build.sh :app:lintDebug
    ok "门禁通过"
  else
    say "1) 门禁 —— 已按 SKIP_CHECK=1 跳过"
  fi
  say ""

  say "2) 打包 assembleRelease"
  run scripts/build.sh assembleRelease
  SRC="app/build/outputs/apk/release/app-release.apk"
  if [ "$DRY_RUN" != "1" ]; then
    [ -f "$SRC" ] || { bad "没找到 $SRC"; exit 1; }
    mkdir -p artifacts
    cp "$SRC" "$APK"
    SIZE=$(stat -c%s "$APK")
    SHA=$(sha256sum "$APK" | cut -d' ' -f1)
    ok "产物 $APK_NAME"
    ok "体积 $SIZE 字节"
    ok "SHA-256 $SHA"
  else
    SIZE=0; SHA="(dry)"; say "  [dry] 复制并计算指纹"
  fi
  say ""

  say "3) 回填真实数据（README 双站 + SHA256SUMS）"
  if [ "$DRY_RUN" = "1" ]; then
    say "  [dry] 跳过回填"
  else
    python3 scripts/backfill-release.py "$VER" "$CODE" "$SIZE" "$SHA" "$APK_NAME"
    {
      echo "# dsh-companion 产物校验（${TAG}）"
      echo "$SHA  $APK_NAME"
    } > artifacts/SHA256SUMS.txt
    ok "已写 artifacts/SHA256SUMS.txt"

    # 回填完立刻自检：README 里不该再出现旧版本号（最容易漏的是英文站）。
    STALE=$(grep -c "v2\.[0-9]*\.[0-9]*" README.md README.en.md 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')
    say "   （README 双站当前版本号引用共 $STALE 处，应全部是 ${TAG}）"
    for f in README.md README.en.md; do
      [ -f "$f" ] || continue
      mix=$(grep -oE "v2\.[0-9]+\.[0-9]+" "$f" | sort -u | grep -v "^v${VER}$" || true)
      [ -n "$mix" ] && warn "$f 里还有别的版本号：$(echo "$mix" | tr '\n' ' ')"
    done

    say ""
    say "接下来（人工）："
    say "  1) 校对 README 双站的**文字**描述（数字已自动填，文字仍需人写）"
    say "  2) git add 点名的路径（不要 git add -A）→ git commit"
    say "  3) bash scripts/release.sh --publish"
  fi
  exit 0
fi

# ══════════════════════════════════════════════════════════════════
say "═══ 阶段 B · 发布 ${TAG} ═══"
say ""

say "0) 前置检查"
if [ -n "$(git status --porcelain)" ]; then
  bad "工作区有未提交改动 —— 先提交，否则 tag 会指向一个文档还没更新的提交"
  git status --short | head -10
  exit 1
fi
[ -f "$APK" ] || { bad "没有产物 $APK —— 先跑阶段 A"; exit 1; }
SIZE=$(stat -c%s "$APK")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
ok "工作区干净、产物存在（$SIZE 字节）"

# 产物指纹必须与 README 里写的一致，否则就是「文档描述另一个包」。
if grep -q "$SHA" README.md && grep -q "$SHA" README.en.md; then
  ok "README 双站的 SHA-256 与产物一致"
else
  bad "README 里的 SHA-256 与产物不符 —— 回填没做完或漏了一站"
  exit 1
fi

GT=$(grep -m1 "gitee.com" ~/.git-credentials 2>/dev/null | sed 's|https://[^:]*:||; s|@gitee.com||' || true)
AT=$(grep -m1 "atomgit.com" ~/.git-credentials 2>/dev/null | sed 's|https://[^:]*:||; s|@atomgit.com||' || true)
[ -n "$GT" ] && ok "Gitee token 就绪" || warn "没有 Gitee token，Release 会跳过"
[ -n "$AT" ] && ok "AtomGit token 就绪" || warn "没有 AtomGit token，Release 会跳过"
say ""

say "1) 打 tag $TAG"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  ok "本地已有 $TAG，跳过"
else
  run git tag -a "$TAG" -m "dsh-companion ${VER}"
  ok "已创建 $TAG"
fi
say ""

say "2) git 双推（Gitee 主站 + AtomGit 备站，复用 push-all.sh）"
if [ "$DRY_RUN" = "1" ]; then
  say "  [dry] PUSH_TAGS=1 bash scripts/push-all.sh"
else
  PUSH_TAGS=1 bash scripts/push-all.sh
  ok "双推完成"
fi
say ""

say "3) 两站 Release + APK 附件"
gitee_release() {
  [ -z "$GT" ] && { warn "无 Gitee token，跳过"; return 0; }
  local id
  id=$(curl -s "https://gitee.com/api/v5/repos/$REPO_GITEE/releases/tags/$TAG?access_token=$GT" \
       | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{console.log(JSON.parse(s).id||'')}catch{console.log('')}})" 2>/dev/null || true)
  if [ -z "$id" ]; then
    say "  创建 Gitee Release $TAG…"
    [ "$DRY_RUN" = "1" ] && return 0
    local payload
    payload=$(VER="$VER" TAG="$TAG" APK_NAME="$APK_NAME" SIZE="$SIZE" SHA="$SHA" node -e '
      const body = [
        `## ${process.env.TAG}`,` `,
        `- 产物：\`${process.env.APK_NAME}\`，**${Number(process.env.SIZE).toLocaleString("en-US")} 字节**`,
        `- SHA-256：\`${process.env.SHA}\``,` `,
        `变更详情见仓库 docs/project/CHANGELOG-DEV.md 的 ${process.env.TAG} 一节。`,
        `完整发布说明见 Gitee Release 正文（由 RELEASE_NOTES 维护）。`
      ].join("\n");
      process.stdout.write(JSON.stringify({tag_name:process.env.TAG,name:process.env.VER,body,target_commitish:"main"}));
    ')
    # `access_token` 必须随请求走：Gitee 创建接口不接受把它塞进 JSON body，
    # 而下面 `-d "$payload"` 就是 JSON 正文，所以只能挂在 query 上。
    # 2026-10-21 漏了这一步，v2.2.47 首跑报「登录失效，无权限访问该资源」（40001）——
    # 同一个 token 做 GET 一切正常，说明不是凭据过期，纯粹是 POST 没带鉴权。
    id=$(curl -s -X POST "https://gitee.com/api/v5/repos/$REPO_GITEE/releases?access_token=$GT" \
         -H 'Content-Type: application/json' -d "$payload" \
         | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{console.log(JSON.parse(s).id||'')}catch{console.log('')}})")
    [ -n "$id" ] && ok "Gitee Release 已建（id $id）" || { bad "Gitee Release 建失败"; return 1; }
  else
    ok "Gitee Release 已存在（id $id），跳过创建"
  fi
  local have
  have=$(curl -s "https://gitee.com/api/v5/repos/$REPO_GITEE/releases/$id?access_token=$GT" \
        | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{console.log((JSON.parse(s).assets||[]).map(a=>a.name).join(' '))}catch{console.log('')}})")
  for f in "$APK" artifacts/SHA256SUMS.txt; do
    [ -f "$f" ] || continue
    local base; base=$(basename "$f")
    if echo " $have " | grep -qF " $base "; then
      ok "Gitee 已有 $base"
    else
      [ "$DRY_RUN" = "1" ] && { say "  [dry] 上传 $base"; continue; }
      curl -s -X POST "https://gitee.com/api/v5/repos/$REPO_GITEE/releases/$id/attach_files" \
        -F "access_token=$GT" -F "file=@$f" >/dev/null
      ok "Gitee 已传 $base"
    fi
  done
}

atomgit_release() {
  [ -z "$AT" ] && { warn "无 AtomGit token，跳过"; return 0; }
  local cur
  cur=$(curl -s -H "Authorization: Bearer $AT" \
        "https://atomgit.com/api/v5/repos/$REPO_ATOMGIT/releases?per_page=1" \
        | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const d=JSON.parse(s);console.log((d[0]||{}).tag_name||'')}catch{console.log('')}})" 2>/dev/null || true)
  if [ "$cur" = "$TAG" ]; then
    ok "AtomGit Release 已存在（$TAG），跳过"
  else
    [ "$DRY_RUN" = "1" ] && { say "  [dry] 创建 AtomGit Release $TAG"; return 0; }
    local payload
    payload=$(VER="$VER" TAG="$TAG" APK_NAME="$APK_NAME" SIZE="$SIZE" SHA="$SHA" node -e '
      const body = [
        `## ${process.env.TAG}`,` `,
        `- 产物：\`${process.env.APK_NAME}\`，**${Number(process.env.SIZE).toLocaleString("en-US")} 字节**`,
        `- SHA-256：\`${process.env.SHA}\``,` `,
        `下载：https://gitee.com/westanke/dsh-companion/releases/download/${process.env.TAG}/${encodeURIComponent(process.env.APK_NAME)}`
      ].join("\n");
      process.stdout.write(JSON.stringify({tag_name:process.env.TAG,name:process.env.VER,body,target_commitish:"main"}));
    ')
    curl -s -X POST "https://atomgit.com/api/v5/repos/$REPO_ATOMGIT/releases" \
      -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "$payload" >/dev/null
    ok "AtomGit Release 已建（正文含 Gitee 下载链接）"
  fi
  warn "AtomGit 不支持附件上传（API 不存在），APK 以 Gitee 为准"
}

gitee_release
atomgit_release
say ""

say "4) 全渠道回读"
if [ "$DRY_RUN" = "1" ]; then
  say "  [dry] 跳过回读"
  exit 0
fi
rc=0
ENC=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$APK_NAME")
code=$(curl -sL -o /dev/null -w '%{http_code}' --max-time 180 \
  "https://gitee.com/${REPO_GITEE}/releases/download/${TAG}/${ENC}")
[ "$code" = "200" ] && ok "Gitee: APK 可下载（200）" || { bad "Gitee: APK 返回 $code"; rc=1; }

for r in gitee atomgit; do
  if [ -n "$(git ls-remote --tags "$r" "refs/tags/$TAG" 2>/dev/null)" ]; then
    ok "$r: tag $TAG 已就位"
  else
    bad "$r: tag $TAG 缺失"; rc=1
  fi
done

# 下载回来的字节数必须与本地产物的 SHA 对得上（防「传了个旧包」）。
if [ "$rc" -eq 0 ]; then
  TMP_DL="artifacts/.verify-${TAG}.apk"
  curl -sL -o "$TMP_DL" --max-time 300 "https://gitee.com/${REPO_GITEE}/releases/download/${TAG}/${ENC}"
  DL_SHA=$(sha256sum "$TMP_DL" | cut -d' ' -f1)
  rm -f "$TMP_DL"
  if [ "$DL_SHA" = "$SHA" ]; then
    ok "下载回来的包 SHA-256 与本地一致（没有传错包）"
  else
    bad "下载回来的包 SHA 不一致：$DL_SHA vs $SHA"; rc=1
  fi
fi

say ""
if [ "$rc" -eq 0 ]; then
  ok "═══ 发版完成：双站 tag + 两站 Release + APK 全部就位 ═══"
  say "GitHub 从站由 mirror-sync workflow 每 3 小时自动对齐（也可去 Actions 手动 dispatch）。"
else
  bad "═══ 有渠道未就位，见上方 ❌ ═══"
fi
exit "$rc"
