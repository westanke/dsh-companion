package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `MobileWsTransport` 的通道行为（设计 §3.2/§3.3）。
 *
 * **这是全仓唯一测「通道差异」的地方**（设计 §6 最后一条）：超时、重连退避、降级判定
 * 都在这里跑一遍，P2 各域的单测用假 Port 即可，不必各自再起一个 WS。
 *
 * 每个用例都注入小超时（[FAST_TIMEOUT_MS]）与假退避（[FAST_BACKOFF_MS]）：真实数值
 * （15s / 1,2,4,8,16,30s）由 [退避序列是 1_2_4_8_16_30 带正负 20 百分比抖动] 这类纯函数用例钉死，
 * 集成用例只关心「有没有按顺序发生」。
 */
class MobileWsTransportTest {

    private lateinit var server: MockWebServer

    /** 服务端侧收到的每一帧文本（按到达顺序）。 */
    private val received = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ───────────────────────── hello ─────────────────────────

    @Test
    fun `收到 hello 且能力位齐时进 WsReady`() {
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()

        awaitState(transport) { it == ChannelState.WsReady }
        assertEquals(ChannelState.WsReady, transport.state.value)
    }

    @Test
    fun `hello 缺能力位时进 WsDegraded 而不是 WsDown`() {
        // 关键：连上是连上了，只是能力不足。判成 WsDown 会触发无意义的重连风暴。
        server.enqueue(upgrade(sendHello(capabilities = listOf("session-list-v1"))))
        val transport = transport()
        transport.start()

        val state = awaitState(transport) { it is ChannelState.WsDegraded }
        val reason = (state as ChannelState.WsDegraded).reason
        assertTrue("reason 应点名缺的位：$reason", reason.contains("history-v1"))
        assertTrue("reason 应点名缺的位：$reason", reason.contains("turn-cancel-v1"))
    }

    @Test
    fun `连接建立但 hello 未到时不算 WsReady`() {
        // 桥是先鉴权后发 hello，能力位在 hello 里。抢跑会让缺能力位的服务端被当健康通道。
        server.enqueue(upgrade(onOpen = { _, _ -> /* 故意不发 hello */ }))
        val transport = transport()
        transport.start()

        awaitUpgradeHandled()
        Thread.sleep(150)
        assertFalse("hello 未到就不该判 WsReady", transport.state.value is ChannelState.WsReady)
    }

    @Test
    fun `hello 的 data 不是对象时按断开重连处理`() {
        server.enqueue(upgrade(onOpen = { socket, _ -> socket.send("""{"kind":"hello","data":"oops"}""") }))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()

        awaitState(transport) { it == ChannelState.WsReady }
    }

    // ───────────────────────── requestId 关联 ─────────────────────────

    @Test
    fun `requestId 回显时 send 返回对应帧`() = runBlocking {
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                onText = { socket, text ->
                    val requestId = WsFrames.requestIdOf(WsFrames.decode(text)!!)!!
                    socket.send("""{"kind":"sessions","data":{"requestId":"$requestId","items":[]}}""")
                },
            ),
        )
        val transport = transport()
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        val reply = transport.send(WsFrames.command(WsClientFrame.SESSIONS))

        assertEquals("sessions", WsFrames.kindOf(reply))
        assertEquals(0, transport.pendingCount())
    }

    @Test
    fun `请求帧的 requestId 出现在顶层且每次不同`() = runBlocking {
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                // 回显 requestId：send 才能正常返回，第二次发送也才不会被超时后的换代打断。
                onText = { socket, text ->
                    val frame = WsFrames.decode(text)!!
                    val id = frame["requestId"]!!.jsonPrimitive.content
                    socket.send("""{"kind":"sessions","requestId":"$id","data":{"sessions":[]}}""")
                },
            ),
        )
        val transport = transport()
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        transport.send(WsFrames.command(WsClientFrame.PING))
        transport.send(WsFrames.command(WsClientFrame.PING))

        val first = WsFrames.decode(received.poll(2, TimeUnit.SECONDS)!!)!!
        val second = WsFrames.decode(received.poll(2, TimeUnit.SECONDS)!!)!!
        val id1 = first["requestId"]?.jsonPrimitive?.content
        val id2 = second["requestId"]?.jsonPrimitive?.content
        assertTrue("requestId 应在顶层：$first", id1 != null)
        assertTrue("两次请求的 requestId 必须不同", id1 != id2)
    }

    @Test
    fun `服务端 error 帧让 send 抛 DshApiException 并带 code`() = runBlocking {
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                onText = { socket, text ->
                    val requestId = WsFrames.requestIdOf(WsFrames.decode(text)!!)!!
                    socket.send(
                        """{"kind":"error","data":{"requestId":"$requestId","code":"MISSING_PARAMS","message":"needs sessionId"}}""",
                    )
                },
            ),
        )
        val transport = transport()
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        try {
            transport.send(WsFrames.command(WsClientFrame.HISTORY))
            fail("应抛 DshApiException")
        } catch (error: DshApiException) {
            assertEquals("MISSING_PARAMS", error.code)
            assertTrue(error.message.contains("needs sessionId"))
        }
        assertEquals(0, transport.pendingCount())
    }

    @Test
    fun `无 requestId 的帧走推送回调而不是进 pending`() {
        val pushes = LinkedBlockingQueue<JsonObject>()
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                onText = { socket, _ -> socket.send("""{"kind":"pong","data":{"at":1}}""") },
            ),
        )
        val transport = transport(onPush = { pushes.offer(it) })
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        transport.sendNoReply(WsFrames.command(WsClientFrame.PING))

        val push = pushes.poll(2, TimeUnit.SECONDS)!!
        assertEquals("pong", WsFrames.kindOf(push))
        assertEquals(0, transport.pendingCount())
    }

    // ───────────────────────── 超时 ─────────────────────────

    @Test
    fun `请求超时抛 WS_TIMEOUT 并清空 pending`() = runBlocking {
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED))) // 服务端永不回帧
        val transport = transport()
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        try {
            transport.send(WsFrames.command(WsClientFrame.SESSIONS))
            fail("应抛超时异常")
        } catch (error: DshApiException) {
            assertEquals("WS_TIMEOUT", error.code)
            assertEquals(408, error.status)
        }
        assertEquals("超时后 pending 必须清空", 0, transport.pendingCount())
    }

    @Test
    fun `超时后按设计主动 close 1001 触发重连`() = runBlocking {
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        runCatching { transport.send(WsFrames.command(WsClientFrame.SESSIONS)) }

        // 1001 会让客户端走 onClosed → WsDown → 重连；重连成功后 hello 再把它拉回 WsReady。
        awaitState(transport, timeoutMs = 5_000) { it == ChannelState.WsReady }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `连接不可用时 send 直接抛 WS_NOT_READY 不排队`() = runBlocking {
        // 排队等重连比直接失败更糟：调用方会一直转圈，而 REST 兜底本来就在手边。
        val transport = transport()
        try {
            transport.send(WsFrames.command(WsClientFrame.SESSIONS))
            fail("未连接时应抛异常")
        } catch (error: DshApiException) {
            assertEquals("WS_NOT_READY", error.code)
        }
    }

    // ───────────────────────── 降级：RestOnly ─────────────────────────

    @Test
    fun `upgrade 404 时进 RestOnly 且不再重试`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val transport = transport()
        transport.start()

        awaitState(transport) { it == ChannelState.RestOnly }
        Thread.sleep(400)
        assertEquals("老桥不会突然长出通道，不许重试", 1, server.requestCount)
        assertEquals(ChannelState.RestOnly, transport.state.value)
    }

    @Test
    fun `upgrade 405 与 426 同样进 RestOnly`() {
        listOf(405, 426).forEach { code ->
            val fresh = MockWebServer()
            fresh.start()
            try {
                fresh.enqueue(MockResponse().setResponseCode(code))
                val transport = MobileWsTransport(
                    endpoint = fresh.url("/").toString(),
                    token = "tok",
                    defaultTimeoutMillis = FAST_TIMEOUT_MS,
                    fileTimeoutMillis = FAST_TIMEOUT_MS,
                    backoffMillis = { FAST_BACKOFF_MS },
                )
                transport.start()
                awaitState(transport) { it == ChannelState.RestOnly }
                assertEquals("HTTP $code 不该重试", 1, fresh.requestCount)
                transport.close()
            } finally {
                runCatching { fresh.shutdown() }
            }
        }
    }

    @Test
    fun `upgrade 503 走退避重连而不是 RestOnly`() {
        // 桥在重启时回 503。这是「等会儿就好」，判死会让 App 在桥恢复后依然拒绝用 WS。
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()

        awaitState(transport, timeoutMs = 5_000) { it == ChannelState.WsReady }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `hello 之前的 4001 子协议被拒进 RestOnly 不重试`() {
        server.enqueue(
            upgrade(
                onOpen = { socket, _ ->
                    // 稍等一下再关：立刻关会和客户端 onOpen 抢跑，导致客户端只看到 onFailure。
                    // 真机上桥是先完成握手再判子协议，所以「先 onOpen 后 onClosed」才是真实时序。
                    Thread {
                        Thread.sleep(120)
                        socket.close(4001, "AUTH_REQUIRED")
                    }.apply { isDaemon = true }.start()
                },
            ),
        )
        val transport = transport()
        transport.start()

        awaitState(transport) { it == ChannelState.RestOnly }
        Thread.sleep(400)
        assertEquals("子协议被拒是确定性答复，不该重试", 1, server.requestCount)
    }

    // ───────────────────────── 降级：WsDown + 重连 ─────────────────────────

    @Test
    fun `已建连接断开进 WsDown 并在途请求全部失败`() {
        val transport = transport()
        server.enqueue(
            upgrade(
                onOpen = { socket, _ ->
                    // 发完 hello 立刻踢掉，且之后不再给新的 upgrade 应答 —— 让状态停在 WsDown。
                    socket.send("""{"kind":"hello","data":{"protocol":3,"capabilities":$ALL_REQUIRED_JSON}}""")
                    socket.close(1000, "server bye")
                },
            ),
        )
        transport.start()

        awaitState(transport) { it is ChannelState.WsDown }
        assertEquals("断连后在途请求不许悬挂", 0, transport.pendingCount())
    }

    @Test
    fun `断连后按退避重连 重连成功回 WsReady`() {
        server.enqueue(
            upgrade(
                onOpen = { socket, _ ->
                    socket.send("""{"kind":"hello","data":{"protocol":3,"capabilities":$ALL_REQUIRED_JSON}}""")
                    socket.close(1000, "bye")
                },
            ),
        )
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()

        awaitState(transport, timeoutMs = 5_000) { it == ChannelState.WsReady }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `重连不重放断连期间的请求`() {
        // 重放比丢更危险（设计 §3.2 第 4 条）：写操作重放会真的执行两次。
        val transport = transport()
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        // 发一个服务端不回应的请求 → 它会挂起 → 然后断连把它掀掉。
        val sender = Thread {
            runCatching { runBlocking { transport.send(WsFrames.command(WsClientFrame.SESSIONS)) } }
        }
        sender.start()
        awaitFirstFrame()
        sender.join(3_000)

        assertEquals("重连前后 pending 都必须是 0", 0, transport.pendingCount())
    }

    @Test
    fun `退避序列是 1_2_4_8_16_30 带正负 20 百分比抖动`() {
        // 抖动取 0.5 = 不偏不倚，用来钉死基数。
        val nominal = (0..8).map { MobileWsTransport.reconnectDelayMillis(it, jitterRoll = 0.5) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L), nominal)

        // 抖动下界 0.0 → -20%；上界接近 1.0 → +20%。
        assertEquals(800L, MobileWsTransport.reconnectDelayMillis(0, jitterRoll = 0.0))
        assertEquals(1_200L, MobileWsTransport.reconnectDelayMillis(0, jitterRoll = 1.0))
        assertEquals(24_000L, MobileWsTransport.reconnectDelayMillis(5, jitterRoll = 0.0))
        assertEquals(36_000L, MobileWsTransport.reconnectDelayMillis(5, jitterRoll = 1.0))
    }

    @Test
    fun `退避序列常量与设计文档一致`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L), MobileWsTransport.BACKOFF_SECONDS)
        assertEquals(0.2, MobileWsTransport.JITTER_RATIO, 1e-9)
    }

    @Test
    fun `超时数值符合设计 默认 15 秒 文件类 120 秒`() {
        assertEquals(15_000L, MobileWsTransport.DEFAULT_TIMEOUT_MS)
        assertEquals(120_000L, MobileWsTransport.FILE_TIMEOUT_MS)
    }

    @Test
    fun `attachment 与 file 前缀帧都走文件超时档`() {
        // `attachment` 在桥侧是不带 `file-` 前缀的独立 type（ws-protocol.ts:76），
        // 只按前缀判会把它漏进 15 秒档 —— 而它正是读附件字节那条，大图必超时。
        // 这里刻意不用 transport()：夹具把两档都注入成 FAST_TIMEOUT_MS，那样断言恒等、测不出分档。
        val probe = MobileWsTransport(
            endpoint = server.url("/").toString(),
            token = "tok",
            defaultTimeoutMillis = 1_000,
            fileTimeoutMillis = 7_000,
            backoffMillis = { FAST_BACKOFF_MS },
        )
        assertEquals("attachment 必须走文件档", 7_000L, probe.timeoutMillisFor(WsClientFrame.ATTACHMENT))
        assertEquals("file- 前缀走文件档", 7_000L, probe.timeoutMillisFor("file-download-read"))
        assertEquals("普通帧走默认档", 1_000L, probe.timeoutMillisFor(WsClientFrame.SESSIONS))
        assertEquals("未知 type 走默认档", 1_000L, probe.timeoutMillisFor(null))
        assertEquals("ping 走默认档", 1_000L, probe.timeoutMillisFor(WsClientFrame.PING))
    }

    // ───────────────────────── 建连 URL ─────────────────────────

    @Test
    fun `建连路径是根下的 ws mobile 不带 api v1 前缀`() {
        // 桥拿 url.pathname === '/ws/mobile' 逐字比较（server.ts:456-464）。
        // 拼成 /api/v1/ws/mobile 会让握手 404 → 被误判 RestOnly → 永远不重试。
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()
        awaitUpgradeHandled()

        val path = server.takeRequest(2, TimeUnit.SECONDS)!!.path
        assertEquals("/ws/mobile", path)
    }

    @Test
    fun `令牌只走子协议头不进 URL`() {
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport(token = "secret-token")
        transport.start()
        awaitUpgradeHandled()

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val path = request.path!!
        assertFalse("URL 不许带令牌：$path", path.contains("secret-token"))
        assertFalse("URL 不许带 token 参数：$path", path.contains("token="))
        val protocols = request.getHeader("Sec-WebSocket-Protocol")!!
        assertEquals("dsh-mobile-v1, dsh-auth.secret-token", protocols)
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun transport(
        token: String = "tok",
        onPush: ((JsonObject) -> Unit)? = null,
    ) = MobileWsTransport(
        endpoint = server.url("/").toString(),
        token = token,
        onPush = onPush,
        defaultTimeoutMillis = FAST_TIMEOUT_MS,
        fileTimeoutMillis = FAST_TIMEOUT_MS,
        backoffMillis = { FAST_BACKOFF_MS },
    )

    private fun upgrade(
        onOpen: (WebSocket, Response) -> Unit,
        onText: (WebSocket, String) -> Unit = { _, _ -> },
    ): MockResponse = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket, response)
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.offer(text)
                onText(webSocket, text)
            }
        },
    )

    /** 服务端连上就发 hello。 */
    private fun sendHello(capabilities: List<String>): (WebSocket, Response) -> Unit = { socket, _ ->
        val caps = capabilities.joinToString(",") { "\"$it\"" }
        socket.send(
            """{"kind":"hello","data":{"protocol":3,"capabilities":[$caps],"authenticated":true,"clients":1}}""",
        )
    }

    private fun awaitState(
        transport: MobileWsTransport,
        timeoutMs: Long = 3_000,
        predicate: (ChannelState) -> Boolean,
    ): ChannelState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = transport.state.value
            if (predicate(current)) return current
            Thread.sleep(20)
        }
        fail("等待状态超时，当前为 ${transport.state.value}")
        error("unreachable")
    }

    private fun awaitUpgradeHandled() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (server.requestCount >= 1) return
            Thread.sleep(20)
        }
        fail("服务端始终没收到 upgrade 请求")
    }

    private fun awaitFirstFrame() {
        assertTrue("服务端应收到至少一帧", received.poll(3, TimeUnit.SECONDS) != null)
    }

    private companion object {
        const val FAST_TIMEOUT_MS = 400L
        const val FAST_BACKOFF_MS = 60L
        val ALL_REQUIRED = REQUIRED_CAPABILITIES_FOR_WS.toList()
        val ALL_REQUIRED_JSON = ALL_REQUIRED.joinToString(",") { "\"$it\"" }
    }
}
