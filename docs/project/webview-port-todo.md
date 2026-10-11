# WebView → 原生 移植清单（截至 v2.2.20）

- 日期：2026-10-10（初版）/ 2026-10-11（补「输入区方案 A + ⋯ 菜单」一轮，再补 2.2.18 → 2.2.20 五项）
- 版本：**v2.2.20（versionCode 10045）**
- 方向：`app/src/main/assets/chat.html`（WebView 聊天页）→ 原生 Compose 聊天页（`ui/ChatScreen.kt`）
- 本任务边界：**只记文档，不改代码**；所有结论来自读代码取证，每条附文件与函数名。
- 取证快照（v2.2.20 收口时重测，工作树仍未提交）：
  - `ui/ChatScreen.kt` **2907 行**，md5 `4111fbf44fee4dfdfe5070da24bff9c9`
  - `data/ChatPresentation.kt` **446 行**，md5 `182044b815aedbf1b7928719f2c96a2f`
  - `assets/chat.html` **2251 行**，md5 `6423e83767d402001dfda3e9cec70c91`（这两轮**页面侧未再改**）
  - 旧快照（**行号已全部过期，仅供追溯**）：`ChatScreen.kt` 2859 行 / md5 `bfbaaa68f27b7d1e56579847bdb7941b`；
    更早 2754 行 / md5 `b060fdb5407348d427c5e16e1be86f8e`。
- **提醒：行号会漂移，一律以函数名 / 组件名为准。** 本文写作期间 `ChatScreen.kt` 被多名队友先后改过
  （思考条 → 输入区药丸 + `⋯` 菜单 → 输入框自适应 → 启动门闩 → 文件清单统一）；引用代码请按函数名检索。
- 不重复另两份文档：`docs/project/webview-parity-audit.md`（原生 vs 网页版功能对照）、
  `docs/project/webview-report.md`（WebView 这条线的历史复盘；原名 `webview-2.2.9-report.md`，
  名字只反映起点、不反映覆盖范围，2026-10-11 收口时改名）。本文只回答「移植了什么、还差什么」。

---

## 一、先对齐方向（避免看错结论）

本轮目标是让**原生聊天页**具备 WebView 页的界面布局与功能。于是本文分四类：

| 类别 | 含义 | 本文位置 |
|---|---|---|
| 已移植 | 网页版有，这一轮补进原生 | 第二节 |
| 未实现 | 网页版有，原生还没有 | 第三节 |
| 原生已有、网页版没有 | 不是待办，但移植时必须**不丢**，逐项记现状（权限模式、文件详情、附件失败文案、HTML 结构预览） | 第三节（标注「现状」） |
| 有意不移植 | 属于列表页/设置页或用户明确排除 | 第三.7 节、第四节 |

---

## 二、本轮已移植的项

| # | 项目 | 落点（文件 · 函数） | 关键取证 |
|---|---|---|---|
| 1 | 工具显示改官方口径**中文动作短语** | `data/ChatPresentation.kt` · `toolActionsFor()`（词表 `INNER_ACTIONS`） | `toolItem()` 调用它，标题 = `actions.joinToString("，")`；旧的英文类别词 `toolTitle()` 已在本轮删除（`git diff HEAD` 可见）。`run_code` 扫参数拼内层动作来自真实统计（1129 次调用） |
| 2 | 连续工具调用**合并成一行** | `data/ChatPresentation.kt` · `mergeToolRuns()` | 挂在 `ChatHistory.displayItems()` 入口；只合并**相邻** TOOL，中间夹正文即断开；口径对齐网页版 `toolGroupSummary`（chat.html：去重、>4 项加「等」） |
| 3 | 历史**不显示思考过程** | `ui/ChatScreen.kt` · `ChatDetail()` 内 `historyItems` | `.filter { it.kind != ChatItemKind.REASONING }`；只过滤历史，流式那段另走思考条 |
| 4 | 流式思考挪到**输入区上方一行**（本轮并行落地） | `ui/ChatScreen.kt` · `ChatThinkBar()` | 纯函数 `liveThinkingText()`（只取流式 REASONING）、`thinkBarText()`（空白折单空格、>140 字取尾）；`liveItems` 也过滤 REASONING。口径对齐 chat.html `#thinkbar` / `setThinking` |
| 5 | 上下文注入**默认隐藏**（与设置开关联动） | `ui/ChatScreen.kt` | `val showContext = viewModel.showContext()`，再 `.filter { showContext \|\| it.kind != ChatItemKind.CONTEXT }` |
| 6 | 设置页新增「显示上下文注入」开关 | `ui/SettingsScreen.kt` · `SwitchRow()`（Switch）；`data/AppPreferences.kt` · `showContext`（默认 false）；`HarnessViewModel` · `setShowContext()/showContext()` | 文案 `values/strings.xml` `show_context_title/desc`；WebView 侧同步推送在 `ui/ChatWebScreen.kt`（`window.dshChat.setPrefs({showContext})`） |
| 7 | 输入区压成**单行药丸**：只留 `+` · 输入框 · 发送 | `ui/ChatScreen.kt` · `Composer()` | 药丸 = `Surface(RoundedCornerShape(22.dp), outlineVariant 边框, surfaceContainerLow)`；内部 `Row` 三段：`IconButton(Icons.Outlined.Add, 34dp)` · `BasicTextField`（自己不再画边框/圆角）· 发送 `Surface(CircleShape, 34dp)`。发送不可用时改**透明底 + 灰字**（`Color.Transparent` + `onSurfaceVariant`），不是整体压透明度。口径对齐 chat.html `#row` 与 `#attachBtn` 的样式 |
| 8 | 顶栏新增 `⋯` 菜单，承载**模型 · 权限 · 插话** | `ui/ChatScreen.kt` · `ConversationHeader()` 内联 `Box{ IconButton(MoreVert, R.string.more_actions) + DropdownMenu }`；权限段抽成 `PermissionMenuItems()`，完整访问二次确认抽成 `FullAccessConfirmDialog()` | 位置对齐 chat.html `#more`（标题右边、停止键旁）。菜单三项：模型（`onModelClick` → 既有 `ModelSheet()`）· 权限（三档 + 完整访问二次确认）· 插话（固定 `steer`，`onSteer()` → `viewModel.sendMessage(message, true)`） |
| 9 | 附件入口由两个平铺 IconButton 改成 `+` 弹出的两项；运行中提示由独立一行改为**占位符** | `ui/ChatScreen.kt` · `Composer()` 内 `Box{ IconButton(Add) + DropdownMenu }`；`composerHint()` / `ComposerHint` | `+` 两项文案 `attachment_pick_image` / `attachment_pick_file`，分别触发既有 `imageLauncher`(GetContent `image/*`) 与 `fileLauncher`(OpenDocument `*/*`) —— launcher 仍建在 `Composer` 作用域内。占位符按 `composerHint(sessionRunning, defaultIsSteer)` 三态取 `message_hint` / `message_hint_running_queue` / `message_hint_running_steer`，`maxLines = 1` + 省略号。口径对齐 chat.html 改 placeholder 的做法 |
| 10 | 输入框高度**随内容自适应**（44~120dp） | `ui/ChatScreen.kt` · `Composer()`（`BasicTextField` 外层 `heightIn(min = 44.dp, max = 120.dp)`，行内对齐 `Alignment.Bottom`） | 此前写死 34dp，敲到第二行就被裁。口径对齐 chat.html `#ta` 的 `min-height:36px / max-height:120px`、`align-items:flex-end`。要点：`BasicTextField` 用 `fillMaxWidth` 而不是 `fillMaxSize`（后者会永远撑到 120dp 上限）。版本 2.2.18 / 2.2.19① |
| 11 | 设置页三个开关统一 `SwitchRow` | `ui/SettingsScreen.kt` · `SwitchRow()`（三处调用：`useWebChat` / `showContext` / `genUiDebug`） | 此前三处各写各的 `Row`（一个 `padding(bottom=4dp)`、一个 `padding(vertical=8dp)`），页面上就是「中间那个开关上下多出一截」。版本 2.2.19② |
| 12 | 开关**可观测性**：两个开关进 `HarnessState`，不再由 UI 直读 SharedPreferences | `HarnessViewModel.kt` · `HarnessState.useWebChat` / `showContext`、`withPreferences()`（初始 / `disconnect` / `loadSnapshot` 三处整份重建都要盖上）、setter `update { copy(...) }` | 修真机「点开关不变化，必须再点别的才跳」：直读 SharedPreferences 不是 Compose 状态 —— 读不建立订阅、写不触发重组。顺带修 `busySendMode` / `genUiDebug` 在被整份重建时打回默认。版本 2.2.19③ |
| 13 | 模型配置拆成**独立导航页** | `ui/ModelsScreen.kt` · `ModelsScreen()`；入口 `ui/App.kt` · `MainSection.Models`（Plugins 与 Settings 之间） | 设置页 697 → 297 行并删净 30 个死 import。模型页**永远可进**（与设置页同取舍：看不到入口就不知道是权限问题、也不知道去哪儿开）。版本 2.2.19④ |
| 14 | 交付物改读内核 **`deliverables/presented`** 事件 | `data/ChatPresentation.kt` · `ChatHistory.deliverables()`（`present` 工具触发，带绝对路径 + 人写的说明）；渲染 `ui/ChatScreen.kt` · `sessionFileList()` / `SessionFilesFooter()` | 此前 `displayItems()` 只认三种事件，这一类被**整类丢掉** —— 用户在 App 里看不到 agent 交付的报告与安装包。旧的「嗅工具参数」保留为兜底。版本 2.2.19⑤ |
| 15 | **启动永远先落连接页** + 连接页两种方式**默认收起** | 门闩：`HarnessViewModel.kt` · `connectGate` / `passConnectGate()`（**进程级**，刻意不进 `HarnessState`，否则会被整份重建冲掉）、`ui/App.kt` 按它分流；入口行：`ui/ConnectScreen.kt` · `ConnectionEntryRow()`（导入配置文本 / 手动配对，两行互斥展开） | 冷启动自动重连成功**仍然停在连接页**，只由用户的三个主动动作放行。另修 `connectHostInternal` 失败分支漏置 `restoring=false`（自动重连失败会让转圈永远停住）。版本 2.2.19⑥ |
| 16 | 同一个会话的**文件清单两处统一** | `ui/ChatScreen.kt` · `sessionFileList()` —— 底部 `SessionFilesFooter()` 与标题栏弹层 `SessionFileList()` 共用同一来源 | 此前底部读 `deliverables/presented`，弹层还在 `SessionFileRefs.refs()` 嗅工具参数：底部是 agent 交付的报告与安装包，弹层却是一堆嗅出来的路径（真机反馈「标题栏文件夹里的不合适」）。版本 2.2.20 |

> **同批发布、但不是移植项**：2.2.20 另修了「进入应用」按钮灰着点不动 ——
> 启用条件 `!busy && !connecting` 是给「连接这台电脑」准备的，却误伤了纯本地跳转的
> 「进入应用」（后台有请求在跑就变灰，用户既进不去也没第二入口）。现在按 `isActive` 分开判。
> 记在这里只为版本对齐。

### 2.1 入口对照表（证明「换位置，不丢功能」）

| 功能入口 | 旧位置（改版前） | 新位置（本轮） | 是否仍在 |
|---|---|---|---|
| 模型切换 | `Composer()` 行内模型 `TextButton` → `onModelClick` → `ModelSheet()` | `⋯` 菜单第一项（`R.string.model`）→ 同一个 `ModelSheet()` | ✅ 同一面板，未换实现 |
| 智能体切换 | 本来就在 `ModelSheet()` 里（`AgentPresetRow()`），入口是上面那个模型按钮 | 同左，不重复做 | ✅ |
| 权限模式（只读 / 工作区写入 / 完整访问） | `Composer()` 行内 `PermissionControl()` | `⋯` 菜单 `PermissionMenuItems()` + `FullAccessConfirmDialog()` | ✅ 三档齐全；完整访问仍需勾选 `acknowledged` 才能确认 |
| 插话（steer） | `Composer()` 行内 `SubdirectoryArrowRight` IconButton → `onSubmit(true)` | `⋯` 菜单第三项（`R.string.steer`）→ `onSteer()` → `viewModel.sendMessage(message, true)` | ✅ 仍是**固定 steer**，不受设置里「繁忙时默认行为」影响 |
| 图片附件 | 平铺 `Icons.Outlined.ImageIcon` IconButton → `imageLauncher.launch("image/*")` | `+` →「从相册选图片」→ 同一个 `imageLauncher` | ✅ launcher 未搬出 `Composer` |
| 文件附件 | 平铺 `Icons.Outlined.AttachFile` IconButton → `fileLauncher.launch("*/*")` | `+` →「选择文件」→ 同一个 `fileLauncher` | ✅ |
| 发送 | 行尾 44dp `Surface` | 药丸内 34dp `Surface`（`onSubmit(defaultIsSteer)`） | ✅ 留在药丸里 |
| 运行中提示（回车=排队/插话） | `Composer()` 里独立的 `Text`（`send_mode_queue` / `send_mode_steer`） | 并进占位符（`message_hint_running_queue` / `message_hint_running_steer`） | ⚠️ 信息在，**位置与措辞变了**：从独立「回车=…」变成占位符「运行中… 发送即排队/插话」；旧两键已无引用（保留未删，见 §5.8） |
| 物理键盘 Enter / 软键盘发送键 | `BasicTextField.onKeyEvent` + `KeyboardActions.onSend` | 原样保留在药丸的 `BasicTextField` 上 | ✅ |
| 无 `chat.write` 时不显示附件入口 | `if (canWrite)` 包住两个 IconButton | `if (canWrite)` 包住 `+` | ✅ |
| 停止运行 | 顶栏 `ConversationHeader()` 的 `cancelRun` IconButton | 未动 | ✅ |

> 第 4、7、8、9 项都不在最初的任务清单里，是在写本文档期间由并行改动落地的，故一并记为「已移植」。
> 第 10 ~ 16 项同理：它们是 2.2.18 → 2.2.20 这几轮里落地的，2026-10-11 收口时补记。
> 其中第 11 项（`SwitchRow` 统一）与第 12 项（开关可观测性）严格说是界面一致性 / 状态管理，
> 不是「照页面搬一个功能」，但动机同样来自「与页面的观感与行为对齐」，故并入本节。

---

## 三、未实现项 / 现状清单

### 3.1 原生 Markdown 表格渲染

- **是什么**：网页版由浏览器渲染 Markdown 表格（chat.html `md()` 的表格分支）；原生侧要能一样画出来。
- **结论：原生本来就能渲染表格，这不是待办项。** 取证：
  - 依赖 `com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0`（`app/build.gradle.kts`；版本 `gradle/libs.versions.toml`）。
  - 解开 Gradle 缓存里的 aar（`~/.gradle/caches/modules-2/files-2.1/com.mikepenz/multiplatform-markdown-renderer-android/0.41.0/.../multiplatform-markdown-renderer.aar` → `classes.jar`），确认存在 `com/mikepenz/markdown/compose/elements/MarkdownTableKt.class`（61746 字节）、`MarkdownComponents.class`（含 `getTable`）、`DefaultMarkdownComponents.class`（含 `table` 字段）。
  - 本仓库所有 `Markdown(` 调用**都不传 components**（`ui/ChatScreen.kt` 5 处 + `ui/FilesScreen.kt` 1 处），走默认实现；`compactMarkdownTypography()`（`ui/ChatScreen.kt`）还专门配了 `table = MaterialTheme.typography.bodySmall`。
- **为什么这条还留着**：要区分「渲染不出来」与「渲染出来但窄屏不好看」。表格转竖线是**网页版**的旧问题（页面 `md()` 原本没实现表格，见 `webview-report.md` 的 2.2.11 / 2.2.12），与原生无关。
- **真正的差别**：窄屏宽表的**横向滚动**——网页版有 `.tw` 包裹层（`chat.html` 的 `md()` 与 `renderCard()` 里各一处），原生由库内部决定。
- **若要做**：改 `compactMarkdownTypography()`（ChatScreen.kt）或给 `Markdown(` 传自定义 `components`；横向滚动需库支持或自绘表格组件。
- **状态**：不是未实现项；**真机窄屏观感未核实**（本任务不跑真机）。
- **本轮复核（输入区药丸化之后）**：`compactMarkdownTypography()`（`ui/ChatScreen.kt`）仍配 `table = MaterialTheme.typography.bodySmall`；`Markdown(` 的 5 处调用点**都没传 `components`**（走默认实现，含 `MarkdownTableKt`）；Gradle 缓存里的 `multiplatform-markdown-renderer.aar` 仍在。**没有读到反证，§3.1 结论维持**。行号已漂移，请按函数名找。
- **v2.2.20 复核**：两条仍成立 —— `compactMarkdownTypography()` 继续配 `table = MaterialTheme.typography.bodySmall`；
  `Markdown(` 调用点 `ui/ChatScreen.kt` 5 处 + `ui/FilesScreen.kt` 1 处，**均未传 `components`**；aar 仍在。
  **别把两条线混起来**：dsh-ui **卡片**里的表格 2.2.16 已对齐横向滚动（`ui/GenUiCard.kt` ·
  `GenUiTable()` / `genUiTableColumnWidthDp()`），那是卡片表格；本节说的是 **Markdown 渲染表格** ——
  它在真机窄屏到底滚不滚、列宽压不压，**未核实**。

### 3.2 原生思考条位置（消息区下方、输入区上方）—— 现已落地

- **是什么**：网页版 `#thinkbar`（chat.html）固定在输入区上方一行，只显示当前思考、过长取尾部、没有就整条隐藏（`setThinking`、`clearThinking`、节流写入）。原生原先把思考当成消息流里一个可展开行（`ChatItemKind.REASONING` → `R.string.think`，`ActivityRow()`）。
- **现状**：**已实现并随 2.2.16 出包**。`ChatThinkBar()`（`ui/ChatScreen.kt`），挂载在消息区 `LazyColumn` 之后、
  `when (approvalState)` 之前 —— 按函数名检索，行号已漂。
- **难点（已解决，值得记下来）**：
  1. 位置必须在消息区与输入区之间，**不能进 `LazyColumn`** —— 否则随消息滚动、把正文挤开（注释）。
  2. 没有思考时必须**整条不渲染**；用 `AnimatedVisibility` 收起会留下一条带边框的空缝，永久占着输入区上方一行（注释）。
  3. 历史里的思考必须不显示，否则会拿一段旧思考冒充「正在想」（见 `ChatThinkBar` 挂载点旁的注释）。
- **未核实**：`ChatThinkBar`、`liveThinkingText`、`thinkBarText` 的**真机表现**（滚动时机、占位、长文本取尾）。
  2.2.20 的 APK 已产出（`artifacts/DeepSeek-Harness-companion-v2.2.20.apk`），说明这批改动至少过了一次编译；
  但那次构建是否覆盖工作树的最后一处改动、真机是否逐项点过 —— **未核实**。

### 3.3 原生会话权限模式切换的现状

- **是什么**：`read-only` / `workspace-write` / `danger-full-access` 等运行权限选择（`PermissionSelect`）。
- **现状（原生，完整；本轮换过容器）**：
  - 控件：原来的 `PermissionControl()`（自带 `DropdownMenu` + 完整访问 `AlertDialog`）**本轮已删除**，拆成 `⋯` 菜单里的 `PermissionMenuItems()`（三档选项，选中 `danger-full-access` 时转交二次确认）与 `FullAccessConfirmDialog()`（必须在弹窗里勾选 `acknowledged`，`Button(enabled = acknowledged)` 才可确认）。
  - 挂载点：`ConversationHeader()` 的 `⋯` 菜单 —— 原来挂在 `Composer()` 的输入区底部工具栏。
  - 当前值从会话投影解析：`ChatPresentation.kt` `permissionSelect()`。
  - 切换走命令 `/permission <preset>`：`HarnessViewModel.selectPermissionPreset()`。
  - 文案 `permissionLabel()`、图标 `permissionIcon()` 未动，两个新组件继续复用它们。
  - 可用性条件未变：`permissionEnabled = canWrite && !state.busy`；`permission` 为 null 或无选项时整段不渲染。
- **现状（网页版，完全没有）**：`grep -c -i permission app/src/main/assets/chat.html` = **0**（本轮复测）。网页版自己的 `⋯` 菜单也**没有**权限入口 —— `openSheet()`（chat.html）只渲染「模型」与「智能体」两节；`docs/project/webview-parity-audit.md` 第四节同样记为「网页版全无」，并建议「给页面加一个 REST 调用 + more 弹层一项」。切到 WebView 模式后，会话中途要提权没有任何入口。
- **重要区别**：原生 `⋯` 菜单比网页版 `⋯` 菜单**多两项**（权限模式 + 插话）。插话在页面里是顶栏的独立按钮 `#steer`（chat.html，绑定），**不在** `⋯` 里。所以这不是「照抄页面」，而是**原生替网页版补欠账**。
- **结论**：这一项**不是「原生待实现」，而是「网页版缺」**；移植后原生保留，只是位置从输入区挪进了 `⋯` 菜单。
  已随 **2.2.16 / 2.2.17** 出包（2.2.20 的 APK 里在）。
- **遗留小瑕疵（已核实，不影响行为）**：`FullAccessConfirmDialog()` 的 KDoc 仍写 `[PermissionControl]`，而该组件已删除 —— 悬空文档引用，仅注释层面，不影响编译与运行。

### 3.4 会话文件详情（大小 / 时间 / 预览）

- **是什么**：网页版给会话文件只有一行路径，点了只 toast「文件已在电脑上，不便在手机上直接打开」（chat.html `setFiles`、`openFile`）；原生有完整详情面板。
- **现状（原生）**：
  - `SessionFilesSheet()` ChatScreen.kt（列表 `SessionFileList()`；详情 `SessionFileDetail()`）。
  - 元信息 `SessionFileMeta()` 显示 **contentType · 体积**（`formatFileSize()`）。
  - 预览按类型分派：`previewKind()`（`data/PreviewKind.kt`）→ Markdown / HTML 结构 / 等宽源码（ChatScreen.kt）；截断、「不在授权根内」、「文件过大」等各有文案（见 `SessionFileDetail()`）。
- **v2.2.20 补记**：文件清单的**数据来源**已统一（`sessionFileList()`，见 §2 第 16 项），
  但 `SessionFileMeta()` 仍只显示 `contentType` · 体积 —— **修改时间仍未显示**，这条缺口继续有效。
- **缺口：修改时间没显示。** 数据其实拿得到——`ResolvedPath.modifiedAt`（`data/Models.kt`，`roots/resolve` 返回），`HarnessViewModel.sessionFileEntry()` 也把它带进了 `FileEntry`；但 `SessionFileMeta()` 只用了 `contentType` 与 `size`。网页版更没有这一项。
- **难点**：低。`SessionFileOpen.resolved`（HarnessViewModel）里就有 `modifiedAt`，加一行格式化即可；注意它是 epoch 秒的 `Double`，要确认时区与单位口径。
- **动哪里**：`SessionFileMeta()` ChatScreen.kt（`formatFileSize()` 旁加时间格式化）。
- **同族差别**：网页版**不渲染历史图片**——`<img` 只出现在 Markdown 链接（chat.html）与待发附件缩略图（本地 base64），没有走 `/attachments/:id` 取历史图片的代码；原生有 `loadAttachmentImage()`（HarnessViewModel）。是否算待办由用户定。

### 3.5 附件失败分类文案（401 / 403 / 404 翻成人话）

- **是什么**：把取附件失败的原始错误翻成「用户该去做什么」。
- **现状（原生，完整）**：`HarnessViewModel.describeAttachmentFailure()` 分四类——
  - 401 / TOKEN → `attachment_fail_auth`「授权已过期，去设置页重新配对一次」
  - 403 / SCOPE / FORBIDDEN → `attachment_fail_scope`「缺 files.read」
  - 404 / NOT_FOUND → `attachment_fail_missing`「附件已不在服务器上」
  - 其他 → `attachment_fail_other`（带原始信息）

  文案在 `values/strings.xml`；配套重试策略 `isRetryableAttachmentFailure()`（401/403 可自愈，404 记死）；渲染侧 `AttachmentNotice()` ChatScreen.kt，取值 `failures[attachment.attachmentId]`。
- **现状（网页版，没有）**：上传失败直接 toast 原始 `e.message`（chat.html `uploadAtt`）；历史图片根本不渲染。
- **结论**：不是原生待实现项，是网页版缺；移植后原生保留。
- **未核实**：WebView 桥接层（`web/ChatWebBridge.kt`）是否也做分类——未逐行读。

### 3.6 生成 HTML 的结构预览

- **是什么**：网页版对 `.html` 没有预览（点了只 toast）；原生把 HTML 抽成结构画成原生控件（**刻意不走 WebView**：那等于让一份不可信文件拿到执行权）。
- **现状（原生，完整）**：`data/HtmlPreview.kt` `extractHtmlBlocks()` + `decodeHtmlEntities()`（共 235 行）；渲染 `HtmlStructurePreview()` ChatScreen.kt；分派在 `previewKind()`（PreviewKind.kt，`html/htm/xhtml` → `PreviewKind.HTML`）。
- **有意局限**：不支持表格排版（`tr` 降级成 `LIST_ITEM`，HtmlPreview.kt；ChatScreen.kt 注释写明「刻意不画表格」）；有序列表不区分编号；不执行样式与脚本。这些是取舍，不是 bug。
- **若要做**：网页版要补整套「文件预览」（当前完全没有）；原生若要把表格也排出来，得自写排版器，成本高收益低 → 建议保持现状。
- **未核实**：真机上大 HTML 的抽取性能。

### 3.7 「本次产生的文件」区块 —— **有意保留差异（用户明确说不移植）**

- **是什么**：网页版 `setFiles`（chat.html）在页面底部列出本会话产生的文件路径。
- **用户指示**：**明确说不移植这个。** 因此本文把它记为「有意保留的差异」，不作为待办。
- **现状**：原生**已经有一份，且比网页版更早**（v1.9.0，commit `f6b359f` 引入）：`SessionFilesFooter()` ChatScreen.kt，挂载在消息流末尾（`state.liveChat == null` 且非空时显示）；数据来自 `data/SessionFileRefs.refs()`（调用处见 `SessionFilesFooter` 挂载点）。顶栏另有文件夹按钮 → `SessionFilesSheet()`。
- **待确认（未核实）**：用户说的「不移植」是指「不要再朝网页版形态靠」，还是「把原生这一份也去掉」。本任务按前者处理：**不动**。若要去掉已有的那份，需要用户再确认。

---

## 四、有意不移植的（属于列表页 / 设置页，不该出现在聊天页）

| 项目 | 原生落点 | 网页版 | 处理 |
|---|---|---|---|
| 会话列表 | `SessionPane()` ChatScreen.kt | **无**（`grep` workspace / session list = 0） | 保留在原生的列表层，不进聊天详情页 |
| 新建会话 | `NewSessionSheet(...)`，调用 | **无** | 同上 |
| 工作区管理 | `WorkspaceManagementSheet(...)`，调用 | **无** | 同上 |

**理由**：这三项在窄屏是独立的列表页（`selected == null` 时显示 `SessionPane`），在宽屏是左侧栏（`if (wide)` ChatScreen.kt、`detail({})`）；网页版只承载**详情页**——`ChatWebScreen` 里没有任何列表/新建/工作区代码（`grep` 为空）。把列表塞进聊天页会破坏窄屏的返回栈与宽屏的分栏。

---

### 4.1 决策记录：输入区**方案 A（完全照页面）**

**决策**：用户在「原生输入区照不照 WebView 页摆法」上明确选择**方案 A —— 完全照页面**。药丸里只留 `+` · 输入框 · 发送；模型 / 智能体 / 权限模式 / 插话全部搬进新的顶栏 `⋯` 菜单。

**来龙去脉（页面侧的事实，先查后写）**：

| 页面元素 | 位置 | 内容 |
|---|---|---|
| 输入区 | `#comp` / `#row`（chat.html） | 单行：`+`（`#attachBtn`）· `textarea#ta` · 发送 `#send` |
| `⋯` 菜单 | `#more`（chat.html），点开 `openSheet()` | **只有「模型」和「智能体」两节** |
| 权限模式 | —— | **完全没有**：`grep -c -i permission chat.html` = 0。这是网页版的欠账（`webview-parity-audit.md` 第四节已记并建议补） |
| 插话 | 顶栏独立按钮 `#steer`（页面顶栏，绑定紧跟其后） | **不在** `⋯` 里 |
| 附件入口 | `attachBtn.onclick` | `+` 弹两项：「从相册选图片」/「选择文件」 |
| 运行中提示 | `setRunning()` 改 `ta.placeholder` | 「运行中… 发送即插话」/「输入消息…」 |

**原生做法**：`Composer()` 压成单行药丸；`ConversationHeader()` 加 `⋯` 菜单（模型 + 权限 + 插话）；附件走 `+` 两项；运行中提示并进占位符。逐入口对照见 **§2.1**（10 个入口逐条点名，结论：**换位置，不丢功能**）。

**代价与遗留（四条）**：

1. **原生新增了一个 `⋯` 菜单组件**：`ConversationHeader()` 内联的 `DropdownMenu`（没有单独 composable）。同时删掉 `PermissionControl()`，拆成 `PermissionMenuItems()` + `FullAccessConfirmDialog()`。`ChatScreen.kt` 净增约 105 行（2754 → 2859）。
2. **入口从「一眼可见」变「两步点击」**：模型 / 权限 / 插话现在要先点 `⋯`。这是把输入区从两行压到一行的必要代价。
3. **原生 `⋯` 与网页版 `⋯` 内容不同**：原生多「权限模式」与「插话」两项。若以后要再对齐页面，要么给页面补这两项，要么接受这个差异（当前选择是后者）。
4. **两个旧键失去引用**：`send_mode_queue` / `send_mode_steer` 在 `app/src` 全仓已无 `R.string.` 引用（保留未删）；新增 `message_hint_running_queue` —— 网页版只有「即插话」一种占位符，原生因设置里有「繁忙时默认行为」而多一种文案。

**易混淆**：`SessionPane()` → `SessionRow()` 里本来就有个 `MoreVert` 菜单（`R.string.session_actions`，重命名/归档/删除）。本文说的 `⋯` 是**聊天详情页顶栏新加的那个**，两者不是一回事。

---

## 五、未核实清单（别当结论用）

1. 原生 Markdown 表格在**真机窄屏**的实际观感（是否横向滚动、列宽是否压扁）—— 仍未跑真机，**未核实**。**注意区分**：`dsh-ui` **卡片**里的表格已在 **2.2.16** 落地（`GenUiCard.kt` 的 `GenUiTable()` / `genUiTableColumnWidthDp()`：列宽 `max(96dp, 可用宽度/列数)`，装得下铺满、装不下横向滚），那是卡片表格；本节说的是 **Markdown 渲染表格**（走 `multiplatform-markdown-renderer` 库的默认实现），两条线别互相当成已验证。
2. **编译层面已过、真机层面未知**：本文记的这串改动（`Composer()` 药丸化、`ConversationHeader()` 的 `⋯` 菜单、
   `PermissionMenuItems()`、`FullAccessConfirmDialog()`、`composerHint()` / `ComposerHint`、`ChatThinkBar`、
   `mergeToolRuns()` / `toolActionsFor()`、`SwitchRow`、`withPreferences()`、`ModelsScreen()`、
   `ChatHistory.deliverables()`、`connectGate` / `ConnectionEntryRow`、`sessionFileList()`）
   已随 **2.2.20** 出包（`artifacts/DeepSeek-Harness-companion-v2.2.20.apk`），编译不再算悬案；
   **真机逐项验证未做** —— 本次收口只在工作树里读代码 + 数行数 / 算 md5，没跑 gradle、没上真机。
3. `ResolvedPath.modifiedAt` 是否在所有 `roots/resolve` 返回里都有意义的值（服务端字段语义未核实）。
4. 网页版**当前**确认没有权限入口（`grep -c -i permission chat.html` = 0，`openSheet()` 只有模型/智能体）；但页面后续是否会补，未知。
5. ~~`web/ChatWebBridge.kt` 是否对上传失败做分类~~ → **本轮已核实：无分类。** `web/` 下（`ChatWebBridge.kt` 328 行、`ActiveChatBridge.kt`）grep `401|403|404|fail_|describeAttachment` 全空，只有 `takeAttachment()` 这类 uri 管道；上传失败文案仍来自页面自身 toast（chat.html `uploadAtt`）。
6. 网页版是否存在历史图片渲染的隐藏路径——按 `grep -n '<img' chat.html` 判断为无（只命中 Markdown 链接与 待发附件 base64）；**仍未逐行读完 chat.html 全 2251 行**。
7. **行号会漂移**：v2.2.20 收口时 `ChatScreen.kt` = **2907 行** / md5 `4111fbf44fee4dfdfe5070da24bff9c9`；
   更早的两个快照是 2859 行 / `bfbaaa68f27b7d1e56579847bdb7941b` 与 2754 行 / `b060fdb5407348d427c5e16e1be86f8e`。
   **与代码对不上时以函数名为准**，不要按行号跳转。
8. **死键式遗留（已核实）**：`send_mode_queue` / `send_mode_steer` 两个字符串键在 `app/src` 全仓已无 `R.string.` 引用（grep 为 NONE），仅定义还在（`values/strings.xml`、`values-en/strings.xml`）。删不删由 lead 定。

---

## 六、取证方式（可复现）

- `git diff HEAD` 看本轮改了什么（全部还在 working tree，未提交）。
- `grep -c permission app/src/main/assets/chat.html` → `0`；`rename`/`archive`/`workspace` 同为 `0`。
- Markdown 表格：解 aar 的 `classes.jar` 查 `MarkdownTableKt.class` / `MarkdownComponents.getTable`；对照 `build.gradle.kts` 与所有 `Markdown(` 调用点。
- 行号均来自写文档时的 `grep -n` 与 `read`，未凭印象。
- 本轮（药丸化之后）复测：`grep -c -i permission app/src/main/assets/chat.html` → `0`；`grep -n '<img' app/src/main/assets/chat.html` → 仅 2 处（Markdown 链接、待发附件 base64）；`grep -rn 'R.string.send_mode_queue|R.string.send_mode_steer' app/src` → 无引用（NONE）。
- `⋯` 菜单消歧：`grep -n 'MoreVert' app/src/main/java/io/github/hakunm/deepseekharness/ui/ChatScreen.kt` → `SessionRow()` 的会话操作菜单 与 `ConversationHeader()` 的 `⋯` 两处，注意区分。
- Markdown 表格复核：`compactMarkdownTypography()` 仍配 `table = bodySmall`；`Markdown(` 的 5 处调用点**均未传 `components`**；Gradle 缓存里 `multiplatform-markdown-renderer.aar` 仍在 —— **未发现反证**。
- 本轮新增/变更字符串（values 与 values-en 键名一致）：`more_actions`、`add_attachment`、`attachment_pick_image`、`attachment_pick_file`、`message_hint_running_steer`、`message_hint_running_queue`。
- **v2.2.20 收口复测**（2026-10-11，全部在工作树里跑，未跑 gradle）：
  - `wc -l` + `md5sum`：`ChatScreen.kt` 2907 / `4111fbf…`；`ChatPresentation.kt` 446 / `182044b…`；`chat.html` 2251 / `6423e83…`。
  - `grep -n 'SwitchRow('` → `SettingsScreen.kt` 定义 1 处 + 调用 3 处（`useWebChat` / `showContext` / `genUiDebug` 各一）。
  - `grep -rn 'withPreferences\|sessionFileList\|connectGate\|deliverables'` → `HarnessState` 三处整份重建都盖上偏好；
    `sessionFileList()` 挂在底部与标题栏弹层两个调用点；`deliverables()` 在 `ChatPresentation.kt`。
  - `grep -rn 'Markdown('` → `ChatScreen.kt` 5 处 + `FilesScreen.kt` 1 处，**均未传 `components`**。
  - `grep -c '@JavascriptInterface' web/ChatWebBridge.kt` → 11 个方法（第 12 次命中在 KDoc 注释里）；
    页面 `App.*` 实调 8 个（`close copy openLink pageReady pickFile poll request takeAttachment`）。
