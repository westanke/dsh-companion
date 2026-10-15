# 开发日志

本文件记录本仓库的改动。每条都写清**基于什么、为什么、干了什么、测了什么**。
上半部分是本仓库新增的开发记录，下半部分是上游 `Hakunm/dsh-android-app` 的历史（原样保留）。

## 2026-10-15 · v2.2.39：修 Ctrl+Enter 发送（会话列表收起已回退）

用户第三轮反馈两件事：「给会话列表也加个隐藏模式」、「Ctrl+Enter 目前不是发送」。
本版**只保留第二件**；第一件实现后用户实测要求回到 v2.2.38 的界面，已整套回退
（含代码、字符串、组件），下面「为什么回退」一节记下这段过程备查。

### ① Ctrl+Enter 发送

v2.2.38 只修了软键盘 hide 的时机（组合时 → 获焦时），事件链仍可能不通。
这次补上**来源判据**：

```
Android KeyEvent.FLAG_SOFT_KEYBOARD（值 2）
  ├─ IME 通过 sendKeyEvent() 送进来的事件：带这个 flag
  └─ 外接键盘的事件：不带
```

于是规则变成「**触摸屏的 Enter 一律换行；只有物理键盘的 Ctrl+Enter 才发送**」——
不再依赖「IME 不会补发带修饰键的事件」这种没有保证的假设。
判据抽成纯函数 `isSoftKeyboardEvent(nativeFlags)` 并单测（`ComposerPillTest` 从 9 扩到 11 用例）。

### ② 会话列表收起：实现了，已回退

原本按用户方案做了「会话列表也可收起」：默认收起、左缘 48dp 竖条开关、
展开时**挤**内容（占 320–440dp，收起时全让给会话详情）——与一级导航的
overlay 抽屉刻意做成两种形态，理由是「看列表和看会话本来就是二选一」。
另有一条设计：**选中会话后不自动收起**，免得用户在几个会话间切换时每切一次都要重展。

用户实测后结论是「界面还是回到 2.2.38」。已撤干净：

- `ChatScreen.kt` 的 wide 分支回到 v2.2.38 逐字一致的形态（只多一段「曾试过、已回退」的注释）；
- 删除 `SessionPaneToggle` 组件、`SessionPane` 的 `onCollapse` 参数与标题栏关闭按钮；
- 删除字符串 `session_pane_expand` / `session_pane_collapse`（中英双份，按「无用即删」）。

**记下这条的价值**：一级导航默认收起（v2.2.37）用户接受，同一思路搬到二级会话列表
却被否 —— 差别在**频次**：导航一天切几回，会话列表一分钟切几回。常驻的代价
（少 320–440dp）由高频操作承担，不划算。

### 测了什么

- `ui/ComposerPillTest.kt` 从 9 用例扩到 **11 用例**：`isSoftKeyboardEvent` 两个方向
  （带 flag 认出来、与 `FLAG_FROM_SYSTEM`/`FLAG_EDITOR_ACTION` 并存也认得出；
  物理键盘的 0 / 单独 flag 不误判）。
- 全量 **27 类 / 290 用例 / 0 失败**；`docsCheck` 8 文档 43 任务一致。
- **待真机验收**：外接键盘 Ctrl+Enter 发送、触摸屏 Enter 仍换行（界面已回到 v2.2.38，
  那部分无需再验）。

---

## 2026-10-15 · v2.2.38：修 v2.2.37 两个实测回归

用户装上新版后报两件事：「平板模式下抽屉图标和会话列表的标题重复了，无法点击」、
「硬键盘回车、Ctrl+回车、Shift+回车 都是换行」。两条都是 v2.2.37 新引入的回归，
本版修掉。**改动仍只在 wide 分支或物理键盘场景，手机端零改动。**

### ① 汉堡按钮不再浮在内容之上

v2.2.37 把导航开关按 `Modifier.align(Alignment.TopStart)` 浮在内容左缘 —— 而那正是
ChatScreen 会话列表栏顶部「会话」标题的位置。浮层在上、内容在下，**那一整片点击被浮层
吃掉**，用户看到的就是「两个图标重着且点不动」。

改为内容左缘一条**独立的 48dp 竖条**（里面装开关）：

- 竖条自己占位，内容区从它右边开始 —— 天然不重叠，不需要跟任何页面协商避让；
- 代价是内容区少 48dp，相比 v2.2.37 之前常驻的 264dp 面板仍省 216dp；
- 顺带当「有导航可开」的常驻提示，不用时也认得出它在哪儿。

### ② 软键盘 hide 改到输入框获焦时

这条是「Ctrl+Enter 不发送」的根因。v2.2.37 写的是
`LaunchedEffect(physicalKeyboard) { keyboardController?.hide() }` —— 它在 Composer
**组合时**执行，而那一刻输入框**还没有焦点**，hide 是个空操作。等用户点输入框，
软键盘照弹；**IME 一旦接管，物理键盘的 Enter / Ctrl+Enter 全被它吞掉，
`onKeyEvent` 一个事件都收不到**。用户看到「回车就是换行」——换行正是 IME 的行为，
所以三种组合键看起来全都一个样。

改为 `BasicTextField.onFocusChanged` 监听焦点，**焦点为真即 hide**。每次点输入框都收一次，
物理键盘的事件链才通得过来。

顺带按「无用即删」删掉那个 `FocusRequester`：没有任何地方调 `requestFocus()`
（没有「进页面就抢焦点」的场景），`onFocusChanged` 独立工作，它是多余的。

### 一处仍然成立的底线（写在文档里备查）

**软键盘的「换行」键与物理 Enter 在 `onKeyEvent` 里派发同一个 `Key.Enter`，
事件本身分不出来源。** 所以「Enter 一律换行、发送只走 Ctrl+Enter」不能改 ——
一旦让 Enter 发送，软键盘用户就再也打不出换行。Shift+Enter 换行是正确的、预期的行为。

### 测了什么

- `:app:testDebugUnitTest` **288 用例 / 0 失败**（本轮无新增纯函数可测：
  两处都是 Compose 布局/焦点时机，属「阅读 + 真机验证」范畴，见 AGENTS.md 第 5 节）；
  `docsCheck` 8 文档 43 任务一致。
- **待真机验收（本轮重点）**：平板模式下点左缘竖条能开导航、点「会话」标题不再被吃掉；
  平板外接键盘点输入框**不弹软键盘**，Ctrl+Enter 能发送、Enter/Shift+Enter 仍换行。

---

## 2026-10-15 · v2.2.37：平板导航默认收起 + 软/硬键盘发送键

用户看了 v2.2.36 的平板截图后给新方案：「设置的抽屉继续默认收起来，让会话页面显示区域更大，
考虑键盘使用的软？硬键盘，发送键咋改」。本版实现这两条，**全部收敛在 wide 分支或
物理键盘场景，手机端零改动**。

### ① 导航面板默认收起

wide 分支原来**常驻**一根 264dp 的导航面板，平板上白白吃掉约 20% 屏宽 ——
会话页的内容区只有「屏宽 − 264dp」。改为：

- **默认收起**：左缘只留一个 48dp 汉堡按钮（MASTER 的触控目标规范）。
- **按需展开**：点汉堡以 **overlay** 形式从左侧滑出 264dp 抽屉，盖在内容之上
  **不挤压内容**（与手机 `ModalNavigationDrawer` 同一套观感，只是这里常驻且状态驱动）。
- **用完即收**：点抽屉外的透明区域、切 section、宽窄变化（wide 翻转）时自动收起 ——
  抽屉是一次性的，选完就该把屏幕还给内容。

内容区因此从「屏宽 − 264dp」变成**整屏**。

### ② 软/硬键盘发送键

先说一个既有事实：**软键盘的「换行」键与物理 Enter 在 `onKeyEvent` 里派发同一个
`Key.Enter`，事件本身分不出来源**（`Composer` 里那段注释早已踩明）。
所以「Enter 一律换行、发送只走 Ctrl+Enter」是**不能改的底线** —— 一旦让 Enter 发送，
软键盘用户就再也打不出换行。

这次补的是两个真问题：

| 问题 | 修法 |
| --- | --- |
| 平板 + 外接键盘是常态，点输入框却仍弹出占掉半屏的软键盘 | 检测到物理键盘（`Configuration.keyboard == KEYBOARD_QWERTY`）时，输入框获焦即主动 `hide()` 软键盘控制器。不依赖实验 API，也不改 `KeyboardOptions`（那会连 IME action 一起改掉） |
| 硬键盘用户根本不知道 Ctrl+Enter 能发送 | 空闲占位符追加「Ctrl+Enter 发送」（`composerHint` 新增 `IDLE_PHYSICAL` 态） |

**只加在空闲态**：运行中两段文案（RUNNING_QUEUE / RUNNING_STEER）本身已在说
「发送即插话/排队」，再拼一段会超出占位符的一行容量（`maxLines = 1` + Ellipsis）。

### 两处刻意的取舍

- **会话列表栏不跟着一起收起**：用户只说「设置的抽屉」（一级导航）。ChatScreen 的
  会话列表是二级栏，v2.2.36 已让它随屏宽伸缩，再收会连带改动选中态、`@` 面板定位等
  一串东西 —— 收益不成比例，留给后续排期。
- **`Configuration.keyboard` 判据的三态**：`KEYBOARD_QWERTY` 才算物理键盘
  （外接键盘 / 平板桌面模式 / 折叠屏展开）；`KEYBOARD_UNDEFINED` 一律按软键盘处理 ——
  不确定时提示短一点不会错，误报「有物理键盘」才会打扰触摸用户。

### 测了什么

- `ui/ComposerPillTest.kt` 从 3 用例扩到 **9 用例**：`IDLE_PHYSICAL` 新增（空闲 + 物理键盘）、
  运行中两态**不受**物理键盘影响、默认参数 = false（手机路径行为不变）、
  `hasPhysicalKeyboard` 三态（QWERTY / NOKEYS / UNDEFINED）。
- 全量 **27 类 / 288 用例 / 0 失败**；`docsCheck` 8 文档 43 任务一致。
- **待真机验收**：平板外接键盘三连（点输入框不弹软键盘、Ctrl+Enter 发送、
  空闲占位符带提示）；导航抽屉开合与「用完即收」。

---

## 2026-10-15 · v2.2.36：平板界面三处优化（全部只在 wide 分支，手机端零改动）

用户发来平板截图问「有优化建议吗，但要保持手机端界面不变」。审查下来问题全出在同一个根因：
**会话列表栏宽沿用手机抽屉的 304.dp，原样搬到了平板上**。本版改三处，全部收敛在
`wide`（`maxWidth >= 840.dp`）分支里，手机路径编译期即可确认零 diff。

### 截图暴露的三处伤

| 位置 | 现象 | 根因 |
| --- | --- | --- |
| 会话列表标题 | `/media/wangke/OFF…`、`阅读https://github.c…` 大面积腰斩 | 304.dp 是手机抽屉宽度，直接搬到平板 |
| 未选会话时的右侧 | 超过一半屏幕的纯空白，只居中一句「选择一个会话」 | 该位置没有任何内容设计 |
| 聊天详情顶栏 | cwd 路径单行被砍 | `maxLines = 1` 是窄屏规格 |

### 干了什么

1. **会话列表栏宽随屏宽伸缩**（`sessionPaneWidth`）：`0.25 × 屏宽`，夹在 `320–440dp`。
   下限 320dp 保证不比旧的 304.dp 更窄，上限 440dp 保证不把聊天区挤没。
2. **会话行标题 wide 下放宽到两行**：长路径标题换行比截断更可读。手机保持单行。
3. **未选会话时右侧改为 `SessionOverviewPane`**：主机状态 + 最近 5 个会话
   （点击直接选中）+ 新建会话入口。会话为空时退回原有的空态。
4. **聊天详情顶栏标题 wide 下允许两行**：兜底到长 cwd 路径时不再腰斩。
5. **标题兜底链抽成纯函数 `sessionDisplayTitle`**（title → workspaceTitle → cwd →
   id 前 12 位）：会话列表与概览面板共用一份，不再各抄一遍。

### 两处刻意的取舍

- **概览面板不显示「最后活跃时间」**：内核 `ChatSession.updatedAt` 的单位未定
  （毫秒/秒都可能），猜错就显示 1970 年 —— 锦上添花的单位探测宁可不做，
  最近会话卡片只放「标题 + 工作区 + 运行状态」。
- **`BoxWithConstraints` 只包 wide 分支**：手机/窄屏路径保持原样，
  像素级零改动。栏宽计算在 lambda 开头取出（`paneWidth`），
  不进 `Row` 的 content —— 否则嵌套 receiver（`BoxWithConstraintsScope` → `RowScope`）
  会让 `maxWidth` 解析歧义，编译直接报错（本版踩过）。

### 测了什么

- 新增 `ui/SessionPaneLayoutTest.kt`（9 用例）：栏宽三档边界（840/960 → 320dp 下限、
  1280/1440/1600 → 等比、1920/2560 → 440dp 上限）+ 「不低于旧值」回归护栏 +
  标题兜底链三级 + 概览排序截断。
- 全量 **27 类 / 282 用例 / 0 失败**（口径：`scripts/build.sh :app:testDebugUnitTest`
  后数 `app/build/test-results/` XML）；`docsCheck` 8 文档 43 任务一致。
- **待真机验收**：平板横屏三处观感（栏宽 320dp 是否够用、概览面板密度、
  顶栏两行标题会不会把行高压塌）。布局事实靠阅读 + 真机，单测替代不了。

---

## 2026-10-13 · v2.2.35：长文件名改为横向滚动

用户报「设置-文件中的文件名如果过长，无法显示全，导致看不完，判断不出到底是哪个文件」，
随后明确要求「让显示不全的文件名滚动显示」，并确认四处「都改」。

### 为什么这里格外难分辨

文件页那一项的第二行给的是**「类型 · 大小」**—— 对区分文件**毫无帮助**。而这里的文件名
恰恰经常**又长又前缀雷同**：几个 `DeepSeek-Harness-companion-v2.2.xx.apk` 截断之后
长得一模一样，用户确实无从下手。

### 走了两步

1. **先放宽到 3 行**（文件列表）/ 2 行（预览标题）。绝大多数名字够用了，
   但超长名仍会截断 —— 而用户真正要的是「总能看完」。
2. **改用横向滚动**（本版最终形态）：文件名放不下就滚动，而不是省成 `DeepSeek-Harness-…`。

### 四处统一（用户确认「都改」）

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| 文件列表项 | 3 行换行 | 1 行 + 滚动 |
| 文件预览标题 | 2 行换行 | 1 行 + 滚动 |
| 会话「文件」弹层的文件名 | 截断 | 1 行 + 滚动 |
| 输入框 `@` 补全面板的文件名 | 截断 | 1 行 + 滚动 |

### 两处刻意的取舍

- **滚动与多行换行互斥**：marquee 要单行才滚得对，所以文件页那两处是**替换**而非叠加。
  滚动更彻底 —— 换行对超长名仍会截断，滚动总能看全。
- **用持续滚动**（`iterations = Int.MAX_VALUE`）：只滚一遍的话，动画结束会回到起点，
  结果与截断无异，等于没改。`basicMarquee` **只在文本溢出时才动**，短名字完全不受影响；
  每轮之间默认还夹 1.2s 停顿，不是永不停歇地跑。`LazyColumn` 会回收屏幕外的项，
  真正同时在滚的只有当前可见的几条。

**各处的第二行一律不动**（类型·大小 / 相对路径 / 完整路径）：它们是会换行、更适合阅读的
那份参照，与滚动的名字互补 —— 一个负责好看全，一个负责不跳。

### 顺带：产物目录不再囤积

用户要求「Release 有的 APK 本地不保存了（除了必须要的，和最新的）」。`artifacts/` 现在只留
两个**上游基线包**（git 跟踪，比对证书用）与**最新版**；已删除本地 v1.9.0 与 v2.2.33 包
（两站 Release 均有资产）。`SHA256SUMS.txt` **不动** —— 它是全量历史校验记录，不是本地
文件清单。规则与「发新版 → 上传 → 回读校验 → 再删旧包」的动作顺序都写进了 STATUS。

### 测了什么

- `:app:testDebugUnitTest` **273 用例 / 0 失败**。
- 编译无未使用导入告警（`basicMarquee` 需要新增导入，两处文件都已加）。
- 四处 `Modifier.basicMarquee(iterations = Int.MAX_VALUE)` 逐一 grep 确认就位。

## 2026-10-13 · v2.2.34：会话页三处减法 + `@` 面板终于能滚

纯 App 侧改动，不动插件。用户提的三条 + 一条补充。

### 一、`@` 面板上划会拉出侧边栏

用户描述是「在 @ 出来的文件列表上划，划出『会话、文件、插件、模型、设置』的侧边栏」，
补充说「文件要随上划动作上划」。

**看着像抽屉乱抢手势，实际根因在面板自己身上**：那个浮层用的是 `Column` + `forEach`，
**它根本不能滚动**，还硬截 `.take(8)`。用户上划时列表纹丝不动 —— 手势没被任何控件消费，
就顺着手势通道一路传到外层 `ModalNavigationDrawer`，抽屉于是被拉出来。

**改法两层，缺一不可**：

- 列表换 `LazyColumn`（上限 264dp，约 5 条半），条数 `.take(8)` → `.take(50)`。
  能滚了就不该再截那么狠 —— 一个几十文件的目录里 8 条几乎等于找不到。
- 面板开着时**关掉抽屉的横划手势**（新增 `HarnessUiState.composerOverlay`，
  由 ChatScreen 同步、App.kt 消费，与既有的 `webChatOpen` 同一套思路）。
  这道保险仍需要：列表滚到顶/底时手势会外溢，面板之外的横划也不该开抽屉。

`/` 命令面板的「是否显示」判据也被抽成 `commandSuggestions()`，两处共用一条规则 ——
否则面板可见时抽屉该不该关，会出现两套判据漂移。

### 二、取消会话页顶部的「长按可选中复制…」提示条

连 `copyHintDismissed` 状态与中英两条字符串一起删。

### 三、取消「本次产生的文件」区块

用户看着的是**原生**会话页，但 **WebView 版会话页另有一套**（把文件列表推给网页渲染）。
用户确认「一并取消，保持一致」，所以两边都删：

- 原生：删 `SessionFilesFooter` 组件、它的调用点，以及只服务它的 `sessionFiles` 变量；
  中英 `session_files_footer` / `_hint` 两条字符串。
- WebView：`chat.html` 的 `<details id="files">` 元素、`setFiles` / `openFile` 两个函数、
  `window.dshChat.setFiles` 导出、`#files` 系列 CSS，以及 ChatWebScreen 里那段推送
  （连带去掉已无用的 `SessionFileRefs` 导入）。

**没删的**：顶部那个文件夹按钮与 `sessionFileList()` —— 前者是另一条入口，
后者当初就是为了让「底部区块」和「顶部弹层」显示同一批文件才抽出来的，弹层还在用。

**删 WebView 那段格外小心**：`chat.html` 顶部有条真实教训注释 ——
v2.2.7 撤了 `#diag` 元素却漏删 `.onclick` 绑定，顶层语句在加载时 `$('diag')` 返回 null、
抛 TypeError、整个脚本中断、`window.dshChat` 根本没定义，页面全白。所以这次删完
专门把内联脚本抽出来跑了 `node --check`，确认语法完整。

### 测了什么

- `:app:testDebugUnitTest` **273 用例 / 0 失败**。
- 编译无未使用导入告警；grep 确认 `copy_hint`、`session_files_footer`、`SessionFilesFooter`、
  `setFiles`、`openFile`、`id="files"` 全无残留。
- `chat.html` 内联脚本 `node --check` 通过（防白屏）。

## 2026-10-13 · v2.2.33：修 `@` 补全 —— 它从写出来就没成功列出过文件

配套插件端 v2.3.5。用户报「APP 会话中使用 `@` 出现问题」，截图里面板只显示
**「读不到这个会话的工作目录」**，一个文件都列不出来。

### 三层根因，第一层是「注释写反了」

| 层 | 问题 |
| --- | --- |
| 1 | v2.2.31 的注释把 `cwd` 记成**绝对路径**，实际是**相对授权根**的 wire 路径 |
| 2 | 会话工作目录 = 授权根本身时，服务端算出的相对路径是**空串**，被当成「没有工作目录」丢掉 |
| 3 | App 手里没有根的 realPath（`RootView` 刻意不含），拼不出绝对路径 |

第 1 层直接导致 v2.2.31 的实现走向错误：它把 `cwd` 送去 `api.resolvePath()`，
而服务端（`router.ts` 的 `/roots/resolve`）对相对路径是**硬拒绝**的：

```js
if (!path.isAbsolute(requested)) throw new ApiError(400, 'PATH_NOT_ABSOLUTE', ...)
```

于是第二步必然失败、`mentionEntries` 保持 null —— **这个补全从来没有列出过任何文件**。
而那次「翻译」本身也是多余的：会话对象里本来就有 `rootId`，列举接口要的正是
「根 id + 相对根的路径」，两个值都是现成的。

### 干了什么

- **去掉 `resolvePath` 那一圈**，直接用 `session.rootId` + `session.cwd` 列举。
- **空 `cwd` 不再被当成「没有」**：它是合法值，表示会话工作目录就是授权根本身，
  照常列举根目录即可。
- **新增 `cwdAbsolute` 用法**（配套插件端 v2.3.5）：服务端补上绝对路径。
  拿不到时（老服务端）降级插入相对根的路径 —— 能用，但不完整。
- **`MentionQuery.absolute` 纠正语义**：列举接口给的 `entry.path` 是**相对授权根**的
  完整路径，会话目录只是它的一段前缀，所以正确操作是**换前缀**而不是「cwd + 相对路径」
  拼接。旧实现和旧单测都按错的语义写，一起改了；并钉住「前缀按整段比对」——
  否则 `"ws"` 会误配 `"wsx/a.txt"`，拼出一个不存在的路径。
- **面板文案分三态**（新增 `mentionFailed`）：`null`+未失败 = 还在读、`null`+失败 =
  读不到、空列表 = 真的是空的。此前「加载失败」会被显示成「这个目录还是空的」——
  把失败谎报成空，和插件页那句「package.json 可能损坏」是同一类毛病。

### 兼容性

- `cwdAbsolute` 在 `ChatSession` 里是**可空默认 null**：老服务端不返回时反序列化照常成功，
  走降级分支。
- **不要求两端同时升级**：App 先升只会插相对根路径；插件先升则老 App 完全无感
  （它不认识这个字段，`ignoreUnknownKeys` 直接忽略）。

### 测了什么

- `MentionQueryTest` 重写 `absolute` 那组：从 3 条旧语义用例换成 5 条新语义用例
  （换前缀 / 根目录空串 / 多余斜杠 / 同前缀兄弟目录不误配 / 拿不到绝对根时降级）。
- **273 个用例全绿**（`:app:testDebugUnitTest`），0 失败。
- 构建走 `android-toolchain` 里的 JDK17 + gradle-home（系统 PATH 里没有 `java`，
  构建缓存也都在沙箱外，本轮已补齐进工作区）。

## 2026-10-13 · v2.2.32：「插件」页从只读清单升级为可操作

配套插件端 v2.3.2（profile 判定修复）+ v2.3.3（详情字段与 PATCH 端点）。用户选的 A+B。

### 详情（B）

- **简介**：手机上只有一串 `@scope/name@1.2.3` 时，用户没法判断「这个能不能停用」，
  描述就是那个判断的主要依据 —— 官方清单页也把它放在这一行。
- **主页可点**：服务端已把 `git+` 前缀与 `.git` 后缀剥干净了（否则点开是 git 协议地址）。
  跳转包在 `runCatching` 里：主页字段来自第三方包的 package.json，
  可能是系统不认的 scheme、或这台设备上没浏览器，为一次跳转把页面崩掉不值得。
- **更新提示只在确实有新版本时出现**。`updateAvailable` 是**三态**：`null` 表示没查，
  与 `false`（已是最新）不是一回事。显示「已是最新」是撒谎，显示「未知」是噪音，
  所以 `null` 什么都不显示。
- **顺带更正一条过期文案**：`plugins_unavailable_profile_unknown` 原本写
  「请确认 DSH 启动时带有 --profile 参数」—— 那是 v2.3.2 之前的说法，
  现在 `dsh web` 简写也能识别了，改成「以 `dsh web` 或 `dsh --profile web` 启动」。

### 启停（A）

- **Switch 只在两个条件都满足时出现**：服务端说这个插件 `toggleable`，且本设备有
  `settings.write`。没权限时不画一个点了会失败的开关 —— 缺什么由页面顶部的横幅说明。
- **不能启停的必须说明原因**（官方包 / 电脑上没装 / 声明缺失）。不给原因的话，
  用户会去找一个根本不存在的开关。
- **先确认再执行**：这是**改电脑上 profile 的 package.json** 的操作，写坏了 dsh
  下次起不来、而用户此刻在手机上。对话框里说清「会写进电脑上的插件清单，并自动备份原文件」。
- **成功后重拉清单**：本地那份的 `loaded` 还是旧值，不重拉开关会弹回原位，
  用户会以为没生效（实际生效了，只是要重启 DSH 才算数）。
- **把「要重启」说在明处**：页面上有条件地显示一行说明（只在有可启停插件时出现，
  纯只读清单说这句话是多余的），操作成功也给一次性提示。服务端恒返回
  `restartRequired: true` —— 不说的话，用户点完看到插件照旧在跑，只会以为功能坏了。
- **「检查更新」单独一个按钮**：它是重活（每个包一次 registry 请求，清单从毫秒级
  变成好几秒），与顶栏那个轻快的「刷新」不是一类动作，并列会让人以为两个一样快。

### 实测（真机侧还没装，但服务端数据已逐条核对）

用户已重启 dsh，插件端报 `pluginVersion: 2.3.3`、`profile: web` ——
**v2.3.2 的 profile 判定修复确认生效**（此前这个页面读不出清单）。清单实测：

```
profile=web  已加载=17  问题项=3  共 20 条
可启停 15 条：12 启用 / 3 停用（@liustack/modlens、dsh-hyperframes、dsh-remotion）
不可启停 5 条：全部是 @deepseek-ai/ 官方包
```

描述字段中英文都有拿到（如 `dsh-ffmpeg` 是「用自然语言调用 FFmpeg，处理已有音视频文件。」），
说明数据链路完整。

### 测了什么

- `./scripts/build.sh :app:compileDebugKotlin` 通过
- `./scripts/build.sh :app:testDebugUnitTest` 全绿（25 类 / 271 用例）
- 中英字符串逐条比对：新增 13 条两侧都有，`plugins_toggle_confirm_title` 的
  `%1$s`/`%2$s` 占位符两侧一致（格式符不匹配会让 Android 直接崩）
- **待真机确认**：开关点击 → 确认框 → 写入 → 提示重启；以及重启后插件状态确实变了

## 2026-10-13 · v2.2.31：输入框 `@` 调取当前工作目录的文件与文件夹

> 用户原话：「官方有两个输入框的功能一个是"/"加载命令，"@"是调取当前工作目录 文件或者文件夹」。

`/` 加载命令本 App 早有；`@` 这一半**完全没有**（全仓 `grep '@'` 零命中）。本轮补上。

### 数据从哪来

会话对象自带 `cwd`（**绝对路径**），而目录列举接口要的是「根 id + 相对根的路径」，
中间得过一次 `api.resolvePath(cwd)` 做翻译 —— 客户端拿不到根的绝对路径，自己算不出来。

```
ChatSession.cwd (绝对)
  → resolvePath(cwd)        → ResolvedPath(rootId, 相对路径)
  → entries(rootId, path)   → List<FileEntry>(name, path, kind, size…)
```

任一步失败就**静默放弃**（`mentionEntries` 保持 null）：`@` 补全出不来不影响打字与发送，
不该为此弹一条错误提示打断用户。但进了 `@` 面板会明说「读不到这个会话的工作目录」——
比让用户对着一个永远不出现的列表等要好。

### 三个刻意的设计决定

**1. 与「文件」页的状态分开存。** 新增 `mentionEntries` 字段，不复用 `directory`：
后者是用户自己点来点去的浏览状态（会切根、进子目录），而 `@` 必须**始终**指向本会话的 cwd。
共用一个字段会导致「在文件页翻到别处，回会话里 `@` 补全出错目录」这种阴间 bug。
同理，**换会话时必须清空** —— 否则切过去的头几百毫秒里 `@` 会列出上个会话的文件，
用户手快选中就发出一条指向错误路径的消息。

**2. 触发规则从严：`@` 必须在行首或空白之后。** 这条单独抽到
`data/MentionQuery.kt`（纯逻辑，配 15 条单测），因为它是本块**最容易被改坏**的地方：

- `foo@bar.com` 里的 `@` **不触发** —— 前面是字母，那是邮箱不是提及。
- 宽松匹配的后果不是「少一个功能」，而是**正常打字时突然弹出文件列表**，
  那种打扰比不弹更让人烦躁。

**3. 选中只替换「@…末尾」那一段。** 不做整行替换：用户很可能已经写了半句
「帮我看看 @xxx」，整行替换会把前半句吃掉。

### 插入的形态

插入**绝对路径**（`@/home/u/proj/src/Main.kt `），与官方及内核惯例一致：
会话事件与工具调用里给模型看的都是绝对路径，相对路径会让模型先猜「相对于谁」，
猜错就是一次无效工具调用。

结尾字符按类型分：**目录补 `/` 且不加空格**（用户大概率要继续往里选下一级，接着打正好）；
**文件补一个空格**（把这个词结束掉）。

### 面板本身

- 目录排在文件前面：`@` 一个文件夹继续往里选是常见流程，混排时文件夹总被文件名淹掉。
- 每行第二行显示**相对工作目录的路径**：绝对路径往往太长被截断，
  而用户判断「是不是我要的那个」靠的通常就是中间那几级目录。
- 空目录 / 读不到工作目录各有专门文案，不静默什么都不发生。
- 与 `/` 命令面板**互斥**：`@` 命中时不出命令面板（两者判据本来互斥，这里把规矩写死，
  免得将来判据放宽后两个列表叠在一起）。

### 测了什么

- 新增 `MentionQueryTest`：**15 条用例**，其中一半在测「**不该**触发」的情形
  （邮箱、光标已离开提及词、无 `@`、多个 `@` 取最后一个未闭合的）。
- 写测试时自己踩了一次坑：手算下标算错两处（把 `帮我看看 @src` 的 `@` 数成 6、
  `" @"` 里的 `@` 数成 0），**是测试断言错、不是实现错** —— 改断言而不是改实现，
  因为实现的行为（前面是空白才算提及）正是想要的。
- `./scripts/build.sh :app:compileDebugKotlin` 通过
- `./scripts/build.sh :app:testDebugUnitTest` 全绿：**25 个测试类 / 271 用例**
  （上轮 24 类 / 256 用例，本轮 +1 类 +15 用例）
- 补全面板的实际交互（弹出时机、点击插入、目录续选）**待真机确认**

## 2026-10-13 · v2.2.30：熄屏就断流，亮屏就接回

> 用户定下的策略（原话）：「能让 熄屏 就不在线了，如果不是熄屏状态 实时优先」。

这是 v2.2.29 修掉「流式期间每秒拉全量历史」之后，**耗电的第二大头**：
`eventSubscription` 原先只在 `onCleared()` 与主动断开时关闭，App 切后台 / 熄屏后
WebSocket 一直连着 —— 息屏之后屏幕是不亮了，但 WiFi 射频与 TCP keepalive 全程在线，
系统还会反复唤醒进程来维持这条连接。

### 为什么不听 `onStop`

`onStop` 在**切到别的 App、分屏、下拉通知栏盖住页面**时也会触发，而那些场景屏幕
还亮着、用户可能马上切回来 —— 那时断流是白白牺牲实时性，回来还得重连。

真正只有**熄屏**（按电源键、合盖、超时黑屏）时用户才看不到内容，保持连接才是纯耗电。
`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` 正好把这两种情形分开。

### 做了什么

1. **`MainActivity` 动态注册熄屏/亮屏广播**：用 `ContextCompat.registerReceiver` +
   `RECEIVER_NOT_EXPORTED`。Android 14（API 34）起对动态注册的接收器强制要求声明
   导出性，不写就直接抛 `SecurityException`；而这两个系统广播只可能由系统本身发出，
   `NOT_EXPORTED` 是正确且最严的选法。`onDestroy` 里反注册。

2. **ViewModel 新增 `pauseEvents()` / `resumeEvents()`**：熄屏**只关事件流**，
   **保留 `active` 连接与令牌** —— 不走 `disconnect()`，因为那会把 `active` 一起清掉，
   亮屏后得重新探活 + 配对校验（还要闪一下「恢复中」），代价远大于收益。

3. **亮屏补三样**，缺一样用户就会觉得「手机卡住了」：
   - 会话列表 + 当前会话历史：复用 `scheduleChatRefresh()`（自带 400ms 静默去抖与单飞）；
   - **审批面板与待答问题**：这两类**不走 history**，不单独刷新就永远不出现。

4. **`eventsPaused` 标志**区分「熄屏暂停」与「用户主动断开」：`disconnect()` 会清掉它。
   否则下次亮屏 `resumeEvents()` 会把用户刚刚主动断开的连接又接回去 ——
   那是最容易让人以为「断开没用」的 bug。

### 为什么审批与待答必须单独补

它们不是会话内容，是**交互状态**，走的是独立端点（`pendingApprovals` / `pendingQuestions`），
由事件帧触发刷新。熄屏期间事件流关着，如果正好有审批在等、或 Agent 提问了，
这两张卡不会自己出现 —— 用户亮屏看到的是一片安静，而电脑端其实在等他的决定。
「我明明没看到有东西问我」是最坏的体验。

这两个方法各自带「会话切换后丢弃结果」的保护（比对 `selectedSessionId`），
所以直接调用是安全的。

### 行为边界（说清楚，免得日后误解）

- **熄屏期间断流 = 收不到新消息推送**。这是用户明确选的取舍（省电优先）。
  亮屏时会一次性补上，不会丢内容 —— 但**熄屏期间不会有通知**。
- 若熄屏期间进程被杀：Activity 重建时走的是冷启动 `restoreConnection()`，
  与广播无关，那条路已经存在且经过验证。
- `active` 为 null（连接真的断了）时 `resumeEvents()` 直接返回，
  用户需要手动重连 —— 不会凭空复活一条已经不存在的连接。

### 测了什么

- `./scripts/build.sh :app:compileDebugKotlin` 通过
- `./scripts/build.sh :app:testDebugUnitTest` 全绿（24 类 / 256 用例）
- **熄屏/亮屏的实际行为需真机确认**：按电源键熄屏 → 看服务端连接是否断开；
  再按亮屏 → 看是否自动接回、熄屏期间落盘的消息是否补上、
  熄屏期间产生的审批/提问卡片是否在亮屏后出现

## 2026-10-13 · v2.2.29：耗电主因修掉，跳转按钮落到会话尾部，两处界面优化

> 用户原话：「会话中的浮动一键到最新按钮，目前是到当页的尾部，应该直接到会话的尾部，
> 另外在看看界面中有优化的方面吗给个建议，在看看整体 APP 的电源管理方面，感觉使用 APP 后设备电池耗电量增加了」。

### ① 耗电主因：流式输出期间每秒拉一次全量历史

`scheduleChatRefresh()` 原来的形状是：

```kotlin
delay(250)
while (chatRefreshPending || chatSessionRefreshPending) {
    ... api.history(id) ...
    delay(1_000)          // ← 这里的 1 秒是轮询周期
}
```

**问题不在 1 秒本身，在于它是个 while 轮询，而它的唤醒条件几乎恒为真**：
事件分发末尾有一句兜底分支

```kotlin
else -> if (sessionId == mutableState.value.selectedSessionId) scheduleChatRefresh()
```

——**什么事件都命中**。每个事件帧都把 `chatRefreshPending` 重新置 true；
而 `chat.message.delta` 是 token 级推送，一段回答几十秒内能来几百帧。
于是 1 秒的循环窗口里几乎总有新帧进来 → 循环一直转 →
**整个流式输出期间每秒一次 `history()` 全量拉取**，几百条消息反复下载。

用户侧体感就是「一用 APP 电量掉得快」。

**改成静默去抖**：`delay(400)` 后只跑一轮，跑完再确认一次「这 400ms 里有没有新帧」，
没有就收工。流式期间事件不断、窗口一直被重置、任务根本不起跑；
等这一轮消息落定、事件安静下来，才做那唯一一次 `history()`。

**实时流式显示不受影响** —— 这一点是动手前查证的：`chat.message.delta`
走的是 `liveChat.append(delta)` 独立那一路（token 级推送，不经 history），
去抖只影响「消息落盘后补一次历史」的时机，不影响字一个一个往外冒。

### ② 浮动「跳到最新」只跳到当页尾部

原来定位用 `displayItems.lastIndex`，而列表里除了消息还有三类固定条目：
顶部「复制手势提示」、底部「本次产生的文件」页脚、`hasMore` 为真时的「加载更早消息」按钮。
少算一项就停在「看着到底了其实还差一截」的地方，用户会以为按钮没生效。

改用 `listState.layoutInfo.totalItemsCount - 1` —— LazyColumn 自己的权威计数，
以后再加任何固定条目都不用回来改这里。

### ③ 连带修：`isAtBottom` 判定同样漏算

它手算的是 `displayItems.size + 2`，而固定条目**随状态增减**（`hasMore` 会变、
复制提示可以被关掉），漏算就会误判「已经到底」→ 自动跟随突然开火，
把正在翻历史的人又拽回底部 —— 那正是本条最初要修的问题。
两处统一改用 `totalItemsCount`。

### ④ `isAtBottom` 改 `derivedStateOf`

旧写法 `LaunchedEffect(listState.firstVisibleItemIndex, visibleItemsInfo.size) + mutableStateOf`
在**每滑一帧都重新赋值**，哪怕值没变（绝大多数帧都没变）重组照样触发。

`derivedStateOf` 只在读到的值**真的变了**才通知下游：滑动过程中绝大多数帧
`isAtBottom` 都是同一个 `false`，Compose 直接跳过重组。滑得越长省得越多。

连带的好处：它不可写，于是按钮点击时**不需要**手动把 `isAtBottom` 改回 true ——
`animateScrollToItem` 滚到底之后它自己就变 true，按钮自动消失。

### ⑤ 浮动按钮半透明 + 滚动中隐去

滑动时那个实心圆盘压在滚过的文字上，读起来像脏点。现在：
alpha 0.88 半透明，且**滚动中（`isScrollInProgress`）隐去、停手再浮现**，140ms 过渡。

用 `animateFloatAsState` 驱动 alpha 而不是 `AnimatedVisibility` ——
这个位置 `BoxScope` / `ColumnScope` 混排，`AnimatedVisibility` 的两个重载
（顶层 vs `ColumnScope` 扩展）会被 Kotlin 的解析规则卡住，报
`'ColumnScope.AnimatedVisibility ... cannot be called in this context with an implicit receiver'`。
自己驱动 alpha 既绕开那个坑，也顺手把出现/隐去平滑化了。

### 未做（等产品决策，不是 bug）

**后台不断连事件流**。`eventSubscription` 只在 `onCleared()` 与主动断开时关闭，
App 切后台时 WebSocket 一直连着 —— 息屏后 WiFi / TCP keepalive 全程在线，
这是省电的第二个大头。但断连意味着**后台收不到通知**，属于「省电优先」还是
「实时优先 + 前台服务」的产品取舍，**没有擅自改**，留给用户定。

### 测了什么

- `./scripts/build.sh :app:compileDebugKotlin` 通过
- `./scripts/build.sh :app:testDebugUnitTest` 全绿（24 类 / 256 用例）
- 手势与视觉（按钮落到真尾部、滚动中隐去、alpha 过渡）**待真机确认**

## 2026-10-13 · v2.2.28：会话里「长按可选中复制片段」是句空头支票

> 用户原话：「App 原生界面中 提示双击文字段可以选择文字复制，实际中，双击不出来选择项」。

### 现象

会话页顶部挂着提示「**长按**可选中复制片段，**双击**复制整条」，但实际长按消息正文
**什么也不弹** —— 没有选择手柄、没有工具栏，一个字都选不了。双击倒是能用（复制整条）。

### 根因：为了让出长按，却忘了有人接

代码里留着一段注释，记着当年那场手势之争：

> 早期版本给整条消息挂 `combinedClickable(onLongClick = …)`，结果长按事件被它吃掉，
> `SelectionContainer` 的选择手柄永远弹不出来 —— 用户原话：「不能选择性复制，只能复制整段」。

于是改法是：**把长按整个让出去**（外层 `combinedClickable` 只留 `onClick` / `onDoubleClick`），
改用双击复制整条。这个判断本身没错，但**只做了一半**：

翻遍 `ui/` 包，`SelectionContainer` 只出现 4 处，全在设置 / 文件详情这类界面；
**会话消息的渲染路径（`ChatItem` → `AssistantBody` → 第三方 `Markdown`）里一处都没有**。

也就是说：双击复制整条实现了，**「选择片段」从来没实现**，而提示文案两头都写了。
让出长按之后没人接手，选择功能就凭空消失了 —— 用户看到的自然是「说了做不到」。

### 修法

`ChatItem` 内部包一层 `SelectionContainer`，把长按接回来：

```
Box(Modifier.combinedClickable(onClick, onDoubleClick))   ← 双击：复制整条
  └ SelectionContainer                                     ← 长按：弹出选择手柄
       └ when (item.kind) { USER / ASSISTANT / FAILURE / … }
```

两个手势各归各的，靠的是**外层不声明 `onLongClick`**：`combinedClickable` 没有
`onLongClick` 时不会消费长按，事件穿透到内层的 `SelectionContainer`。

⚠️ **千万别给外层补 `onLongClick`** —— 那会把长按重新吃掉、选择手柄又弹不出来，
也就是当年翻车的那个原因。这条约束已经写进代码注释，防止下一个人"顺手补全"。

**为什么包在 Box 内层而不是给每个 `Text` 单独包**：助手正文由第三方
`com.mikepenz.markdown.m3` 渲染，里面有几个 `Text`、怎么嵌套由它决定；
在外层包一次就覆盖全部段落、代码块、列表项，不必去改第三方库的内部结构。

### 测了什么

- `./scripts/build.sh :app:compileDebugKotlin` 通过
- `./scripts/build.sh :app:testDebugUnitTest` 全绿
- 手势本身需要在真机上按（长按 → 是否弹手柄；双击 → 是否复制整条），**待真机确认**

## 2026-10-13 · v2.2.27：真机反馈的七个问题一次收口（已发版）

七条问题里六条在客户端、一条在插件。逐条写清**现象 → 根因 → 修法**。

### ① 「加载更早消息」点了没反应（插件侧修复）

**根因在插件**：`dsh-remote-bridge` 的 `history()` 只用 `follow()` 的开场帧取历史，
**把客户端传的 `beforeSeq` 整个忽略** —— 传不传都返回同一窗。客户端把返回的与已有的
一合并、按 `seq` 去重，自然什么都没多出来。

修法：带 `beforeSeq` 时改走内核 `sessionController.page()`，`throughSeq` 取
开场帧的 `cursor`（内核要求该值必须取自对应 follow 开场帧，硬编码会返回 HTTP 200 +
空页这种静默失败）。详见插件侧 `CHANGELOG-DEV.md` 的 v2.3.1 条目。

### ② 会话页返回键/左滑直接退出 APP

**根因**：原生 `ChatDetail` 只把 `onBack` 传给了顶栏返回按钮，**没有注册 `BackHandler`** ——
系统返回键直接穿透到 Activity 默认行为（退出 APP）。WebView 版 `ChatWebScreen` 早就有
`BackHandler`，原生这条一直是漏的。

修法：`ChatScreen.kt` 的 `ChatDetail` 补 `onBack?.let { BackHandler(enabled = true, onBack = it) }`，
与 WebView 版对齐。现在首次返回退到会话列表，再按才退出。

### ③ 从 high 档会话切到不支持推理的模型，切不过去

**根因**：`ModelRow` 点击时传的是

```kotlin
model.reasoning?.defaultEffort ?: current.reasoningEffort
```

目标模型**不支持推理**时 `model.reasoning` 为 null，`?.` 返回 null 于是**回退到当前档位**
（比如 `high`）—— 而目标模型根本不支持 `high`，内核报 `MODEL_UNAVAILABLE`。

修法：去掉那个 `?:` 回退，只传 `model.reasoning?.defaultEffort`（不支持推理时自然为 null，
正是内核想要的「不带推理参数」）。

### ④ 翻历史时页面总被拽到最新

**根因**：`LaunchedEffect(displayItems.size, state.liveChat?.revision)` 无条件滚到末尾 ——
新内容一来就抢走用户正在看的位置。

修法：加 `isAtBottom`（看最后一个可见 item 是否落在总数 -3 以内）。
**只有本来就在底部附近才自动跟随**；不在底部时右下角出现浮动按钮（`FloatingActionButton`
+ `Icons.Outlined.KeyboardArrowDown`）一键回到底部。`animateScrollToItem` 是挂起函数，
按钮回调里用 `scope.launch` 包。

### ⑤ 输入法回车显示「换行」，按下去却发送

**根因**：Composer 的 `onKeyEvent` 判据是

```kotlin
if (!sessionRunning && !alternate) return@onKeyEvent false   // 只有「空闲 + 无修饰键」才放行
```

注释以为「只有物理键盘才派发 `Key.Enter`」，但**中文输入法的软键盘回车也会派发** ——
于是会话运行中按那个画着「换行」的回车，被当成发送。

修法：改成 `if (!alternate) return@onKeyEvent false` —— **普通回车一律换行**，
只有 `Ctrl/Meta + Enter`（物理键盘快捷键）才发送。发送只走软键盘的「发送」action 键
与输入框旁的发送按钮。

### ⑥ 中文界面下启动报错显示英文

**根因**：`messageOf` 只对 `DshApiException`（服务端错误码）做了本地化，
**网络异常直接露 `error.message` 原文** —— `DshConnectionException` 的
`All N endpoint(s) unreachable: ...`、OkHttp 的 `failed to connect to ...` 全是英文。

修法：按**异常类型**映射（子类必须排在父类 `SocketException` / `IOException` 之前）：

| 异常 | 文案 |
| --- | --- |
| `DshConnectionException` | 连不上这台电脑，所有地址都没通（检查电脑是否开机、远程访问是否开启） |
| `SocketTimeoutException` | 连接超时了，请检查网络后重试 |
| `UnknownHostException` | 找不到服务器，请检查地址是否正确、网络是否连通 |
| `ConnectException` | 连接被拒绝了，请确认远程访问已开启 |
| `SSLException` / `SocketException` / `EOFException` | 网络连接中断了，请重试 |
| 其余 `IOException` | 网络请求失败了（附原文兜底） |

中英各加 7 条字符串。

### ⑦ 设置页「当前设备」权限显示成协议串

**根因**：`SettingsScreen` 直接 `Text(scope)` —— `chat.read`、`settings.write` 原样端出来，
中文界面挂一排英文小点。

修法：新增 `scopeLabel()` 按界面语言查表（读取聊天 / 发送消息 / 读取文件 / 写入文件 /
删除文件 / 查看设置 / 修改设置），**认不出的新 scope 保留原文**（与错误码表「认不出不硬猜」
同一原则）。中英各加 7 条。

### ⑧ 附带修复：启动必报 `NetworkOnMainThreadException`

这条是用户真机反馈「网络正常，每次启动都显示」。

**根因**：`connectHostInternal` 里 `connection.client.currentDevice()` 与 `loadSnapshot()`
是同步 HTTP、`hostStore.upsert` 是磁盘 IO，而 `restoreConnection` 从
`viewModelScope.launch`（**主线程**）直接调它。手动连接走 `launchOperation`（IO）所以不炸，
**只有冷启动这条主线程路径必炸**。

修法：在**被调函数内部**用 `withContext(Dispatchers.IO)` 包住这三件事 ——
比逐个约束调用方更稳，重复切换只是无害空转。

### ⑨ 顺手修的既有问题

`ChatPresentationTest` 两处断言还在测 `error.title`（字符串），而代码早已改成
`titleRes`（资源 id）—— 测试编译不过（`git stash` 验证过是既有问题，与本轮改动无关）。
改为断言 `R.string.turn_failed_title` / `R.string.turn_stopped_title`。

### 测了什么

- `./scripts/build.sh assembleRelease` 通过（含 `lintVitalRelease` + `minifyReleaseWithR8`）
- `./scripts/build.sh :app:testDebugUnitTest` 全绿
- 插件侧：`pnpm check` 全绿（30 文件 / 198 用例），并**实测分页**：
  第一页 261 事件（seq 104~364）→ 带 `beforeSeq=104` 返回 104 事件（seq 0~103）

### 出包

`artifacts/DeepSeek-Harness-companion-v2.2.27.apk`，2,869,642 字节（release 构建，
R8 + 资源裁剪），`CN=DSH Pocket Client` 签名，可直接覆盖 v2.2.24~v2.2.26。

## 2026-10-11 · 出错文案跟随界面语言（不再把英文原文端给用户）

> 未出包。`./gradlew :app:compileDebugKotlin` 通过。

### 用户看到的是什么

中文界面下发一次图失败，屏幕上写的是：

```
PATH_OUTSIDE_ROOT: The path is outside every authorized root.
```

—— 错误码 + 英文。`HarnessViewModel.messageOf` 以前就是 `"${error.code}: ${error.message}"`，
而模型失败那条路 `notify(failure.message)` 更是内核原文直出；
连失败行的标题都写死中文（`ChatTurnFailure.title` = 「本轮运行失败」），英文界面下照样弹中文。

### 现在的规矩

**语言是本地的事，服务端不需要知道**。按错误码查本地化文案：

- 新增 `data/ErrorMessages.kt`：`localizedErrorRes(code)`。插件那 74 个错误码**按语义归并成
  33 条**（`PATH_NOT_DIRECTORY` / `PATH_NOT_FILE` / `WORKSPACE_PATH_NOT_DIRECTORY` 都是
  「路径类型不对」），内核码取 `substringAfterLast('/')` 后再比 —— 内核有时给
  `DSH_SESSION/MODEL_UNAVAILABLE` 这种带前缀的形态。
- `values/strings.xml` 与 `values-en/strings.xml` 各加 38 条：33 条错误 + 2 条失败标题
  + 3 条 WebView 提示。
- 接上的口子四处：`messageOf`（HTTP 错误）、`chat.turn.failed` 的全局提示、
  失败行渲染（`TurnFailureRow`：标题走 `titleRes`，正文按码本地化）、
  WebView 聊天页与桥的 `NO_CONNECTION` / `NO_TOKEN`。
- **data 层不再写死任何语言**：`ChatTurnFailure.title` 改成 `@get:StringRes titleRes`，
  登出消息留空由界面按 `ACCOUNT_SIGNED_OUT` 本地化。

两条刻意的取舍：

1. **认不出的错误码保留原文**（英文）。编一句像模像样的话比露原文更糟 —— 用户至少还能搜、
   能贴给开发者；只有 `err_request_generic` 会附上错误码。
2. `ConnectionCoordinator` 的 `All N endpoint(s) unreachable: …` 这类**技术诊断文本不翻译**：
   它是给排查用的细节，翻了反而搜不到。

### 踩的坑

`ChatWebScreen` 里那两句提示在 `LaunchedEffect` 内（**不是** Composable 上下文），
第一版写成 `stringResource(...)`，编译器直接报
`@Composable invocations can only happen from the context of a @Composable function`。
改用作用域里已有的 `context.getString(...)`。

### 验证

`./gradlew :app:compileDebugKotlin`（`JAVA_HOME=…/android-toolchain/jdk17`）`BUILD SUCCESSFUL`。
**仍需出包 + 安装才生效**；服务端无需改动配合。

---

## 2026-10-11 · v2.2.25 纯文本模型发的图，手机端也「看得见」

> **出包**：v2.2.25 / versionCode 10050，正式证书 `CN=DSH Pocket Client`
> （证书 SHA-256 `8b2ba797…`），APK SHA-256 `37a6d9a1a4817587d515e7d85f63781d8318890d0a69e455dfe44dfd89543b8b`。
> **未推送、未打 tag、未发版。真机验证待做。**
> 本仓库没动服务端一个字节，改动全在显示层与取字节那一步。

### 诉求（用户原话）

「看，人类如何看懂，把图片显示出来就好了，给一堆路径」，随后的指路是
「你已接入了官方的图片显示方法，只需要把那个图片的路径读出来」。

上一轮（插件侧的图片交接）解决的是「**机器**能不能读到图」：模型不支持图片输入时，
插件把图落进会话工作区 `.dsh-images/<sha256>.<ext>`，消息里追加两行说明 ——
一行人话、一行给机器读的 `[Image: source: /绝对路径]`。
机器那条路通了，但**人**在手机上只看到一串路径。这一版补的就是人这一半。

### 改了什么（全在客户端）

- `data/ChatPresentation.kt`
  - `ChatAttachment` 新增 `handoffPath`：交接图**不在附件存储里**（那只存会话里的原生
    image 部件），它是工作区里的普通文件 —— 取字节的方法因此完全不同，得在类型上分开。
  - `userItems()`：**只对用户消息**做交接解析。上下文注入的正文（记忆库、README 示例、
    上一轮工具输出）经常就引用着 `.dsh-images/` 路径甚至 `[Image: source: …]` 字面量，
    不设这道闸就会把那些正文切碎、还凭空多出几张读不到的假附件 ——
    这条是拿真机会话跑出来的，不是猜的（见下面「验证」）。
  - 摘说明时**按行摘**而不是整条 part 摘：实测插件把说明放在独立 part，但万一哪天
    与用户正文同处一条，整条摘会把用户自己打的话一起吞掉。摘完正文可能为空，
    判据仍是「有文字**或**有附件」，消息不会消失。
  - 交接附件的 id 用 `handoff:<绝对路径>`（`HANDOFF_ATTACHMENT_PREFIX`）：
    它同时是图片缓存的键、去重的键和「从哪读字节」的唯一依据，不必再引一张 id 表。
  - `contentText` 拆出 `contentTextParts`：交接说明要能整条识别，
    合并成字符串之后再按行切，就是拿分隔符当数据结构。
- `HarnessViewModel.kt`
  - `loadAttachmentImage` 变成**两条来源**：原生附件照旧 `readAttachment(sessionId, id)`；
    交接图走 `readHandoffImage` = 官方那条 `resolvePath` → `readFile(rootId, path)`，
    与文件页预览同一个端点、同一份 `files.read` 授权，**没有为图片另开口子**。
  - 交接图与会话无关，因此不再要求 `selectedSessionId`（原生附件仍然要求）。
  - 路径不在任何授权根内时按失败上报，新增文案 `attachment_fail_outside_roots`（中英各一条）。
- 单测 6 条（`ChatPresentationTest`）：说明被摘成附件、只发图不留空正文、
  同一 part 混排时只删那两行、同一条消息里的重复路径去重、**注入正文里的路径不被动**、
  以及 id 判据 `handoffPathOf`（交接 id 解出路径 / 附件哈希一律 null）。
- **未做**：WebView 页（`chat.html`）的历史消息本来就不渲染任何历史图片附件
  （原生附件也不渲染），这是既有缺口，不在本次范围内。

### 验证

- 单测：**25 个测试类 / 256 用例 / 0 失败**（本版新增 6 条）。
- 编译：`scripts/build.sh :app:testDebugUnitTest` 2m54s · `assembleRelease` 1m19s，
  均 BUILD SUCCESSFUL；签名校验 `CN=DSH Pocket Client` 与既有正式证书一致。
- 拿真实会话离线复跑解析：`session-b595c4ad` / `session-fae8a61d` 里 2 条真机图片消息
  → 正文只剩用户原话、各解析出 1 个工作区路径；同为这些会话里的 3 条 `runtime-context`
  注入消息**保持原样**（不解析、不摘行）。
- **真机验证待做**：装 2.2.25 后，在纯文本模型会话里发一张图 —— 手机上应当直接出现缩略图，
  而不是那行 `[Image: source: …]`。

### 顺带修好一处会让构建「静默挂起」的路径问题

`scripts/build.sh` 原先把 `GRADLE_USER_HOME` 写死成工具链内的 `gradle-home`，
但那份缓存里 `wrapper/dists/gradle-9.5.0-bin/<hash>/` 只剩一个**空目录**（没有发行版），
于是 wrapper 转去 `services.gradle.org` 下载 —— 国内网络下它**不报错、只是静默挂起**
（实测 16 分钟零输出、CPU 0.3%）。改为按序挑「真的有发行版」的那份
（工具链 → `$HOME/.gradle`），本次两条构建随即正常，用的是家目录那份带完整依赖缓存的。

### 为什么没走插件新加的 `/api/v1/image-handoff`

`dsh-remote-bridge` 源码里确实有一条专门的交接图读回端点，但它**没进构建产物、
没同步实装**（`readHandoffImage` 在 `lib/index.js` 里 0 命中）。既然客户端本来就有
「绝对路径 → 授权根 → 读文件」这条官方通路，就先用它：少一个端点、少一份权限面。
那条读回链路**留作备胎**（未构建、不影响运行），等这条路真机验证通过后再决定删还是留。

## 2026-10-11 · v2.2.24 修「历史图片永远显示不出来」+ 模型失败不再傻等

> **出包**：v2.2.24 / versionCode 10049，**正式证书** `CN=DSH Pocket Client`
> （SHA-256 `8b2ba797…`），SHA-256 `9f244eb3…`（图片修复后的第二版）。
> **未推送、未打 tag、未发版。**

### A. 修「历史图片永远显示不出来」

**先说教训：这一条我误判了两次，都是没先看真进程的日志、光从源码推。**

1. 第一次以为附件存储没数据 → 实测 35/35 个 id 全部命中磁盘，错。
2. 第二次以为是「客户端没把四项元数据带上来，被内核完整性校验 `ATTACHMENT_CORRUPT`」→
   从源码看成立，还真改了一版。
3. **真因**（重启后读 `.attachment-diag.log`）：真进程里 **`ctx.attachments` 是 `MISSING`** ——
   这条 profile 没把附件存储挂成可注入服务。vitest 里看到的 `OK` 全是测试桩噪音。

**正路：让内核自己取，插件不碰 `ctx.attachments`。**

内核主机端有 `SessionController.attachment({sessionId, attachmentId})`
（客户端契约里叫 `readAttachment`）。它内部一次做完三件事：校验该会话确实引用了这张图、
读出字节、用**会话事件里那条真实 ref** 做完整性校验，返回 base64。
绕开它自己调 `ctx.attachments`，既拿不到注入属性、又要自己复刻这三件事 ——
完整性校验正是先前 `ATTACHMENT_CORRUPT` 的来源。

核实过的事实（不靠猜）：

| 核实项 | 方法 | 结果 |
|---|---|---|
| 存储里有没有字节 | 从真实会话解出 35 个 attachmentId 逐个查磁盘 | **35/35 命中**，抽一个是 `PNG 768x512` |
| 真进程能不能拿到服务 | 重启后读插件写的 `.attachment-diag.log` | **`ctx.attachments MISSING`** |
| 内核正路 | 读 `dsh-api-session-controller` 源码 | `attachment({sessionId, attachmentId})` → base64 |

**改了什么**：
- `DshClient.readAttachment(sessionId, attachmentId)`：把 sessionId 拼进 query
  （内核要靠它读会话事件、定位真实 ref）；`attachmentId` 里的冒号仍由 `addPathSegment` 编码。
- `HarnessViewModel.loadAttachmentImage`：用当前 `selectedSessionId` 调用；
  不在任何会话时直接返回，避免发必然 400 的请求。幂等/去重/失败可重试那套保留不动。
- **撤掉了上一轮的弯路**：删掉 `HarnessState.referenceOf()` 与四项元数据透传
  （mediaType/bytes/width/height）—— 真实 ref 内核手里本来就有，客户端不用带。
  围绕它写的 `AttachmentReferenceTest.kt` 一并删除。
- 其余（`contentAttachments` 解析四项、消息流里的 `ChatAttachment`）保留 ——
  那些是**展示**用的（尺寸、类型），跟取字节无关。

**插件侧配套**（详见 dsh-remote-bridge 的 CHANGELOG-DEV）：插件改调
`sessionController.attachment()` 并解码 base64；附件端点改为收 query `sessionId`
（缺失即 400），不再收四项元数据。

**✅ 真机验证通过**（2026-10-11 18:51 / 18:57）：装好本版 + 重启 dsh 后，
**历史图片与即时发送的图片都能正常显示**。用户原话「看到历史图片了」，
并附了一张 App 截图确认刚发的图也完整渲染。

插件侧诊断日志的铁证（两条路都成功、0 失败）：

```
sessionController.attachment ✓ 139215 bytes · image/jpeg   ← 历史图
sessionController.attachment ✓ 211964 bytes · image/webp   ← 历史图
sessionController.attachment ✓ 267688 bytes · image/jpeg   ← 即时发送的图
```

### B. 接住 `chat.turn.failed`：模型失败不再让客户端傻等

模型限流（429）、配额用尽、密钥失效这些终局失败此前**完全到不了手机**：内核把失败写在
`turn/end` 的 `reason`，插件整段丢弃，客户端只认 `chat.turn.end` ——
于是「模型早就失败了」与「还在跑」在界面上一模一样。用户原话：
「要不然客户端一直在傻傻的等」「类似的 模型出了问题 ，都要告诉客户端」。

**改了什么**：
- 数据层：新增 `ChatItemKind.FAILURE` + `ChatTurnFailure(message, code, stopped)`；
  实时帧 `chatTurnFailure()` 与历史帧 `turnEndItem()` **两条路都渲染** ——
  失败后用户往往先退出再进会话，实时帧只到一次，历史是唯一能留住它的地方。
- 事件层：`chat.turn.failed` 分支做三件事 ——
  ① `markSessionStopped()` **本地立即**把会话置为不在运行（服务端标记与帧到达之间
  有几百毫秒空档，期间停止按钮还亮着）；② `notify()` 弹出服务端原话；
  ③ 清流式缓冲、拉历史、撤挂起的提问卡。
- 渲染层：原生 `TurnFailureRow`（红标题 + 错误码 + 原话，**不折叠**，长按复制整条
  好把 request id 带去排查）；WebView 页同步做。

**三个刻意取舍**：不翻译 message（本地化按官方 `failureMessage()` 划分）；
`max-tokens` 不算失败（那是「回答被截断」，官方渲染成黄色警示 —— 误报比漏报更伤）；
登出停止单独标 `stopped`、标题用「任务已停止」。429 的 `code` 是**空串**，
客户端按空串保留而非当缺失。

**已真机验证**：用户确认「修改成功」。

### C. 修签名路径 + 重新生成正式密钥

- `app/build.gradle.kts`：原先默认只找仓库**外**的 `../.secrets/...`，而实际配置在
  仓库**内** → 每次出包都得记着传 `-PdshSigningProperties`，否则
  `verifyDedicatedReleaseSigning` 直接拦。现改为三处候选按序取第一个存在的
  （`-P` 参数 → 仓库内 → 仓库外）。
- 原始 `dsh-release.jks`（`CN=DSH Pocket Client`）找回来了，但**密码彻底丢失**
  （本机/仓库/CI/git 历史/记忆库全查过，60 多个候选密码也试过，无一命中）。
  已归档到 `.secrets/deepseek-harness-android/archived-unknown-password/` 保留不删，
  并重新生成新密钥（证书主题与旧的一致、别名 `dshrelease`、RSA 4096 / 30 年、密码 `dshlocal`）。
  ⚠️ **新钥匙盖不上旧钥匙签的 v1.9.0 及更早的包**，这点已写进 signing.properties 注释。

### 验证

编译 0 警告（修掉了 3 个静态自查漏掉的编译错误，见任务 `fix()` 的坑）·
**25 个测试类 / 250 用例 / 0 失败**（失败帧 7 个新用例在内；图片那条的核心断言在
插件侧的桥层 + REST 层测试里，共 4 个用例）·
签名确认为正式证书 · APK 内 chat.html 确认含失败帧逻辑。
**✅ 图片已真机验证通过**（历史图 + 即时发送的图都正常显示）；
出包版本 SHA-256 `9f244eb3…`（versionCode 10049 / 2.2.24）。

## 2026-10-11 · 接住 `chat.turn.failed`：模型失败不再让客户端傻等

> **未出包、未推送**：等用户真机测试通过。改动只做了静态自查（见下「验证」）。
> 用户原话：「更改完不要急于发版，推送，先让我测试通过」。

### 为什么

用户真机报的 bug，附带原样报文：

```
本轮运行失败429: {"code":"","message":"您已达到免费用户的 API 速率限制。升级 Token Plan
即可解锁更高限额，继续不间断使用 API。 (request id: 20261011054136674823028JzjiUYGm)","type":"AgnesAI_error"}
```

并追加了一句要求：**「类似的 模型出了问题 ，都要告诉客户端」**。

### 根因：那句话从来没到过手机

链路三处断点，缺一处就还是"傻等"：

1. 内核把失败写在 `turn/end` 的 `data.reason`（`kind === 'error'` 时详情在 `reason.error`）；
2. 插件 `normalizeDshEvent` 只转 `type`，把 `reason` 整段丢掉；
3. 客户端只认 `chat.turn.end`，历史里 `turn/end` 落进 `else -> emptyList()`。

### 干了什么

**① 插件侧（配套改动，见 dsh-remote-bridge 的 CHANGELOG-DEV）**

`normalizeDshEvent` 为失败多发一帧 `chat.turn.failed`，`data.failure = {message, code, stopped?}`；
正常收尾仍只发 `chat.turn.end`（一个失败一帧，不会收到两个信号）。

**② 客户端数据层（`data/ChatPresentation.kt`）**

- `ChatItemKind` 新增 `FAILURE`；新增 `ChatTurnFailure(message, code, stopped)` ——
  `code` 是 `String?` 而**不是可空 + 空串混用**，因为 429 那个 code 就是空串，
  客户端要原样显示错误码那一格，当成缺失就少一列信息。
- `chatTurnFailure()`：从实时帧解失败。判据是 **type 精确等于** `chat.turn.failed`，
  老插件只发 `chat.turn.end` 时安静返回 null，客户端照旧走原收尾路径。
- `turnEndItem()`：历史里的 `turn/end` 帧 → 失败行。**为什么历史里也要**：失败后用户往往
  先退出再进会话，实时帧只到一次，历史是唯一能把"这轮失败了"长期留住的地方。
- `mergeToolRuns` 只合并 TOOL，FAILURE 自然 `flush(); out += item`，顺序不受影响。

**③ 客户端事件层（`HarnessViewModel.kt`）**

新增 `chat.turn.failed` 分支，做三件事：

1. **立即把会话本地标成不在运行**（`markSessionStopped()` 纯函数）。这一步不能省：
   服务端把会话标成 not-running 与这帧到达之间有几百毫秒，这段空档里"停止"按钮还亮着、
   占位符还写着「运行中…」，用户看着就以为还在跑。本地先置位，等 GET 回来用权威数据再盖一次。
2. 走全局错误通道 `notify()` 让那句话以 snackbar 弹出；
3. `liveChat = null` + 拉历史。流式缓冲必须清 —— 留着半截文字，用户会以为"它还在说，只是慢"。

**④ 原生渲染（`ui/ChatScreen.kt`）**

新增 `TurnFailureRow`：红标题 + 错误码 + 服务端原话，**不折叠**（用户要的就是那句为什么）。
长按复制整条 —— 把 request id 带给电脑端排查是唯一有效的手段。
分发里加 `ChatItemKind.FAILURE -> item.failure?.let { TurnFailureRow(it) }`；
`REASONING` / `CONTEXT` 两个过滤器都不会挡到它。

**⑤ WebView 版（`assets/chat.html`，两条路径都做）**

- 历史：`ev.type === "turn/end"` → `turnFailureFrom(ev)` → `addTurnFailure(f)`；
- 实时：`case "chat.turn.failed"` → `setRunning(false)` + `clearPend()` +
  `addTurnFailure(d.failure)` + `refreshQuestions()` + `refreshHistory()`。
- 新增 `.fail` 样式（红边、不折叠）与 `turnFailureFrom` / `addTurnFailure` 两个函数。

**⑥ 新增两个测试文件**

- `data/ChatPresentationTest.kt` +4 个用例：429 原样报文（含空 code）、登出停止、
  **四种不该报失败的收尾**（`ok` / `completed` / `max-tokens` / 用户取消 / 老帧）、
  实时帧解析、`error` 是字符串的兜底。
- `MarkSessionStoppedTest.kt` +3 个用例：失败的会话停下来、不动其它会话、
  没有可停的就原样返回（同一个实例，Compose 会跳过重组）。

### 三个刻意的取舍

1. **不翻译 message**：服务端那句话本身就是中文。本地化按官方 `failureMessage()` 的划分留给
   客户端（`ACCOUNT_SIGNED_OUT` / `ACCOUNT_SIGN_IN_REQUIRED` / `QUOTA` / `AUTH` 这几个 code
   在客户端换文案，其余透传）。
2. **`max-tokens` 不算失败**：那是"回答被截断，已有输出保留"，官方渲染成黄色警示。
   误报比漏报更伤 —— 用户没失败却看到红框，第一反应是客户端坏了，真失败时他反而不信了。
3. **登出停止单独标 `stopped`**：它被内核与失败同级展示，但不是模型故障，标题用「任务已停止」。

### 验证

- **编译**：`assembleRelease` 通过（R8 + lintVital 全过，**0 编译警告**）；
- **单测**：**26 个测试类 / 249 用例 / 0 失败**，其中本轮新增 7 个用例
  （ChatPresentationTest +4、MarkSessionStoppedTest +3）已确认真实执行；
- **签名**：与 v2.2.23 同一证书（SHA-256 `f0e6c701…` 一致），可覆盖安装；
- **包内容核对**：APK 内 `assets/chat.html` 确认含新逻辑（6 处引用 + 1 个新样式块），
  不是"打包漏掉"；
- **✅ 真机验证通过**（2026-10-11）：用户重启 dsh + 装 v2.2.24 后，
  429 限流被如实显示，**真机确认「不再傻等」**。

### 编译时抓到的 3 个真错误（静态自查漏掉的，都已修）

这一节留着当教训 —— 上一轮我做过括号配平、引用闭环、导入齐全的静态自查，
**三处错误一处都没抓到**。编译器一上手就全出来了：

1. **`ChatScreen.kt` 漏 import `ChatTurnFailure`** —— 连带 `title` / `message` /
   `isNullOrEmpty` 一串全解析不了。（我查过 ChatScreen 的 import 清单，却没查
   **新增类型**是否在清单里。）
2. **`JsonPrimitive.booleanOrNull` 这个扩展不存在** —— 项目自有 `booleanValue()`
   （`contentOrNull?.toBooleanStrictOrNull()`），没先看既有实现就动手了。
3. **`update { state -> state.copy(...) }` 写错了** —— `update` 的 receiver 就是
   `HarnessState`，显式参数名**把 receiver 遮蔽**了，报 `Unresolved reference`。
   正确写法是项目既有的 `update { copy(...) }`（直接用 receiver 属性）。

> 一句话：**没有编译器就不算数。** 静态自查只能抓低级错误，类型与作用域问题一律靠编译。

### 发版前遗留（本轮没动，等发版时处理）

- [ ] **签名配置路径对不上**：`app/build.gradle.kts:11` 默认找
  `../.secrets/deepseek-harness-android/signing.properties`，但实际配置在仓库内
  `.secrets/local-signing/signing.properties`。本轮靠显式传
  `-PdshSigningProperties=...` 才出的包。要不要把默认路径指对，省得下次再踩。
- [ ] **正式发布密钥丢失**：该文件注释写明「dsh-release.jks 连同密码在本机已丢失」，
  当前用的是 `debug.jks`（`CN=DSH Debug`，仅供试装）。发 GitHub Release 前需找回。
- [ ] 按用户规矩，**未推送、未打 tag、未发版**。

## 2026-10-11 · 问答卡片、修 /技能名、图片 PDF 预览、工具调用详情（v2.2.21 → v2.2.23）

> 版本归属：改动分布在 **v2.2.21 / 2.2.22 / 2.2.23**（逐版注释在 `app/build.gradle.kts`，以它为准）。
> **出包版本 2.2.23 / versionCode 10048。**

### 为什么

这一轮全部由**用户真机反馈**驱动，五件事：

1. **`ask_user_question` 的卡片在手机上看不到。** 这是个从来没实现过的功能，不是渲染 bug。
2. **`/技能名` 调不动。** 用户打 `/技能名` 得到 `COMMAND_NOT_FOUND`——官方能用的技能一个都调不动。
3. **图片 / PDF 打不开。** 文件页遇到它们只显示一行「二进制文件」。
4. **工具调用看不到细节。** 只能看到一张静态卡片，查不到输入/输出/耗时。
5. 卡片刷新的实时性。

### 干了什么

**① 问答卡片（原生 + WebView 两套）**

- 原生 `QuestionCard` 落在输入区上方（不顶掉输入框）；WebView 页面复用审批卡的排版。
  两套都做，是因为只做一边就会重演「同一个会话两处显示不一致」。
- 认领插件推的三个专属帧：`chat.userQuestions/requested` → 刷新；`answered` / `dismissed`
  → **就地摘卡**（等下一次 GET 只会让卡片多挂一个往返）。
- `PendingQuestionSet` 新增 `viaBridge`（默认 false，兼容老服务端）：区分「正阻塞着 agent」
  与「已超时、可迟到补交」。
- 刷新走**事件驱动**，不引定时器：`isToolResultFrame()` 按 `data.eventType` 精确挑出工具结果帧
  （一轮里这类帧很多，来一帧拉一次等于自己造轮询）；外加「轮次结束时补拉一次」覆盖 agent
  很快收尾的情况。

**② 修 `/技能名`**

此前只要输入以 `/` 开头就整条送去 `executeCommand`，而技能名不在宿主命令表里。
官方语义是「**先看是不是已注册命令，不是就当普通消息原样发给模型**」（`dsh-client-ui-skill/README.md:28`），
由 Host 在 pre-step 注入技能正文。现按此实现 `isHostCommandLine()`，匹配规则**照抄插件**的
`/^\/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])/u`。两边规则必须一致：放宽了客户端放行而服务端说不认识，
收紧了命令永远发不出去。**刻意不做大小写不敏感**（官方与插件都是精确匹配）。

**③ 图片与 PDF 预览**

- `PreviewKind` 新增 `IMAGE` / `PDF`，**零新依赖**：图片走系统 `BitmapFactory`（两趟解码 +
  2 的幂采样），PDF 走系统 `android.graphics.pdf.PdfRenderer`。
- 上限：图片 24 MiB / 解码后 12 MP；PDF 32 MiB / 100 页。超限给出路而不是白屏。
- `PdfHolder` 是唯一可变持有者，**登记发生在 `open()` 成功那一行**（不等协程回 UI 线程），
  堵住「打开成功 → 登记」之间被取消而漏 fd 的窗口——真机上只表现为「用久了打不开文件」。
  逐页用 `openPage(i).use{}`：Page 不关会让 `renderer.close()` 抛异常。
- 文件页与会话附件**共用**同一个组件与同一套判据。

**④ 工具调用详情**

- `ChatDisplayItem` 新增 `tools: List<ToolCallDetail>`；`displayItems()` 改为**先建 callId→result
  索引再渲染**——旧写法 `events.flatMap(::displayItems)` 单条事件看不出配对，于是 `tool/result`
  整类被 `else -> emptyList()` 丢掉。
- 耗时用两个事件的顶层 `time` 相减；算不出来就**不渲染那一行**，不瞎编。
- 合并成一行之后仍能逐条展开。
- **一个必须记住的坑**：`isError` **不等于**命令失败——实测那条 `curl` 超时、输出全是 stderr 的
  样本 `isError=false`。所以输出块**无条件显示**，「失败」徽标只如实转述内核标记。

### 测了什么

- `:app:testDebugUnitTest`：**24 个测试类 / 242 用例 / 0 失败**（本轮新增 5 个测试类：
  `HostCommandLineTest` / `ToolResultFrameTest` / `RemoveQuestionTest` / `PendingQuestionsTest` /
  `QuestionAnswerTest`，另扩充 `PreviewKindTest`）。
- **问答卡片经真机端到端验证通过**：
  ```
  提问到达: hasAgent=true sessionId=… questions=1 hasWait=false
  已推帧 userQuestions/requested: callId=bridge-3af742ae-…
  chat.question.answer | callId=bridge-3af742ae-… answers=1 questionIds=["target"]
  ```
  截图确认渲染正确（header / question / detail / options[].label 与 description / multiSelect 全到位）。
- **未真机验收**：图片 PDF 预览的翻页与缩放手感、工具详情的展开观感。

### 踩到的坑（值得单独读）

插件仓库 `docs/project/RETRO-question-card.md` 记了一次**代价很大**的排查。要点：卡片代码从头到尾
都是对的，错的是排查方法——我为一个**从未发生过的现象**（我的会话 agent 根本没有
`ask_user_question` 工具）编了四个自洽的根因。

**判据：如果某个环节的日志一条都没有，那不是「执行失败」，是「压根没执行」。**
这两者的排查方向完全相反。

另有一个真实技术坑同在那份文档里：`wait.callId` 判据过严导致 plan 模式被整条挡在门外。

---

## 2026-10-11 · 连接页门闩、偏好入状态、模型页拆分、输入区自适应（v2.2.20）

> 版本归属：下面这些改动分布在 **v2.2.15 → v2.2.20** 之间（逐版注释在 `app/build.gradle.kts`，以它为准）；本文件此前只记到 v1.9.0，这一条把本轮一次性补齐。**出包版本 2.2.20 / versionCode 10045。**

### 为什么

这一轮的问题看着分散，但大半是**同一个病根的三副面孔**：状态没有放在 Compose 能看见的地方。

1. **冷启动直接跳进上次的会话**，用户想换一台电脑得先找到「断开」——连接页成了藏起来的入口。
2. **「进入应用」按钮灰着点不动**（真机截图：已连接 + 按钮禁用 = 死路）。
3. **设置页的开关点了不变化**，得再点别的控件才跳一下。
4. **输入框写死 34dp**，敲到第二行文字就被裁。
5. 设置页三个开关的 padding 各写各的，中间那个上下多出一截。
6. 模型供应商配置全挤在设置页里，一处 697 行的巨型页面。
7. **同一个会话的文件清单，底部与标题栏弹层显示的还不一样。**

### 根因与改法

**① 启动永远先落连接页 —— 进程级门闩 `connectGate`。**
根因：进不进应用早期由连接状态（`state.connected` 之类）推断，而连接状态会被后台请求与自动重连反复改写，落点跟着请求抖动。
改法：`HarnessViewModel.connectGate`（`MutableStateFlow<Boolean>`）做成**进程级**门闩，只由用户的三个主动动作放行（`passConnectGate()`，即连接页上三次 `onEnterApp`）；冷启动自动重连成功**仍然停在连接页**。`ui/App.kt` 只读不写。
要点：**刻意不进 `HarnessState`** —— 它在初始 / `disconnect` / `loadSnapshot` 三处会被整份重建（见 ⑤），门闩放进去会被直接冲掉。

**② 连接页两种方式默认收起、互斥展开（`ConnectionEntryRow`）。**
「导入配置文本」与「手动配对」都改成默认收起的一行入口，点哪行展开哪行、另一行自动收起；两处调用同一 composable（`ui/ConnectScreen.kt`），样式与行为不可能走偏。

**③ 修「进入应用」按钮灰着点不动。**
根因：按钮的启用条件是 `!busy && !connecting` —— 那是给「连接这台电脑」准备的（要并发探活、自动选路，忙时确实该禁）。但「进入应用」是**纯本地跳转**，不碰网络；自动重连成功后只要后台还有一次请求在跑（`loadSnapshot`、会话刷新都算 `busy`），按钮就灰着，用户既进不去、也没有第二个入口。
改法：`ConnectScreen` 里按 `isActive` 分开判 —— 已连上那台只看「有没有地址」（`host.endpoints.isNotEmpty()`），其余仍走原来的 `enabled`。

**④ 修自动重连失败时 `restoring` 永远停在 true。**
根因：`coordinator.connect(host)` 返回 `Result`，失败时是**正常返回**（不抛异常），所以 `restoreConnection` 的 `onFailure` 兜不住；没有别的分支复位 `restoring`，「恢复中」就变成一句永久的谎话。
改法：`HarnessViewModel.connectHostInternal()` 的 `getOrElse` 失败分支里补 `restoring = false`。

**⑤ 修「点开关不变化」+ 状态重建丢偏好。**
根因有两层：
- `useWebChat` / `showContext` 由 UI **直接读 `SharedPreferences`** —— 那不是 Compose 状态，读不建立订阅、写不触发重组；于是「点了不动，再点别的才跳」。
- `HarnessState` 在**初始 / `disconnect` / `loadSnapshot`** 三处被整份重建，全都没带偏好字段 —— `genUiDebug` 从来没被恢复过、`busySendMode` 每次断开都被打回默认。
改法：两个开关进 `HarnessState`，setter 走 `update { copy(...) }` 并落盘，UI 读 `state`（`SettingsScreen` 的 `SwitchRow(checked = state.useWebChat, ...)`）；新增 `HarnessState.withPreferences()` 统一盖上四个偏好（`busySendMode` / `genUiDebug` / `useWebChat` / `showContext`），三处重建一并接上。

**⑥ 输入框高度随内容自适应（44~120dp）。**
根因：写死 34dp，第二行就被裁（真机反馈）。
改法：对齐网页版 `#ta` 的 `min-height:36px / max-height:120px`，`Modifier.heightIn(min = 44.dp, max = 120.dp)`，行内对齐改 `Alignment.Bottom`（网页版是 `align-items:flex-end`）。
要点：`BasicTextField` 的容器用 `fillMaxWidth` 而不是 `fillMaxSize` —— 后者会撑满 `heightIn` 的上限，变成「永远 120dp 高」。

**⑦ 设置页三个开关统一为 `SwitchRow`。**
根因：三处各写各的 `Row` —— 一个 `padding(bottom = 4dp)`、一个 `padding(vertical = 8dp)`，页面上就是「中间那个开关上下多出一截」；其中一处还多带一句没作用的 `Arrangement.SpaceBetween`。
改法：抽 `SwitchRow(title, description, checked, onCheckedChange)`，三处统一。

**⑧ 模型供应商配置拆成独立的「模型」导航页（`ui/ModelsScreen.kt`）。**
从设置页拆出，插在 Plugins 与 Settings 之间；设置页 697 → 297 行（本轮实测 `wc -l` = 299），删净 30 个死 import。模型页**永远可进** —— 与设置页同一取舍：看不到入口就不知道是权限问题、也不知道去哪儿开。

**⑨ 会话文件清单两处统一，改读内核交付物。**
根因：会话底部那块已改成读内核的 `deliverables/presented` 事件（`present` 工具触发，带绝对路径 + agent 写的说明），而标题栏文件夹弹层（`SessionFileList`）还在按 `SessionFileRefs.refs()` 嗅工具参数 —— 底部是 agent 真正交付的报告与安装包，弹层却是一堆嗅出来的路径（真机反馈：「标题栏的文件夹中的不合适」）。
改法：抽公用 `sessionFileList(history)`（`ui/ChatScreen.kt`），两处同一来源；优先交付物、旧嗅参数保留为兜底。

**⑩ 工具显示改官方口径动作短语 + 连续工具调用合并成一行。**
根因：原生原来报英文类别词（Bash / Read / Code…），与官方口径对不上；且一次调用一行，跑几十次就是一屏。
改法：`ChatPresentation.toolActionsFor()` 照页面同源判据拼中文动作短语（`run_code` 扫 `arguments` 里的内层工具）；`mergeToolRuns()` 只合并**相邻** TOOL，中间夹正文即断开（对齐网页版 `toolGroupSummary`）。

**⑪ 历史不再显示「思考过程」；上下文注入默认隐藏。**
历史里的 REASONING 整类过滤（实时那条思考行保留 —— 那是运行状态，不是会话内容）；上下文注入默认隐藏，与设置页的 `showContext` 开关联动。

### 测了什么

- 新增/在用的单测：`data/SessionFileRefsTest.kt`（交付物来源）、`ui/ComposerPillTest.kt`（药丸占位符三态：空闲 / 运行中排队 / 运行中插话）、`ui/ChatThinkBarTest.kt`、`data/ConnectionCoordinatorTest.kt`、`ui/GenUiTableLayoutTest.kt` 等，共 **17 个测试类**。
- 页面侧静态体检：`tools/chat-html-check.mjs`（引用的 id 是否都存在、顶层绑定是否都用 `bind()`、接口是否齐全）。
- **全量用例数与构建结果未在本条核实**（写这条时不跑 gradle，构建由发布流程统一跑）。

### 未做真机验证

- 输入框 44~120dp 的实际长高过程与软键盘联动；
- 连接页两个入口互斥展开的可发现性；
- 「进入应用」在弱网/后台请求密集时是否仍可点。

---

## 2026-10-09 · 长按让位选择、文件按类型预览（v1.9.0）

### 为什么：两个「功能有但用不了」和一个「偷懒」

**其一，长按不能选择性复制。** 用户原话：「复制会话中内容是不能选择性复制，只能复制整段」。

根因是手势竞争：整条消息外层挂了 `combinedClickable(onLongClick = 复制整条)`，
它会**消费**长按事件，内层 `SelectionContainer` 永远收不到，选择手柄弹不出来。
早先加「长按复制整条」本意是给一个便捷入口（用户曾说「会话内容无法复制」），
结果把「选中片段」这条更基本的路堵死了。

**其二，文件预览一律当源码画。** 点开 `.md` 看到的是 `# 标题`、`**粗体**` 的原样字符，
`.html` 更是一片尖括号。这不是设计，是**实现偷懒**：所有文件都走同一条路
（`Text(preview)` + 等宽字体），根本没有按类型分流。网页版会渲染 Markdown，App 不会。

### 干了什么

**手势分工**：长按留给「选中片段」，整条复制改用**双击**。并在消息流顶部放一次性提示
（可关闭、`rememberSaveable` 记住），否则用户会以为功能没了 —— 行为被换掉而不说明，
是最容易让人误判成 bug 的做法。

**预览分流**抽成纯函数 `previewKind(fileName, contentType)`（`data/PreviewKind.kt`），
判据顺序是刻意的：

| 顺序 | 判据 | 理由 |
|---|---|---|
| 1 | **扩展名优先** | 本机 MIME 五花八门（`text/x-markdown`、`text/plain`、空串都见过），拿 MIME 当主判据会得到「有时渲染有时不渲染」的最坏体验 |
| 2 | 退回 `contentType` | 无名文件至少不会被当源码 |
| 3 | 二进制永远不走文本预览 | `application/octet-stream` 配 `.png` 是常态，按 UTF-8 解只会得到替换字符 |

**HTML 用结构化抽取而不是 WebView**（`data/HtmlPreview.kt`）：WebView 会执行页面里的
`<script>`、加载外链、发起网络请求，而这里读的是「会话里出现过的任意文件」——
用 WebView 等于把手机浏览器的一次请求权限交给一份不可信文件。所以只提取结构
（标题/段落/列表/引用/代码/链接），`<script>` 与 `<style>` 的内容**整体丢弃**。

### 测了什么

新增 `PreviewKindTest` 6 条（扩展名优先于 MIME、二进制即便 MIME 说 text 也走二进制、
无扩展名时退回 MIME、既无扩展名也无 MIME 时按源码而非二进制 —— 宁可让用户看到
可复制的原文，也不要一片空白）、`HtmlPreviewTest` 7 条（标题/段落抽取、列表项、
**script 与 style 内容必须丢弃**、链接保留 href、代码块保留原文不剥标记、实体还原、
未闭合标签不吞掉后续内容）。

全量 **169 用例 / 0 失败**（上一版 156 + 13）。中英字符串键 291/291 对齐。

### 未做真机验证

双击手势与滚动手势的冲突边界（滚动中误触双击）需要真机才能判断；
HTML 结构化预览的排版观感同理。

---

## 2026-10-09 · 发送失败不吞字、取图失败说清原因（v1.8.0）

### 为什么：两处「把用户的东西弄丢了」和一处「撒谎的提示」

**其一，发送失败会吞掉正文。** 旧 `sendMessage` 是「先清空附件、再发请求」，
失败时只把**附件**还回输入区，**文字直接丢了**。用户打了一长串字、发送失败、
输入框空了 —— 一个字都找不回来。附件能还而文字不能，本身就不讲道理。

**其二，取图失败一律显示「图片已不可用（可能已被清理）」。** 这句话是写死的，
与真实原因无关。而 401（授权过期）、403（缺 `files.read`）、404（附件真没了）、
网络错误在屏幕上长得一模一样 —— 用户看到「可能已被清理」，只能去清缓存，而缓存不是原因。

更糟的是「失败一次就永久记住」：授权过期重新配对后本可自愈，
却被记死成不可用，除非重启 App。

### 干了什么

- **原文还回**：提交时把正文记在局部变量里，失败时写进 `HarnessState.pendingText`，
  输入框通过 `LaunchedEffect` 回填。刻意**不**在提交时就写进 state —— 那样 UI 会把
  刚提交的正文立刻塞回输入框，用户看着像「发了又没发」，比吞字更糟。
- **失败原因落到界面**：`attachmentFailures` 从 `Set<String>` 改成 `Map<String, String>`
  （id → 人类可读原因），新增 `describeAttachmentFailure()` 按 401/403/404/其他分流；
  发送失败同理走 `describeSendFailure()`，不再把 `BODY_INVALID` 原样甩给用户。
- **可重试与不可重试分开**：授权类失败（401/403）记在独立的可重试集合里，
  重新配对后能自愈；只有 404 那种「重试也没用」的才记死。

### 测了什么

沿用既有 156 条用例（本版未新增单测，改动集中在状态流转与文案分流）；
中英字符串键完全对齐。

### 未做真机验证

失败提示的文案长度在不同机型上的折行表现。

---

## 2026-10-09 · dsh-ui 卡片诊断开关（v1.7.0）

### 为什么：三种失败在屏幕上长得一样

「围栏没被识别」「JSON 坏了」「节点类型不认识」三种故障，用户看到的都是
一段「看着别扭但读得懂」的文本。没有实机可验证时，靠截图猜是哪一种，
等于每次改代码都在掷骰子 —— 而这个环境**没有可用设备**，猜的成本特别高。

### 干了什么

设置页「调试」组加一个开关（默认关）。打开后每条助手消息下方显示一行灰字，
把三段式（**切分 → 解析 → 分发**）每一步的结果变成可读文字：
点一下展开全文，长按可复制。`diagnoseGenUi()` 独立于渲染分支复算一遍 ——
这样「解析器认为成功但渲染仍失败」这种不一致也会暴露出来。

这条工具**立刻就赚回了成本**：它第一次运行就指出真凶不是 App 而是
**我生成的 JSON 不合法**（`Unexpected JSON token at offset 431`），
省掉了一轮盲改。

### 测了什么

`GenUiBlocksTest` 从 16 条加到 21 条，新增 5 条钉诊断的**报告内容**（而不是渲染结果）：
纯文字不误报、有围栏但未识别必须报警、正常卡片报成功并列出节点类型、
JSON 坏了要报出原因、**未闭合围栏属流式中间态不得误报为故障**。

最后一条是被测试逼出来的：初版把「未闭合」也报成 `⚠`，
而流式输出中围栏逐字到达、未闭合是**正常中间态**，
报成警告会让用户盯着一个永远不消失的提示。同时发现围栏计数把闭合行也算成了开头
（于是一段卡片被报成「见到 2 个围栏」，把人往错方向引）。

全量 **156 用例 / 0 失败**。

---

## 2026-10-09 · 会话产生的文件列在消息流末尾（v1.6.0）

### 为什么：功能有，但用户找不到

「会话文件」能力在 v1.3.0 就有了，入口是顶栏一个文件夹图标。
问题不在实现，在**可发现性**：用户得先知道那个图标存在。

而网页版的形态是「会话结束后，文件直接列在对话下方，不必点任何入口」。
用户反馈的正是这个差异：「网页版如果对话中有文件，会在会话结束后，在下面显示产生的文件」。

### 干了什么

消息流末尾自动挂载 `SessionFilesFooter`：显示条数、可折叠、展开后点一条即看内容。
顶栏图标**保留**（大屏下翻历史时仍然好用），两者不冲突。

两个刻意的取舍：

- **新回复还在流式输出时不显示**（`liveChat != null`）——否则文件列表会在生成过程中
  反复增删、闪个不停。等这一轮结束才出现，这才是网页版的行为。
- **只列服务器绝对路径**，不解析 `bash` 命令里的路径。那里面大多是搜索范围、变量、
  或只是被 echo 出来的文本；误报比少列更糟（用户点一个打不开的路径，会以为功能坏了）。

### 测了什么

沿用 151 条用例，新增文案键中英对齐检查。

### 未做真机验证

折叠交互与列表长度的观感。

---

## 2026-10-09 · 会话里的 dsh-ui 交互卡片原生渲染（v1.5.0）

### 为什么：网页版有的东西，手机上退化成一坨裸 JSON

`dsh-ui` 围栏里的 JSON 在网页版会被渲染成真正的组件（卡片、表格、按钮、选项…）。
App 原来把它当普通代码块画 —— 用户看到的是几百个字符的裸 JSON。
最直接的后果：**助手在网页版弹给用户的选择题，手机上完全没法回答。**

### 干了什么

`data/GenUiBlocks.kt`（纯逻辑：切围栏 + 解析 spec + 组件白名单）+
`ui/GenUiCard.kt`（22 种组件的 Compose 渲染）。渲染时文字仍走 `Markdown()`，
只有卡片走原生组件。

围栏识别改用**按标记词匹配**而不是精确前缀：genui skill 自己的文档里就有
```` ```dsh-ui ```` 与 ```` ```json dsh-ui ````两种合法写法，只认前者的后果是
「网页版能弹、手机上不能弹」。判据是「标记里含有 `dsh-ui`」，与顺序无关。

三条退化规则，每条都对应真实故障：

1. **未闭合围栏整段按原文保留** —— 流式输出时围栏逐字到达，切开只能得到半截 JSON，
   卡片会凭空消失；等闭合了再切。
2. **JSON 坏了回落成代码块**，而不是让整条消息消失 —— 用户宁可看到原始内容，也不要空白。
3. **未知组件类型只丢那一个节点**，不牵连整份 spec。

卡片里的选择按钮等价于用户在输入框打了一行字再发送，走同一条 `sendMessage` 通道。

### 测了什么

新增 `GenUiBlocksTest` 16 条，钉的全是**退化行为**（未闭合保留原文、
坏 JSON 回落、未知类型丢弃、两种合法围栏写法都认、同一条消息里多个卡片都切出来）。

全量 **151 用例 / 0 失败**。

### 未做真机验证

22 种组件的排版观感、按钮点击热区。

---

## 2026-10-09 · 会话图片可见 + 消息可复制 + 链接可点 + 发图片/发文件（v1.4.0）

### 为什么：四项「用不了」，其中一项是两个根因叠加

**会话里的图片看不见**，真因是两个问题叠在一起：

1. `ChatPresentation.userItems()` 用 `contentText()` 只取文本并 `takeIf(String::isNotBlank)`，
   Web 端「只发图不打字」的消息被当**空消息整条丢弃** —— 连空气泡都没有。
2. 历史消息里的图片不是 base64，而是 `{type:'image', attachment:{attachmentId:'sha256:…'}}`
   **引用**，字节存在服务端附件存储里。客户端只有 id，要另发一次请求才拿得到。

**会话内容无法复制**、**点链接无响应**、**缺发图/发文件按钮**，都是入口或能力缺失。

### 干了什么

- 消息非空判据改为 `body.isNotBlank() || attachments.isNotEmpty()` —— 只发图不打字是正常用法。
- 新增 `GET /api/v1/attachments/:id` 的客户端调用；图片按 `attachmentId` 缓存，
  **失败也记住**（`attachmentFailures`），否则每次刷新都会把历史图片重下一遍。
  渲染分三态：下载中占位 / 失败原因 / 成功显示。
- `data/ChatContentParts.kt`：把「文本 + 待发附件」组装成 messages 请求体。两条硬规则 ——
  **只有纯文本才走旧格式** `{"text":...}`（向后兼容老插件），带附件才用 `{"content":[...]}`；
  编码用 `encodeDefaults = true`，否则 `mediaType = "image/png"` 这种等于默认值的字段
  会被静默丢掉，服务端收到一个没有 mediaType 的 image part。
- `data/ChatLinkTarget.kt`：链接分流，不可打开的 scheme 给出明确提示而不是静默失败。
- 附件体积上限前置拦截（图 8 MiB、文件 24 MiB），给「先压缩再试」这种能照做的提示，
  而不是等服务端 413。

### 测了什么

新增 `ChatContentPartsTest` 11 条（旧格式与 content 数组的分界、空内容拒绝、
图片上限、base64 口径、`encodeDefaults` 不能丢 mediaType）、`ChatLinkTargetTest` 9 条；
`ChatPresentationTest` 加 3 条（只发图片的消息不丢、空消息仍丢、`type:file` 不当图片处理）。

全量 **135 用例 / 0 失败**（上一版 112 + 23）。

### 未做真机验证

图片在长列表里的加载与内存表现。

---

## 2026-10-08 · 运行时发送行为可配置（排队 / 插话）

### 为什么：需求描述的「行为不一致」其实是「行为不存在」

用户提出：「智能体运行时 Enter 键和发送按钮的行为；Cmd/Ctrl+Enter 使用另一行为；排队发送、插话发送，搞一个设置项」。

核实后发现**比需求描述的更严重**——旧 Composer 里：

```kotlin
IconButton(onClick = onSteer, enabled = ... && !state.busy && ...)   // 插话：运行时禁用
Surface(onClick = onSend,  enabled = ... && !state.busy && ...) {    // 发送：运行时禁用
    if (state.busy) CircularProgressIndicator(...)                    // 且变成转圈
}
```

两个按钮都被 `!state.busy` 禁掉、发送键还变成转圈，**所以智能体运行时根本发不出消息**。
而 `state.busy` 是「**任何**操作在跑」（连一次后台刷新都会置位），
并非「这个会话的 agent 在运行」。Enter 键则从未被处理过（`BasicTextField` 没有
`keyboardOptions` / `onKeyEvent`，纯粹换行）。

所以问题不是「默认行为选哪个」，而是**排队与插话两条路都还不存在**。

### 干了什么

**判据修正**：引入 `selectedSessionRunning()`，用**会话的 `running`** 而不是全局 `busy`
去决定「有没有可插话的轮次」。这一条同时修掉了「后台刷新期间发不出消息」的毛病。

**决策逻辑抽成 `ComposerSendPolicy`（纯函数，可单测）**，而不是埋在 1900 行的 Compose 里：

| 场景 | 触发 | 结果 |
|---|---|---|
| 空闲 | Enter / 发送键 | `queue`（等价普通发送） |
| 空闲 | Ctrl/Cmd+Enter | 仍是 `queue` —— 没有可插话的轮次，不制造歧义 |
| 运行中 | Enter / 发送键 | **按设置**：`queue` 或 `steer` |
| 运行中 | Ctrl/Cmd+Enter | **相反**的那个（`opposite()` 是对合） |
| 运行中 | 侧边插话键 | 固定 `steer`，不受设置影响 |

最后一条是有意的：**设置项管的是「默认」，插话键管的是「我要插话，就现在」**。
如果它也跟随设置，那用户打开这个开关就等于关掉了另一个入口。

**可发现性**：运行中在发送键旁显示 `回车=排队` / `回车=插话` —— 否则用户只能靠试。

**空闲时 Enter 保持换行**：多行输入是正常需求，只有运行中才把软键盘回车变成发送
（`ImeAction.Send`）。物理键盘的 Ctrl/Cmd+Enter 由按键事件处理，两条路都覆盖。

**设置项**放在设置页而不是聊天页：它影响的是长期输入习惯，不是一次性的会话开关。

**防重复提交**改为依赖「提交后立即清空输入框」而不是禁用按钮 —— 后者正是导致
「运行中发不出消息」的原因。

### 测了什么

`ComposerSendPolicyTest` 6 条：空闲恒 `queue`（即使设置为 `steer`）、空闲按 Ctrl+Enter
也不产生 `steer`、运行中遵循设置、运行中反向键翻转、`opposite()` 是对合、
`canSubmit` 同时要求非空内容与写权限。

全量 **112 用例 / 0 失败**（上一版 106 + 6）；编译无警告；中英字符串键完全对齐。

### 未做真机验证

- 手机软键盘的「发送」键依赖 `ImeAction.Send`，不同输入法对 IME action 的处理不完全一致；
- 物理 Ctrl/Cmd+Enter 依赖按键事件，需要外接键盘或桌面模式才能验证。

两条都需要在真机上确认，本环境没有可用设备。

---

## 2026-10-08 · 会话中的文件可以直接打开（配合服务端 `FS-003`）

### 为什么：先纠正一个我自己给错的结论

用户反馈第 4 条：**「会话中的文件无法查看」** —— agent 在会话里说改了某个文件，用户在手机上看得到
那句话却点不开，只能回电脑前。

我最初判断这一条**不需要改服务端**，理由是可以「把绝对路径与授权根做最长前缀匹配」。
**这个判断是错的**，三条实测把它推翻了：

| 实测项 | 结果 |
|---|---|
| `/api/v1/roots` 是否返回根的绝对路径 | **不返回**，只有 `{ id, label, createdAt }` |
| 会话的 `cwd` 字段是否可用 | **71/71 全为空字符串** |
| 绝对路径能否直传 `roots/:id/content` | **400**（只接受相对路径；`../` 逃逸同样 400） |

客户端的处境因此是：手里有「一个绝对路径」和「一个 rootId」，**中间那一环它永远拿不到**。
必须在服务端补一次转换（见 `dsh-workspace` 的 `FS-003`：`GET /api/v1/roots/resolve`，
**只回 rootId + 相对路径，仍不泄露根的绝对路径**）。

### 干了什么

- `data/Models.kt` 新增 `ResolvedPath`（全字段带默认值）。
- `data/DshClient.kt` 新增 `resolvePath()`。
- 新增 `data/SessionFileRefs.kt`：从会话事件里提取文件引用。
  - 采集来源按可靠度排序：`data.meta.diffs[].path`（结构化字段）→ 工具 `arguments` 里的 `file_path`。
  - **只认绝对路径**：相对路径无法可靠解析到根，一律忽略。
  - **`bash` / `pwsh` / `run_code` 的参数一律不解析**（连其中出现的 `file_path` 键也不看）：
    命令里出现的路径多半是 grep 模式、变量或文本内容，解析会大量误报。
    误报比没有功能更糟 —— 它会让用户学会忽略这个面板。这一条有测试钉住。
- `ui/ChatScreen.kt`：会话头部加「本会话的文件」入口（`ModalBottomSheet`），列出文件名、
  完整绝对路径与出现次数；点击后解析并只读预览。**消息气泡的渲染一行未动**（1600+ 行的区域，
  风险与收益不匹配）。

### 一个真实的错误分流问题（联调时才暴露）

服务端插件版本过旧时，这个端点**根本不存在**，返回 `404 ROUTE_NOT_FOUND`。
而最初的契约写作「404/400 一律返回 null」，于是客户端把「端点不存在」与
**「路径不在授权根内」渲染成同一句提示** —— 用户看到的是「这个路径不在授权根内」，
于是他会去反复检查自己的路径，**而真正该做的是升级插件**。

修正为**按错误码分流**：只有 `PATH_OUTSIDE_ROOTS` / `PATH_NOT_ABSOLUTE` / `PATH_NOT_FILE` /
`PATH_NOT_FOUND` 才算「打不开」（返回 null），其余 404 一律抛出，界面据此提示
「服务端插件版本过旧，请升级并重启」。

> 这是一类值得记住的缺陷：**把两种语义不同的失败折叠成同一句话**。它不会让程序崩溃，
> 但会把用户引向错误的排查方向，比直接报错更难发现。

### 测了什么

| 测试类 | 用例 | 结果 |
|---|---|---|
| `SessionFileRefsTest`（新增） | 14 | 全绿 |
| `DshClientTest`（新增 2 条：错误码分流 + 正常解析） | 16 | 全绿 |
| 其余（连接层、插件页等） | 76 | 全绿 |
| **合计** | **106** | **全绿** |

覆盖：`meta.diffs` 提取、`read`/`write`/`edit` 的 `file_path` 提取、**`bash` 不解析**、
相对路径被忽略、去重与计数、空事件、畸形 JSON 不崩溃、
`PATH_OUTSIDE_ROOTS` → null 而 `ROUTE_NOT_FOUND` → 抛出。中英字符串 250/250 键完全对齐。

### 联调前提（重要）

**必须先升级服务端插件并重启 DSH**，否则手机上点任何文件都会提示「插件版本过旧」。
源码与产物都已就绪（`dsh-workspace` 的 `FS-003`），差的只是安装那一步。

---

## 2026-10-08 · 插件清单页（配合服务端 `PLUGIN-001`）

### 为什么

用户反馈第 2 条：**「功能单调点，无法查看装的插件情况」**。

这条**无法纯靠客户端解决** —— 核实过 `dsh-workspace` 的对外 API 面：`/api/v1` 原有 10 个端点
（`healthz` / `pairings/exchange` / `devices/self` / `roots` / `trash` / `chat/sessions` /
`chat/workspaces` / `chat/agent-presets` / `settings/models` / `settings/providers`）外加 WS `events`，
**没有任何一个与插件相关**；loopback 管理面 `/manage/status` 也只回 `remote` / `roots` / `devices`。

因此先在插件侧加了 `GET /api/v1/settings/plugins`（见 `dsh-workspace` 的 `PLUGIN-001`），
本仓库负责把它显示出来。

### 干了什么

- `data/Models.kt` 新增 `PluginEntry` / `PluginInventory`。
- `data/DshClient.kt` 新增 `pluginInventory()`。
- `HarnessViewModel` 新增 `pluginInventory` 状态、`refreshPluginInventory()`，并在 `loadSnapshot()`
  里随顶栏刷新/重连一起带上。
- 新增 `ui/PluginsScreen.kt`：显示 profile 名、已加载数、问题数，逐条列出名称/实际版本/状态徽标；
  `problemCount > 0` 时给出提示条。
- `ui/App.kt` 新增「插件」页签，由 `settings.read` scope 门控。
- 中英字符串各 +23 条。

### 两处契约缺口（值得记录，因为都是「文档比实现少写了」的类型）

服务端返回的形态里有两点与最初的任务描述不一致，**若照描述写就会在真实设备上出问题**：

1. **`state` 有四种取值，不是三种。** 除 `loaded` / `installed-not-loaded` / `declared-missing`
   外还有 **`runtime-provided`** —— 官方 `@deepseek-ai/` 包随 DSH 运行时安装，不会出现在 profile 的
   `node_modules` 下，这是**正常状态**。按三种写不会崩溃，但会让一台健康机器冒出一排「状态未知」，
   而假警报比没有信息更糟。
2. **`profile` / `profilePath` 在服务端是 `string | null`，且判定不出 profile 时会显式发 `null`。**
   若把它们声明成非空的 `String = ""`，JSON 里的 `null` **不会退回默认值，而是让整个响应解码失败**
   —— 这比 [AgentPreset.trust] 那次的「缺键」更隐蔽，因为缺键会走默认值、显式 null 不会。

两条都已按**服务端实测 payload** 兜住，并补了专门的解码回归测试
（`DshClientTest.pluginInventoryDecodesRuntimeProvidedStateAndNullProfile` 与
`...AcceptsExplicitNullProfileAndUnavailableReason`），断言四种状态、显式 null、
以及「只有 name 的条目」都能解出。

> 教训沿用上一节：**契约要用真实响应核对，不能只对着文档写**。这次的来源是「任务书比实现落后了一版」
> —— 服务端加了第四种状态却没有同步给客户端。下游按旧契约写出的不是崩溃，而是更容易被忽略的假警报。

### 测了什么

| 测试类 | 用例 | 结果 |
|---|---|---|
| `DshClientTest`（新增 2 条解码回归） | 14 | 全绿 |
| 其余（含上一节的连接层测试） | 76 | 全绿 |
| **合计** | **90** | **全绿** |

---

## 2026-10-08 · 连接层重构：从「单地址」到「一台电脑多个地址」

### 基于什么

- 上游 [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app) `v1.0.0`（commit `b0bf711`）。
- 许可证 **AGPL-3.0**。上游版权声明、`NOTICE`、`THIRD_PARTY_LICENSES` 全部原样保留。
- 起点已包含上游修复的 DSH 0.2.x 枚举契约问题（见下方条目）。

### 为什么

来自真实使用反馈的四条问题，追查后发现**根因是同一个**：旧模型把「一台电脑」和「一个地址」
焊成了同一个东西。

```kotlin
// 改动前
data class StoredConnection(val endpoint: String, val token: String)
//                                    ^^^^^^^^ 单个字符串，装不下第二个地址
class DshClient(endpoint: String, private val token: String? = null)
```

| 反馈原文 | 追查到的根因 |
|---|---|
| 「只能添加一条路径」 | `endpoint` 只有一个位置 |
| 「我出去了，配对码谁给我？」 | 配对码必须在电脑上生成，而人已经不在电脑旁 |
| 「我出去有可能是局域网，也有可能虚拟网，或者 Tailscale/ZeroTier」 | 同上：只有一个地址位 |
| 「会话中的文件无法查看」 | 会话事件里的绝对路径没有与授权根做匹配（**本项尚未实现**） |

第 2 条推翻了上游的一个隐含前提：上游流程默认「人和电脑在一起」，而真实场景相反 ——
**人已出门，电脑在家，屏幕看不到**。此时「去电脑上生成配对码」不是难用，是做不到。

### 干了什么

**新增连接层模型**（`data/ConnectionModels.kt`）：

```
Host          一台电脑：id / 名字 / 地址列表 / 首选地址 / 凭据 / 时间戳
 ├─ Endpoint     一个地址：id / 标签 / baseUrl / 类型 / 启用 / 探活延迟 / 最后错误
 └─ Credential   凭据：挂在电脑上而非地址上
```

- `EndpointKind` 按地址推断类型；`100.64.0.0/10` 归为虚拟网（Tailscale 与 BeyondTunnel 都用这个 CGNAT 网段）。
- `EndpointSelection.rank` 定死选路规则：延迟优先，但**局域网近似即优先**
  （局域网地址延迟不超过最快地址的 1.5 倍则排前）—— 同网段直连更稳、不烧虚拟网流量。
- **不需要服务端任何修改**：依据是 DSH 的 `devices` 表结构
  `id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at` **没有地址列**，
  设备令牌天然是主机级的，同一令牌在任何地址上都有效。

**新增多主机持久化**（`data/HostStore.kt` + `data/AndroidConnectionStorage.kt`）：

- 整个 `List<Host>` 序列化后**整体加密**（Android Keystore AES-GCM，`IV || 密文`单串）再落盘，明文绝不落盘。
- 解密/解析失败返回空列表而**不抛异常**（否则一条坏数据把用户锁进启动崩溃循环），原值保留 + 另存副本。
- 旧数据迁移：用**旧的 Keystore 别名**解密旧令牌（旧格式 IV 与密文分开存，需手工重组）转成新 `Host`，幂等。

**新增并发探活选路**（`data/ConnectionCoordinator.kt`）：

- 并发探测全部地址，每个地址**独立计时、独立超时**（默认 1500ms），总耗时接近单个超时而非累加。
- 全部失败抛 `DshConnectionException(probes)`，携带逐地址失败原因供诊断面板展示。
- **必须记录的坑**：`DshClient.health()` 是同步阻塞的 OkHttp 调用，**超时无法靠
  `withTimeoutOrNull` 实现**（超时到点后仍要等这次调用返回，最坏等到 OkHttp 自带的 45 秒读超时）——
  看着有超时，实际没用。现实现把调用交给自有 scope、只对 `Deferred.await()` 设 deadline，
  于是 timeout 由协调层自己拥有，不依赖外部注入的 HTTP 超时。
- **`close()` 的完整语义**：拒绝新的探活、并立即取消在途探活的**等待层**
  （注意：只 `probeScope.cancel()` 不够，详见下方「一处值得记录的错误判断」）。
  仍做不到的是让已经发出的阻塞 HTTP 调用提前结束、释放 IO 线程 —— 根治需要 OkHttp `callTimeout`。

**新增配置导入**（`data/ConnectionShare.kt` + `tools/emit-config.mjs`）：

- 线格式 `DSH1:` + UTF-8 JSON 的 Base64 **URL-safe 无填充**；窄格式只写
  `displayName` / `endpoints[]{label,baseUrl}` / `token` / `deviceName` / `scopes`，
  不写本机状态字段（每个地址省约 40 字符）。
- 解码容错覆盖：内部空白换行、零宽字符、BOM、带/不带前缀、大小写前缀、
  URL-safe 与标准两种字母表、缺失 `=` 填充、结尾粘上的标点。
- **一个反直觉的失败模式**：早期实现只认行首 `DSH1:`，而真实粘贴常带标签
  （`Pixel 9 的配置：DSH1:xxx`）。标签里的 Latin 字母与数字**本身就在 Base64 字母表内**，
  所以「过滤非字母表字符」这条兜底完全无效，会被当正文吞进去冲掉整段。
  正解是用 `DSH1:` 哨兵在整段里定位。
- encode 对非法配置抛异常（生成端在电脑上，能立刻改）；decode 对任何非法输入返回 null 绝不抛
  （此刻用户人在外面，崩溃比明确报错更糟）。
- **安全边界**：文本不加密，它本身就是凭据载体（内含设备令牌），等价于一把钥匙。
  安全性由「发给谁」决定，界面与 KDoc 都明确提示只发给自己。

**界面**：`ui/ConnectScreen.kt` 重写（新增「已保存的电脑」列表含每地址类型与探活结果、
「从电脑导入配置」主路径、地址诊断面板；手动配对降为可折叠次要入口并标注前提）；
`ui/SettingsScreen.kt` 显示当前电脑名与全部地址并标出**此刻实际使用**的那个；
`ui/App.kt` 增加 `CONFIG_INVALID` 本地化；字符串资源中英各新增 27 条。

**行为语义**：`disconnect()` **不再删除凭据**（上游把断开与删令牌绑成一个动作，
导致网络抖动或误触就要重新配对，而重新配对又要求人在电脑旁）；
启动恢复失败**不再清除已保存的主机**（上游 `store.clear()` 把「此刻网络不通」误判成「凭据失效」）；
启动时自动连接**最近活动过**的那台电脑。

### 测了什么

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `HostStoreTest` | 26 | 全绿 |
| `ConnectionShareTest` | 23 | 全绿 |
| `ConnectionCoordinatorTest` | 14 | 全绿 |
| `DshClientTest`（上游原有） | 12 | 全绿 |
| `ChatPresentationTest`（上游原有） | 3 | 全绿 |

覆盖：密文落盘（断言磁盘不含明文令牌）、两类损坏数据容错、迁移幂等性、
并发探活全部成功/部分失败/全部失败、局域网近似即优先、单地址超时不拖累整批、取消异常透传、
粘贴容错 23 种敌意输入、编码长度预算回归护栏。

**一处值得记录的错误判断（我错了，同事用实测纠正）**：
`ConnectionCoordinatorTest.closeCancelsInFlightProbeAndRejectsNewOnes` 最初失败（5.0s，断言「在途探活应随
close() 被取消，实际 null」）。我当时的判断是「这在协程取消模型下物理上做不到」，并要求放宽断言。
**这个判断是错的。** 打不断的只是那次 `Call.execute()`，而**调用方的等待是一个普通 suspend 点，可取消**。
正确修法是监听 close 信号后**显式 cancel 掉正在等待的那一层**。修好后该用例 5.021s → 0.016s，断言原样保留。

过程中暴露的两个协程语义坑（会反复被踩，记录在此）：

1. **只 `probeScope.cancel()` 不够**：在途 `probeAll` 不会提前返回（Job 停在 Cancelling），
   而且最后会产出一条**假失败探活**（`error = timeout after Nms`）喂给诊断与落盘 ——
   地址其实没失败，是协调器被关了。这个副作用比「慢」更严重。
2. **子协程抛出的 `CancellationException` 不会上传父协程**：它会被 `JobSupport.childCancelled`
   当作「该子协程正常取消」处理；而父协程正挂在 `call.await()` 上，没有人叫醒它。
   所以「监听关闭信号后就 throw CE」这种写法仍然会等满超时 —— 必须显式 cancel 等待层。

保留下来、如实写进 KDoc 的限制：已发出的阻塞 HTTP 调用仍会占用 IO 线程直到 OkHttp 自身读超时；
根治需要给 `DshClient` 加 OkHttp `callTimeout`（见「已知未做」）。

### 构建链路修复（这三个问题会让任何人在本机构建失败）

1. **上游 `gradlew` 在 git 中被记录为 `100644`（没有执行位）** —— `git ls-files -s gradlew`
   可复现。clone 之后直接运行 `./gradlew` 会得到 `Permission denied`；而它又被管道里的
   `| tail` 掩盖成 `exit 0`，非常有欺骗性（看起来「构建成功」，其实 Gradle 从未启动）。
   本仓库修正为 `100755`。
2. **Gradle wrapper 静默挂起**：`distributionUrl` 指向不可达的 `services.gradle.org`；
   本地缓存的 Gradle 属于另一个 URL（腾讯云镜像），哈希不匹配 → wrapper 认为未下载 →
   尝试下载 → 网络不通 → **不报错、不退出、十几分钟无任何输出**。
   实测该目录下有两个哈希目录，其中一个只有 20MB 的 `.part` 残骸。
3. 依赖镜像脚本与 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 冲突 → 构建直接失败。
   改为项目自带的 `scripts/init-mirrors.gradle`（只重写 settings 级仓库）。

### 已知未做

- **会话中文件查看**（第 4 条）未实现。方向：会话事件的绝对路径
  （`events[N].event.data.meta.diffs[0].path` 与 `meta.path`）与授权根做最长前缀匹配，
  转相对路径后经 `GET /roots/:id/content?path=...` 读取。
- **查看已安装插件**（第 2 条）需服务端加接口：`dsh-workspace` 目前只有 10 个接口
  （`healthz` / `devices/self` / `roots` / `trash` / `chat/sessions` / `chat/workspaces` /
  `chat/agent-presets` / `settings/models` / `settings/providers` / `pairings/exchange`），
  **没有任何插件相关接口**，因此无法纯靠客户端解决。
- **`DshClient` 的 OkHttp `callTimeout`**：加上后阻塞调用才真正可被超时中断，
  `ConnectionCoordinator` 也能退回更简单的实现。
- 文件区重构（按根分组 / 最近 / 收藏 / 搜索）。

---

## 2026-10-08 · 兼容 DSH 0.2.x 的字符串枚举契约（下游分支）

- **背景**：上游 `Hakunm/dsh-android-app` v1.0.0 使用 kotlinx.serialization 严格解码。
  `AgentPreset.trust` 为无默认值的必填字段；而 DSH 0.2.x 的 `AgentPreset` / `AgentPresetRow`
  已移除 system/user 二分，**服务端不再返回该字段**。客户端因此对整个列表报错：
  `Field 'trust' is required for type with serial name '...AgentPreset', but it was missing at path: $.items[0]`，
  表现为 Agent 预设选择器完全打不开。
- **做法**：给 `AgentPreset.trust` 加默认值 `"system"`，并同时给 `isDefault` / `available`
  补上安全默认值。这样**服务端缺少任一可选字段时，一个字段的缺失不会再拖垮整个列表**。
  同批在服务端（`dsh-workspace` 的兼容层）也补了该字段，两端互为保险。
- **为什么改客户端而不是只改服务端**：严格解码在遇到未知/缺失字段时是「全有或全无」的。
  客户端不应假定某个具体内核版本一定返回某个可选字段；给可选字段默认值是更稳的防线。
- **构建环境说明**：本次构建在无外网代理的环境下完成，构建时临时使用了国内镜像
  （`settings.gradle.kts` 指向阿里云与 `dl.google.com`，Gradle wrapper 指向腾讯镜像）。
  **这些改动未包含在本分支中**——本分支相对上游只保留与修复直接相关的文件，
  以便差异最小、便于审阅。
- **验证**：
  - `./gradlew assembleRelease` 构建成功，产物 `app-release.apk` 2,561,512 字节，
    与上游 v1.0.0 产物同尺寸（R8 优化后恰好一致）。
  - 逐 dex 比对确认改动**确实编译入包**：`classes.dex` 由 3,709,572 字节变为 3,709,696 字节
    （+124，即新增默认值产生的字节码），`AgentPreset` 符号在包内存在。
  - APK 已用自签密钥签名并验签通过（`CN=DSH Pocket Client`，
    SHA-256 `4e7fa1db395c4a2436e7a96fcdf6c606e20a14bad55dfcd60c80892f4f92deb3`）。
  - 装机实测：连接 DSH 0.2.x + `dsh-workspace` 兼容层后，Agent 预设列表可正常打开，
    会话模型、历史记录、流式输出、命令菜单均正常。
- 产物 `DeepSeek-Harness-compat02-release.apk`（SHA-256 `4df87f47…`）随分支提供；
  签名密钥 `dsh-release.jks` **不进入仓库**。

## 2026-08-15 · v1.0.0

- 完成 `DOC-002` 与 `REL-004`：README 扩展为 6 张用户提供的真实手机截图，新增文件浏览与新建工作区界面，删除中英文截图来源说明；GitHub topics 加入 `dsh-plugin`，公开分支和标签继续保持单一根提交。
- 完成 `BRAND-002` 与 `LICENSE-001`：App 用户可见简称统一为 `DSH`，项目自身许可证改为 `AGPL-3.0-only`；上游鲸鱼图标与 Markdown 渲染器继续按各自许可证保留告知。
- 完成 `DOC-001`：README 改用用户在真实手机上提供的导航、聊天、文本编辑和模型供应商截图；连接地址已打码，模拟器截图不进入正式仓库。
- Oracle 演示服务器已清除发布前的会话历史和截图过程产生的临时会话，只保留一个本次发布创建的“你好”演示会话；DSH WebUI 与远程 listener 均恢复正常。
- 完成 `REL-003`：v1.0.0 正式签名 APK、README、截图和许可证统一后，以单一根提交重写公开分支与标签，并替换 Release 产物。
- 完成 `WORKSPACE-001`：会话列表页新增 DSH 工作区管理入口，可重命名和移除登记，删除确认明确保留服务器目录、文件和会话日志。
- 完成 `SESSION-001`：每个会话子项增加重命名、分叉和归档；分叉后自动进入子会话，归档后刷新默认列表并保留日志。
- 正式项目名调整为 `dsh-android-app`，版本升级为 `1.0.0` / versionCode `10000`，设置页展示运行版本；新增 GitHub Android CI 和中英双语用户 README。
- `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过；正式 APK 为 2,561,512 字节，SHA-256 `823344D6CEAFF9FE030F625335191D52ABE97FB6D02AFD2B9555B12680E4A266`，v2 签名和既有独立 4096 位 RSA 证书验证通过。
- 完成 `REL-002`：在保留用户手动删改的基础上使用 Humanizer-zh 重写中文 README，拆出完整 `README.en.md`，把安装、连接和核心操作前置，并加入会话、会话菜单与工作区管理三张真实 App 截图。
- 截图不包含设备令牌、API 密钥或服务器连接地址；包含连接 IP 的抽屉采样未进入仓库。
- 创建 `Hakunm/dsh-android-app` 公开仓库并推送 `main`，设置 `android`、`deepseek-harness`、`jetpack-compose`、`material3`、`vibe-coding` topics。
- 发布 GitHub `v1.0.0` Release，上传正式签名 `DeepSeek-Harness-v1.0.0.apk` 与 `SHA256SUMS.txt`。
- 完成 `CI-001`：Release 签名校验从 Gradle 配置阶段移到 Release 打包任务前。公开 CI 可在不持有正式私钥的情况下验证 Debug；`packageRelease` 与 `bundleRelease` 仍依赖专用签名检查，修复后的 hosted run 已成功。

## 2026-08-15

- 完成 `TODO-001`、`COMMAND-001` 和 `PERMISSION-001`：聊天页展示 DSH TODO 完成进度与任务状态，输入 `/` 时提供当前会话 host 命令补全并走受控 BFF 执行，输入区增加只读/工作区写入/完整访问权限选择器；完整访问必须二次确认。
- 完成 `BRAND-001`：设置页的“关于”分区以中英文展示 `Github@Hakunm`。
- 正式签名 Release 已覆盖安装到 Android 15 尺寸设备，并连接更新后的 Oracle listener 完成权限选择器和 `/` 命令候选视觉验收；APK v2 与既有独立证书保持一致。
- Oracle 服务器已载入审批版插件并重启 DSH；App 下一次收到审批请求即可通过稳定 REST/WS 展示并决定，不再依赖旧插件。旧运行随用户授权的重启终止。

## 2026-08-14

- 完成 `APPROVAL-001`：App 通过插件稳定 BFF 恢复待审批状态，会话列表改为“等待审批”，审批面板接管输入区并显示脱敏原因/命令；提供“拒绝/允许一次”，`danger-full-access` 必须勾选风险确认，不提供永久允许。WebSocket 审批变化会刷新 REST 状态，决定过程使用显式 UI 状态而非通用 busy。
- 记录 `APPROVAL-001`：通过 App 发起的真实 Skill 安装停在 DSH `approval/asked`，证明当前移动端缺少审批交互会将等待授权误呈现为持续运行；服务器与命令均未异常，恢复需在 WebUI 允许一次或拒绝。
- 完成 `STREAM-001`、`AGENT-001` 与 `IME-001`：App 直接呈现 token 级文本/思考增量并在完成后以历史正文收口；会话配置加入仅空白会话可用的 Agent 切换；聊天输入区移除与窗口 `adjustResize` 重复的 IME padding，修复键盘上方整块空白。
- 用户实机确认完全移除聊天 IME inset 会让键盘覆盖输入框，`IME-001` 因此重新打开：父级 `Scaffold` 现在消费已应用的系统 inset，聊天列只应用剩余 IME inset，避免“完全不抬升”和“重复抬升”两个极端；最终状态等待用户实际键盘复验。
- 使用 Android 15 发布版连接真实 Oracle listener 完成流式视觉采样：连续帧中的助手正文从 `1...34` 增长到 `1...120`，最终 REST 历史正常接管且无 429/崩溃；已开始会话的 Agent 面板同步完成锁定状态验收。
- 扩展 `APP-004`：文字源码与 Markdown 预览新增共用的 75%–250% 缩放状态，支持缩小、百分比显示、重置、放大和双指捏合；字体与行高同步缩放，行号沟槽随字号调整，单指滚动、编辑和 ETag 保存路径保持不变。

- 扩展 `APP-005`：新建会话增加“已有工作区/新建工作区”模式；新建模式从授权根浏览工作目录，经插件登记 DSH 工作区后直接创建会话，仍不会把文件根误当作 WebUI 工作区。
- 扩展 `MODEL-001`：现有供应商编辑显示 DSH 有效配置、凭据引用和继承模型；新增完全自定义供应商表单，协议选项由服务器 schema 提供。
- 扩展 `MD-001`：文字文件编辑加入同步行号，Markdown 文件支持源码/渲染预览切换。
- 完成 `UI-004`：聊天 Markdown 正文与列表统一到紧凑正文层级，缩小消息留白、用户气泡、宽屏会话栏和输入正文区，保留关键按钮的触控热区。
- Android 15 最终复验发现新建工作区长表单会把创建按钮挤出屏幕；现已收紧段间距与目录列表高度，使根目录、目录选择、Agent 和底部创建按钮在同一手机视口内完整可操作，并重新完成 lint、Release、签名和覆盖安装。

- 创建独立 Android 工程并锁定应用名、包名、SDK、Compose/Material 3 和双语基线。
- 从 DSH WebUI 上游 favicon 提取黑色鲸鱼路径作为 adaptive/monochrome launcher icon，并保留 MIT 许可。
- 在仓库外生成独立 4096 位 RSA PKCS12 签名，未使用 Android debug 测试签名。
- 建立持久化项目追踪与 DSH WebUI 功能对等矩阵。
- 完成配对/恢复、Android Keystore 令牌保护、scope 感知和 HTTP/HTTPS 客户端；WebSocket 使用封顶 30 秒的指数退避重连。
- 完成自适应会话与文件界面：创建/历史/发送/steer/取消，以及 ETag 编辑、新建、移动、上传下载、替换、软删除和恢复。
- 添加 OkHttp 合约单测并通过 lint、R8 Release 构建、APK 包名与独立证书签名核验；真机端到端仍待执行。
- 对本机真实插件 listener 完成临时设备/根回归，验证一次性配对、scope、ETag 文件更新、移动、软删除/恢复和会话授权过滤，并在结束后撤销测试授权。
- 文件上传下载改为 Okio 流式传输，补充目录分页、操作菜单替换文件的 ETag 获取、纯 CR 换行保留和 NUL 二进制识别；Compose 默认中文首屏测试 APK 编译通过。
- 在 Android 15 上运行发布版并通过真实插件完成健康检查、一次性配对、设备/scope/根加载、应用重启恢复和主导航验收；临时设备与根已撤销清理。
- 真机配对发现 WebSocket 请求提前改写 `ws://` 导致 OkHttp 拒绝，现改为传入 HTTP(S) 握手 URL 让 OkHttp 执行 Upgrade，并新增第 6 项回归单测。
- Compose 仪器测试首次因真实配对状态残留而无法进入连接首屏；清理测试前置状态后重跑通过，测试文档明确要求干净安装。
- 完成 `APP-005`：聊天历史增加强类型事件 presentation 层，隐藏 `step/end`、`turn/end`、token 数组等内部原始 JSON，只保留正文、思考、上下文注入与工具摘要；新建会话改为读取并选择 DSH 工作区和 Agent Preset，不再把文件授权根当作工作区，也无需手填路径或模式 ID。
- Android 15 发布版完成 `APP-005` 视觉验收：空工作区、临时非空 DSH 工作区、默认 Agent 和四种模式列表均按真实 listener 数据渲染；测试工作区与设备随后清理，重新生成并核验正式签名 APK。
- 完成 `UI-001`：安装并采用 UI/UX Pro Max 的 Compose 设计建议，重构浅色/深色主题、应用栏、自适应导航、连接表单、会话列表与消息、文件浏览与编辑操作、设置分区及弹窗；所有业务 ViewModel、网络协议、scope 判断和文件回调保持不变。Android 15 的 1080×1920 实际截图复核通过。
- 完成 `UI-002`：分析用户提供的参考 APKS 及实际运行层级，将内容优先留白、移动端抽屉、宽屏固定侧栏、行式列表、底部面板与克制过渡应用到 DSH 自有品牌界面；没有复制第三方商标、资源或字体，并持久化设计系统。
- 完成 `MODEL-001`：设置页新增供应商配置、凭据状态、只写密钥和模型发现；聊天页新增真实可路由模型与思考强度底部面板，均由 `settings.read/settings.write` 和 session/root 授权保护。
- 完成 `MD-001`：集成 `multiplatform-markdown-renderer-m3` 0.41.0，助手正文及展开思考支持标题、列表、引用、代码和表格；补充 NOTICE 与 Apache-2.0 全文。
- 完成 `RATE-001`：聊天增量事件改为合并刷新，会话列表只在生命周期或本地主动动作后刷新，429 静默退避；所有错误从阻塞对话框改为 Snackbar。真实发送与 24 秒观察无 429 或崩溃。
- ARM64 Oracle 上的插件 tarball 已更新并重载；真实 API、Android 15 模型/供应商/Markdown/消息发送、10 项单测、lint、R8 Release、正式签名和最终覆盖安装均通过。
- 完成 `UI-003`：将全局标题、正文、会话行、消息留白、用户气泡和聊天输入容器收紧；为 Markdown 增加独立紧凑 H1-H6 映射，避免聊天正文标题使用库的超大默认 display 样式。Android 15 实际会话截图确认内容密度提升，44/48dp 关键触控区域不变。
