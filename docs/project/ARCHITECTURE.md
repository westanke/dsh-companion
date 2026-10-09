# 架构

单 Activity Compose App 使用 ViewModel 持有连接、会话与文件状态。`DshClient` 是唯一网络边界，通过插件 `/api/v1` REST 和 WebSocket 工作。阻塞网络操作只在 `Dispatchers.IO` 执行。

端点与加密令牌由 `SecureConnectionStore` 保存。AES-256-GCM 密钥由 Android Keystore 生成且不可导出；偏好中只保存 IV 与密文，应用数据同时排除在云备份和设备迁移之外。移动端使用模态导航抽屉，宽屏使用固定侧栏；两者共享相同状态与业务动作。

Compose 表现层只消费 `HarnessState` 并调用既有 `HarnessViewModel` 动作，不持有协议或权限决策。`Theme.kt` 集中定义暖白/墨黑/青绿/陶土色语义色、完整深色 surface 层级、衬线标题、0 字距排版和不超过 8dp 的形状；`Components.kt` 提供品牌标记、状态、警告、空状态、设置行和分区容器。连接、会话、文件与设置页采用内容优先行式布局；导航切换使用淡入与短距离滑动，创建会话、模型选择和供应商编辑使用底部面板，错误使用 Snackbar。业务回调、scope 条件和 ETag 行为保持不变。

`DshClient` 规范化用户输入的 IP/域名为 `/api/v1`。WebSocket 握手向 OkHttp 提供同源 `http://` 或 `https://` URL，由 OkHttp 执行协议升级；不能在构造 `HttpUrl` 时提前改写为 `ws://` 或 `wss://`。实时事件连接失败后按 1、2、4 秒递增退避并封顶 30 秒；REST 始终是重连后的状态恢复来源，不依赖无限事件回放。

当前会话页支持列表、创建、历史、发送、steer 与取消。历史网络模型保留 DSH `{ event, view }` 结构，单独的 presentation 层只输出用户/助手正文、推理摘要、上下文注入来源和工具调用摘要；`step/end`、`turn/end`、token 序列与未知内部事件不会作为聊天卡片显示。上下文和思考行可展开，工具行优先使用 DSH view 的说明并只从白名单参数生成后备摘要，绝不直接打印整段事件 JSON。

助手正文和展开后的思考文本通过 `multiplatform-markdown-renderer-m3` 渲染。聊天 WebSocket 的稳定 `chat.message.delta` 会按 `sessionId + turn + step + block index` 直接累积到 Compose 状态；助手完成事件只标记对应 step，REST 历史返回时原子替换该临时正文，避免闪烁或误清下一步输出。其他持久事件在 250 ms 后合并刷新，会话列表只在生命周期变化时刷新；`RATE_LIMITED` 保持待刷新并静默退避，不进入阻塞错误 UI。StateFlow 更新使用原子 `update`，防止高频 token 与 UI 操作互相覆盖。

助手正文先按 `dsh-ui` 围栏切成「文字段 + 卡片段」：文字走 Markdown 渲染，围栏内容交给原生 dsh-ui 组件树（22 种组件），此前手机上只能看到一坨裸 JSON。四条切分约束都有具体原因：围栏**闭合后才切**（流式输出期间逐字到达，提前切会得到半截 JSON），只认**行首**围栏（正文中间的 ``` 是代码块），标记中**含有** `dsh-ui` 即可（```dsh-ui 与 ```json dsh-ui 两种写法都合法），空卡片不切；解析失败的卡片只影响自己那条，不吞掉整条消息，认不出的组件类型直接跳过而不是画空壳。会话文件预览按 `PreviewKind` 分流：Markdown 走渲染、HTML 走结构化抽取（**不使用 WebView** —— 那等于让一份不可信文件在手机上取得执行权）、其余按等宽源码。消息手势是「长按选中片段 + 双击复制整条」。设置页提供 dsh-ui 诊断开关（默认关），打开后逐条报告围栏识别 / JSON 解析 / 节点分发结果。

Activity 使用 `adjustResize`，Connected `Scaffold` 在应用系统 padding 后消费已处理 inset；聊天 Column 只通过 `imePadding` 补剩余 IME 高度，避免键盘覆盖输入框和重复抬升。会话配置面板同时展示 Agent、模型和思考强度；Agent 仅在会话 `blank=true` 时可切换，首条消息后保持可见但禁用，与 DSH WebUI 和宿主 `agentPreset.select` 规则一致。

历史响应中的 `projections.values.todos` 投影为紧凑、可折叠的当前任务模块；`projections.values.permissions` 驱动输入区权限选择器。App 只允许选择 DSH 当前提供的权限预设，完整访问需显式风险确认。会话打开时从版本化 BFF 读取 host 命令，输入 `/` 时按命令名筛选并补全；发送分支会识别斜杠命令并调用命令执行端点，绝不回退到普通消息接口。

模型与供应商均通过插件稳定 BFF。会话打开时读取可路由模型组、当前模型和服务端允许的思考强度，选择后以 `provider + model + reasoningEffort` 写回；设置页读取 DSH 模板默认值与用户覆盖合并后的有效配置，显示继承模型和凭据引用但不回显密钥。插件声明自定义能力时，App 可提交 route、名称、Base URL、协议、模型及可选密钥创建完全自定义供应商。界面仅在设备拥有 `settings.read/settings.write` 时展示或启用相应能力。

新会话对话框分别请求 `/chat/workspaces` 与 `/chat/agent-presets`。已有模式只展示 DSH WebUI 登记且设备获准访问的工作区；新建模式从 `/roots` 选择授权根、浏览目录并调用 `/chat/workspaces` 把现有目录登记为 DSH 工作区，然后使用返回的 `workspaceId` 创建会话。文件根本身不会自动冒充工作区。Agent 选择器默认选中 DSH 标记的默认 preset，损坏模式禁用。

文件页支持授权根、分页目录、UTF-8/BOM/换行保留、2 MiB 移动编辑上限、同步滚动行号、Markdown 源码/渲染切换、ETag 保存、新建、移动、流式上传下载、替换、软删除与恢复。文字源码与 Markdown 预览按文件共享 75%–250% 缩放状态；按钮以 25% 步进并提供重置，双指手势连续缩放，字体和行高同步变化，单指滚动仍由原编辑/预览容器处理。上传/下载通过 Okio 流传输，不把整个文件载入 Android 堆。尚无 BFF 的 DSH 能力不进入客户端，详见 `PARITY.md`。
