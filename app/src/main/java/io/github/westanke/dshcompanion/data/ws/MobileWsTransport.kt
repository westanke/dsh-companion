package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.debug.DiagLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * `/ws/mobile` 传输层（`docs/project/WS-FIRST-DESIGN.md` §3.1/§3.3）。
 *
 * 职责只有四件：**建连、保活/重连、帧收发、requestId 关联**。它不认识任何业务模型 ——
 * 「`sessions` 帧回来怎么变成 `ChatSession`」是 P2 的 Port 层的事。这样切的原因是
 * 通道差异（超时、重连、降级）只需要在这一个文件里测一遍，业务域各自的测试用假 Port 即可
 * （设计 §6 最后一条：测试翻倍 → 通道差异只在一处测）。
 *
 * ## 三条硬约束（别改）
 *
 * 1. **不新建 `OkHttpClient`**：复用 [DshClient.defaultHttpClient]。`ArchitectureGuardTest`
 *    的 `NETWORK_BOUNDARY_ALLOWLIST` 只放行 `data/DshClient.kt` 与 `web/ChatWebBridge.kt`，
 *    在这里 `OkHttpClient(...)` 会让 `:app:testDebugUnitTest` 直接红（`AGENTS.md` §4.1）。
 *    复用同一实例还意味着连接池、超时、ping 策略全 App 只有一处定义。
 * 2. **令牌不进 URL**：只走 `Sec-WebSocket-Protocol`（[WsSubprotocol]）。桥对 `?token=` 是
 *    直接关连接（`ws-handler.ts:262-267`），而且 query 会进访问日志与反代日志。
 * 3. **重连不重放**：断连期间在途的请求一律失败交给 REST 兜底，**不重发**（设计 §3.2 第 4 条）。
 *    重放比丢更危险 —— 写操作重放会真的执行两次，而 REST 侧本来就有幂等兜底。
 *
 * ## 线程模型
 *
 * 所有回调都在 OkHttp 自己的读线程上；`pending` 用 [ConcurrentHashMap]，`socket` 用
 * `@Volatile`。重连调度在独立的 [reconnectScope]（`Dispatchers.IO`）上，不在回调里 `sleep` ——
 * OkHttp 的读线程被阻塞会让同一条连接上的所有帧停摆。
 */
internal class MobileWsTransport(
    endpoint: String,
    private val token: String,
    private val http: OkHttpClient = DshClient.defaultHttpClient,
    /** 本阶段必需的能力位；默认值即 `WsFrames.REQUIRED_CAPABILITIES_FOR_WS`，测试可缩到空集。 */
    private val requiredCapabilities: Set<String> = REQUIRED_CAPABILITIES_FOR_WS,
    /** 无 `requestId` 的帧（hello 之外）走这里：`event` / `pong` / 未来的 `question-requested`。 */
    private val onPush: ((JsonObject) -> Unit)? = null,
    /** 状态迁移观察点（诊断日志用）；状态本身仍以 [state] 为准。 */
    private val onStateChange: ((ChannelState) -> Unit)? = null,
    /** 退避时长（毫秒）。抽成参数是为了让重连用例不必真的等 1/2/4 秒。 */
    private val backoffMillis: (attempt: Int) -> Long = { reconnectDelayMillis(it) },
    /** 普通请求超时；默认 [DEFAULT_TIMEOUT_MS]，测试注入小值以免真的等 15 秒。 */
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    /** 文件类请求超时；默认 [FILE_TIMEOUT_MS]。 */
    private val fileTimeoutMillis: Long = FILE_TIMEOUT_MS,
    /**
     * 在途请求被丢弃时的通知（issue #12）。
     *
     * 本类只清**自己**那张 `pending` 表（[send] 用的），而真正跑业务请求的是
     * [WsRequestRouter]，它另有一张在途表，本类看不见。没有这个回调时，断线后
     * 路由器的请求无人唤醒，只能等满超时 —— 用户看到的就是那句与真相无关的
     * 「WS 请求超时（15000ms）」。
     *
     * 参数是 (错误码, 人话原因)。
     */
    private val onPendingDropped: ((code: String, message: String) -> Unit)? = null,
    /**
     * 应用层心跳间隔；`<= 0` 表示关掉心跳。
     *
     * 桥的 `ping` 处理器（`ws-handler.ts:702-707`）回一条不带 `requestId` 的 `pong`，
     * 客户端这边落在 [onPush] 里被丢掉 —— 它的全部价值就是**刷新 [lastInboundAtMillis]**。
     * 抽成参数是让单测不必真等 20 秒（既有测试同样注入小超时）。
     */
    private val heartbeatIntervalMillis: Long = HEARTBEAT_INTERVAL_MS,
    /**
     * 多久没收到任何入站帧就判定「这条连接不可信」。
     *
     * 取心跳的 2 倍加余量：一拍丢包不算死（移动网抖动很常见），连续两拍没动静、
     * 且此前已经在收东西，才说明这条连接不是「安静」而是「死了」。
     */
    private val livenessTimeoutMillis: Long = livenessTimeoutFor(HEARTBEAT_INTERVAL_MS),
    /** 回前台探活的等待窗口；抽成参数是为了让单测不必真等 3 秒。 */
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MS,
    /** 时钟（毫秒）。抽成参数是为了让「冻结 20 秒」这种场景在单测里瞬时发生。 */
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /**
     * WS 的 URL。
     *
     * **注意路径不在 `/api/v1` 下**：桥是拿 `url.pathname === '/ws/mobile'` 逐字比较的
     * （`server.ts:456-464`），而 [DshClient.endpoint] 规范化后带 `/api/v1` 后缀。
     * 直接 `base.addPathSegment("ws")` 会拼成 `/api/v1/ws/mobile`，服务端认不出这条 upgrade，
     * 表现是「握手 404 → 被判 RestOnly → 永远不重试」。所以这里**只取 origin**。
     */
    private val wsUrl: String = run {
        val base = DshClient.normalizeEndpoint(endpoint).toHttpUrlOrNull()
            ?: throw DshApiException(0, "INVALID_ENDPOINT", "WS endpoint is not a valid URL: $endpoint")
        base.newBuilder().encodedPath(WS_PATH).query(null).build().toString()
    }

    private val _state = MutableStateFlow<ChannelState>(ChannelState.WsDown("尚未建立连接"))

    /** 降级状态机（唯一真理源）。初始 [ChannelState.WsDown]：还没连上，一切走 REST。 */
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    @Volatile private var socket: WebSocket? = null

    /**
     * 连接代号。每次 [connect] 自增；每条 [Listener] 记住自己那一代的号。
     *
     * 为什么需要：超时/主动放弃时我们会**立刻**开新连接，而旧连接的 `onClosed` 往往几毫秒后
     * 才到。没有代号的话，旧回调会把 [socket] 清成 null（把新连接弄丢）、并再排一次重连
     * （重连风暴）。这是 WS 客户端最典型的一类竞态。
     */
    private val generation = AtomicInteger(0)
    @Volatile private var helloSeen: Boolean = false
    @Volatile private var closed: Boolean = false
    private var attempt: Int = 0
    private var reconnectJob: Job? = null

    /**
     * 最后一次从这条连接上**收到**东西的时刻（[clock] 的口径）。
     *
     * 这是全类唯一的「时间维度」事实，解决的是 issue #16 那个真机现场：进程被系统冻结
     * 二十秒后回来，socket 其实已经死了，但 [state] 还停在 `WsReady` —— 因为状态机只在
     * **收帧**时被动更新，它不知道「已经很久没收到任何东西」。
     *
     * 与 OkHttp 的 `pingInterval(20s)`（`DshClient.kt:683`）不重复：那是 OkHttp 自己的
     * 定时任务，**进程被冻结时它也停摆**，回来之后不会补一次「这段时间你收到了什么」，
     * 所以它检测不到冻结造成的半开连接；而这里的时间戳能，因为它记录的是「真实发生过
     * 入站帧的绝对时刻」，冻结多久都算得出来。
     *
     * 值为 [NO_INBOUND_YET] 表示「这条连接还没收到过任何东西」—— 此时不做陈旧判定
     * （否则刚连上、hello 还没到就会被判死），发帧本来就另有 `WS_NOT_READY` 拦着。
     */
    @Volatile private var lastInboundAtMillis: Long = NO_INBOUND_YET

    /** 应用层心跳的协程（[heartbeatIntervalMillis] 一拍）。 */
    private var heartbeatJob: Job? = null

    // ───────────────────────── 生命周期 ─────────────────────────

    /** 开始建连。重复调用无副作用（已连上时直接返回）。 */
    fun start() {
        if (closed) return
        if (socket != null) return
        connect()
    }

    /**
     * 主动关闭：不再重连，在途请求全部失败。
     *
     * 状态落到 [ChannelState.WsDown]（而不是 `RestOnly`）：`RestOnly` 的语义是
     * 「这台桥没有 `/ws/mobile`」，是**对服务端能力**的判断；主动关闭是本端行为，
     * 两者混用会让诊断日志把「用户退出」误读成「老桥」。
     */
    fun close() {
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        lastInboundAtMillis = NO_INBOUND_YET
        socket?.close(1000, "App closed")
        socket = null
        failAllPending("WS_CLOSED", "WS 通道已关闭")
        transition(ChannelState.WsDown("已主动关闭"))
        reconnectScope.cancel()
    }

    private fun connect() {
        if (closed) return
        val request = Request.Builder()
            .url(wsUrl)
            // 子协议清单：首项必须是 dsh-mobile-v1（桥拿 webSocket.protocol 逐字比较），
            // 第二项才是凭据。令牌只在头里，URL 里一个字都不带。
            .header("Sec-WebSocket-Protocol", WsSubprotocol.forToken(token).joinToString(", "))
            .build()
        val gen = generation.incrementAndGet()
        socket = http.newWebSocket(request, Listener(gen))
    }

    // ───────────────────────── 发送 ─────────────────────────

    /**
     * 发一条请求帧并等待它的应答（`requestId` 关联）。
     *
     * `requestId` 由本方法生成并注入**顶层**（桥所有处理器都读 `frame.requestId`）；
     * 调用方即便自己带了也会被替换 —— 保证「一个在途请求一个 key」，不会两个协程抢同一个
     * `CompletableDeferred`。
     *
     * 超时（默认 [DEFAULT_TIMEOUT_MS]，文件类 [FILE_TIMEOUT_MS]）时按设计 §3.3 主动
     * `close(1001)` 触发重连并抛 [DshApiException]：静默等待会让界面一直转圈，
     * 而那条连接此时已经不值得信任了（半开连接是 WS 最典型的故障形态）。
     *
     * @throws DshApiException 未连接 / 发送失败 / 服务端回 error 帧 / 超时 / 断连。
     */
    suspend fun send(frame: JsonObject): JsonObject {
        val webSocket = socket
        if (webSocket == null || !state.value.usesWs) {
            throw DshApiException(0, "WS_NOT_READY", "WS 通道当前不可用（${state.value}）")
        }
        // 先判「这条连接此刻可信吗」再碰 socket（issue #16）。见 [refuseIfStale]。
        refuseIfStale(WsFrames.typeOf(frame))
        val requestId = UUID.randomUUID().toString()
        val outbound = JsonObject(frame + (WsFrames.REQUEST_ID to JsonPrimitive(requestId)))
        val deferred = CompletableDeferred<JsonObject>()
        pending[requestId] = deferred

        if (!webSocket.send(WsFrames.encode(outbound))) {
            pending.remove(requestId)
            throw DshApiException(0, "WS_SEND_FAILED", "WS 帧发送失败（连接已关闭）")
        }

        val timeout = timeoutMillisFor(WsFrames.typeOf(frame))
        return try {
            withTimeout(timeout) { deferred.await() }
        } catch (cancelled: TimeoutCancellationException) {
            pending.remove(requestId)
            // 按设计 §3.3：超时即 `close(1001)` 触发重连，而不是留一条半死连接。
            //
            // 这里**不只**发 close 帧，而是直接走 [abandon]：只发 close 帧的话，要等对端回
            // close 才会触发 `onClosed` —— 而对端已经半死的时候它永远不回，重连就永远不开始
            // （真机上表现为「超时一次，之后一直转圈，直到 20 秒 ping 超时」）。
            // abandon 立刻换代 + 关旧连接 + 退避重连，把恢复时间从「看对端脸色」变成确定值。
            abandon("请求超时（${timeout}ms）：${WsFrames.typeOf(frame) ?: "unknown"}", closeCode = 1001)
            throw DshApiException(408, "WS_TIMEOUT", "WS 请求超时（${timeout}ms）：${WsFrames.typeOf(frame) ?: "unknown"}")
        }
    }

    /**
     * 发一条**不等应答**的帧（`ping` / `subscribe` / `unsubscribe`）。
     *
     * 这几条在桥侧的回帧（`pong` / `subscribed` / `sent`）**不带 `requestId`**，
     * 所以它们进不了 pending map —— 用 [send] 会稳定超时。它们的回帧走 [onPush]。
     *
     * 业务请求也走这里（`WsRequestRouter` 的 `sender` 就是它）：路由器自己有在途表，
     * 不需要本层的 `pending`。所以**返回 `false` 是「帧没发出去」的唯一信号** ——
     * 路由器据此抛 `WS_SEND_FAILED`、调用方据此回落 REST。陈旧判定必须在这里也生效，
     * 否则业务请求（正是 issue #16 里那条 `message`）照样会写进一条死连接。
     *
     * @return `false` = 帧**一个字节都没写出去**（未连接 / 通道降级 / 连接陈旧）。
     */
    fun sendNoReply(frame: JsonObject): Boolean {
        val webSocket = socket ?: return false
        if (!state.value.usesWs) return false
        if (refuseIfStale(WsFrames.typeOf(frame), throwOnStale = false)) return false
        return webSocket.send(WsFrames.encode(frame))
    }

    /**
     * 连接「静默太久」就当场放弃它，并返回是否已经放弃。
     *
     * ## 为什么这件事不能等超时（issue #16）
     *
     * 真机现场：开相册选图 → App 进 `onStop` → 二三十秒后 `onResume` → 发送。
     * 此时 socket 早已不可用，但 [state] 还是 `WsReady`（状态机只在收帧时更新）。
     * 帧写进 socket 后永远等不到应答，15 秒后抛 `WS_TIMEOUT` —— 而 `WS_TIMEOUT`
     * **不能**回落 REST（帧可能已送达，发消息不幂等，重发等于问两遍）。
     * 结果就是用户白等 15 秒、消息退回输入框、手点重发。
     *
     * 把判据前移到**写 socket 之前**，同一个故障就变成了「帧没发出去」：
     * [refuseIfStale] 里先 [abandon]（换代 + 关旧连接 + 排一次重连，1 秒起步），
     * 再让调用方按「没发出去」处理 —— 那是**可以安全回落 REST** 的一侧。
     * 这不是放宽判据，而是它字面成立：一个字节都没写出去。
     *
     * 只在这条连接**曾经收到过东西**之后才判（[lastInboundAtMillis] 不等于
     * [NO_INBOUND_YET]）：刚连上、hello 还没到的窗口由 `WS_NOT_READY` 管，不该在这里被判死。
     *
     * 哨兵值**不能**用 `0`：真机时钟是 `System.currentTimeMillis()`，单测里的假时钟却可以从
     * 0 起步，两种语义会撞在一起 —— 那正是第一版用例集体失败的原因。
     *
     * @param throwOnStale `true` 时抛 [DshApiException]（[send] 用）；`false` 时返回 `true`
     *   交由调用方转成 `false`（[sendNoReply] 用）。
     * @return 是否已经因为陈旧而放弃这条连接。
     */
    private fun refuseIfStale(type: String?, throwOnStale: Boolean = true): Boolean {
        val last = lastInboundAtMillis
        if (last == NO_INBOUND_YET) return false
        val silent = clock() - last
        if (silent < livenessTimeoutMillis) return false
        DiagLog.w("WS", "连接已静默 ${silent}ms（阈值 ${livenessTimeoutMillis}ms），放弃并重建：${type ?: "unknown"}")
        // 先换代再抛：调用方拿到异常立刻回落 REST，而重连已经在路上（1 秒起步退避）。
        abandon("连接已静默 ${silent}ms（阈值 ${livenessTimeoutMillis}ms）", closeCode = 1001)
        if (!throwOnStale) return true
        throw DshApiException(
            0,
            WS_CHANNEL_STALE,
            "WS 连接已静默 ${silent}ms，帧未发出：${type ?: "unknown"}",
        )
    }

    /**
     * 回到前台时主动探一次活（issue #16）。
     *
     * ## 为什么不能只靠「静默时长」
     *
     * 真机现场的窗口是 **10 秒**：21:51:24 `onPause`、21:51:34 `onResume`，然后用户立刻
     * 点发送。10 秒远没到 [livenessTimeoutMillis]（35 秒），所以只按静默时长判的话这条
     * 连接会被判成「健康」——帧照样写进那个已经死掉的 socket。
     *
     * 冻结期间心跳和 pong 一起停了，事后无法从时间戳分辨「安静」和「死了」。
     * 唯一可靠的办法是**当场问一句**：发一条 `ping`，在一个很短的时间窗内等**任何**
     * 入站帧。回得来就是活的（桥的 `handlePing` 必回 `pong`），回不来就当场换连接。
     *
     * 探活窗口取 [PROBE_TIMEOUT_MS]（3 秒）：正常 RTT 是个位数毫秒，3 秒已经给了
     * 弱网（含 4G 抖动）足够余量；再长就是在替用户干等。
     *
     * 这是挂起函数，但调用方（`MainActivity.onResume`）在自己的协程里调，不阻塞主线程。
     */
    suspend fun revalidateLiveness() {
        val webSocket = socket ?: return
        if (!state.value.usesWs) return
        val baseline = lastInboundAtMillis
        DiagLog.i("WS", "回前台探活：发 ping，基线=${if (baseline == NO_INBOUND_YET) "无" else baseline.toString()}")
        // 直接问 socket 而不是走 [sendNoReply]：后者会因为「静默超阈值」而拒发，
        // 但这里恰恰是要**逼**一次往返来拿实证，不能提前放弃。
        if (!webSocket.send(WsFrames.encode(WsFrames.command(WsClientFrame.PING)))) {
            DiagLog.w("WS", "回前台探活：ping 写不出去，换连接")
            abandon("回前台探活：ping 写不出去", closeCode = 1001)
            return
        }
        // 用真实时间数轮次，而不是 `clock()`：后者是「判定陈旧」用的假想时钟，
        // 单测里可以任意跳，拿它当等待预算会让循环永远不结束。
        val rounds = (probeTimeoutMillis / PROBE_POLL_MS).coerceAtLeast(1)
        repeat(rounds.toInt()) {
            if (lastInboundAtMillis != baseline) {
                DiagLog.i("WS", "回前台探活：拿到入站帧，连接是活的")
                return
            }
            delay(PROBE_POLL_MS)
        }
        if (lastInboundAtMillis != baseline) {
            DiagLog.i("WS", "回前台探活：拿到入站帧，连接是活的")
            return
        }
        DiagLog.w("WS", "回前台探活：${probeTimeoutMillis}ms 内没有任何入站帧，换连接")
        abandon("回前台探活：${probeTimeoutMillis}ms 内没有任何入站帧", closeCode = 1001)
    }

    // ───────────────────────── 监听 ─────────────────────────

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        /** 这条回调是否还属于当前连接。旧连接的迟到回调一律丢弃。 */
        private fun isCurrent(): Boolean = gen == generation.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // **刻意不在这里置 WsReady**：连上不等于能用。桥在鉴权之后才推 hello
            // （`ws-handler.ts:457-476`），能力位也在 hello 里。抢跑会让「缺能力位」的
            // 服务端被当成健康通道，然后每个域都发一帧等 15 秒超时。
            if (!isCurrent()) return
            attempt = 0
            // 新连接的时间戳从 0 起算：还没收到任何东西，[refuseIfStale] 不该拿上一条
            // 连接的入站时刻判它死（那会让「刚重连上」被立刻再判死一次，形成重连风暴）。
            lastInboundAtMillis = NO_INBOUND_YET
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            // 任何入站帧都算「这条连接活着」（issue #16）：包括 hello、pong、event。
            // 放在 decode 之前 —— 哪怕是一条我们解析不了的帧，也证明链路此刻通。
            lastInboundAtMillis = clock()
            val frame = WsFrames.decode(text) ?: return
            val kind = WsFrames.kindOf(frame)

            if (kind == WsServerFrame.HELLO) {
                onHello(frame)
                return
            }

            val requestId = WsFrames.requestIdOf(frame)
            if (requestId != null) {
                val waiter = pending.remove(requestId)
                if (waiter != null) {
                    if (kind == WsServerFrame.ERROR) {
                        waiter.completeExceptionally(errorOf(frame))
                    } else {
                        waiter.complete(frame)
                    }
                    return
                }
                // 回显的 requestId 不在**本层**的 pending 表里 —— 不等于该丢。
                //
                // 本层 pending 只装 [send] 发出去的请求；P2 的 [WsRequestRouter] 另外维护
                // 自己的一张表（它发帧走 [sendNoReply]，本层看不到）。桥侧补齐统一回显后，
                // 路由器发出的帧会带着 requestId 回来 —— 那时在这里 `return` 就等于把
                // 路由器的快路**永久切断**，它只能永远靠 kind 兜底。
                //
                // 所以落下去交给 onPush，让上层自己认领；认不出来的上层也无害（路由器
                // 命中不到会继续走 kind 分支或丢进事件出口）。
                // 真正的迟到应答不会因此出错：路由器同样按 requestId + kind 双重校验。
            }

            // 无 requestId：hello / pong / event / question-requested / approval-requested。
            // 一律走推送，不进 pending map（设计 §3.3）。
            onPush?.invoke(frame)
        }

        /**
         * 对端发起关闭。
         *
         * **这个回调必须处理，不能只依赖 [onClosed]**：对端发完 close 帧就断开 socket 时，
         * 客户端只收到 `onClosing`，`onClosed` 可能永远不来（OkHttp 需要我们把 close 帧
         * 回过去才算握手完成，对端已经走了就没人收）。只挂在 `onClosed` 上会让「桥重启」
         * 表现为连接一直卡着 —— 直到 20 秒 ping 超时才被发现。
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            // 先把 close 帧回过去完成握手，再把本回调退休，避免随后迟到的 onClosed 再排一次重连。
            runCatching { webSocket.close(1000, null) }
            retire()
            onDisconnected(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            onDisconnected(code, reason)
        }

        private fun onDisconnected(code: Int, reason: String) {
            socket = null
            if (closed) return
            if (isProtocolRejected(code, helloSeen)) {
                // 子协议被拒：老桥/配置错误的令牌。**不重试**（设计 §3.2 第 1 条）。
                failAllPending("WS_PROTOCOL_REJECTED", "服务端拒绝子协议（$code $reason）")
                transition(ChannelState.RestOnly)
                return
            }
            degradeAndRetry("连接关闭（$code $reason）")
        }

        /** 让本连接的后续回调全部失效（[isCurrent] 变 false）。 */
        private fun retire() {
            generation.incrementAndGet()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            socket = null
            if (closed) return
            val status = response?.code
            if (status != null && !helloSeen && isDefinitiveUpgradeRejection(status)) {
                // upgrade 404/405/426…：这台桥没有 /ws/mobile，或反代不支持 upgrade。
                // 这是确定性答复，重试一万次也一样，所以进终态 RestOnly（设计 §3.2 第 1 条）。
                failAllPending("WS_UPGRADE_REJECTED", "服务端不支持 /ws/mobile（HTTP $status）")
                transition(ChannelState.RestOnly)
                return
            }
            if (status == HTTP_TOO_MANY_REQUESTS && !helloSeen) {
                // 连接数已满：**这不是终态**。桥在认真回答「现在不行，晚点来」，而且给了节奏。
                //
                // 以前这里落到默认分支，用 1 秒起步的退避去撞 —— 桥刚把 32 上限做实（2026-10-29），
                // 多设备用户第一次真会撞上，撞法还是「每秒一次」，等于我们自己制造重连风暴。
                // 现在按 Retry-After 等，且仍与自己的退避取较大者（各管一头，见 RetryAfter.kt）。
                val asked = retryAfterMillis(response?.headers)
                val detail = asked?.let { "服务端要求至少等 ${it}ms" } ?: "服务端未给 Retry-After，按退避重试"
                degradeAndRetry("连接数已满（HTTP $HTTP_TOO_MANY_REQUESTS），$detail", minimumWaitMillis = asked)
                return
            }
            degradeAndRetry(t.message ?: t::class.java.simpleName)
        }
    }

    private fun onHello(frame: JsonObject) {
        val hello = WsFrames.parseHello(frame)
        if (hello == null) {
            // data 不是对象：协议已经不对了。当「能力未知」处理会一直发帧等超时，不如断开重连。
            degradeAndRetry("hello 载荷不是对象")
            return
        }
        helloSeen = true
        val next = decideAfterHello(hello.protocol, hello.capabilities, requiredCapabilities)
        transition(next)
        // hello 之后才有「能用」这个结论，心跳从这里才开始有意义（issue #16）。
        syncHeartbeat(next.usesWs)
    }

    /**
     * 按通道是否可用开关心跳（issue #16）。
     *
     * 心跳的唯一职责是把 [lastInboundAtMillis] 一直刷新下去，让 [refuseIfStale] 有据可依。
     * 它**不**负责「发现断了就重连」——那是 `onClosing`/`onFailure`/超时 [abandon] 的事，
     * 三条路已经覆盖了「明确断开」；这里补的是「谁都没说话，但其实早断了」那条。
     */
    private fun syncHeartbeat(active: Boolean) {
        if (!active || heartbeatIntervalMillis <= 0 || closed) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        if (heartbeatJob?.isActive == true) return
        DiagLog.i("WS", "心跳启动：每 ${heartbeatIntervalMillis}ms 一条 ping（陈旧阈值 ${livenessTimeoutMillis}ms）")
        heartbeatJob = reconnectScope.launch {
            while (isActive) {
                delay(heartbeatIntervalMillis)
                // 发得出去发不出去都无所谓：`send()` 返回 false 说明 socket 已经关了，
                // 那种情况 `onFailure`/`onClosed` 会接手。这里只要别把循环弄崩。
                sendNoReply(WsFrames.command(WsClientFrame.PING))
            }
        }
    }

    /** 停掉心跳并清空时间戳（断线/主动关闭后不该再拿旧读数判活）。 */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        // 与 Listener.onOpen 用同一个哨兵：清空 = 「这条连接还没收到过任何东西」，
        // [refuseIfStale] 见到它就不判陈旧。这里写 `0` 会在真机上被当成「静默了几十年」。
        lastInboundAtMillis = NO_INBOUND_YET
    }

    // ───────────────────────── 状态迁移 ─────────────────────────

    /**
     * 主动放弃当前连接：换代（让旧回调失效）、关旧 socket、按断线处理。
     *
     * 「换代」是关键 —— 不换代的话旧连接的 `onClosed` 会把新连接清掉并再排一次重连。
     */
    private fun abandon(reason: String, closeCode: Int = 1001) {
        val stale = socket
        socket = null
        generation.incrementAndGet()
        runCatching { stale?.close(closeCode, "client abandoned") }
        degradeAndRetry(reason)
    }

    /**
     * 断线 → [ChannelState.WsDown] 并排一次退避重连。
     *
     * 在途请求**全部失败**而不是留着等重连：`CompletableDeferred` 跨连接没有意义
     * （requestId 只在那条连接上有效），留着只会让调用方等满超时。
     */
    private fun degradeAndRetry(reason: String, minimumWaitMillis: Long? = null) {
        failAllPending("WS_DISCONNECTED", "WS 连接已断开：$reason")
        // 连接已经不可用：心跳没有发送对象，时间戳读数也不再代表任何连接（issue #16）。
        stopHeartbeat()
        if (closed) return
        transition(ChannelState.WsDown(reason))
        scheduleReconnect(minimumWaitMillis)
    }

    /**
     * @param minimumWaitMillis 服务端明确要求的最短等待（`Retry-After`），可与退避叠加，
     *   见 [reconnectWaitMillis]。
     */
    private fun scheduleReconnect(minimumWaitMillis: Long? = null) {
        if (closed) return
        if (reconnectJob?.isActive == true) return
        val wait = reconnectWaitMillis(backoffMillis(attempt), minimumWaitMillis)
        attempt += 1
        reconnectJob = reconnectScope.launch {
            delay(wait)
            if (!closed) {
                helloSeen = false
                connect()
            }
        }
    }

    private fun transition(next: ChannelState) {
        val previous = _state.value
        _state.value = next
        if (previous != next) onStateChange?.invoke(next)
    }

    private fun failAllPending(code: String, message: String) {
        // 先通知路由器（它那张表与这里的 pending 是两回事，见构造参数注释）。
        // 即使本类的 pending 是空的也要通知 —— 业务请求全在路由器那边。
        onPendingDropped?.invoke(code, message)
        if (pending.isEmpty()) return
        val snapshot = pending.keys.toList()
        snapshot.forEach { key ->
            pending.remove(key)?.completeExceptionally(DshApiException(0, code, message))
        }
    }

    private fun errorOf(frame: JsonObject): DshApiException {
        val data = WsFrames.dataOf(frame)
        val code = (data["code"] as? JsonPrimitive)?.contentOrNull ?: "WS_ERROR"
        val message = (data["message"] as? JsonPrimitive)?.contentOrNull ?: "WS 服务端返回错误"
        return DshApiException(0, code, message)
    }

    /**
     * 这条帧该用哪档超时。
     *
     * 文件类判据是「`file-` 前缀 **或** `attachment`」—— `attachment` 在桥侧
     * （`ws-protocol.ts:76`）是个**不带前缀**的独立 type，只按前缀判会把它漏进 15 秒档，
     * 而它恰恰是读附件字节的那一条（大图在弱网下 15 秒必超时，一超时按设计就掀整条连接）。
     */
    internal fun timeoutMillisFor(type: String?): Long = when {
        type == null -> defaultTimeoutMillis
        type.startsWith("file-") -> fileTimeoutMillis
        type == WsClientFrame.ATTACHMENT -> fileTimeoutMillis
        else -> defaultTimeoutMillis
    }

    /** 测试与诊断用：当前在途请求数（重连后必须是 0 —— 不重放、不悬挂）。 */
    internal fun pendingCount(): Int = pending.size

    companion object {
        /** 默认请求超时（设计 §3.3）。 */
        const val DEFAULT_TIMEOUT_MS: Long = 15_000

        /**
         * Upgrade 阶段「连接数已满」的状态码（桥 `server.ts` 的 `handleUpgrade`，默认上限 32）。
         *
         * 单独给个名字而不是写裸 `429`：它不是错误，是服务端在说「晚点再来，节奏在这个头里」，
         * 和 404/405 那种「这条通道不存在」是**相反**的语义（见 [isDefinitiveUpgradeRejection]）。
         */
        const val HTTP_TOO_MANY_REQUESTS: Int = 429

        /** 文件传输类超时（设计 §3.3）：分片下载 512 KiB/块，15 秒在小带宽下不够。 */
        const val FILE_TIMEOUT_MS: Long = 120_000

        /**
         * 「这条连接已经静默太久」的错误码（issue #16）。
         *
         * 与 [WsRequestRouter] 的 `WS_CHANNEL_CLOSED` 同级语义 —— **帧一个字节都没写出去**，
         * 所以归进 `isFrameNeverSent`、可以安全回落 REST。区别只在成因：那个是「通道被关时
         * 还有在途请求」，这个是「发帧之前就发现连接早死了」。
         */
        const val WS_CHANNEL_STALE: String = "WS_CHANNEL_STALE"

        /**
         * 应用层心跳间隔。
         *
         * 20 秒是权衡：比桥的 `DEFAULT_MOBILE_HEARTBEAT_INTERVAL_MS = 30_000`
         * （`ws-handler.ts:136`）短，保证桥那侧的「连续两拍没 pong 就 terminate」永远
         * 不会因为客户端不发而误杀；又不至于密到在移动网上变成耗电噪音。
         * 另外它顺带保住 NAT/中间设备的空闲连接（运营商侧常见的 30–60 秒回收）。
         */
        const val HEARTBEAT_INTERVAL_MS: Long = 20_000

        /**
         * 回前台探活时，等多久算「问了没回」。
         *
         * 3 秒：正常 RTT 个位数毫秒，3 秒已给足弱网余量；再长就是在替用户干等，
         * 而探活本身的目的就是**缩短**「点了没反应」的时间。
         */
        const val PROBE_TIMEOUT_MS: Long = 3_000

        /** 探活等待期间的轮询间隔。20ms 只是别把 CPU 占满，精度够用。 */
        internal const val PROBE_POLL_MS: Long = 20

        /**
         * 「这条连接还没收到过任何入站帧」的哨兵值。
         *
         * 不用 `0`：真机用 [System.currentTimeMillis]（几百亿量级），单测的假时钟却常从 0
         * 起步 —— 用 `0` 会让「刚起步的假时钟」被当成「从没收到过帧」。
         */
        internal const val NO_INBOUND_YET: Long = Long.MIN_VALUE

        /**
         * 「静默多久算死」：心跳的 2 倍加 5 秒余量。
         *
         * 为什么不取更短：移动网抖一下丢一拍 pong 很常见，取 1 倍会把健康连接判死、
         * 白白把业务请求推到 REST。为什么不取更长：真机实测从相册返回后的僵死窗口
         * 经常就在 20–40 秒量级，阈值再大就回到「用户白等」的老问题上。
         */
        internal fun livenessTimeoutFor(heartbeatIntervalMillis: Long): Long =
            if (heartbeatIntervalMillis <= 0) 0 else heartbeatIntervalMillis * 2 + 5_000

        /**
         * 退避基数（秒）：`1,2,4,8,16,30`，与 `ReconnectingEvents`（`DshClient.kt:731`）同一口径。
         *
         * 上限 30 秒是刻意的：再长，用户从地铁里出来要等半分钟才恢复；再短，桥重启期间
         * 几十个客户端会把它再打挂一次。
         */
        val BACKOFF_SECONDS: List<Long> = listOf(1, 2, 4, 8, 16, 30)

        /** 抖动幅度：±20%。没有抖动的话，桥重启后所有客户端会在同一毫秒撞上来。 */
        const val JITTER_RATIO: Double = 0.2

        /**
         * 第 [attempt] 次重连前要等多久（毫秒）。
         *
         * [jitterRoll] ∈ [0,1)，0.5 表示「不偏不倚」。抽成纯函数是为了让「退避序列」
         * 这条能被单测钉死 —— 用真实 `delay` 测序列既慢又不稳（CI 上 1 秒能变成 3 秒）。
         */
        internal fun reconnectDelayMillis(attempt: Int, jitterRoll: Double = Random.nextDouble()): Long {
            val base = BACKOFF_SECONDS[attempt.coerceIn(0, BACKOFF_SECONDS.lastIndex)]
            val factor = 1.0 + (jitterRoll.coerceIn(0.0, 1.0) * 2.0 - 1.0) * JITTER_RATIO
            return (base * 1000.0 * factor).roundToLong()
        }
    }
}
