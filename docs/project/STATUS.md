# 当前状态

- 日期：2026-10-16
- 阶段：本 fork 的 **2.x 版本线**。**v2.2.40 已实现待真机验收**（versionCode 10065）——该版是**纯重构，零界面与行为改动**：2026-10-16 完成一次按职责的文件拆分，`TECH-DEBT.md` 行数台账上的三条债全部清账（`ChatScreen.kt` 3882→329 + `ui/chat/` 12 个文件、`HarnessViewModel.kt` 2277→663 + 8 个域文件、`ChatPresentation.kt` 825→98 + 5 个文件），`app/src` 下**零个文件超 800 行**；290 用例 0 失败、build / docsCheck 全绿。**该版只为真机验收而出，不发布**（发布需先经真机过一遍主流程）。其上 v2.2.39 已实现待发版（v2.2.36 平板三处 → v2.2.37 导航默认收起与软/硬键盘发送键 → v2.2.38 修两个实测回归 → v2.2.39 修 Ctrl+Enter 发送）。**2026-10-16 完成一次纯重构**：`TECH-DEBT.md` 行数台账上的三条债全部清账（`ChatScreen.kt` 3882→329 + `ui/chat/` 12 文件、`HarnessViewModel.kt` 2277→663 + 8 个域文件、`ChatPresentation.kt` 825→98 + 5 个文件），`app/src` 下**零个文件超 800 行**；290 用例 0 失败、build / docsCheck 全绿，**版本号与界面均未变动**（不是发版内容，是发版前的地基加固）。`v2.2.35`（`versionCode` 10060）**已发版**（GitHub + Gitee 双站 Release，tag v2.2.35）；上一版 v2.2.34 同日发布。**v2.2.39 已实现待发版**（v2.2.36 平板三处 → v2.2.37 导航默认收起与软/硬键盘发送键 → v2.2.38 修两个实测回归 → v2.2.39 修 Ctrl+Enter 发送；**界面回到 v2.2.38 状态**，会话列表常驻不收起；未构建发布产物、未打 tag、未推双站）
- 版本线：v1.1.0 多地址连接 + 配置导入 → v1.2.0 插件清单页 → v1.4.0 会话文件与图片 → v1.9.0 长按选中/双击复制 + 文件按类型预览 → v2.0.0 起会话详情页增加 WebView 实现（2.2.15 起把网页版呈现方式反向移植回原生）→ v2.2.23 问答卡片、图片 PDF 预览、工具调用详情 → v2.2.24–2.2.26 模型失败可见、图片交接可用、出错文案跟随界面语言 → v2.2.27 真机七问题收口 → v2.2.28 补上「长按可选中复制片段」→ v2.2.29 耗电主因（流式期间每秒全量拉 history → 400ms 静默去抖）+ 跳转落到会话尾部 + isAtBottom 改 derivedStateOf + 浮动按钮半透明 → v2.2.30 熄屏断流、亮屏接回（省电第二大头）→ v2.2.31 输入框 `@` 调取当前工作目录文件/文件夹（对齐官方）→ v2.2.35 长文件名改为横向滚动（文件页 / 预览标题 / 会话文件弹层 / `@` 面板四处统一）→ v2.2.36 平板界面三处优化（会话列表栏宽随屏宽伸缩 + 会话行/顶栏标题 wide 下两行 + 未选会话时右侧改概览面板，全部只在 wide 分支、手机端零改动）→ **v2.2.37 平板导航默认收起（overlay 抽屉 + 48dp 汉堡 + 用完即收，内容区从「屏宽−264dp」变整屏）+ 软/硬键盘发送键（物理键盘时输入框不弹软键盘 + 空闲占位符追加「Ctrl+Enter 发送」；Enter 一律换行的底线不动）→ v2.2.38 修 v2.2.37 两个实测回归（汉堡按钮从浮在内容之上改为左缘独立 48dp 竖条，不再压住会话列表标题导致点不动；软键盘 hide 从 Composer 组合时改到输入框 onFocusChanged 获焦时，否则 IME 接管后物理键盘的 Ctrl+Enter 全被吞掉）→ **v2.2.39 修 Ctrl+Enter 发送（`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据：触摸屏 Enter 一律换行，只有物理键盘的 Ctrl+Enter 才发送）；界面回到 v2.2.38 —— v2.2.39 曾做的「会话列表收起」经实测后已整套回退（含组件、字符串、代码），一级导航仍默认收起**
- 包名：`io.github.hakunm.deepseekharness`；Android 基线：min SDK 26、target/compile SDK 36、JDK 17
- 插件基线：`dsh-remote-bridge` **≥ 2.3.1**（旧名 `dsh-workspace`；Web 挂载前缀仍是历史名 `/dsh-workspace-api`，未随改名变动）。「加载更早消息」**要求 ≥ 2.3.1** —— 2.3.0 及更早的 `history()` 会忽略 `beforeSeq`，点按钮不会有任何反应
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
  - `DeepSeek-Harness-companion-v2.2.40.apk`，**2,895,422 字节**（**当前版本 · 重构验证包 · 待真机验收**，
    release 构建，R8 + 资源裁剪），SHA-256 `a277a38e9e6f90f69a32e9e1a86607fef4bead93ec5648ff4276aca6084c0e79`
    —— **与 v2.2.39 字节数完全相同**（同为 2,895,422），这正是「重构未改一行代码」最直接的证据
  - `DeepSeek-Harness-companion-v2.2.39.apk`，2,895,422 字节（上一版；已实现待发版，**未构建发布产物**）
  - `DeepSeek-Harness-v1.0.0.apk` 与 `DeepSeek-Harness-compat02-release.apk`（上游基线，各 2,561,512 字节）。`.gitignore` 特意用 `!` 保留这两个，用途是比对「本 fork 证书 ≠ 上游证书」，**不要删**
  - `SHA256SUMS.txt`：**全量历史**校验和（含 v1.9.0 起所有已发布版本）。它是**校验记录**而不是本地文件清单，本地删包**不动它**。
  - 其余版本的 APK（v1.1.0 ~ v2.2.33）**本地不留** —— 两站 Release 都有资产，需要时直接下载；`artifacts/*` 除 `SHA256SUMS.txt` 与上述两个基线包外全部被 `.gitignore` 忽略，**丢了无法从 git 回滚**，所以更不该在本地堆。
  - 发新版本时的动作：构建 → 拷入 `artifacts/` → 上传两站 Release → **回读校验哈希** → 再删掉上一版本地包（等价于「本地只留最新」）。
- **构建类型**：正式包一律 `scripts/build.sh assembleRelease`。debug 包不混淆、不裁剪资源，体积约 **21.8 MB**，与 release 的 2.87 MB 差 7.5 倍 —— 只看体积就能判断打错没打错
- 签名：**`dsh-release.jks`**（`CN=DSH Pocket Client`，证书 SHA-256 `8b2ba797589da844b779941fec213da3432633062744f64bc916e62df9dbf68a`；alias `dshrelease`，口令见 `.secrets/deepseek-harness-android/signing.properties`，该文件与 `*.jks` 都在 `.gitignore` 内）
  - 2026-10-11 说明：**原始那把发布钥匙的密码已彻底丢失**（本机/仓库/CI/git 历史全查过），现用的是**重新生成**的钥匙，主题刻意与旧钥匙一致（同为 `CN=DSH Pocket Client`）。
  - 后果：**它不是旧钥匙** —— v1.9.0 及更早（旧 `CN=DSH Pocket Client`，证书 `4e7fa1db…`）与上游 `CN=Hakunm` 都盖不上去，跨签名升级**必须先卸载**（会清除已保存的连接）。**v2.2.24 之后的包彼此可以直接覆盖**（v2.2.26 → v2.2.27 实测同一把钥匙）
  - 旧钥匙已归档在同目录 `archived-unknown-password/` 下，保留不删
- 仓库：`https://github.com/westanke/dsh-companion`、`https://gitee.com/westanke/dsh-companion`；上游 `Hakunm/dsh-android-app` 只作参考，**不推送**
- 已打 tag：`v1.1.0` / `v1.2.0` / `v1.4.0` / `v1.9.0` / `v2.2.20` / `v2.2.23` / **`v2.2.26`** / **`v2.2.27`**（`git tag` 实测）。v2.0.0–v2.2.19 之间没有 tag
- 真机验收：**问答卡片已端到端验证通过**（内核提问 → 插件推帧 → App 渲染 → 作答 → 答案回到内核，审计表有完整证据链）。v2.2.27 的七项修复**待本轮真机验收**。**尚未逐项验收**：图片/PDF 预览的翻页与缩放手感、工具调用详情的展开观感、长按选中/双击复制的手势边界、`dsh-ui` 卡片观感、HTML 结构预览排版、输入区 `⋯` 菜单在窄屏与各输入法下的可达性
- 已知缺口：单测夹具 `ConnectionShareInteropTest.kt` 仍内嵌一枚**已吊销**的真实设备令牌；该文件在 `app/src` 内，是否改写夹具待定（文档侧已全部脱敏）
- 其他文档：`docs/project/CHANGELOG-DEV.md`（逐版开发日志）、`docs/project/PARITY.md`（对等矩阵）、`docs/project/webview-report.md`（WebView 线复盘）、`docs/project/webview-parity-audit.md`（原生 vs WebView 能力边界）、`docs/project/webview-port-todo.md`（移植清单）、`docs/task-14-session-audit.md`（能力缺口审计）、`README.en.md`（英文 README，已同步 2.2.27）
- 插件仓库侧的排查复盘值得一读：`dsh-remote-bridge/docs/project/RETRO-question-card.md` —— 问答卡片「一直不显示」的排查里，**卡片代码从头到尾都是对的**，错的是排查方法（先解释现象、却没先确认现象真的发生过）
- 阻塞项：`PARITY.md` 中仍有后续 DSH WebUI 能力未对等；`IME-001` 继续扩大不同厂商键盘实机覆盖
- 下一步：真机验收 v2.2.27 的七项修复与上一条列的未验收项；按需补齐 DSH WebUI 对等能力（设置五大组、终端、插件启停等需要先改插件开 REST 通道）
