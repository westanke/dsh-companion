# 发版流程（RELEASE）

> 本文件是 `AGENTS.md` §0 路由表的落点，把散在 `CHANGELOG-DEV.md`、`README`、`STATUS.md`
> 里的发版口径收敛到一处。**用户既定规矩优先于本文**——两者冲突时以用户当次指示为准。

## 推荐路径：`bash scripts/release.sh`（两阶段）

发版是七步清单，不是「记得的那几件」。脚本把顺序固化，**并因此分成两个阶段**：

```sh
bash scripts/release.sh              # 阶段 A：检查 → 门禁 → 打包 → 回填真实数据
git add … && git commit …            # 人工提交（数字已由 A 填好，文字仍需人写）
bash scripts/release.sh --publish    # 阶段 B：tag → 双推 → 两站 Release → 全渠道回读
```

**为什么必须两阶段**：本仓的顺序铁律是「⑤ 回填 → ⑥ 提交 → ⑦ 推送」。回填发生在提交**之前**，
所以不能一口气跑到底——打包后 README 会变，那批改动必须先提交，tag 才不会指向一份
「文档还是旧数字」的提交。

阶段 A 的**回填**是核心，因为本仓最高频的失分点就是 **README 双站漂移**
（`docsCheck` 不覆盖 README；v2.2.33–v2.2.35 连续三版漂过）。
回填由 `scripts/backfill-release.py` 做，**只动锚点定位的几处**：

| 改什么 | 锚点 |
|---|---|
| 版本 + versionCode | `- 当前版本：`vX`（`versionCode` N）` |
| APK 文件名 | `DSH伴侣-companion-vX.apk` |
| 体积 | `**N 字节**` / `**N bytes**` |
| SHA-256 | 前面明确写着 `SHA-256` 的那一处 |
| 已发 tag | `tag `vX`` |

> **不许用 `v2\.\d+\.\d+` 全局替换**：那会把 README 里 v2.2.41 / v2.2.40 这些
> **历史版本记录**一并改成当前版本——那不是回填，是篡改历史。（写脚本时第一版就是这么错的，
> 自查时发现并改掉了。）回填脚本末尾还会交叉检查双站是否都能查到当前版本/体积/SHA。

阶段 B 除了打 tag 与双推，还多做两件事：**校验 README 里的 SHA 与产物一致**（否则就是
「文档描述另一个包」），以及**把 Release 上的 APK 下载回来比对 SHA**（防「传了个旧包」）。

---

## 顺序（不可倒）

```
① 改代码
② 改文档与版本号        ← app/build.gradle.kts 逐版注释 + versionCode/versionName
③ scripts/build.sh 校验  ← docsCheck + testDebugUnitTest
④ 同步实装目录          ← 只读实装副本
⑤ 打包 + 回填真实数据    ← APK 大小与 SHA-256 写回文档
⑥ git 提交（不推送不发布）
⑦ 等用户发话 → 推主备（Gitee + AtomGit）+ 打 tag + Gitee 发 Release；GitHub 从站自动同步
```

**为什么 ② 必须在 ③/④ 之前**：先同步产物再改文档/版本号，会让文档描述一个**不存在的状态**
（用户原话：「以后应该先改文档在同步，要不你改的文档是没同步状态」）。

## ② 必须改的地方（清单）

| 文件 | 改什么 |
|---|---|
| `app/build.gradle.kts` | `versionCode` +1、`versionName`、**逐版中文变更注释** |
| `docs/project/CHANGELOG-DEV.md` | 每条写清**基于什么 / 为什么 / 干了什么 / 测了什么** |
| `docs/project/STATUS.md` | 日期、阶段、版本线追加一条、最近验证用例数 |
| `README.md` + `README.en.md` | 下载区 APK 名、项目信息（版本/versionCode/APK 大小/SHA-256/签名/测试数/bridge 基线） |
| `docs/project/TESTING.md` | 追加本轮用例数与门验结果 |

**README 双站是最高频失分点**：`docsCheck` 不覆盖它。对账命令见 `PITFALLS.md` §README 双站漂移。

## ③ 门验

```sh
scripts/build.sh docsCheck                 # 8 文档 / ROADMAP 任务 / STATUS 引用
scripts/build.sh :app:testDebugUnitTest    # 全量单测
scripts/build.sh assembleRelease           # 发布包（需签名配置）
```

> `assembleRelease` 需要专用签名，否则构建失败并提示
> `Dedicated signing config is required. Set dshSigningProperties.`（`app/build.gradle.kts:693`）。
> 签名来源优先级见该文件头部注释：① `-PdshSigningProperties=` ② gradle 属性 ③ 环境变量。
> **签名文件与密码绝不入库。**

## ⑤ 回填真实数据

```sh
stat -c '%s' "artifacts/<APK 文件名>"     # 字节数（v2.2.41 起文件名含中文应用名 DSH伴侣）
sha256sum "artifacts/<APK 文件名>"        # 摘要
```

把这两个真实值写回 README 双站与 `artifacts/SHA256SUMS.txt`。
**不许抄上一版的数字**——体积每次都不同。

## ⑥ 提交纪律

- 提交信息照既有体例：`docs：…` / `v2.2.x：…`，正文写基于什么/为什么/干了什么/测了什么。
- 只 `git add` 自己点名的路径，**绝不 `git add -A`**（`PITFALLS.md` §并发写者与未提交改动）。
- **提交 ≠ 可发布**。对外表述必须区分「已合并」「已发布」「用户吃到了」三态。

## ⑦ 推送与发布（必须用户点头）

三端拓扑（2026-10-16 起）：**Gitee 主 / AtomGit 备 / GitHub 镜像从站**。

- 主备推送：`bash scripts/push-all.sh`（推 Gitee + AtomGit 的 main），`PUSH_TAGS=1 bash scripts/push-all.sh` 推标签；
  远端命名先跑 `bash scripts/setup-remotes.sh` 整理。**默认不推 GitHub**（`INCLUDE_GITHUB=1` 才顺带试，本机 github.com:443 通常不通）。
- Release 只在 **Gitee** 手工发（正文 + APK / SHA256SUMS.txt / RELEASE_NOTES；tag 名的 zip/tar.gz Gitee 自动生成）。
- **GitHub 全自动**：`.github/workflows/mirror-sync.yml` 每 3 小时（也可 Actions 页手动 dispatch）
  `git push --mirror` 对齐 refs，再由 `scripts/ci/sync-releases.py` 幂等回填 Release 与三类资产。
  发版后想立刻见效，去 GitHub Actions 手动触发一次；否则等下个整点后 17 分。
- **禁止 `--force`** 到主备两站；GitHub 从站的强推由仓库内 workflow 用 GITHUB_TOKEN 执行（镜像语义，非人工）。
- 链接：本仓库自身以 Gitee 为主链接；上游引用保持 GitHub 不改；姊妹仓库沿用各自拓扑。

## 产物目录纪律

`artifacts/` 只保留「**必须的**」（两个上游基线包 `DeepSeek-Harness-v1.0.0.apk`、
`DeepSeek-Harness-compat02-release.apk`）与「**最新的**」（当前版本 APK）。
已发布到 Release 的 APK 不在本地囤积。

动作顺序：**发新版 → 上传 → 回读校验 → 再删上一版本地包**。
