package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KernelCallClient] 的配对与生命周期测试。
 *
 * 这里是**手工构帧**、不走 MockWebServer：`kernel-call-result` 被桥明确排除在
 * `requestId` 回显之外（`REQUEST_ID_ECHO_EXCLUDED_KINDS`），所以真实链路上
 * 唯一的关联键就是 `callId`。本文件钉的正是「只按 callId 也能配上、且帧被拒时不留在途请求」。
 */
class KernelCallClientTest {

    /** 同样的 supervisorScope 口径见 [WsRequestRouterTest]：失败用例不该把父作用域取消掉。 */
    private fun case(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking<Unit> { supervisorScope { body() } }

    /** 记录发出的帧，并允许测试手工注入「服务端回帧」。 */
    private class FakeLink(private val accept: Boolean = true) {
        val sent = mutableListOf<JsonObject>()

        fun sender(): (JsonObject) -> Boolean = { frame ->
            synchronized(sent) { sent.add(frame) }
            accept
        }

        fun last(): JsonObject? = synchronized(sent) { sent.lastOrNull() }
    }

    private suspend fun awaitSent(link: FakeLink): JsonObject =
        withTimeout(2_000) {
            while (link.last() == null) delay(1)
            link.last()!!
        }

    private fun resultFrame(callId: String, ok: Boolean, body: String): JsonObject =
        WsFrames.decode(
            """{"kind":"kernel-call-result","data":{"callId":"$callId","ok":$ok$body}}""",
        )!!

    @Test
    fun `call 只按 callId 配对结果帧且不依赖 requestId`() = case {
        val link = FakeLink()
        val client = KernelCallClient(sender = link.sender(), newCallId = { "call-fixed" })

        val job = async { client.call("terminal/exec") }
        val frame = awaitSent(link)
        assertEquals("kernel-call", WsFrames.typeOf(frame))
        assertEquals("call-fixed", frame["callId"]?.jsonPrimitive?.content)
        assertNull(frame[WsFrames.REQUEST_ID])

        client.onFrame(resultFrame("call-fixed", ok = true, body = ",\"value\":\"done\""))
        val result = job.await()
        assertTrue(result.ok)
        assertEquals("done", result.value?.jsonPrimitive?.content)
        assertEquals(0, client.pendingCount())
    }

    @Test
    fun `业务失败是结果不是异常`() = case {
        val link = FakeLink()
        val client = KernelCallClient(sender = link.sender(), newCallId = { "call-denied" })

        val job = async { client.call("terminal/exec") }
        awaitSent(link)
        client.onFrame(
            resultFrame(
                "call-denied",
                ok = false,
                body = ",\"error\":{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"not in whitelist\"}",
            ),
        )
        val result = job.await()
        assertFalse(result.ok)
        assertEquals("METHOD_NOT_ALLOWED", result.error?.code)
        assertNull(result.value)
    }

    @Test
    fun `目录帧同步到 StateFlow 并回调`() = case {
        var pushed: List<KernelEndpoint>? = null
        val client = KernelCallClient(sender = { true }, onEndpoints = { pushed = it })

        val handled = client.onFrame(
            WsFrames.decode(
                """{"kind":"kernel-endpoints","data":{"endpoints":[{"endpoint":"terminal/exec","wires":["command"]}]}}""",
            )!!,
        )
        assertTrue(handled)
        assertEquals(listOf("terminal/exec"), client.endpoints.value.map { it.endpoint })
        assertEquals(listOf("terminal/exec"), pushed?.map { it.endpoint })
    }

    @Test
    fun `不认识的 kind 交回上层路由`() = case {
        val client = KernelCallClient(sender = { true })
        assertFalse(client.onFrame(WsFrames.decode("""{"kind":"event","data":{}}""")!!))
    }

    @Test
    fun `断线 failAll 让在途调用以 WS_CLOSED 失败`() = case {
        val link = FakeLink()
        val client = KernelCallClient(sender = link.sender())

        val job = async { client.call("terminal/exec") }
        awaitSent(link)
        client.failAll("WS_CLOSED", "WS 通道已关闭")

        val error = runCatching { job.await() }.exceptionOrNull()
        assertTrue(error is DshApiException)
        assertEquals("WS_CLOSED", (error as DshApiException).code)
        assertEquals(0, client.pendingCount())
    }

    @Test
    fun `帧没发出去时立刻失败且不留在表里`() = case {
        val client = KernelCallClient(sender = { false }, newCallId = { "c-send" })
        val error = runCatching { client.call("terminal/exec") }.exceptionOrNull()
        assertTrue(error is DshApiException)
        assertEquals("WS_NOT_READY", (error as DshApiException).code)
        assertEquals(0, client.pendingCount())
    }

    @Test
    fun `等不到结果时按 WS_TIMEOUT 失败`() = case {
        val client = KernelCallClient(sender = { true }, newCallId = { "c-timeout" }, timeoutMillis = 30)
        val error = runCatching { client.call("terminal/exec") }.exceptionOrNull()
        assertTrue(error is DshApiException)
        assertEquals("WS_TIMEOUT", (error as DshApiException).code)
        assertEquals(0, client.pendingCount())
    }

    @Test
    fun `cancel 发出 kernel-call-cancel 帧`() = case {
        val link = FakeLink()
        val client = KernelCallClient(sender = link.sender())
        assertTrue(client.cancel("c-cancel"))
        val frame = link.last()!!
        assertEquals("kernel-call-cancel", WsFrames.typeOf(frame))
        assertEquals("c-cancel", frame["callId"]?.jsonPrimitive?.content)
    }
}
