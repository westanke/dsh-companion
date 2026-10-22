# 接口说明（契约与签名）

本文回答：**App 消费哪些 bridge 接口、模块之间怎么调用、改接口要同步什么**。

- **对外权威在 bridge 仓**：`dsh-remote-bridge` 的 `docs/api/openapi.yaml` +
  `docs/api/asyncapi.yaml` + `docs/project/API.md`。App **只使用公开版本化 API**，
  能力不足时**先改 bridge 仓库**（API / OpenAPI / AsyncAPI / SDK / scope / 合约测试），再接入 App。
- 签名一律**逐字照抄源码**。本文没写到的符号不代表不存在 —— 只收「跨模块会用到」的那些。
- 目录与分层见 [STRUCTURE.md](STRUCTURE.md)；设计动机见 [ARCHITECTURE.md](ARCHITECTURE.md)。
- 「要求 bridge ≥ 2.3.7」是**安装门槛**，不是功能判据；功能判据一律走 `connection.ready` 能力位。

> **同步纪律**：改公开方法/类签名、消费的 bridge 端点或事件字段，**同一提交**内更新本文与文末锚点，
> 否则 `scripts/build.sh docsCheck` 变红（见 [AGENTS.md](../../AGENTS.md) §4.4）。

---

## 1. 接口分层总览

| 层 | 代表接口 | 权威文件 |
| --- | --- | --- |
| 对外（消费 bridge REST） | `DshClient` 的强类型方法 | bridge 的 `openapi.yaml` + 本文 §3.1 |
| 对外（消费 bridge 事件流） | `DshClient.events(...)` 的 SSE/WS 订阅 | bridge 的 `asyncapi.yaml` + 本文 §3.2 |
| 对外（配置导入） | `ConnectionShare` 的文本编解码 | [CONFIG-EMITTER.md](CONFIG-EMITTER.md) |
| 内部（状态与动作） | `HarnessViewModel` + `Harness*ViewModel` 扩展 | 本文 §4.2 |
| 内部（协议映射） | `ChatPresentation` 等纯函数 | 本文 §4.3 |
| 内部（WebView 桥） | `ChatWebBridge` 的消息协议 | 本文 §4.4 |

---

## 2. 消费边界：唯一网络出口

**`data/DshClient.kt` 是唯一网络边界**，阻塞 IO 只在 `Dispatchers.IO`。

```kotlin
class DshClient(
    endpoint: String,
    private val token: String? = null,
    private val http: OkHttpClient = defaultHttpClient,
)
```

- 已知唯一例外：`web/ChatWebBridge.kt` 自持 OkHttp（登记在 `TECH-DEBT.md` DEBT-006，
  `ArchitectureGuardTest` 盯着）。**新建代码不得再建 `OkHttpClient`。**
- `defaultHttpClient` 是共享实例（`internal`）—— 更新检查等后续网络功能**复用它**，不要另起。
- 未知字段一律 `ignoreUnknownKeys = true`：bridge 加可选字段不会让旧 App 崩。

---

## 3. 对外接口（消费 bridge）

### 3.1 REST 端点清单（按 `DshClient` 方法分组）

路径相对 base（`<endpoint>/` 之下即 `/api/v1/…`）。「对应方法」列是 App 侧唯一入口。

| 方法 | 路径 | App 侧方法 | 用途 |
| --- | --- | --- | --- |
| GET | `healthz` | `health()` | 存活探针（**不带鉴权**） |
| POST | `pairings/exchange` | `pair(code, deviceName)` | 配对码换 token |
| GET | `devices/self` | `currentDevice()` | 当前设备身份与 scope |
| GET | `roots` | `roots()` | 授权根列表（**不含绝对路径**） |
| GET | `roots/resolve?path=` | `resolvePath(absolutePath)` | 绝对路径 → 授权根 + 相对路径（失败返回 null） |
| GET | `roots/{rootId}/entries?path=&cursor=` | `entries(rootId, path, cursor)` | 列目录（分页） |
| GET | `roots/{rootId}/content?path=` | `readFile` / `downloadFile` | 读文件 / 流式下载 |
| PUT | `roots/{rootId}/content?path=` | `writeFile(...)` | 写文件（带 etag → `If-Match`） |
| POST | `roots/{rootId}/entries` | `createEntry(rootId, path, directory)` | 新建文件/目录 |
| PATCH | `roots/{rootId}/entries` | `moveEntry(...)` | 移动/改名 |
| DELETE | `roots/{rootId}/entries?path=` | `trashEntry(...)` | 移入回收站 |
| GET | `trash` | `trash()` | 回收站列表 |
| POST | `trash/{trashId}/restore` | `restoreTrash(id)` | 恢复 |
| GET | `trash`（清理） | — | 彻底删除仅回环管理面，App 不消费 |
| GET | `chat/workspaces` | `chatWorkspaces()` | 工作区列表 |
| POST | `chat/workspaces` | `createChatWorkspace(rootId, path)` | 新建工作区 |
| PATCH | `chat/workspaces/{workspaceId}` | `renameChatWorkspace(...)` | 改名 |
| DELETE | `chat/workspaces/{workspaceId}` | `deleteChatWorkspace(...)` | 删除 |
| GET | `chat/agent-presets` | `agentPresets()` | 智能体预设 |
| GET | `chat/permission-presets` | `permissionPresets()` | 权限档位（bridge ≥ 2.4.2） |
| GET | `chat/sessions?archived=` | `sessions(archived)` | 会话列表（归档需能力位） |
| POST | `chat/sessions` | `createSession(workspaceId, agentPreset)` | 新建会话 |
| PATCH | `chat/sessions/{sessionId}` | `renameSession(...)` | 改名 |
| POST | `chat/sessions/{sessionId}/fork` | `forkSession(sessionId, atSeq)` | 分叉 |
| POST | `chat/sessions/{sessionId}/archive` | `archiveSession(...)` | 归档 |
| GET | `chat/sessions/{sessionId}/jobs` | `sessionJobs(sessionId)` | 后台任务 roster |
| GET | `chat/sessions/{sessionId}/jobs/{jobId}/output` | `jobOutput(...)` | 任务输出（增量） |
| POST | `chat/sessions/{sessionId}/jobs/{jobId}/kill` | `killJob(...)` | 取消任务 |
| GET | `chat/sessions/{sessionId}/messages` | `history(sessionId, beforeSeq, maxMessages)` | 历史（分页 + 字节预算） |
| POST | `chat/sessions/{sessionId}/messages` | `sendMessage(...)` | 发消息（附件内联 / 上传凭证） |
| POST | `attachments`（上传） | `uploadAttachment(...)` | 上传附件 |
| GET | `attachments/{attachmentId}?sessionId=` | `readAttachment(...)` | 取附件字节 |
| GET | `chat/sessions/{sessionId}/approvals` | `pendingApprovals(...)` | 待审批 |
| POST | `chat/sessions/{sessionId}/approvals/{approvalId}/decision` | `decideApproval(...)` | 审批决定 |
| GET | `chat/sessions/{sessionId}/questions` | `pendingQuestions(...)` | 待答问题 |
| POST | `chat/sessions/{sessionId}/questions/{callId}/answer` | `answerQuestions(...)` | 提交答案 |
| GET | `chat/sessions/{sessionId}/commands` | `sessionCommands(...)` | 斜杠命令 |
| POST | `chat/sessions/{sessionId}/commands` | `executeCommand(...)` | 执行命令 |
| GET | `chat/sessions/{sessionId}/models` | `sessionModels(...)` | 会话可选模型 |
| PUT | `chat/sessions/{sessionId}/model` | `selectModel(...)` | 选择模型 |
| PUT | `chat/sessions/{sessionId}/agent-preset` | `selectAgentPreset(...)` | 选择智能体预设 |
| POST | `chat/runs/{id}/cancel` | `cancel(sessionId)` | 取消当前轮 |
| GET | `settings/providers` | `providerSettings()` | 提供商配置 |
| POST | `settings/providers` | `createCustomProvider(...)` | 新建自定义提供商 |
| PATCH | `settings/providers/{providerId}` | `updateProvider(...)` | 改提供商 |
| DELETE | `settings/providers/{providerId}` | `deleteProvider(...)` | 删提供商 |
| POST | `settings/providers/{providerId}/discover` | `discoverModels(...)` | 发现可用模型 |
| GET | `settings/models` | — | 模型目录（插件页面用） |
| GET | `settings/plugins` | `pluginInventory()` / `pluginInventoryWithUpdates()` | 插件清单 |
| PATCH | `settings/plugins` | `setPluginLoaded(name, loaded)` | 插件启停 |
| GET | `settings/namespaces` | `settingsNamespaces()` | 按插件划分的设置命名空间（含 schema；**老桥 404 按不可用降级**） |
| PATCH | `settings/namespaces/{ns}` | `updateSettingsNamespace(ns, ops, expectedRevision)` | 按路径改设置（revision → 409 冲突） |

> 路径的机器真值在 bridge 仓的 `docs/api/openapi.yaml`。**改路径先改 bridge**，本表随后跟上。

### 3.2 事件流

```kotlin
fun events(listener: (WorkspaceEvent) -> Unit, connection: (Boolean) -> Unit): Closeable
internal fun eventsUrl(): HttpUrl        // <endpoint>/events
```

- 端点：`/api/v1/events`，Bearer 鉴权在升级阶段完成；断链由 `ReconnectingEvents` 自动重连。
- 帧形状：`{ id, type, time, data }`（`WorkspaceEvent`）。**未知 type 一律忽略**，
  不要因为多了个事件就当成错误。
- 归并规则在 `HarnessEventRules`（纯函数，可单测）：`delta` 累积、`committed` 定稿、
  `turn/end` / `step/end` 不作为聊天卡片渲染。
- 断档判定在 `ChatSeqMonitor`：`incoming > last + 1` 才算断档；重复与倒退交给去重。
- `seq` 只表示**会话持久事件序号**；流式 delta 不带它（bridge ADR-0008）。

### 3.3 能力位与必需项

```kotlin
internal data class BridgeCapabilities(val bridgeVersion: String?, val capabilities: Set<String>)
internal fun parseBridgeCapabilities(data: JsonElement): BridgeCapabilities?
internal fun missingRequiredCapabilities(capabilities: Set<String>): List<String>
internal fun supportsArchiveListing(capabilities: Set<String>): Boolean
internal fun supportsSessionJobs(capabilities: Set<String>): Boolean
internal val REQUIRED_CAPABILITIES: List<String>  // ["approvals-v1", "questions-v1"]
```

- 来源：bridge 首帧 `connection.ready` 的 `data`。
- **读不到 = 未知（null），不是「什么都缺」**。空数组也按未知处理 ——
  假警告比没有警告更坏，它教会用户忽略警告。
- 调用方一律按「null → 不提示、不降级」处理。
- 两个必需项是**跨仓契约**：bridge 侧 `capabilities.spec.ts` 有对应钉子，
  防止那边当死代码删掉而这边对所有用户误报「插件过旧」。

### 3.4 配置导入（不走 bridge 的对外格式）

`data/ConnectionShare.kt` 负责「连接信息 ↔ 可粘贴短文本」的编解码；
生成端在 bridge 仓（`/manage/config/text` 与 `tools/emit-config.mjs`）。
线格式细节见 [CONFIG-EMITTER.md](CONFIG-EMITTER.md)。改格式**两端同批改**。

---

## 4. 内部模块接口

> 只列跨模块会用到的符号。私有实现细节看源码，别在这里抄。

### 4.1 数据层（`data/`）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `class DshClient(...)` | `data/DshClient.kt` | 唯一网络边界，见 §2 与 §3.1 |
| `internal class HostStore` | `data/HostStore.kt` | 主机/凭据存储（经 `SecretBox` 加密） |
| `interface KeyValueStore` | `data/HostStore.kt` | 键值抽象（为可测试而存在） |
| `interface SecretBox` | `data/HostStore.kt` | 加解密抽象 |
| `class SecureConnectionStore(context: Context)` | `data/SecureConnectionStore.kt` | 凭据与主机存储门面 |
| `class AppPreferences(context: Context)` | `data/AppPreferences.kt` | 纯偏好（明文）：语言/主题/字号/诊断开关等 |
| `enum class InterfaceStyle` | `data/AppPreferences.kt` | 界面明暗三档（SYSTEM/LIGHT/DARK） |
| `enum class AppTheme` | `data/AppPreferences.kt` | 配色主题四套（PAPER/WARM/MIST/INK），默认 PAPER = 改造前配色 |
| `var AppPreferences.appTheme: AppTheme` | `data/AppPreferences.kt` | 配色主题偏好（存 enum name，不存 ordinal） |
| `internal fun normalizeFontScale(value: Float): Float` | `data/AppPreferences.kt` | 字号兜底（防非法值） |
| `class ConnectionCoordinator(...)` | `data/ConnectionCoordinator.kt` | 多地址探活与选路；全失败抛 `DshConnectionException` |
| `class DshConnectionException(...)` | `data/ConnectionCoordinator.kt` | 携带**每个地址**的失败原因（诊断面板逐条展示） |
| `sealed interface ProbeOutcome` | `data/ConnectionCoordinator.kt` | 单地址探活结果 |
| `fun ConnectionLiveness` 系列纯函数 | `data/ConnectionLiveness.kt` | 「此刻通不通」判据 |
| `class ConnectionShare` / `ConnectionShareInterop` | `data/ConnectionShare.kt` | 配置导入编解码 |
| `internal class EventDebouncer` | `data/EventDebouncer.kt` | 事件副作用去抖 |
| `internal class RequestGate` | `data/RequestGate.kt` | 请求代际闸门（过期结果写不回状态） |
| `fun extractHtmlBlocks(source: String): List<HtmlBlock>` | `data/HtmlPreview.kt` | HTML 抽结构给原生控件（不执行脚本） |
| `fun decodeHtmlEntities(raw: String): String` | `data/HtmlPreview.kt` | 实体解码 |
| `enum class ReleaseChannel` | `data/UpdateChecker.kt` | 更新渠道（中文 Gitee / 英文 GitHub） |
| `fun channelForLanguage(languageTag: String): ReleaseChannel` | `data/UpdateChecker.kt` | 按界面语言分流（用户既定规矩） |
| `fun isNewerVersion(remote: String, current: String): Boolean` | `data/UpdateChecker.kt` | 版本比较 |
| `fun pickApkAsset(assets: List<ReleaseAsset>): ReleaseAsset?` | `data/UpdateChecker.kt` | 从 Release 资产里挑 APK |

### 4.2 状态与动作（根目录 ViewModel）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `data class HarnessState(...)` | `HarnessModels.kt` | App 的**唯一 UI 状态**（Compose 只消费它） |
| `val HarnessState.effectiveFontScale: Float` | `HarnessModels.kt` | 有效字号倍数 |
| `fun HarnessViewModel.setAppTheme(theme: AppTheme)` | `HarnessViewModel.kt` | 切配色主题（写偏好 + 更新状态，实时生效） |
| `class HarnessViewModel(application: Application) : AndroidViewModel(application)` | `HarnessViewModel.kt` | 唯一 ViewModel，持有 state 与跨域基础设施 |
| 各域扩展函数（`fun HarnessViewModel.xxx()`） | `HarnessConnectionViewModel.kt` / `HarnessSessionsViewModel.kt` / `HarnessFilesViewModel.kt` / `HarnessInteractionsViewModel.kt` / `HarnessAttachmentsViewModel.kt` / `HarnessJobsViewModel.kt` / `HarnessPluginsViewModel.kt` / `HarnessDiagViewModel.kt` / `HarnessTruncationViewModel.kt` | 按域拆分的动作集；调用方写法与成员函数一致 |
| `HarnessEventRules` 纯函数 | `HarnessEventRules.kt` | 事件帧与输入行的判定（可单测） |
| `HarnessOperations` 扩展 | `HarnessOperations.kt` | 拿活跃连接跑请求；无连接时明确提示 |

### 4.3 协议映射与展示（`data/Chat*`）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `fun ChatHistory.displayItems(): List<ChatDisplayItem>` | `data/ChatPresentation.kt` | **会话展示的单一出口** |
| `fun ChatHistory.todoItems(): List<TodoItem>` | `data/ChatPresentation.kt` | 当前任务面板数据 |
| `fun ChatHistory.deliverables(): List<ChatDeliverable>` | `data/ChatPresentation.kt` | 显式交付物 |
| `fun ChatHistory.permissionSelect(catalog: PermissionCatalog?): PermissionSelect?` | `data/ChatPresentation.kt` | 会话权限档投影 |
| `sealed interface HarnessState` 相关 | `HarnessModels.kt` | 见 §4.2 |
| `enum class ChatItemKind` | `data/ChatModels.kt` | 展示项种类（消息/工具/交付物/失败行…） |
| `data class ChatDisplayItem(...)` | `data/ChatModels.kt` | 展示项模型 |
| `data class ToolCallDetail(...)` | `data/ChatModels.kt` | 工具调用详情 |
| `data class ChatDelta(...)` | `data/ChatModels.kt` | 流式增量帧 |
| `data class LiveChatState(...)` | `data/ChatModels.kt` | 流式快照状态 |
| `data class ChatTurnFailure(...)` | `data/ChatModels.kt` | 终局失败（限流/配额/密钥） |

### 4.4 WebView 桥（`web/`）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `class ChatWebBridge(...)` | `web/ChatWebBridge.kt` | 注入 `assets/chat.html` 的 JS 桥 |
| `ActiveChatBridge` | `web/ActiveChatBridge.kt` | 当前存活桥实例（Activity 回调落点） |

协议要点：网页里的 `fetch` 一律**回到原生**转发（`file://` 里发跨源 `http://` 会被拦），
桥接收「方法 + 路径 + JSON」三件套；**它不解析业务语义**，只做转发与错误透传。
这也是它必须自持 OkHttp、并被登记为唯一网络例外的原因（DEBT-006）。

### 4.5 诊断（`debug/`）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `DiagLog` | `debug/DiagLog.kt` | 诊断日志单一出口；`enabled` 关掉就什么都不做 |
| `DiagProbe` | `debug/DiagProbe.kt` | 探针浮层（可拖动，收起成小条） |

### 4.6 主题与自适应（`ui/theme/`、`ui/AdaptiveBreakpoints.kt`）

| 符号 | 位置 | 说明 |
| --- | --- | --- |
| `fun DeepSeekHarnessTheme(darkTheme, fontScale, appTheme, content)` | `ui/theme/Theme.kt` | 全 App 唯一主题入口；`appTheme` 默认 `PAPER`（零改动） |
| `val LocalSemanticColors` | `ui/theme/Theme.kt` | 连接通/断语义色下发（CompositionLocal） |
| `internal fun paletteOf(theme: AppTheme): ThemePalette` | `ui/theme/ThemePalettes.kt` | 主题 → 明暗两份色板 + 两套语义色 |
| `data class ThemePalette` / `data class SemanticColors` | `ui/theme/ThemePalettes.kt` | 一套主题的完整外观 |
| `internal enum class PaneMode` | `ui/AdaptiveBreakpoints.kt` | 宽度四档（Compact/Medium/Expanded/Large） |
| `internal fun paneModeFor(width: Dp): PaneMode` | `ui/AdaptiveBreakpoints.kt` | 纯函数，阈值 600/840/1200 |
| `internal fun isWideLayout(width: Dp): Boolean` | `ui/AdaptiveBreakpoints.kt` | 等价于改造前的 `maxWidth >= 840.dp` |
| `val ConnectionOnlineColor` / `ConnectionOfflineColor` | `ui/Components.kt` | `@Composable` 取色入口（语义色随主题走） |
| `internal fun displayEntryContentType(entry)` | `ui/chat/ChatProcessGroup.kt` | LazyColumn `contentType`（消息 / 过程组） |

> **主题与明暗正交**：`AppTheme` 定色相基调，`InterfaceStyle` 定深浅，组合出 8 份色板。
> 两份偏好**都进 `HarnessState`**，且都必须出现在 `withPreferences()` 里 ——
> 漏一处，用户切一次连接就被打回默认（选择器会「自己跳回去」）。

---

## 5. 改接口的检查清单

按顺序做，**不要跳步**：

1. **先改 bridge 仓**：API.md + OpenAPI + AsyncAPI + Kotlin SDK + 合约测试，五处一起。
2. **能力位**：客户端要据此分支才加；不需要分支的（收到就用、没有就晚一点）**不加**。
3. **App 侧模型**：`data/Models.kt` 等 DTO（`ignoreUnknownKeys` 让旧字段可选）。
4. **App 侧映射**：`ChatPresentation` / `Chat*` 纯函数，配单测。
5. **本文与锚点**：更新 §3.1 表与文末 `interfaces-check` 区。
6. **`scripts/build.sh docsCheck` + `:app:testDebugUnitTest`**，全绿再提交。
7. **真机确认**：涉及手势、键盘、WebView、耗电、断流的一律真机验证（单测绿 ≠ 行为对）。

---

## 6. 机检锚点（docsCheck 读取，勿改格式）

> 本区由 `build.gradle.kts` 的 `DocsCheckTask` 解析：第二列文件必须存在，
> 且该文件包含第一列里提取出的标识符（取最后一串 `[A-Za-z_][A-Za-z0-9_]*`）。
> 改类名/方法名或搬文件时同步这里，否则 `scripts/build.sh docsCheck` 变红。

<!-- interfaces-check:begin -->
| 符号 | 位置 | 类型 |
| --- | --- | --- |
| `class DshClient` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | class |
| `fun health` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun pair` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun resolvePath` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun uploadAttachment` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun readAttachment` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun events` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun eventsUrl` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `interface KeyValueStore` | `app/src/main/java/io/github/westanke/dshcompanion/data/HostStore.kt` | interface |
| `interface SecretBox` | `app/src/main/java/io/github/westanke/dshcompanion/data/HostStore.kt` | interface |
| `class SecureConnectionStore` | `app/src/main/java/io/github/westanke/dshcompanion/data/SecureConnectionStore.kt` | class |
| `class AppPreferences` | `app/src/main/java/io/github/westanke/dshcompanion/data/AppPreferences.kt` | class |
| `fun normalizeFontScale` | `app/src/main/java/io/github/westanke/dshcompanion/data/AppPreferences.kt` | function |
| `enum class AppTheme` | `app/src/main/java/io/github/westanke/dshcompanion/data/AppPreferences.kt` | enum |
| `fun paletteOf` | `app/src/main/java/io/github/westanke/dshcompanion/ui/theme/ThemePalettes.kt` | function |
| `data class ThemePalette` | `app/src/main/java/io/github/westanke/dshcompanion/ui/theme/ThemePalettes.kt` | class |
| `fun paneModeFor` | `app/src/main/java/io/github/westanke/dshcompanion/ui/AdaptiveBreakpoints.kt` | function |
| `fun isWideLayout` | `app/src/main/java/io/github/westanke/dshcompanion/ui/AdaptiveBreakpoints.kt` | function |
| `enum class PaneMode` | `app/src/main/java/io/github/westanke/dshcompanion/ui/AdaptiveBreakpoints.kt` | enum |
| `class ConnectionCoordinator` | `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionCoordinator.kt` | class |
| `class DshConnectionException` | `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionCoordinator.kt` | class |
| `interface ProbeOutcome` | `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionCoordinator.kt` | interface |
| `internal class EventDebouncer` | `app/src/main/java/io/github/westanke/dshcompanion/data/EventDebouncer.kt` | class |
| `internal class RequestGate` | `app/src/main/java/io/github/westanke/dshcompanion/data/RequestGate.kt` | class |
| `fun extractHtmlBlocks` | `app/src/main/java/io/github/westanke/dshcompanion/data/HtmlPreview.kt` | function |
| `enum class ReleaseChannel` | `app/src/main/java/io/github/westanke/dshcompanion/data/UpdateChecker.kt` | enum |
| `fun channelForLanguage` | `app/src/main/java/io/github/westanke/dshcompanion/data/UpdateChecker.kt` | function |
| `fun isNewerVersion` | `app/src/main/java/io/github/westanke/dshcompanion/data/UpdateChecker.kt` | function |
| `fun pickApkAsset` | `app/src/main/java/io/github/westanke/dshcompanion/data/UpdateChecker.kt` | function |
| `data class BridgeCapabilities` | `app/src/main/java/io/github/westanke/dshcompanion/data/BridgeCapabilities.kt` | class |
| `fun parseBridgeCapabilities` | `app/src/main/java/io/github/westanke/dshcompanion/data/BridgeCapabilities.kt` | function |
| `fun missingRequiredCapabilities` | `app/src/main/java/io/github/westanke/dshcompanion/data/BridgeCapabilities.kt` | function |
| `data class HarnessState` | `app/src/main/java/io/github/westanke/dshcompanion/HarnessModels.kt` | class |
| `class HarnessViewModel` | `app/src/main/java/io/github/westanke/dshcompanion/HarnessViewModel.kt` | class |
| `fun ChatHistory.displayItems` | `app/src/main/java/io/github/westanke/dshcompanion/data/ChatPresentation.kt` | function |
| `fun ChatHistory.deliverables` | `app/src/main/java/io/github/westanke/dshcompanion/data/ChatPresentation.kt` | function |
| `enum class ChatItemKind` | `app/src/main/java/io/github/westanke/dshcompanion/data/ChatModels.kt` | enum |
| `data class ChatDisplayItem` | `app/src/main/java/io/github/westanke/dshcompanion/data/ChatModels.kt` | class |
| `data class ChatTurnFailure` | `app/src/main/java/io/github/westanke/dshcompanion/data/ChatModels.kt` | class |
| `class ChatWebBridge` | `app/src/main/java/io/github/westanke/dshcompanion/web/ChatWebBridge.kt` | class |
| `object ActiveChatBridge` | `app/src/main/java/io/github/westanke/dshcompanion/web/ActiveChatBridge.kt` | object |
| `object DiagLog` | `app/src/main/java/io/github/westanke/dshcompanion/debug/DiagLog.kt` | object |
| `fun DiagProbe` | `app/src/main/java/io/github/westanke/dshcompanion/debug/DiagProbe.kt` | function |
| `fun settingsNamespaces` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `fun updateSettingsNamespace` | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | function |
| `data class SettingsNamespaceList` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | class |
| `data class SettingsNamespace` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | class |
| `object SettingsSchema` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | object |
| `enum class SettingsControl` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | enum |
| `fun humanizeFieldName` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | function |
| `fun clampSettingsNumber` | `app/src/main/java/io/github/westanke/dshcompanion/data/SettingsNamespaces.kt` | function |
<!-- interfaces-check:end -->
