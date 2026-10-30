- 日期：2026-10-30
- **v2.2.61（versionCode 10086，发）**：**智能团队只读面板 + 自然语言指挥条 + 权限档位分色**。
  依据《手机端智能团体管理与会话功能研究报告 2026-10-30》P0/P1 方案（官方 Web 团队 UI 本身只读，
  管理靠 Lead agent 的模型工具，故桥零改动）。新增 `data/AgentTeamProjection.kt`（容错解析
  agentTeam/subagentCatalog 投影，id 与 sessionId 双键都认）与 `ui/chat/TeamPanelSheet.kt`
  （成员名册/共享任务板/子会话三节只读卡 + 「新成员/派任务/问进度」指挥 chips，以普通队列消息
  发给 Lead）；入口为会话 ⋯ 菜单一级项 + 标题副行权限 Pill 后的品牌色「智能团队」胶囊。
  权限三档分色分图标：只读=信息蓝 Lock、工作区写入=琥珀 Edit、完整访问=危险红 WarningAmber
  （新增纯函数 permissionTone，颜色走语义色板）。门禁：docsCheck（164 锚点/144 源文件）、
  单测 **88 类 / 874 用例**（新增 7 例）、lintDebug 0 error、assembleDebug 全绿。
  **真机已验**（V2080A，17:52 覆盖安装）：两胶囊并排、团队面板三节数据正确（lead + 2 个可续用子会话）、
  指挥对话框预填正确。P2（点成员直跳子会话）受阻于桥侧 origin:subagent 过滤与 parentSessionId 不透传，未做。
- 日期：2026-10-28
- **BFF 三项能力接入（未发版，对应 `BFF-001`）**：按桥 `BFF-REST-CONTRACT.md` 补齐 **分块上传 / 定时任务 / 官方语音输入**。
  数据层新增 `data/DshClientBff.kt`（12 个 `DshClient` 扩展端点，复用唯一 OkHttp 客户端与鉴权）、`BffModels.kt`、`ChunkPlan.kt`、
  `CronHint.kt`、`WavHeader.kt`；VM 新增 `HarnessSchedulesViewModel.kt` / `HarnessSpeechViewModel.kt`，附件域 `attachFile` 在
  >4 MiB 且能力位允许时改走分块（进度、续传、409 缺块补传一次）；UI 新增 `ScheduleSheet.kt` / `SpeechInput.kt` / `SpeechRecorder.kt`，
  `ChatHeader` 加「定时任务」入口、`ChatComposer` 加麦克风与上传进度条。权限只加了 `RECORD_AUDIO`。
  > **2026-10-29 跨仓对齐**：桥侧实现落地后复核出三处不一致并已修 —— 停用改走 DELETE（内核无暂停语义）、
  > `createdAt` 恒 null 不再用于排序、能力位收紧成定稿单值（`attachment-chunked-upload-v1` 等）。
  > 单测 **75 类 / 711 用例**。
  门禁：`assembleDebug` 成功、单测 **74 类 / 702 用例 / 0 失败**、docsCheck 仅剩 README 版本号等待发版回填。
  **未真机验收**：录音链路、权限拒绝/永久拒绝流程、老桥 404 隐藏入口都只有单测与编译层证据。
- **运维（2026-10-25，未发版）**：**删除 `upstream` 远端**。原 `Hakunm/dsh-android-app`
  原作者早已停止更新，该远端从建仓起就没再 fetch 过，留着只是让 `git remote -v` 多一行噪音。
  删的是 **git 远端**，不是署名 —— 代码血缘与 AGPL 归属仍在 README 双站、`NOTICE`、
  `AGENTS.md` 与 `docs/project/CHANGELOG-DEV.md` 里写明。现远端为 `origin`（GitHub 从站）/
  `gitee`（主站）/ `atomgit`（备站）三个。同步改了 `scripts/setup-remotes.sh` 的注释与
  末尾远端清单。产品代码零改动，不发新版。
- **v2.2.55（versionCode 10080，2026-10-25，发）**：**对齐桥 v2.7.0：模型上下文容量显示 + 空白会话复用**。
  `ModelRow` 开始渲染 `model.contextWindow`（新增 `data/ModelCapacityLabel.kt`，
  1024 进制，`1048576 → 1.0M`）；`DshClient.createSession` 加可选 `reuseBlank`，
  省略即不编码故老桥请求体逐字节不变，新桥则走「复用空白会话」的桌面语义。
  门禁：docsCheck 双向一致（137 锚点 / 118 源文件）、lintDebug 0 error、
  **69 类 / 634 用例 / 0 失败**（新增 `ModelCapacityLabelTest` 7 例）。
  行数全部在 800 以内（`DshClient.kt` 799 / `ChatSheets.kt` 781）。
  **未真机验、未推送、未发版**；`reuseBlank` 的实际效果需桥 v2.7.0 部署后才闭环。
- **v2.2.54（versionCode 10079，2026-10-24，发）**：**连接页顶栏归位：DSH 图标与语言/诊断按钮同一条中线**。
  用户截图反馈「语言 调试两个按钮距离顶部太靠近，操作不便，美观没有，挪到与 DSH 图标一条线」，
  拆成两个根因：①「太靠近顶部」是 `enableEdgeToEdge()` 全屏绘制而整页无状态栏 inset ——
  顶栏实际画在状态栏正下方；②「不在一条线」是 `BrandMark()` 长在滚动区里、前面垫着
  `Spacer(18.dp)`，与顶栏隔一层层级差。改法：根容器加 `statusBarsPadding()`；`BrandMark()`
  提到顶栏行首，与两个 `IconButton` 走 `Alignment.CenterVertically` 同轴，
  `Arrangement.SpaceBetween` 把按钮推右；滚动区删 `Spacer` + 图标，标题 `top` 20→24dp。
  图标侧 20dp / 按钮侧 8dp **刻意不等**（IconButton 自带 12dp 内边距，8+12=20 才让图标视觉
  左缘与 24dp 文字边距同一竖线）。门禁：docsCheck 双向一致（136 锚点 / 117 源文件）、
  lintDebug 0 error、assembleDebug 成功；单测数量不变（68 类 / 627 用例，纯布局无逻辑改动）。
  **真机已验**（V2080A 1080×2400）：brand 方块、诊断图标、语言图标**水平中线完全重合于
  y=179.5**；触控区 144px = 48dp；中/英 × 浅/深四组截图无裁切。
  遗留：诊断浮层打开时压住 BrandMark 左下角（按 PITFALLS「浮层不调死坐标」不改，浮层可拖动）。
- 日期：2026-10-23
- **v2.2.53（versionCode 10078，2026-10-23，发）**：**设备身份上报：让桥知道这是哪台设备**。
  配套桥 v2.6.1。`DshClient.authenticated()` 统一加 `X-Dsh-Device: android/${Build.MODEL}`，
  HTTP 与 WS 建连同一 builder、一处生效两处；`DeviceView` 加三个可选字段
  `platform` / `model` / `nameSource`（老桥不返回时为 null）。为什么必须客户端做：
  OkHttp 默认 UA 是 `okhttp/x.y.z`，没有平台也没有型号，桥的 UA 兜底对本 App 完全失效。
  门禁 68 类 / 627 用例、docsCheck、lintDebug 0 error、assembleDebug 全绿。
  **未真机验** —— 需桥 v2.6.1 部署后设备表出现「Android · <机型>」才算闭环。
- 日期：2026-10-22
- **v2.2.52（versionCode 10077，2026-10-22，发）**：**过程组不再被劈碎 + 正文图片能看 + 历史不再被刷新抹掉**。
  三条真机反馈连着来，逐条用证据收口：①「加载更早消息后只剩一排『耗时 xxx · N 次工具调用』」——
  `OlderMessagesButton` 按 index/偏移锚定视口，并补加载态；②「不展开时页面上只有组头」——
  空助手消息（真实会话 46 条里 45 条只有 reasoning + tool-call）不再算消息，折进过程组，
  不再把一条工具流水劈成几十段；③「正文里的图片位置是空白」——Markdown 图片走附件通路
  （绝对路径 + 图片扩展名 → 缩略图，点开看大图），其余目标一律降级为链接，渲染器不再收到 `![]()`。
  ④「加载更早后又变空白」的真因是刷新用最后一页整体替换 history，改走 `mergeRefreshedHistory`
  保留已加载旧页（真机 `DiagLog` 的 `totalItemsCount` 5→2 回退是决定性证据）。
  门禁 67 类 / 625 用例、docsCheck、assembleRelease 全绿；用户真机确认「显示出来了」。
- 日期：2026-10-21
- **v2.2.51（versionCode 10076，2026-10-21，发）**：**配色主题四套 + 平板零风险项 + 同步 bridge 2.5.1 安全模型**。
  ① 外观新增「配色主题」四套（纸感/暖米/青灰/高对比，各含明暗两份色板），与「界面风格」正交，
  默认纸感**逐字段等于原配色**（`AppThemeTest` 66 个 ARGB 断言钉住），不切换则零变化；
  新增 `ThemePalettes.kt` / `AdaptiveBreakpoints.kt` 与 `theme_paper|theme_warm|theme_mist|theme_ink` 中英文案。
  ② O-02 断点：840dp 魔数 → 具名常量 + 四档纯函数，与旧判据二进制等价（`PaneModeTest` 16 个宽度点比对）。
  ③ O-06：会话/文件列表补 `contentType`。④ O-08：状态语义色改主题成对色，深色 3.47/3.16 → 9.90/7.79。
  ⑤ O-10：附件删除触摸区 30→48dp。O-03 核实为现状（三页自 `e2ba3dd` 起已居中），未改。
  bridge 2.5.1 收紧管理面判据，本 App 只消费 `/api/v1`、不碰 `/manage`，**零影响**（但补了错误码文案对照表）。
  门验：66 类 / 588 用例 / 0 失败；docsCheck 双向一致；lintDebug 0 error；真机 V2080A 已验（切主题往返逐像素 0 差异）。
- **v2.2.50（versionCode 10075，2026-10-21，发）**：**真机验证收口 v2.2.49 三处瑕疵**（功能面不动）。
  ① 目录通道状态行/校验文案：新建目录条目空目录时按官方说「该提供商没有列出任何模型，请手动添加。」，
  目录通道的空目录报错换成「至少需要添加一个模型。」（自定义通道保留「自定义模型 API 至少需要…」）；
  新增字符串 `models_empty_hint` / `models_needs_models`（中英同步）。
  ② 候选面板跨供应商残留：`AddProviderSheet` 的 `LaunchedEffect(mode)` 与 `LaunchedEffect(picked?.id)`
  先 `clearDiscoveredModels()`，切 tab / 换供应商即清空上一家的「已发现 N 个模型」。
  门验：`docsCheck`（8 文档 / 46 任务 / 14 项根检查全过）+ `:app:testDebugUnitTest`（62 类 / 575 用例 / 0 失败）
  + `:app:lintDebug` + `assembleRelease` 全绿；release 产物 3,143,660 字节，
  SHA-256 `001abbdb…2946d`（`artifacts/SHA256SUMS.txt` 同步）。真机复验：目录选 groq → 自动发现 →
  切自定义 tab 不再残留候选；空目录文案正确。

- **v2.2.49（versionCode 10074，2026-10-21，发）**：**模型/插件设置页对照官方 Web 界面对齐**（bridge 需 2.5.0，见其 CHANGELOG）。
  - **模型页**（`ui/ModelsScreen.kt` 重写 + 新文件 `ui/ModelsEditorSheet.kt`）：
    文案对齐官方（「模型」+ 简介「填入各提供商的 API 密钥即可使用其模型。」）；只读横幅
    「当前部署的设置文档为只读。」；添加卡双 tab（「第三方模型提供商」目录下拉 = 休眠的
    llm-pi-ai 条目 /「自定义模型 API」）；共用编辑器：API 协议**下拉**（原自由文本）、
    API 密钥 placeholder 分档（已保存凭据/已检测到环境变量）、模型目录结构化行
    （ID/显示名称/上下文窗口/最大输出 token 数，K/M 容量解析 + 逐行校验文案）、
    「恢复默认模型」（wire `models:null`）、discover 候选面板（搜索/全选/取消全选/添加所选）、
    **删除供应商**（确认框文案按凭据有无分档）；打开卡片捕获 revision 作 expectedRevision，
    冲突 409 SETTINGS_CONFLICT →「这张卡片打开期间…」，成功才关卡片（providerSaved 反馈）。
  - **插件页**（`ui/PluginsScreen.kt`）：官方清单页交互形态 —— 「搜索插件」过滤名称/描述、
    无匹配空态「没有匹配的插件。」、行卡可展开（主行 = 名称 + 状态徽标 + 开关，
    展开显示完整名称/已安装/声明版本/主页/不可启停原因）。
  - 数据层：`ProviderView` +settingsNs/defaultBaseURL/baseURLEnv/nativeAuth（可选字段，老服务端兼容）、
    `DshClient.deleteProvider/resetProviderModels`、`HarnessState.providerSaved`、
    错误映射 +SETTINGS_CONFLICT；字符串 53 条中英同步。
  兼容兜底：旧 bridge（无 `settingsNs` 目录元数据）不显示目录添加候选、不提供「恢复默认模型」，
  目录 tab 改为提示「模型目录需要 bridge ≥ 2.5.0。」（数据驱动判定，不看版本号）。
  门验：`docsCheck`（8 文档 / 46 任务 / 14 项根检查全过）+ `:app:testDebugUnitTest`
  （62 类 / 575 用例 / 0 失败）+ `:app:lintDebug` + `assembleRelease` 全绿；
  release 产物 3,142,644 字节，SHA-256 `c06ec719…3ebd3`（`artifacts/SHA256SUMS.txt` 同步）。

- **v2.2.48（versionCode 10073，2026-10-21 发）**：三笔未发版合并随本版发布 ——
  ① **Markdown 表格贴行不渲染**（2026-10-20 完成、真机已复验）：org.intellij.markdown 对
  「表头紧贴上一行、无空行」的 GFM 表格不产出 TABLE 节点，整表并入上一段落显示成一串竖线文字；
  修法：新增 `ui/chat/ChatMarkdownNormalize.kt` 渲染前补表格前边界空行（围栏感知 / 流式安全 / 幂等，
  `@patch` `glued-gfm-table-needs-blank-line`），`AssistantBody` plain/prose 两分支都接入；
  连带修三列及以上表格首列被窄列截断（`ChatMarkdownTable.kt` 仅 ≥3 列时首列按权重折行）。
  ② **应用内更新检查**（2026-10-21 完成、真机已复验）：设置「关于」区「检查更新」条目，
  `data/UpdateChecker.kt` 按界面语言分流渠道（zh*→Gitee、其他→GitHub），下载 APK 到
  cacheDir/updates/ + SHA256SUMS 校验 + FileProvider 安装引导；下载/安装链路未上机（当时无新版可触发）。
  ③ **会话内联交付物卡片**：见下条。门验（发版终态）：**62 类 / 575 用例 / 0 失败** +
  docsCheck 全绿 + lintDebug 0 error + assembleRelease 成功。
- **v2.2.48 · 会话内联交付物卡片（对齐桌面端）**：用户对照两端截图发现电脑会话里
  交付物有内联文件卡片、手机端只有「本会话的文件」弹层。数据一直在历史里，是 `displayItems()`
  把 `deliverables/presented` 整类丢掉。修法：`ChatItemKind.DELIVERABLE` + 两段式冲刷
  （按出现顺序缓冲，每轮 `turn/end` 处出卡、钉在轮次收尾；无 `turn/end` 补尾；
  同轮同路径单卡取最后说明）。新卡片 `ui/chat/ChatDeliverableCard.kt`，点按走
  `openSessionFile` + 文件面板（与正文链接同路）。
  门验：**62 类 / 575 用例 / 0 失败** + docsCheck 全绿 + lintDebug 0 error；
  回归用例 `ChatDeliverableCardsTest.kt` 6 例。**真机复验由用户自行完成**。
- **v2.2.47（versionCode 10072，2026-10-21 发）**：**文档链接带位置后缀打不开**（与鸿蒙版同源双端修复）。
  用户真机截图：`docs/project/STATUS.md:1`、`/…/STATUS.md#L101` 点开报「找不到这个路径」，
  文件其实存在。**两层根因**：① 位置后缀（`:1` / `:12-34` / `:12:5` / `#L101`）被当成路径
  的一部分发给 `roots/resolve`；② 分类用 `indexOf(':')` 一刀切认 scheme，`docs/x.md:1`
  被判成「未知协议」，连拼 cwd 的机会都没有。**修法**：`data/ChatLinkTarget.kt` 新增
  `stripLocationSuffix()`（只剥确定形态、最多两段、剥空不剥）+ 纯函数 `joinUnderCwd()`
  （相对路径拼 cwd 前剥），`ChatLinkHandlers` 改调后者。刻意不动 `https://e.com/a#L1`
  （页内跳转）与 `http://host:8080`（端口），靠「网页分支返回原文」保住，均有单测钉住。
  门验：**60 类 / 542 用例 / 0 失败** + `docsCheck` 全绿 + `lintDebug` 0 error；另修掉上一个提交留下的
  坑位索引红灯（`busy` 标题内层反引号与索引正则冲突）。坑位见 `PITFALLS.md`
  §文档链接带位置后缀。**真机复验由用户自行完成**。
- 日期：2026-10-20
- **「回答被截断」提示真机端到端复核通过（零代码改动）**：v2.2.44 验收报告里唯一悬置的「⏸ 未验证」项就此闭环。
  当晚 23:12 真实触发一次 max-tokens 截断（会话 `session-9b3d9ba9-b1aa-4cfa-b40d-d51fe80a5c44`），
  四层取证：① 内核会话日志 `~/.dsh/sessions/<工作目录>/…/session.v4.jsonl.zstd` 里
  `{"type":"turn/end","seq":3676,…,"reason":{"kind":"max-tokens"}}`（同日志仅 2 条 turn/end，
  再次证实 `sessions.history` 不返回 turn/end、提示只能靠实时帧）；② web profile 运行中的
  bridge 2.4.3 `lib/index.js` 含 `reason: turnReason` 透传；③ 真机 App 2.2.46
  `TRUNCATED` 黄条全链路在岗；④ **用户亲眼看到提示**。结论：此前「无提示」系旧版双端所致，
  升级后自愈。环境事实随记：desktop 实例（`~/.dsh-desktop`）**未装** remote-bridge 插件，
  App 实际连的是 web profile（`dsh --profile web --port 35869`）。
- 日期：2026-10-19
- **v2.2.46（versionCode 10071，待发）**：后台任务面板（会话 ⋯ 菜单 →「后台任务」）。
  消费 bridge 2.4.3 的 `/chat/sessions/{id}/jobs` 三端点与 `job-list-v1` / `job-output-v1` /
  `job-kill-v1` 能力位：清单（状态徽标 / 运行时长 / 进度 / 终态原因）、展开已保留输出
  （`lossy` 时明说不是完整输出）、终止（确认框；`already-finished` 显示「它已经结束了」）。
  入口按能力位显示，老 bridge 上不出现。真机验收：入口/清单/输出/刷新全绿；
  终止确认框未点到（本机无 running 任务）。
- **已发版 v2.2.45（versionCode 10070，2026-10-19）**：真机验收三缺陷收口 + 用户逐条点名的九项交互整改
  + 交付物链接打不开。产物 `DSH伴侣-companion-v2.2.45.apk`，**3,034,832 字节**，
  SHA-256 `6bd2634d08648f71ec5d0d32875a0228cf93f3ddaed7e013543e08c8bf9e713f`；
  门验 **57 类 / 505 用例 / 0 失败** + docsCheck 8 文档 + lintDebug 0 error；
  需 bridge **≥ 2.4.2**（投影透传 + 权限档位 BFF + 斜杠命令执行修复）。
- **上一版收口记录（v2.2.44 真机验收三缺陷）**：验收报告报 3 失败 + 4 有缺陷，
  动手前先在源码与**正在运行的 bridge** 上复核根因，修正了报告里三条结论（详见 `CHANGELOG-DEV.md` 顶部条目）：
  ① **F1 权限档位**：实测 `messages` 端点返回体**没有 `projections` 字段**，候选项在独立远程服务
  `permissionPresets.catalog()` 上 —— 故 App 侧单改读 `preset/sandbox` 并不够，
  **bridge 已补 `GET /api/v1/chat/permission-presets` BFF**，App 改为「当前值取投影 + 候选项取目录」两路合成，
  且候选项为空时仍显示当前档位（老 bridge 降级为只读标签，不再整块消失 —— 这正是 v2.2.44 真机的症状）。
  ② **F3 `@` 补全卡死**：真实原因不是报告说的「会话没进列表」，而是**创建路径不经过 `selectSession`**，
  而 `loadMentionEntries()` 只挂在它上面；两个创建入口都已补调用，并给面板加了重试按钮。
  ③ **F2 文件创建静默失败**：端点实测正常（201 / 409 均有），报告推不出「请求没发出」；
  已修的是**错误不可见**（静默早退改抛错走 snackbar）。**请求未达 bridge 那一层仍未定位，待真机复测。**
  另修 W1（统计轮次只数 `source.kind == "user"`）、W2（表格单元格走行内渲染）、W3（未保存保护 + 返回拦截）。
  **W4（构建水印双值）未动**，属 Agent 侧 watermark 口径，需先确认再议。
  门验：**55 类 / 493 用例 / 0 失败**；bridge `pnpm check` 全绿（39 文件 / 297 用例）。
  **真机复验未做**（本轮无设备）—— 单测绿 ≠ 行为对，出包后必须再上机。
- 日期：2026-10-19
- **已发版 v2.2.44（versionCode 10069，2026-10-19）**：**工具专用块 + 权限档位 Pill + chat.html 对标补全 + 会话页存量修复**。已双推 origin + gitee、打 tag `v2.2.44`、双站 Release 与 APK 已上传。
  - **工具专用块 + 权限档位 Pill**（依据 `workspace/资料调研/官方Web界面与APP功能对比_2026-10-18.md`）：
    ① `data/ChatToolBlocks.kt` 按 name 分发 Read / Terminal / Diff / Generic（bash 71% / edit 9% /
    read 8% / write 2%，本机 3000 条实测）；ANSI 剥离、解析失败一律回落 Generic；
    `ui/chat/ChatToolBlocks.kt` 三种专用块接入 `ToolCallDetailBlock`，非 Generic 的失败输出仍以
    error 块补显。② `ui/chat/ChatPermissionPill.kt` 发送区上方常驻当前档位，与 ⋯ 菜单同源同动作，
    完整访问仍二次确认。③ chat.html `toolEntry` 同款四分支同步。④ Goal 入口**本轮不做**：
    核实发现桥上无 goal API（对比文档「goal API 已在桥上」不实），需先在 dsh-remote-bridge 补 BFF。
  - **chat.html 拆分**：2575 → **355 行壳**，样式与脚本搬进 `assets/chat/`（chat.css / core / render /
    tools / interact / cards / bridge），加载顺序 core 最先；file:// 相对子资源天然可用，Kotlin 侧零改动。
    搬迁等价性机检：拼接内容与原 `<script>` 区块 **diff 为零**；APK 内 7 资产齐全。
    约定升级：新功能直接落 `chat/` 对应域文件，不再回填 chat.html。
  - **chat.html 对标补全**（用户逐项点名）：模型选择**按厂家树型分组**；新增**工具轨迹**与**统计**
    面板；新增 **`@` 文件补全**（原生 init 多传 `rootId`/`cwd`/`cwdAbsolute`，进会话预拉
    `roots/{rootId}/entries`，选中插绝对路径）；顶栏 `#status` 移末尾让
    `▤文件 → ⟳刷新 → ⇥插话 → ■停止 → ⋯更多` 连续靠右；**修 `/` 命令面板发送后不落回**
    （根因：`#cmd`/`#mention` 的 `display:flex` 优先级压过 `[hidden]`，改默认 `display:none`）。
  - **「回答被截断」提示**：`HarnessTruncationViewModel` 读 `turn/end` 的 `reason.kind == 'max-tokens'`，
    配套 bridge **v2.4.1** 透传该字段（见姊妹仓）；同批落地诊断日志开关（`debug/DiagLog` +
    `HarnessDiagViewModel`）、连接导入面板 `ConnectImportPanel`、进会话**落底跟随** `ChatScrollFreeze`。
  - 门验：**55 类 / 478 用例 / 0 失败** · `docsCheck` 8 文档一致 · 行数门禁绿（ChatDetail 807 → 800）·
    `lintDebug` **0 error**（首跑 3 个 `LocalContextGetResourceValueCall`：`SettingsScreen` 点击回调里
    `context.getString` 已改为组合期 `stringResource` 预取）。
  - APK：`DSH伴侣-companion-v2.2.44.apk`，**3,012,936 字节**，SHA-256
    `9d004d81d08e7e178008c83faaeb7c39008118b916d591191903028df4e13df0`。
  - 未做：`@` 补全与截断提示的真机端到端复核（截断提示需 bridge ≥ 2.4.1）。
- 日期：2026-10-17
- **在途（真机反馈五修，未升版本号）**：**状态条合并**（用户要求：「把『内容由 AI 生成，请甄别后使用』
  与显示思考过程的重叠在一栏显示，有思考内容时显示思考，没有时再显示这个」）——
  新增 `ChatStatusBar`：`thinkBarText(liveThinking).isNotEmpty()` 为真时渲染思考条（可展开），
  否则渲染 AI 标识条。原来两者是并排两行，思考一来就把标识挤上去。
  新增 `ChatStatusBarTest` 4 例（含**首跑抓到断言错误**：截断后是「省略号 + 140 字符」= 141，
  我断言成 140 —— 实现对、断言错）。门验：**47 类 / 425 用例 / 0 失败**。
- **在途（真机反馈三修，未升版本号）**：**加「构建水印」终结「改了没生效」的争议** ——
  用户两次重装后仍反馈表格/时间戳没变化。源码、提交、R8 映射三处都证明改动在包里
  （`mapping.txt` 里有 `GFMFlavourDescriptor` 18 条、`ChatMarkdownTableKt` 全量保留），
  但**无法证明用户装的是哪一个包**（三次重打共用同一 versionName 与文件名）。
  故在设置页「关于」的版本行下加一行 `构建 <git短哈希>`，`buildConfigField` 取 `git rev-parse --short HEAD` ——
  装完看到 `ffe2fb5` 就是本次修复。**顺带纠错**：先前用「dex 里搜类名」验证产物是**错的**
  （R8 混淆后类名会变，dex 字符串池搜不到不代表没打进去），正确做法是查 R8 映射文件。
  门验：**46 类 / 421 用例 / 0 失败**。
- **在途（真机反馈二修，未升版本号）**：**表格不生效的根因是 flavour 没给** ——
  渲染库默认用 **CommonMark**，而 CommonMark **不解析 GFM 表格**，所以
  `MarkdownComponents.table` 回调**根本不会被调用**，表格被当成普通段落渲染（图1 里竖排密排）。
  已给全部 7 处 `Markdown(...)` 传 `flavour = GFMFlavourDescriptor()`，表格节点才会被解析。
  表格样式按 Whaleness 重做为**两列**（左字段名窄列、右值宽列折行，行间细分隔线，
  一块浅底不浮起）—— 第一版的「一字段一张卡」被反馈「没可读性了」。
  时间戳二修：`bottom = 10.dp` + 消息流 `5dp → 8dp`。
  新增 `MarkdownFlavourTest`：**源码级守卫**（每处 `Markdown(` 必须传 GFM flavour），
  并做过变异验证（删掉 flavour → 红、恢复 → 绿）。
  门验：**46 类 / 421 用例 / 0 失败**。
- **在途（真机反馈修复，未升版本号）**：**会话页两处体验修复**（用户真机反馈）：
  ① 消息时间戳与**下一条消息重叠** —— `ChatMessageFooter` 的时间戳只有 `top = 3.dp`、
  没有底距，而消息流本身只有 5dp 间距。补 `bottom = 6.dp`（与正文 3dp、与下一条 11dp）。
  ② **Markdown 表格被截断** —— 渲染库自带的表格是横向滚动 + 固定列宽（内部 `tableMaxWidth` /
  `widthIn`），手机上一宽右侧就切掉。该库 `MarkdownComponents` 有 `getTable()` 开放接口，
  故覆盖成**纵向卡片**：一行数据一张卡，卡内「字段名 + 值」、值自然折行（`ui/chat/ChatMarkdownTable.kt`）。
  解析失败时回落到 `MarkdownText`，**不吞内容**。
  ⚠️ 其中一条用例首跑就抓到实现的**真实 bug**：转义逻辑把 Windows 路径 `F:\work` 吃成 `F:work`
  （实现第一版见 `\` 就消费后一个字符）—— 而用户反馈的表格里恰好全是这种路径。
  门验：**45 类 / 420 用例 / 0 失败**。
- **在途（质量收尾，未升版本号）**：**测试数字一致性并入常设门禁** —— 新 `TestCountsTest`：
  README 中/英 + AGENTS §5 三处声明的类数与用例数必须相同，且**用例数 == 源码 `@Test` 注解数**
  （静态计数，不依赖正在写入的 XML）。**首跑如期变红**（新测试自己让 409 → 410），
  同步三处后转绿 —— 这条门禁要的就是「加测试必须同步文档」。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（质量收尾，未升版本号）**：**中英 strings 一致性并入常设门禁** ——
  新 `StringResourcesTest`（键集合双向一致 + **占位符一致** + 下限断言；用 XML 解析器读资源）。
  占位符那条防的是**崩溃**（英文少一个 `%1$s` → `MissingFormatArgumentException`），不只是显示问题。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（质量收尾，未升版本号）**：**坑位索引一致性并入常设门禁** —— `ArchitectureGuardTest`
  新增一条测试（索引 ↔ `PITFALLS.md` 正文**双向**一致 + **下限断言**防解析失败被当成"一致" +
  按措辞而非编号找小节）。门验：**44 类 / 410 用例 / 0 失败**。
- **在途（B 批，未升版本号）**：**Clock/Transport 接缝** —— `ConnectionCoordinator` 加
  `measureOverride`（默认墙钟，测试注入受控测量）；原逻辑**逐字搬进** `probeWithWallClock`，
  超时/关闭/取消语义**仍归协调器**（接缝只替换「怎么测」，不替换「怎么保证不挂死」）。
  三个依赖精确延迟比较的用例改用 `fixedLatency`，两个真实行为用例保持真实延迟。
  **DEBT-007 当天立、当天清**。门验：**44 类 / 410 用例 / 0 失败**（用例数不变）。
- **在途（Whaleness 舒适度，未升版本号）**：**轨迹页 UI** —— 新 `ui/chat/ChatTrajectorySheet.kt`
  （按轮次时间轴 + 点节点展开**原始 JSON**，复用 `ChatCodeBlock` 高亮）+ 数据层补 `raw` 与
  `shortDuration` + ⋯ 菜单入口。**只做「原始」一栏**：竞品的多 Tab 需要按工具类型解析载荷，
  而「参数/结果/Schema 挂在哪个字段」本仓**不可证**。**第三次被行数守卫抓到**
  （ChatDetail 810 → 拆出 `ChatHeader.kt`：163 行顶栏 → ChatDetail 636 / ChatHeader 214）。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（Whaleness 舒适度，未升版本号）**：**轨迹页数据层** —— 新 `data/Trajectory.kt`
  （`trajectoryOf` 按轮次分组、组内按 seq 排序；`Other` 类型**不丢弃**；耗时**只在时间戳单调时**给值）。
  UI 留下一轮。顺手记下 **DEBT-007**：`ConnectionCoordinatorTest` 的延迟容差用例是 flaky
  （实测延迟受机器负载影响），清账路径 = B 批的 Clock/Transport 接缝。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（C 批，未升版本号）**：**交付物实时到达** —— `connectEvents` 认出
  `chat.deliverables.presented`（姊妹仓同轮补的正式事件名）并触发一次刷新，
  文件卡片不再等下一次消息完成才更新。**无单测**（ViewModel 层无测试基建，如实记录）。
- **在途（C 批，未升版本号）**：**会话统计（执行维度）** —— 新 `data/SessionStats.kt`
  （轮次 / 助手消息 / 工具调用 / 事件总数 / 数据截至 `#seq`，判据与 `ChatHistoryMapping` 同源）
  + `ui/chat/ChatStatsSheet.kt`（会话页 ⋯ 菜单入口）。
  **上下文用量环没做**：内核 usage **不可证**（`node_modules` 下没有内核包），
  没有数据就不画环；也**不给字符数估算**（误差成倍，一个不可靠的数字比没有更坏）。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（C 批，未升版本号）**：**断档检测与 resync** —— 新 `data/ChatSeqMonitor.kt`
  （`seqVerdict` 跳号判定 + `baselineBehind` 基线比较）+ `HarnessState.lastEventSeq/baselineCursor`
  + `connectEvents` 统一过闸（命中即 `scheduleChatRefresh()`，自带去抖与单飞）。
  消费姊妹仓同轮的 `chat.session.snapshot`。**换会话必须清空序号**（不同会话序列不同，混比必然误判）。
  判据只认**严格跳号**：重复与倒退交给去重（判太紧会让每次重连都全量重取）。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（B 批，未升版本号）**：**历史投影缓存** —— 新 `data/ChatVisibleItems.kt`
  （可测核心 `visibleItemsOf`）+ `ChatDetail` 用 `remember(state.history, showContext)` 缓存投影。
  核过的真问题：投影是**逐事件映射的纯函数**，却在 Composable 体内每帧重跑（流式期间每秒几十帧）。
  `liveItems` 刻意不缓存（每帧都变，加了是假优化）。**第二次被行数守卫抓到超预算**
  （ChatDetail 797→越过 800），拆出 `ui/chat/ChatScrollFreeze.kt` → 781。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（B 批，未升版本号）**：**事件去抖** —— 新 `data/EventDebouncer.kt`（**尾沿去抖**，
  用 `runTest` 虚拟时间测）+ `HarnessViewModel.directoryDebouncer` 接到 `file.*` 分支。
  核过一件事：我们**没有事件队列**（事件直通 `update`，`LiveChatState` 按 step 分块天然有界），
  真正无界的是**事件驱动的副作用**（旧实现每帧 `file.*` 发一次 `entries()` 请求 → 请求风暴）。
  窗口 500ms；已知边界「事件不停时饿死」与既有的 `scheduleChatRefresh` 同取舍。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（B 批开工，未升版本号）**：**请求代际隔离** —— 新 `data/RequestGate.kt`
  （`current`/`invalidate`/`isCurrent`，`AtomicInteger`）+ `launchOperation` **在调用点领号**、
  回来过闸；三个切换点已接（`selectSession`/`connectHostInternal`/`disconnect`）。
  挡的是「旧会话的响应回来覆盖新会话状态」（标题 B、内容 A 那类静默错乱）。
  门验：**44 类 / 410 用例 / 0 失败**（含 8 线程 × 500 次并发领号不重复）。
- **在途（第六批，未升版本号）**：**A 批收官 —— `atSeq` 分叉入口**。新 `data/ChatForkPoint.kt`
  （只给用户消息、`seq > 0`）+ `forkSession(sessionId, atSeq)` + 时间戳行里的低对比度按钮，
  确认框由 `ChatDetail` 单点持有。入口**不占任何既有手势**（长按留给选词 —— 这是既有坑位）。
  **顺带被 `ArchitectureGuardTest` 抓到超预算**（ChatMessageItems 806 / ChatDetail 805），
  按制度拆分出 `ChatMessageFooter.kt`（84）与 `ChatForkDialog.kt`（39）→ 752 / 797。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（第五批，未升版本号）**：**消息气泡带时间戳** —— `data/ChatTimeLabel.kt`
  （三档粒度：今天只给时刻 / 今年给月日 / 更早给年月日，中文单独一支 pattern）+
  `ChatItem` 只为**消息**显示、跟随气泡一侧对齐、按值 `remember` 缓存。
  数据来源早就存在（历史事件的 `event.time`，过程组耗时摘要已用它），**不需要 bridge 支持**。
  门验：**44 类 / 410 用例 / 0 失败**。
- **在途（第四批，未升版本号）**：**已归档会话可看** —— `DshClient.sessions(archived)` +
  `HarnessState.archivedSessions` + 会话面板的「已归档 / 返回会话」切换（归档行用独立组件，
  不给重命名/分叉/归档菜单）。**入口按能力位 `session-archive-list-v1` 守卫**：
  老 bridge 会忽略 `archived` 参数并返回未归档列表，不判能力位就会展示**假结论**。
  归档视图明说「本插件暂不支持恢复归档」。门验：**44 类 / 410 用例 / 0 失败**。
- **在途（第三批，未升版本号）**：**失败行可「重试上一轮」** ——
  新 `data/ChatRetry.kt`（取历史里最后一条有正文的用户消息）+ `retryLastUserMessage()`
  + `TurnFailureRow` 的重试按钮（登出停止不给按钮）；两条渲染路径都接线。
  `atSeq` 分叉 UI **有意未做**（消息级长按菜单会重踩「`combinedClickable` 吃掉长按、
  选词手柄弹不出」那个坑，需单独设计入口）。门验：**44 类 / 410 用例 / 0 失败**。
- **在途（第二批，未升版本号）**：**客户端接入服务端能力位** —— 解析 `connection.ready` 的
  `bridgeVersion`/`capabilities`，设置页在「读到清单且缺关键能力」时**明说**
  「插件过旧、审批与问答卡片不会出现」；清单缺失或为空一律算**未知**（不提示）——
  假警告会让用户学会忽略警告。门验：**44 类 / 410 用例 / 0 失败**、`compileDebugKotlin` 真执行。
  动机是「卡片不弹」与「本来就没有待审批」在屏幕上完全一样（该坑踩过两次，根因都在插件侧）。
- **在途改动（未升版本号）**：对标第三方移动端 Whaleness（详见 `dsh-study/分析-2026-10-16/` 六份报告）
  的 **A/B 两批客户端第一批**已落地 —— 会话页「滚动期冻结流式快照（80ms 稳定窗口）」、
  「过程分组折叠 + 耗时摘要」（为此给 `ChatDisplayItem` 补了 `time` 字段）、思考条可展开、
  设置页新增**外观**组（主题三档 + 应用内字号缩放 0.8–1.5× + 语言跟随系统）、
  代码块高亮/语言标签/复制、消息内图片点击查看与保存相册、会话文件面板下载，
  以及把工程纪律变成可执行门禁的 `ArchitectureGuardTest`（上线即抓到 `web/ChatWebBridge.kt`
  自建网络客户端，已立账 `TECH-DEBT.md` DEBT-006）。
  门验：**44 类 / 410 用例 / 0 失败**、`compileDebugKotlin` 真执行、`docsCheck` 通过；
  **真机验收未做**（滚动手感、折叠观感、200% 系统字号布局都只能上机看）。
  C 批（bridge 侧 `seq` 隔离 / 原子基线 / 交互重放等）与 A/B 剩余项见 `CHANGELOG-DEV.md` 2026-10-17 条末节。
- 阶段：本 fork 的 **2.x 版本线**。**v2.2.43（versionCode 10068）= 纯仓库卫生，无任何代码或界面改动**（2026-10-17，清掉 40.9 MB 调试残留 + 把 `.secrets/` 改为整目录忽略；APK 代码与 v2.2.42 完全一致）。上一版 **v2.2.42（versionCode 10067）对标补强 A/B/C 三批 + Whaleness 会话页舒适度已发布**（2026-10-17，tag v2.2.42，Gitee Release 已回验）。再上一版 **v2.2.41（versionCode 10066）应用改名 + 包名迁移，Gitee 与 AtomGit 双主备就位**（2026-10-16，tag v2.2.41，Gitee Release 已回验）。**GitHub 旧仓已删除并重建为镜像从站**，由 Actions 每 3 小时从 Gitee 自动同步（详见下文「渠道状态」）。
  应用名 `DeepSeek Harness` → **`DSH伴侣`**（英文 `DSH Companion`），包名 `io.github.hakunm.deepseekharness`
  → **`io.github.westanke.dshcompanion`**。动因是华为应用市场驳回「应用与『DeepSeek』名称相似、未提供商标权属证明」
  —— `NOTICE` 那份 MIT 只覆盖 logo **著作权**，不是**商标权**。
  **代价**：`applicationId` 变了就是另一个 App，老用户**无法覆盖升级、必须先卸载**（卸载清空已保存的连接，需重新配对）。
  门验：290 用例 0 失败（与 v2.2.40 逐项一致）、compileDebugKotlin 真执行、docsCheck 通过；
  **UI 测试与真机验收未做**（包名变更要求先卸载旧包）。
  其下 **v2.2.40（versionCode 10065）真机验收通过，待发版**（2026-10-16，① 连接与启动 ② 会话列表 ③ 消息流与工具详情 ④ 输入区 ⑥ 文件/插件/设置/会话文件面板 ⑦ 切会话与熄屏亮屏 **全绿**；⑤ 审批与问答卡片**当时**记为「不弹」，**该结论已于同日推翻——病根全在插件侧，已随 bridge v2.3.7 修复并真机复验通过**）。
  该版是**纯重构，零界面与行为改动**：按职责拆文件，`TECH-DEBT.md` 行数台账上的三条债全部清账（`ChatScreen.kt` 3882→329 + `ui/chat/` 12 个文件、`HarnessViewModel.kt` 2277→663 + 8 个域文件、`ChatPresentation.kt` 825→98 + 5 个文件），`app/src` 下**零个文件超 800 行**；290 用例 0 失败、build / docsCheck 全绿。其后三笔均为**纯文档/清理**，不进版本号：清 `ChatScreen.kt` 三处死 import、补 `PITFALLS.md` 的 `resolveAgent` 原因、撤掉「卡片不弹」缺口并把 README 双站的 bridge 门槛提到 **≥ 2.3.7**。
  版本线（v2.2.35 之后）：v2.2.36 平板三处 → v2.2.37 导航默认收起与软/硬键盘发送键 → v2.2.38 修两个实测回归 → **v2.2.39 修 Ctrl+Enter 发送（`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据）；界面回到 v2.2.38 状态**（v2.2.39 曾做的「会话列表收起」经实测后已整套回退，一级导航仍默认收起）→ **v2.2.40 纯重构**。`v2.2.35`（versionCode 10060）**已发版**（GitHub + Gitee 双站 Release，tag v2.2.35），v2.2.34 同日发布；**v2.2.36–v2.2.40 一并随 v2.2.40 发布**（中间五版未单独发版）→ **v2.2.41 应用改名「DSH伴侣」+ 包名迁移**（唯一一版破坏覆盖安装、老用户须先卸载）→ **v2.2.42 对标补强 A/B/C 三批 + Whaleness 会话页舒适度**（已发布，tag v2.2.42）→ **v2.2.43 纯仓库卫生**（清 40.9 MB 调试残留 + `.secrets/` 整目录忽略；APK 代码与 v2.2.42 完全一致）
- 版本线：v1.1.0 多地址连接 + 配置导入 → v1.2.0 插件清单页 → v1.4.0 会话文件与图片 → v1.9.0 长按选中/双击复制 + 文件按类型预览 → v2.0.0 起会话详情页增加 WebView 实现（2.2.15 起把网页版呈现方式反向移植回原生）→ v2.2.23 问答卡片、图片 PDF 预览、工具调用详情 → v2.2.24–2.2.26 模型失败可见、图片交接可用、出错文案跟随界面语言 → v2.2.27 真机七问题收口 → v2.2.28 补上「长按可选中复制片段」→ v2.2.29 耗电主因（流式期间每秒全量拉 history → 400ms 静默去抖）+ 跳转落到会话尾部 + isAtBottom 改 derivedStateOf + 浮动按钮半透明 → v2.2.30 熄屏断流、亮屏接回（省电第二大头）→ v2.2.31 输入框 `@` 调取当前工作目录文件/文件夹（对齐官方）→ v2.2.35 长文件名改为横向滚动（文件页 / 预览标题 / 会话文件弹层 / `@` 面板四处统一）→ v2.2.36 平板界面三处优化（会话列表栏宽随屏宽伸缩 + 会话行/顶栏标题 wide 下两行 + 未选会话时右侧改概览面板，全部只在 wide 分支、手机端零改动）→ **v2.2.37 平板导航默认收起（overlay 抽屉 + 48dp 汉堡 + 用完即收，内容区从「屏宽−264dp」变整屏）+ 软/硬键盘发送键（物理键盘时输入框不弹软键盘 + 空闲占位符追加「Ctrl+Enter 发送」；Enter 一律换行的底线不动）→ v2.2.38 修 v2.2.37 两个实测回归（汉堡按钮从浮在内容之上改为左缘独立 48dp 竖条，不再压住会话列表标题导致点不动；软键盘 hide 从 Composer 组合时改到输入框 onFocusChanged 获焦时，否则 IME 接管后物理键盘的 Ctrl+Enter 全被吞掉）→ **v2.2.39 修 Ctrl+Enter 发送（`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据：触摸屏 Enter 一律换行，只有物理键盘的 Ctrl+Enter 才发送）；界面回到 v2.2.38 —— v2.2.39 曾做的「会话列表收起」经实测后已整套回退（含组件、字符串、代码），一级导航仍默认收起**
- 包名：**`io.github.westanke.dshcompanion`**（v2.2.41 起；v2.2.40 及更早为 `io.github.hakunm.deepseekharness`，只留在 git 历史里）；Android 基线：min SDK 26、target/compile SDK 36、JDK 17
- 插件基线：`dsh-remote-bridge` **≥ 2.3.7**（旧名 `dsh-workspace`；Web 挂载前缀仍是历史名 `/dsh-workspace-api`，未随改名变动）。分项：「加载更早消息」**要求 ≥ 2.3.1**（2.3.0 及更早的 `history()` 会忽略 `beforeSeq`，点按钮不会有任何反应）；「插件」页启停 **≥ 2.3.3**；**审批 / 问答卡片 ≥ 2.3.7**（更低版本手机端一张都不显示——该症状曾连续坏在两个插件侧病根上，App 侧代码自始至终是对的，详见 `PITFALLS.md` §bridge 版本基线）
- 最近验证：**436 用例 / 0 失败**（v2.2.43 本轮重跑：48 个测试文件、50 个测试类；口径：跑 `scripts/build.sh :app:testDebugUnitTest`，再数 `app/build/test-results/` 下的 XML）。这个数字随代码变化，不要当常量引用
- **「插件」页现状（v2.2.32）**：只读清单 + 详情（简介 / 主页 / 更新提示）+ **手机上直接启停**。
  - 配套插件端 **≥ 2.3.3**：`v2.3.2` 修了 profile 判定（`dsh web` 简写此前被判不出，整页读不出清单）；`v2.3.3` 加了详情字段与 `PATCH /settings/plugins` 启停端点。
  - 启停只改 profile 的 `dsh.profile.bundles`（加载清单），**不碰依赖、不装包**，写前自动备份 `package.json.bak-<时间戳>`；**必须重启 DSH 才生效**（bundles 只在启动时读）。
  - 实测（用户已重启，插件端报 2.3.3）：profile=web、已加载 17、问题项 3、共 20 条；可启停 15 条（12 启用 / 3 停用：`@liustack/modlens`、`dsh-hyperframes`、`dsh-remotion`），不可启停 5 条全是 `@deepseek-ai/` 官方包。
- **电源管理现状**（两个大头都已处理）：
  - v2.2.29 修掉头号：`scheduleChatRefresh` 的 1 秒轮询 —— 每个事件帧都把标志位置 true（事件分发末尾还有 `else ->` 兜底分支什么都命中），流式输出期间每秒一次全量 `history()`；改成 400ms 静默去抖，实时流式显示走 `chat.message.delta` → `liveChat` 独立那一路，不受影响。
  - v2.2.30 修掉第二大头：`eventSubscription` 原先只在 `onCleared()` 与主动断开时关闭，熄屏后 WebSocket 一直连着（WiFi 射频 + TCP keepalive 全程在线，系统还会反复唤醒进程）。现在 **熄屏断流、亮屏接回**：`MainActivity` 注册 `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON`（**不用 `onStop`** —— 切 App/分屏时屏幕还亮着不该断），ViewModel 加 `pauseEvents()`/`resumeEvents()`，熄屏只关流、**保留 active 连接与令牌**。亮屏补三样：会话列表、当前会话历史、**审批与待答问题**（后两类不走 history，必须单独补，否则熄屏期间产生的卡片永远不出现）。
  - 策略由用户拍板（原话「能让 熄屏 就不在线了，如果不是熄屏状态 实时优先」）。**代价**：熄屏期间收不到推送通知，亮屏一次性补上、不丢内容。
- 当前产物：**只保留「必须的」与「最新的」**（用户要求：已发布到 Release 的 APK 不在本地囤积）：
  - `DSH伴侣-companion-v2.2.44.apk`，**3,012,936 字节**（**当前版本 · 工具专用块 + 权限档位 Pill + chat.html 对标补全 + 会话页存量修复 · 已发 Gitee + GitHub，tag `v2.2.44`**），
    release 构建，R8 + 资源裁剪，SHA-256 `9d004d81d08e7e178008c83faaeb7c39008118b916d591191903028df4e13df0`
    —— 已核验包内 `versionCode 10069` / `versionName 2.2.44`、**v2 签名**（证书 `CN=DSH Pocket Client`，
    与 v2.2.42/2.2.43 同一张证书 ⇒ 可直接覆盖安装）
  - `DSH伴侣-companion-v2.2.43.apk`，**2,965,986 字节**（上一版 · 纯仓库卫生，无功能改动 · **已发 Gitee**），
    release 构建，R8 + 资源裁剪，SHA-256 `5bad043b0d75c96ceb57572245bc25e1f7585cc4f64de867a4ce1d7c27380f9a`
    —— 已核验包内 `versionCode 10068` / `versionName 2.2.43`、**v2 签名**（证书 `CN=DSH Pocket Client`，
    证书 SHA-256 `8b2ba797589da844b779941fec213da3432633062744f64bc916e62df9dbf68a`，与 v2.2.42 同一张证书
    ⇒ 可直接覆盖安装），构建水印 `afbea7d`；**APK 行为与 v2.2.42 完全一致**，只随附版本号
  - `DSH伴侣-companion-v2.2.42.apk`，**2,965,986 字节**（上一版 · 对标补强 A/B/C 三批 + Whaleness 会话页舒适度 · **已发 Gitee**），
    release 构建，R8 + 资源裁剪，SHA-256 `e009b1776aff593bdc3b090def5b6471cdec6cb8e72015faca461b8e109f171a`
    —— 已上传 Gitee Release（tag v2.2.42）并**下载回验**：字节数与 SHA-256 在本地、上传返回、回读三处一致。
    **已在本机删除**（v2.2.43 回验通过后按既定顺序清掉上一版；需要时从 Release 资产下载）
  - `DeepSeek-Harness-v1.0.0.apk` 与 `DeepSeek-Harness-compat02-release.apk`（上游基线，各 2,561,512 字节）。`.gitignore` 特意用 `!` 保留这两个，用途是比对「本 fork 证书 ≠ 上游证书」，**不要删**
  - `SHA256SUMS.txt`：**当前可在本机校验的文件清单**（两个上游基线包 + 最新一版 APK）。
    ⚠️ 2026-10-17 更正：该文件此前的 23 行**全部写着同一个 SHA**（`e009b177…`），
    而实测 `DeepSeek-Harness-compat02-release.apk` 是 `4df87f47…`、`DeepSeek-Harness-v1.0.0.apk`
    是 `80940602…` —— 只有最新一版那行恰好对。历史行是批量复制同一值造成的**错误数据**，
    本地又没有旧包可重算，留着只会误导，故本次重写为只含**真实存在且可校验**的三个文件。
    历史版本的校验请直接对 Release 资产重新计算。
  - 其余版本的 APK（v1.1.0 ~ v2.2.33）**本地不留** —— 两站 Release 都有资产，需要时直接下载；`artifacts/*` 除 `SHA256SUMS.txt` 与上述两个基线包外全部被 `.gitignore` 忽略，**丢了无法从 git 回滚**，所以更不该在本地堆。
  - 发新版本时的动作：构建 → 拷入 `artifacts/` → 上传两站 Release → **回读校验哈希** → 再删掉上一版本地包（等价于「本地只留最新」）。
- **构建类型**：正式包一律 `scripts/build.sh assembleRelease`。debug 包不混淆、不裁剪资源，体积约 **21.8 MB**，与 release 的 2.87 MB 差 7.5 倍 —— 只看体积就能判断打错没打错
- 签名：**`dsh-release.jks`**（`CN=DSH Pocket Client`，**证书** SHA-256 `8b2ba797589da844b779941fec213da3432633062744f64bc916e62df9dbf68a`；alias `dshrelease`，口令见 `.secrets/deepseek-harness-android/signing.properties`，该文件与 `*.jks` 都在 `.gitignore` 内）
  > 2026-10-17 更正：此处原先写的 `e009b177…` 是**某一版 APK 文件**的 SHA-256，被误当成证书摘要。
  > 证书指纹以 `keytool -list -v` / `apksigner verify --print-certs` 的输出为准，与 APK 文件摘要无关。
  - 2026-10-11 说明：**原始那把发布钥匙的密码已彻底丢失**（本机/仓库/CI/git 历史全查过），现用的是**重新生成**的钥匙，主题刻意与旧钥匙一致（同为 `CN=DSH Pocket Client`）。
  - 后果：**它不是旧钥匙** —— v1.9.0 及更早（旧 `CN=DSH Pocket Client`，证书 `4e7fa1db…`）与上游 `CN=Hakunm` 都盖不上去，跨签名升级**必须先卸载**（会清除已保存的连接）。**v2.2.24 之后的包彼此可以直接覆盖**（v2.2.26 → v2.2.27 实测同一把钥匙）
  - 旧钥匙已归档在同目录 `archived-unknown-password/` 下，保留不删
- 仓库：Gitee 主站 `https://gitee.com/westanke/dsh-companion`、AtomGit 备站 `https://atomgit.com/westanke163/dsh-companion`、GitHub 镜像从站 `https://github.com/westanke/dsh-companion`（自动同步，勿手工推）；上游 `Hakunm/dsh-android-app` 只作参考，**不推送**
- 已打 tag（`git tag` 实测，2026-10-17）：`v1.1.0` / `v1.2.0` / `v1.4.0` / `v1.9.0` / `v2.2.20` / `v2.2.23` / `v2.2.26` / `v2.2.27` / `v2.2.28` / `v2.2.29` / `v2.2.30` / `v2.2.31` / `v2.2.32` / `v2.2.33` / `v2.2.34` / `v2.2.35` / `v2.2.39` / `v2.2.40` / `v2.2.41` / **`v2.2.42`** / **`v2.2.43`**（共 21 个）。v2.0.0–v2.2.19 之间没有 tag；**v2.2.36–v2.2.38 没有独立 tag**（随 v2.2.39 一并发布）
- 真机验收：**审批与问答卡片已确认修好（2026-10-16）**，但**修的是插件侧，本 App 一行未改**。plan 模式评审实测：**手机端弹出卡片，电脑端 WebUI 同时正常弹出**（红线「不得抢答」未踩）。病根是 `dsh-remote-bridge` 的两个钩子注册时没带 Cordis 的 `{ prepend: true }`，被 `push` 到 waterfall 链尾、排在官方 WebUI 之后，于是永不被调用；插件 **v2.3.7** 已修并真机验证。**此前「10-11 能弹、10-16 又不弹」两次结论相反的悬案就此解开**：不是非稳定复现，而是 `sessionIdOfAgent`（v2.3.6 修）与 `prepend`（v2.3.7 修）**两个病根叠加**——前者修好后后者仍在，症状看起来「回来了」。**App 侧代码（订阅、拉取、渲染三环）自始至终是对的。**v2.2.27 的七项修复**待本轮真机验收**。**尚未逐项验收**：图片/PDF 预览的翻页与缩放手感、工具调用详情的展开观感、长按选中/双击复制的手势边界、`dsh-ui` 卡片观感、HTML 结构预览排版、输入区 `⋯` 菜单在窄屏与各输入法下的可达性
- 已知缺口：
  - 单测夹具 `ConnectionShareInteropTest.kt` 仍内嵌一枚**已吊销**的真实设备令牌；该文件在 `app/src` 内，是否改写夹具待定（文档侧已全部脱敏）
- 其他文档：`docs/project/CHANGELOG-DEV.md`（逐版开发日志）、`docs/project/PARITY.md`（对等矩阵）、`docs/project/webview-report.md`（WebView 线复盘）、`docs/project/webview-parity-audit.md`（原生 vs WebView 能力边界）、`docs/project/webview-port-todo.md`（移植清单）、`docs/task-14-session-audit.md`（能力缺口审计）、`docs/project/approvals-questions-trace.md`（审批/问答两条链路端到端追踪，9 节带行号）、`README.en.md`（英文README，已同步 2.2.27）
- 插件侧的定位过程值得一读：`dsh-remote-bridge/docs/project/PITFALLS.md` §waterfall 不 prepend 就等于没注册（含 Cordis 源码行号）——最反直觉的一点是，**提问钩子的源码注释里早就写对了机理，只是注册时漏了这个选项**，且失败完全静默、两侧日志都不说一句话。另一份 `RETRO-question-card.md` 复盘的结论是：**卡片代码从头到尾都是对的**，错的是排查方法（先解释现象、却没先确认现象真的发生过）
- 阻塞项：`PARITY.md` 中仍有后续 DSH WebUI 能力未对等；`IME-001` 继续扩大不同厂商键盘实机覆盖
- 下一步：真机验收 v2.2.27 的七项修复与上一条列的未验收项；按需补齐 DSH WebUI 对等能力（设置五大组、终端、插件启停等需要先改插件开 REST 通道）
- **v2.2.42 已发布（2026-10-17）**：Gitee Release 已上线，三个资产（APK / SHA256SUMS.txt / RELEASE_NOTES）全部就位并**下载回验**——字节数 2,965,986 与 SHA-256 `e009b177…f171a` 与本地、上传返回三处一致。main + tag `v2.2.42` 已推 Gitee 与 AtomGit；配套插件 `dsh-remote-bridge` v2.3.9 已推两站。
- **v2.2.43 已发布（2026-10-17）**：Gitee Release id `1212565`（tag `v2.2.43`）已上线，三个资产全部就位并**下载回验**——APK **2,965,986 字节**、SHA-256 `5bad043b…0f9a`，在「本地 / 上传返回 / 从 release 下载回读」三处一致；再用**发布出去的 `SHA256SUMS.txt`** 反向校验下载到的 APK，结果为「成功」。main 与 tag 已推 Gitee + AtomGit，两端 `main` 与 tag 都指向 `5ee39f6`（tag 对象 `f7430a8f`）。**本版无功能改动**，APK 行为与 v2.2.42 完全一致；上一版本地包已按顺序删除。
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
    重建当次另修一处既有 CI 问题：`setup-android@v3` 默认装已下架的 `tools` 包（改显式声明
    `platform-tools platforms;android-36 build-tools;36.0.0`）。
  - **lint 历史欠账已清零（2026-10-16 晚，不升版本号，App 行为变化仅震动修复一处）**：16 个 error
    全部修复——`ChatWebBridge.haptic()` 补 `VIBRATE` 权限并按 SDK 31/26 分级（原代码在 API 26~30
    必崩、被 runCatching 吞成静默失效）、10 处 `LocalContext.getString()` 改组合期 `stringResource`
    （切语言随重组刷新）、`onPicked` 降 internal 收紧 JS 暴露面、一处 `JavascriptInterface` 误报在调用点
    带理由 `@SuppressLint`。现 **0 error / 52 warning / 4 hint（无 baseline）**，`lintDebug` 已提回
    android.yml 硬门（warning 不拦截）。本地门验：lintDebug BUILD SUCCESSFUL、290 单测 0 失败。
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
