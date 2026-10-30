# DSH WebUI 功能对等矩阵

- 最后更新：**2026-10-29**（对应 v2.2.57 未发版的 BFF 三项：分块上传 / 定时任务 / 官方语音输入；已按桥侧定稿收紧能力位单值与停用语义。
  桥侧 REST BFF 契约见姊妹仓 `dsh-remote-bridge/docs/project/BFF-REST-CONTRACT.md`；
  本轮三条都按「老桥 404 → 入口隐藏、不整页报错」实现，并用 `DshClientBffTest` 钉住退化行为）。
  Android 列按本轮代码复核；插件 API 列本轮**已复核**（姊妹仓 `dsh-remote-bridge` 同期完成 C 批，见其 `CHANGELOG-DEV.md`）。

| 能力 | 插件 API | Android | 状态 |
| --- | --- | --- | --- |
| 健康检查与一次性配对 | 已有 | Android 15 发布版已通过真实 HTTP listener 验收；HTTPS 待验收 | in-progress |
| 设备身份、scope 与根授权 | 已有 | Android 15 已读取真实设备、7 scope 与授权根 | in-progress |
| 会话列表与创建 | 已有，含 DSH 工作区发现/创建 | 可选择已有工作区，或从授权根浏览工作目录、登记新 DSH 工作区并创建会话 | done |
| 历史、发送、steer、取消 | 已有 | 历史投影与 Markdown 已完成，真实消息发送/回复已通过；steer 与取消仍待真实运行验收（v2.2.17 起 steer 入口在顶栏 `⋯` 菜单，仍为固定插话） | in-progress |
| 聊天实时事件 | 已有稳定文本/思考 delta；**`seq` 语义单一化**（只表示持久事件序号，流式 delta 不再输出它，ADR-0008）；**订阅基线** `chat.session.snapshot`（带 `cursor`） | 真实 Oracle listener 已验证按 token 连续增长并在完成后用 REST 历史收口；**断档检测**：跳号或本地落后于基线时自动重取历史（`ChatSeqMonitor`）；网络切换待验收 | in-progress |
| 文件列表、读写和 ETag | 已有 | 已实现行号文本编辑、Markdown 源码/预览与 ETag 保存；冲突仍待端到端验收 | in-progress |
| 新建、移动、上传、下载、软删除、恢复 | 已有 | 已实现并有 MockWebServer/服务端回归，移动 UI 动作待验收。**v2.2.44 真机验收报的「新建文件静默失败」已现场结案：不是 App 缺陷，是验收时点击落空** —— 输入文件名后软键盘弹起让对话框上移 426px，用弹起前的坐标点「创建」落在对话框外，触发 dismiss，于是一个请求都没发（详见 `PITFALLS.md` §键盘弹起让对话框上移）。**加固已落地**：早退点不再静默 `return` 而是抛错走全局 snackbar（真机复验：同名冲突会显示「同名文件已经存在了」）；`createEntry` 与「请求失败 / 连接缺失 / 代际丢弃」四处补了诊断日志 | done |
| 会话搜索、重命名与分叉 | 重命名与分叉已有（`POST …/fork` 支持 `atSeq`）；搜索未进入 v1 | 会话子项菜单可重命名并分叉；**另可从某条用户消息分叉**（`atSeq`，入口在时间戳行，刻意不占长按 —— 长按归选词） | in-progress |
| 模型与推理等级 | 已有 | 已读取真实模型组、切换模型与服务端支持的思考强度 | done |
| Agent Preset / 模式选择 | 已有列表、创建参数及空白会话切换 | 新建和现有空白会话可切换；首条消息后按 DSH 规则显示锁定 | done |
| 当前 TODO / 任务模块 | 历史 `projections.values.todos` | 聊天页显示完成进度和可折叠任务列表，随历史刷新 | done |
| 斜杠命令 | 已有当前会话 host 命令列举/执行 BFF | 输入 `/` 即筛选、补全并受控执行，不作为模型消息发送 | done |
| 会话权限预设 | 当前值取会话投影 `permissions.currentValue`（**需 bridge 下发 `projections`**）；候选项取 `GET /chat/permission-presets`（内核 `permissionPresets.catalog()` 的 BFF，**尚未发版**，本地 2.4.1 产物已含） | 顶栏 `⋯` 菜单可切换档位、完整访问二次确认（`PermissionMenuItems()` + `FullAccessConfirmDialog()`；v2.2.17 起入口从输入区迁入；**v2.2.44** 发送区上方新增常驻权限档位 Pill，与菜单同源同动作）。**v2.2.44 真机验收时这条的 `done` 是假的**：候选读的是投影里的 `options[]`，而内核那条 wire 视图只产 `currentValue` → `permissionSelect()` 恒为 null，Pill 与菜单权限项**从不渲染**。已改为「当前值取投影、候选项取目录接口」两路合成；候选项为空时仍显示当前档位（降级为只读标签，不再整块消失）。**2026-10-19 真机复验通过**：Pill 显示「完整访问」、⋯ 菜单显示「权限模式 · 完整访问」 | done |
| 附件与消息队列 | 已有：附件上传/读取接口 + **分块上传**（`/chat/sessions/{id}/attachments/uploads`，块 1 MiB、上限 256 MiB、1 小时有效、可按 `alreadyReceived` 续传）；排队与插话由客户端按设置决定 | 可发送图片（≤8 MiB，内联 base64）与文件（≤24 MiB 走既有 base64 上传，先换 receiptId 再引用；**>4 MiB 且桥声明 `attachment-chunked-upload-v1` 时改走分块**：`UploadProgressChip` 显示「已传块/总块」与续传标记，失败可重试，409 缺块按 `details.missing` 补一次）；会话图片按 attachmentId 取回并缓存，401/403/404 分开提示且授权类失败可重试；智能体运行时 Enter/发送键按设置排队或插话，`Ctrl/Cmd+Enter` 走相反的一种 | done |
| 工具详情、审批与问题回答 | 审批与提问 BFF 已有；**连接即重放**（`interaction-replay-v1`：新连接会补推仍待处理的审批/提问）；**能力位** `approvals-v1` / `questions-v1` | 显示待审批状态、脱敏命令、"允许一次/拒绝"及完整访问二次确认；连接与亮屏时主动刷新；**缺关键能力位时明说"插件过旧、卡片不会出现"**（不再静默）；**单胜方**：身份比对与迟到响应 `not-pending` 已有，并行下发待拍板（ADR-0009）；**v2.2.44**：工具详情按 name 分发专用块（read 路径头+行号正文 / bash `$ cmd`+暗底输出（ANSI 已剥）/ edit·write `-`/`+` 对照），解析失败回落通用渲染，chat.html 同步 | in-progress |
| DSH 工作区发现与会话归属 | 已有，按授权根过滤 | 已实现选择和会话标题/工作区标题显示 | done |
| DSH 工作区创建 | 已有，限授权目录 | App 目录选择器可登记并立即用于新会话 | done |
| DSH 工作区管理与会话归档 | 工作区重命名/移除、会话归档与**已归档列表**（`GET /chat/sessions?archived=true`）已有；**取消归档缺失** | 工作区管理页可重命名/移除，会话子项可归档；**面板可切到「已归档」查看并打开**；归档恢复**不做** —— 内核是否提供 unarchive 从本仓不可证（界面已明说"需在电脑端 WebUI 调整"）；自定义排序待后续 | in-progress |
| 服务端能力探测 | `connection.ready` 带 `bridgeVersion` + **26 项能力位** | 客户端解析能力位做**运行时**特性判据（不再靠版本号）；缺关键能力时给出可见提示 | done |
| 交付物 / 文件卡片 | 持久事件 `deliverables/presented` + 实时事件 `chat.deliverables.presented` | 会话文件面板显示智能体**明确交付**的文件（带 agent 写的说明）；历史与实时都覆盖 | done |
| 会话统计 | 无（客户端从历史算） | ⋯ 菜单「会话统计」：**执行维度**（轮次 / 助手消息 / 工具调用 / 事件总数 / 数据截至 `#seq`）+ **内核投影维度**（目标 objective/phase/revision/rounds、内核步数/LLM 耗时/解码 token、Token 用量五项与缓存命中率、上下文压力 fraction/窗口/占用/表层、智能体团队成员数与任务数、子代理目录条数）。投影值**只显示内核自己写在 `history` 的 `projections.values` 里的数字**，客户端不做任何估算；投影缺失时面板只留执行维度并显示「用量与上下文暂不可用」 | done |
| 轨迹（按轮次时间轴） | 无（客户端从历史算） | ⋯ 菜单「轨迹」：按轮次分组的时间轴（含每轮耗时与工具数），**点节点展开原始 JSON**；不认得的事件类型也保留 | done |
| 目标、子代理与后台任务 | 目标 / 智能体团队 / 子代理目录投影写在 `history` 的 `projections.values` 里（`goal`、`agentTeam`、`subagentCatalog`），由内核算、App 只读；后台任务面板自 v2.2.46 已有 | **只读投影**：会话统计面板展示目标阶段与轮次上限、团队成员数 / 任务数、子代理目录条数；后台任务面板独立。**目标与子代理的创建/改/删仍是 `/ws/mobile` 专属**，App 不发 —— 只消费投影，不做管理 | done（只读） |
| 智能团队面板（对齐官方 Web 只读监工面板） | 官方 `dsh-experimental-client-ui-agent-team`：会话头部一个入口，只读 roster + 只读任务板，唯一动作 `openTeammate` 跳进成员子会话；建成员/派任务/私信全靠 Lead 持有的模型工具，人不直接写 | ⋯ 菜单一级「智能团队」（2026-10-30，不藏统计面板里）：成员名册（名字 / 负责人·成员 / 在岗·组建中·失败 / 失败原因）、共享任务板（主题 / 四态 / 描述 / 负责人 / 前置依赖 / 写范围 / 未就绪）、子会话目录（标签 / 会话 id / one-shot·可续用等模式）全部读 `history.projections.values`；底部「指挥负责人」三枚 chips（新成员 / 派任务 / 问进度）把话术预填进对话框，确认后作为**普通用户消息**发给 Lead 触发其团队工具。**直连成员子会话（openTeammate）不做**：桥的 `sessions.list` 过滤 `origin:'subagent'`，属桥侧 P2 窄口，不在 App 侧绕 | done（只读 + 自然语言指挥；子会话直连待桥 P2） |
| 官方语音输入 | 新增 BFF：`GET /chat/speech/catalog`、`POST /chat/speech/transcriptions`、`PUT /chat/speech/config`（`BFF-REST-CONTRACT.md` §3） | 输入区麦克风：AudioRecord 采 16 kHz 单声道 PCM16 → 封 WAV → 整段转写 → **插入输入框（不自动发送）**；权限三态（拒绝/永久拒绝→引导系统设置）、录音可取消、转写可取消；`available:false` 时禁用并显示服务端 `reason`；无能力位或老桥 404 时按钮不渲染 | done |
| 定时任务 | 新增 BFF：`/chat/schedules` 列表/新建/改/删（`BFF-REST-CONTRACT.md` §2，时间一律 epoch 毫秒；**内核无暂停语义，停用 = DELETE**，`enabled:false` 会被桥回 400） | 会话顶栏 `⋯` →「定时任务」底部面板：列出本会话任务（cron、时区、下次运行、启用开关），可新建（文本 + cron + 时区）、启用（`enabled:true`，已结束 409 给专用文案）、**停用 = 删除**（二次确认文案点明「停用等于删除」）、删除（二次确认）；列表按 `nextRunAt` 排序（`createdAt` 恒 null，不可用）；cron 输入给**人话预览**（如「每天 08:00」），时间按本地时区格式化；老桥 404 显示「需要升级插件」而不是报红 | done |
| Provider、凭据与设置 | 已有细粒度 scope、有效配置投影和自定义创建 | 可查看已填配置/继承模型、编辑配置、只写/清除凭据、发现模型并创建完全自定义供应商；这些配置项集中在独立的「模型」导航页（v2.2.19 从设置页拆出，`ui/ModelsScreen.kt`） | done |

“对等”的判据是「用户能读到同一状态、并能完成同一用户动作」，**不限定实现路径**：Android 原生、WebView 页（`app/src/main/assets/chat.html`）或两者混用都算，只要该能力在 Android 端实际可用、且走的是插件公开接口。WebView 与原生存量共用本矩阵，任一实现达成即记为达成；直接调用 DSH 私有 `/api` 仍不计为完成。

> 说明（2026-10-11 修订）：本条原文是「仅显示 WebView 或直接调用 DSH 私有 `/api` 不计为完成」，订立时 WebView 还只是试验品。现在聊天页整页可选走 WebView 且大量走插件 REST；按原文的字面读法，聊天页永远算「未完成」，与事实不符，故改为容纳原生与 WebView 两种实现，判据落在「能力可用」而不是「用哪种壳」。
