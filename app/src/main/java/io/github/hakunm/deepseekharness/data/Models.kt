package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.SerialName
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

/**
 * `GET /roots/resolve?path=<绝对路径>` 的响应：把一个**服务器绝对路径**翻译成
 * 「它属于哪个授权根 + 相对路径」。
 *
 * 为什么需要服务端代劳：会话事件里的路径是绝对路径，而 `/roots` 刻意不返回根的绝对
 * 路径，客户端没有足够信息自己完成映射；把绝对路径直接喂给 `/roots/:id/content`
 * 又会被 `PATH_INVALID` 拒绝。响应里**依然不含根的绝对路径**，安全性没有被削弱。
 *
 * 所有字段都给了默认值，理由同 [AgentPreset.trust] 与 [PluginInventory]：本项目用的是
 * 严格解码器（`ignoreUnknownKeys` 只放过多余键，缺键照样抛异常），而字段可能随版本增减。
 * `size` 与 `contentType` 在服务端是 `number | null` / `string | null`，必须可空 ——
 * 目录没有大小和内容类型，服务端会**显式发送 `null`**。
 */
@Serializable
data class ResolvedPath(
    val rootId: String = "",
    val path: String = "",
    /** `file` 或 `directory`；未知取值由界面兜底，不在这里抛错。 */
    val kind: String = "",
    val size: Long? = null,
    val modifiedAt: Double = 0.0,
    val contentType: String? = null,
)

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

/**
 * 一条 prompt 内容 —— DSH 内核的 `PromptContentPart` 联合类型。
 *
 * 三种形态（与内核逐字段对齐，多一个字段就可能让服务端的 union 解析失败）：
 * - [TextPart]：纯文本；
 * - [ImagePart]：图片，**base64 直接内联**，一步到位，不需要先上传；
 * - [FilePart]：文件，先经 `chat/sessions/:id/attachments` 换来 `receiptId`，再引用。
 *
 * 判别器是 `type`（[SerialName] 的值），这正是 kotlinx.serialization 对 sealed 类型的
 * 默认判别器字段名，所以序列化结果就是内核要的形状：
 * `{"type":"image","mediaType":"image/png","data":"..."}`。
 *
 * 为什么每个字段都给默认值：本项目用的是**严格解码器**，缺键会直接抛异常
 * （[AgentPreset.trust] / [PluginInventory.profile] / [ResolvedPath] 三次事故）。
 * 这些类型虽然主要用于编码，但默认值能让它们在测试与将来可能的回读里保持宽容。
 */
@Serializable
sealed interface PromptPart {
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String = "") : PromptPart

    @Serializable
    @SerialName("image")
    data class ImagePart(
        /** 如 `image/png`；内核按它决定怎么解码 [data]。 */
        val mediaType: String = "image/png",
        /** 标准 base64（非 URL-safe）。正常无填充需求，编码器默认带 `=`。 */
        val data: String = "",
        /** 可选的原文件名，仅用于展示；为 null 时不发送该键。 */
        val name: String? = null,
    ) : PromptPart

    @Serializable
    @SerialName("file")
    data class FilePart(val receiptId: String = "") : PromptPart
}

/**
 * 保留任务书里的名字。两者是同一个类型，用别名只是为了不产生第二份实现。
 */
typealias PromptContentPart = PromptPart

/**
 * `POST /chat/sessions/:id/attachments` 的响应。
 *
 * 字段全带默认值，理由同 [PluginInventory]：服务端将来省略 `name`（或返回 `null`）
 * 时，不能让整个上传流程因为一个可选字段就解码失败。
 */
@Serializable
data class UploadedAttachment(
    val receiptId: String = "",
    val name: String = "",
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
