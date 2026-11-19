package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.supportsKernelCall
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
        assertEquals(listOf("dsh-mobile-v1", "dsh-auth.tok-123", "dshrb-v1"), protocols)
        assertEquals("dsh-mobile-v1", protocols.first())
    }

    @Test
    fun `配对子协议清单用 dsh-pair 前缀`() {
        assertEquals(
            listOf("dsh-mobile-v1", "dsh-pair.abc123", "dshrb-v1"),
            WsSubprotocol.forPairing("abc123"),
        )
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

    // ───────────────────────── 背压告知（connection-resync-required） ─────────────────────────

    @Test
    fun `resync 帧的 kind 与桥侧字面量一致`() {
        // 写成 connection.resyncRequired（老通道那种带点的拼法）会让这帧永远认不出来，
        // 表现为「丢了帧但什么也不发生」—— 这条断言就是为了防这个。
        assertEquals("connection-resync-required", WsServerFrame.RESYNC_REQUIRED)
    }

    @Test
    fun `resync 帧能读出丢帧数与原因`() {
        val frame = WsFrames.decode(
            """{"kind":"connection-resync-required","data":{"dropped":7,"reason":"backpressure"}}""",
        )!!
        assertEquals(7, WsFrames.resyncDroppedOf(frame))
        assertEquals("backpressure", WsFrames.resyncReasonOf(frame))
    }

    @Test
    fun `resync 帧字段缺失时给 null 而不是 0`() {
        // 给 0 会让诊断日志写成「丢了 0 条却要求重取」，反而更难查；
        // null 明确表示「这个数字不可信/没给」。
        val frame = WsFrames.decode("""{"kind":"connection-resync-required","data":{}}""")!!
        assertNull(WsFrames.resyncDroppedOf(frame))
        assertNull(WsFrames.resyncReasonOf(frame))
    }

    @Test
    fun `resync 帧的计数不是数字时给 null`() {
        val frame = WsFrames.decode(
            """{"kind":"connection-resync-required","data":{"dropped":"many","reason":""}}""",
        )!!
        assertNull(WsFrames.resyncDroppedOf(frame))
        assertNull(WsFrames.resyncReasonOf(frame))
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

    // ───────────────────── 通用内核调用面（桥 issue #26 阶段 5）─────────────────────

    @Test
    fun `内核调用四帧常量与桥侧一致`() {
        assertEquals("dshrb-v1", WsSubprotocol.KERNEL_CALL_PROTOCOL)
        assertEquals("kernel-call", WsClientFrame.KERNEL_CALL)
        assertEquals("kernel-call-cancel", WsClientFrame.KERNEL_CALL_CANCEL)
        assertEquals("kernel-call-result", WsServerFrame.KERNEL_CALL_RESULT)
        assertEquals("kernel-endpoints", WsServerFrame.KERNEL_ENDPOINTS)
    }

    @Test
    fun `内核子协议追加在清单末尾且不夺首项`() {
        // 桥按请求头清单判定是否启用四帧，握手回执仍是 dsh-mobile-v1；
        // 首项一旦被动过，连接会被 close(4001) 且看起来像令牌无效。
        assertEquals("dshrb-v1", WsSubprotocol.forToken("tok-123").last())
        assertEquals("dshrb-v1", WsSubprotocol.forPairing("abc123").last())
        assertEquals("dsh-mobile-v1", WsSubprotocol.forToken("tok-123").first())
    }

    @Test
    fun `kernel-call 无参数时省略 args 字段`() {
        // asyncapi：无参数时省略 args。空对象也按无参数处理，别给内核递多余键。
        val frame = WsFrames.kernelCall("call-1", "terminal/exec")
        assertEquals("kernel-call", WsFrames.typeOf(frame))
        assertEquals("call-1", frame["callId"]?.jsonPrimitive?.content)
        assertEquals("terminal/exec", frame["endpoint"]?.jsonPrimitive?.content)
        assertNull(frame["args"])
        assertNull(frame["requestId"])
    }

    @Test
    fun `kernel-call 携带参数时把 args 放在顶层`() {
        val args = buildJsonObject { put("command", JsonPrimitive("echo hi")) }
        val frame = WsFrames.kernelCall("call-2", "terminal/exec", args)
        assertEquals(args, frame["args"]?.jsonObject)
    }

    @Test
    fun `kernel-call-cancel 只带类型与 callId`() {
        val frame = WsFrames.kernelCallCancel("call-3")
        assertEquals("kernel-call-cancel", WsFrames.typeOf(frame))
        assertEquals("call-3", frame["callId"]?.jsonPrimitive?.content)
        assertNull(frame["endpoint"])
        assertNull(frame["requestId"])
    }

    @Test
    fun `parseKernelCallResult 解析成功结果并保留 value`() {
        val raw = """
            {"kind":"kernel-call-result","data":{"callId":"c-1","ok":true,
             "value":{"stdout":"hi","exitCode":0}}}
        """.trimIndent()
        val result = WsFrames.parseKernelCallResult(WsFrames.decode(raw)!!)!!
        assertEquals("c-1", result.callId)
        assertTrue(result.ok)
        assertNull(result.error)
        assertEquals("hi", result.value?.jsonObject?.get("stdout")?.jsonPrimitive?.content)
    }

    @Test
    fun `parseKernelCallResult 解析 ok=false 的错误形状`() {
        val raw = """
            {"kind":"kernel-call-result","data":{"callId":"c-2","ok":false,
             "error":{"code":"METHOD_NOT_ALLOWED","message":"endpoint not allowed"}}}
        """.trimIndent()
        val result = WsFrames.parseKernelCallResult(WsFrames.decode(raw)!!)!!
        assertFalse(result.ok)
        assertEquals("METHOD_NOT_ALLOWED", result.error?.code)
        assertEquals("endpoint not allowed", result.error?.message)
        assertNull(result.value)
    }

    @Test
    fun `parseKernelCallResult 形状不完整时返回 null`() {
        // 这些帧继续用只会让调用方永远等一个不会来的结果，宁可当坏帧丢掉。
        val bad = listOf(
            """{"kind":"kernel-call-result"}""",
            """{"kind":"kernel-call-result","data":"oops"}""",
            """{"kind":"kernel-call-result","data":{"ok":true}}""",
            """{"kind":"kernel-call-result","data":{"callId":"","ok":true}}""",
            """{"kind":"kernel-call-result","data":{"callId":"c-1"}}""",
            """{"kind":"kernel-call-result","data":{"callId":"c-1","ok":"yes"}}""",
            """{"kind":"kernel-call-result","data":{"callId":"c-1","ok":false,"error":"oops"}}""",
            """{"kind":"kernel-call-result","data":{"callId":"c-1","ok":false,"error":{"message":"m"}}}""",
        )
        bad.forEach { raw -> assertNull(raw, WsFrames.parseKernelCallResult(WsFrames.decode(raw)!!)) }
    }

    @Test
    fun `内核调用错误码字面量与桥侧一致`() {
        // 授权/参数失败也在结果帧里（ok:false），这些码会直接展示给用户，不能漂。
        val codes = listOf(
            "METHOD_NOT_ALLOWED",
            "kernel/endpoint-unknown",
            "gateway/arguments-invalid",
            "call/in-flight",
            "call/cancelled",
            "call/not-found",
        )
        codes.forEach { code ->
            val raw = """{"kind":"kernel-call-result","data":{"callId":"c","ok":false,"error":{"code":"$code","message":"m"}}}"""
            assertEquals(code, WsFrames.parseKernelCallResult(WsFrames.decode(raw)!!)?.error?.code)
        }
    }

    @Test
    fun `parseKernelEndpoints 解析目录并容忍未知字段`() {
        val raw = """
            {"kind":"kernel-endpoints","data":{"endpoints":[
              {"endpoint":"terminal/exec","scope":"terminal","stream":false,
               "wires":["command","cwd"],"summary":"执行命令","futureField":1},
              {"endpoint":"agentTeams/list"}
            ],"futureTop":true}}
        """.trimIndent()
        val endpoints = WsFrames.parseKernelEndpoints(WsFrames.decode(raw)!!)!!
        assertEquals(2, endpoints.size)
        assertEquals("terminal/exec", endpoints[0].endpoint)
        assertEquals("terminal", endpoints[0].scope)
        assertFalse(endpoints[0].stream)
        assertEquals(listOf("command", "cwd"), endpoints[0].wires)
        assertEquals("执行命令", endpoints[0].summary)
        // 缺省字段按宽松默认处理：目录元素形状尚未冻结，不能因少字段就整批丢掉。
        assertEquals("agentTeams/list", endpoints[1].endpoint)
        assertEquals("", endpoints[1].scope)
        assertFalse(endpoints[1].stream)
        assertTrue(endpoints[1].wires.isEmpty())
        assertEquals("", endpoints[1].summary)
    }

    @Test
    fun `parseKernelEndpoints 缺目录或非数组时返回 null`() {
        val bad = listOf(
            """{"kind":"kernel-endpoints"}""",
            """{"kind":"kernel-endpoints","data":"oops"}""",
            """{"kind":"kernel-endpoints","data":{"endpoints":"oops"}}""",
            """{"kind":"kernel-call-result","data":{"endpoints":[]}}""",
        )
        bad.forEach { raw -> assertNull(raw, WsFrames.parseKernelEndpoints(WsFrames.decode(raw)!!)) }
    }

    @Test
    fun `parseKernelEndpoints 跳过缺少 endpoint 的元素`() {
        val raw = """{"kind":"kernel-endpoints","data":{"endpoints":[{},{"endpoint":""},{"endpoint":"terminal/exec"}]}}"""
        val endpoints = WsFrames.parseKernelEndpoints(WsFrames.decode(raw)!!)!!
        assertEquals(1, endpoints.size)
        assertEquals("terminal/exec", endpoints[0].endpoint)
    }

    @Test
    fun `supportsKernelCall 判据只看能力位`() {
        // 缺位 = 入口整段不组合、一帧不发；老桥不认识 dshrb-v1，永远不会发这个位。
        assertFalse(supportsKernelCall(emptySet()))
        assertFalse(supportsKernelCall(setOf("history-v1", "session-list-v1")))
        assertTrue(supportsKernelCall(setOf("kernel-call-v1")))
    }
}
