package io.github.westanke.dshcompanion.data

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
    /**
     * 会话工作目录**相对授权根**的路径 —— 列举接口（`roots/{id}/entries`）要的正是它。
     *
     * **空字符串是合法值**：会话工作目录正好就是授权根本身时，服务端算出的相对路径
     * 就是空的。别把空串当成「没有工作目录」丢掉，那会让 `@` 补全在根目录下直接瘫痪
     * （线上就是这么表现的：「读不到这个会话的工作目录」）。
     */
    val cwd: String,
    /**
     * 会话工作目录的**绝对路径**（服务端 v2.3.5 起提供；老服务端不返回时为 null）。
     *
     * `@` 要往输入框里插一条绝对路径（官方与内核给模型看的都是绝对路径，相对路径会让
     * 模型先猜「相对于谁」），而客户端手里没有根的 realPath —— `RootView` 刻意不含它。
     * 这个字段就是那条缺失的桥。
     */
    val cwdAbsolute: String? = null,
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

/**
 * `ask_user_question` 的问答模型（方案 A：**只支持 `continued` 状态**）。
 *
 * 为什么只做 continued：`open` 是内核的前台阻塞态 —— Agent 正卡在那里等答案，
 * 远程客户端答不了，也不该假装答得了。`continued` 才是「问题问出来了、Agent 已经继续跑、
 * 答复属于补交」的那一类，正好是手机上能补的。
 *
 * 字段几乎都给了默认值：插件对 questions 各项是**原样透传**，将来内核往选项里加字段
 * 不该让老客户端整条解不出来（`DshClient` 的 Json 是 `ignoreUnknownKeys = true`）。
 * `intent`（如 plan-review）刻意**不建模**：内核明确说它只影响呈现、永不改变协议，
 * 所以命中与否都按普通选项渲染 —— 特判只会多出一个会过期的分支。
 */
@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

@Serializable
data class ChatQuestion(
    /** 回传时必须**原样带回**的题目 id。 */
    val id: String,
    val question: String,
    /** 短标题；可能缺席。 */
    val header: String? = null,
    /** 补充说明；可能缺席。 */
    val detail: String? = null,
    /** 可能缺席或为空 —— 空数组表示这题要自由文本（对应 [QuestionAnswer.custom]）。 */
    val options: List<QuestionOption> = emptyList(),
    /**
     * 单选还是多选，由**提问方**声明。
     *
     * 缺省 false（单选）。UI 不许自己猜（比如「选项多就是多选」那种推断）——
     * 猜错的后果是用户以为能多选、提交上去只剩一个。
     */
    val multiSelect: Boolean = false,
)

/** 一次 `ask_user_question` 调用留下的一组待答问题。 */
@Serializable
data class PendingQuestionSet(
    val callId: String,
    /** 目前只处理 [QUESTION_STATE_CONTINUED]；其余状态原样收下但不上卡片。 */
    val state: String = "",
    val questions: List<ChatQuestion> = emptyList(),
    /**
     * 这道题是不是**正阻塞着** agent 前台等待（插件侧由 QuestionBridge 接管）。
     *
     * 两种来源的语义不同，值得区分：
     * - `viaBridge = true`：插件挂起了这次调用，**agent 正卡在这里等答案**。
     *   在手机上作答，答案会作为那次工具调用的返回值送回去。
     * - `viaBridge` 缺省（false）：内核那条「已超时、可迟到补交」的路
     *   （`state = continued`），agent 已经往下干了，答复属于补交。
     *
     * 带默认值是因为它由**插件新加**（老服务端不返回它）——
     * 严格解码器下缺键会让整条解不出来。
     */
    val viaBridge: Boolean = false,
)

/** 一道题的答案。 */
@Serializable
data class QuestionAnswer(
    val id: String,
    /** 选中的选项 label；没有选项的题填空数组。 */
    val selected: List<String>,
    /**
     * 自由文本答案。刻意用**非空默认值** `""`：kotlinx 默认不编码等于默认值的字段，
     * 于是「这题不填自由文本」时请求体里干脆没有 `custom` 这个键，
     * 而不是发一个 `"custom": null` 让服务端去猜。
     */
    val custom: String = "",
)

@Serializable
data class QuestionAnswerRequest(val answers: List<QuestionAnswer>)

/** 唯一支持远程回答的状态（见 [PendingQuestionSet] 的说明）。 */
const val QUESTION_STATE_CONTINUED: String = "continued"

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
    /** 包自己声明的简介；服务器可能给 null。 */
    val description: String? = null,
    /** 包的主页 / 仓库地址（服务器已剥掉 `git+` 与 `.git`）；可能为 null。 */
    val homepage: String? = null,
    /**
     * 有没有新版本可装。
     *
     * **三态，别当布尔用**：`null` 表示「没查 / 查不到」，与 `false`（「已是最新」）
     * 不是一回事。服务器只在带 `?checkUpdates=1` 时才去问 registry —— 不查的时候
     * 把 null 显示成「已是最新」就是在撒谎。
     */
    val updateAvailable: Boolean? = null,
    /** npm 上的最新版本号；没查或查不到时为 null。 */
    val latestVersion: String? = null,
    /**
     * 能否在 App 里启停。
     *
     * 官方包（随 DSH 运行时安装）与没装进 profile 的包都为 false —— 界面对它们
     * 不该画开关：画了就是骗用户去点一个必然失败的按钮。
     */
    val toggleable: Boolean = false,
)

/**
 * `PATCH /api/v1/settings/plugins` 的响应：启停一个插件的结果。
 *
 * [restartRequired] 目前**恒为 true**：服务端改写的是 profile 的
 * `dsh.profile.bundles`，而那份清单**只在 DSH 启动时读一次** —— 运行中的进程
 * 仍旧用着旧集合。界面必须把这句话明说，否则用户点完开关看到插件照旧在跑，
 * 只会以为「这个功能是坏的」。
 */
@Serializable
data class PluginToggleResult(
    val name: String = "",
    val loaded: Boolean = false,
    /** 改动前写的备份文件名（幂等时为空串）。 */
    val backupName: String = "",
    /** 改动后 bundles 数组的长度，便于界面显示「17 → 18」。 */
    val bundleCount: Int = 0,
    val restartRequired: Boolean = true,
)

/** `PATCH /api/v1/settings/plugins` 的请求体。 */
@Serializable
data class PluginToggleRequest(val name: String, val loaded: Boolean)

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
