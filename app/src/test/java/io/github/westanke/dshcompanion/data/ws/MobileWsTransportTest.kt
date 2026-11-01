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

    // ───────────────────────── 连接陈旧（issue #16）─────────────────────────

    @Test
    fun `连接静默超过阈值时业务帧在写 socket 前就被拒 于是能回落 REST`() {
        // 这是 issue #16 的核心：sendNoReply 返回 false → 路由器抛 WS_SEND_FAILED
        // → isFrameNeverSent 成立 → 调用方回落 REST。帧一个字节都没写出去。
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        var now = 0L
        val transport = transport(clock = { now })
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        transport.sendNoReply(WsFrames.command(WsClientFrame.PING))
        awaitFirstFrame()

        // 静默但没到阈值：帧照发（真机上这就是「安静一会儿」的正常连接）。
        now += 1_000
        assertTrue(
            "没到阈值，连接仍应可信",
            transport.sendNoReply(WsFrames.command(WsClientFrame.PING)),
        )

        // 越过阈值：下一帧必须在写 socket 之前就被拒。
        now += FAST_LIVENESS_MS
        assertFalse(
            "静默超阈值后帧必须发不出去",
            transport.sendNoReply(WsFrames.command(WsClientFrame.PING)),
        )
        awaitState(transport) { it is ChannelState.WsDown }
    }

    @Test
    fun `send 撞上陈旧连接时抛 WS_CHANNEL_STALE 而不是等满超时`() = runBlocking {
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        var now = 0L
        val transport = transport(clock = { now })
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }
        transport.sendNoReply(WsFrames.command(WsClientFrame.PING))
        awaitFirstFrame()

        now += FAST_LIVENESS_MS + 1

        try {
            transport.send(WsFrames.command(WsClientFrame.SESSIONS))
            fail("应抛陈旧异常")
        } catch (error: DshApiException) {
            assertEquals(MobileWsTransport.WS_CHANNEL_STALE, error.code)
            assertTrue("消息应带上静默时长：${error.message}", error.message!!.contains("静默"))
        }
        assertEquals("陈旧判定不该留下 pending", 0, transport.pendingCount())
    }

    @Test
    fun `重连出来的新连接不继承上一条连接的静默时长`() {
        // 陈旧判定用的是「这条连接最后一次收到帧」的时刻。换代后必须重新起算，
        // 否则一条刚连好的连接会被上一条的静默时长判死，直接进入重连风暴。
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        var now = 0L
        val transport = transport(clock = { now })
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        // 第一条连接静默超阈值 → 当场放弃、排重连。
        now += FAST_LIVENESS_MS + 1
        assertFalse(transport.sendNoReply(WsFrames.command(WsClientFrame.PING)))

        // 重连成功（第二条连接的 hello 刷新了时间戳），此刻不该再被判陈旧。
        awaitState(transport, timeoutMs = 5_000) { it == ChannelState.WsReady }
        Thread.sleep(100)
        assertTrue(
            "新连接不该继承旧连接的静默时长",
            transport.sendNoReply(WsFrames.command(WsClientFrame.PING)),
        )
    }

    @Test
    fun `任何入站帧都会刷新存活时间戳`() {
        // pong 的价值就在这里：它不带 requestId、业务上没人认领，但证明链路此刻通。
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                onText = { socket, _ -> socket.send("""{"kind":"pong","data":{"at":1}}""") },
            ),
        )
        var now = 0L
        val transport = transport(clock = { now })
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        now += FAST_LIVENESS_MS - 1_000
        assertTrue(transport.sendNoReply(WsFrames.command(WsClientFrame.PING)))
        transport.sendNoReply(WsFrames.command(WsClientFrame.PING))
        awaitFirstFrame()
        Thread.sleep(120)

        // 收到 pong 之后时间戳被刷新：再走一段本来会判死的静默，仍然可用。
        now += FAST_LIVENESS_MS - 1_000
        assertTrue(
            "入站帧刷新过时间戳，连接应仍然可信",
            transport.sendNoReply(WsFrames.command(WsClientFrame.PING)),
        )
    }

    @Test
    fun `回前台探活拿不到任何入站帧就当场换连接`() = runBlocking {
        // 真机场景：从相册回来 socket 已经死了，但状态还写着「已连接」。
        // 探活必须在观察窗内等不到帧时主动 abandon，让界面进入重连而不是假装没事。
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        var now = 0L
        val transport = transport(
            clock = { now },
            probeTimeoutMillis = 300L,
        )
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        transport.revalidateLiveness()

        awaitState(transport, timeoutMs = 5_000) { it == ChannelState.WsReady }
        assertTrue("探活失败应触发重连：${server.requestCount}", server.requestCount >= 2)
    }

    @Test
    fun `回前台探活收到 pong 就保持原连接不动`() = runBlocking {
        server.enqueue(
            upgrade(
                onOpen = sendHello(capabilities = ALL_REQUIRED),
                onText = { socket, _ -> socket.send("""{"kind":"pong","data":{"at":1}}""") },
            ),
        )
        val transport = transport(probeTimeoutMillis = 1_000L)
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        transport.revalidateLiveness()

        awaitState(transport) { it == ChannelState.WsReady }
        assertEquals("探活成功不该换连接", 1, server.requestCount)
    }

    @Test
    fun `心跳按间隔发 ping 让连接不停留在静默状态`() {
        // 不心跳的话，一条正常空闲的连接会被 refuseIfStale 误判成死的。
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport(heartbeatIntervalMillis = 60L)
        transport.start()
        awaitState(transport) { it == ChannelState.WsReady }

        val pings = received.poll(3, TimeUnit.SECONDS)!!
        assertTrue("心跳应是 ping 帧：$pings", pings.contains(""""type":"ping""""))
        assertEquals(ChannelState.WsReady, transport.state.value)
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
    fun `upgrade 429 连接数已满时按 Retry-After 等待并自己恢复，不判死`() {
        // 桥从 2026-10-29 起对 /ws/mobile 真的执行 32 条上限，第 33 条在 Upgrade 阶段收 429。
        // 这是「我知道你来了，请晚点再来」——和 404/405 的「这条通道不存在」语义相反：
        // 前者必须能自己恢复（进 RestOnly 等于把用户永久降级到 REST），且要按服务端给的节奏重试。
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(upgrade(onOpen = sendHello(capabilities = ALL_REQUIRED)))
        val transport = transport()
        transport.start()

        awaitState(transport, timeoutMs = 8_000) { it == ChannelState.WsReady }
        assertEquals("429 之后必须再试一次并成功", 2, server.requestCount)
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
        heartbeatIntervalMillis: Long = HEARTBEAT_OFF_MS,
        livenessTimeoutMillis: Long = FAST_LIVENESS_MS,
        probeTimeoutMillis: Long = FAST_PROBE_MS,
        clock: () -> Long = System::currentTimeMillis,
    ) = MobileWsTransport(
        endpoint = server.url("/").toString(),
        token = token,
        onPush = onPush,
        defaultTimeoutMillis = FAST_TIMEOUT_MS,
        fileTimeoutMillis = FAST_TIMEOUT_MS,
        backoffMillis = { FAST_BACKOFF_MS },
        heartbeatIntervalMillis = heartbeatIntervalMillis,
        livenessTimeoutMillis = livenessTimeoutMillis,
        probeTimeoutMillis = probeTimeoutMillis,
        clock = clock,
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

        /** 大多数用例不关心心跳，关掉它免得后台协程干扰断言。 */
        const val HEARTBEAT_OFF_MS = 0L

        /** 陈旧阈值：故意取很小，让「静默超阈值」不必真等 35 秒。 */
        const val FAST_LIVENESS_MS = 5_000L

        /** 探活窗口。 */
        const val FAST_PROBE_MS = 200L
        val ALL_REQUIRED = REQUIRED_CAPABILITIES_FOR_WS.toList()
        val ALL_REQUIRED_JSON = ALL_REQUIRED.joinToString(",") { "\"$it\"" }
    }
}
