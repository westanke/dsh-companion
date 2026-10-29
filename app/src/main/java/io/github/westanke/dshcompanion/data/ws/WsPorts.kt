package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.HistoryEntry
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.util.TimeZone

/**
 * 三个高频域的**能力面**接口（`docs/project/WS-FIRST-DESIGN.md` §3.1 的 `SessionPort` /
 * `MessagePort` / `EventPort`）。
 *
 * ## 为什么是接口而不是直接调 `DshClient`
 *
 * 每个域都有**两套实现**：[WsSessionPort] 走 `/ws/mobile`，[RestSessionPort] 走 `DshClient` 的
 * REST 方法。装配层（`HarnessWsPortsViewModel.kt` 的 [Adaptive] 系列）按 [ChannelState] 选一个。
 * 好处是：ViewModel 只认接口，通道差异一行都不出现在业务代码里；各域单测用假实现跑，
 * 不必起 WS（设计 §6 最后一条：测试翻倍 → 通道差异只在 `MobileWsTransportTest` 一处测）。
 *
 * ## 签名为什么长得像 `DshClient` 而不是"更优雅"的样子
 *
 * 硬判据是「[ChannelState] 为 RestOnly / WsDown 时行为与改造前**逐字节一致**」。所以：
 *
 * - `createSession` 保留 `workspaceId` + `agentPreset` + `reuseBlank` 三个参数，
 *   **故意不迁 WS**：桥的 `session-create` 只认 `rootId` + `cwd`，且内部写死
 *   `reuseBlank: false`（`ws-sessions.ts` 的 `handleSessionCreate`），与 REST 的
 *   `createSessionInWorkspace` 语义不可互换。迁过去会让"新建会话带上指定 agent 预设"
 *   静默失效，也会让"复用空白会话"变成"每次都新建"。
 * - `sendMessage` 收 `List<PromptPart>` 而不是 `JsonArray`：REST 侧要交给
 *   `DshClient.sendMessage` 的正是这个类型，中间不做一次 JSON 往返，才能保证退化路径
 *   **字节级**等同今天。
 * - `history` 的 `beforeSeq` 决定走哪条通道（见 [WsSessionPort.history]）。
 */
internal interface SessionPort {
    /** 会话列表。WS 帧 `sessions`。`archived = true` **恒走 REST**（设计 §4.1）。 */
    suspend fun sessions(archived: Boolean = false): List<ChatSession>

    /**
     * 历史事件与投影。
     *
     * `beforeSeq == null`（首屏/刷新）**恒走 REST**：桥的 `handleHistory` 只解构
     * `{events, hasMore}`，把 `projections` 丢掉了（`chat-adapter.ts` 明明返回了它）。
     * 投影是待办清单、会话统计、WebView 页的数据源（`ChatPresentation.todoItems()`、
     * `SessionAnalytics`、`ChatWebScreen`），丢掉就是整块面板变空。
     *
     * `beforeSeq != null`（翻页）走 WS：分页本来就不需要投影。
     */
    suspend fun history(sessionId: String, beforeSeq: Int? = null, maxMessages: Int? = null): ChatHistory

    /** 新建会话，返回 sessionId。**恒走 REST**（理由见接口 KDoc）。 */
    suspend fun createSession(workspaceId: String, agentPreset: String? = null, reuseBlank: Boolean? = null): String

    /** 重命名。WS 帧 `session-rename` → 应答 `session-renamed`（带 `data.sessionId`，可安全配对）。 */
    suspend fun renameSession(sessionId: String, title: String)

    /** 归档。WS 帧 `session-archive` → 应答 `session-archived`（带 `data.sessionId`）。 */
    suspend fun archiveSession(sessionId: String)

    /** 停止当前回合（不是删会话）。WS 帧 `session-cancel` → 应答 `session-cancelled`。 */
    suspend fun cancelSession(sessionId: String)

    /**
     * 从某轮分叉，返回新 sessionId。WS 帧 `fork` → 应答 kind 也叫 `fork`。
     *
     * 应答只有 `data.sessionId`（**新** id），没有原会话 id 也没有别的判别字段，
     * 所以这一条在退化路径上只能靠 [WsRequestRouter] 的「同 kind 串行」认出来。
     */
    suspend fun forkSession(sessionId: String, atSeq: Int? = null): String
}

internal interface MessagePort {
    /**
     * 发消息。WS 帧 `message` → 应答 `sent`（带 `data.sessionId` + `data.accepted`）。
     *
     * @param steer `true` 时插话（桥 `mode: "steer"`），否则排队（`mode: "queue"`）。
     */
    suspend fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean = false)

    /**
     * 读附件字节。**恒走 REST**。
     *
     * 桥的 `attachment` 帧是**上传**（`{sessionId, data: base64, name}` → `{receiptId, name}`），
     * 与这里要的"读字节"是两回事；WS 的下载要走 `file-download-*` 那套分片状态机，
     * 属于设计 §3.5 明确不迁的范围。留 REST 也意味着超时是 OkHttp 的 readTimeout，
     * 不会出现"附件读取超时把整条 WS 掀掉"。
     */
    suspend fun attachment(sessionId: String, attachmentId: String): ByteArray
}

internal interface EventPort {
    /**
     * 订阅会话事件。WS 帧 `subscribe` → 应答 `subscribed`（**无 requestId**，走推送）。
     * @return 是否成功发出（连接不可用时 false，调用方回落到 `/api/v1/events`）。
     */
    fun subscribe(sessionId: String? = null): Boolean

    /** 退订。WS 帧 `unsubscribe`（无应答）。 */
    fun unsubscribe(sessionId: String? = null): Boolean

    /** 推送回调：`data` 已是 `WorkspaceEvent` 形状时交给这里；解析失败返回 null 由实现丢弃。 */
    fun onEvent(handler: (WorkspaceEvent) -> Unit)
}

// ───────────────────────── WS 实现 ─────────────────────────

/**
 * `SessionPort` 的 WS 实现。
 *
 * 三条帧都**没有 requestId 回显**（桥 `ws-sessions.ts` 里 `grep requestId` = 0 命中），
 * 所以全部经 [WsRequestRouter]：先带 requestId 发出去，桥不回显就按响应 kind 配对。
 * 直接调 [MobileWsTransport.send] 会稳定等满超时并按设计 §3.3 `close(1001)` 掀连接 —— 不能用。
 */
internal class WsSessionPort(
    private val router: WsRequestRouter,
    private val json: Json,
) : SessionPort {

    override suspend fun sessions(archived: Boolean): List<ChatSession> {
        val frame = router.request(
            requestType = WsClientFrame.SESSIONS,
            expectedKind = WsServerFrame.SESSIONS,
            payload = if (archived) JsonObject(mapOf("archived" to JsonPrimitive(true))) else JsonObject(emptyMap()),
        )
        val items = WsFrames.dataOf(frame)["sessions"] ?: return emptyList()
        return json.decodeFromJsonElement(SessionList.serializer(), items).items
    }

    override suspend fun history(sessionId: String, beforeSeq: Int?, maxMessages: Int?): ChatHistory {
        val frame = router.request(
            requestType = WsClientFrame.HISTORY,
            expectedKind = WsServerFrame.HISTORY,
            payload = buildMap {
                put("sessionId", JsonPrimitive(sessionId))
                if (beforeSeq != null) put("beforeSeq", JsonPrimitive(beforeSeq))
                if (maxMessages != null) put("maxMessages", JsonPrimitive(maxMessages))
            }.let(::JsonObject),
            // 应答带 data.sessionId：同 kind 已串行，但仍按它校验一次 —— 会话切换时的
            // 迟到帧不该被当成新会话的历史。
            matcher = { WsFrames.dataOf(it).sessionIdOf() == sessionId },
        )
        val data = WsFrames.dataOf(frame)
        val events = data["events"]
        val entries = if (events == null) {
            emptyList()
        } else {
            json.decodeFromJsonElement(ListSerializer(HistoryEntry.serializer()), events)
        }
        val hasMore = (data["hasMore"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        // projections 恒为 null：WS 帧不带它，而需要投影的调用点（beforeSeq == null）走 REST。
        return ChatHistory(events = entries, hasMore = hasMore, projections = null)
    }

    override suspend fun createSession(workspaceId: String, agentPreset: String?, reuseBlank: Boolean?): String =
        throw DshApiException(
            0,
            "WS_UNSUPPORTED",
            "session-create 不走 WS：桥侧帧只认 rootId+cwd 且强制 reuseBlank=false，与 REST 语义不等价",
        )

    override suspend fun renameSession(sessionId: String, title: String) {
        router.request(
            requestType = WsClientFrame.SESSION_RENAME,
            expectedKind = WsServerFrame.SESSION_RENAMED,
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId), "title" to JsonPrimitive(title))),
            matcher = { WsFrames.dataOf(it).sessionIdOf() == sessionId },
        )
    }

    override suspend fun archiveSession(sessionId: String) {
        router.request(
            requestType = WsClientFrame.SESSION_ARCHIVE,
            expectedKind = WsServerFrame.SESSION_ARCHIVED,
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
            matcher = { WsFrames.dataOf(it).sessionIdOf() == sessionId },
        )
    }

    override suspend fun cancelSession(sessionId: String) {
        router.request(
            requestType = WsClientFrame.SESSION_CANCEL,
            expectedKind = WsServerFrame.SESSION_CANCELLED,
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
            matcher = { WsFrames.dataOf(it).sessionIdOf() == sessionId },
        )
    }

    override suspend fun forkSession(sessionId: String, atSeq: Int?): String {
        val frame = router.request(
            requestType = WsClientFrame.FORK,
            expectedKind = WsServerFrame.FORK,
            payload = buildMap {
                put("sessionId", JsonPrimitive(sessionId))
                if (atSeq != null) put("atSeq", JsonPrimitive(atSeq))
            }.let(::JsonObject),
            // 没有判别字段（应答只有新 id），只能靠同 kind 串行保证唯一。
        )
        return WsFrames.dataOf(frame).sessionIdOf()
            ?: throw DshApiException(0, "WS_BAD_FRAME", "fork 应答缺少 data.sessionId")
    }
}

/**
 * `MessagePort` 的 WS 实现。
 *
 * 与 REST 的两处**必须**对齐的差异（都是桥侧帧的硬约束，不是选择）：
 *
 * 1. **只认 `content` 数组**：`ws-handler.ts` 的 `handleMessageFrame` 只读 `frame.content`，
 *    没有 REST `readPromptParts` 那样的 `body.text` 回退。所以纯文本也必须包成
 *    `{"content":[{"type":"text","text":"…"}]}`，不能沿用 `ChatContentParts.messageBody()`
 *    产出的旧格式 `{"text":"…"}`。
 * 2. **必须显式带 `locale`**：REST 走 `DshClient.authenticated()`，会自动加
 *    `Accept-Language`（`interfaceLanguageTag()`），桥据此判 handoff 语言；WS 建连只用
 *    `Sec-WebSocket-Protocol`，不经过那条路径，不显式带就永远是桥的默认值（`zh`）。
 */
internal class WsMessagePort(
    private val router: WsRequestRouter,
    private val json: Json,
    private val localeTag: () -> String?,
) : MessagePort {

    override suspend fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean) {
        require(sessionId.isNotBlank()) {
            "sessionId 不能为空：桥在 sessionId 缺失时会静默新建一个空会话（ws-handler.ts 的 handleMessageFrame）"
        }
        require(parts.isNotEmpty()) { "EMPTY_CONTENT" }
        val content = json.encodeToJsonElement(ListSerializer(PromptPart.serializer()), parts)
        val payload = buildMap {
            put("sessionId", JsonPrimitive(sessionId))
            put("content", content)
            put("mode", JsonPrimitive(if (steer) "steer" else "queue"))
            put("clientTimeZone", JsonPrimitive(TimeZone.getDefault().id))
            localeTag()?.let { put("locale", JsonPrimitive(it)) }
        }.let(::JsonObject)
        router.request(
            requestType = WsClientFrame.MESSAGE,
            expectedKind = WsServerFrame.SENT,
            payload = payload,
            // `sent` 也用于 `unsubscribe` 的确认（`{type:'unsubscribe', acknowledged:true}`）；
            // 靠 data.sessionId + data.accepted 把两者分开，避免发消息的调用被退订确认"完成"。
            matcher = { frame ->
                val data = WsFrames.dataOf(frame)
                data.sessionIdOf() == sessionId && data.containsKey("accepted")
            },
        )
    }

    override suspend fun attachment(sessionId: String, attachmentId: String): ByteArray =
        throw DshApiException(0, "WS_UNSUPPORTED", "附件读取不走 WS 帧：桥的 attachment 帧是上传语义，恒走 REST")
}

/**
 * `EventPort` 的 WS 实现。
 *
 * 这一域**不依赖 requestId**：`subscribe` 的应答 `subscribed` 本身就是无 requestId 的推送。
 */
internal class WsEventPort(private val transport: MobileWsTransport) : EventPort {

    private var handler: ((WorkspaceEvent) -> Unit)? = null

    override fun subscribe(sessionId: String?): Boolean =
        transport.sendNoReply(WsFrames.command(WsClientFrame.SUBSCRIBE, sessionFilter(sessionId)))

    override fun unsubscribe(sessionId: String?): Boolean =
        transport.sendNoReply(WsFrames.command(WsClientFrame.UNSUBSCRIBE, sessionFilter(sessionId)))

    override fun onEvent(handler: (WorkspaceEvent) -> Unit) {
        this.handler = handler
    }

    /** 供装配层接推送：当前注册的处理器。 */
    internal fun currentHandler(): ((WorkspaceEvent) -> Unit)? = handler

    private fun sessionFilter(sessionId: String?): JsonObject =
        if (sessionId == null) JsonObject(emptyMap()) else JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId)))
}

// ───────────────────────── REST 实现 ─────────────────────────
//
// 这三个类**必须**与改造前 `HarnessSessionsViewModel` / `HarnessViewModel` 里那些
// `api.xxx()` 调用逐字等价：它们是退化路径（ChannelState 为 RestOnly / WsDown）的唯一出口，
// 而"退化时行为与今天逐字节一致"是 P2 的硬判据。
//
// 全部套 `withContext(Dispatchers.IO)`：`DshClient` 的方法是同步阻塞的，而接口是 suspend。
// 调用方（`launchOperation`）本来就在 `Dispatchers.IO` 上跑，这里再套一层只是让接口自身安全
// —— 谁写错上下文都不会把主线程卡住。

internal class RestSessionPort(private val api: () -> DshClient) : SessionPort {

    override suspend fun sessions(archived: Boolean): List<ChatSession> =
        withContext(Dispatchers.IO) { api().sessions(archived) }

    override suspend fun history(sessionId: String, beforeSeq: Int?, maxMessages: Int?): ChatHistory =
        withContext(Dispatchers.IO) { api().history(sessionId, beforeSeq, maxMessages) }

    override suspend fun createSession(workspaceId: String, agentPreset: String?, reuseBlank: Boolean?): String =
        withContext(Dispatchers.IO) { api().createSession(workspaceId, agentPreset, reuseBlank).id }

    override suspend fun renameSession(sessionId: String, title: String) {
        withContext(Dispatchers.IO) { api().renameSession(sessionId, title) }
    }

    override suspend fun archiveSession(sessionId: String) {
        withContext(Dispatchers.IO) { api().archiveSession(sessionId) }
    }

    override suspend fun cancelSession(sessionId: String) {
        withContext(Dispatchers.IO) { api().cancel(sessionId) }
    }

    override suspend fun forkSession(sessionId: String, atSeq: Int?): String =
        withContext(Dispatchers.IO) { api().forkSession(sessionId, atSeq) }
}

internal class RestMessagePort(private val api: () -> DshClient) : MessagePort {

    override suspend fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean) {
        withContext(Dispatchers.IO) { api().sendMessage(sessionId, parts, steer) }
    }

    override suspend fun attachment(sessionId: String, attachmentId: String): ByteArray =
        withContext(Dispatchers.IO) { api().readAttachment(sessionId, attachmentId) }
}

/** `data.sessionId` 读成字符串；缺失返回 null。 */
private fun JsonObject.sessionIdOf(): String? = (this["sessionId"] as? JsonPrimitive)?.contentOrNull

@Serializable
private data class SessionList(val items: List<ChatSession>)
