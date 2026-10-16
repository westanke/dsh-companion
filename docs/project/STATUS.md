# 当前状态

- 日期：2026-10-16
- 阶段：本 fork 的 **2.x 版本线**。**v2.2.41（versionCode 10066）应用改名 + 包名迁移，Gitee 与 AtomGit 双主备就位**（2026-10-16，tag v2.2.41，Gitee Release 已回验）。**GitHub 旧仓已删除并重建为镜像从站**，由 Actions 每 3 小时从 Gitee 自动同步（详见下文「渠道状态」）。
  应用名 `DeepSeek Harness` → **`DSH伴侣`**（英文 `DSH Companion`），包名 `io.github.hakunm.deepseekharness`
  → **`io.github.westanke.dshcompanion`**。动因是华为应用市场驳回「应用与『DeepSeek』名称相似、未提供商标权属证明」
  —— `NOTICE` 那份 MIT 只覆盖 logo **著作权**，不是**商标权**。
  **代价**：`applicationId` 变了就是另一个 App，老用户**无法覆盖升级、必须先卸载**（卸载清空已保存的连接，需重新配对）。
  门验：290 用例 0 失败（与 v2.2.40 逐项一致）、compileDebugKotlin 真执行、docsCheck 通过；
  **UI 测试与真机验收未做**（包名变更要求先卸载旧包）。
  其下 **v2.2.40（versionCode 10065）真机验收通过，待发版**（2026-10-16，① 连接与启动 ② 会话列表 ③ 消息流与工具详情 ④ 输入区 ⑥ 文件/插件/设置/会话文件面板 ⑦ 切会话与熄屏亮屏 **全绿**；⑤ 审批与问答卡片**当时**记为「不弹」，**该结论已于同日推翻——病根全在插件侧，已随 bridge v2.3.7 修复并真机复验通过**）。
  该版是**纯重构，零界面与行为改动**：按职责拆文件，`TECH-DEBT.md` 行数台账上的三条债全部清账（`ChatScreen.kt` 3882→329 + `ui/chat/` 12 个文件、`HarnessViewModel.kt` 2277→663 + 8 个域文件、`ChatPresentation.kt` 825→98 + 5 个文件），`app/src` 下**零个文件超 800 行**；290 用例 0 失败、build / docsCheck 全绿。其后三笔均为**纯文档/清理**，不进版本号：清 `ChatScreen.kt` 三处死 import、补 `PITFALLS.md` 的 `resolveAgent` 原因、撤掉「卡片不弹」缺口并把 README 双站的 bridge 门槛提到 **≥ 2.3.7**。
  版本线（v2.2.35 之后）：v2.2.36 平板三处 → v2.2.37 导航默认收起与软/硬键盘发送键 → v2.2.38 修两个实测回归 → **v2.2.39 修 Ctrl+Enter 发送（`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据）；界面回到 v2.2.38 状态**（v2.2.39 曾做的「会话列表收起」经实测后已整套回退，一级导航仍默认收起）→ **v2.2.40 纯重构**。`v2.2.35`（versionCode 10060）**已发版**（GitHub + Gitee 双站 Release，tag v2.2.35），v2.2.34 同日发布；**v2.2.36–v2.2.40 一并随 v2.2.40 发布**（中间五版未单独发版）
- 版本线：v1.1.0 多地址连接 + 配置导入 → v1.2.0 插件清单页 → v1.4.0 会话文件与图片 → v1.9.0 长按选中/双击复制 + 文件按类型预览 → v2.0.0 起会话详情页增加 WebView 实现（2.2.15 起把网页版呈现方式反向移植回原生）→ v2.2.23 问答卡片、图片 PDF 预览、工具调用详情 → v2.2.24–2.2.26 模型失败可见、图片交接可用、出错文案跟随界面语言 → v2.2.27 真机七问题收口 → v2.2.28 补上「长按可选中复制片段」→ v2.2.29 耗电主因（流式期间每秒全量拉 history → 400ms 静默去抖）+ 跳转落到会话尾部 + isAtBottom 改 derivedStateOf + 浮动按钮半透明 → v2.2.30 熄屏断流、亮屏接回（省电第二大头）→ v2.2.31 输入框 `@` 调取当前工作目录文件/文件夹（对齐官方）→ v2.2.35 长文件名改为横向滚动（文件页 / 预览标题 / 会话文件弹层 / `@` 面板四处统一）→ v2.2.36 平板界面三处优化（会话列表栏宽随屏宽伸缩 + 会话行/顶栏标题 wide 下两行 + 未选会话时右侧改概览面板，全部只在 wide 分支、手机端零改动）→ **v2.2.37 平板导航默认收起（overlay 抽屉 + 48dp 汉堡 + 用完即收，内容区从「屏宽−264dp」变整屏）+ 软/硬键盘发送键（物理键盘时输入框不弹软键盘 + 空闲占位符追加「Ctrl+Enter 发送」；Enter 一律换行的底线不动）→ v2.2.38 修 v2.2.37 两个实测回归（汉堡按钮从浮在内容之上改为左缘独立 48dp 竖条，不再压住会话列表标题导致点不动；软键盘 hide 从 Composer 组合时改到输入框 onFocusChanged 获焦时，否则 IME 接管后物理键盘的 Ctrl+Enter 全被吞掉）→ **v2.2.39 修 Ctrl+Enter 发送（`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据：触摸屏 Enter 一律换行，只有物理键盘的 Ctrl+Enter 才发送）；界面回到 v2.2.38 —— v2.2.39 曾做的「会话列表收起」经实测后已整套回退（含组件、字符串、代码），一级导航仍默认收起**
- 包名：**`io.github.westanke.dshcompanion`**（v2.2.41 起；v2.2.40 及更早为 `io.github.hakunm.deepseekharness`，只留在 git 历史里）；Android 基线：min SDK 26、target/compile SDK 36、JDK 17
- 插件基线：`dsh-remote-bridge` **≥ 2.3.7**（旧名 `dsh-workspace`；Web 挂载前缀仍是历史名 `/dsh-workspace-api`，未随改名变动）。分项：「加载更早消息」**要求 ≥ 2.3.1**（2.3.0 及更早的 `history()` 会忽略 `beforeSeq`，点按钮不会有任何反应）；「插件」页启停 **≥ 2.3.3**；**审批 / 问答卡片 ≥ 2.3.7**（更低版本手机端一张都不显示——该症状曾连续坏在两个插件侧病根上，App 侧代码自始至终是对的，详见 `PITFALLS.md` §bridge 版本基线）
- 最近验证：**290 用例 / 0 失败**（口径：跑 `scripts/build.sh :app:testDebugUnitTest`，再数 `app/build/test-results/` 下的 XML）。这个数字随代码变化，不要当常量引用
- **「插件」页现状（v2.2.32）**：只读清单 + 详情（简介 / 主页 / 更新提示）+ **手机上直接启停**。
  - 配套插件端 **≥ 2.3.3**：`v2.3.2` 修了 profile 判定（`dsh web` 简写此前被判不出，整页读不出清单）；`v2.3.3` 加了详情字段与 `PATCH /settings/plugins` 启停端点。
  - 启停只改 profile 的 `dsh.profile.bundles`（加载清单），**不碰依赖、不装包**，写前自动备份 `package.json.bak-<时间戳>`；**必须重启 DSH 才生效**（bundles 只在启动时读）。
  - 实测（用户已重启，插件端报 2.3.3）：profile=web、已加载 17、问题项 3、共 20 条；可启停 15 条（12 启用 / 3 停用：`@liustack/modlens`、`dsh-hyperframes`、`dsh-remotion`），不可启停 5 条全是 `@deepseek-ai/` 官方包。
- **电源管理现状**（两个大头都已处理）：
  - v2.2.29 修掉头号：`scheduleChatRefresh` 的 1 秒轮询 —— 每个事件帧都把标志位置 true（事件分发末尾还有 `else ->` 兜底分支什么都命中），流式输出期间每秒一次全量 `history()`；改成 400ms 静默去抖，实时流式显示走 `chat.message.delta` → `liveChat` 独立那一路，不受影响。
  - v2.2.30 修掉第二大头：`eventSubscription` 原先只在 `onCleared()` 与主动断开时关闭，熄屏后 WebSocket 一直连着（WiFi 射频 + TCP keepalive 全程在线，系统还会反复唤醒进程）。现在 **熄屏断流、亮屏接回**：`MainActivity` 注册 `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON`（**不用 `onStop`** —— 切 App/分屏时屏幕还亮着不该断），ViewModel 加 `pauseEvents()`/`resumeEvents()`，熄屏只关流、**保留 active 连接与令牌**。亮屏补三样：会话列表、当前会话历史、**审批与待答问题**（后两类不走 history，必须单独补，否则熄屏期间产生的卡片永远不出现）。
  - 策略由用户拍板（原话「能让 熄屏 就不在线了，如果不是熄屏状态 实时优先」）。**代价**：熄屏期间收不到推送通知，亮屏一次性补上、不丢内容。
- 当前产物：**只保留「必须的」与「最新的」**（用户要求：已发布到 Release 的 APK 不在本地囤积）：
  - `DSH伴侣-companion-v2.2.41.apk`，**2,895,430 字节**（**当前版本 · 改名 + 包名迁移 + AI 标识 · 已发 Gitee**，
    release 构建，R8 + 资源裁剪），SHA-256 `5317b009d1e806fc695a695b77d255b8c32cfae06c7a593736cf822e2e23f957`
    —— 已上传 Gitee Release（tag v2.2.41）并**下载回验**：字节数与 SHA-256 与本地三处一致
  - `DeepSeek-Harness-v1.0.0.apk` 与 `DeepSeek-Harness-compat02-release.apk`（上游基线，各 2,561,512 字节）。`.gitignore` 特意用 `!` 保留这两个，用途是比对「本 fork 证书 ≠ 上游证书」，**不要删**
  - `SHA256SUMS.txt`：**全量历史**校验和（含 v1.9.0 起所有已发布版本）。它是**校验记录**而不是本地文件清单，本地删包**不动它**。
  - 其余版本的 APK（v1.1.0 ~ v2.2.33）**本地不留** —— 两站 Release 都有资产，需要时直接下载；`artifacts/*` 除 `SHA256SUMS.txt` 与上述两个基线包外全部被 `.gitignore` 忽略，**丢了无法从 git 回滚**，所以更不该在本地堆。
  - 发新版本时的动作：构建 → 拷入 `artifacts/` → 上传两站 Release → **回读校验哈希** → 再删掉上一版本地包（等价于「本地只留最新」）。
- **构建类型**：正式包一律 `scripts/build.sh assembleRelease`。debug 包不混淆、不裁剪资源，体积约 **21.8 MB**，与 release 的 2.87 MB 差 7.5 倍 —— 只看体积就能判断打错没打错
- 签名：**`dsh-release.jks`**（`CN=DSH Pocket Client`，证书 SHA-256 `8b2ba797589da844b779941fec213da3432633062744f64bc916e62df9dbf68a`；alias `dshrelease`，口令见 `.secrets/deepseek-harness-android/signing.properties`，该文件与 `*.jks` 都在 `.gitignore` 内）
  - 2026-10-11 说明：**原始那把发布钥匙的密码已彻底丢失**（本机/仓库/CI/git 历史全查过），现用的是**重新生成**的钥匙，主题刻意与旧钥匙一致（同为 `CN=DSH Pocket Client`）。
  - 后果：**它不是旧钥匙** —— v1.9.0 及更早（旧 `CN=DSH Pocket Client`，证书 `4e7fa1db…`）与上游 `CN=Hakunm` 都盖不上去，跨签名升级**必须先卸载**（会清除已保存的连接）。**v2.2.24 之后的包彼此可以直接覆盖**（v2.2.26 → v2.2.27 实测同一把钥匙）
  - 旧钥匙已归档在同目录 `archived-unknown-password/` 下，保留不删
- 仓库：Gitee 主站 `https://gitee.com/westanke/dsh-companion`、AtomGit 备站 `https://atomgit.com/westanke163/dsh-companion`、GitHub 镜像从站 `https://github.com/westanke/dsh-companion`（自动同步，勿手工推）；上游 `Hakunm/dsh-android-app` 只作参考，**不推送**
- 已打 tag（`git tag` 实测，2026-10-16）：`v1.1.0` / `v1.2.0` / `v1.4.0` / `v1.9.0` / `v2.2.20` / `v2.2.23` / `v2.2.26` / `v2.2.27` / `v2.2.28` / `v2.2.29` / `v2.2.30` / `v2.2.31` / `v2.2.32` / `v2.2.33` / `v2.2.34` / `v2.2.35` / `v2.2.39` / `v2.2.40` / **`v2.2.41`**（共 19 个）。v2.0.0–v2.2.19 之间没有 tag；**v2.2.36–v2.2.38 没有独立 tag**（随 v2.2.39 一并发布）
- 真机验收：**审批与问答卡片已确认修好（2026-10-16）**，但**修的是插件侧，本 App 一行未改**。plan 模式评审实测：**手机端弹出卡片，电脑端 WebUI 同时正常弹出**（红线「不得抢答」未踩）。病根是 `dsh-remote-bridge` 的两个钩子注册时没带 Cordis 的 `{ prepend: true }`，被 `push` 到 waterfall 链尾、排在官方 WebUI 之后，于是永不被调用；插件 **v2.3.7** 已修并真机验证。**此前「10-11 能弹、10-16 又不弹」两次结论相反的悬案就此解开**：不是非稳定复现，而是 `sessionIdOfAgent`（v2.3.6 修）与 `prepend`（v2.3.7 修）**两个病根叠加**——前者修好后后者仍在，症状看起来「回来了」。**App 侧代码（订阅、拉取、渲染三环）自始至终是对的。**v2.2.27 的七项修复**待本轮真机验收**。**尚未逐项验收**：图片/PDF 预览的翻页与缩放手感、工具调用详情的展开观感、长按选中/双击复制的手势边界、`dsh-ui` 卡片观感、HTML 结构预览排版、输入区 `⋯` 菜单在窄屏与各输入法下的可达性
- 已知缺口：
  - 单测夹具 `ConnectionShareInteropTest.kt` 仍内嵌一枚**已吊销**的真实设备令牌；该文件在 `app/src` 内，是否改写夹具待定（文档侧已全部脱敏）
- 其他文档：`docs/project/CHANGELOG-DEV.md`（逐版开发日志）、`docs/project/PARITY.md`（对等矩阵）、`docs/project/webview-report.md`（WebView 线复盘）、`docs/project/webview-parity-audit.md`（原生 vs WebView 能力边界）、`docs/project/webview-port-todo.md`（移植清单）、`docs/task-14-session-audit.md`（能力缺口审计）、`docs/project/approvals-questions-trace.md`（审批/问答两条链路端到端追踪，9 节带行号）、`README.en.md`（英文README，已同步 2.2.27）
- 插件侧的定位过程值得一读：`dsh-remote-bridge/docs/project/PITFALLS.md` §waterfall 不 prepend 就等于没注册（含 Cordis 源码行号）——最反直觉的一点是，**提问钩子的源码注释里早就写对了机理，只是注册时漏了这个选项**，且失败完全静默、两侧日志都不说一句话。另一份 `RETRO-question-card.md` 复盘的结论是：**卡片代码从头到尾都是对的**，错的是排查方法（先解释现象、却没先确认现象真的发生过）
- 阻塞项：`PARITY.md` 中仍有后续 DSH WebUI 能力未对等；`IME-001` 继续扩大不同厂商键盘实机覆盖
- 下一步：真机验收 v2.2.27 的七项修复与上一条列的未验收项；按需补齐 DSH WebUI 对等能力（设置五大组、终端、插件启停等需要先改插件开 REST 通道）
- **渠道状态（2026-10-16，三端拓扑：Gitee 主 / AtomGit 备 / GitHub 镜像从站）**：
  - **Gitee = 主站**：代码、tag v2.2.41、Release（含 APK / SHA256SUMS.txt / RELEASE_NOTES 三个资产）全部就位，
    资产已**下载回验**，字节数 2,895,430 与 SHA-256 `5317b009…f957` 与本地三处一致。
  - **AtomGit = 备站**（`westanke163/dsh-companion`，注意账号名与 Gitee 不同）：2026-10-16 建仓并全量推送
    main + 19 个 tag，与 Gitee 逐个 ref 比对 SHA 一致（v1.1.0/v1.2.0 两个最老轻量 tag 两端服务端展开方式
    略有差异，指向的 commit 相同，无实际影响）。AtomGit 只镜像代码，不承担 Release。
  - **GitHub = 镜像从站**：旧仓存在 v2.2.35 之后**历史重写分叉**（两边 11 个提交树对象两两相同、hash 不同；
    试过 `git merge origin/main` 爆 47 个冲突，`git merge --abort` 已回退无残留），手工合并/推送此路不通，
    加上本机到 `github.com:443` 长期被阻断。用户拍板：**删除旧仓重建为镜像从站**，此后不再手工推 GitHub。
    重建实路（GitHub Import API 已 410/404 废弃、网页 Importer 在本机网络卡 availability 检查）：
    ① 建空仓；② 经 Contents API（api.github.com 可达）提交引导版 `mirror-sync.yml`；
    ③ workflow 在 GitHub runner 上从 Gitee `clone --mirror` 再 `push --mirror` 覆盖空仓——
    普通 git 传输**保留真实 commit hash**，重建后 GitHub main 与 Gitee 每次同步后 SHA 逐字一致（19 tag 齐全）。
    重建当次另修两处既有 CI 问题：`setup-android@v3` 默认装已下架的 `tools` 包（改显式声明
    `platform-tools platforms;android-36 build-tools;36.0.0`）、`lintDebug` 16 个既有错误被当硬门禁
    （按 AGENTS.md「无 baseline 勿当门禁」改为仅出报告，硬门为 docsCheck + 单测 + assembleDebug）。
  - **自动同步机制**：`.github/workflows/mirror-sync.yml` 每 3 小时（UTC `17 */3 * * *`）+ 手动触发，
    两个 job：① 内嵌自举段（等价 `scripts/ci/mirror-push.sh`）在 runner 上从 Gitee（不可达则回退 AtomGit）
    `git clone --mirror` 后 `git push --mirror` 到本仓——refs 全量对齐，主源删分支/tag 从站跟随删除。
    推送凭证用仓库 secret **`MIRROR_PAT`**（带 `repo` + `workflow` scope 的 OAuth/PAT）——GITHUB_TOKEN
    按安全设计不能增改 workflow 文件，而 mirror 必然写入历史里的 `.github/workflows/*.yml`（实测全 ref 被拒）。
    ② `scripts/ci/sync-releases.py` 幂等把 Gitee Release 正文与三类资产
    （APK / SHA256SUMS.txt / RELEASE_NOTES，源码包不搬，GitHub 自动生成）补到 GitHub，已存在即跳过。
    **GitHub 资产名强制 ASCII**：GitHub uploads 不支持非 ASCII 资产名（实测中文百分号编码被丢弃、raw 报 400），
    故 GitHub 侧 APK 名为 `DSH-companion-vX.Y.Z.apk`（Gitee 侧保留 `DSH伴侣-…` 中文名，Release 标题/正文不受影响）。
  - **本地推送**：`bash scripts/push-all.sh` 只推 Gitee + AtomGit（`PUSH_TAGS=1` 推标签，
    `INCLUDE_GITHUB=1` 才会顺带试 GitHub）；远端命名用 `bash scripts/setup-remotes.sh` 幂等整理。
  - 历史教训留档：历史重写分叉的来龙去脉与「merge 提交触发镜像脚本 422」见 `PITFALLS.md` 对应条目；
    旧的 31 提交欠账随删仓一笔勾销，不再需要线性化历史，也不再在本地对 GitHub 使 `--force`。
