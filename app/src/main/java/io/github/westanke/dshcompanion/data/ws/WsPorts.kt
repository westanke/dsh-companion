package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 三个高频域的**能力面**接口（`docs/project/WS-FIRST-DESIGN.md` §3.1 的 `SessionPort` /
 * `MessagePort` / `EventPort`）。
 *
 * ## 为什么是「接口 + 骨架」而不是「直接实现」
 *
 * P1 只交付传输层（连接、重连、帧、requestId），P2 才切域。这里先把**签名**钉住有两个用处：
 *
 * 1. P2 写 `HarnessViewModel` 对接时，改的是「用哪个实现」，不是「调哪个方法」——
 *    这样 P2 的 diff 里不会混进接口设计；
 * 2. 各域单测可以用假实现跑，不必起 WS（设计 §6 最后一条：测试翻倍 → 通道差异只在
 *    `MobileWsTransportTest` 一处测）。
 *
 * ## 为什么现在还不能填 WS 实现
 *
 * **桥侧 P0 尚未补齐 requestId 回显**：`src/host/ws-sessions.ts` 里 `grep requestId` 是 **0 命中**
 * （`ws-handler`/`ws-hitl`/`ws-models`/`ws-commands`/`ws-presets`/`ws-host`/`ws-settings` 同为 0），
 * 只有 `ws-goals`(3) / `ws-files`(4) / `ws-team`(2) / `ws-schedule`(2) 有 `echoRequestId`。
 *
 * 也就是说：此刻若真把 `sessions` 帧发出去，服务端会回一条**没有 requestId** 的 `sessions` 帧，
 * 客户端 pending map 认不出来 → 每个请求稳定等满 15 秒超时 → 按设计 §3.3 触发 `close(1001)` →
 * 重连 → 再超时。**那是比 REST 更糟的状态**。所以三个域的 WS 实现一律 `TODO()`，
 * 由 P2 在桥侧 P0 落地后填（见各方法注释里的依赖）。
 *
 * 这一条已作为「设计与桥侧现状的冲突」上报给 lead，不自行改设计。
 */
internal interface SessionPort {
    /** 会话列表。WS 帧 `sessions`（桥 `ws-sessions.ts` 未回显 requestId，P2 依赖桥侧 P0）。 */
    suspend fun sessions(archived: Boolean = false): List<ChatSession>

    /** 历史事件与投影。WS 帧 `history`；**投影仍读 REST**（设计 §3.4，不迁）。 */
    suspend fun history(sessionId: String, beforeSeq: Int? = null, maxMessages: Int? = null): ChatHistory

    /** 新建空会话，返回 sessionId。WS 帧 `session-create` → 应答 `session-created`。 */
    suspend fun createSession(rootId: String, cwd: String = ""): String

    /** 重命名。WS 帧 `session-rename` → 应答 `session-renamed`。 */
    suspend fun renameSession(sessionId: String, title: String)

    /** 归档。WS 帧 `session-archive` → 应答 `session-archived`。 */
    suspend fun archiveSession(sessionId: String)

    /** 停止当前回合（不是删会话）。WS 帧 `session-cancel` → 应答 `session-cancelled`。 */
    suspend fun cancelSession(sessionId: String)

    /** 从某轮分叉，返回新 sessionId。WS 帧 `fork` → 应答 `fork`（`data.sessionId` 是**新**会话）。 */
    suspend fun forkSession(sessionId: String, atSeq: Int? = null): String
}

internal interface MessagePort {
    /** 发消息。WS 帧 `message` → 应答 `sent`；`parts` 形状与 REST 的 `PromptPart` 一致。 */
    suspend fun sendMessage(sessionId: String, parts: JsonArray, steer: Boolean = false)

    /**
     * 读附件字节。WS 帧 `attachment`。
     *
     * 注意超时档位：附件走 [MobileWsTransport.FILE_TIMEOUT_MS]（120s），不是默认 15s ——
     * 大图在弱网下 15 秒必超时，而超时按设计会 `close(1001)` 把整条通道一起掀掉。
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

// ───────────────────────── WS 骨架实现（本阶段不填） ─────────────────────────

/**
 * `SessionPort` 的 WS 实现骨架。
 *
 * 每个方法都 `TODO()` 而**不是**「发出帧然后等」：等不到 requestId 回显的等待不是「实现不完整」，
 * 是「稳定超时 + 掀掉连接」。留 `TODO()` 让 P2 一上手就撞到这条依赖，而不是在真机上慢慢发现。
 *
 * 填之前必须先确认桥侧 `ws-sessions.ts` 已对 `sessions`/`history`/`session-create` 等回显
 * `requestId`（对照 `ws-goals.ts:258-262` 的 `echoRequestId` 写法）。
 */
internal class WsSessionPort(private val transport: MobileWsTransport) : SessionPort {

    override suspend fun sessions(archived: Boolean): List<ChatSession> =
        TODO("P2：依赖桥侧 ws-sessions.ts 回显 requestId（当前 0 命中），否则每个请求稳定超时 15s")

    override suspend fun history(sessionId: String, beforeSeq: Int?, maxMessages: Int?): ChatHistory =
        TODO("P2：同上；投影字段仍从 REST history 读（设计 §3.4）")

    override suspend fun createSession(rootId: String, cwd: String): String =
        TODO("P2：帧 session-create → 应答 session-created.data.sessionId")

    override suspend fun renameSession(sessionId: String, title: String) =
        TODO("P2：帧 session-rename → 应答 session-renamed")

    override suspend fun archiveSession(sessionId: String) =
        TODO("P2：帧 session-archive → 应答 session-archived")

    override suspend fun cancelSession(sessionId: String) =
        TODO("P2：帧 session-cancel → 应答 session-cancelled")

    override suspend fun forkSession(sessionId: String, atSeq: Int?): String =
        TODO("P2：帧 fork → 应答 kind 也叫 fork，data.sessionId 是【新】会话 id")
}

/** `MessagePort` 的 WS 实现骨架。同上：桥侧 `ws-handler.ts` 的 `message` 应答 `sent` 也不带 requestId。 */
internal class WsMessagePort(private val transport: MobileWsTransport) : MessagePort {

    override suspend fun sendMessage(sessionId: String, parts: JsonArray, steer: Boolean) =
        TODO("P2：帧 message → 应答 sent；桥侧目前无 requestId 回显，需先补 P0")

    override suspend fun attachment(sessionId: String, attachmentId: String): ByteArray =
        TODO("P2：帧 attachment（120s 档），或维持 REST readAttachment")
}

/**
 * `EventPort` 的 WS 实现骨架。
 *
 * 这一域**不依赖 requestId**（`subscribe` 的应答 `subscribed` 本身就是无 requestId 的推送），
 * 所以它是三个里唯一可以真正先跑的 —— 但事件形状到 `WorkspaceEvent` 的映射需要 P2 定，
 * 本阶段仍留 `TODO()`，避免把「解析一半」的实现带进主干。
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

    /** 供 P2 接推送时用：当前注册的处理器（本阶段只存不调，`event` 帧仍走 `onPush`）。 */
    internal fun currentHandler(): ((WorkspaceEvent) -> Unit)? = handler

    private fun sessionFilter(sessionId: String?): JsonObject =
        if (sessionId == null) JsonObject(emptyMap()) else JsonObject(mapOf("sessionId" to kotlinx.serialization.json.JsonPrimitive(sessionId)))
}
