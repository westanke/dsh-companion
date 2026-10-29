package io.github.westanke.dshcompanion.data.ws

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧编解码与协议常量。
 *
 * 这里钉的都是**写错一个字符就静默超时**的地方：`type`/`kind` 字面量、`requestId` 的位置
 * （顶层还是 `data` 里）、子协议顺序（`dsh-mobile-v1` 必须在首项）、以及 `hello` 的宽容解析。
 */
class WsFramesTest {

    // ───────────────────────── 常量与桥侧对齐 ─────────────────────────

    @Test
    fun `路径与子协议常量与桥侧一致`() {
        assertEquals("/ws/mobile", WS_PATH)
        assertEquals("dsh-mobile-v1", WsSubprotocol.PROTOCOL)
        assertEquals("dsh-pair.", WsSubprotocol.PAIR_PREFIX)
        assertEquals("dsh-auth.", WsSubprotocol.AUTH_PREFIX)
    }

    @Test
    fun `令牌子协议清单首项必须是 dsh-mobile-v1`() {
        // 桥拿 webSocket.protocol 与 'dsh-mobile-v1' 逐字比较（ws-handler.ts:233）。
        // 首项写错 = 每次连接都被 close(4001)，而且看起来像「令牌无效」，极难查。
        val protocols = WsSubprotocol.forToken("tok-123")
        assertEquals(listOf("dsh-mobile-v1", "dsh-auth.tok-123"), protocols)
        assertEquals("dsh-mobile-v1", protocols.first())
    }

    @Test
    fun `配对子协议清单用 dsh-pair 前缀`() {
        assertEquals(listOf("dsh-mobile-v1", "dsh-pair.abc123"), WsSubprotocol.forPairing("abc123"))
    }

    @Test
    fun `客户端帧 type 字面量与桥侧一致`() {
        assertEquals("ping", WsClientFrame.PING)
        assertEquals("subscribe", WsClientFrame.SUBSCRIBE)
        assertEquals("unsubscribe", WsClientFrame.UNSUBSCRIBE)
        assertEquals("sessions", WsClientFrame.SESSIONS)
        assertEquals("history", WsClientFrame.HISTORY)
        assertEquals("session-create", WsClientFrame.SESSION_CREATE)
        assertEquals("session-rename", WsClientFrame.SESSION_RENAME)
        assertEquals("session-archive", WsClientFrame.SESSION_ARCHIVE)
        assertEquals("session-cancel", WsClientFrame.SESSION_CANCEL)
        assertEquals("message", WsClientFrame.MESSAGE)
        assertEquals("attachment", WsClientFrame.ATTACHMENT)
    }

    @Test
    fun `服务端帧 kind 字面量与桥侧一致`() {
        assertEquals("hello", WsServerFrame.HELLO)
        assertEquals("pong", WsServerFrame.PONG)
        assertEquals("event", WsServerFrame.EVENT)
        assertEquals("subscribed", WsServerFrame.SUBSCRIBED)
        assertEquals("sent", WsServerFrame.SENT)
        assertEquals("error", WsServerFrame.ERROR)
        assertEquals("session-created", WsServerFrame.SESSION_CREATED)
        assertEquals("session-renamed", WsServerFrame.SESSION_RENAMED)
        assertEquals("session-archived", WsServerFrame.SESSION_ARCHIVED)
        assertEquals("session-cancelled", WsServerFrame.SESSION_CANCELLED)
    }

    @Test
    fun `必需能力位就是 P2 三域的 REST 对等面`() {
        assertEquals(
            setOf("session-list-v1", "history-v1", "turn-cancel-v1"),
            REQUIRED_CAPABILITIES_FOR_WS,
        )
    }

    // ───────────────────────── 请求帧构造 ─────────────────────────

    @Test
    fun `request 把 requestId 放在顶层`() {
        val frame = WsFrames.request("sessions", "rid-1", buildJsonObject { put("archived", JsonPrimitive(false)) })
        assertEquals("sessions", frame["type"]?.jsonPrimitive?.content)
        // 顶层！放进 data 里服务端读不到（ws-goals.ts 的 echoRequestId 读 frame.requestId）。
        assertEquals("rid-1", frame["requestId"]?.jsonPrimitive?.content)
        assertFalse("requestId 不许塞进 data", frame["data"] is JsonObject)
    }

    @Test
    fun `request 把 payload 摊平到顶层`() {
        // 桥侧直接读 frame.sessionId / frame.title，不读 frame.data.sessionId。
        val frame = WsFrames.request(
            "session-rename",
            "rid-2",
            buildJsonObject {
                put("sessionId", JsonPrimitive("s-1"))
                put("title", JsonPrimitive("新标题"))
            },
        )
        assertEquals("s-1", frame["sessionId"]?.jsonPrimitive?.content)
        assertEquals("新标题", frame["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `command 不带 requestId`() {
        val frame = WsFrames.command("ping")
        assertEquals("ping", frame["type"]?.jsonPrimitive?.content)
        assertNull(frame["requestId"])
    }

    @Test
    fun `编码后是裸 JSON 而不是包裹结构`() {
        val text = WsFrames.encode(WsFrames.command("ping"))
        assertTrue("应以 { 开头：$text", text.trimStart().startsWith("{"))
        assertTrue("应含 type 字段：$text", text.contains("\"type\""))
    }

    // ───────────────────────── 解码 ─────────────────────────

    @Test
    fun `decode 接受合法帧`() {
        val frame = WsFrames.decode("""{"kind":"pong","data":{"at":1}}""")
        assertNotNull(frame)
        assertEquals("pong", WsFrames.kindOf(frame!!))
    }

    @Test
    fun `decode 对非法 JSON 返回 null 而不是抛异常`() {
        // 回调里抛异常会把 OkHttp 的读线程打掉，整条连接静默失活。
        assertNull(WsFrames.decode("not json"))
        assertNull(WsFrames.decode(""))
        assertNull(WsFrames.decode("[]"))
        assertNull(WsFrames.decode("123"))
    }

    @Test
    fun `kindOf 与 typeOf 对缺字段返回 null`() {
        assertNull(WsFrames.kindOf(JsonObject(emptyMap())))
        assertNull(WsFrames.typeOf(JsonObject(emptyMap())))
        assertEquals("hello", WsFrames.kindOf(JsonObject(mapOf("kind" to JsonPrimitive("hello")))))
    }

    @Test
    fun `kindOf 忽略空字符串`() {
        assertNull(WsFrames.kindOf(JsonObject(mapOf("kind" to JsonPrimitive("")))))
    }

    @Test
    fun `requestId 优先从 data 里读 也兼容顶层`() {
        val inData = WsFrames.decode("""{"kind":"sessions","data":{"requestId":"a"}}""")!!
        assertEquals("a", WsFrames.requestIdOf(inData))

        val onTop = WsFrames.decode("""{"kind":"sessions","requestId":"b"}""")!!
        assertEquals("b", WsFrames.requestIdOf(onTop))
    }

    @Test
    fun `无 requestId 的帧返回 null 以走推送路径`() {
        listOf(
            """{"kind":"hello","data":{}}""",
            """{"kind":"pong","data":{"at":1}}""",
            """{"kind":"event","data":{"id":"e1","type":"chat.message.delta"}}""",
        ).forEach { raw ->
            assertNull("这帧不该有 requestId：$raw", WsFrames.requestIdOf(WsFrames.decode(raw)!!))
        }
    }

    @Test
    fun `dataOf 对缺 data 给空对象`() {
        val frame = WsFrames.decode("""{"kind":"sent"}""")!!
        assertEquals(0, WsFrames.dataOf(frame).size)
    }

    // ───────────────────────── hello 解析 ─────────────────────────

    @Test
    fun `parseHello 解析全部字段`() {
        val raw = """
            {"kind":"hello","data":{
              "protocol":3,
              "capabilities":["session-list-v1","history-v1","turn-cancel-v1"],
              "authenticated":true,
              "device":{"id":"dev-1","name":"V2080A","scopes":["chat"],"rootIds":["root-1"]},
              "port":7801,
              "clients":2
            }}
        """.trimIndent()
        val hello = WsFrames.parseHello(WsFrames.decode(raw)!!)!!
        assertEquals(3, hello.protocol)
        assertEquals(setOf("session-list-v1", "history-v1", "turn-cancel-v1"), hello.capabilities)
        assertTrue(hello.authenticated)
        assertEquals("dev-1", hello.deviceId)
        assertEquals("V2080A", hello.deviceName)
        assertEquals(listOf("chat"), hello.scopes)
        assertEquals(listOf("root-1"), hello.rootIds)
        assertEquals(7801, hello.port)
        assertEquals(2, hello.clients)
    }

    @Test
    fun `parseHello 对缺字段给默认值而不是 null`() {
        // 桥是先鉴权再发 hello，所以 hello 到达本身就证明通道可用。
        // 载荷被中间层截断时应该当「能力未知」（→ 缺位 → WsDegraded），不是判连接失败。
        val hello = WsFrames.parseHello(WsFrames.decode("""{"kind":"hello","data":{}}""")!!)!!
        assertEquals(0, hello.protocol)
        assertTrue(hello.capabilities.isEmpty())
        assertFalse(hello.authenticated)
        assertNull(hello.deviceId)
        assertNull(hello.port)
    }

    @Test
    fun `parseHello 对非对象 data 返回 null`() {
        // 这一种是真的协议坏了，继续用会更糟。
        assertNull(WsFrames.parseHello(WsFrames.decode("""{"kind":"hello","data":"oops"}""")!!))
        assertNull(WsFrames.parseHello(WsFrames.decode("""{"kind":"hello"}""")!!))
    }

    @Test
    fun `parseHello 容忍 capabilities 里混入非字符串项`() {
        val raw = """{"kind":"hello","data":{"protocol":3,"capabilities":["history-v1",null,3,""]}}"""
        val hello = WsFrames.parseHello(WsFrames.decode(raw)!!)!!
        assertEquals(setOf("history-v1"), hello.capabilities)
    }

    @Test
    fun `parseHello 与 decideAfterHello 串起来能判出 WsReady`() {
        val raw = """
            {"kind":"hello","data":{"protocol":3,
             "capabilities":["session-list-v1","history-v1","turn-cancel-v1"],"authenticated":true}}
        """.trimIndent()
        val hello = WsFrames.parseHello(WsFrames.decode(raw)!!)!!
        val state = decideAfterHello(hello.protocol, hello.capabilities, REQUIRED_CAPABILITIES_FOR_WS)
        assertEquals(ChannelState.WsReady, state)
    }

    @Test
    fun `hello 缺能力位时串起来判出 WsDegraded`() {
        val raw = """{"kind":"hello","data":{"protocol":3,"capabilities":["session-list-v1"]}}"""
        val hello = WsFrames.parseHello(WsFrames.decode(raw)!!)!!
        val state = decideAfterHello(hello.protocol, hello.capabilities, REQUIRED_CAPABILITIES_FOR_WS)
        assertTrue(state is ChannelState.WsDegraded)
    }

    @Test
    fun `解码进 JsonObject 后未知字段不丢`() {
        // 桥一直在加字段（projectionState / asOfSeq / replayed）。宽 JsonObject 是刻意的：
        // 逐帧 data class 会让服务端加字段就必须跟着发版。
        val frame = WsFrames.decode("""{"kind":"event","data":{"id":"e1"},"futureField":42}""")!!
        assertEquals(42, frame["futureField"]?.jsonPrimitive?.content?.toInt())
        assertEquals("e1", WsFrames.dataOf(frame)["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `编码再解码往返一致`() {
        val original = WsFrames.request(
            "history",
            "rid-9",
            buildJsonObject {
                put("sessionId", JsonPrimitive("s-1"))
                put("beforeSeq", JsonPrimitive(42))
            },
        )
        val round = WsFrames.decode(WsFrames.encode(original))!!
        assertEquals(original.jsonObject, round)
    }
}
