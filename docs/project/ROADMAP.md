# 路线图

> 本表承接**上游 v1.0.0** 的任务台账。上游那些 `in-progress` 项都属于
> 「实现已随版本发布、但缺真机端到端验收」这一类（HTTPS、steer/取消、平板布局等），
> **不代表功能尚未实现**。本 fork 自己那条版本线（`REL-005`）、本轮 WebView→原生收尾（`PORT-*` / `COMPOSER-*` /
> `CONNECT-*` / `MODEL-*` / `SETTINGS-*` / `DELIVERABLE-*`）与资料清理（`DOC-003` / `DOC-004`）见文末。
> 各版本实际改了什么、测了什么，以 `CHANGELOG-DEV.md`、`app/build.gradle.kts` 的逐版注释与 `TESTING.md` 为准。
> 口径：`done` = 已随某个版本发布；**「未真机验收」= 逻辑已实现、只过了单测，没做系统性真机确认**，
> 这类标在验收条件里，不改变状态。

| ID | 状态 | 验收条件 |
| --- | --- | --- |
| APP-001 | done | Compose/Material 3 独立工程、包名、双语、图标与正式签名 Release 构建通过 |
| APP-002 | in-progress | HTTP/HTTPS 健康检查、配对、Keystore 令牌保存、自动恢复和断开可用 |
| APP-003 | in-progress | 会话列表/创建、历史、实时增量、发送/steer/取消可用 |
| APP-004 | in-progress | 授权根、目录、带行号文本编辑、Markdown 源码/预览、75%–250% 按钮与双指缩放、ETag、新建、移动、上传、下载、回收站可用 |
| APP-005 | done | 会话历史只呈现正文、思考、上下文和工具摘要；新会话可选择已有 DSH 工作区，或从授权根选择工作目录登记新工作区，再选择 Agent 模式 |
| UI-001 | done | 使用 UI/UX Pro Max 基线完整重构 Compose 表现层；浅色/深色主题、连接、会话、文件、设置、弹窗与自适应导航通过真实设备尺寸验收，业务回调与权限逻辑不变 |
| UI-002 | done | 参考授权 APKS 的内容优先层级、留白、抽屉、底部面板与过渡动效完成第二轮视觉重构；保留 DSH 品牌、业务动作和权限逻辑 |
| UI-003 | done | 收紧全局标题比例、会话列表节奏、聊天消息间距与输入框高度，同时保留至少 44/48dp 的关键触控区域并通过真机截图验收 |
| UI-004 | done | 聊天正文、Markdown 段落/列表/表格、消息留白和输入区再缩小一档，视觉控件更紧凑但操作热区仍符合 Android 触控要求 |
| MODEL-001 | done | 设置页显示 DSH 模板与用户覆盖合并后的有效配置、继承模型和凭据状态，可编辑现有供应商或创建完全自定义供应商；聊天页可选择模型和思考强度 |
| MD-001 | done | 助手正文和展开思考按 Markdown 渲染；文件编辑器带同步行号，Markdown 文件可切换源码与渲染预览 |
| RATE-001 | done | WebSocket 聊天事件按时间窗合并刷新，发送/取消不再立即重复请求会话列表，429 静默退避且不弹阻塞错误框 |
| STREAM-001 | done | App 直接累积插件稳定 `chat.message.delta` 文本/思考增量，完成后以 REST 历史原子收口，不再等待整段回复 |
| AGENT-001 | done | 当前会话显示 Agent；空白会话可切换可用 Preset，首条消息后按 DSH 规则锁定并提供中英文说明 |
| APPROVAL-001 | done | App 显示 DSH 待审批状态，可查看脱敏命令与原因、允许一次或拒绝，高风险操作需二次确认；等待期间会话不得只显示为“运行中” |
| TODO-001 | done | App 从 DSH `todos` 会话投影显示当前任务列表、进行中状态与完成进度，并随实时事件刷新 |
| COMMAND-001 | done | 输入 `/` 时显示当前会话可用命令及说明，支持补全和受控执行，斜杠命令不得作为普通消息发送给模型 |
| PERMISSION-001 | done | 聊天输入区显示当前会话权限预设并可切换 Read Only、Workspace Write、Full access；Full access 必须二次确认 |
| BRAND-001 | done | 设置页以中英双语展示作者信息 `Github@Hakunm` |
| BRAND-002 | done | App 用户可见文案统一使用社区常用简称 DSH，并随 v1.0.0 重新生成正式签名 APK |
| DOC-001 | done | 中英双语用户 README 使用真实手机截图展示导航、聊天、文件编辑和模型供应商，连接地址完成打码 |
| DOC-002 | done | README 扩展为 6 张用户提供的真实手机截图，补充文件浏览和新建工作区界面，并按发布要求删除截图来源说明 |
| LICENSE-001 | done | 项目自身许可证从 MIT 切换为 AGPL-3.0-only，并保留上游图标与 Markdown 渲染器原许可证告知 |
| WORKSPACE-001 | done | App 可查看、重命名和移除 DSH 工作区；移除时明确不会删除服务器目录或文件 |
| SESSION-001 | done | App 会话子项可重命名、分叉和归档；分叉后进入新会话，归档后从默认列表隐藏 |
| IME-001 | in-progress | `Scaffold` 消费已应用的系统 inset，聊天窗口只补剩余 IME inset；输入框在不同厂商键盘上均贴近键盘上沿且列表保留可滚动空间。**2.2.2 已用 `Modifier.imePadding()` 修掉边到边模式下键盘遮挡输入框的主体问题**；不同厂商键盘的覆盖仍未做（未真机验收） |
| PARITY-001 | in-progress | `PARITY.md` 所列 DSH WebUI 能力全部完成或有明确版本计划（`PARITY.md` 随 2.2.20 文档刷新同步更新） |
| TEST-001 | done | 单元测试、Compose UI、lint、Release 签名、真机/模拟器端到端通过 |
| CI-001 | done | GitHub Actions 在不持有正式签名密钥时通过 Debug 门禁，同时任何 Release 打包仍必须提供项目专用签名 |
| REL-001 | done | `dsh-android-app` v1.0.0 用户文档、版本展示、正式签名 APK、独立 Git 仓库和 `v1.0.0` 标签完成 |
| REL-002 | done | 创建 GitHub 公开仓库并推送源码；参考高关注 DSH 项目重构中英双语 README，加入真实脱敏截图并发布含正式签名 APK 的 v1.0.0 Release |
| REL-003 | done | v1.0.0 使用最终 DSH 文案、AGPLv3、真实手机截图和正式签名 APK，以单一根提交重写公开 `main` 与标签并替换 Release 产物 |
| REL-004 | done | App 加入 `dsh-plugin` topic；App 与插件 README 刷新后继续以各自单一根提交发布，不保留中间修改历史 |
| REL-005 | done | 本 fork 独立版本线 v1.1.0–v1.9.0：main 与 tag 双推 GitHub/Gitee 并各自发布 Release（含签名 APK）；上游仓库只读、不推送（2.x 见 `REL-006`） |
| DOC-003 | done | 清理过期资料：版本号/用例数/插件名/签名指纹/上游链接与死图；删除历史 APK 与不再相符的截图，README 不留死链；文档内设备令牌脱敏 |
| PORT-001 | done | WebView 聊天页（`assets/chat.html`）的呈现方式移植到原生：工具显示改动作短语、连续工具调用合并一行、历史不显示思考、上下文注入默认隐藏（2.2.15）；思考条挪到输入区上方一行、`dsh-ui` 卡片内表格列宽自适应 + 横向滚（2.2.16）。遗留项与「原生本已具备」的取证见 `docs/project/webview-port-todo.md` |
| COMPOSER-001 | done | 输入区照网页版重做为单行药丸：模型 / 智能体 / 权限模式 / 插话收进顶栏 `⋯` 菜单，附件并入 `+`，运行中提示改占位符（2.2.17）；输入框高度随内容自适应 44–120dp（2.2.18 / 2.2.19）。功能零退化（权限三档 + 完整访问二次确认、插话、附件、Enter 发送全部保留） |
| CONNECT-001 | done | 启动永远先落连接页（进程级 `connectGate`，只由用户主动动作放行；冷启动自动重连成功也停在连接页）；导入配置与手动配对两种方式默认收起且互斥展开；顺带修 `connectHostInternal` 失败分支漏置 `restoring=false` 导致转圈不停（2.2.19） |
| MODEL-002 | done | 模型供应商配置从设置页拆成独立「模型」导航页（`ui/ModelsScreen.kt`，位于插件与设置之间）；模型页永远可进；设置页因此从 697 行瘦到 297 行（2.2.19） |
| SETTINGS-001 | done | 设置页三个开关（用网页版聊天页 / 显示上下文注入 / dsh-ui 卡片诊断）统一走 `SwitchRow`；`useWebChat` / `showContext` / `genUiDebug` 进 `HarnessState`，点开关立即生效；`withPreferences()` 修掉偏好被整份重建冲掉（2.2.19） |
| DELIVERABLE-001 | done | 会话交付物改读内核 `deliverables/presented` 事件（`present` 工具触发，带绝对路径与人写的说明）；标题栏文件夹弹层与消息流末尾共用 `sessionFileList()`，两处不再不一致（2.2.20）。**未真机验收** |
| REL-006 | done | 2.2.20（`versionCode` 10045）出包 `artifacts/DeepSeek-Harness-companion-v2.2.20.apk`（2,792,754 字节）。**用 Android 调试密钥签名（`CN=DSH Debug`，发布证书已丢失）**，跨签名升级必须先卸载；tag 与 Release 待补 |
| DOC-004 | done | 文档刷新到 2.2.20：README / README.en.md / STATUS / ROADMAP / PARITY / CHANGELOG-DEV 与 WebView 四份报告逐项对齐当前事实；WebView 报告收口（`webview-2.2.9-report.md` → `webview-report.md`，`docs/webview-audit.md` 压缩为历史指针） |
