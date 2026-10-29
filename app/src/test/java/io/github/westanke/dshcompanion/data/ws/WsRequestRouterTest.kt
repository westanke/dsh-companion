package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [WsRequestRouter] 的配对逻辑测试。
 *
 * 这里全程**手工构造帧**、不经 MockWebServer：MockWebServer 会老老实实回显 requestId，
 * 那正好测不出真实桥的不对称（`sessions` / `history` / `message` 都不回显 —— 见
 * `data/ws/WsRequestRouter.kt` 的 `@patch` 块）。所以本文件模拟的是**老桥**行为：
 * 发出去的帧带 requestId，回来的帧不带。
 */
class WsRequestRouterTest {

    /**
     * 用 [supervisorScope] 起测试体。
     *
     * 直接 `runBlocking { async { 失败 } }` 会踩结构化并发：子协程失败会**取消父作用域**，
     * 于是异常从 `runBlocking` 里冒出来，`job.await()` 外层那圈 `try/catch` 根本拦不住
     *（断言就变成「测试框架报错」而不是「我断言到了 WS_TIMEOUT」）。
     * 而本文件有好几个用例**就是要**让在途请求失败。
     */
    private fun case(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking<Unit> { supervisorScope { body() } }

    /** 记录发出去了什么，并允许测试手工注入"服务端回帧"。 */
    private class FakeLink {
        val sent = mutableListOf<JsonObject>()
        @Volatile var router: WsRequestRouter? = null

        fun sender(): (JsonObject) -> Boolean = { frame ->
            synchronized(sent) { sent.add(frame) }
            true
        }

        fun sentTypes(): List<String> = synchronized(sent) { sent.mapNotNull { WsFrames.typeOf(it) } }

        fun sentRequestIds(): List<String> = synchronized(sent) { sent.mapNotNull { WsFrames.requestIdOf(it) } }

        /** 拆掉 requestId，模拟真实老桥：回帧里根本没有这个键。 */
        fun strip(frame: JsonObject): JsonObject = JsonObject(frame - WsFrames.REQUEST_ID)
    }

    private fun sessionsReply(sessions: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList())) =
        buildJsonObject {
            put("kind", "sessions")
            put("data", buildJsonObject { put("sessions", sessions) })
        }

    private fun historyReply(sessionId: String) = buildJsonObject {
        put("kind", "history")
        put(
            "data",
            buildJsonObject {
                put("sessionId", sessionId)
                put("events", kotlinx.serialization.json.JsonArray(emptyList()))
                put("hasMore", false)
            },
        )
    }

    private fun errorReply(code: String, requestType: String? = null) = buildJsonObject {
        put("kind", "error")
        put(
            "data",
            buildJsonObject {
                put("code", code)
                put("message", "服务端说：$code")
                if (requestType != null) put("requestType", requestType)
            },
        )
    }

    // ---------------------------------------------------------------- 回显快路

    @Test
    fun `requestId 被回显时走快路 不依赖 kind`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())
        link.router = router

        val job = async {
            router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS)
        }
        awaitSent(link, 1)
        // 回帧**带** requestId（新桥行为），且故意把 kind 换成别的，证明快路不看 kind。
        val echoed = buildJsonObject {
            put("kind", "sessions")
            put("data", buildJsonObject { put(WsFrames.REQUEST_ID, JsonPrimitive(link.sentRequestIds().single())) })
        }
        router.onFrame(echoed)

        val reply = withTimeout(2_000) { job.await() }
        assertEquals(WsServerFrame.SESSIONS, WsFrames.kindOf(reply))
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `出站帧带顶层 requestId 且每次不同`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val first = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)
        router.onFrame(sessionsReply())
        withTimeout(2_000) { first.await() }

        val second = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 2)
        router.onFrame(sessionsReply())
        withTimeout(2_000) { second.await() }

        val ids = link.sentRequestIds()
        assertEquals(2, ids.size)
        assertNotNull(ids[0])
        assertTrue("两次请求的 requestId 不能相同", ids[0] != ids[1])
    }

    // ---------------------------------------------------------------- 老桥兜底：按 kind

    @Test
    fun `没有 requestId 的应答按 kind 配对`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)
        // 真实老桥：回帧外层与 data 内层都没有 requestId。
        val reply = link.strip(sessionsReply())
        assertEquals("前提：这条回帧里不该有 requestId", null, WsFrames.requestIdOf(reply))
        router.onFrame(reply)

        val result = withTimeout(2_000) { job.await() }
        assertEquals(WsServerFrame.SESSIONS, WsFrames.kindOf(result))
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `history 应答按 data sessionId 判别 不串会话`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val job = async {
            router.request(
                requestType = WsClientFrame.HISTORY,
                expectedKind = WsServerFrame.HISTORY,
                payload = JsonObject(mapOf("sessionId" to JsonPrimitive("s-1"))),
                matcher = { WsFrames.dataOf(it)["sessionId"]?.let { id -> (id as JsonPrimitive).content } == "s-1" },
            )
        }
        awaitSent(link, 1)

        // 先来一条别的会话的迟到 history：不该被认领。
        router.onFrame(link.strip(historyReply("s-OTHER")))
        assertEquals("别的会话的 history 不许被认领", 1, router.pendingCount())

        router.onFrame(link.strip(historyReply("s-1")))
        val result = withTimeout(2_000) { job.await() }
        assertEquals(WsServerFrame.HISTORY, WsFrames.kindOf(result))
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `同 kind 串行化 后一条等前一条完成才发`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val first = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)

        val second = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        delay(150)
        assertEquals("同 kind 不许并发发第二条", 1, link.sentTypes().size)

        router.onFrame(sessionsReply())
        withTimeout(2_000) { first.await() }

        awaitSent(link, 2)
        router.onFrame(sessionsReply())
        withTimeout(2_000) { second.await() }
        assertEquals(2, link.sentTypes().size)
    }

    @Test
    fun `不同 kind 可以并发`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val sessions = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        val history = async {
            router.request(
                requestType = WsClientFrame.HISTORY,
                expectedKind = WsServerFrame.HISTORY,
                payload = JsonObject(mapOf("sessionId" to JsonPrimitive("s-1"))),
            )
        }
        awaitSent(link, 2)
        assertEquals(setOf(WsClientFrame.SESSIONS, WsClientFrame.HISTORY), link.sentTypes().toSet())

        router.onFrame(historyReply("s-1"))
        router.onFrame(sessionsReply())
        withTimeout(2_000) { sessions.await() }
        withTimeout(2_000) { history.await() }
        assertEquals(0, router.pendingCount())
    }

    // ---------------------------------------------------------------- error 归属

    @Test
    fun `error 帧按 requestType 归属对应的在途请求`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)
        router.onFrame(errorReply("RATE_LIMITED", requestType = WsClientFrame.SESSIONS))

        try {
            withTimeout(2_000) { job.await() }
            fail("error 帧必须让请求抛异常")
        } catch (failure: DshApiException) {
            assertEquals("RATE_LIMITED", failure.code)
        }
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `没有 requestType 的 error 帧谁都不归属`() = case {
        val link = FakeLink()
        val events = mutableListOf<JsonObject>()
        val router = WsRequestRouter(sender = link.sender(), onEvent = { synchronized(events) { events.add(it) } })

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)

        // 桥侧大量 reportError 都不带 requestType（ws-sessions.ts / ws-models.ts / ws-hitl.ts …）。
        // 这条 error 指向谁完全不可知 —— 绝不能拿它打死一条无关的在途请求。
        router.onFrame(errorReply("speech/busy"))
        delay(200)

        assertEquals("无主的 error 不许失败任何在途请求", 1, router.pendingCount())
        assertEquals("无主的 error 要进事件出口留痕", 1, synchronized(events) { events.size })

        // 真正的应答来了照常完成。
        router.onFrame(sessionsReply())
        withTimeout(2_000) { job.await() }
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `requestType 有值但不是当前在途的 type 时也不归属`() = case {
        val link = FakeLink()
        val events = mutableListOf<JsonObject>()
        val router = WsRequestRouter(sender = link.sender(), onEvent = { synchronized(events) { events.add(it) } })

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)

        router.onFrame(errorReply("NOT_IMPLEMENTED", requestType = WsClientFrame.MESSAGE))
        delay(200)
        assertEquals("别人的 error 不许打死 sessions", 1, router.pendingCount())
        assertEquals(1, synchronized(events) { events.size })

        router.onFrame(sessionsReply())
        withTimeout(2_000) { job.await() }
    }

    // ---------------------------------------------------------------- 超时与清理

    @Test
    fun `超时只失败本次请求 并且清空在途表`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender(), timeoutMillis = { 120 })

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)

        try {
            withTimeout(2_000) { job.await() }
            fail("超时必须抛异常")
        } catch (failure: DshApiException) {
            assertEquals("WS_TIMEOUT", failure.code)
            assertEquals(408, failure.status)
        }
        assertEquals("超时后不许 pending 泄漏", 0, router.pendingCount())
    }

    @Test
    fun `发送失败时抛 WS_SEND_FAILED 且不留在途`() = case {
        val router = WsRequestRouter(sender = { false })
        try {
            router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS)
            fail("发送失败必须抛异常")
        } catch (failure: DshApiException) {
            assertEquals("WS_SEND_FAILED", failure.code)
        }
        assertEquals(0, router.pendingCount())
    }

    // ---------------------------------------------------------------- 推送分流

    @Test
    fun `event 帧进事件出口 不被当成应答`() = case {
        val link = FakeLink()
        val events = mutableListOf<JsonObject>()
        val router = WsRequestRouter(sender = link.sender(), onEvent = { synchronized(events) { events.add(it) } })

        router.onFrame(
            buildJsonObject {
                put("kind", "event")
                put(
                    "data",
                    buildJsonObject {
                        put("id", "e-1")
                        put("type", "chat.turn.start")
                        put("time", 1L)
                    },
                )
            },
        )
        assertEquals(1, synchronized(events) { events.size })
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `迟到帧命中不到在途时改为走 kind 分支 而不是直接丢`() = case {
        val link = FakeLink()
        val router = WsRequestRouter(sender = link.sender())

        val job = async { router.request(WsClientFrame.SESSIONS, WsServerFrame.SESSIONS) }
        awaitSent(link, 1)

        // 「桥只补了一半回显」：这条帧带了 requestId，但不是我要的那个。
        val halfEchoed = buildJsonObject {
            put("kind", "sessions")
            put("data", buildJsonObject { put(WsFrames.REQUEST_ID, JsonPrimitive("someone-else")) })
        }
        router.onFrame(halfEchoed)

        val result = withTimeout(2_000) { job.await() }
        assertEquals(WsServerFrame.SESSIONS, WsFrames.kindOf(result))
    }

    /** 等到至少 [count] 个帧发出去，避免 `async` 还没跑到 sender 就注入回帧。 */
    private suspend fun awaitSent(link: FakeLink, count: Int) {
        repeat(200) {
            if (link.sentTypes().size >= count) return
            delay(10)
        }
        assertEquals("等不到第 $count 个出站帧", count, link.sentTypes().size)
    }
}
