package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.debug.DiagLog
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `/ws/mobile` 的**请求-配对层**：把「发一帧、等一条能认出属于它的应答」这件事从
 * [MobileWsTransport] 里分出来（设计 §3.3）。
 *
 * ## 为什么不用 [MobileWsTransport.send]
 *
 * [MobileWsTransport.send] 的超时分支按设计 §3.3 `abandon(closeCode = 1001)` —— 它假设
 * 「等不到应答 = 这条连接已经半死」。那个假设对**协议保证会回显 requestId** 的帧成立，
 * 对下面这批帧不成立。
 *
 * ## 两条配对路径（双兼容，`AGENTS.md` §7 坑位「契约文档写着 requestId 回显，实测一半的帧不回显」）
 *
 * 桥侧 `docs/api/asyncapi.yaml:100-112` 明文承诺「请求帧带非空 `requestId` 时，服务端在响应帧的
 * `data.requestId` 逐字回显」，但实现是**逐个处理器手工做的**：`ws-goals.ts`（`echoRequestId`）、
 * `ws-files.ts`、`ws-team.ts`、`ws-schedule.ts`、`ws-speech.ts` 回显，而 `ws-handler.ts` 的
 * 基础帧（`sessions` / `history` / `message` / `attachment` / `workspaces`）与 `ws-sessions.ts`
 * 的会话域（`session-create` / `session-rename` / `session-archive` / `session-cancel` / `fork`）
 * **一个都没有**。根因是 `ws-handler.ts` 的 `sendFrame(webSocket, frame: {kind, data?})` 外层
 * 结构里根本没有 `requestId` 的位置。
 *
 * 于是这里按优先级走两条路：
 *
 * 1. **requestId 回显**（正确路径）：帧带回 `requestId` 且命中在途表 → 直接完成。桥侧补齐后
 *    自动走这条，**不需要客户端改任何东西**，也不靠版本号猜（能力位里根本没有「是否回显」这种位）。
 * 2. **按响应 kind 配对**（老桥兜底）：没有 `requestId` 的帧，按「我在等哪个 kind」+ 判别字段
 *    找在途请求。这条路上同 kind **必须串行**（见下）。
 *
 * ## 为什么没有 requestId 也能配对
 *
 * 桥是 `void this.handleMessage(...)` 并发处理的（`ws-handler.ts:411-414`，**不 await**），
 * 应答顺序不保证 FIFO —— 所以不能靠「谁先发谁先回」。但去掉顺序依赖以后，剩下的判据是
 * 「响应里有没有能唯一指认请求的字段」，逐个帧看：
 *
 * | 请求 | 应答 kind | 判别字段 |
 * |---|---|---|
 * | `sessions` | `sessions` | 无（`data.sessions`）→ 必须单飞 |
 * | `history` | `history` | `data.sessionId` |
 * | `session-create` | `session-created` | 无 → 必须单飞 |
 * | `session-rename` | `session-renamed` | `data.sessionId` |
 * | `session-archive` | `session-archived` | `data.sessionId` |
 * | `session-cancel` | `session-cancelled` | `data.sessionId` |
 * | `fork` | `fork` | 无（只回新 id）→ 必须单飞 |
 * | `message` | `sent` | `data.sessionId` + `data.accepted`（排除 `unsubscribe` 的 `sent`） |
 *
 * 判别字段齐的也要串行化 —— 不是怕配错，是怕**同一 sessionId 的两次 history 交错**（第一次
 * 翻页和第二次刷新会互相吃掉）。统一做法：**每个期望 kind 一把 [Mutex]**，同 kind 同时只有
 * 一条在飞。代价是这些域串行化，而它们全是低频 UI 动作（列表刷新、翻页、改名、归档、发一条
 * 消息），串行的等待远小于一次 REST 往返。
 *
 * ## `error` 帧怎么归属
 *
 * 桥的错误帧是 `{kind:'error', data:{code, message, requestType?, statusCode?}}`，**不带
 * requestId**。`requestType` 是**请求帧的 type**（如 `subscribe`），不是响应 kind。
 *
 * 有 `requestType` → 只在**该 type** 的在途里取最老的一条（同 kind 已串行，正常只有一条）。
 * 没有 `requestType` → **谁都不归属**，直接进事件出口。桥侧大部分 `reportError` 都不带
 * `requestType`（只有 `ws-commands.ts` / `ws-files.ts` 带），此时「猜一条失败掉」等于用别人的
 * 错误码打死一条无关请求，而且错误码看起来还有来源、极难诊断。认不出就不认：让它超时。
 *
 * ## 超时
 *
 * 两种配对**都有超时**，且**只失败本次请求、不掀连接**：超时在这里抛
 * [DshApiException]（code `WS_TIMEOUT`），连接留着 —— 因为「按 kind 配对等不到」最常见的
 * 原因不是连接坏了，而是老桥压根没有这条帧的应答。真正连接坏了，[MobileWsTransport] 自己的
 * `onFailure` / `onClosing` 会把状态置 [ChannelState.WsDown]，调用方随即走 REST。
 *
 * ## 前向兼容风险（写在这里，别当它不存在）
 *
 * 如果桥将来给基础帧补上 `requestId` 回显，帧会带 `requestId` 进 [onFrame] 的第一条分支 ——
 * 这是**期望行为**，会走正确路径。真正要小心的是反过来：如果桥补了回显但**只补了一半**
 * （比如 `sessions` 补了、`history` 没补），两条路径同时生效也没问题 —— 第一分支命中就走，
 * 没命中会继续落到 kind 分支，不会丢帧。
 */
// @patch
// id: ws-request-id-echo-asymmetry
// for: /ws/mobile 只有部分处理器回显 requestId —— 基础帧（sessions/history/message/attachment/workspaces）与会话域的 reportError 都不回显（dsh-remote-bridge src/host/ws-handler.ts 的 sendFrame 外层无 requestId 槽位，2026-10-29）
// why: asyncapi 契约承诺逐字回显、实现只做了一半；不按响应 kind 兜底配对，这些请求会永远等不到应答，每个都稳定等满 15 秒再按设计 §3.3 close(1001) 掀掉连接
// until: 桥侧统一回显落地且老版本桥全部淘汰（届时删掉 kind 兜底路径，只留 requestId 快路）
// test: WsRequestRouterTest
internal class WsRequestRouter(
    /** 发帧出口。返回 false 表示通道不可用或发送失败。 */
    private val sender: (JsonObject) -> Boolean,
    /** 超时（毫秒）。默认 [MobileWsTransport.DEFAULT_TIMEOUT_MS]。 */
    private val timeoutMillis: (requestType: String?) -> Long = { MobileWsTransport.DEFAULT_TIMEOUT_MS },
    /** `event` 帧的去处（不走配对）。 */
    private val onEvent: ((JsonObject) -> Unit)? = null,
) {

    /** 一条在途请求。 */
    private class Pending(
        val requestId: String,
        /** 请求帧的 `type`，供 `error.data.requestType` 反查。 */
        val requestType: String,
        /** 期望的响应 `kind`，退化路径的配对键。 */
        val expectedKind: String,
        /** 判别字段校验：同 kind 下再用它确认「这条应答确实是我要的」。 */
        val matcher: (JsonObject) -> Boolean,
        /** 入表序号，退化路径取最老的。 */
        val seq: Long,
        val deferred: CompletableDeferred<JsonObject>,
    )

    private val pendings = LinkedHashMap<String, Pending>()
    private val seq = AtomicLong(0)

    /**
     * 每个期望 kind 一把锁。
     *
     * 用 [ConcurrentHashMap] 而不是普通 map：`computeIfAbsent` 是原子的，避免两条协程各拿到
     * 一把不同的锁 —— 那等于没串行。
     */
    private val kindLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** 诊断用：当前在途请求数。 */
    internal fun pendingCount(): Int = synchronized(pendings) { pendings.size }

    /** 诊断用：当前在途请求的 kind 列表（按入表顺序）。 */
    internal fun pendingKinds(): List<String> = synchronized(pendings) { pendings.values.map { it.expectedKind } }

    /**
     * 发一帧并等它的应答。
     *
     * @param requestType 客户端帧 `type`（[WsClientFrame] 里的字面量）。
     * @param expectedKind 期望的响应 `kind`（[WsServerFrame] 里的字面量）。
     * @param payload 摊平到帧顶层的字段（`sessionId` / `title` / `archived` 等）。
     * @param matcher 同 kind 下的判别字段校验；没有判别字段的帧传默认值即可（靠串行化）。
     * @throws DshApiException 通道不可用（`WS_NOT_READY`）/ 发送失败（`WS_SEND_FAILED`）/
     *   服务端 error 帧（带服务端 code）/ 超时（`WS_TIMEOUT`）。
     */
    suspend fun request(
        requestType: String,
        expectedKind: String,
        payload: JsonObject = JsonObject(emptyMap()),
        matcher: (JsonObject) -> Boolean = { true },
    ): JsonObject {
        val lock = kindLocks.computeIfAbsent(expectedKind) { Mutex() }
        return lock.withLock {
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonObject>()
            val entry = Pending(
                requestId = requestId,
                requestType = requestType,
                expectedKind = expectedKind,
                matcher = matcher,
                seq = seq.incrementAndGet(),
                deferred = deferred,
            )
            // 先入表再发帧：桥的回帧可能比 send() 返回还早（同进程写 + 读线程回调）。
            synchronized(pendings) { pendings[requestId] = entry }
            try {
                // 注意：这里发出去的帧**带**顶层 requestId —— 桥补了回显就能立刻走上快路，
                // 没补也不影响（配对退回 kind 分支）。
                if (!sender(WsFrames.request(requestType, requestId, payload))) {
                    throw DshApiException(0, "WS_SEND_FAILED", "WS 帧发送失败（连接已关闭）：$requestType")
                }
                val timeout = timeoutMillis(requestType)
                withTimeout(timeout) { deferred.await() }
            } catch (cancelled: TimeoutCancellationException) {
                // 只失败本次请求，**不** close 连接（见类注释「超时」一节）。
                throw DshApiException(
                    408,
                    "WS_TIMEOUT",
                    "WS 请求超时（${timeoutMillis(requestType)}ms）：$requestType（期望应答 $expectedKind）",
                )
            } catch (io: IOException) {
                throw DshApiException(0, "WS_SEND_FAILED", "WS 帧发送失败：${io.message ?: requestType}")
            } finally {
                // 无论成功、失败还是超时，都在途表里不留痕（设计 §3.3「不许 pending 泄漏」）。
                synchronized(pendings) { pendings.remove(requestId) }
            }
        }
    }

    /**
     * 收到一条推送帧（即 [MobileWsTransport.onPush] 的入口 —— 没有 `requestId` 或
     * `requestId` 没命中在途表的帧）。
     *
     * 只做三件事：`event` 分流、`error` 归属、按 kind 配对。**不抛异常** —— 这是 OkHttp 的
     * 读线程，抛出去会让整条连接的回调链断掉。
     */
    fun onFrame(frame: JsonObject) {
        // 收到重复 / 无 kind 的脏帧直接丢：配对靠 kind，没 kind 就无从下手。
        val kind = WsFrames.kindOf(frame) ?: return

        // ── 路径 1：requestId 回显（正确路径）──
        val echoed = WsFrames.requestIdOf(frame)
        if (echoed != null) {
            val hit = synchronized(pendings) { pendings.remove(echoed) }
            if (hit != null) {
                DiagLog.i("WS", "配对：requestId 快路 ${hit.requestType} ← $kind")
                complete(hit, frame, kind)
                return
            }
            // 回显了但不在途：可能是迟到帧（已超时），也可能是桥只对部分帧回显时
            // 混进来的别的在途请求的帧。**不 return**，继续往下走 kind 分支 —— 否则
            // 「一半帧回显」的桥上，那些没回显的请求会永远等不到人认领。
        }

        // ── 路径 2：error 帧按 requestType 归属 ──
        //
        // 这里**不许**在没有可信依据时挑一条在途请求失败掉。桥侧 error 帧既不带 requestId，
        // 大多数 reportError 也不带 requestType（`ws-sessions.ts` / `ws-models.ts` / `ws-hitl.ts`
        // / `ws-settings.ts` / `ws-presets.ts` 都只有 `(error, fallbackMessage)`；只有
        // `ws-commands.ts` / `ws-files.ts` 多带一个 requestType）。缺 requestType 的 error 等价于
        // 「服务端说了句话，但没说对谁说」—— 客户端没有任何可信依据去猜，猜错就是把一条完全
        // 无关的请求用别人的错误码打死（例：`speech/busy` 打死 `sessions` 列表加载，用户看到
        // 「会话列表加载失败」，而真正该失败的那条还在等自己的超时；错误码看起来还有来源，
        // 诊断起来极难认）。
        //
        // 所以：**认不出就不认**。让它进事件出口（诊断日志能看见桥为什么生气），各请求各自
        // 超时 —— 超时是确定性错误，错配是不确定性错误，确定性永远更好。
        if (kind == WsServerFrame.ERROR) {
            val data = WsFrames.dataOf(frame)
            val requestType = (data["requestType"] as? JsonPrimitive)?.contentOrNull
            if (requestType.isNullOrEmpty()) {
                onEvent?.invoke(frame)
                return
            }
            val target = synchronized(pendings) {
                // 只在该 requestType 的在途里取最老的一条。**不**回退到全体 —— 见上。
                pendings.values
                    .filter { it.requestType == requestType }
                    .minByOrNull { it.seq }
            }
            if (target != null) {
                synchronized(pendings) { pendings.remove(target.requestId) }
                DiagLog.i("WS", "配对：error 帧按 requestType 归属 ${target.requestType}")
                complete(target, frame, kind)
                return
            }
            // requestType 有值但没有任何在途请求匹配它（已超时/已完成的迟到 error）：
            // 同样只进事件出口，不掉进 kind 分支误配。
            onEvent?.invoke(frame)
            return
        }

        // ── 路径 3：按响应 kind 配对（老桥兜底）──
        val target = synchronized(pendings) {
            pendings.values
                .filter { it.expectedKind == kind }
                .filter { runCatching { it.matcher(frame) }.getOrDefault(false) }
                .minByOrNull { it.seq }
        }
        if (target != null) {
            synchronized(pendings) { pendings.remove(target.requestId) }
            DiagLog.i("WS", "配对：按 kind 兜底 ${target.requestType} ← $kind（该桥未回显 requestId）")
            complete(target, frame, kind)
            return
        }

        // ── 都不匹配：这是纯推送（`event` / `pong` / `subscribed` / `sent`）──
        if (kind == WsServerFrame.EVENT || !knownReplyKind(kind)) {
            onEvent?.invoke(frame)
        }
        // 其余（`subscribed` / `pong` / 无人认领的 `sent`）：没有订阅者，静默丢弃。
        // 它们本来就不带需要落地的东西，打日志只会把 diag 刷满。
    }

    private fun complete(entry: Pending, frame: JsonObject, kind: String) {
        if (kind == WsServerFrame.ERROR) {
            val data = WsFrames.dataOf(frame)
            val code = (data["code"] as? JsonPrimitive)?.contentOrNull ?: "WS_ERROR"
            val message = (data["message"] as? JsonPrimitive)?.contentOrNull ?: "WS 服务端返回错误"
            entry.deferred.completeExceptionally(DshApiException(0, code, message))
        } else {
            entry.deferred.complete(frame)
        }
    }

    private companion object {
        /**
         * 这些 kind 是「应答」，不该被当成推送喂给事件出口。
         *
         * 白名单而不是黑名单：桥的 `ServerFrameKind` 联合约 90 项，将来新增的任何 kind
         * 默认都往事件出口走（宁可多一条日志，也别把推送静默吞掉）。
         */
        fun knownReplyKind(kind: String): Boolean = when (kind) {
            WsServerFrame.SESSIONS,
            WsServerFrame.HISTORY,
            WsServerFrame.SESSION_CREATED,
            WsServerFrame.SESSION_RENAMED,
            WsServerFrame.SESSION_ARCHIVED,
            WsServerFrame.SESSION_CANCELLED,
            WsServerFrame.FORK,
            WsServerFrame.SENT,
            WsServerFrame.SUBSCRIBED,
            WsServerFrame.PONG,
            WsServerFrame.HELLO,
            -> true
            else -> false
        }
    }
}
