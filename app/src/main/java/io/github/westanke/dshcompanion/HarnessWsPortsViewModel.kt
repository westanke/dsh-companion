package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.LiveChatState
import io.github.westanke.dshcompanion.data.SeqVerdict
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import io.github.westanke.dshcompanion.data.baselineBehind
import io.github.westanke.dshcompanion.data.chatDelta
import io.github.westanke.dshcompanion.data.chatTurnFailure
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.data.parseBridgeCapabilities
import io.github.westanke.dshcompanion.data.seqVerdict
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.ws.ChannelState
import io.github.westanke.dshcompanion.data.ws.EventPort
import io.github.westanke.dshcompanion.data.ws.MessagePort
import io.github.westanke.dshcompanion.data.ws.MobileWsTransport
import io.github.westanke.dshcompanion.data.ws.RestMessagePort
import io.github.westanke.dshcompanion.data.ws.RestSessionPort
import io.github.westanke.dshcompanion.data.ws.SessionPort
import io.github.westanke.dshcompanion.data.ws.WsEventPort
import io.github.westanke.dshcompanion.data.ws.WsFrames
import io.github.westanke.dshcompanion.data.ws.WsMessagePort
import io.github.westanke.dshcompanion.data.ws.WsRequestRouter
import io.github.westanke.dshcompanion.data.ws.WsServerFrame
import io.github.westanke.dshcompanion.data.ws.WsSessionPort
import java.io.Closeable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * P2 的**装配层**：把 [SessionPort] / [MessagePort] / [EventPort] 接到
 * [HarnessViewModel] 上，并管 [MobileWsTransport] 的连接生命周期。
 *
 * ## 为什么单独开文件
 *
 * `HarnessViewModel.kt` 已经 791 行，`AGENTS.md` §4.2 的预算是 800 行/文件。P2 要往 VM 里塞
 * 「WS 传输实例 + 状态观察 + 三套端口的装配 + 事件通道收敛」，一行不改都不可能塞进去。
 *
 * 本仓既有做法就是拆文件：`HarnessSchedulesViewModel.kt` / `HarnessSpeechViewModel.kt` /
 * `HarnessAttachmentsViewModel.kt` / `HarnessSessionsViewModel.kt` 全是
 * `internal fun HarnessViewModel.xxx()` 扩展。这里沿用同一种形状。
 *
 * 顺带解决第二个问题：`connectEvents` 的 listener 主体原本有 187 行嵌在 VM 里，
 * 现在原样搬成 [handleWorkspaceEvent]。**逐字搬迁、不改一个字符** —— 它的每一条分支
 * 都有注释说明为什么这么写，重排只会引入回归。
 *
 * ## 硬判据：退化等价
 *
 * [ChannelState] 不是 `WsReady` / `WsDegraded` 时：
 *
 * - [sessionPort] / [messagePort] 一律返回 REST 实现，与改造前调的是**同一批** `DshClient`
 *   方法（同一个 `withContext(Dispatchers.IO)` 包裹，同样的参数顺序）。
 * - [reconcileEventChannel] 走 [openRestEvents]，与改造前的 `api.events(...)` 完全一致。
 *
 * 也就是说 `RestOnly` / `WsDown` 下的代码路径**没有新增分支**，只是多了一次
 * [ChannelState.usesWs] 判断（它必然是 false，于是走原路）。
 */
/**
 * 事件 listener 的主体。REST `/api/v1/events` 与 WS `event` 帧**共用这一段**。
 *
 * 这段代码从 `HarnessViewModel.connectEvents` 的 lambda 里**逐字搬出**，一字未改 ——
 * 它每条分支都对应一个真实踩过的坑（见各段注释与 `docs/project/PITFALLS.md`）。
 * 搬家只是为了腾出 VM 的行数预算、并让两条通道共用同一套语义。
 */
internal fun HarnessViewModel.handleWorkspaceEvent(event: WorkspaceEvent) {
                val delta = event.chatDelta()
                if (delta != null) {
                    if (mutableState.value.selectedSessionId == delta.sessionId) {
                        update { copy(liveChat = (liveChat ?: LiveChatState(delta.sessionId, delta.turn, delta.step)).append(delta)) }

                        // 正文一律走历史那条路（页面只从历史渲染），所以每来一段增量
                        // 都要让历史跟上，否则流式文字要等消息落盘才出现。
                        //
                        // 以前这里**不**刷新：正文只能靠页面里那个独立的实时区显示，
                        // 而实时区换 step 就重置 —— 真机表现是「这一段说完，上一段没了」。
                        // scheduleChatRefresh 自带 400ms 静默去抖 + 单飞，token 再密也只捞一次。
                        // （旧实现是 1 秒轮询 + 每帧置位，token 流式期间等于每秒拉一次全量
                        //  history —— 那是 v2.2.29 修掉的耗电主因，详见该函数 KDoc。）
                        scheduleChatRefresh()
                    }
                } else if (event.type == "connection.ready") {
                    // 服务端连上就自报版本与能力位。读到就存：缺关键能力时界面要**明说**，
                    // 因为「卡片不弹」与「本来就没有待审批」在屏幕上完全一样（真踩过，见 PITFALLS）。
                    parseBridgeCapabilities(event.data)?.let { reported ->
                        update {
                            copy(
                                bridgeVersion = reported.bridgeVersion ?: bridgeVersion,
                                bridgeCapabilities = reported.capabilities,
                            )
                        }
                    }
                } else if (event.type.startsWith("chat.")) {
                    val sessionId = event.dataString("sessionId")
                    // ——— 断档检测（2026-10-17）———
                    //
                    // WS 是「订阅之后才收得到」，REST 历史是「发请求那一刻的快照」——
                    // 两者的时间差里发生的事件**谁都管不着**，除非客户端能自己发现「我漏了一段」。
                    // 判据、以及「为什么重复和倒退不算断档」，见 `data/ChatSeqMonitor.kt`。
                    //
                    // 只对**当前会话**计数：别的会话有自己的序号序列，混在一起必然误判。
                    val incomingSeq = event.dataInt("seq")
                    if (incomingSeq != null && sessionId == mutableState.value.selectedSessionId) {
                        if (seqVerdict(mutableState.value.lastEventSeq, incomingSeq) == SeqVerdict.Resync) {
                            // 重取历史。`scheduleChatRefresh` 自带 400ms 去抖 + 单飞，
                            // 连续跳号也只会合并成一次。
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                        }
                        update { copy(lastEventSeq = incomingSeq) }
                    }
                    when (event.type) {
                        // 订阅基线：服务端告诉我们「这条订阅从哪个持久 seq 开始接增量」。
                        // 本地历史落后于它 ⇒「拉历史」与「订阅」之间的窗口**真漏了**事件，重取一次。
                        "chat.session.snapshot" -> {
                            val cursor = event.dataInt("cursor")
                            if (cursor != null && sessionId != null) {
                                update { copy(baselineCursor = cursor) }
                                if (sessionId == mutableState.value.selectedSessionId) {
                                    val localMax = mutableState.value.history?.events?.maxOfOrNull { it.event.seq }
                                    if (baselineBehind(localMax, cursor)) {
                                        chatSessionRefreshPending = true
                                        scheduleChatRefresh()
                                    }
                                }
                            }
                        }
                        // 交付物（内核 `present` 工具）：卡片数据在**历史**里，收到这条就刷新一次，
                        // 新交付物立刻出现在会话文件卡片里。此前这个事件名客户端不认识 ——
                        // 数据一直在历史里原样透传，只是要等下一次消息完成才顺带刷到。
                        "chat.deliverables.presented" -> {
                            if (sessionId == mutableState.value.selectedSessionId) {
                                chatSessionRefreshPending = true
                                scheduleChatRefresh()
                            }
                        }
                        "chat.message.committed" -> {
                            if (sessionId != null && sessionId == mutableState.value.selectedSessionId) {
                                if (event.dataString("role") == "assistant") {
                                    val turn = event.dataInt("turn")
                                    val step = event.dataInt("step")
                                    if (turn != null && step != null) liveFinalizeTarget = LiveKey(sessionId, turn, step)
                                }
                                scheduleChatRefresh()
                            }
                        }
                        "chat.turn.start", "chat.turn.end", "chat.session.status" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                            // 「这一轮刚结束」= 会话已不在运行。
                            //
                            // 这是工具结果帧那条路的兜底：那帧只在提问还挂在前台时才会到，
                            // agent 若在超时后很快收尾，App 可能整轮都没接住。
                            // 在轮次结束的**那一刻**补拉一次，之后彻底停止 —— 不是轮询。
                            if (event.type == "chat.turn.end" && sessionId != null && !isSessionRunning(sessionId)) {
                                refreshPendingQuestions()
                            }
                            noteTurnBoundary(event, sessionId) // 截断提示，见同名文件
                        }
                        "chat.agent-preset.selected" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                            if (sessionId == mutableState.value.selectedSessionId) refreshSessionModels()
                        }
                        // 终局失败：限流 429、配额用尽、密钥无效、登出停止。
                        //
                        // 为什么必须在这里就地处理，而不是等 `chat.turn.end`：
                        // 插件为失败发的**就是**这一帧，`chat.turn.end` 那一帧在失败时
                        // 不再发（一个失败只有一帧）。而客户端原来只认 turn.end，
                        // 于是「模型早就失败了」与「还在跑」在界面上完全一样 ——
                        // 表现就是转圈等到天荒地老。
                        //
                        // 三件事：① 本地立即标记不在运行；② 全局错误通道；③ 拉历史收口。
                        //
                        // 第 1 步不能省：服务端标 not-running 与这帧到达之间有几百毫秒，
                        // 那时「停止」还是亮的、占位符还写「运行中…」。本地先置位，等 GET 再盖
                        // 权威数据盖一次，两头都对。
                        "chat.turn.failed" -> {
                            val failure = event.chatTurnFailure()
                            if (failure != null) {
                                if (sessionId != null) {
                                    // 注意 update 的 receiver 就是 HarnessState，所以这里直接用
                                    // 属性而不是 `it.` / `state.` —— 写成显式参数名会遮蔽 receiver，
                                    // 编译直接报「Unresolved reference」。
                                    update {
                                        copy(
                                            sessions = markSessionStopped(sessions, sessionId),
                                            liveChat = if (selectedSessionId == sessionId) null else liveChat,
                                            busySendMode = if (selectedSessionId == sessionId) BusySendMode.QUEUE else busySendMode,
                                        )
                                    }
                                }
                                if (sessionId == mutableState.value.selectedSessionId) {
                                    // 流式缓冲清掉：失败之后留着半截文字，用户会以为
                                    // 「它还在说，只是慢」。历史拉回来会带上完整失败行。
                                    // 出错文案跟随界面语言：先按错误码本地化，命不中才用内核原文
                                    //（多半是英文，但至少是信息，也方便贴给开发者）。
                                    val localized = localizedErrorRes(failure.code)
                                    notify(
                                        when {
                                            localized == null -> failure.message.ifBlank { appString(failure.titleRes) }
                                            localized == R.string.err_request_generic -> appString(localized, failure.code.orEmpty())
                                            else -> appString(localized)
                                        },
                                    )
                                    // 挂起的提问卡片也要撤：这一轮已经结束了，
                                    // 卡片继续挂着会让人以为问题还有效。与 WebView 页同口径。
                                    refreshPendingQuestions()
                                }
                                chatSessionRefreshPending = true
                                scheduleChatRefresh()
                            }
                        }
                        "chat.approval.requested", "chat.approval.resolved" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                            if (sessionId == mutableState.value.selectedSessionId) refreshPendingApprovals()
                        }
                        else -> if (sessionId == mutableState.value.selectedSessionId) scheduleChatRefresh()
                    }
                    // 待答问题的刷新靠事件驱动，**不引定时器、不轮询**：一条相关事件一次 GET。
                    //
                    // `chat.session.event` 是插件给所有「非特殊」会话事件的统一降级形态
                    //（chat-adapter.ts 的 normalizeDshEvent 末段），`tool/result` 走的正是它。
                    // 提问超时的那一刻，内核写出一条 tool/result，App 因此收到一帧 ——
                    // 事件已经到了，只是它没有内容、只有 eventType 与 seq。
                    // 这里按eventType 精确挑出工具结果，不做「来一帧就拉一次」：
                    // 一个工具调用会带好几帧无关事件，全拉就是自己造轮询。
                    if (event.type in QUESTION_REFRESH_EVENTS || isToolResultFrame(event.type, event.data)) {
                        refreshPendingQuestions()
                    }
                    // 插件为问答推的**专属帧**（经 normalizeDshEvent 末段兜成 `chat.<payloadType>`）。
                    //
                    // 有了它，手机端不必等「tool/result 帧」或「轮次结束」这两个间接信号：
                    // 提问一落地就能拿到通知；电脑端答完之后卡片也能**立刻**消失。
                    when (event.type) {
                        "chat.userQuestions/requested" -> refreshPendingQuestions()
                        "chat.userQuestions/answered",
                        "chat.userQuestions/dismissed" -> {
                            // 这两帧是「这道题已经不用答了」的确定性事实 ——
                            // 直接摘掉，不必等一次网络往返（等 GET 只会让卡片多挂一会儿）。
                            val callId = event.dataString("callId")
                            if (callId != null) {
                                update { copy(questions = removeQuestion(questions, callId)) }
                            }
                        }
                    }
                }
                // 去抖，不是直接 loadDirectory()：一次批量改写会推来几十帧，
                // 逐帧请求就是自己造的轮询风暴（见 directoryDebouncer 的说明）。
                if (event.type.startsWith("file.")) directoryDebouncer.trigger()
}

/**
 * 事件通道的连接状态回调。与 [handleWorkspaceEvent] 一样，逐字搬自原 `connectEvents`。
 *
 * 注意它管的是「事件流连上了」，不是「主机连上了」—— 两者会分别置位（PITFALLS
 * 「连接状态位是『曾经连上过』，不是『此刻还连着』」）。
 *
 * **为什么 `connected == false` 在 WS 可用时要丢弃**（真机 2026-10-29 复现）：
 * `DshClient.EventSubscription.close()` 会**无条件**回调 `connection(false)`
 * （`data/DshClient.kt:742`）。而 [reconcileEventChannel] 在 WS 抢到事件流时做的第一件事
 * 就是关掉那条 REST 流 —— 于是刚置上的 `eventsConnected = true` 会被这次关闭
 * 立刻改回 false，顶栏一直显示「已断开」，而推送其实好好地走在 WS 上。
 * 判据：**同一时刻只有一个事件通道**，谁在服役听谁的；WS 在服役时 REST 的告别不算数。
 */
internal fun HarnessViewModel.handleEventsConnection(connected: Boolean) {
    if (!connected && wsEventsUsable()) {
        DiagLog.i("WS", "忽略 REST 流的断开回调：WS 正接管事件通道")
        return
    }
    DiagLog.i("WS", "事件连状态 → $connected")
    update { copy(eventsConnected = connected) }
    if (connected && mutableState.value.selectedSessionId != null) {
        scheduleChatRefresh()
        refreshPendingApprovals()
        // 断线期间问出来的问题要靠这一下补回来。
        refreshPendingQuestions()
        refreshCommands()
    }
}

/**
 * 打开老的 REST 事件流（`GET /api/v1/events`，SSE）。
 *
 * **这是降级路径的唯一实现**：`ChannelState` 不可用 WS 时走它，行为与 P2 改造前完全一致。
 */
internal fun HarnessViewModel.openRestEvents(api: DshClient): Closeable =
    api.events(
        listener = { event -> handleWorkspaceEvent(event) },
        connection = { connected -> handleEventsConnection(connected) },
    )

// ───────────────────────── WS 传输生命周期 ─────────────────────────

/**
 * 建立（或复用）本连接的 WS 传输实例，并让它开始连。
 *
 * 幂等：已有实例就直接返回 —— `connectEvents` 会被 `resumeEvents()` 反复调用，
 * 每次新建一个 transport 会让旧的那条永远留着、退避重连也继续跑（连接泄漏）。
 *
 * token 只能从 `active.host.credential.secret` 取：`DshClient` 的 `token` 是 private
 * 且没有 getter（这是好事，别为了图方便给它加一个）。
 */
internal fun HarnessViewModel.connectWsTransport(api: DshClient) {
    val existing = wsTransport
    if (existing != null) {
        DiagLog.i("WS", "复用已有传输，state=${existing.state.value}")
        existing.start()
        reconcileEventChannel()
        return
    }
    val connection = active ?: return
    val token = connection.host.credential?.secret
    if (token.isNullOrBlank()) {
        // 没有令牌就不建 WS：桥会以 4001 拒绝，客户端按设计进 RestOnly。
        // 与其先连一次再失败，不如直接留在 REST —— 行为与改造前一致。
        DiagLog.w("WS", "无设备令牌，不建 WS 通道，全量走 REST（与改造前一致）")
        return
    }
    // ── 装配顺序是有讲究的，别随手调换 ────────────────────────────────
    //
    // 路由器必须**先于** transport 建好，并且 transport 的 `onPush` 只能捕获这个
    // **局部** `router`（不能读 `wsRouter` 那个可变字段）：
    //
    // 桥在 upgrade 之后立刻推 `hello`，而 `hello` 之后紧跟着就可能来 `event` 帧。
    // 若 `onPush` 写成 `{ frame -> wsRouter?.onFrame(frame) }`，那么在「transport 已
    // start、字段还没赋值」这段窗口里到达的帧会读到 null 被**静默丢弃** —— 表现为
    // 「刚连上那几秒的推送丢了」，且日志里什么都没有。
    //
    // 反过来路由器的 `sender` 也不能直接捕获 transport（那会循环依赖），所以用一个
    // 局部可空引用过渡：router 建好时它还是 null，transport 建好后立刻赋上，而
    // `sender` 只在用户**发请求**时才被调用（远晚于装配完成），不会读到 null。
    var transportRef: MobileWsTransport? = null
    val router = WsRequestRouter(
        sender = { frame -> transportRef?.sendNoReply(frame) ?: false },
        onEvent = { frame -> onWsUnmatchedFrame(frame) },
    )
    val transport = MobileWsTransport(
        endpoint = connection.endpoint.baseUrl,
        token = token,
        onPush = { frame -> router.onFrame(frame) },
        onStateChange = { reconcileEventChannel() },
    )
    transportRef = transport
    wsRouter = router
    wsTransport = transport
    wsEventPort = WsEventPort(transport)
    wsEventPort?.onEvent { event -> handleWorkspaceEvent(event) }
    ports = PortSet(
        session = AdaptiveSessionPort(
            ws = WsSessionPort(router, api.json),
            rest = RestSessionPort { client ?: api },
            state = { transport.state.value },
        ),
        message = AdaptiveMessagePort(
            ws = WsMessagePort(router, api.json) { localeTag() },
            rest = RestMessagePort { client ?: api },
            state = { transport.state.value },
        ),
        events = wsEventPort!!,
    )
    DiagLog.i("WS", "建立 WS 传输：endpoint=${connection.endpoint.baseUrl} 先 REST 后 WS，等 hello 判决")
    transport.start()
    reconcileEventChannel()
}

/**
 * WS 通道此刻能不能承载事件流。
 *
 * `WsDegraded` 也算能用（[ChannelState.usesWs] 为 true）—— 它只表示「某些能力位缺失」，
 * 事件推送不受影响；真扛不住时 transport 自己会降到 `WsDown`。
 */
internal fun HarnessViewModel.wsEventsUsable(): Boolean = wsTransport?.state?.value?.usesWs == true

/** 订阅 WS 事件。`sessionId` 为空时**不订阅**（桥要求非空，空会回 MISSING_PARAMS）。 */
internal fun HarnessViewModel.subscribeWsEvents() {
    val port = wsEventPort ?: return
    val sessionId = mutableState.value.selectedSessionId ?: return
    if (port.subscribe(sessionId)) wsSubscribedSessionId = sessionId
}

/** 退订 WS 事件。 */
internal fun HarnessViewModel.unsubscribeWsEvents() {
    val subscribed = wsSubscribedSessionId ?: return
    wsEventPort?.unsubscribe(subscribed)
    wsSubscribedSessionId = null
}

/**
 * 收敛事件通道：**WS 优先，REST 兜底**。
 *
 * 会在三个时机被调用：连接建立后、`ChannelState` 每次变化、`resumeEvents()`。
 *
 * 关键约束 —— `eventsPaused` 时什么都不做：熄屏暂停是用户可见的状态，
 * 后台悄悄开一条 SSE 会把它推翻。
 */
internal fun HarnessViewModel.reconcileEventChannel() {
    if (eventsPaused) return
    val api = client ?: return
    if (wsEventsUsable()) {
        // WS 能用了：关掉 REST 流（否则同一批事件会到两次），改订阅 WS。
        if (eventSubscription != null) {
            eventSubscription?.close()
            eventSubscription = null
            DiagLog.i("WS", "事件通道切到 WS：已关闭 REST /api/v1/events 流")
        }
        wsEventPort?.onEvent { event -> handleWorkspaceEvent(event) }
        val selected = mutableState.value.selectedSessionId
        if (selected != null && selected != wsSubscribedSessionId) {
            unsubscribeWsEvents()
            subscribeWsEvents()
        }
        // WS 事件流没有「连接状态」回调（它天然与 transport 同生共死），
        // 这里补一次置位，让界面不再显示「事件未连接」。
        handleEventsConnection(true)
    } else {
        unsubscribeWsEvents()
        if (eventSubscription == null) {
            eventSubscription = openRestEvents(api)
            DiagLog.i("WS", "事件通道留在 REST：state=${wsTransport?.state?.value ?: "无传输"}")
        }
    }
}

/**
 * 把一个 WS 帧交给诊断/事件出口。
 *
 * 能走到这里说明路由器**认不出**它属于哪条在途请求：`event` 帧是纯推送（正常），
 * `error` 帧则是「服务端报了错但没说对谁说」（桥侧大部分 `reportError` 不带
 * `requestType`）。后者**绝不能**拿去失败任意一条在途请求 —— 详见 `WsRequestRouter` 注释。
 */
internal fun HarnessViewModel.onWsUnmatchedFrame(frame: JsonObject) {
    val kind = WsFrames.kindOf(frame) ?: return
    if (kind == WsServerFrame.EVENT) {
        val data = WsFrames.dataOf(frame)
        val event = runCatching { WsFrames.decodeEvent(data) }.getOrNull() ?: return
        handleWorkspaceEvent(event)
        return
    }
    if (kind == WsServerFrame.ERROR) {
        val data = WsFrames.dataOf(frame)
        val code = (data["code"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        DiagLog.w("WS", "收到无法归属的 error 帧（无 requestId / 无 requestType）：${code ?: "unknown"}")
    }
}

/** 关闭 WS 传输。连接断开、`onCleared` 时调用。 */
internal fun HarnessViewModel.closeWsTransport() {
    wsSubscribedSessionId = null
    wsEventPort = null
    wsRouter = null
    ports = null
    wsTransport?.close()
    wsTransport = null
}

// ───────────────────────── 自适应端口 ─────────────────────────

/**
 * 按 [ChannelState] 选通道的 [SessionPort]。
 *
 * **退化时不走任何新分支**：`!usesWs` 直接 `return rest.xxx(...)`，与改造前调的完全是同一个
 * `DshClient` 方法。
 *
 * WS 失败时是否回落 REST，按「重放安不安全」分两类：
 *
 * - **读**（[sessions] / [history]）：任何 WS 失败都回落。读是幂等的，多读一次无害。
 * - **写**（[renameSession] / [archiveSession] / [cancelSession] / [forkSession]）：只在
 *   **帧根本没发出去**时回落（`WS_NOT_READY` / `WS_SEND_FAILED`）。`WS_TIMEOUT` 不回落 ——
 *   超时意味着「不知道服务端做没做」，重试可能变成改两次名、归档两次、分叉出两个会话。
 *   宁可把超时报给用户，也不制造服务端看不见的重复动作。
 */
internal class AdaptiveSessionPort(
    private val ws: SessionPort,
    private val rest: SessionPort,
    private val state: () -> ChannelState,
) : SessionPort {

    override suspend fun sessions(archived: Boolean): List<ChatSession> {
        // 归档列表恒走 REST：设计 §4.1 —— `session-archives` 在桥的 kind 联合里
        // 但**没有任何生产者**，`GET /chat/sessions?archived=true` 才是唯一入口。
        if (archived) return rest.sessions(true)
        return readViaWs({ ws.sessions(false) }) { rest.sessions(false) }
    }

    override suspend fun history(sessionId: String, beforeSeq: Int?, maxMessages: Int?): ChatHistory {
        // 首屏（beforeSeq == null）恒走 REST：WS 的 history 帧会把 projections 丢掉
        //（桥 handleHistory 只解构 {events, hasMore}），而待办清单/会话统计/WebView 页
        // 全靠它。丢了就是整块面板变空 —— 比省一条连接重要得多。
        if (beforeSeq == null) return rest.history(sessionId, null, maxMessages)
        return readViaWs({ ws.history(sessionId, beforeSeq, maxMessages) }) {
            rest.history(sessionId, beforeSeq, maxMessages)
        }
    }

    override suspend fun createSession(workspaceId: String, agentPreset: String?, reuseBlank: Boolean?): String =
        // WS 的 session-create 只认 rootId+cwd 且写死 reuseBlank:false，带不了 agentPreset。
        rest.createSession(workspaceId, agentPreset, reuseBlank)

    override suspend fun renameSession(sessionId: String, title: String) {
        writeViaWs({ ws.renameSession(sessionId, title) }) { rest.renameSession(sessionId, title) }
    }

    override suspend fun archiveSession(sessionId: String) {
        writeViaWs({ ws.archiveSession(sessionId) }) { rest.archiveSession(sessionId) }
    }

    override suspend fun cancelSession(sessionId: String) {
        writeViaWs({ ws.cancelSession(sessionId) }) { rest.cancelSession(sessionId) }
    }

    override suspend fun forkSession(sessionId: String, atSeq: Int?): String =
        writeViaWs({ ws.forkSession(sessionId, atSeq) }) { rest.forkSession(sessionId, atSeq) }

    private suspend fun <T> readViaWs(viaWs: suspend () -> T, viaRest: suspend () -> T): T {
        if (!state().usesWs) return viaRest()
        return try {
            viaWs()
        } catch (failure: DshApiException) {
            if (isWsTransportFailure(failure)) viaRest() else throw failure
        }
    }

    private suspend fun <T> writeViaWs(viaWs: suspend () -> T, viaRest: suspend () -> T): T {
        if (!state().usesWs) return viaRest()
        return try {
            viaWs()
        } catch (failure: DshApiException) {
            if (isFrameNeverSent(failure)) viaRest() else throw failure
        }
    }
}

/** 按 [ChannelState] 选通道的 [MessagePort]。回落策略同 [AdaptiveSessionPort]（写操作）。 */
internal class AdaptiveMessagePort(
    private val ws: MessagePort,
    private val rest: MessagePort,
    private val state: () -> ChannelState,
) : MessagePort {

    override suspend fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean) {
        if (!state().usesWs) return rest.sendMessage(sessionId, parts, steer)
        try {
            ws.sendMessage(sessionId, parts, steer)
        } catch (failure: DshApiException) {
            // 只有「帧根本没发出去」才回落：发消息绝不幂等，超时后重发会重复提问。
            if (isFrameNeverSent(failure)) rest.sendMessage(sessionId, parts, steer) else throw failure
        }
    }

    override suspend fun attachment(sessionId: String, attachmentId: String): ByteArray =
        // 桥的 attachment 帧是上传语义，读字节恒走 REST。
        rest.attachment(sessionId, attachmentId)
}

/** WS 帧压根没离开客户端：这种情况回落 REST 绝不会造成重复副作用。 */
private fun isFrameNeverSent(failure: DshApiException): Boolean =
    failure.code == "WS_NOT_READY" || failure.code == "WS_SEND_FAILED"

/** WS 传输层失败（而不是服务端业务错误）：读操作遇到它就该回落 REST。 */
private fun isWsTransportFailure(failure: DshApiException): Boolean =
    isFrameNeverSent(failure) || failure.code == "WS_TIMEOUT" || failure.code == "WS_BAD_FRAME" ||
        failure.code == "WS_ERROR"

/** 一次连接内的三套端口。 */
internal class PortSet(val session: SessionPort, val message: MessagePort, val events: EventPort)

/**
 * 取会话端口。
 *
 * 传输还没建（冷启动、未连接、无令牌）时直接给 REST 实现 —— 与改造前完全一致。
 */
internal fun HarnessViewModel.sessionPort(): SessionPort =
    ports?.session ?: RestSessionPort { requireApi() }

/**
 * 取会话端口，并**显式指定**兜底用的 client。
 *
 * 只给 [loadSnapshot] 用，因为它是唯一一个「`active` 还没赋值就要发请求」的调用点：
 * `connectHostInternal` 的顺序是 `loadSnapshot(connection.client, ...)` 在前、`active = connection`
 * 在后（`HarnessConnectionViewModel.kt:231` vs `:233`）。而 [requireApi] 读的是
 * `active?.client`，冷启动时那是 null —— 走无参版本会抛 `NO_CONNECTION`，连接直接失败。
 * 真机冷启动必现，单测里没人实例化 ViewModel 所以测不出来。
 */
internal fun HarnessViewModel.sessionPort(api: DshClient): SessionPort =
    ports?.session ?: RestSessionPort { api }

/** 取消息端口。理由同 [sessionPort]。 */
internal fun HarnessViewModel.messagePort(): MessagePort =
    ports?.message ?: RestMessagePort { requireApi() }

/** 取消息端口，显式指定兜底 client。理由同 [sessionPort] 的 `(api)` 重载。 */
internal fun HarnessViewModel.messagePort(api: DshClient): MessagePort =
    ports?.message ?: RestMessagePort { api }

/**
 * 拿当前 `DshClient`，没有就抛。
 *
 * 调用点都在 `withClient { api -> ... }` 或 `client ?: return` 之后，
 * 走到这里还缺 client 只可能是中途断连 —— 抛异常比静默走错通道好。
 */
private fun HarnessViewModel.requireApi(): DshClient =
    client ?: throw DshApiException(0, "NO_CONNECTION", "无活动连接")

/**
 * 界面语言二值化成桥认的 handoff locale（`HandoffLocale = 'zh' | 'en'`）。
 *
 * REST 路径由 `DshClient.authenticated()` 自动带 `Accept-Language`；WS 帧不经过那里，
 * 必须显式传，否则桥永远用默认值 `zh`，英文界面下会话里的中文/英文判定会与 REST 不一致。
 */
internal fun HarnessViewModel.localeTag(): String? =
    if (java.util.Locale.getDefault().language == "en") "en" else "zh"
