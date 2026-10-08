package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthView(val ok: Boolean, val version: String, val pluginVersion: String? = null)

@Serializable
data class RootView(val id: String, val label: String, val createdAt: Long)

@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val rootIds: List<String>,
)

@Serializable
data class PairingResult(val token: String, val device: DeviceView)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val kind: String,
    val size: Long,
    val modifiedAt: Double,
    val writable: Boolean,
)

@Serializable
data class DirectoryPage(val path: String, val entries: List<FileEntry>, val nextCursor: String? = null)

@Serializable
data class TrashEntry(
    val id: String,
    val rootId: String,
    val path: String,
    val kind: String,
    val size: Long,
    val createdAt: Long,
    val status: String,
)

@Serializable
data class ChatSession(
    val id: String,
    val rootId: String,
    val cwd: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val title: String? = null,
    val workspaceId: String? = null,
    val workspaceTitle: String? = null,
    val agentPreset: String? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val pendingInteraction: String? = null,
)

@Serializable
data class PendingApproval(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val reason: String? = null,
    val detail: String? = null,
    val risk: String,
    val requestedAt: Long,
)

@Serializable
data class CommandInput(val hint: String)

@Serializable
data class CommandDescriptor(
    val name: String,
    val description: String,
    val input: CommandInput? = null,
)

@Serializable
data class CommandResult(
    val kind: String,
    val text: String? = null,
    val sourceEventSeq: Int? = null,
)

@Serializable
data class CommandExecution(val commandId: String, val result: CommandResult)

data class TodoItem(val content: String, val status: String)

data class PermissionOption(val value: String, val name: String, val description: String? = null)

data class PermissionSelect(val options: List<PermissionOption>, val currentValue: String)

@Serializable
data class CreatedSession(val id: String, val agentPreset: String? = null)

@Serializable
data class RenamedSession(val title: String, val seq: Int)

@Serializable
data class ChatWorkspace(
    val id: String,
    val title: String,
    val rootId: String,
    val path: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AgentPreset(
    val id: String,
    val name: String,
    val description: String? = null,
    // DSH 0.2.x dropped the `system`/`user` trust split: `AgentPresetRow` carries
    // no such field, and a strict decoder rejects the whole list with
    // "Field 'trust' is required ... missing at path: $.items[0]". Default it so
    // one absent optional field cannot break the preset picker.
    val trust: String = "system",
    val isDefault: Boolean = false,
    val available: Boolean = true,
)

@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class ModelReasoningEffort(val id: String, val name: String, val description: String? = null)

@Serializable
data class ModelReasoning(
    val efforts: List<ModelReasoningEffort>,
    val defaultEffort: String? = null,
)

@Serializable
data class ModelView(
    val id: String,
    val name: String,
    val description: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val reasoning: ModelReasoning? = null,
)

@Serializable
data class ModelProviderGroup(val id: String, val name: String, val models: List<ModelView>)

@Serializable
data class ModelFailure(val provider: String, val message: String)

@Serializable
data class SessionModels(
    val current: ModelSelection,
    val routable: Boolean,
    val groups: List<ModelProviderGroup>,
    val failures: List<ModelFailure> = emptyList(),
)

@Serializable
data class ProviderModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
)

@Serializable
data class ProviderConfig(
    val baseURL: String? = null,
    val api: String? = null,
    val displayName: String? = null,
    val thinking: String? = null,
    val reasoningEffort: String? = null,
    val models: List<ProviderModel> = emptyList(),
    val modelsInherited: Boolean = false,
)

@Serializable
data class CredentialState(
    val ref: String,
    val configured: Boolean,
    val source: String? = null,
    val writable: Boolean,
)

@Serializable
data class ProviderView(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val declared: Boolean? = null,
    val configurable: Boolean,
    val configured: Boolean,
    val removable: Boolean,
    val credential: CredentialState,
    val config: ProviderConfig,
)

@Serializable
data class ProviderSettings(
    val writable: Boolean,
    val revisionByNamespace: Map<String, Int>,
    val customProvider: CustomProviderCapability = CustomProviderCapability(),
    val providers: List<ProviderView>,
)

@Serializable
data class CustomProviderCapability(
    val available: Boolean = false,
    val protocols: List<String> = emptyList(),
    val revision: Int? = null,
)

@Serializable
data class ProviderPatch(
    val displayName: String? = null,
    val baseURL: String? = null,
    val api: String? = null,
    val apiKey: String? = null,
    val thinking: String? = null,
    val reasoningEffort: String? = null,
    val models: List<ProviderModel>? = null,
    val expectedRevision: Int? = null,
)

@Serializable
data class CustomProviderCreate(
    val id: String,
    val displayName: String? = null,
    val baseURL: String,
    val api: String,
    val apiKey: String? = null,
    val models: List<ProviderModel>,
    val expectedRevision: Int? = null,
)

/**
 * 已安装插件清单里的一条记录。
 *
 * 所有字段都带默认值，理由和 [AgentPreset.trust] 那次事故一样：DSH 解码器是严格的
 * （`ignoreUnknownKeys` 只放过多余的键，缺键照样抛异常），而插件清单的字段明显会随
 * DSH 版本增减。一个可选的 `official` 今天缺席，就不该把整页清单打挂。
 *
 * [state] 取服务端约定的四种值：`loaded` / `installed-not-loaded` / `declared-missing` /
 * `runtime-provided`（官方包随 DSH 运行时自带，属正常状态）；未知取值由 UI 兜底显示，
 * 不在这里抛错。
 */
@Serializable
data class PluginEntry(
    val name: String = "",
    /** package.json 里的版本声明，如 `^0.4.5`；可能为 null。 */
    val declared: String? = null,
    /** node_modules 里实际装上的版本；可能为 null。 */
    val installed: String? = null,
    val loaded: Boolean = false,
    /** 是否 DSH 官方包（`@deepseek-ai/` 前缀）。 */
    val official: Boolean = false,
    val state: String = "",
)

/**
 * `GET /api/v1/settings/plugins` 的响应。
 *
 * [available] 为 false 时 [items] 为空，[reason] 给出人可读的失败原因
 * （`PROFILE_UNKNOWN` / `MANIFEST_UNREADABLE`），界面必须显示解释而不是空白。
 *
 * 注意 [profile] 与 [profilePath] 在服务端是 `string | null`：判定不出 profile 时
 * 会**显式发送 `null`**。所以它们必须声明为可空 —— 非空属性遇到 JSON 里的 null 不会
 * 退回默认值，而是直接让整个响应解码失败（比 [AgentPreset.trust] 那次缺键更隐蔽）。
 */
@Serializable
data class PluginInventory(
    val profile: String? = null,
    val profilePath: String? = null,
    val available: Boolean = false,
    val reason: String? = null,
    val loadedCount: Int = 0,
    val problemCount: Int = 0,
    val items: List<PluginEntry> = emptyList(),
)

@Serializable
data class SessionEvent(
    val type: String,
    val seq: Int,
    val time: Long,
    val data: JsonElement,
    val sourceEventSeqs: List<Int> = emptyList(),
    val surfaceOp: JsonElement? = null,
)

@Serializable
data class HistoryEntry(val event: SessionEvent, val view: JsonElement? = null)

@Serializable
data class ChatHistory(
    val events: List<HistoryEntry>,
    val hasMore: Boolean,
    val projections: JsonElement? = null,
)

@Serializable
data class WorkspaceEvent(val id: String, val type: String, val time: Long, val data: JsonElement)

data class FileContent(val bytes: ByteArray, val etag: String, val contentType: String?)

data class StoredConnection(val endpoint: String, val token: String)

class DshApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)
