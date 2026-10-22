# 开发日志

本文件记录本仓库的改动。每条都写清**基于什么、为什么、干了什么、测了什么**。
上半部分是本仓库新增的开发记录，下半部分是上游 `Hakunm/dsh-android-app` 的历史（原样保留）。

## 未发布 · 正文里的 Markdown 图片终于能看了（2026-10-22）

### 基于什么

真机反馈：「图片这个不能让 APP 显示出来吗」。上一版把空消息、过程组修干净之后，
正文中间还剩一块**莫名的空白** —— 那里本该是图。

### 根因

助手（和模型）在正文里贴图用的是标准 Markdown 图片语法 `![alt](/abs/path.png)`，
目标是**服务器上的绝对路径**。渲染层不认这种图，又不属于交接说明那套专用标记，
于是整段被丢掉，屏幕上只剩一块空白：既没有图，也没有"这里本来有张图"的提示。

### 干了什么

复用**已经验证过**的交接图通路，而不是另写一套图片加载器：

- `data/ChatHandoff.kt` 新增 `MARKDOWN_IMAGE_PATTERN` / `markdownImageTarget()` /
  `markdownImagePathsIn()` / `stripMarkdownImages()`。目标里**允许空格**
  （本项目截图路径就长这样：`…/报告/截图 c1.png`），可选 `"title"` 与 `<…>` 会先剥掉。
- `data/ChatHistoryMapping.kt`：助手正文与用户原话里的本地图片 → 转成
  `handoff:<绝对路径>` 附件（走「绝对路径 → 授权根」解析取字节），正文里摘掉；
  只贴图不写字的消息**照样成条**。**上下文注入**的正文一律不动 —— 那里全是路径示例，
  解析会把正文切碎并凭空造出一堆读不到的附件（交接说明踩过一次，不再踩）。
- 非本地图片：`http(s)` 降级成普通链接（App 不做远程图片加载，不留白洞），
  相对路径保留 alt 文字。摘图后的连续空行压平，正文不出现大段留白。

### 测了什么

- `:app:testDebugUnitTest` **621 例**全绿，新增 3 例：正文里的图片变成附件（路径含空格）、
  只贴图不写字的消息不消失、远程图片降级成链接。README 双站与 AGENTS §5 的数字同步为 621。
- `docsCheck` 绿。

## 未发布 · 空助手消息归入过程组：一堆「耗时 · N 次工具调用」的根治（2026-10-22）

### 基于什么

真机反馈（用户原话）：「对话窗口也存在 工具加载过多，不展开折叠的情况下，当前页面
只显示 耗时 xxx x次工具调用」。

### 根因（用真机同一会话的 API 数出来的）

`GET chat/sessions/{id}/messages` 统计该会话：`assistant/message` **46 条**，
其中 **45 条 content 只有 `reasoning` + `tool-call`、`text` 一个字都没有**
（类型计数 `{reasoning: 46, tool-call: 45, text: 1}`）。

这类消息照样映射成 `ASSISTANT` 展示项、`body` 是空串。旧的 `isProcessItem()`
把 `ASSISTANT` **一律**当「消息」，于是每两条过程项之间就插进一条零高度的空消息，
`flushRun()` 被反复触发：

> 组头 →（空消息，看不见）→ 组头 →（空消息）→ 组头 …

屏幕上就成了一整屏「耗时 8 分 33 秒 · 42 次工具调用」，用户以为正文丢了。
**不是折叠坏了，是折叠被劈碎了。**

### 干了什么

- `data/ChatDisplayGroups.kt`：`isProcessItem()` 对 `ASSISTANT` 改判
  「**有没有读得出来的东西**」—— 新增 `hasVisibleBody()`（`body.isNotBlank() || attachments.isNotEmpty()`）；
  空的折进过程组，有正文（含 dsh-ui 围栏）或带图片才算消息。文件头判定表与新增
  「空助手消息」一节同步记录真机原话、46/45 实测与机制。
- `ChatDisplayGroupsTest`：新增 3 例 —— 空助手消息合入同组（且工具计数仍为 2）、
  纯空白同理、**只有图片没有文字仍算消息**（不让「只发图」的消息被吞）。
  夹具 `item()` 补 `body` 参数并默认给正文，避免所有"消息类"夹具悄悄变成过程项。

### 测了什么

- `:app:testDebugUnitTest` **617 例**全绿（67 类）；README 双站与 AGENTS §5 的数字同步为 617。
- `docsCheck` 绿；`assembleDebug` 成功。
- 真机：同一会话进入后，一屏内不再是一排组头，页面上只剩真正的正文与一个合并后的过程组。

## 未发布 · 「加载更早消息」视口锚定与加载态（2026-10-22）

### 基于什么

真机反馈（用户原话）：「点击 加载更早消息 如果历史消息中有过多的 工具调用，导致历史对话消息
加载不出来，历史工具调用加载了，但是观看感知差了」。

### 根因

不是没加载，而是**加载完视口跳走**。`LazyColumn` 的滚动位置按 index 记账，往列表**前面**
插入 N 条之后，同一个 index 现在指向更早的内容 —— 于是整屏被顶走，用户落地在一排折叠的
过程组（「耗时 … · N 次工具调用」）上，正文在屏幕之外。会话里工具调用越密，错位越明显，
看起来就像「只加载了工具调用，对话没加载出来」。

### 干了什么

- 新增 `ui/chat/OlderMessagesButton.kt`：点击时记下当前第一条可见项的 index / 像素偏移 /
  渲染条目数；数据变长后把 index 加回增量再 `scrollToItem` —— 视线原地不动，新增内容出现在
  上方（Telegram / Twitter 载入更早内容的行为）。没拉到新内容（到底 / 失败）则不动。
- 同一处补**加载态**：加载期间按钮显示「正在加载更早消息…」并禁用，连点不会重复发请求。
  用随会话重置的局部标志而不是全局 `state.busy` —— 后者会让文案在别处操作时乱闪。
- `ChatDetail.kt` 从 837 行回到 799 行（800 行预算内）：把这套 state 抽到上面的新文件，
  `ArchitectureGuardTest` 的行数预算断言重新变绿。

### 测了什么

- `:app:testDebugUnitTest` 614 例全绿。
- `docsCheck` 绿（新文件已登记进 STRUCTURE.md 机检锚点）。
- 真机：在工具调用密集的历史会话上点「加载更早消息」，视口不跳、按钮随内容上移、可继续往上读。

## 未发布 · 通用设置命名空间：schema 驱动的插件设置表单（2026-10-22）

### 基于什么

用户要求：App「设置」里要能改**各插件自己的设置**（ui-chat 的工作步骤展示、ui-theme 的字号等），
且**不得为某个插件硬编码页面** —— 新装插件不改 App 代码。对端契约由 `dsh-remote-bridge` 并行实现：
`GET settings/namespaces` 返回「命名空间 + JSON Schema + 当前值 + 用户覆盖 + 可写位」，
`PATCH settings/namespaces/{ns}` 按 `{"op":"set","path":[…],"value":…}` + `expectedRevision` 局部写入。
桥与 App **分批发版**，所以老桥（无此路由）与新 App 的共存是必须处理的第一类场景。

### 干了什么

- **数据层（新增）** `data/SettingsNamespaces.kt`：`SettingsNamespaceList` / `SettingsNamespace`
  （`schema`/`value`/`user` 用 `JsonObject`/`JsonElement` 承接，**不强类型化**，命名空间是动态的）、
  `SettingsSecret`（`secrets[].path` 是密钥位的权威信号）、`SettingsOp` / `SettingsNamespaceUpdate`。
  纯函数 `SettingsSchema.fields()` 把 schema 映射成控件描述（`SettingsControl`：Enum / Boolean / Number /
  Text / Secret / ReadOnly）：`enum` → 枚举选择、`boolean` → 开关、`number|integer` → 数字输入（min/max 夹取）、
  其余（object/array/无 type）→ **只读折叠 JSON**，避免半吊子写入；`properties` 缺失时用 `value` 的键兜底成只读行。
  另有 `humanizeFieldName()`（`transcriptView` → `Transcript view`）与 `clampSettingsNumber()`。
- **网络层** `data/DshClient.kt`：`settingsNamespaces()` 与 `updateSettingsNamespace(ns, ops, expectedRevision)`
  （PATCH，路径段经 `pathUrl` 编码），沿用既有 `get`/`patch` 辅助。
- **错误文案** `data/ErrorMessages.kt` 新增 `SETTINGS_NAMESPACE_READONLY` / `SETTINGS_OPS_INVALID` /
  `SETTINGS_UNAVAILABLE` 三条映射（`SETTINGS_CONFLICT` 已有，改为**就地重拉 + 提示已刷新**）。
- **状态与 VM**：`HarnessState.settingsNamespaces`；新增 `HarnessNamespacesViewModel.kt`
  （加载 404/403 静默、写入按 code 分支、冲突重拉）。快照加载里单独 `runCatching`，
  **新端点失败不拖垮整条快照**（这是老桥共存的关键）。
- **UI（新增）** `ui/SettingsNamespacesScreen.kt`：设置页「工作区设置（来自内核）」入口 → bottom sheet
  列表 → 命名空间详情表单。`writable=false` 的命名空间可看不可编辑并显示原因；
  密钥位（声明在 `secrets` 或值为 `***`）只读显示「已设置/未设置」、**永不提交**；
  标题优先 schema `title`，其次已知项文案表，最后字段名人类化。
- **设置页接入** `ui/SettingsScreen.kt`：入口只在拿到非空命名空间时出现，放在「繁忙时的发送行为」之后、
  Web 对话之前；无 `settings.read` 时置灰并说明。
- **字符串**：中英各 50 条 `settings_ns_*` / `err_settings_*`，Compose 里零硬编码中文。
- **测试**：`SettingsSchemaTest`（17 例）覆盖 enum / boolean / number / string / 缺 schema 退化 /
  密钥位 / 保序 / 夹取 / 人类化。`ArchitectureGuardTest` 的 800 行预算触发后，
  把 `loadSettingsNamespaces` 从 `HarnessViewModel.kt` 迁到新 VM 文件（814 → 791 行）。

### 测了什么

- `scripts/build.sh :app:testDebugUnitTest`：**67 类 / 614 用例 / 0 失败**（新增 21 例，
  其中 3 例专测「只显示在 App 里起作用的命名空间」）。
- `scripts/build.sh docsCheck`：目录锚点（+4 行）与接口锚点（+10 行）双向一致。
- `scripts/build.sh`：`assembleDebug` 通过。
- 真机验收由用户完成；老桥降级路径由 404/403 分支 + 快照内 `runCatching` 共同保证。

### 后续修正：只显示「在 App 里真起作用」的命名空间（同日）

真机第一轮发现两件事，都不是渲染错误而是**取舍错误**：

1. 内核对设置发的 schema 是 cordis 的 **schemastery 引用图**，不是 JSON Schema ——
   只认 JSON Schema 的解析器把每个字段都退化成只读行（字号那栏孤零零显示「14」，
   点不动、保存永远灰）。修法：`SettingsSchema.propertiesOf()` 先取 `properties`，
   取不到再调 `Schemastery.expand()` 摊平引用图（`union`→`enum`、`meta.min/max`→`minimum/maximum`、
   `meta.role == "secret"` → `x-secret`）；摊不出来**必须**回退只读，不许猜类型。
2. 用户原话：「对无法设置的内核没必要显示出来，其实如果不能在 APP 上起到作用的都没必要显示。」

4. **真机第二轮：保存键点得动，但存不进去。** 点「保存」后表单停在脏状态、界面无提示；
   curl 复现是 `403 SCOPE_REQUIRED`——桥要求 `settings.write`，而配对默认那五档
   （`chat.read/chat.write/files.read/files.write/settings.read`）里**没有**它。
   修法：入口按 scope 二段门控（`List<String>.canWriteSettingsNamespaces()`）——
   有读没写就明说「需要 settings.write 权限才能在这里修改（在电脑端『已配对设备』里勾选）」，
   不再画一排点完没反应的控件。判据只认 `devices/self` 的 `scopes`，不按桥版本号猜。
   于是新增纯函数 `List<SettingsNamespace>.forNativeApp()`：命中原生 App **不消费**的纯 WebUI
   外观键（`ui-theme` / `ui-chat` / `ui-conversation` / `ui-settings` / `ui-settings-general`）整段隐藏，
   另外「没有任何可编辑字段」的命名空间也隐藏（看得到、改不了等于噪音）。
   入口计数、列表、详情查找三处走同一份过滤结果，避免「计数 22 / 列表 17 / 点进去空」的错位。

### 测了什么（修正）

- `SettingsSchemaTest` 新增 3 例：ui-* 全部过滤而 `llm-*` 保留、纯只读命名空间被过滤、
  只有密钥位的命名空间**算可改**（密钥可以重新输入覆盖）。共 612 用例 / 0 失败。
- `docsCheck` 仍绿；`PITFALLS.md` 新增两条坑位（schemastery、照抄 WebUI 命名空间）并登记进 AGENTS §7。


## v2.2.51（versionCode 10076）· 配色主题四套 + 平板零风险项 + bridge 2.5.1 安全模型同步（2026-10-21）

### 基于什么

用户在 2026-10-21 提出两件事：①「安全方面的全部修改……先改安卓版本」——bridge 侧已升
2.5.1（管理面判据从「来源是否回环」升级为四层，隧道模式下管理面整体禁用）；本 App 只消费
`/api/v1`、全仓不碰 `/manage`，所以管理面收紧对本 App **零影响**，但新增的四个错误码需要文案。
②「安卓的界面方面你结合建议调整，并在设置中增加几个主题，看累了可以自己调整」——主题是
本轮唯一的新增外观能力，其余只落地「不改手机视觉」的项。

前情：`docs/界面优化方案` 一轮调研给出 14 项（O-01~O-14）与 7 条待批（A-1~A-7）；
本轮只做零风险的那几项，动手机端视觉的一律不做（A-1 药丸形、A-4 警告色等留待用户拍板）。

### 干了什么

- **配色主题（新增）**：`ui/theme/ThemePalettes.kt` 承载四套主题 × 明暗两份色板 ——
  纸感 PAPER（`#F9F9F7`/`#131313`，**逐字段等于改造前配色**）、暖米 WARM（`#FAF6EF`/`#1F1B16`，降蓝光）、
  青灰 MIST（`#F5F7F6`/`#16201E`，冷感）、高对比 INK（`#FFFFFF`/`#0A0A0A`，强光）。
  `AppTheme` 进 `AppPreferences`（**存 name 不存 ordinal**，沿用 `interfaceStyle` 的既有理由：
  枚举顺序若调整，ordinal 会把老用户的「深色」读成「浅色」）。与「界面风格」正交，实时生效。
  设置页外观区新增四档 SegmentedButton；默认 PAPER，不切换则看不到任何变化。
- **O-02 断点收敛**：`ui/AdaptiveBreakpoints.kt` 把 `App.kt` 的 `840.dp` 魔数换成具名常量 +
  四档纯函数（Compact/Medium/Large/ExtraLarge）。与旧判据**二进制等价**，手机端永远走原分支。
  **刻意没有引入 `material3-adaptive` 新依赖**（体积与构建风险），WindowSizeClass 留作后续。
- **O-06 contentType**：`ChatDetail.kt` 的 `items(displayEntries, key = …)` 补 `contentType`
  （Message / ProcessGroup 两分支），混排列表不再错复用合成槽位；`FilesScreen.kt` 文件/目录同样补。
- **O-08 状态语义色**：连接状态绿/红从硬编码改为主题成对色，深色底对比度 3.47/3.16 → **9.90/7.79**
  （达 WCAG AA 小字号）；浅色值未变。
- **O-10 触摸区**：`ChatAttachments.kt` 附件删除键 30dp → 48dp（图标仍 15dp，布局多 18dp）。
- **O-03 核实为现状**：Settings/Models/Plugins 三页自 `e2ba3dd` 起已有
  `horizontalAlignment = CenterHorizontally`，不是本次引入，未再改。
- **字符串**：`theme_paper` / `theme_warm` / `theme_mist` / `theme_ink` 中英同步。
- **纪律**：`AGENTS.md` §5 新增铁律「桥的安全模型变了客户端必须跟着过一遍」，含四项对照表。

### 测了什么

- `scripts/build.sh :app:testDebugUnitTest`：**66 类 / 588 用例 / 0 失败**。
  新增 `AppThemeTest`（8 例，含 **66 个 ARGB 逐字段零漂移断言** + 各主题 WCAG 对比度断言）、
  `PaneModeTest`（4 例，16 个宽度点与旧 `maxWidth >= 840.dp` 逐点比对）、
  `AppearancePreferencesTest` +1。
- `scripts/build.sh docsCheck`：10 文档 / 任务状态 / README 双站 / 目录与接口锚点**双向**一致
  （STRUCTURE 132 锚点覆盖 113 源文件；INTERFACES 43 锚点）。
- `scripts/build.sh :app:lintDebug`：**0 error** / 68 warning / 4 hint。
- **真机（V2080A）**：走 `android-ui-drive` 的 `ui.sh install`（自动处理厂商确认页），
  `lastUpdateTime` 21:18:51 → 23:17:03 确认包真的换了。切暖米整页变暖、✓ 跟随、无需重启；
  切回纸感与切换前**内容区逐像素差异 0**（2,419,200 像素）；深色语义绿实测 `#7FD48A`、旧值 0 像素。

### 未做

- 平板 wide 视图未在真平板复验（O-03 本就是现状）。
- 动手机端视觉的 A-1（SegmentedButton 药丸形 vs MASTER ≤8dp）、A-4（浅色警告色提升）等仍需用户拍板。
- 旧的 `.diag-shots/main-screen.png` 基线不能逐像素比对（抓取时界面内容是「地址诊断 timeout」，
  现在是「已连接」），故改用**同内容状态往返对比**作为更强证据。

## v2.2.50（versionCode 10075）· 真机验证收口 v2.2.49 三处瑕疵（2026-10-21）

### 基于什么

v2.2.49 发布后在 vivo V2080A 上按 `android-ui-drive` 的规矩逐屏点击验证（坐标只取
`uiautomator dump`、每次点击后复验），bridge 已升级到 2.5.0，目录通道首次真正跑通
（目录下拉列出 amazon-bedrock / anthropic / google / groq 等休眠条目，选中 groq 后
`defaultBaseURL` 预填为 `https://api.groq.com/openai/v1`、协议预选 `openai-completions`，
适配器自动问出 7 个模型）。顺带撞见三处只在真机上才看得见的瑕疵。

### 干了什么

- 目录通道的状态行与校验文案：新建目录条目且模型目录为空时，原来沿用编辑态的
  「已自定义模型目录」，还会在目录通道里显示自定义通道的专有报错
  「自定义模型 API 至少需要一个模型。」。改为按 `rows.isEmpty()` 走官方文案
  「该提供商没有列出任何模型，请手动添加。」，目录通道的空目录报错换成
  「至少需要添加一个模型。」（自定义通道保留原专有文案）。新增两条中英字符串
  `models_empty_hint` / `models_needs_models`。
- 候选面板跨供应商残留：先选目录供应商 A 自动「获取可用模型」，再切到「自定义模型 API」
  或改选供应商 B，A 的「已发现 N 个模型」面板仍挂在表单里。`AddProviderSheet` 里加
  `LaunchedEffect(mode)` 与 `LaunchedEffect(picked?.id)` 先 `clearDiscoveredModels()`
  再发现，切换即清空。

### 测了什么

- `./scripts/build.sh assembleDebug` 通过，装真机（`lastUpdateTime` 变化确认真换包）后复验：
  目录通道空目录显示「该提供商没有列出任何模型，请手动添加。」+「至少需要添加一个模型。」；
  目录选 groq → 自动发现 →（等发现返回后）切自定义 tab，页面上不再出现「已发现」面板。
- 四项门禁：`docsCheck` + `:app:testDebugUnitTest` + `:app:lintDebug` + `assembleRelease` 全绿。

## v2.2.49（versionCode 10074）· 设置「模型 / 插件」对照官方 Web 对齐（2026-10-21）

### 基于什么

用户点名：APP 设置里的「模型」「插件」要与官方 Web 界面对齐。官方前端
（`@deepseek-ai/dsh-client-ui-settings-models` / `-settings-plugin-inventory`，`lib/client.js` 是可读源码）
的模型页有：目录/自定义双通道添加、协议下拉、凭据状态分档提示、结构化模型目录行、
「恢复默认模型」、删除供应商、打开卡片时捕获 revision 防并发（409 `SETTINGS_CONFLICT` →
「请关闭后重新打开，在当前值上编辑」）；插件清单页有搜索过滤与可展开详情卡。
本 App 此前模型编辑是自由文本协议 + 纯 ID 列表，且没有删除供应商与保存反馈。

服务端先行：`dsh-remote-bridge` 2.5.0 把内核 `listConfigurableProviders` 的目录元数据
（`defaultBaseURL` / `baseURLEnv` / `nativeAuth` / `settingsNs`）透传到
`GET /api/v1/settings/providers`，并让 `ProviderPatch.models` 支持 `null`（unset，恢复
适配器默认目录）；五处同步（API.md / openapi / asyncapi / kotlin-sdk / 测试），`pnpm check` 全绿。

### 干了什么

- `data/Models.kt`：新增 `NativeAuthHint`；`ProviderView` 补 `settingsNs` / `defaultBaseURL` /
  `baseURLEnv` / `nativeAuth`（全部带默认值，老 bridge 缺席不炸）。
- `data/DshClient.kt`：新增 `deleteProvider`（DELETE + `?expectedRevision=`）与
  `resetProviderModels`（手工拼 `{"models":null,...}`，绕开 kotlinx 不发 null 的限制）。
- `data/ErrorMessages.kt`：`SETTINGS_CONFLICT` → `err_settings_conflict`。
- `HarnessState.providerSaved`（成功才关卡片的反馈通道）；`HarnessPluginsViewModel` 的
  update / create / delete / reset 扩展统一写该通道并提示「已保存 {供应商}。」。
- `ui/ModelsScreen.kt` 重写：官方标题与简介、只读横幅、「添加模型提供商」双通道添加卡
  （第三方目录 = 休眠 `llm-pi-ai` 条目下拉 / 自定义模型 API）。
- `ui/ModelsEditorSheet.kt`（新）：编辑 / 目录 / 自定义三态共用表单 —— 协议下拉（默认
  `protocols[0]`）、密钥 placeholder 按凭据状态分档 + 显隐切换、结构化模型行（ID / 名称 /
  上下文窗口 / 最大输出，K/M 容量解析 + 逐行校验）、「恢复默认模型」、discover 候选面板
  （搜索 / 全选 / 取消全选 / 添加所选）、删除供应商（文案按是否存有凭据分档）；
  打开卡片捕获 revision 作 expectedRevision，失败留卡片、成功由 `providerSaved` 统一关闭。
- `ui/PluginsScreen.kt`：清单加搜索过滤（无匹配空态），插件行改可展开详情卡
  （完整名称 / 已安装 / 声明版本 / 主页 / 不可启停原因）。
- 字符串中英双站同步（`values` / `values-en` 各 +52 键）。
- 真机验证发现并修复：插件清单的搜索框原先直接放进 `SectionPanel` 的内容槽（Surface 内无
  纵向排布），会盖住列表首行；改为 Column 包住搜索框与列表（`ui/PluginsScreen.kt`）。

### 测了什么

- `scripts/build.sh docsCheck :app:testDebugUnitTest :app:lintDebug assembleRelease` 四项门禁。
- release 产物回填：`artifacts/DSH伴侣-companion-v2.2.49.apk`（3,142,644 字节，
  SHA-256 `c06ec719…3ebd3`），`artifacts/SHA256SUMS.txt` 同步。
- 真机（V2080A）安装 debug 包实测：模型页目录添加 / 编辑保存 / 恢复默认 / 删除供应商，
  插件页搜索与展开详情。

## v2.2.46 · 后台任务面板（会话 ⋯ 菜单 →「后台任务」）

### 基于什么

远程场景的真焦虑：在手机上让 agent 跑个长任务（构建、批量处理），然后切走或放下手机 ——
此时没有任何地方能看到「它还在跑吗」，更没有地方能「让它停下」。任务跑飞了只能干等，
或者回到电脑前。官方 WebUI 有 Jobs 面板（`IconJobs` / `client-ui-jobs`），
而本 App 此前 26 个能力位里一个 job 相关的都没有。

服务端先行：`dsh-remote-bridge` 本轮新增三个端点
（`GET/POST /chat/sessions/{sessionId}/jobs*`，能力位 `job-list-v1` / `job-output-v1` /
`job-kill-v1`），Kotlin SDK 同步了 `listSessionJobs` / `readJobOutput` / `killSessionJob`。
本 App 按既有接入纪律消费 bridge 公开版本化 API。

### 干了什么

- `data/JobViews.kt`（新）：`JobView` / `JobStatus` / `JobOutput` / `JobKillResult`
  wire 数据。三条 wire 约定写进注释：可选字段（owner/progress/detail/finishedAt）
  缺省即没有；`next` 是绝对字节偏移；`lossy = true` 的输出**不是完整的**。
- `DshClient` +3 方法：`sessionJobs` / `jobOutput`（支持 `?from=` 续读）/ `killJob`。
- `BridgeCapabilities.supportsSessionJobs`：入口判据。三个能力位在服务端同批声明，
  判据只查 `job-list-v1` 一个（逐个查只是把同一句话写三遍）。
- `JobsPanelState` 进 `HarnessState` + `HarnessJobsViewModel.kt`（新）：
  开/关面板、读清单（失败保留旧清单只亮错误）、终止（确认框在 UI 层；
  `already-finished` 显示「它已经结束了，不用再管」—— 与「已终止」是两句话）、
  展开/收起输出。展开状态跨刷新存活。
- `ui/chat/ChatJobsSheet.kt`（新）：任务卡（label + 状态徽标 + 运行时长 + progress +
  终态原因）、输出等宽文本区、终止确认对话框、手动刷新。**不做实时推送** ——
  服务端就是 REST 只读一帧，v1 用按钮刷新，不为「看起来实时」在手机上挂轮询。
- 入口：会话 ⋯ 菜单「后台任务」，按 `supportsSessionJobs` 能力位显示 ——
  老 bridge 上这条路由是 404，显示入口只会让用户点进去看报错。

### 测了什么

- 新增 `JobViewsTest` 7 例：全字段解码、可选字段缺省、五种生命周期状态按 wire 名
  逐一映射、roster 信封、输出（含 next/lossy/status）、两种 kill outcome、
  **未知字段忽略**（内核加新字段不该让 App 整条解码失败、把「新增」升级成「功能全挂」）。
- `BridgeCapabilitiesTest` 加 1 例：能力位判据（有清单/缺 job-list/空清单）。
- 门禁：`testDebugUnitTest` 59 类 / 524 用例 0 失败（三处声明已同步：
  README 双站 + AGENTS.md §5）、`docsCheck` 过、`lintDebug` 0 error、
  `compileDebugKotlin` 过。

### 踩过的坑（都在同一次编译里）

1. **Kotlin 块注释可嵌套**：数据文件头注释里写了路径通配符（jobs 加斜杠星号），
   那个「斜杠星号」被当成嵌套注释层开始，整个文件从那以后全部错位，
   报 `Unclosed comment`（L80，离出错处 40 行远）。修的过程中在「教训注释」里
   **又写了三处**同样的序列，再炸一次。最后用文字表述代替。
2. **`HarnessViewModel.update` 的 transform 是 receiver lambda**
   （`HarnessState.() -> HarnessState`）：里面直接读字段、直接 `copy(...)`，
   **没有 `it` 也没有参数名**。写成 `update { it.copy(...) }` 或
   `update { state -> ... }` 都是类型错误，报错信息（Map.Entry 未推断）完全不指向根因。
3. **`pathUrl` 返回 `HttpUrl` 不是 String**：`resolve(pathUrl(...))` 类型不匹配，
   直接 `pathUrl(...).newBuilder()`。

### 真机验收（2026-10-20 16:0x，V2080A / Android 11）

装 debug 包（同签名覆盖，vivo 确认页「已了解 + 继续安装」）后逐项点验：

| 项 | 结果 |
|---|---|
| ⋯ 菜单「后台任务」入口（**真实能力位判据，无临时绕过**） | ✅ 显示 |
| 面板打开 + 清单渲染 | ✅ 拉到真实内核任务：`已完成` / `已终止` / `signal: SIGTERM` / `5 min 45 s` |
| 「查看输出」展开 | ✅ 显示「输出」标题 + 空态「这个任务没有输出」（该任务无留存输出，合理） |
| 「刷新」 | ✅ 面板保持、数据在、不崩 |
| 权限档位菜单（顺带验到） | ✅ 显示内核真实三档 `只读` / `完整访问`（F1 修复在 App 侧生效） |

未验到的两项及原因，如实记下：
- **终止确认框**：本机当前没有 `running` 的任务，造不出可终止对象。代码路径有单测
  （`kill` 两种 outcome + 确认框是标准 `AlertDialog`），但真机没点到，不算验过。
- **老 bridge（≤ 2.4.2）上入口隐藏**：需要一个旧版 bridge 实例，本机没有。

### 一次误判要记下来

第一次装完包后菜单里**没有**「后台任务」，我据此判断「能力位没拿到」，还为此临时改成
`true || 判据` 重编验证。实际上：服务端 WS 实测发的是 29 项能力位（含 `job-list-v1`），
App 的 `connection.ready` 也正常到达（临时加的 CAP 诊断日志证实）。

真相是**那一次装的包没有真正生效**（装包后 App 没跑到新代码）。用 `true ||` 绕过后
菜单出现，恢复判据重装后**依然出现** —— 说明判据一直是对的。

教训与 `PITFALLS` 里那条「先坐实现象再改代码」同源：**「装了包」不等于「跑的是新代码」**。
验证装包要看 `lastUpdateTime` + 至少一次冷启动，不能只看 `adb install` 的退出码。

## v2.2.48（versionCode 10073）· Markdown 表格贴行不渲染（真机截图反馈，2026-10-20）

### 基于什么

用户真机截图：一张本该是表格的内容显示成一长串带竖线 `|` 的文字，竖线原样、换行被折叠。
会话「DSH 全局命令升级研究」（2026-10-19 23:41）。

### 根因（取模型原文 + 同款解析器 AST 对照，非猜测）

- bridge（`chat-adapter.ts`）原样透传 `rawChunk.text`、App 拼接（`LiveChatState.append`）纯字符串相加不 trim，
  传输层换行没丢；从内核 `session.v4.jsonl.zstd` 取出的模型原文换行完整，但标题行与表头间**没有空行**：
  `**三层结构**` 紧接 `| 层 | … |`。
- App 底层解析器 org.intellij.markdown（markdown-jvm 0.7.3）对「表格头紧贴上一行、无空行」**不产出 TABLE 节点**，
  整表并入上一个 PARAGRAPH → 竖线变纯文本。GFM/GitHub 允许这种紧凑写法，这颗解析器更严格。
  对照实验（同款 0.7.3 jar）：只补表头前一个空行即出 TABLE；后边界 / 文档开头 / 围栏内伪表格均不受影响。

### 干了什么

1. 新增 `ui/chat/ChatMarkdownNormalize.kt`：`normalizeMarkdownTables()` 渲染前只补「表格前边界」空行；
   跳过围栏代码块（```` ``` ```` / `~~~` 同种符号闭合状态机）；流式安全（分隔行未到不补）；幂等。带 `@patch` 五要素
   （id `glued-gfm-table-needs-blank-line`，因第三方解析器行为写的适配）。
2. `ChatMessageItems.kt` 的 `AssistantBody` 最前接入，**plain 整段与切段 prose 两个分支都用规范化文本**
   （第一版只改 segments，但现场消息无 dsh-ui 围栏走 plain，差点白修）。
3. 连带修真机发现的残留：三列及以上数据表第一列是实体名（如「内核 `@deepseek-ai/dsh` 0.2.0-rc.2」），
   原被两列「字段名窄列（104dp / 两行省略）」吃掉显示成 `@deepseek-...`；`ChatMarkdownTable.kt` 改为
   **仅 ≥3 列时第一列也按权重列自然折行**，两列 Whaleness 定稿布局不动。
4. 文档：PITFALLS 新增「表格贴上一行：intellij-markdown 不认无空行 GFM 表格」+ AGENTS §7 索引；
   README 中英双站 + AGENTS §5 测试数 505→516（57→58 类）。

### 测了什么

- 新增 `ChatMarkdownNormalizeTest`（10 例：补行 / 幂等 / 文档开头 / 无分隔行不动 / 围栏内不动 / 流式半截 /
  后边界 / 对齐冒号 / 尾换行，外加同款解析器「修复前 PARAGRAPH、修复后 TABLE」端到端断言）；
  `ChatMarkdownTableTest` 加三列首列完整性 1 例。
- 门验：`testDebugUnitTest` **58 类 / 516 用例 / 0 失败**、`docsCheck` 过、`lintDebug` 0 error、assembleDebug 成功。
- **真机已复验**（V2080A，2026-10-20 07:24 包装机）：历史会话原表格显示为带边框三列真表格，三行完整，
  第一列包名完整换行、无省略、无横向截断。

## v2.2.47（versionCode 10072）· 文档链接带位置后缀（:行号 / #L行号）打不开（真机截图反馈，2026-10-21）

### 基于什么

用户真机截图：会话正文里的 `docs/project/STATUS.md:1`、`/…/STATUS.md#L101` 点开后，
文件详情面板报「找不到这个路径，可能已经被删了」—— 而文件就在磁盘上，从「本会话的文件」
面板点得开。模型引用文档几乎总带位置，四种写法都见到了：`:1`（第 1 行）、`:12-34`（行范围）、
`:12:5`（行:列）、`#L101`（GitHub 风格）。

本轮改动与**鸿蒙版**（`dsh-companion-harmony` 的 `core/ChatLinkTarget.ts` + `ui/ChatDetail.ets`）
对齐，属于同源缺陷的双端修复。

### 根因（两层，同源）

| # | 根因 | 表现 |
|---|---|---|
| 1 | 位置后缀被原样发给 `roots/resolve`：文件名里真的多了个 `#L101` / `:1` | 服务端回「不是普通文件 / 找不到」 |
| 2 | 分类用 `indexOf(':')` **一刀切**认 scheme，`docs/x.md:1` 的「scheme」是 `docs/x.md` | 判成**未知协议**，连拼 cwd 的机会都没有 |

`#` 更隐蔽：它在 URI 里是**片段标识符**，而模型照着 GitHub 的写法写，我们此前一直当文件名的一部分。

### 干了什么

1. `data/ChatLinkTarget.kt` 新增 `stripLocationSuffix()`（纯函数）：
   只认**确定形态** `:数字` / `#L数字`（含 `:12-34` / `#L12-L34` / `:12:5`，`#L` 大小写不敏感）；
   **最多剥两段**（`/a/b.ts:1:2:3` 剥成 `/a/b.ts:1` 就收手）；**剥成空串则不剥**（目标本身是 `:12`）。
2. 接入点两处，与鸿蒙版一一对应：
   - **绝对路径**在 `classify()` 里剥 → `ServerPath(stripLocationSuffix(target))`；
   - **相对路径**在拼 cwd **之前**剥 —— 新增纯函数 `joinUnderCwd()`，`ChatLinkHandlers` 改调它。
     抽成纯函数是因为真正的调用点在 Compose 的 `UriHandler` 里，那里测不了；
     而「拼错基准」是本仓历史最贵的一类错（拼出翻倍路径 / 打开另一个文件）。
3. 分类逻辑修「冒号一刀切」：相对路径分支先剥后缀再判 scheme，`docs/x.md:1` 归 `RELATIVE_PATH`
   而不是 `UNKNOWN_SCHEME`。仍然**不猜基准**，`raw` 保留原文供拼接时再剥一次。
4. 文档：`PITFALLS.md` 新增坑位「文档链接带位置后缀（:行号 / #L行号）被当成路径的一部分」
   + AGENTS §7 索引；README 中英双站 + AGENTS §5 测试数 537→542。
   **顺带修掉上一个提交留下的门禁红灯**：`busy` 坑位的 `###` 标题带了反引号，
   而 `ArchitectureGuardTest` 的索引正则 `^- \`([^`]+)\`$` 不允许被包内容里再有反引号 ——
   双向比对必红。两处标题已去掉内层反引号（索引保持单层包裹）。

### 刻意不动的两处（都有单测钉住）

- `https://e.com/a#L1` 的 `#` 是**页内跳转**，剥掉会把用户送回页面顶部；
- `http://host:8080` 的端口也不是行号。

保住的办法是「**网页分支返回原文**」（`Web(target)` 传未经剥壳的串）。
谁把 `stripLocationSuffix` 提到分支之前统一调用，这两条会立刻变红。

### 测了什么

- `ChatLinkTargetTest` +5 例（22 → 27）：绝对路径六种写法归到同一文件 / 相对路径带后缀仍算相对
  且 `raw` 保留原文 / 网页片段与端口一字不动（含尖括号包住的写法）/ `stripLocationSuffix` 边界
  （日期不误伤、行范围要剥、`:12` 不剥成空、三段只剥两段）/ `joinUnderCwd` 拼接（含前导尾随斜杠）。
- 门验：`testDebugUnitTest` **60 类 / 542 用例 / 0 失败**、`docsCheck` 8 文档 + 14 项新检查全绿。
- **未上机**：真机复验由用户自行完成（用户既定口径）。单测绿 ≠ 行为对。

## v2.2.48（versionCode 10073）· 应用内更新检查（方案报告对标，2026-10-21）

### 基于什么

方案报告 `report/dsh-companion-应用内更新检查-实现方案_2026-10-21.md`：App 此前只有手动装 APK
一条升级路，没有应用内检查更新的入口；真机上想知道「有没有新版本」只能回电脑看 release 页。
方案定下：检查源按 UI 语言分渠道（中文 zh* → Gitee，英文/其他 → GitHub），流程
「查最新 → 下 APK → SHA256SUMS.txt 校验 → 拉起安装器」，不引第三方库。

### 干了什么

- `data/UpdateChecker.kt`（新）：`ReleaseChannel` 枚举（GITEE/GITHUB，各自 apiUrl + headers；
  GitHub 不带 User-Agent 会 403）、`channelForLanguage`（zh* → GITEE）、`isNewerVersion`
  （数字段逐段比较）、`ReleaseInfo`/`ReleaseAsset`（kotlinx.serialization + ignoreUnknownKeys）、
  `pickApkAsset`/`pickShaSumsAsset`、`parseSha256Sums`、`sha256Hex`（64KiB 分块）、
  `checkLatest`/`downloadApk`（进度回调 `(downloaded, total)`）/`downloadText`、
  `buildInstallIntent`（FileProvider `${packageName}.files` + ACTION_VIEW）、
  `buildInstallSourcesIntent`（未知来源设置页）、`summarizeReleaseNotes`。
- `ui/UpdateCheckSection.kt`（新）：`UpdateUiState` 七态（Idle/Checking/UpToDate/Available/
  Downloading/Ready/Failed）+ `UpdateCheckSection(context)` + `UpdateActionButton`，
  复用 `SettingRow` 挂在设置页「关于」一节版本行之后；文案全走 `stringResource`，
  点击回调里不摸 `context.getString`（lint `LocalContextGetResourceValueCall`）。
- `data/DshClient.kt`：`defaultHttpClient` 由 `private val` 改 `internal val`（KDoc 注明给更新
  检查复用）—— `ArchitectureGuardTest` 的 OkHttpClient 白名单只有两个文件，更新代码另建必红，复用即绿。
- `AndroidManifest.xml`：+`REQUEST_INSTALL_PACKAGES`（拉起系统安装器所需）。
- 文案：`values/strings.xml` + `values-en/strings.xml` 各 +20 个 `update_check_*` 键，
  中英键集与占位符双向一致（`StringResourcesTest` 双向断言）。

### 测了什么

- 单测：`data/UpdateCheckerTest.kt`（新）27 例：channelForLanguage、isNewerVersion、
  pickApkAsset/pickShaSumsAsset、parseSha256Sums、summarizeReleaseNotes、downloadProgressPercent。
- 门验：`testDebugUnitTest` **61 类 / 569 用例 / 0 失败**（三处声明同步：README.md / README.en.md /
  AGENTS.md）、`lintDebug` 0 error、`docsCheck` 全绿、`app/src` 无文件超 800 行。
- **真机已复验**（V2080A，2026-10-21 08:40 包装机）：设置 →「关于」→「检查更新」真实走通
  Gitee HTTPS → 解析 ReleaseInfo → 找资产 → `isNewerVersion` 判同版 → 渲染
  「当前版本：v2.2.47 / 已是最新版本」，布局无重叠无截断（截图 OCR 核对）。
- **未上机**：Available → Downloading → Ready → 安装器的下载/校验/安装链路 —— 真机当前版本与
  Gitee 最新发布同为 v2.2.47，无新版可下；要触发只能人为造 release 或临时降 versionName，
  超出本任务边界（方案 §八 同款遗留）。

## v2.2.48（versionCode 10073）· 会话内联交付物卡片（对齐桌面端，2026-10-21）

### 基于什么

用户对照两端截图：电脑端会话里 `present` 的文件有**内联卡片**（钉在消息尾部），
手机端只有「本会话的文件」弹层。数据其实一直在历史里 —— `deliverables/presented`
事件早已被 `deliverables()` 读去弹层面板，但 `displayItems()` 的映射把这一类整条丢弃，
消息流里从来没有卡片。

### 干了什么

- `ChatItemKind.DELIVERABLE`（新枚举值）+ `ChatDisplayItem.deliverable` 字段：
  与 `failure` 同一条纪律 —— 卡片信息由事件显式给出，**不做路径嗅探**。
- `ChatPresentation.displayItems()` 改两段式：按出现顺序把 `deliverables/presented`
  缓冲进 pending（同路径重复交付只留一条、说明取最后一次），每轮 `turn/end` 处冲刷成卡片
  —— 钉在**轮次收尾**，与桌面端 turnTail 的钉法一致；会话尾部没有 `turn/end` 时补在最后。
  交付发生在轮次结束之前，卡片排在 `turn/end` 行（失败/截断提示）之前，时间序不错位。
- `ui/chat/ChatDeliverableCard.kt`（新）：紧凑卡片（图标 + 文件名 marquee + 说明），
  **不带完整路径**（预览面板里有，对齐桌面端信息密度）；点按走 `openSessionFile` +
  文件面板，与正文里的文件链接同一条路（`ChatItem` / `ChatDisplayEntryRow` 加
  `onOpenFile` 形参，带默认值不破坏调用方）。
- 文档：PITFALLS §交付物事件与路径嗅探 补内联卡片段；AGENTS §4.1 输出清单加显式交付物。

### 测了什么

- 新增 `ChatDeliverableCardsTest` 6 例：轮次收尾钉卡（卡片在收尾消息后、下一轮提问前）、
  无 `turn/end` 补尾、同轮同路径单卡取最后说明、失败轮保卡（交付在失败前）、
  空 `files` / 缺说明容忍、DELIVERABLE 不折进过程组。
- 门验：`testDebugUnitTest` **62 类 / 575 用例 / 0 失败**、`docsCheck` 全绿、`lintDebug` 0 error。
- **未上机**：真机复验由用户自行完成（用户既定口径）。单测绿 ≠ 行为对。

## v2.2.45 · 真机交互九条 + 交付物链接打不开（用户逐条点名，2026-10-19 晚）

### 基于什么

用户在真机验收后逐条提出的八项交互反馈（原话见下），全部围绕**会话页的可用性**：
顶栏太挤、状态不直观、文件打不开、权限改不了、发送/停止混在一起。

### 干了什么

1. **权限 Pill 从输入区上方挪到顶栏状态行**（与「已连接」同一行）。
   原来它 `.fillMaxWidth()` 独占一条，用户原话「影响页面可视区域」。
   `PermissionPill` 同时改成紧凑胶囊（去掉 fillMaxWidth，可在行内使用）。
2. **状态文案改「已连接 / 已断开」+ 绿/红圆点**。`StatusLabel` 新增
   `activeColor` / `inactiveColor`（默认仍走主题色，零回归），连接状态传
   `ConnectionOnlineColor`（绿）/ `ConnectionOfflineColor`（红）。
   会话列表栏同一套；WebView（`chat/bridge.js`、`chat.html`）文案同步。
3. **会话里的相对路径能打开了**：新增 `ui/chat/ChatLinkHandlers.kt` 承接原在
   `ChatDetail.kt` 的链接分发；相对路径用**会话 `cwdAbsolute`** 拼成绝对路径再走
   `roots/resolve` 正路（此前一律提示「相对路径无法定位」—— 那时没有基准，现在有）。
   拼完仍由服务端校验，不会打开「别的文件」。
   **不支持的类型调系统关联应用**：会话文件面板新增「用其他应用打开」——
   下载到 `cacheDir` → `FileProvider` → `ACTION_VIEW`（新增 `res/xml/file_paths.xml`
   与 manifest 的 provider）。覆盖「超过预览上限」与「本 App 不认的二进制」两个分支。
4. **「追加引导」为什么一直是灰的**：它是「把输入框的内容插进**正在运行**的那一轮」，
   判据是 `会话运行中 + 输入框有内容`。空输入时灰置是**正确行为**，但缺说明 ——
   现在灰置时副标题改成「先在输入框写好内容，再从这里送进正在运行的那一轮」。
5. **创建会话时可选权限档位 + 会话中可切换**：新建会话抽屉新增
   `PermissionPresetPicker`（新文件），选好后 `createSession` 建完立刻用
   `/permission <preset>` 切过去（与手动切**同一套机制**，不做两套语义）。
   会话中切换沿用既有 Pill / ⋯ 菜单。
6. **顶栏的刷新与停止收进 ⋯ 菜单**（用户要求）：顶栏只留「本会话的文件」。
   菜单最前面新增两项（刷新 / 停止运行），停止仅在运行中可点。
7. **输入区控件垂直居中 + 发送箭头朝上**：`Alignment.Bottom` → `CenterVertically`
   （此前照抄网页版 flex-end，但网页输入框单行不变高，本 App 输入框 44–120dp 会变高，
   越矮差异越刺眼）；发送图标 `Send` → `ArrowUpward`。
8. **发送键三态**（用户规则）：有内容 → 永远发送（哪怕运行中，那是插话/排队）；
   运行中且空 → **红色方形停止键**；空闲且空 → 灰置。抽成纯函数
   `composerSendButton()` + 单测 `ComposerSendButtonTest` 钉住优先级。

9. **权限档位文案全本地化**（用户要求：中文就全中文、英文就全英文，不要混着显示）：
   原因是「标题走 App 本地化（中文），而描述直接来自服务端（内核英文原文）」。
   新增 `permissionDescription(value, fallback)`，已知档位（read-only / workspace-write /
   danger-full-access / custom）一律用 App 自己的资源；**未知档位才退回服务端原文** ——
   自己没见过的档位，翻不了就不假装翻。`PermissionMenuItems`（Pill 与 ⋯ 菜单共用）
   与 `PermissionPresetPicker`（创建会话）两个消费点都切过去。

10. **会话里的文件链接点不开**（用户报：「我手动点都没反应」）—— 三段式根因，缺一不可：
    ① `ChatLinkTarget.classify` 不剥 Markdown autolink 的尖括号（模型写的是
    `[文字](</abs/path>)`），于是 `<...>` 被判成**相对路径**，绝对路径又被拼一次 cwd，
    得到翻倍路径（实测 `PATH_NOT_FOUND path=/workspace/</media/…>`）；
    ② `openSessionFile` **只加载数据、不开面板**（面板是 ChatDetail 的局部 `filesSheet`），
    所以即使 resolve 成功，屏幕也一动不动；
    ③ `UNRESOLVABLE_CODES` 把 `PATH_NOT_FOUND` 也算「打不开的正常结果」，
    「文件没找到」被显示成「不在授权根内」，排查方向被带偏。
    修法：`classify` 先剥尖括号；把「打开什么」交给调用方（`onOpenSessionFile` 回调，
    ChatDetail 一并 `filesSheet = true`）；`UNRESOLVABLE_CODES` 收紧到只剩 `PATH_OUTSIDE_ROOTS`。
    另加 `LINK openUri=…` / `PATH resolve 失败 code=…` 两条诊断日志用于二分。
    真机实测：点击 → 面板打开并渲染 Markdown（`text/markdown · 12.2 KiB`）。
    顺带新增 `linkifyKnownFiles`：模型只在正文写纯文件名（不是链接）时也能点开，
    规则是「唯一文件名才替换、只换第一次、不碰已有链接」。

### 顺带（bridge，姊妹仓）

`chat-adapter.executeCommand` 调内核 `commands.execute` 时**少传了
`submittedAttachments`**（内核签名四个参数），导致 signal 位错、所有斜杠命令报
`Cannot read properties of undefined (reading 'aborted')`。这就是「会话中改不了权限」的根因。
已修 + 桩断言补上（`tests/chat-adapter.spec.ts` 现在断言第三、四参）。

### 测了什么

- 新 `ComposerSendButtonTest` 3 例（有内容优先发送 / 运行中空才停止 / 空闲空则禁用）。
- 门验：**56 类 / 496 用例 / 0 失败**、`docsCheck` 8 文档、`lintDebug` 0 error。
- 真机（V2080A）：**需求 1/2/6/7/8 逐条截图核对通过**（顶栏「● 已连接 🔒 完整访问」、
  输入区空且运行中显示红色方形停止键）。
- **第二轮真机（22:21，重启 DSH 后）**：需求 5「会话中切换」✅（REST 实测
  `{"kind":"success","text":"preset workspace-write"}`，App 侧 Pill 由「完整访问」变
  「工作区写入」）、需求 9 ✅（菜单描述全中文，英文残留 0 处）、需求 4 ✅
  （灰置时副标题说明「先在输入框写好内容…」）。
- **仍未验**：需求 3 的两条路（相对路径解析 / 用其他应用打开）—— 需要一条含相对路径
  链接的消息或一个本 App 不认的二进制文件，尚未构造。

## v2.2.45 · v2.2.44 真机验收三缺陷收口（2026-10-19）

### 基于什么

《验收报告-v2.2.44.md》（`.verify-v2244/`，vivo V2080A 真机 5.5 小时）报 3 失败 + 4 有缺陷。
动手前先在源码与**正在运行的 bridge** 上复核了一遍根因，发现报告里三条结论要修正。

### 复核改了什么结论（重要，别照抄报告）

1. **F1 比报告说的更深一层**：报告说「投影里 permissions 形状对不上」。实测
   `GET /api/v1/chat/sessions/{id}/messages` 返回体**根本没有 `projections` 字段**
   （top keys 只有 `events / hasMore / historyFormatVersion`），所以
   `permissionSelect()` 的第一行就已经是 null。**候选项根本不在投影里** ——
   内核把它放在独立远程服务 `permissionPresets.catalog()` 上（wire 视图只产 `currentValue`）。
   推论：App 侧单改读 `preset/sandbox` **仍然拿不到 options**，必须 bridge 先补 BFF。
2. **F3 的根因不成立**：`createSession` 其实已经把 `sessions` 写回并置了 `selectedSessionId`。
   真正的洞是**创建路径不经过 `selectSession`**，而 `loadMentionEntries()` 只挂在
   `selectSession` 上 —— 于是新会话后 `@` 面板永远停在「正在读取」，且**没有任何重试入口**。
3. **F2 已结案：不是 App 缺陷，是验收时点击落空**（2026-10-19 真机现场查清）。
   报告称「请求没到 bridge」；实测该端点 POST 正常 201、制造冲突时 409，
   而审计 `file.create` 记在 `createEntry` **之后**（`router.ts:973`）——「无审计记录」
   本来就推不出「请求没到」。真因是：**往对话框输入文件名后软键盘弹起，对话框上移 426px**
   （`(834,1442)` → `(834,1016)`），用**弹起前** dump 的坐标点「创建」落在对话框之外，
   触发 `onDismissRequest` 把它关掉 —— 全程一个请求都没发，于是「文件没建、
   没有提示、审计无记录」三件事同时成立，完美伪装成功能坏了。
   一锤定音的是本轮补的诊断日志（`FILE 创建条目 …` / `SEND 请求失败： …`）：
   用正确坐标点击后日志出现、bridge 审计出现 `actor_type=device` 的 `file.create`、文件真的生成。
   已写入 `PITFALLS.md` §键盘弹起让对话框上移。

### 真机复验（2026-10-19 晚，vivo V2080A）

- **F3 `@` 补全卡死：已修复**（新建会话内打 `@` → 正常列出 `.diag-shots/` 等条目，无「正在读取」）。
- **F1 权限档位：两个断点已修复，真机确认渲染**（重启后复验）。
  ① bridge 的 `messages` 端点原先**完全不下发 `projections`** → 新增 `readSessionProjections()`
  并进 history（`@patch history-projections-passthrough`，新 6 例测试）。
  ② 目录端点原报 `cannot get property "permissionPresets" without inject` ——
  **真因不是「包没装」**（包就在核身体内，且 `/permission` 命令与投影都在正常工作），
  而是该服务**不在插件的可注入面**；按本仓 `attachments`/`fileUploads` 那条既有结论
  （此异常 = 「压根没有」，正确做法是**容错读取**）改用 `optionalService` + 内核默认档位降级。
  真机实测：**Pill 显示「完整访问」、⋯ 菜单显示「权限模式 · 完整访问」**；
  候选项来自降级清单（`workspace-write` / `danger-full-access`），档位因此可切换。
  ③ **意外收获**：投影 `values` 里还带着 `tokenUsage` / `contextPressure` / `contextBreakdown`
  —— App 会话统计面板至今写着「需要内核提供 usage 数据，本插件暂拿不到」，现在拿得到了，
  留作下一轮功能点（本轮不扩范围）。
- **W3 未保存保护：已修复**（未改动 → 点返回直接回目录；改动后 → 弹出
  「有未保存的改动 / 关闭 / 丢弃改动 / 保存」，选「丢弃改动」后文件未被写入）。
- **诊断浮层遮挡：已根治**（见下）。

### 干了什么

- **F1 权限档位（bridge + App 两侧）**：
  - bridge：新增 `GET /api/v1/chat/permission-presets`（scope `chat.read`，与 agent-presets 同档），
    走新 `permissionPresets` 命名空间 → 内核 `catalog()`；出网前过 `normalizePermissionCatalog()`
    收敛形状（丢无 value 的行、缺 name 退回 value、**空串 description 不透传**）。
    接入 `inject` 并登记 `@patch permission-presets-catalog-bff`。
  - App：`permissionSelect(catalog)` 改为**两路合成** —— 当前值依次读
    `currentValue` → `preset` → `sandbox`，候选项优先取投影内联、否则取目录接口；
    候选项为空**不再整块不渲染**（Pill 与 ⋯ 菜单都改成「无选项时显示只读档位标签」）。
    连接时取一次目录，取不到不报错（老 bridge 404 不该每次连接都弹 snackbar）。
- **F3 `@` 补全**：两个创建会话的入口都补 `loadMentionEntries()`；`loadMentionEntries`
  早退时置 `mentionFailed = true`（说「读不到」而不是谎称「还在读」）；面板加**重试**按钮。
- **F2 文件创建**：静默早退改成抛错（走全局 snackbar），新错误码 `NO_ROOT_SELECTED`
  进本地化表（中英）。真机复验：**同名冲突会显示「同名文件已经存在了」**（报告里
  「409 也不显示」那一项由此修好）。结案结论见上文（点击落空，非 App 缺陷）。
- **诊断日志补点（用户要求「没有记录就把记录补上」）**：在四处原本**一声不吭**的地方留痕 ——
  ① `withClient` 无活动连接被丢弃；② `launchOperation` 代际失效连错误一起丢；
  ③ 任何请求失败（记异常类型 + 原文）；④ `createEntry` 的入口与返回。
  这几条直接让 F2 当场结案 —— 在此之前真机上**没有任何判据**能回答「请求到底发了没有」。
  为使 `HarnessViewModel.kt` 回到 800 行预算内，`withClient`/`launchOperation` 一并搬到
  新文件 `HarnessOperations.kt`（成员函数改扩展函数，调用点写法不变）。
- **W3 未保存保护**：`OpenDocument` 加 `originalText` 与 `hasUnsavedChanges`；保存键染主色；
  返回箭头与系统返回手势都拦（新 `UnsavedChangesDialog`，存不了时「保存」键不亮）。
- **诊断浮层改为可拖动（第四次复发的根治）**：`DiagProbe` 加 `dragX/dragY` +
  `detectDragGestures`，边界取自父容器实测尺寸。前三次都靠「调死坐标」让开，
  第四次撞上**会话详情页的返回箭头**（与主页汉堡不在同一 y）—— 结论是
  **没有一个固定坐标能对所有页面都对**，故改拖动。详见 `PITFALLS.md` 同名条目。
- **W1 统计口径**：`sessionStatsOf` 的「轮次」只数 `source.kind == "user"`
  （真机实测 9 条里只有 2 条是真用户），缺 `source` 的老格式按「是」处理。
- **W2 表格行内代码**：单元格接一次行内 Markdown 渲染；`isInlineOnly()` 把含换行/管道/
  列表/标题/围栏的单元格挡回纯文本（自绘两列布局嵌块级结构必错位）。
- **W3 未保存保护**：`OpenDocument` 加 `originalText` 与 `hasUnsavedChanges`；保存键染主色；
  返回箭头与系统返回手势都拦（新 `UnsavedChangesDialog`，存不了时「保存」键不亮）。
- **拆文件（行数门禁）**：`ui/FilesScreen.kt` 858 → 735（对话框搬到 `ui/FileEntryDialogs.kt`
  与 `ui/UnsavedChangesDialog.kt`）、`ui/chat/ChatDetail.kt` 805 → 800（权限值提为局部变量；
  两处各算一次会出现「菜单里有、Pill 没有」）、`HarnessViewModel.kt` 817 → 774
  （`withClient`/`launchOperation` 搬到 `HarnessOperations.kt`）。
- W4（构建水印双值）**未动**：那需要改 Agent 侧产出的 watermark 取值口径，
  属另一个仓的约定，本轮不动，等确认后再议。

### 测了什么

- 新 `PermissionProjectionTest` 7 例（新旧两种投影形状、目录兜底、内联优先、sandbox 兜底、
  空档位名退回 value、「什么都没有」才返回 null）；
  新 `SessionStatsAndEditorGuardTest` 8 例（注入不算轮次、老格式按用户算、行内/块级判定、
  脏状态与非文本文件）；bridge 新 `tests/permission-presets.spec.ts` 6 例。
- 门验：**55 类 / 493 用例 / 0 失败**；bridge `pnpm check` 全绿（39 文件 / 297 用例）。
- bridge 那条用例**首跑就抓到实现的真 bug**：空串 `description` 被原样透传，
  客户端会渲染出一行空白副标题 —— 已修（判据与 name 一样按非空串算）。
- **未做**：真机复验（本轮无设备）。F2 的请求链路、W1–W3 的界面观感都**只过了单测**，
  按本仓铁律「单测绿 ≠ 行为对」，出包后必须再上机。

## v2.2.44（versionCode 10069）· 工具专用块 + 权限档位 Pill：对标官方 Web 第一梯队（2026-10-18）

### 基于什么

《官方Web界面与APP功能对比》（`workspace/资料调研/官方Web界面与APP功能对比_2026-10-18.md`）
第一梯队两项：工具调用专用块（diff / terminal / read）与发送区常驻的权限档位 Pill。
工具名分布按本机真实会话日志实测（3000 条 tool/call：bash 71%、edit 9%、
read/read_image 8%、write 2%）—— 三种专用块覆盖 ~88% 的调用。

### 干了什么

- **A1 数据层**：新 `data/ChatToolBlocks.kt` —— `toolBlockView()` 按 name+arguments 把
  `ToolCallDetail` 分发为 `Read / Terminal / Diff / Generic` 四种视图模型；
  ANSI 转义剥离（CSI + OSC，残缺序列不吞正文）、read 结果 `<content>` 包装剥取、
  行号判据（`N: ` 前缀）都是纯函数。**解析失败一律回落 Generic**。
- **A2-A5 UI**：新 `ui/chat/ChatToolBlocks.kt` —— ReadBlock（暗底路径头 + 正文）、
  TerminalBlock（`$ cmd` 提示符行 + 暗底输出）、DiffBlock（edit 旧/新 `-`/`+` 着色对照、
  write 全文「新建」）。接入 `ToolCallDetailBlock`：专用块接管输入/输出主体；
  Generic 分支原样走旧渲染；非 Generic 的**失败输出**仍以 error 块补显（isError 是内核
  独立标记，不因专用块丢失）。头部（名称/状态/耗时）不动。
- **B 权限 Pill**：新 `ui/chat/ChatPermissionPill.kt` —— 发送区上方常驻当前档位
  （锁图标 + 档位名），点击弹档位菜单；数据与动作与顶栏 ⋯ 菜单**同源**
  （`permissionSelect()` 投影 + `selectPermissionPreset`），完整访问仍过
  `FullAccessConfirmDialog` 二次确认。无权限投影时整条不渲染。
- **C chat.html 同步**：`toolEntry` 增加同款四分支（终端 `$ cmd` 行、read 路径头+正文、
  edit/write `-`/`+` 对照）；文件 2 517 → 2 575 行，**先入 TECH-DEBT 台账再写码**，
  并立约：chat.html 不再吸收新功能。
- 文案：中英 strings 各 +7 键。

### 测了什么

- 新 `ChatToolBlocksTest` 16 例：分发（bash/read/read_image/edit/write/未知）、
  畸形输入回落（坏 JSON / 非对象 / 空 result）、ANSI 剥离（CSI/OSC/残缺）、行号判据。
  首跑抓到 2 个真实问题：残缺 CSI 序列会吞掉参数字节（实现 bug，已修）；
  「空参数」对 bash/read 是合法输入应正常分发（测试断言错，已修）。
- 全量 `testDebugUnitTest`：53 类 / 478 用例 / 0 失败；`docsCheck` 8 文档一致。
- 行数门禁：ChatDetail 807 → 压回 800 内；`ArchitectureGuardTest` 绿。

## v2.2.44（同批次追加）· chat.html 拆分：壳 + 6 JS + 1 CSS，逐字搬迁零行为变化（2026-10-18）

### 基于什么

上一条目立约「chat.html 不再吸收新功能」后，用户指示研究并执行拆分。可行性研究确认三个前提：
① WebView 走 `loadUrl("file:///android_asset/chat.html")` 且 `allowFileAccess = true`，
相对子资源天然可用，无需动 Kotlin；② 无 `evaluateJavascript` 抢跑时序；③ 全仓无按行引用。

### 干了什么

- chat.html 2575 行 → **355 行壳**（HTML 骨架 + 6 `<script src>` + 1 `<link>`）；
- 拆出 `assets/chat/`：chat.css 302 / core.js 582 / render.js 175 / tools.js 203 /
  interact.js 803 / cards.js 223 / bridge.js 269；
- 切分判据是**功能域**（全局状态基础设施 / 渲染 / 工具组 / 交互与连接 / dsh-ui 卡片 / 附件与桥导出）；
- 普通脚本共享全局作用域（core 必须最先加载），刻意不用 ES module（file:// 下 import 被 CORS 拦）。

### 测了什么

- **逐字等价机检**：6 个 JS 内容拼接与原 `<script>` 区块 diff 为零（三处切点空行对齐后）；
- `node --check` 六文件 + 拼接整体语法全过；
- 全量单测回归 0 失败；assembleDebug 产包内 7 个资产齐全（unzip -l 核对）；
- interact.js 803 行超预算 3 行，已在 TECH-DEBT 台账登记（下批拆 renderHistory 收掉）。

### 未做

- 真机回归（WebView 兜底路径全功能过一遍）—— 等发话。

## v2.2.44（同批次追加）· chat.html 对标补全 + 会话页存量修复（2026-10-19）

### 基于什么

用户逐项点名 chat.html 与原生界面的差距：模型选择不分厂家、缺工具轨迹与统计、`@` 补全
缺失、`/` 命令面板发送后不落回、▤ 文件不在右侧。另有前期会话留下的「回答被截断」提示
缺失 —— 真机 2026-10-18 复现：电脑端 WebUI 有这条黄色提示，App 里整条消失。

### 干了什么

- chat.html ⋯ 菜单：模型选择改**按厂家分组的树型**（对齐原生 ModelSheet）；新增
  **工具轨迹**与**统计**两个底部面板（纯前端从历史归算，不新增桥方法）。
- chat.html 新增 **`@` 文件补全**：原生 init 多传 `rootId`/`cwd`/`cwdAbsolute`，进会话预拉
  `roots/{rootId}/entries`；输入 `@`（前为行首/空白才触发，避开邮箱）弹补全窗，选中插
  **绝对路径**（目录尾补 `/`、文件补空格），对齐原生 `MentionQuery`。
- 顶栏把 `#status` 移到末尾，`▤文件 → ⟳刷新 → ⇥插话 → ■停止 → ⋯更多` 连续靠右。
- **修 `#cmd`/`#mention` 发送后不落回**：二者 CSS 的 `display:flex` 优先级压过 `[hidden]`，
  `hidden=true` 形同虚设；改为默认 `display:none`、`:not([hidden])` 才显示。
- **「回答被截断」提示**：`HarnessTruncationViewModel` 从 `turn/end` 的
  `reason.kind == 'max-tokens'` 落提示与清理（配套 bridge **v2.4.1** 透传该字段）。
- 会话页存量：诊断日志开关（`debug/DiagLog` + `HarnessDiagViewModel`）、连接导入面板
  `ConnectImportPanel`、进会话**落底跟随** `ChatScrollFreeze` + 探针 `ChatScrollDiag`。

### 测了什么

- 全量 `testDebugUnitTest`：**55 类 / 478 用例 / 0 失败**；
- `docsCheck` 8 文档一致；
- `lintDebug` 首跑 3 个 error（`SettingsScreen` 点击回调里 `context.getString` 触发
  `LocalContextGetResourceValueCall`）—— 改为组合期 `stringResource` 预取后清零；
- 真机（2026-10-18）：用户确认 `/` 落回、`@` 弹补全、▤ 靠右三处正常。

### 未做

- `@` 补全与截断提示的真机端到端复核（截断提示需 bridge ≥ 2.4.1）。

## v2.2.43（versionCode 10068）· 仓库卫生：清掉 40.9 MB 调试残留 + 堵住 `.secrets` 泄露口子（2026-10-17）

> **本版无任何代码或界面改动**，APK 与 v2.2.42 完全一致，只多了版本号。真正的改动都在
> `.gitignore` 与仓库文件树上。

### 基于什么

用户要求清查仓库根目录下 `.wm` 类文件夹的用途、判断能否删除。排查发现它们不是资产，
而是 v2.2.42 从「四打」排到「十一打」逐版真机排查时留下的**解包取证目录**：

- `.wm` ~ `.wm8`、`.apkcheck`、`.apkcheck2`：各存一份从构建出的 APK 里 unzip 出来的
  `classes.dex`，用来核对「这次改动到底有没有打进包」；
- `.m3` / `.mdlib`：把 `multiplatform-markdown-renderer` 的 AAR/JAR 解包成 `.class`，
  用来查表格渲染相关的类结构与方法签名。

它们是一次性产物，但 `.gitignore` 里没有对应规则，某次 `git add -A` 把它们一并提交了。

### 为什么

1. **体积**：146 个文件、40.9 MB 常驻版本库，每个 clone 都要白拖；而且它们看起来像
   「有意保留的资产」，后来者不敢删，只会在旁边继续堆。
2. **泄露口子（更要紧）**：`.gitignore` 保护 `.secrets/` 的只有 `*.jks`、`*.keystore`、
   `signing.properties` 三条**按文件名**的规则，目录本身从未被忽略。实测往里放一个
   `secret.txt`，`git check-ignore` 不命中、`git status` 能看见，一次 `git add -A` 就会
   推到 Gitee / AtomGit / GitHub 三个远端 —— 而那目录里是发布签名密钥与两份口令文件。

### 干了什么

- `79d015c`：`git rm -r --cached` 并物理删除 12 个目录（146 文件 / 40.9 MB），
  `.gitignore` 增补根锚定规则 `/.wm*/`、`/.apkcheck*/`、`/.m3/`、`/.mdlib/` 并注明用途；
- `afbea7d`：`.gitignore` 第 23 行新增 `/.secrets/` **整目录忽略**，注释写明为什么
  不能只靠逐文件名规则；
- 顺带清掉本机 `.gradle`（32 M 构建缓存）与 `.kotlin`（56 K 编译日志），两者早已被忽略、
  不在提交内，删前先 `gradle --stop` 停掉空闲守护进程；
- 未改动 `.secrets` 内任何文件、未碰源码与构建脚本。

### 测什么

- `docsCheck`：8 文档 / 46 任务一致；
- 忽略规则**正向**：`git check-ignore` 对 `.secrets/`、其子目录与 `dsh-release.jks` 均命中；
- 忽略规则**负向**：`app/`、`docs/`、`scripts/`、`.gitignore` 未命中，没有误伤；
- **漏洞回归**：复现原攻击路径（真建 `.secrets/__probe__/secret.txt`），现已命中 `/.secrets/`，
  且 `git status` 中该文件出现条数为 0，探针随后删除；
- `.secrets` 四个文件逐一复核完好（`dsh-release.jks` 3826 B、归档旧钥匙 2656 B、
  两份 `signing.properties` 1212 B / 455 B），字节数与清理前一致；
- 全量单测本轮重跑，类数 / 用例数 / 失败数见 `TESTING.md`。

### 未做

真机验收 —— 无界面改动，不需要上机。

## v2.2.42（versionCode 10067）· 对标补强：A/B/C 三批 + Whaleness 会话页舒适度（2026-10-17）

### 基于什么

`dsh-study/分析-2026-10-16/` 的六份对比报告（对 Whaleness `dsh-mobile` 与
`dsh-plugin-mobile-gateway`），以及用户拍板的三条要求：A/B/C 三批全做、会话页舒适度对齐、
设置页选项合并。姊妹仓 `dsh-remote-bridge` 同期发布 **2.3.8**。

### 干了什么

- **A 批（功能与渲染）**：消息内图片查看与存相册、会话面板文件下载、思考块折叠、
  代码/JSON 高亮与复制、滚动期冻结、过程分组与耗时摘要、失败重试、字号缩放、
  消息时间戳（三档粒度）、按 `atSeq` 从某条用户消息分叉、已归档会话列表；
  **归档恢复不做** —— 内核是否提供 unarchive 调用从本仓不可证，界面已明说需在电脑端调整；
- **B 批（工程质量）**：架构守卫单测、请求代际隔离、事件去抖、投影缓存、
  Clock/Transport 接缝（顺带清掉 DEBT-007 那条 flaky）；
- **C 批（客户端侧）**：服务端能力位探测（缺关键位时明说「插件过旧」）、
  断档检测与 resync、订阅基线、连接即重放、交付物实时事件、会话统计与轨迹页；
- **Whaleness 舒适度**：过程分组折叠 + 耗时、滚动期暂停 token 提交、轨迹页
  （按轮次时间轴，点节点展开原始 JSON）、设置页外观选项合并（样式 / 字号 / 跟随系统）；
- **三条新机检门禁**：坑位索引 ↔ PITFALLS 正文双向一致、中英 strings 键与占位符一致、
  测试数字在 README 双站与 AGENTS 三处一致且与源码 `@Test` 数相符。

### 测什么

`:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**；`docsCheck` 8 文档 / 46 任务一致；
`lintDebug` 通过；`compileDebugKotlin` 真执行。

### 未做

**真机验收** —— 所有界面改动均未上机；待验收清单见
`dsh-study/实施-2026-10-17/交付说明.md` 第七节。

## 2026-10-17 · 真机验收（首次自己上机）+ 落底判据改为「布局稳定」

### 首次自己上真机

设备：vivo V2080A / Android 11 / 1080×2400。USB 调试经 `usermod -aG plugdev` 后可用
（`plugdev` 组原本是空的，adb 读不到 `/dev/bus/usb/xxx/yyy`）。

装上 v2.2.42（水印 `45a893e`）后逐项走完：

| 项 | 结果 |
|---|---|
| 第一界面是连接页（`connectGate`） | ✅ |
| 配对导入 → 连接 | ✅（`V2080A · 已连接 · 局域网 · http://192.168.1.126:3090`） |
| 会话列表（48 个会话 / 运行中·空闲 / 工作区） | ✅ |
| **时间戳不重叠** | ✅ `10:18` / `10:19` 与上下正文都有清晰间距 |
| **状态条合并** | ✅ 无思考时显示「内容由 AI 生成，请甄别后使用」 |
| **进会话落到底部** | ❌ 仍停在顶部，右下角出现「跳到底部」按钮 |

### 落底为什么前两版都没修好

`snapshotFlow { totalItemsCount }.filter { it > 0 }.first()` —— `.first()` 在**第一次**读到非零时
就返回，而那一次很可能只是「加载更早消息」按钮 + 空态（1~2 项）。滚过去等于滚到那两项；
等真正的消息分批加载完，这个 effect 早就结束了，位置就留在顶部。

真机证据：进会话后右下角出现「跳到底部」的浮动按钮 —— App 自己也知道当前不在底部。

**修**：判据从「有内容」改成「**布局稳定**」——
`snapshotFlow { totalItemsCount }.collect { if (it > 0 && it == previous) 滚到底; previous = it }`，
即**连续两次读到同一个 count** 才认为数据到位（首次进入数据分批到达，count 会连着变好几轮）。

### 过程中的一个操作教训（如实记）

用 `adb input text` 注入 464 字符的配置 token 被输入法吞掉大半，分 4 段注入只有后面几段进去。
**长文本不该自己注入** —— 该请用户在手机上手动输（配对码只有几十字符，一次就成）。
另外「按截图估算坐标」在这台设备上有 ~240px 的系统偏移（状态栏），**应以 `uiautomator dump`
的 bounds 为准**，截图只用来肉眼确认结果。

### 测什么

`:app:testDebugUnitTest` 通过（纯布局时序改动，用例数不变）。

### 未做

- 未上真机复验这一版（打完包立刻让用户装）。
- 未推送、未发版。

## 2026-10-17 · 真机取证后的落底修复：九版教训 + 两条硬判据

### 基于什么

用户直接指出验收标准本身错了：「『跳到底部』按钮消失，本就差一大截就不显示了，你还用它做标准」。

### 这次改了什么（取证驱动，不是猜）

**取证手段**：这台 vivo（V2080A / Android 11）上 **App 的 `Log.i` 一条都不进 logcat**（debug 版也是），
所以改成**屏上可见探针**（会话页底部一行红字，显示 `total/vis/last/first/atBottom` 与 effect 内读数）。

关键的一次读数：

```
外层 total=49 vis=3 last=2 first=0/0 viewport=1719 atBottom=false
内层 count=49 atBottom=false lastIdx=49 total=49 vis=7
```

**它说明了两件事**：collect 正常发射、`count` 也对；但历史**分批加载**期间「视口还在 0、
count 已涨到 49」，于是「不在底部」成立 → 旧代码判定「用户翻过历史」→ **自动滚动永久关闭**。

**数据加载与用户滑动在布局上表现完全一样**，只有 `isScrollInProgress`（真手势）能区分。

### 正确形状

```kotlin
LaunchedEffect(selected.id) {
    // 等列表有内容
    while (totalItemsCount == 0 && guard < 60) { delay(50) }
    // 独立循环：每 32ms 推 600px，scrollBy 返回 0 即到底；用户真在拖动就退出
    while (...) {
        if (listState.isScrollInProgress) return@launch
        if (listState.scrollBy(SCROLL_DOWN_PX) <= 0f) break
        delay(SCROLL_TICK_MS)
    }
}
```

真机实测：`first` 从 **0/0 → 48/4560**（推了三屏多），最后一句出现在屏上。

### 九版教训（全写进源码注释，改动前先读）

1. 「跳到底部」按钮消失 ≠ 落底（判据只是「距底 ≤3 项」，实测差 46 项）；
2. `LaunchedEffect(selected.id)` 只跑一次；
3. `filter { count > 0 }.first()` 首次非零只有「加载更早消息」一项；
4. 「等 count 连续两次相同」——数据分批到达，count 一直在变；
5. **把「不在底部」当「用户滑过」—— 最致命，数据加载与手势无法用布局区分**；
6. `animateScrollToItem(..., scrollOffset=-N)` 对「比一屏高的项」不生效；
7. 每帧只推一次不够（collect 发射次数推不完一屏）。

### 两条硬判据（以后验收都用这个）

- **落底**：`/api/v1/chat/sessions/{id}/messages` 的最后一条消息**末尾文本出现在屏上**，
  且**不需要手动滑动**。
- **不要**用「跳到底部按钮是否消失」。

### 过程中踩到的两个环境事实

- 这台设备上 App 的 `Log.i` 完全不进 logcat（debug 版也不进）→ 调试只能靠屏上可见探针；
- debug 包用 `apksigner` 以发布钥匙重签即可覆盖安装 release 版 → 调试链路打通。

### 测什么

`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**；`docsCheck` 8 文档 / 46 任务一致；`lintDebug` 通过。
探针已全部删除（`grep probeText/innerProbe/DSH_SCROLL` 均为 0）。

## 2026-10-17 · 真机反馈九修：正文与时间戳之间补一层 Column（真凶是 SelectionContainer 的兄弟排布）

### 基于什么

用户第八次反馈，附真机截图：`09:03 从这条分叉` 跑到了**屏幕左边**、与绿色气泡**同一行**重叠。

### 真凶

`ChatItem` 的结构是：

```kotlin
SelectionContainer {
    when (item.kind) { ... }          // 正文（Row / AssistantBody / ActivityRow）
    ChatMessageFooter(item, onForkAt)  // 时间戳 + 分叉入口
}
```

**SelectionContainer 把直接子节点当兄弟排布（inline-like），不是叠成上下。**
所以正文与 footer 一直是**并排**的 —— 之前看不出来，是因为 footer 那时是
`fillMaxWidth()` 的全宽 Text，宽度撑满后看起来像是「在下一行」（其实是被挤到了一边）。

第五版我把它改成 `wrapContentWidth()` 之后，这个并排**暴露了**：宽度不再撑满，
时间戳直接顶到屏幕左边，与气泡并排在同一行 —— 截图里就是这样。

也就是说，前五轮我调的所有 padding（3→6→8→10dp、归零、加回来）**都作用在一个本来
就排错了位的元素上** —— 那些间距从来不是问题的原因。

### 干了什么

1. `SelectionContainer` 内部加一层 **`Column(Modifier.fillMaxWidth())`**：
   正文在上、时间戳与分叉入口在下，各自占一行；
2. `ChatMessageFooter` 内部把时间戳与分叉入口包进一个 **`Row`**：
   `Arrangement.End`（用户消息靠右）/ `Start`（助手靠左），
   内部 Text 用 `weight(1f, fill = false)` 保持宽度自适应 —— 兼顾「不跨出 item 边界」
   与「左右对齐正确」这两件第五版没同时做到的事；
3. 保留 `top = 6dp / bottom = 8dp`（宽度自适应之后它们不再有副作用）。

### 测什么

`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**；`docsCheck` 8 文档 / 46 任务一致；
`lintDebug` 通过。

### 复盘（八轮才找到的原因，值得记住）

| 轮 | 我改了什么 | 为什么没用 |
|---|---|---|
| 1–4 | 时间戳 padding 3→6→8→10dp、消息流 5→8dp、分叉按钮 8dp | 间距不是那里产的 |
| 5 | `wrapContentWidth()` + padding 归零 | **元素本身就排错了位**（与正文并排） |
| 6 | bottom 加回 8dp | 同上 |
| **9** | **加一层 Column + Row 对齐** | — |

教训：改样式前先确认**元素在布局树里的位置**。这次连续四轮都在给一个「排错了行的元素」
调间距。`SelectionContainer` 把子节点当兄弟排布这件事，代码里没有任何注释提示，
只有把结构打出来才看得见。

### 未做

- 未上真机复验；未推送、未发版。

## 2026-10-17 · 真机反馈八修：时间戳底距恢复 8dp + 进会话落底改成「等列表有内容」（未升版本号）

### 基于什么

用户第六次反馈（附截图，水印 `ac4ac01` 已对上）：「图片中文字全部修好了 上 显示的时间和文字重叠，
进会话默认在顶部 没修好」。

### 两个根因

**① 时间戳仍重叠**：上一版把 footer 的 padding **全归零**了（以为间距该由 LazyColumn 的
`spacedBy(8.dp)` 独占）。但时间戳是 `labelSmall`（行高约 13dp），对一条文字来说只剩
`spacedBy(8.dp)` 太紧 —— 截图里 `08:44` 与下方正文几乎贴着。
上一版（`bottom = 10dp` + `spacedBy(5.dp)` = 15dp）其实比这版宽，是我把有效的部分删掉了。
**修**：`wrapContentWidth()` 保留（这才是根治：行高不再横向跨出 item 边界），
`bottom = 8dp` 加回来 —— 有了 wrapContentWidth 之后 bottom 不会再被推出边界，可以放心加。

**② 进会话仍停在顶部**：`LaunchedEffect(selected.id)` 只在 id 变化时跑**一次**。
首次进入 `ChatDetail` 时历史往往还没回来（`displayItems` 为空），守卫 `isNotEmpty()`
直接 return —— 之后**再没有第二次触发**，位置就停在顶部。前两版都栽在「只跑一次」上。
**修**：改用 `snapshotFlow { layoutInfo.totalItemsCount }.filter { it > 0 }.first()` ——
等列表**真的有内容**的那一刻才滚一次，然后收工。

### 测什么

`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**；`lintDebug` 通过。
（补了 `kotlinx.coroutines.flow.filter` / `first` / `runtime.snapshotFlow` 三个 import。）

### 未做

- 未上真机复验；未推送、未发版。

## 2026-10-17 · 真机反馈七修：时间戳行高不再横向跨出 item 边界（未升版本号）

### 基于什么

用户第五次反馈「显示时间与消息重叠」（并确认了那个时间就是**消息时间戳 hh:mm**）。

### 为什么（五轮迭代的账，全部留在这里）

| 版 | 改法 | 结果 |
|---|---|---|
| 1 | 时间戳加 `bottom = 6dp` | 无效 |
| 2 | `bottom = 10dp` | 无效 |
| 3 | `top = 8dp` + `bottom = 10dp` | 无效 |
| 4 | 给分叉按钮行加 `bottom = 8dp` | 无效 |
| **5** | **`wrapContentWidth()` + padding 归零** | 待真机复验 |

前四版全错在同一个地方：**间距根本不是 padding 产的**。item 之间由 LazyColumn 的
`spacedBy(8.dp)` 负责，而时间戳是一条 `fillMaxWidth()` 的**全宽 Text** ——
它的行高横向延伸到整个 item 边界，与下一个 item 的气泡顶部叠在一起。
在这种情况下加 padding 只会把行高推得更出界，越加越糟。

### 干了什么

`ChatMessageFooter`：
1. `fillMaxWidth()` → **`wrapContentWidth()`**：行高只包住文字本身，不再横向延伸；
2. **上下 padding 归零**（只留 `top = 6dp` 与气泡分开）：与下一条消息的距离完全交给
   LazyColumn 的 `spacedBy(8.dp)`，不再重复加；
3. 分叉按钮那行同样去掉 `bottom = 8.dp`。

### 过程中犯的错（如实记）

本想「把时间戳搬进气泡的 Column」——那是更彻底的结构性修法，但对
`ChatMessageItems.kt` 的两次文本插入把 `when` 分支弄错位了（多一个 `}`、`Alignment.End`
落进 Row、`ChatItemKind` 未解析）。**已 `git checkout` 恢复该文件**，改用上面的最小侵入方案。
教训：对这个 756 行、缩进不规律的 `when` 文件做结构性搬移，不该用文本替换硬插。

### 测什么

`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**（纯布局改动，用例数不变）；
`docsCheck` 8 文档 / 46 任务一致；`lintDebug` 通过。

### 未做

- 仍未上真机复验（用户已反馈前四版无效）。
- 若这版仍不行，下一步应做**结构性修法**：把时间戳搬进气泡的 Column
  （需要小心处理 `ChatMessageItems.kt` 的 `when` 结构）。

## 2026-10-17 · 真机反馈六修：进会话落到底部 + 时间与消息重叠（未升版本号）

### 基于什么

用户反馈（附截图，红色圈出）：「**显示时间与消息重叠**」+「每次进入会话，默认在当前页面顶段，
不符合对话性智能体的特点」。

### 为什么

**进会话停在顶部**：`rememberLazyListState()` 的初始项就是 0，而原有的自动滚动守卫是
`isAtBottom` —— 进会话时列表已经有内容、`isAtBottom` 为 false，于是那条不触发，
位置就停在顶部。（流式期间能滚到底，是因为那时 `isAtBottom` 恰好为真 —— 所以以前没发现。）

**时间与消息重叠**：时间戳是一个占满一行的 `Text`，**分叉按钮**是紧随其后的另一个 `Row`，
而那个 Row 没有底部间距、`TextButton` 的 `contentPadding` 又是 `vertical = 0.dp` ——
两者连成一坨，再贴上下一条消息。前几版一直在改 footer 的 `top/bottom` 与消息流间距，
**找错了对象**（该改的是分叉按钮那一行）。

### 干了什么

1. **滚动状态跟着会话走**：`listState = key(selected.id) { rememberLazyListState() }`
   —— 换会话即重建，不再继承上一个会话的位置；
2. **进会话直接落底**：新增 `LaunchedEffect(selected.id)`，有内容就
   `animateScrollToItem(totalItemsCount - 1)`（不用瞬间跳转，给几百毫秒滑动，
   用户能看出「我在往下走」而不是「页面被重置」）；
3. **分叉按钮行补底部间距** 8dp（与消息流 `spacedBy(8.dp)` 同值）；
4. `ChatActivityRow` 的 Column 补 `bottom = 8.dp`（工具行与下一条消息之间）。

### 踩到并修掉的两个编译错误（如实记）

- `remember(selected.id) { rememberLazyListState() }` —— 那个 lambda 不是 @Composable 上下文，
  里面调不了 `rememberLazyListState()`；
- `key(...)` 忘了 import `androidx.compose.runtime.key`。

最终用 `key(selected.id) { rememberLazyListState() }`（Compose 的 `key` 正是为「按 key 重建」
设计的），并把这个取舍写进注释。

### 测什么

`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**（用例数不变，本次是纯布局改动）；
`docsCheck` 8 文档 / 46 任务一致；`lintDebug` 通过。

### 未做

- **保留用户的滚动位置**（进会话时如果上次滑到中间，是否该回到中间）—— 当前是「总是落底」，
  这是对话型产品的常规取舍；若你想改成「记住每个会话的位置」，说一声我加（需要按会话存 index）。
- 未上真机复验。

## 2026-10-17 · 真机反馈五修：状态条合并（思考 / AI 标识二选一）（未升版本号）

### 基于什么

用户要求：「把『内容由 AI 生成，请甄别后使用』与显示思考过程的重叠在一栏显示，
有思考内容的时候显示思考内容，没有的时候再显示这个」。

### 为什么

原来两者是**并排的两行**（思考条 + 标识条，都在「消息区下方 / 输入区上方」那条窄带里）：
思考一来就把标识挤到上面去，那一栏就散了。

### 干了什么

新增 `ChatStatusBar(liveThinking)` 替掉原来的两行调用：
判据是 `thinkBarText(liveThinking).isNotEmpty()` —— 为真渲染思考条（可展开），
为假渲染 AI 标识条。**同一栏、同一位置，二者只居其一。**

为什么不是「标识常驻、思考叠上去」：那一栏只有一行多的空间，两条各占一行必然互相挤压；
而思考是**短生命周期的运行状态**（只在流式期间存在），标识是**常驻**的合规提示 ——
让短生命周期的让位才划算。

### 测什么

新增 `ChatStatusBarTest`（**4 例**）：有思考→显示思考、空白串算无思考、
只有空白的 reasoning 段（流式首帧常见）不顶掉标识、判据与 `thinkBarText` 一致。
`:app:testDebugUnitTest` **47 类 / 425 用例 / 0 失败**；`docsCheck` 一致；`lintDebug` 通过。

### 一个被测试抓到的断言错误（如实记）

首跑红一条：`expected:<140> but was:<141>` —— `thinkBarText` 截断后是「省略号 + 尾部 140 字符」
= **141 字符**，我断言成 140。**实现是对的、断言错**，已修断言并拆成两段
（总长 141 + 内容恰为 140 个 `a`）把语义钉住。

### 未做

- 思考与标识**同时出现**（并排显示）—— 用户明确要求二选一，故不做。
- 未上真机复验。

## 2026-10-17 · 真机反馈四修：表格补表头与边框（像一张表）+ 时间戳上距（未升版本号）

### 基于什么

用户装上带水印的包（确认 `构建 0044f2c`，即装的就是最新包）后第三次反馈：
「**表格不像表，时间重叠还是重叠**」，并附两张截图。

**关键**：水印证明包装对了 —— 所以这次不是「没生效」，而是**我的渲染稿不对**。
截图里那些「证据 / 结果」行正是我的两列表格在渲染：**代码生效了，但看起来就是普通文字行**，
缺表头行、缺边框、缺列间竖线。

### 为什么（四版稿的演进，都留在代码注释里）

1. 第一版用渲染库默认表格 → 横向滚动 + 固定列宽，手机上一宽右侧被切掉；
2. 第二版改「一字段一张竖排卡片」→ 反馈「没可读性了」（多字段行要跳着看）；
3. 第三版改「两列 + 无表头」→ 反馈「不像表」（就是普通文字行）；
4. **第四版（本轮）**：保留两列布局（左字段名窄、右值宽且折行），补上
   **表头行**（深色底、与数据行分明）、**外框边**、**行分隔线**与**列间竖线**。

折行与表格感**两个都要**，不是二选一 —— 这是前三版反复的教训。

### 时间戳：上距也拉开了

前三版都只加 `bottom`（与下一条的距离），结果时间戳贴着**上方正文/表格底部** ——
那也是一种「重叠」。这次 `top = 8.dp`（与上方内容分开）+ `bottom = 10.dp`（与下一条分开）。

### 测什么

`:app:testDebugUnitTest` **46 类 / 421 用例 / 0 失败**（表格解析测试 10 例不变）；
`docsCheck` 8 文档 / 46 任务一致；`lintDebug` 通过。

### 未做

- **列宽按内容分配**（现在第一列是固定上限 104dp，长字段名会被省略成两行）——
  要按内容算宽就得自己量文本，Compose 里可以用 `SubcomposeLayout` 做，但那是另一处复杂度；
- 单元格内行内 Markdown（`**加粗**`、`` `代码` ``）；
- 未上真机复验。

## 2026-10-17 · 真机反馈二修：表格不生效的根因是 flavour（未升版本号）

### 基于什么

用户第二次反馈（附图）：「图1是当前APP的，**时间还是重叠，表格没可读性了**；图2是 whaleness 的」。

### 根因：表格回调从来没被调用过

第一版只加了 `MarkdownComponents.table` 覆盖，却没给 `Markdown(...)` 传 flavour ——
该库默认用 **CommonMark**，而 CommonMark **不解析 GFM 表格**，所以表格节点压根不存在，
`table` 回调**永远不会被调用**，表格被当成普通段落渲染（图1 里「阶段/做什么/目的」竖排密排）。

这个坑咬了两遍：第一遍以为「库自带表格会横滚截断」就加了覆盖（无效）；第二遍补 flavour 时漏了其中一处调用。
已新增 `MarkdownFlavourTest`（源码级守卫：每处 `Markdown(` 必须传 `GFMFlavourDescriptor()`），
并做**变异验证**（删掉 flavour → 红、恢复 → 绿）—— 这个坑 UI 测试抓不到，只能这样守。

### 表格样式按图2 重做

第一版是「一字段一张竖排卡片」，被反馈「没可读性了」。对照 Whaleness 后改成**两列**：
左字段名（窄列、最多两行省略）、右值（宽列、自然折行），字段间细分隔线、整块浅底不浮起 ——
与网页端表格的阅读顺序一致，长值也不会被切掉。

### 时间戳二修

6dp 那版仍嫌挤（用户：「时间还是重叠」）。这次两处一起：
`ChatMessageFooter` 的 `bottom = 10.dp`（与下一条拉到 15dp），消息流 `spacedBy(5 → 8)`。

### 测什么

`:app:testDebugUnitTest` **46 类 / 421 用例 / 0 失败**（新增 `MarkdownFlavourTest`）；
`docsCheck` 8 文档 / 46 任务一致；`lintDebug` 通过。

### 未做

- 表格**列宽按内容分配**（现在字段名列是固定上限）与单元格内行内 Markdown。
- 未上真机复验。

## 2026-10-17 · 真机反馈修复：表格换行 + 时间戳间距（未升版本号）

### 基于什么

用户装上 v2.2.42 测试包后的真机反馈（附截图），三条：

1. 「执行了命令下面显示的**时间与下一条消息重叠**」
2. 「渲染的**表格直接截断**，应该自动换行」
3. 「页面布局适当参考图片（我们的功能几个设置功能整合继续保持）」

### 为什么

- **时间戳**：`ChatMessageFooter` 里时间戳只有 `padding(top = 3.dp)`、**没有底距**，
  而消息流本身只有 `Arrangement.spacedBy(5.dp)` —— 于是时间戳与下一条消息之间只剩 5dp，
  视觉上就是「贴在一起」。加 `bottom = 6.dp` 后变成「与正文 3dp、与下一条 11dp」，层级分开。
- **表格**：渲染库 `com.mikepenz.markdown` 自带的表格是**横向滚动 + 固定列宽**
  （内部 `tableMaxWidth` / `widthIn` 那一套），手机上表格一宽右侧直接被切掉。
  该库的 `MarkdownComponents` 是开放接口（有 `getTable()`），所以不必换库、不必 fork ——
  覆盖 `table` 这一项即可。

### 干了什么

- **表格改成纵向卡片**（新 `ui/chat/ChatMarkdownTable.kt`）：一行数据一张卡片，卡内是
  「字段名 + 值」的键值对，**长值自然折行**（刻意不设 `maxLines` —— 库那边截断的根源正是固定列宽）。
  选纵向而不是「加横向滚动」，是因为手机上滑多列表格每次只能看到两三列、还要一边滑一边记列名。
- **解析用文本而非 AST**：`MarkdownComponentModel.content` 拿得到原文，按 `|` 切分即可；
  这套规则**可单测**（分隔行、空单元格、列数不齐、转义竖线都是边界）。
  列数不齐时**补齐空单元格**而不是截断（截断会丢数据）；解析失败则回落到 `MarkdownText`，不吞内容。
- **布局**：对照截图核过，顶栏（返回/标题/模式/⋯）、「对话 / 轨迹」分段胶囊、输入区药丸、
  行内代码徽章都已在位；本轮只修上面两处影响阅读的问题，**功能整合保持不变**。

### 测什么

- 新增 `ChatMarkdownTableTest`（**10 例**）：常规表格、必须有分隔行、对齐冒号、无数据行、
  列数不齐补齐/截断、**转义竖线**、**Windows 路径不被吃掉**、空单元格、表格前后有别的文本、
  完全无表格。
- `:app:testDebugUnitTest` **45 类 / 420 用例 / 0 失败**；`docsCheck` 一致。

### 一个被单测抓到的真实 bug（如实记）

首跑红了一条：实现第一版见 `\` 就消费后一个字符，于是 `F:\work` 被吃成 `F:work`。
**而用户反馈的表格里恰好全是这种 Windows 路径** —— 单测边界与真机反馈互相印证，
已加回归用例（`keeps windows paths intact`）守住。

### 未做

- 单元格内的**行内 Markdown**（`**加粗**`、`` `代码` ``）仍是纯文本 —— 本轮只解决「折行不被截断」。
- 表格**列宽对齐**（纵向卡片天然不对齐），以及超宽表格的横向兜底。
- 未上真机复验。

## 2026-10-17 · 把「中英 strings 一致性」并入常设门禁（不升版本号）

### 基于什么

自检脚本的第 ② 项（当前 453 / 453 双向对齐），以及这条脉络上的前一项（坑位索引）。

### 为什么这类问题值得一条门禁

1. **英文缺键** → 切到英文界面时那几个字符串**显示中文**（或反之）。这类缺口只在
   「恰好切到英文、恰好用到那个界面」时才被发现；而当前的 453/453 对齐是**手查**出来的。
2. **占位符不一致** → 更严重：调用处按中文的占位符个数传参，英文若少一个 `%1$s`，
   `String.format` 会抛 `MissingFormatArgumentException` —— **这是崩溃，不是显示问题**。

### 干了什么

新增 `StringResourcesTest`（**2 例**）：

- 键集合**双向**一致 + **下限断言**（两边各 ≥ 400 条，防「解析失败」被当成「一致」）；
- 占位符一致：提取 `%s` / `%1$s` / `%1$.2f` 之类（`%%` 不算），只比两边**都有**的键
  （缺键交给上一条）。

**用 XML 解析器而不是正则**读 `strings.xml`，理由写在代码注释里：合法的转义、跨行文本、注释、
`<xliff:g>` 标记都会让正则写出「看起来能用、边界上悄悄漏键」的实现 —— 而那恰恰是这条门禁
要防的失败模式。

### 过程中的一个坑（如实记）

第一次跑两个用例都红：我拼路径时把**仓库根**直接当成了资源目录
（`…/dsh-companion/values/strings.xml`），少了 `app/src/main/res` 一段。
**这次是测试对、实现错** —— 与本轮前几次同型（我的实现与锚点，比测试更容易出错）。

### 测什么

- `:app:testDebugUnitTest` **43 类 / 409 用例 / 0 失败**（+1 类 +2 例）。

## 2026-10-17 · 把「坑位索引一致性」并入常设门禁（不升版本号）

### 基于什么

`dsh-study/实施-2026-10-17/自检脚本.py` 的第 ⑤ 项：`AGENTS.md` 的坑位索引与
`docs/project/PITFALLS.md` 的正文标题**双向一致**。

### 为什么

那条检查原先只在**手动跑脚本**时生效。索引漂移（照索引去翻、翻不到条目）是读者最容易踩的一类，
应该由门禁抓，而不是靠我某天想起来查一次。

### 干了什么

在 `ArchitectureGuardTest` 里加一条测试 —— 它已经在守行数预算、`@patch` 五要素、网络边界，
是同一类「机检文档纪律」的地方。

**判据上有三处讲究**（每条都对应一次真实的踩坑）：

- **双向**：只查「索引 → 正文」时，索引一旦解析失败（0 条）会永远满足、被判成通过；
- **下限断言**（≥10 条）：第二条保险 —— 小节格式变了导致只解析出几条时，双向检查可能
  「双双为空而一致」，下限直接对着「解析失败」报警（姊妹仓同一检查第一次跑就是靠它抓出 bug 的）；
- **不按编号找小节**：本仓索引在 §7、姊妹仓在 §6 —— 写死编号必然有一边解析成空。

### 过程中的一个坑（如实记）

第一次把测试**插错了位置**：我用的锚点里那个 `}` 其实是 `readDebtRegistry` 的结束括号，
被替换吃掉后测试落进了那个函数体内，编译成局部函数 `readDebtRegistry$坑位索引…`，
JUnit 直接报 `initializationError`。

教训与 `PITFALLS.md` §论证要先追到消费侧 同族：**拿 `}` 当锚点，必须先确认那是哪个块的 `}`**。

### 测什么

- `:app:testDebugUnitTest` **42 类 / 407 用例 / 0 失败**（+1 例）；`docsCheck` 一致。

## 2026-10-17 · Clock/Transport 接缝：把 flaky 的根因去掉（DEBT-007 清账）（不升版本号）

### 基于什么

B 批清单里的「Clock/Transport 接缝」。这一项原本被我判为**「做了也没人用」** ——
理由是本项目 ViewModel 层没有测试基建，接缝做出来没有消费者。直到上一轮**真的撞上一条 flaky**
（`ConnectionCoordinatorTest` 的延迟容差用例偶发红、单跑即绿），它才有了明确的收益。

### 病根（DEBT-007）

`ConnectionCoordinator` 用 `System.nanoTime()` 量**真实墙钟**耗时，而选路比较的正是那些实测值。
测试用 `MockResponse.setBodyDelay(300)` 造延迟，断言「局域网 300ms vs 虚拟网 220ms，
容差 1.5× 内局域网胜出」—— 机器带着 Gradle 编译负载时，两次实测会**一起飘**，
边界（300 vs 330）就翻车。

### 干了什么

1. `ConnectionCoordinator` 加构造参数
   `measureOverride: (suspend (DshClient) -> ProbeOutcome)? = null`，默认 null = 原墙钟实现。
   原逻辑**逐字搬进** `probeWithWallClock` —— 连它踩过的两个坑一起
   （只给 `Deferred.await()` 设 deadline，因为协程取消打不断阻塞的 OkHttp 调用；
   等待时同时监听关闭信号）。
2. **接缝的边界画得很清楚**：它只替换「**怎么测这一次**」。超时、关闭与取消语义
   **仍由协调器负责** —— 那些是「保证不挂死」的部分，被测试替身绕过去就等于把最有价值的保护测没了。
3. 三个依赖**精确延迟比较**的用例改为注入 `fixedLatency(...)`（按地址前缀给固定耗时）；
   另外两个（并发性、挂死超时）**保留真实延迟** —— 它们测的就是真实行为，替换掉反而失真。
4. `ProbeOutcome` 提为 **public**：协调器是 public 类，构造参数的类型不能比它更窄
   （Kotlin 会直接拒绝「public 函数暴露 internal 类型参数」）。

### 测什么

- 用例数与断言数**不变**（改的是既有用例的**测量方式**，不是新增断言），
  **42 类 / 406 用例 / 0 失败**。
- 选路规则本身仍被完整考察：断言里既检查选中的地址，也检查
  `EndpointSelection.rank(...)` 的首位 —— 换掉测量方式没有绕过被测逻辑。

### 清账

**DEBT-007 已删**（制度：条目对应的债务消失，同一提交里删掉该条目）。
它当天立、当天清，留下的教训写进了台账：**flaky 测试比没有测试更坏** ——
它教会人「红了先重跑一次」，于是真的回归也会被重跑掩盖。

## 2026-10-17 · 轨迹页 UI：按轮次的时间轴 + 原始载荷下钻（不升版本号）

### 基于什么

上一轮的 `data/Trajectory.kt`（数据层），以及 Whaleness 舒适度清单的**最后一项**。

### 干了什么

1. 新增 `ui/chat/ChatTrajectorySheet.kt`：按轮次的时间轴 —— 每轮一个表头
   （第 N 轮 · N 个工具调用 · 耗时），下面逐行是节点（`#seq` + 类型名 + 工具标签）；
   **点节点展开它的原始 JSON**（复用既有的 `ChatCodeBlock` 做高亮）。
2. 数据层补 `TrajectoryNode.raw`（事件的原始 `data`）—— 轨迹是排查工具，
   用户要看到「当时**真正**收到了什么」；只给解析结果等于把我们不认识的字段永久藏起来。
3. `shortDuration()`：不足一分钟给 `12.3s`，否则 `3:05`。**刻意不做成 strings 资源** ——
   时间数字在各语言里的写法基本一致，做成资源会让这个纯函数失去可测性（要 Android 环境才能取字符串）。
   理由写在函数注释里。
4. ⋯ 菜单加「轨迹」入口（挨着「会话统计」，同为查看类操作）。

### 又被行数守卫抓到一次（第三次）

`ChatDetail.kt` 加到 **810 行**（超预算 10 行）。按制度拆：把整个 **`ConversationHeader`**
（含 ⋯ 菜单，163 行）搬到新文件 `ui/chat/ChatHeader.kt` —— 它本来就自成一体，
只依赖 `HarnessState` 与几个回调，与消息流渲染零耦合。

结果：`ChatDetail.kt` **810 → 636**，`ChatHeader.kt` **214**。
搬家时顺手清掉 ChatDetail 末尾一段**孤儿 KDoc** —— 那是 `SessionFilesSheet` 的注释，
而它的函数体在 `ChatSessionFiles.kt`，显然是复制粘贴留下的死注释。

> 三次超预算都是**守卫先发现、我后知道**。这条门禁的价值已经不需要再论证了。

### 测什么

- `TrajectoryTest` 加 2 例（**共 10 例**）：原始 `data` 原样带上（未知字段不能被吞掉）、
  短耗时的两种形态与边界（`59.9s` / `1:00`）。
- `:app:testDebugUnitTest` **42 类 / 406 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 未做（以及为什么）

- 节点详情的**多 Tab**（竞品有摘要 / 参数 / 结果 / Schema 五栏）：本轮**只做「原始」一栏**。
  其余几栏需要按工具类型解析载荷，而「参数 / 结果 / Schema 分别挂在哪个字段」在本仓**不可证**。
  先给最有用的那一栏（原始语义 = 我们看到的全部），而不是做几个猜出来的 Tab。
   若将来能从真实响应确认字段路径，再按类型补 Tab 即可（`TrajectoryNode.raw` 已经把它们都带着）。
- 未做真机：sheet 观感与长 JSON 的渲染开销要上机看。

## 2026-10-17 · 轨迹页数据层：把历史按轮次折成时间轴（不升版本号）

### 基于什么

Whaleness 舒适度清单里的最后一项「轨迹第二页」（按轮次的节点时间轴 + 节点详情）。

### 干了什么（本轮只做数据层，UI 留下一轮）

1. 新增 `data/Trajectory.kt`：
   - `trajectoryOf(history)` → `List<TrajectoryTurn>`：按 `turn` 分组、组内按 `seq` 排序
     （**事件数组的顺序不可信**，不能依赖它）；
   - `TrajectoryNode(seq, time, kind, label)`，kind 覆盖 User / Assistant / Tool /
     Deliverable / TurnEnd / **Other**；
   - **不认得的事件类型留成 `Other`，不丢弃** —— 轨迹页的价值恰恰是「把当时发生的都列出来」，
     过滤它等于让它失去意义。这与会话流「只留给人读的」正好相反，注释里写明了这层区别。
2. `durationMillis`：**只在时间戳随序号单调时才给值**（见下）。
3. 工具标签只取 `data.name`，取不到就 `null` —— **不猜**多个候选字段名：猜错会显示一个假的
   工具名，而「不知道」是可接受的展示。

### 测什么（一条测试纠正了实现）

- 新增 `TrajectoryTest`（**8 用例**）：分组与组内排序、归属不明的事件进 `UNKNOWN_TURN`
  且**不丢弃**、未知类型留 `Other`、五种已知类型映射、无名字的工具返回 null、
  正常跨度、**乱序/零跨度都返回 null**、空历史。
- **首跑红了一条，是测试对、实现错**：我原本用 `min/max` 算耗时，于是「时间戳乱序」时
  会给出一个正数跨度 —— 但那是「最晚减最早」，与「这一轮花了多久」不是一回事。
  改成**只在时间戳随序号单调时才给值**（乱序说明这份历史的 `time` 不可信）。
- `:app:testDebugUnitTest` **42 类 / 404 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 顺手发现一条 flaky（已立账）

跑全量时 `ConnectionCoordinatorTest` 的延迟容差用例红了一次
（`expected:<lan> but was:<vnet>`），**单跑即绿** —— 它比较的是两种连接方式的**实测延迟**，
机器带着 Gradle 编译负载时会飘。已记入 `TECH-DEBT.md`（**DEBT-007**），清账路径指向 B 批的
**Clock/Transport 接缝**：那条原本被我判为「做了也没人用」的接缝，现在有了具体收益。
台账里同时写了一句：**flaky 测试比没有测试更坏** —— 它教会人「红了先重跑一次」，
于是真的回归也会被重跑掩盖。

### 未做

- 轨迹**页面 UI**（时间轴 + 节点详情）留下一轮。
- 未做真机。

## 2026-10-17 · 交付物实时到达：文件卡片不再等下一次刷新（不升版本号）

### 基于什么

姊妹仓同轮给 `deliverables/presented` 补了正式事件名 `chat.deliverables.presented`
（此前它落进默认分支，被规范化成客户端不认识的名字）。

### 为什么这是「补实时性」而不是「做功能」

客户端**早就**能从历史里读出交付物：`ChatHistory.deliverables()` 与文件卡片
（`ChatSessionFiles.kt`）一直在读内核的持久事件 `deliverables/presented`。
缺的只是**实时通道** —— 新交付物要等到下一次消息完成触发刷新才出现（延迟几十秒到几分钟）。

### 干了什么

`connectEvents` 加 `chat.deliverables.presented` 分支 → `scheduleChatRefresh()`。
卡片数据在历史里，刷新即出现；去抖与单飞由既有机制负责。

### 未做（如实记）

- 这条分支**没有单测**：ViewModel 层在本项目没有测试基建，其余事件分支同样如此。
- 未做真机：要真机上让智能体 `present` 一个文件，看卡片是否**立刻**出现。

## 2026-10-17 · 会话统计：能数的都数出来，数不出的明说（不升版本号）

### 基于什么

C 批清单里的「会话统计 / 上下文用量」，同时也是 Whaleness 舒适度清单里的**上下文用量环**。

### 先核实数据 —— 这项的结论决定了做多少

| 想要的数据 | 本仓可得吗 |
|---|---|
| 模型上下文窗口 | ✓（`models` 视图的 `contextWindow` / `maxTokens`） |
| **当前已用 token / 上下文压力** | ✗ **不可证**：需要内核给 usage，而「历史事件里有没有 usage 字段」从本仓库查不到（`node_modules` 下没有内核包），研究报告里也没有记录 |
| 执行维度（轮次 / 助手消息 / 工具调用 / 事件数） | ✓ 完全可算 —— `ChatHistoryMapping.kt` 已经在按这些事件类型分派渲染，判据**同源** |

### 干了什么

1. 新增 `data/SessionStats.kt`：`sessionStatsOf(history)` 数出轮次 / 助手消息 / 工具调用 /
   事件总数 / **数据截至 `#seq`**（竞品统计表页脚那句「数据截至事件 #N」就是它，用来判断统计新鲜度）。
2. 新增 `ui/chat/ChatStatsSheet.kt`：会话页 ⋯ 菜单里加「会话统计」——
   放在菜单最前，与「模型 / 权限 / 插话」这些**改变**类操作分开。
3. **面板里明说用量拿不到**，并解释为什么**不给估算值**。

### 为什么不做「按字符数估算 token」

误差可能成倍（不同 tokenizer；而且系统提示词与工具定义客户端根本看不到），
而用户会拿它当真实占用做决策（「我还有多少余量」）。
**一个不可靠的数字比没有数字更坏** —— 它把「不知道」伪装成「知道」。

### 测什么

- 新增 `SessionStatsTest`（**4 用例**）：各类型计数、**未知类型只进总数不落明细**、
  新鲜度取**最大序号**而非最后一条（事件顺序不该被当成前提）、空/缺失历史返回空统计。
- `:app:testDebugUnitTest` **41 类 / 396 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 未做（以及为什么）

- **上下文用量环没做**：它是目标里明确列的一项，但**没有数据就不该画一个环** ——
  一个永远显示 0% 或者凭空估算的环，比不显示更误导。若将来能确认内核的 usage 字段，
  `SessionStats` 就是加字段的天然落点（面板不用改结构）。
- 未做真机：菜单项与面板观感要上机看。

## 2026-10-17 · 断档检测与 resync：漏掉的那一段自己会补（不升版本号）

### 基于什么

C 批清单里的「断档检测与 resync」，消费姊妹仓同轮上线的订阅基线
（`chat.session.snapshot` 的 `cursor`）。

### 为什么

WS 是「订阅**之后**才收得到」，REST 历史是「发请求**那一刻**的快照」——
两者的时间差里发生的事件**谁都管不着**。此前客户端连「我漏了一段」都判断不出来：
它没有任何参照点，于是那段历史永远缺着，用户只觉得「怎么少了一句话」而无从追问。

### 干了什么

1. 新增 `data/ChatSeqMonitor.kt`：`seqVerdict(lastSeq, incomingSeq)` 与
   `baselineBehind(localMaxSeq, cursor)` —— 覆盖两种断档：**事件流里跳号**、
   以及**本地历史落后于订阅基线**。
2. `HarnessState` 加 `lastEventSeq` / `baselineCursor`；`selectSession` **必须清空它们** ——
   不同会话各有自己的序号序列，拿 A 的序号去比 B 的事件会**必然误判**，
   还会连带触发一次没必要的全量重取。
3. `connectEvents`：所有 `chat.*` 事件统一过一遍跳号判定；`chat.session.snapshot` 分支
   记录基线并与本地最大 seq 比较。命中就 `scheduleChatRefresh()` ——
   它自带 400ms 去抖 + 单飞，连续跳号也只会合并成一次。

### 判据两头都不能错（这条写进了测试）

- **判太松**（漏判）→ 用户的历史永远缺一段，而且他不知道为什么；
- **判太紧**（误判）→ 每次重连都全量重取一次历史，白烧流量与电。

所以**重复与倒退明确判成 Continue**（那是去重该处理的事），只有**严格跳号**才算断档。

### 测什么

- 新增 `ChatSeqMonitorTest`（**8 用例**）：连续、跳号、重复、倒退、
  无序号（流式 delta 自 ADR-0008 起不再带 `seq`）、无起点、
  基线的三种比较（落后 / 相等 / 本地更新）、`cursor` 未知。
- `:app:testDebugUnitTest` **40 类 / 392 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 未做 / 边界

- **未做真机**：真机上要人为构造「订阅窗口漏事件」才能看到 resync 生效；
  单测覆盖的是判据本身，端到端仍要上机。
- **不需要能力位守卫**：旧服务端不发 `chat.session.snapshot` ⇒ `baselineCursor` 恒为 null
  ⇒ `baselineBehind` 返回 false ⇒ 天然兼容（这比加一个"支持吗"的分支更省事，也更难写错）。

## 2026-10-17 · B 批：历史投影不再每帧重算（顺带又拆了一个超预算文件）（不升版本号）

### 基于什么

B 批清单里的「增量投影」。

### 先核名词

「增量投影」听起来是「把 history 增量合并、不整份替换」。但在本仓，真正烧 CPU 的不是网络往返
（那一路已有 400ms 去抖），而是**每次重组都重跑一遍投影**：`ChatDetail` 的 Composable 体里直接调
`state.history?.displayItems()` —— 而 `displayItems()` 是**逐事件映射的纯函数**。
流式期间每秒几十帧，每帧都要把几百条历史事件重新映射一遍，再跑两次 filter。

### 干了什么

1. 新增 `data/ChatVisibleItems.kt`：`visibleHistoryItems(history, showContext)` 薄壳 +
   可测核心 `visibleItemsOf(items, showContext)`。抽出来有两个收益：
   **规则可测**（「思考永不显示」「上下文按开关」这两条口径此前只靠肉眼守），
   以及让上层只剩一个 `remember`。
2. `ChatDetail` 改为 `remember(state.history, showContext) { … }` ——
   `state.history` 是整份替换的对象（只有刷新才换），引用相等就是「没变」的可靠判据；
   `todos` 同样缓存。
3. **`liveItems` 刻意不缓存**：它每一帧都会变（`append` 出的是新对象），加了也是每帧失效的
   假优化；而它只有当前 step 的那几个块，成本本来就小。注释里写明了，免得后人来「顺手补上」。

### 测什么

- 新增 `ChatVisibleItemsTest`（**4 用例**）：思考永不显示（开关两种取值都验）、
  上下文只在开关打开时出现、顺序原样保持、空历史不炸。
- `:app:testDebugUnitTest` **39 类 / 384 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 又被行数守卫抓了一次（第二次）

`ChatDetail.kt` 797 → 加上投影缓存后越过 **800**。按制度继续拆：把「滚动期间冻结流式快照」
整段（28 行注释 + 代码）抽到 `ui/chat/ChatScrollFreeze.kt` → **781 行**。
这段本来就自成一体（只做一件事：把数据源与渲染快照分开），搬家顺手把它的取舍说明也集中到一处。

> 两次超预算都是**守卫先发现、我后知道** —— 这说明它值得存在：靠人记「这个文件快满了」不可靠。

## 2026-10-17 · B 批：事件去抖（file 事件风暴不再变成请求风暴）（不升版本号）

### 基于什么

B 批清单里的「有界事件队列」。**动手前先核了一件事**：我们的架构里其实**没有事件队列** ——
事件在 OkHttp 的读线程上直通 `update`，而 `LiveChatState` 是按 step 分块的（`append` 只改
对应 index 的块），天然有界。照着名词抄一个队列没有意义。

真正**无界**的是**事件驱动的副作用**：`if (event.type.startsWith("file.")) loadDirectory()` ——
每个 `file.*` 事件发一次 `entries()` 请求。内核批量改写文件（构建产物、格式化多文件、
同步一批文件）时会连着推几十帧，于是「事件驱动的界面」变成了**事件驱动的轮询风暴**。

### 干了什么

1. 新增 `data/EventDebouncer.kt`：**尾沿去抖**（安静满窗口才执行一次，风暴期间零请求）。
   - 抽成独立小类而不是写在 ViewModel 里：ViewModel 需要 Android 环境，根本测不了；
     这个类只要一个 `CoroutineScope` + 窗口毫秒数，就能用**虚拟时间**把语义钉死。
   - 实现是「取消上一个未到点的窗口 = 重置计时」，**不需要额外的 pending 标志** ——
     标志与 job 是同一件事的两种表达，留两个会出现「标志说该跑、job 已被取消」的分裂状态。
2. `HarnessViewModel.directoryDebouncer` 接到 `file.*` 分支；`onCleared` 里 `cancel()`。
3. 窗口取 **500ms**（比 history 那一路的 400ms 略长）：目录变化没那么紧急，
   用户正在浏览时多看半秒旧列表完全无感。

### 测什么（实现被自己的测试抓出一次语义错误）

- 新增 `EventDebouncerTest`（**5 用例**，全部跑在 `runTest` 的**虚拟时间**上）：
  风暴塌缩成一次、事件不停时窗口一直重置、分开的批次各算各的、执行后再来的事件另起窗口、
  cancel 后不执行（且下一次仍走完整窗口）。
- **首跑红了两条，抓出的正是实现里的语义错误**：第一版我写成了**节流**
  （首次触发后 400ms 就执行），而正确语义是**去抖**（安静 400ms 才执行）——
  节流在风暴中仍会每 400ms 请求一次，那正是要消除的东西。改实现后全绿。
- `:app:testDebugUnitTest` **38 类 / 380 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 未做 / 边界

- **饿死**：事件永不停歇时 `action` 永不执行 —— 这与既有的 `scheduleChatRefresh`
  是同一个取舍（换来「风暴中零请求」）。要封顶得再加一个 `maxWait` 参数，
  目前没有这个需求，已在 KDoc 里写明。
- **未做真机**：要验证「批量改写文件时不再连发请求」，只能上机看。

## 2026-10-17 · B 批开工：请求代际隔离（切会话不再看到别人的内容）（不升版本号）

### 基于什么

B 批清单里的「请求代际隔离」。

### 为什么

`launchOperation` 此前把「发请求 → 写状态」当成一段连续逻辑，但中间隔着一次网络往返。
切会话是**瞬时**的、请求不是 —— 于是会这样：

1. 在会话 A 里触发一次历史刷新，请求发出；
2. 用户立刻切到 B，B 的请求也发出；
3. **A 的响应先回来**，`update { copy(history = A 的历史) }` 把 B 的状态覆盖成 A 的。

症状是「切过去又跳回来」，或者更糟：**标题写着 B、内容却是 A**。这类 bug 要卡在特定时序上，
难复现但很吓人。

### 干了什么

1. 新增 `data/RequestGate.kt`：`current` / `invalidate()` / `isCurrent(token)`，`AtomicInteger` 实现。
2. `launchOperation` **在调用点领号**，回来时过闸：换代就整块丢弃 —— 连 `busy = false` 也跳过，
   因为那个标志属于**新**请求（旧请求清它会让转圈提前消失）。
3. 三个上下文切换点作废代际：`selectSession`、`connectHostInternal`、`disconnect`。
4. **只丢弃、不取消**：取消要给每个协程留句柄并处理取消异常，复杂度远高于收益；
   一个已经在飞的网络请求，让它跑完再扔掉结果是最省事的正确做法。

> **领号位置是有讲究的**：必须在这一行（调用点），不能挪进协程里。协程启动本身有延迟，
> 若在延迟之后才领号，「发出请求 → 用户立刻切换」这段窗口里领到的会是**新**代际，
> 结果被当成有效写回去 —— 而那恰恰是我们要挡的情况。注释里写明了这一点。

### 测什么

- 新增 `RequestGateTest`（**4 用例**）：初始号有效、`invalidate` 作废此前所有号、
  连点两个会话只有最后的号算数、**8 线程 × 500 次并发领号互不重复**
  （这条护的是原子性 —— 防日后有人把 `AtomicInteger` 改成普通 `Int`）。
- `:app:testDebugUnitTest` **37 类 / 375 用例 / 0 失败**；`compileDebugKotlin` 真执行。
- **未做真机复现**：单测覆盖的是闸门语义本身，端到端竞态仍要真机卡时序验证。

## 2026-10-17 · A 批收官：`atSeq` 分叉有了入口（顺带被行数守卫逼出一次拆分）（不升版本号）

### 基于什么

A 批清单里的最后一项「`atSeq` 分叉」。接口早就支持
（`DshClient.forkSession(sessionId, atSeq)`），UI 一直不传。

### 为什么之前不做、这次怎么做

上一轮我把它标成「需产品设计」：最顺手的入口 —— 消息长按菜单 —— 会重踩 `ChatItem`
注释里那条血泪教训（`combinedClickable` 的 `onLongClick` 会**消费**长按，
`SelectionContainer` 的选词手柄永远弹不出来，用户原话「不能选择性复制，只能复制整段」）。

这次选的入口**不占任何既有手势**：放在**时间戳那一行**里（上一轮刚加，天然属于「元信息」），
一个低对比度的小按钮，且**只给用户消息** —— 真实场景几乎总是「这次提问的走向不对，从这儿重来」，
每条消息都挂按钮只会让消息流毛糙。

### 干了什么

1. 新增 `data/ChatForkPoint.kt`：`forkPointOf(item)` 只在**用户消息且 `seq > 0`** 时返回分叉点
   （`seq = 0` 不是有效分叉点；给内核传它只会换回一个用户看不懂的报错）。
2. `HarnessViewModel.forkSession(sessionId, atSeq)` 加参数 —— 语义照抄 OpenAPI：
   `at the latest completed boundary, or at the supplied event sequence`。
3. 点击只上报序号，**确认对话框由 `ChatDetail` 那一层持有**：消息流里同时只可能有一个
   待确认的分叉，让每条消息各持一个状态只会让它们互相看不见。
4. 文案**不承诺内核的包含边界**（OpenAPI 只说 "at the supplied sequence"）：
   「保留到这条消息为止的内容」是给人读的表述，真机验收前不该写得更死。

### 顺手被自己的守卫抓到一次违规

这几轮的增量把两个文件推过了 **800 行**预算：`ChatMessageItems.kt` **806**、`ChatDetail.kt` **805** ——
第一批做的 `ArchitectureGuardTest` 当场把它拦下（这正是它存在的意义）。按制度**拆分而不是上调**：

- 新增 `ui/chat/ChatMessageFooter.kt`（时间戳 + 分叉入口，**84 行**）——
  它们的共同点是「都不是消息正文」：一个回答「什么时候说的」，一个回答「能不能从这儿重来」；
- 新增 `ui/chat/ChatForkDialog.kt`（确认框，**39 行**）—— 分叉的文案从此只有一处；
- 结果：`ChatMessageItems.kt` 806 → **752**，`ChatDetail.kt` 805 → **797**。

### 测什么

- 新增 `ChatForkPointTest`（**4 用例**）：用户消息是分叉点、助手/过程行不是、`seq = 0` 与负数被拒。
- `:app:testDebugUnitTest` **36 类 / 371 用例 / 0 失败**；`compileDebugKotlin` 真执行。
- **未做真机**：入口观感、以及内核在 `atSeq` 处的包含边界都要上机确认（文案已避免承诺）。

## 2026-10-17 · 消息气泡带时间戳：三档粒度、纯函数可测（不升版本号）

### 基于什么

对标报告 A 批清单里的「消息时间戳」。

### 为什么（先查证，再动手）

动手前先确认**数据在不在**，结论是**在**：`ChatDisplayItem.time` 早就有真实来源
（历史事件里的 `event.time`），而且**已经在用** —— 过程分组的耗时摘要就靠它
（`ChatDetail.kt:391`）。所以这条是纯 UI 工作，不需要 bridge 支持；缺的只是
「把时间显示在气泡上」。

### 干了什么

1. 新增 `data/ChatTimeLabel.kt`：`chatTimeLabel(millis, now, zone, locale)` 三档粒度 ——
   **今天**只给时刻、**今年**给月日 + 时刻、**更早**给年月日 + 时刻。
   一律只给时刻的话，翻到上周的会话会看到一串 `14:03`，用户分不出是哪天 ——
   而时间戳的作用恰恰是「知道这条是什么时候说的」。
2. `ChatItem` 在**消息**（用户 / 助手）下方显示时间戳，且**跟随气泡那一侧**对齐；
   工具行 / 上下文注入 / 推理**不显示** —— 它们都在过程组里，逐行配时间是噪音，
   「多久」已由过程组的耗时摘要回答，这里回答的是「什么时候」。
3. 时间戳按值 `remember` 缓存，避免热路径上反复重建 `SimpleDateFormat`。

### 测什么（两个假设被测试当场纠正）

- 新增 `ChatTimeLabelTest`（**6 用例**）：三档粒度、跨零点边界、中文/英文形态、时区。
- `:app:testDebugUnitTest` **35 类 / 367 用例 / 0 失败**；`compileDebugKotlin` 真执行。
- **第一次跑就红了两条，两条都是我的假设错、实现对**：
  ① 我以为「换时区只换时刻」，实际上海 10-17 00:30 在 UTC 还是 10-16，
  **分档也跟着掉一档**（断言已按真实行为改写并注明）；
  ② 我以为中文下 `SimpleDateFormat("MMM d")` 输出「10月3日」，实际是「10月 3」（带空格）——
  于是实现给中文单独一支 pattern（`M月d日`），并在注释里写清为什么**不用** Android 的
  `DateFormat.getBestDateTimePattern`（那是 Android API，没有 Robolectric 的 JVM 单测里会 "not mocked"）。

### 未做

- 未做真机：观感、以及滚动时的时间戳重组开销都要上机看。
- `atSeq` 分叉 UI 仍未做（消息级长按会与选词/双击复制抢手势，需单独设计入口）。

## 2026-10-17 · 已归档会话可看：入口按能力位守卫，恢复刻意缺席（不升版本号）

### 基于什么

姊妹仓 `dsh-remote-bridge` 同日上线 `GET /chat/sessions?archived=true` 与能力位
`session-archive-list-v1`（见该仓 `CHANGELOG-DEV.md` 同日条目）。

### 为什么

内核把归档实现为「从所有分组界面**隐藏**」，而手机端此前连看都看不到归档会话 ——
用户点了归档，那条会话就像被弄丢了。**恢复**这件事本仓不可证（内核是否提供 unarchive，见下），
所以这一版只做「能看」，并把「不能恢复」明说。

### 干了什么

1. `DshClient.sessions(archived)`：`?archived=true` 只列归档；默认路径与行为一字不变。
2. `HarnessState.archivedSessions` + `loadArchivedSessions()`：每次进视图**重拉、不缓存** ——
   归档状态可能在电脑端被改过，缓存会让手机端长期显示过期结果。
3. 会话面板加「已归档 / 返回会话」切换入口；归档视图用**独立行组件**（`ArchivedSessionRow`）
   而不复用 `SessionRow`：后者的菜单（重命名 / 分叉 / 归档）对已归档会话要么无意义、
   要么本插件不支持，复用只会让用户点到无效项。
4. **入口按能力位守卫**（`supportsArchiveListing`）：老 bridge 会**忽略** `archived` 参数、
   照样返回未归档列表 —— 若不判能力位就展示入口，用户看到的「一条归档都没有」是**假结论**；
   能力清单未知（老 bridge 不给）时一律不显示入口。
5. 归档视图**明说**「本插件暂不支持恢复归档，需在电脑端 WebUI 调整」。

### 测什么

- `BridgeCapabilitiesTest` 补 1 例：能力位存在 / 不存在（只有 `session-archive-v1`）/ 未知（空集）
  三种都断言，最后一种必须是**假**。
- `:app:testDebugUnitTest` **34 类 / 361 用例 / 0 失败**；`compileDebugKotlin` 真执行。
- **未做真机**：切换入口与归档行的观感要上机看。

### 未做

- **恢复归档**：内核是否提供 unarchive 从本仓不可证（`node_modules` 下没有 `@deepseek-ai/*`）。
  按 bridge 侧 `PITFALLS.md` §空结果的来源要验证 的规矩，只写「不可证」，客户端也**不提供**恢复按钮。
- `atSeq` 分叉 UI 仍未做（消息级长按会与选词/双击复制抢手势，需单独设计入口）。

## 2026-10-17 · 失败行可以「重试上一轮」；`atSeq` 分叉降级为需产品设计（不升版本号）

### 基于什么

对标报告 A 批清单里的「失败重试按钮」与「从某条消息分叉（`atSeq`）」。

### 为什么

- 失败行此前只有展示 + 长按复制。用户看到「限流 / 配额 / 密钥」这类**可恢复**失败时，
  唯一能做的是自己把刚才那段话重打一遍。
- `atSeq` 分叉的接口**早就支持**（`DshClient.forkSession(sessionId, atSeq)`），UI 一直不传 ——
  但**不能顺手加**：`ChatItem` 的注释里记着一条血泪教训 —— `combinedClickable` 的 `onLongClick`
  会**消费**长按事件，导致 `SelectionContainer` 的选词手柄永远弹不出来
  （用户原话「不能选择性复制，只能复制整段」）。消息级长按菜单正是会重踩这个坑的形态，
  它需要单独设计一个不占长按的入口，不该在补 A 批时顺手塞进去。

### 干了什么

1. 新增 `data/ChatRetry.kt`：`retryableUserText(items)` = 历史里**最后一条有正文的用户消息**
   （`ChatTurnFailure` 里没有 turn/seq，内核只给失败摘要，拿不到精确归属；
   而失败行永远紧跟在触发它的那条用户消息之后）。
2. `HarnessViewModel.retryLastUserMessage()`：取到就 `sendMessage(text, steer = false)`；
   取不到（历史上没有带正文的用户消息）**静默不动** —— 用户主动点的按钮，报错更糟。
3. `TurnFailureRow(failure, onRetry)` 加重试按钮；**登出停止（`stopped`）不给按钮**：
   那种失败重试必然再失败一次，只是多烧一次往返。
4. 两条渲染路径都接线（`ChatDetail` 与 `ChatProcessGroup` —— 失败行归「消息」类，
   会走过程组那条路），文案中英双站（`retry` / `Retry`）。

### 测什么

- 新增 `ChatRetryTest`（**5 用例**）：取最后一条用户消息、跳过空正文（只发图不发字的消息）、
  没有用户消息时返回 null、越过夹在中间的失败/工具行、**逐字还原**（不 trim 用户打的空白）。
- `:app:testDebugUnitTest` **34 类 / 360 用例 / 0 失败**；`compileDebugKotlin` 真执行。

### 未做（都写清理由）

- **`atSeq` 分叉 UI 未做**：见上 —— 它会与选词/双击复制抢手势，需要单独设计入口
  （候选：消息行的独立小按钮、或复选模式）。接口侧已经就绪，UI 一旦定稿就是几行接线。
- **归档恢复未做**：`GET /chat/sessions` **没有**「列出已归档」的参数，
  这是**bridge 先行**的活（C 批），客户端只能等接口。
- 未做真机验收：按钮观感、以及「重试后历史刷新的时序」都要上机看。

## 2026-10-17 · 客户端接入服务端能力位：功能缺失从「静默」改为「明说」（不升版本号）

### 基于什么

姊妹仓 `dsh-remote-bridge` 同日上线了 `connection.ready` 的 `bridgeVersion` 与 `capabilities`
（24 项能力位，每项都能指出契约依据），见该仓 `CHANGELOG-DEV.md` 同日条目。

### 为什么

「审批 / 问答卡片不弹」这个坑我们踩过**两次**，两次根因都在插件侧（waterfall 钩子未 `prepend`），
而手机端看到的与「本来就没有待审批」**一模一样** —— 用户只能干等，我们只能靠猜。

顺带纠正一句流传的说法：README 里那句「要求 bridge ≥ 2.3.7」是给**用户**看的安装门槛，
而客户端代码里此前**既没有**版本比较、**也没有**处理 `connection.ready`（已核实）。
所以真正缺的不是「更好的版本判断」，而是**任何运行时判据** —— 这次补的正是它。

### 干了什么

1. 新增 `data/BridgeCapabilities.kt`：解析 `connection.ready` 的版本与能力位；
   `REQUIRED_CAPABILITIES = [approvals-v1, questions-v1]`（**只列**会「静默不工作」的，
   否则每个稍旧的服务端都弹警告，警告一多就没人看）；
   `missingRequiredCapabilities()` 按固定顺序给出缺口。
2. `HarnessState` 加 `bridgeVersion` / `bridgeCapabilities`；`connectEvents` 处理 `connection.ready`。
3. 设置页「连接」区：**读到清单且缺关键能力**时显示警示（带服务端版本号）。
4. **两条语义关键点**：清单缺失或为空一律算**未知**（不提示）——假警告比没有警告更坏；
   数字等非字符串项在解析时跳过，不让一项脏数据把整条清单作废。

### 测什么

- 新增 `BridgeCapabilitiesTest`（**9 用例**）：正常解析、缺失/空清单算未知、脏项跳过
  （含 `42` 这类数字原语 —— 这条真抓到过一次「宽容过度」）、非对象载荷、
  缺口列表与顺序、依赖表守门。
- `:app:testDebugUnitTest` **33 类 / 355 用例 / 0 失败**；`compileDebugKotlin` 真执行（非 UP-TO-DATE）。
- `docsCheck` 通过；README 双站与 TESTING 的用例数已对账（33 类 / 355 用例）。

### 未做

- **未做真机**：警示条的实际观感、以及「旧服务端不弹假警告」的完整链路都要上机看。
- **未用能力位替换任何既有逻辑**：App 目前没有基于版本的逻辑可替换，
  所以这批只做「探测 + 提示」；「按能力位降级 UI」留到有具体需求时再落。

## 2026-10-17 · 对标 Whaleness：会话页舒适度、外观设置与「文档纪律可执行化」（不升版本号）

### 基于什么

`dsh-study/分析-2026-10-16/` 下六份对标报告（主报告 + 会话页/渲染/架构/网关四份专题，共 3 376 行、
逐条 `文件:行号` 取证），对比对象是第三方 DSH 移动端 **Whaleness**
（`Clarklevis1995/dsh-mobile` main @ `70b60b7f0b0e`，versionName 仍停在未发版的 1.7.5）
与其服务端网关 `dsh-plugin-mobile-gateway`（main @ `4e6614a16e`）。

用户读了报告后的要求：**A（纯客户端功能与渲染）、B（工程质量）、C（需 bridge 先行）三批全做**，
另外点名两条 —— 「我们的会话窗口界面没人家舒服，要学过来」与「设置页把它的学过来和我们的合并」，
并要求**测试多个文件一起跑、不要拖慢进度**。

本条目覆盖 A/B 两批的**客户端侧第一批**与设置页合并；C 批（bridge 侧结构性修复）单独立项，见文末。

### 干了什么

1. **会话页舒适度（对齐 Whaleness 的三条做法）**
   - **滚动期冻结流式快照**（`ui/chat/ChatDetail.kt`）：把「数据源 `displayItems`」与
     「渲染快照 `visibleItems`」拆开；手指在滚动时只保留停手那刻的快照，停手后 `delay(80)`
     再一次性灌入。滚动期间每个 token 都重建列表的抖动由此消失（对标 `ConversationScreen.kt:786-811`）。
     新消息一并冻结 —— 滚动中插入条目会把用户正读的那一行顶走，比晚几秒看到新消息更难受。
   - **过程分组折叠 + 耗时摘要**（新 `data/ChatDisplayGroups.kt` + 新 `ui/chat/ChatProcessGroup.kt`）：
     把上下文 / 思考 / 工具调用折成一行「耗时 1 分 12 秒 · 3 项上下文 · 5 次工具调用」，
     点开才展开。**组内只有 1 项时不折**（折叠它只是让用户多一次点击）。
     折叠态用 `rememberSaveable`（对比对象这里用了 `remember`，滚动回收后展开态会丢，是它的已知不一致）。
   - **思考条可展开**（`ui/chat/ChatComposer.kt`）：原先只显示尾部 140 字符且不可点，
     现在点一下展开全文（限高 240dp + 内部滚动，不把输入区顶出屏幕）。
2. **`ChatDisplayItem` 补 `time` 字段**（`data/ChatModels.kt` + 6 处构造点）：
   分组模块交回时指出「模型里根本没有时间字段，耗时摘要永远亮不了」——
   这是真问题，不是它的实现缺陷。现在历史项填 `SessionEvent.time`，流式项为 `null`（诚实的缺失，不是 0）。
3. **代码高亮与代码块渲染**
   - 新 `ui/chat/ChatCodeHighlight.kt`：JSON 用**单遍字符扫描**（不用带后向断言/重复分组的正则 ——
     那类写法在 KMP 下会退化甚至爆栈，是对比对象在注释里亲口记录的坑），
     键靠「后面是否跟冒号」区分；另覆盖 kotlin/java/js/python/shell/yaml/xml/html/css 的关键字/字符串/数字/注释；
     认不出的语言一律返回原文不报错。测试含 20 万字符性能与畸形 JSON 不抛异常。
   - 新 `ui/chat/ChatCodeBlock.kt`：给 Markdown 代码块加**语言标签 + 复制按钮 + 等宽 + 长行横向滚动**
     （此前全部走库默认：无标签、无复制、长行自动换行）。
     接入方式是从本地 aar 反编译核实过的 API：`markdownComponents(codeBlock = { model -> ... })`
     （`com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0`）。
4. **设置页合并：新增「外观」组 + 语言三档**（`ui/SettingsScreen.kt` + `ui/theme/`）
   - **界面风格三档**（跟随系统 / 浅色 / 深色）：`InterfaceStyle` 存 `name` 而不是 ordinal
     （枚举顺序一变，ordinal 会把老用户的「深色」读成「浅色」）。此前界面完全由 `isSystemInDarkTheme()` 决定，
     用户无法强制配色。
   - **应用内字号缩放 0.8–1.5×**（新 `ui/theme/AppFontScale.kt`）：包装 `LocalDensity`，
     `fontScale = 系统 fontScale × 应用倍数`，并把 `TextUnit.toDp()`/`Dp.toSp()` 两条**反向换算钉回基准** ——
     否则「图标 20dp 配文字 20sp」这类成对尺寸会被放大两遍，文字撑破标签。
     非法值（NaN/Inf）由 `normalizeFontScale` 收敛到 1f：坏值进 Density 会白屏且不崩溃，最难查。
   - **语言加「跟随系统」档**，并**拆掉一颗地雷**：`MainActivity` 原先用
     `getApplicationLocales().isEmpty` 判断要不要兜底中文 —— 而「跟随系统」在系统 API 里同样是空列表，
     于是用户选了跟随系统，每次冷启动都会被打回中文。改为看 `AppPreferences.languageChosen` 标志。
5. **把文档纪律变成可执行门禁**（新 `app/src/test/.../ArchitectureGuardTest.kt`，2 类 8 用例）
   - 五条断言：① 单文件 ≤ 800 行（超限须在 `TECH-DEBT.md` 登记且记录行数 ≥ 实际）；
     ② `ui/**` 不得出现 `SharedPreferences` 符号；③ 每个 `// @patch` 须齐备 id/for/why/until/test
     且 `test` 指向的测试类真实存在；④ 构造 OkHttp 客户端的只能是 `data/DshClient.kt`；
     ⑤ `displayItems` 声明必须落在 `data/` 包内、且 `ui/` 不出现内部事件字面量。
   - **不需要解析器**：全是文本匹配 + 文件存在性断言 —— 这条正是从对比对象学来的
     （它用 4 个扫源码的 XCTest 守架构，`AGENTS.md:189` 自认「Kotlin 侧无等价解析器」的顾虑由此撤销）。
   - 另有 3 条**自检**用例专门防「门禁空转全绿」（仓库根定位失败直接抛错、扫到的文件数低于阈值判红、
     关键正则一次都没命中判红）。
   - **门禁上线即抓到一条真违规**：`web/ChatWebBridge.kt:75` 自持 `OkHttpClient.Builder()`，
     违反 §4.1「DshClient 是唯一网络边界」，而这条偏离此前只活在代码注释里。
     处理方式是**立账而不是洗白**：新增 `TECH-DEBT.md` DEBT-006（含清账路径：给 DshClient 加通用转发入口），
     `AGENTS.md` §4.1 补例外说明，测试白名单显式登记 —— 白名单加人必须同时补台账。
   - 同时把 DEBT-004（`@patch` 登记表自动化）与 DEBT-005（行数预算不进 CI）标记为**已由单测兑现**。

### 测什么

- `scripts/build.sh :app:testDebugUnitTest`：**32 类 / 346 用例 / 0 失败**（口径：数
  `app/build/test-results/` XML）。本批新增用例：分组 19（含 1 554 条序列的「摊平后与输入完全相等」
  属性断言）、高亮 12、外观 10、架构门禁 8。另有 1 条是**修断言**而非改实现：
  `[1, 2]` 里两个数字本就都该着色，初版期望写成了只匹配 `"1"`。
- `scripts/build.sh :app:compileDebugKotlin`：真执行，`BUILD SUCCESSFUL`。
- **`scripts/build.sh docsCheck` 与 README 双站对账**：见本轮收口的「文档同步」小节。
- **未做真机验收**：滚动节流的实际手感、过程组折叠的观感、字号缩放在 200% 系统字号下的布局、
  代码块横向滚动 —— 全部只能真机确认（单测绿 ≠ 行为对，见 §5）。

### 还没做（下一轮）

- **C 批（bridge 侧）**：`seq` 隔离（瞬时流帧不占持久 seq）、原子基线 `snapshot` 透传、
  断档 `resync-required`、交互请求连接即重放、Unicode 代理项消毒、工具结果截断 —— 全部先改
  `dsh-remote-bridge`（五处文档同步），再回客户端接。
- **A 批剩余**：消息内图片点击查看/保存、会话面板下载/另存、失败重试按钮、从某条消息分叉
  （接口已支持 `atSeq` 但 UI 不传）、归档恢复入口、消息时间戳。
- **B 批剩余**：请求代际隔离（治迟到响应/抢答）、`Clock`/`Transport` 最小接缝、有界事件队列、
  增量投影路径、真机 `*DeviceTest` 补齐（对比对象 24 文件 87 用例，我方 androidTest 现为 1 文件 1 用例）。

## 2026-10-16 · 清 lint 历史欠账（不升版本号）：16 个 error 归零，lintDebug 提回硬门

### 基于什么

GitHub 重建后 CI 第一次真正跑通，暴露出 `lintDebug` 有 16 个历史 error（此前无 baseline、
又被当硬门禁，曾直接 BUILD FAILED）。用户要求清掉。原则：真问题修代码，确证误报才在调用点
`@SuppressLint` 并写理由，绝不为变绿一刀切压制。

### 干了什么（三类）

1. **震动反馈真 bug（5 个 error：MissingPermission ×1 + NewApi ×3 + 权限缺失）**
   `ChatWebBridge.haptic()` 直接调 `VibratorManager`(API 31) 与 `createPredefined`(API 29)，
   minSdk 却是 26——在 26~30 的机器上必抛 NoSuchMethodError，被外层 `runCatching` 吞成「静默不震」；
   且 Manifest 没声明 `VIBRATE`。修法：Manifest 加 `android.permission.VIBRATE`（normal 级，安装即授予）；
   代码按 SDK 分级，31+ 走 VibratorManager + EFFECT_TICK，26~30 退回 Vibrator + 20ms 单次震动，
   并先查 `hasVibrator()`。**这是本次唯一的真实行为修复**：老设备的发送震动此前是坏的。
2. **Compose 里 `LocalContext.getString()`（10 个 error，LocalContextGetResourceValueCall）**
   `context.getString()` 不订阅 Configuration，切换界面语言时不会随重组刷新，toast/提示会停留在旧语言。
   全部改为组合期 `stringResource(...)` 预取，回调/LaunchedEffect 闭包引用捕获值；
   `ChatWebScreen` 的 LaunchedEffect 还把两个文案加进了 key，切语言会重发带正确文案的 init。
   涉及 ChatActivityRows / ChatDetail / ChatMessageItems / ChatToolDetail / ChatWebScreen。
3. **JS 接口（1 个 error，JavascriptInterface）** 根因是 lint 无法穿过 `AndroidView` 的 factory
   lambda 解析外部 `bridge` 的具体类型、只看到泛型 T（同文件内联匿名对象就不报）。先把唯一无注解的
   public 方法 `onPicked()` 降为 `internal`（顺手收紧真实 JS 暴露面），并给 `bridge` 显式标注
   `ChatWebBridge` 类型；仍报的那处在调用点 `@SuppressLint("JavascriptInterface")` 并注释列出
   已逐一核对带注解的 JS 方法。

### 测什么

- `scripts/build.sh :app:lintDebug`：**0 error / 52 warning / 4 hint，BUILD SUCCESSFUL**（warning 是
  依赖新版本、PluralsCandidate、UnusedResources、长 vector path 等非门禁项，本次不动）。
- `:app:testDebugUnitTest`：27 类 / 290 用例 / 0 失败 / 0 跳过，无回归。
- `.github/workflows/android.yml` 把 `lintDebug` 提回硬门（AGP 只对 error 判失败，warning 不拦截）。
- 震动的真机分级路径与 10 处 UI 文案仍建议真机过一眼（单测不覆盖触感与多语言重组）。

## 2026-10-16 · 渠道基建（不升版本号，App 零改动）：三端拓扑 Gitee 主 / AtomGit 备 / GitHub 镜像从站

### 基于什么

用户指令原话：「atomgit也推，删除github所有内容，让重新自动从atomgit和Gitee去自动同步」。
背景是 GitHub 侧旧账已经还不动：v2.2.35（分叉点 `8fcbf0c`）之后历史被重写，两边 11 个提交
树对象两两相同但 hash 不同，`git merge origin/main` 爆 47 个冲突（28 AA + 15 UU + 4 DU）；
本机到 `github.com:443` 又长期被阻断，手工推送只能走 REST 重建哈希，永远对不齐。

### 干了什么

1. **AtomGit 建仓并全量推送**：`westanke163/dsh-companion`（AtomGit 账号名是 westanke163，
   与 Gitee/GitHub 的 westanke 不同），main + 19 个 tag 全量推送，与 Gitee 逐 ref 比对 SHA 一致。
2. **删除 GitHub 旧仓并重建为镜像**：31 个提交欠账与历史分叉随删仓一笔勾销，不再需要线性化、
   不需要 `--force`。重建实路：GitHub Import REST API 已废弃（404 指向网页 Importer），网页 Importer
   在本机网络下卡 "Couldn't check availability"；最终走「建空仓 → Contents API 提交引导版
   mirror-sync.yml（api.github.com 不受 github.com:443 阻断影响）→ workflow 在 runner 上
   clone --mirror Gitee 并 push --mirror 覆盖」，普通 git 传输保留**真实 commit hash**，
   重建后 GitHub main 与 Gitee 同为 `df6dcc5`、19 个 tag 齐全。
3. **自动同步工作流** `.github/workflows/mirror-sync.yml`（每 3 小时 + 手动 `workflow_dispatch`）：
   - mirror job：引导版内嵌自举段（与 `scripts/ci/mirror-push.sh` 等价），runner 上
     `git clone --mirror` Gitee（不通回退 AtomGit），再 `git push --mirror` 到本仓——refs 全量对齐，
     含删除面。推送凭证必须用仓库 secret `MIRROR_PAT`（`repo` + `workflow` scope）：GITHUB_TOKEN
     按设计不能增改 workflow 文件，实测每个含 `.github/workflows/android.yml` 的 ref 都被拒；
   - `scripts/ci/sync-releases.py`：Gitee Release 正文与三类资产（APK / SHA256SUMS.txt /
     RELEASE_NOTES，不搬 GitHub 会自动生成的 zip/tar.gz）幂等补到 GitHub，已存在即跳过。
     GitHub 资产名 ASCII 化（`DSH伴侣-companion-vX.Y.Z.apk` → `DSH-companion-vX.Y.Z.apk`），
     因 uploads 实测不支持非 ASCII 资产名；带残缺名（中文被吃）资产的自愈删除逻辑。
   - 顺手修了 Android CI 的既有故障：`setup-android@v3` 默认装旧 `tools` 包在新 runner 上失败，
     显式声明 `platform-tools platforms;android-36 build-tools;36.0.0`。
4. **本地推送工具**：`scripts/push-all.sh`（默认只推 Gitee + AtomGit，`PUSH_TAGS=1` 推标签，
   `INCLUDE_GITHUB=1` 才试 GitHub，`FORCE=1` 才走 `--force-with-lease`）；
   `scripts/setup-remotes.sh` 幂等整理远端命名（origin=GitHub / gitee / atomgit，upstream 只读不动）。

### 测什么

- AtomGit 回读：`git ls-remote` 36 行 vs Gitee 38 行，差异仅 v1.1.0/v1.2.0 两个最老轻量 tag
  在两端服务端的 `^{}` 解引用展示，指向 commit 相同（本地验证两个 tag 均为 commit 类型）。
- `bash -n` 三个 shell 脚本、`py_compile` Python 脚本、yaml 解析均通过。
- `sync-releases.py` 用 `DRY_RUN=1` 本地实跑：18 个旧 Release 全部识别为已存在、资产已存在；
  v2.2.41 正确创建并识别三个资产（含中文名 `DSH伴侣-companion-v2.2.41.apk`，2,895,430 字节，
  下载计数与 Gitee 一致）；干跑曾暴露一个 DRY_RUN 占位 bug（创建分支 gh=None），已修并复验。
- 重建后三端对账：GitHub `main` 与本地/Gitee/AtomGit 同为 `df6dcc5…`，19 个 tag 齐全；
  19 个 Release 全部回填，v2.2.41 APK 从 GitHub 下载回读 2,895,430 字节、
  SHA-256 `5317b009…f957` 与 Gitee/本地三处一致（资产名为 ASCII 的 `DSH-companion-v2.2.41.apk`）。
- 踩坑三连均在本文件/PITFALLS 留档：①空仓引导提交没有脚本（job 内嵌自举）；
  ②GITHUB_TOKEN 不能写 workflow 文件（改 secret `MIRROR_PAT`）；
  ③GitHub uploads 拒绝非 ASCII 资产名（ASCII 化 + 自愈）。
- App 侧零改动，无版本号变化。

## 2026-10-16 · v2.2.41（补）：生成合成内容显式标识（AI 标识条）

### 基于什么

同一天收到的华为应用市场驳回里还有一条**应用资质**类意见：

> 应用缺少 AI 文本生成的资质证明文件，不符合相关法律法规要求。
> 修改建议：（1）AI 文本生成模块需补充提供《安全评估报告》、及其在全国互联网安全服务管理平台的
> 提交结果截图，且现场检查结果为「通过」…

提出的疑问是：**这条驳回是不是靠给服务端回复的文字加一句「内容由 AI 生成，请甄别使用」就能解决？**

### 为什么答案是「不能」——资质与标识是两件事

查证《[人工智能生成合成内容标识办法](https://www.cac.gov.cn/2025-03/14/c_1743654684782215.htm)》
（网信办等四部门，2025-09-01 施行）原文，两者是**独立**的两条线：

| | AI 文本生成**资质** | 生成合成内容**标识** |
|---|---|---|
| 依据 | 《生成式人工智能服务管理暂行办法》第 17 条 | 《标识办法》第四条 |
| 要求 | 算法备案 + **《安全评估报告》** + 全国平台提交结果截图（现场检查「通过」） | 在生成内容或交互场景**界面**添加用户可感知的**显式标识** |
| 性质 | 行政流程 / 服务提供者的法定义务 | 界面呈现 |
| 贴一句文案能替代吗 | **不能** | 就是这么做的 |

驳回意见要的明明白白是**《安全评估报告》与提交截图**，那是服务端（模型提供方）的
行政义务，客户端贴字符串替代不了。**加了标识也不能消掉这条资质驳回。**

但标识本身仍值得补，理由与审核无关：华为在上架时会**核验生成合成内容标识相关材料**
（《标识办法》第七条明确分发平台有此职责），而本项目**此前全仓零标识** ——
grep `AI 生成|生成内容|甄别|disclaimer` 命中 0。用户把生成内容截图转发出去时，
接收方没有任何提示，这是真实缺口而非审核员挑刺。

### 干了什么

新增常驻标识条 `AiGeneratedNotice`：

- 中英双份字符串 `ai_generated_notice`：
  「内容由 AI 生成，请甄别后使用」/ `AI-generated content — please review before use`；
- 挂在 `ChatDetail.kt` 的 `ChatThinkBar` **之后**、Composer 之前
  —— 位置固定在「消息区下方 / 输入区上方」，**不进 LazyColumn**。

三个设计取舍：

1. **为什么不进消息列表**：进了列表就随消息滚走，用户看几屏之后再也没见过，
   标识失去意义。第四条要的是「用户明显感知」，滚走即失效。
2. **为什么不是弹窗 / 一次性浮层**：一次性提示被关掉后用户再也看不到，
   比常驻更不合规；弹窗还会打断长会话。
3. **为什么挂思考条之后而不是之前**：两者都在同一条窄带里，标识必须紧贴输入框
   （用户产生 AI 内容的地方），挂前面会被思考条挤远。

样式走 `labelSmall` + `onSurfaceVariant`，刻意弱于正文——常驻合规提示不该抢阅读注意力。

### 免责定性（本 App 是纯客户端）

《标识办法》第二条的适用对象是「服务提供者」。本 App **不提供** AI 生成合成服务：
生成方是**用户自己部署的 DSH 服务端**，模型由**用户自行配置**，内容不出用户自己的地址
（README 隐私章已声明）。本条标识是按**从严**口径加的，不改变资质定性。

### 测了什么

- `compileDebugKotlin`：BUILD SUCCESSFUL，无新增 warning
- `:app:testDebugUnitTest`：**290 用例 / 0 失败**（未增删用例；本条为纯展示层新增）
- 中英字符串键：**409 / 409 完全对齐**
- 行数预算：`ChatComposer.kt` 617 → **653**、`ChatDetail.kt` 721 → **725**，均在 800 行内
- **未做真机验收**：标识的字号/对比度在深色模式与小屏窄屏下的实际观感需真机确认；
  UI 层单测不覆盖此组件

### 仍然待办（不是代码能解决的）

华为那条**资质**驳回需要的是行政材料，不是代码：
《安全评估报告》+ 全国互联网安全服务管理平台的提交结果截图（现场检查「通过」）。
若认定本 App 不涉及 AI 生成合成服务，则走 AppGallery Connect
版本信息 → 应用审核信息 → **备注区域**声明，与审核员说的修改建议（2）一致。

---

## 2026-10-16 · v2.2.41：应用改名 + 包名迁移（唯一一版破坏「覆盖安装」的版本）

### 基于什么

华为应用市场 6 条驳回意见中的知识产权类一条：

> 应用与「DeepSeek」应用的名称相似（其他语言端应用的名称：Deepseek Harness客户端），
> 但未提供相关授权或商标权属证明，可能导致用户产生混淆、误认或不适宜的联想。

旧应用名 `app_name = "DeepSeek Harness"`（`values/strings.xml:3` 与 `values-en/strings.xml:3`）
与审核员转述的名字逐字对得上，确认命中。

顺带说明同一份驳回单里另外 5 条与本仓**无关**，理由各自如下，避免以后误判：

| 驳回项 | 与本仓的关系 |
|---|---|
| 需提供遍历使用的演示录屏 | AppGallery Connect 后台「自测文件」上传动作，**代码零改动** |
| 图标单层图 216×216 应为 1024×1024 | **鸿蒙专属**规格；本仓是 Android Gradle 工程，图标是纯矢量 adaptive icon，一个位图都没有 |
| 未做真实身份认证 | 本 App **无账号体系**（全仓 grep `register\|signup\|登录\|注册\|实名\|手机号` 只命中 `ContextCompat.registerReceiver` 与一条「退出登录」注释），实名认证不适用 |
| 缺 AI 文本生成资质 | 资质动作，**代码零改动** |
| 勾了「不涉及 AI 生成合成服务」 | 后台改勾选声明，**代码零改动** |

> 那份驳回针对的是**鸿蒙 HAP 包**，其代码不在本工作区（全工作区乃至家目录扫描
> `module.json5` / `oh-package.json5` / `hvigorfile.ts` / `*.ets` 均零命中，`artifacts/` 只有 `.apk`）。

### 为什么

`NOTICE:1` 里确实有 MIT 授权，但那份授权覆盖的是 **logo 路径的著作权**，
不是「DeepSeek」这个**商标**。著作权 ≠ 商标权 —— 拿它去过审大概率不认。
商标风险落在**用户可见的应用名**上，所以堵住可见名称是刚需。

包名一并换的理由：旧包名 `io.github.hakunm.deepseekharness` 带着上游作者 hakunm 的
命名空间与 `deepseekharness` 字样，落在人工审核视野里同样是混淆源；
且该前缀本就不是我们的命名空间 —— 仓库归 `westanke`，按 `io.github.<用户>.<仓库>`
惯例应为 `io.github.westanke.dshcompanion`。

### 干了什么

**① 应用名**

| 位置 | 改前 | 改后 |
|---|---|---|
| `values/strings.xml:3` `app_name` | `DeepSeek Harness` | **`DSH伴侣`** |
| `values-en/strings.xml:3` `app_name` | `DeepSeek Harness` | **`DSH Companion`** |
| `values/strings.xml:7` `connect_title` | `连接 DeepSeek Harness` | **`连接 你的 DSH 服务端`** |
| `values-en/strings.xml:7` `connect_title` | `Connect to DeepSeek Harness` | **`Connect to your DSH server`** |

`connect_title` 一起改还有个额外好处：它描述的是**用户自己部署的服务端**，不是本 App ——
旧文案「连接 DeepSeek Harness」指错了对象。

**② 包名**（`io.github.hakunm.deepseekharness` → `io.github.westanke.dshcompanion`）

- `namespace` 与 `applicationId` 同步（`app/build.gradle.kts`）；
- **87 个 Kotlin 文件**的 `package` / `import` 整体替换；
- 源码目录搬家：`app/src/{main,test,androidTest}/java/io/github/hakunm/deepseekharness`
  → `.../io/github/westanke/dshcompanion`（main 60 / test 26 / androidTest 1）；
- `AndroidManifest.xml` 用的是相对名 `.MainActivity`，**无需改**。

**③ 顺带修掉一个必红的测试**

`ConnectScreenTest.kt` 原本硬编码英文字面量 `"Connect to DeepSeek Harness"`（两处），
应用名一改必然报错。改为按 `en` 配置开 Context 读 `values-en` 的 `connect_title`：

```kotlin
val englishTitle = compose.activity.resources.createConfigurationContext(
    Configuration(compose.activity.resources.configuration).apply {
        setLocale(Locale.forLanguageTag("en"))
    },
).getString(R.string.connect_title)
```

**为什么不用 `activity.getString`**：语言切换走 `AppCompatDelegate.setApplicationLocales`，
会触发 Activity 重建；切换瞬间 `activity.getString` 仍可能拿到切换前的 locale 值 ——
原代码用字面量很可能正是被这件事坑过。显式开 `en` 配置的 Context，取值与重建时机无关。

### 测了什么

- `:app:testDebugUnitTest`：**27 类 / 290 用例 / 0 失败 / 0 跳过**（与 v2.2.40 逐项一致 —— 本版不改任何逻辑）
- `compileDebugKotlin`：**真执行**（非 UP-TO-DATE），BUILD SUCCESSFUL，仅 3 条既有 deprecation warning
- `docsCheck`：通过
- **未做**：UI 测试（`ConnectScreenTest` 属 androidTest，需真机/模拟器）与真机验收 ——
  且**包名变更导致无法覆盖安装，真机验收必须先卸载旧包**（会清空已保存的连接，需重新配对）。

### 代价（有意接受）

**`applicationId` 变了就是另一个 App。** 老用户无法覆盖升级，必须先卸载，
而卸载会清掉已保存的连接与令牌，需重新配对。这是本次唯一无法两全的点。

旧包名保留在 git 历史里（tag `v2.2.40` 及更早），需要追溯时去那里看。
已发布的 `artifacts/*.apk` 文件名**保持不动**（按既定规矩，已发布产物不改名），
新版本产物从 v2.2.41 起启用新命名。

---

## 2026-10-16 · v2.2.40：清掉行数预算台账上的全部债务（纯重构，零功能变化）

### 基于什么

`docs/project/TECH-DEBT.md` 的行数预算（800 行/文件）上登记着两条债：

| 原债务 | 原规模 | 台账里的原话 |
|---|---|---|
| `ui/ChatScreen.kt` | 3 619 | 「会话详情页单文件承载：消息流、输入区、思考条、工具行、文件弹层、`@` 面板、手势分发。改一处要读 3 600 行」 |
| `HarnessViewModel.kt` | 2 277 | 「连接 + 会话 + 文件 + 插件 + 设置全在一个 ViewModel；`ChatScreen` 与它双向依赖」 |

实测时又发现第三条漏网的：`data/ChatPresentation.kt` 825 行（台账基线测于 2026-10-15，
它在那之后才长过线）。

### 为什么

「按域拆」是 `TECH-DEBT.md` 早就写下的清账条件，只是从没执行过。真实的痛点是两条：
改聊天页任何一处都要在 3 600 行里定位；改 ViewModel 任何一处都要先想清楚它属于哪个域。
**债可见且方向向下，比假装没有债更可信** —— 台账空着不是成绩，是没还。

### 干了什么

**一行实现都没改**，全部是「换文件」。三个巨型文件按职责边界拆开：

#### `ui/ChatScreen.kt` 3 882 → 329 行 + `ui/chat/` 下 12 个文件

| 文件 | 职责 |
|---|---|
| `ChatScreen.kt` | 入口与宽窄屏分流 + 两个列表层纯函数 |
| `chat/ChatDetail.kt` | 详情页骨架（顶栏 + 消息流 + 挂载点） |
| `chat/ChatSessionPane.kt` | 会话列表 / 概览 / 单行 |
| `chat/ChatMessageItems.kt` | 消息气泡 + 助手正文 + dsh-ui 诊断行 |
| `chat/ChatActivityRows.kt` | 终局失败行 + 折叠活动行 |
| `chat/ChatToolDetail.kt` | 工具调用详情块 + 共用紧凑排版 |
| `chat/ChatComposer.kt` | 输入区药丸 + 思考条 + 占位符规则 |
| `chat/ChatSuggestions.kt` | `@` 文件补全 + `/` 命令补全 |
| `chat/ChatAttachments.kt` | 待发附件条 + 当前任务面板 |
| `chat/ChatApprovals.kt` | 审批面板 + 问答卡片 |
| `chat/ChatPermissionMenu.kt` | 顶栏 `⋯` 的权限段与完整访问二次确认 |
| `chat/ChatSessionFiles.kt` | 会话文件面板（含 HTML 结构预览） |
| `chat/ChatSheets.kt` | 模型 / 工作区 / 新建会话三个弹层 |
| `chat/ChatToast.kt` | Toast 与剪贴板助手（原私有 `toast`，多处共用） |

#### `HarnessViewModel.kt` 2 277 → 663 行 + 8 个域文件

| 文件 | 职责 |
|---|---|
| `HarnessViewModel.kt` | 状态容器、事件流接入、刷新调度、公共工具 |
| `HarnessModels.kt` | `HarnessState` 与跨层流转的数据载体 |
| `HarnessEventRules.kt` | 事件帧与输入行的纯判定（有单测盯着） |
| `HarnessConnectionViewModel.kt` | 多主机 / 多地址的配对、探活、断开 |
| `HarnessSessionsViewModel.kt` | 会话 CRUD、工作区、发送、会话文件面板 |
| `HarnessInteractionsViewModel.kt` | 审批与问答 |
| `HarnessAttachmentsViewModel.kt` | 待发附件与历史图片 |
| `HarnessFilesViewModel.kt` | 文件浏览、编辑、上传下载与回收站 |
| `HarnessPluginsViewModel.kt` | 插件启停与模型提供商 |

域文件里的函数写成 **`HarnessViewModel` 的扩展函数**（`fun HarnessViewModel.xxx()`）而不是子类 ——
调用点写法完全不变（`viewModel.loadDirectory()` 照样成立），所以 `ui/` 下所有界面**一行都没改**。

#### `data/ChatPresentation.kt` 825 → 98 行 + 4 个文件

`ChatPresentation.kt`（门面：单一出口 + 会话投影）/ `ChatModels.kt`（数据模型 + 事件帧解析）/
`ChatHistoryMapping.kt`（逐事件映射）/ `ChatHandoff.kt`（交接图片附件）/
`ChatToolPresentation.kt`（工具行动作短语与详情）/ `ChatJson.kt`（JSON 取值助手）。

### 两处必须记录的代价

1. **可见性放宽**：原先 `private` 的部分成员提升为 `internal` —— ViewModel 的
   `mutableState` / `update` / `withClient` / `client` / `hostStore`，聊天页的组件，
   数据层的 JSON 取值助手。`internal` 只在本 module 内可见，比 `public` 窄得多，
   但确实比原来的 `private` 宽。**这是拆文件的必然代价，不是设计选择。**
2. **JSON 实例改名**：`ChatPresentation.kt` 里私有的 `json` / `prettyJson` 提到
   `data/ChatJson.kt` 并改名为 `chatJson` / `chatPrettyJson`。原因是同包的
   `GenUiBlocks.kt` 也有一个私有 `json`，两者一旦都变成 `internal` 就会互相遮蔽 ——
   那是「改一边、另一边悄悄换了解析配置」的典型入口，宁可名字长一点。

### 测了什么

| 门验 | 结果 |
|---|---|
| `scripts/build.sh :app:compileDebugKotlin` | ✅ 通过 |
| `scripts/build.sh :app:testDebugUnitTest` | ✅ **27 类 / 290 用例 / 0 失败**（与拆分前逐项一致） |
| `scripts/build.sh assembleDebug` | ✅ 通过 |
| `scripts/build.sh docsCheck` | ✅ 8 documents / 43 tasks consistent |
| 行数复核 | ✅ `app/src` 下**零个文件超 800 行**，最大 797 |

四个测试文件（`ChatThinkBarTest` / `ComposerPillTest` / `QuestionAnswerTest` /
`SessionPaneLayoutTest`）只**各加了两行 import** —— 因为它们调的纯函数从 `ui` 包移到了
`ui.chat` 子包。测试的断言、名字、意图一个字没动。

### 产物

`artifacts/DeepSeek-Harness-companion-v2.2.40.apk`，**2,895,422 字节**，release 构建（R8 + 资源裁剪）。

SHA-256 `977d9ac0b06f0823982e2957e3d879fa7168034c52d7fe7b826af760c63afc64`
（已追加进 `artifacts/SHA256SUMS.txt`；2026-10-16 重新打 release 包时哈希更新过一次——
字节数不变、哈希变，原因是 APK 签名含构建时间戳，属正常现象）。

**这个大小本身就是验收证据**：与 v2.2.39 的 **2,895,422 字节完全相同** —— 一个字节都没多、
一个字节都没少。重构只搬了代码的位置，没动逻辑，也没动任何资源或字符串。

签名 `CN=DSH Pocket Client`（证书 SHA-256 `8b2ba797…`，与线上版同一把钥匙），
**可直接覆盖安装，不必卸载**（卸载会清掉已保存的连接）。

### 为什么单独出一个版本号

改动是纯重构，但改了 **44 个文件的物理位置**。项目规矩是「改代码 → 改文档与版本号 → 校验 →
打包」，所以这一版必须有自己的 `versionCode`，否则真机装上去分不清装的是重构前还是重构后的包。
**本版起初只用于真机验收**（发布要等真机过完主流程）——真机已于 2026-10-16 全部通过，
故 v2.2.40 **正式发布**（v2.2.36–v2.2.40 的全部改动随本版一并交付）。

### 真机验收：2026-10-16 通过 ✅

单测绿 ≠ 行为对，所以出了 v2.2.40 专供真机验证。用户在手机上逐项过完：

| # | 验收项 | 结果 |
|---|---|---|
| ① | 连接与启动（不闪退、已保存主机还在、能连上） | ✅ |
| ② | 会话列表（历史完整、分页、长按重命名/归档） | ✅ |
| ③ | 消息流与工具详情（Markdown 渲染、展开详情、复制输出） | ✅ |
| ④ | 输入区（`@` 与 `/` 补全、发送、附件） | ✅ |
| ⑥ | 文件页、插件页、设置页开关、会话文件面板 | ✅ |
| ⑦ | 切会话不卡不崩、熄屏亮屏消息接上 | ✅ |
| ⑤ | 审批与问答 | ⚠️ 当时记「没弹出来」→ **该结论已于同日推翻，见下** |

**⑤ 的最终结论（2026-10-16 当日推翻上面那行）**：验收当时记录「卡片不弹」，
经排查**病根全部在服务端插件 `dsh-remote-bridge`，本 App 一行未改**——订阅、拉取、
渲染三环自始至终都是对的。该症状曾连续坏在**两个**插件侧病根上：

1. `sessionIdOfAgent` 漏了内核 `Agent.id`（内核 Agent 是 class，id 挂在 `session.header.id`）——
   bridge **2.3.6** 修；
2. 两个钩子注册时**没带 Cordis 的 `{ prepend: true }`**。`approval/request` 与
   `user-questions/request` 是 waterfall 链，Cordis 4.0.2 的注册就是
   `options.prepend ? 'unshift' : 'push'`，不传即排到链尾；而 waterfall 语义明写
   「不调 `next()` 就否决后面整条链」—— 于是本插件排在官方 WebUI 的监听器之后，
   WebUI 自己处理掉就不调 `next()`，**本钩子永不被调用**，且**失败完全静默**——
   bridge **2.3.7** 修。

**真机复验**：用户升级插件到 2.3.7、重启 dsh 后，plan 模式评审实测
**手机端弹出卡片、电脑端 WebUI 同时正常弹出**（红线「不得抢答」未踩）。

**教训**（已写进 `PITFALLS.md` §bridge 版本基线）：**卡片不弹时先查插件版本再查 App** ——
两者症状完全一样（手机零卡片、电脑端正常、两侧日志都不说一句话），但修的地方在另一个仓库。

**结论**：v2.2.40 的重构**真机验收通过**（含 ⑤，依赖插件 ≥ 2.3.7），可以进发布流程。

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
