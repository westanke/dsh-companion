# task-14 · 会话与执行类能力缺口审计

- 仓库：/media/wangke/OFFICE/workspace/dsh-companion
- 参照：/usr/local/nodejs/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/（官方 WebUI，共 53 个 dsh-client-ui-* 包）
- 性质：**纯审计，未改动任何代码**
- 方法：全部结论来自 grep / sed 读代码，每条给文件+行号+符号名；核实不了的写「未核实」

## 审计表

| 能力 | 官方包/组件 | 官方实现位置 | 我们侧有无 | 我们侧位置或缺失说明 |
| --- | --- | --- | --- | --- |
| 消息反馈 Like/Dislike + /feedback 弹窗 | dsh-client-ui-message-feedback / MessageFeedbackActions | lib/types/client/MessageFeedbackActions.d.ts（export declare function MessageFeedbackActions）；README.zh.md:28「贡献 conversation.chat.assistant-actions 的 feedback 条目（order 10），位于复制与分支之间」 | **无** | 全仓 grep feedback/dislike/rating 仅命中 ChatScreen.kt:2138 一行本地化文案，无 UI、无 REST 端点；DshClient.kt grep feedback 为空 |
| 斜杠命令**执行** | dsh-client-ui-commands | 同上包 | **有** | HarnessViewModel.kt:824-836：sendMessage 内若 line.startsWith("/") 则调 api.executeCommand(id, line)（DshClient.kt:294），409 时抛 COMMAND_FAILED |
| 斜杠命令**弹窗交互**（上下键导航 / Tab 接受 / Esc 归还焦点） | dsh-client-ui-commands / PopupSelectView、directory.d.ts、resolution.d.ts | lib/types/client/PopupSelectView.d.ts；README.zh.md:28「弹窗持有 composer 焦点：键入即本地筛选，上下键移动，回车与 Tab 接受，Escape 与 Shift+Tab 把焦点还给 composer」 | **无** | ChatScreen.kt:2078 CommandSuggestions 是静态 Column + take(5) 前缀过滤（:2086-2089），无键盘导航、无弹窗、无焦点管理 |
| + 按钮的**双分区命令菜单**（添加 / 指令） | dsh-client-ui-commands | README.zh.md:28「composer 的 + 按钮与键入的 / 打开同一个菜单：添加小节（文件、目标、计划、反馈）与指令小节（压缩、权限、模型、下载日志）按使用频次排列」 | **无** | ChatScreen.kt:173 附近附件 + 按钮只做选文件，无命令分区菜单 |
| 会话**重命名** | dsh-api-workspace-controller | lib/typert.host.js 含 @Remote rename rename(request: WorkspaceRenameRequest)，注释「rename one Workspace to a unique non-blank title」——**是 workspace 不是 session** | **有（仅 workspace）** | DshClient.kt:140 renameChatWorkspace；ChatScreen.kt WorkspaceManagementSheet |
| 会话**删除** | 未在官方 session API 层找到 | 在 dsh-api-session-controller/lib/types/*.d.ts 精确搜 renameSession/pinSession/searchSessions/deleteSession 全部为空；grep -c rename dsh-client-ui-chat/lib/client.js = 0 | **未核实** | 官方入口可能在 Host 侧而非客户端包，客户端包内无证据 |
| 会话**置顶 / 搜索 / 排序** | 未找到 | 全官方树 grep pinned/pinSession 仅命中 speech-to-text-sensevoice、http-proxy、web-fetch-http（**均与会话无关**） | **无（官方亦未见）** | 官方 UI 包内无置顶实现 |
| 会话**归档** | dsh-api-session-controller | lib/types/archived-session-gate.d.ts 存在 | **有** | DshClient.kt:237 archiveSession；ChatScreen.kt SessionRow 归档菜单 |
| 会话 **fork** | dsh-client-ui-conversation | README.zh.md:42 动作行「位于复制与分支之间」，确认 branch 存在 | **有** | DshClient.kt:234 forkSession |
| **重新生成 / 编辑重发** | 未在官方客户端包找到 | 精确搜 regenerate/editMessage/deleteMessage 于 dsh-api-session-controller/lib/types/*.d.ts 为空 | **无（官方亦未见）** | — |
| **上下文压缩**（手动入口） | 官方靠宿主命令 /compact | 由 dsh-client-ui-commands 注册的宿主命令目录 | **部分** | ChatScreen.kt:2136 有 compact 本地化描述串；执行经通用 executeCommand 路径（HarnessViewModel.kt:831），**无专用 UI 入口** |
| **TODO 面板** | 官方无独立 todos 包 | ls -d dsh-client-ui-* 共 53 个包，**无** todos 包 | **有** | ChatScreen.kt TodoPanel（内核 projections.todos） |
| **轨迹 / 工具详情**（交互式检视器） | dsh-client-ui-trajectory | README.zh.md:12「按轮次组织的事件记录表和交互式时间概览…对所选记录打开检查器，显示 token 用量、耗时、输入、输出、计时、图片和附件摘要」 | **无** | 全仓 grep trajectory = **0 处**；我们只有 GenUiCard.kt 静态卡片与 AppPreferences.genUiDebug 诊断行 |
| **plan / plan-review** | dsh-client-ui-plan | README.zh.md:12「计划模式让你在实施前审阅计划。通过 /plan 进入…提交的计划自动在右侧边栏打开供审阅」 | **无** | grep plan = 0 处实现（仅 ChatScreen.kt:2137 有 /plan 文案串） |
| **subagent / Agent Teams** | dsh-client-ui-subagent、dsh-experimental-client-ui-agent-team | 两包存在；REST 侧 dsh-api-session-controller/lib/types/client/sessions/remotes.d.ts 含 subagent 类型 | **无** | grep subagent / agentteam = **0 处** |
| **goal** | dsh-client-ui-goal | README.zh.md:12「同时显示持久 goal 状态及当前进程本地激活状态，供用户编辑、暂停、恢复或清除 goal」 | **无** | grep goal = 0 处（仅 ChatScreen.kt:2138 有 /goal 文案串） |
| **schedule（定时任务）** | dsh-client-ui-schedule | README.zh.md:12「搜索、筛选、修改名称、指令和运行时间、浏览任务运行记录、删除任务，或打开原会话」 | **无** | grep schedule 命中 12 处**全部误报**：HarnessViewModel.kt:867/1179/1300/1499/1500/1512 均为去抖函数 scheduleChatRefresh() |
| **jobs（后台任务）** | dsh-client-ui-jobs | README.zh.md:12「展示本会话的后台任务，包括生命周期、耗时、进度与终态详情」 | **无** | grep jobs = 0 处 |
| **workflow-run** | dsh-client-ui-workflow-run | 包存在 | **无** | 未单独 grep，随 plan/goal 一并确认为 0 |
| **deliverables（交付物卡片）** | dsh-client-ui-deliverables | README.zh.md:12「渲染已完成轮次末尾的改动文件卡片——列出本轮改动的文件及 Host 记录的行数」 | **有（部分）** | ChatScreen.kt:183 import deliverables、:850 注释、:864 val delivered = history?.deliverables()；但未见「行数统计」卡片 |
| **附件/文档预览类型** | dsh-client-ui-sidebar-documentpreview | README.zh.md:57「PDF、HTML、常见图片和表格调用 remote.workspaceFiles.readBytes」；:139 提及 LibreOffice 转 PDF | **部分** | PreviewKind.kt:13-23 全量仅 **MARKDOWN / HTML / SOURCE / BINARY** 四类；**无 PDF、无图片、无表格（xlsx/csv）** |
| **用户提问卡**（ask-user） | dsh-client-ui-user-questions | 包存在 | **有** | grep question = 71 处，含 QuestionCard |

## 关键澄清（避免误判）

1. **schedule 的 12 处命中全是误报** —— 都是 scheduleChatRefresh() 去抖函数，与官方定时任务无关。
2. **会话重命名在官方是 workspace 级**（@Remote rename rename(WorkspaceRenameRequest)），不是 session 级；我们已有 renameChatWorkspace。
3. **官方也没有会话置顶/搜索/排序** —— 全树搜索无实现，不是我们漏做。
4. **/compact、/plan、/goal、/feedback 四条命令我们都有本地化描述串**（ChatScreen.kt:2136-2139），但只有 /compact 能借 executeCommand 通用路径真正执行，其余三条**只显示在补全列表里**。

## 最值得先做的 5 个

1. **工具调用详情（trajectory）** —— 手机上无法开终端，工具调用的输入/输出/耗时是唯一的事后排查手段；目前只有一张静态卡片，出问题完全看不到细节。
2. **计划评审（plan / plan-review）** —— agent 提计划、人批准是我们工作流里最高频的人工介入点，缺了它只能靠读长文本猜。
3. **PDF 与图片预览** —— 官方支持 PDF/图片/表格，我们只有 4 种纯文本类；手机上收到 PDF 目前完全打不开，这是最直接的挫败感来源。
4. **消息反馈 Like/Dislike** —— 官方一行挂载就有，我们完全没有；它是改进模型的唯一入口，实现成本也最低（一个 REST 端点 + 一对按钮）。
5. **命令弹窗（上下键 + Tab）** —— 现有补全只能「看到列表、点一下填进去」，没有键盘导航；加上官方那套弹窗后斜杠命令才真正可用。

（goal / jobs / schedule / subagent 同样缺失，但对手机端的价值低于上面五条 —— 它们偏桌面长驻场景。）

## 总体判断

会话**基础操作**（列表/重命名/归档/fork/工作区）我们与官方基本对齐，差距集中在
**执行过程的可观测性**（trajectory、plan 评审）与**产物预览**（PDF/图片/表格）两块。
其中预览类缺口的影响最直接 —— 用户在手机上收到 PDF 打不开，是功能缺失而非体验差异。
