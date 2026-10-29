package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.PromptPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WsSessionPort] 与 [WsMessagePort] 的两处「最后一米」。
 *
 * 两条都是**参数/字段被悄悄丢掉**的缺陷，词汇表测试抓不到（帧名一直是对的）：
 *
 * 1. `session-create` 曾经不收 `agentPreset`、也不认 `workspaceId`，于是「走 WS 新建」和
 *    「走 REST 新建」是两个不同结果 —— App 只能让新建会话整体留在 REST。
 * 2. `history` 曾经把 `projections` 丢掉，于是首屏历史只能走 REST ——
 *    也就是说「打开会话」这条最热的路，P2 之后一直没真正用上 WS。
 *
 * 另外把 `sent` 这个共用 kind 的五个生产者钉一遍：现在有 `message`、`unsubscribe`、
 * `select-model`、`session-agent-preset`、`select-agent-preset`，判别全靠 `data.type`。
 * 少排一个的表现是「消息看着发出去了、其实只切了个预设」，排查时几乎想不到是这里。
 */
class WsSessionPortTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 让在途请求可以合法失败：supervisorScope 防止子协程失败取消整个测试体。 */
    private fun case(body: suspend CoroutineScope.() -> Unit) =
        runBlocking<Unit> { supervisorScope { body() } }

    /** 假连接：记录出站帧，允许注入**不带 requestId** 的回帧（模拟老桥）。 */
    private class Link {
        private val sent = mutableListOf<JsonObject>()

        val router: WsRequestRouter = WsRequestRouter(
            sender = { frame -> synchronized(sent) { sent += frame }; true },
            onEvent = { },
        )

        fun sentTypes(): List<String> = synchronized(sent) { sent.map { WsFrames.typeOf(it)!! } }

        fun sentData(index: Int): JsonObject = synchronized(sent) { sent[index] }

        suspend fun awaitSent(count: Int) {
            repeat(200) {
                if (sentTypes().size >= count) return
                delay(10)
            }
            assertEquals("等不到第 $count 个出站帧", count, sentTypes().size)
        }

        fun reply(kind: String, data: JsonObject) = router.onFrame(
            buildJsonObject {
                put("kind", kind)
                put("data", data)
            },
        )
    }

    // ------------------------------------------------------------- history

    @Test
    fun `首屏 history 把 projections 原样交出来（这是首屏能走 WS 的前提）`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.history("s-1") }
        link.awaitSent(1)
        assertEquals(WsClientFrame.HISTORY, link.sentTypes().single())
        assertNull("首屏不该带 beforeSeq", link.sentData(0)["beforeSeq"])

        link.reply(
            WsServerFrame.HISTORY,
            buildJsonObject {
                put("sessionId", "s-1")
                put("events", kotlinx.serialization.json.JsonArray(emptyList()))
                put("hasMore", false)
                put(
                    "projections",
                    buildJsonObject {
                        put("asOfSeq", 42)
                        put(
                            "values",
                            buildJsonObject {
                                put("todos", kotlinx.serialization.json.JsonArray(emptyList()))
                            },
                        )
                    },
                )
            },
        )
        val history = withTimeout(2_000) { job.await() }
        // 投影丢了就是待办/统计/WebView 三块面板整块变空 —— 所以这一条不是「顺带」。
        assertEquals(42, history.projections?.jsonObject?.get("asOfSeq")?.jsonPrimitive?.content?.toInt())
        assertTrue(
            "values 必须整块带过来",
            history.projections?.jsonObject?.get("values")?.jsonObject?.containsKey("todos") == true,
        )
        assertEquals(0, history.events.size)
    }

    @Test
    fun `翻页请求带 beforeSeq，且回帧没有 projections 时给 null`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.history("s-1", beforeSeq = 10) }
        link.awaitSent(1)
        assertEquals("10", link.sentData(0)["beforeSeq"]?.jsonPrimitive?.content)

        link.reply(
            WsServerFrame.HISTORY,
            buildJsonObject {
                put("sessionId", "s-1")
                put("events", kotlinx.serialization.json.JsonArray(emptyList()))
                put("hasMore", true)
            },
        )
        val history = withTimeout(2_000) { job.await() }
        // 桥刻意只给首屏带投影；翻页没有就是没有，别伪造一个空对象骗上层。
        assertNull(history.projections)
        assertTrue(history.hasMore)
    }

    // -------------------------------------------------------- createSession

    @Test
    fun `createSession 把 workspaceId 与两个可选项原样发出去，并返回新会话 id`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.createSession("ws-1", agentPreset = "preset-x", reuseBlank = false) }
        link.awaitSent(1)
        assertEquals(WsClientFrame.SESSION_CREATE, link.sentTypes().single())
        val payload = link.sentData(0)
        assertEquals("ws-1", payload["workspaceId"]?.jsonPrimitive?.content)
        assertEquals("preset-x", payload["agentPreset"]?.jsonPrimitive?.content)
        assertEquals("false", payload["reuseBlank"]?.jsonPrimitive?.content)
        // 客户端只认工作区：绝不自算「哪个根 + 相对路径」，那条路两侧规范化口径不同会建到别处。
        assertNull(payload["rootId"])
        assertNull(payload["cwd"])

        link.reply(
            WsServerFrame.SESSION_CREATED,
            buildJsonObject {
                put("sessionId", "sess-new")
                put("workspaceId", "ws-1")
                put("agentPreset", "preset-resolved")
            },
        )
        assertEquals("sess-new", withTimeout(2_000) { job.await() })
    }

    @Test
    fun `createSession 的可选项为 null 时不出现在帧上`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.createSession("ws-1") }
        link.awaitSent(1)
        val payload = link.sentData(0)
        assertTrue("未指定就别塞 null：桥按「键不存在」判默认值", !payload.containsKey("agentPreset"))
        assertTrue(!payload.containsKey("reuseBlank"))

        link.reply(WsServerFrame.SESSION_CREATED, buildJsonObject { put("sessionId", "sess-new") })
        assertEquals("sess-new", withTimeout(2_000) { job.await() })
    }

    @Test
    fun `session-created 缺 sessionId 时报坏帧而不是返回空串`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.createSession("ws-1") }
        link.awaitSent(1)
        link.reply(WsServerFrame.SESSION_CREATED, buildJsonObject { put("workspaceId", "ws-1") })

        val failure = runCatching { withTimeout(2_000) { job.await() } }.exceptionOrNull()
        assertEquals("WS_BAD_FRAME", (failure as? io.github.westanke.dshcompanion.data.DshApiException)?.code)
    }

    // --------------------------------------------------- selectAgentPreset

    @Test
    fun `切 agent 预设发 select-agent-preset 并按 data_type 配对自己的应答`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.selectAgentPreset("s-1", "preset-y") }
        link.awaitSent(1)
        assertEquals(WsClientFrame.SELECT_AGENT_PRESET, link.sentTypes().single())
        assertEquals("s-1", link.sentData(0)["sessionId"]?.jsonPrimitive?.content)
        assertEquals("preset-y", link.sentData(0)["agentPresetId"]?.jsonPrimitive?.content)

        // 应答 kind 是共用的 `sent`，只能靠 data.type 认。
        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_AGENT_PRESET)
                put("sessionId", "s-1")
                put("accepted", true)
                put("agentPresetId", "preset-y")
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }

    @Test
    fun `迟到的 select-agent-preset 确认不许把在途发消息确认掉`() = case {
        val link = Link()
        val messagePort = WsMessagePort(link.router, json) { "zh" }

        val job = async { messagePort.sendMessage("s-1", listOf(PromptPart.TextPart("你好"))) }
        link.awaitSent(1)

        // 字段长得几乎一样（sessionId + accepted），差别只在 type。
        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_AGENT_PRESET)
                put("sessionId", "s-1")
                put("accepted", true)
                put("agentPresetId", "preset-y")
            },
        )
        assertEquals("切预设的确认不许完成在途发消息", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.MESSAGE)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }

    @Test
    fun `发消息的确认不许完成在途切预设`() = case {
        val link = Link()
        val sessionPort = WsSessionPort(link.router, json)

        val job = async { sessionPort.selectAgentPreset("s-1", "preset-y") }
        link.awaitSent(1)

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.MESSAGE)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        assertEquals("message 的确认不许完成在途切预设", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_AGENT_PRESET)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }

    @Test
    fun `别的会话的切预设确认不许完成在途请求`() = case {
        val link = Link()
        val port = WsSessionPort(link.router, json)

        val job = async { port.selectAgentPreset("s-1", "preset-y") }
        link.awaitSent(1)

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_AGENT_PRESET)
                put("sessionId", "s-OTHER")
                put("accepted", true)
            },
        )
        assertEquals("会话 id 对不上就不是我的应答", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_AGENT_PRESET)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }
}
