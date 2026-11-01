package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.AdaptiveMessagePort
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.PromptPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发图通道按体积分流（issue #15）。
 *
 * 判据是「**一开始就选对通道**」，而不是「先发 WS、失败了再回落」：
 * - 图片总量在预算内 → 走 WS（省一次 HTTP 建连）；
 * - 图片总量超预算 → **直接**走 REST，WS 一次都不许试。
 *
 * 为什么第二条要这么严：WS 单帧上限 1 MiB，超了桥在**读到帧头**就 `close(1009)`。
 * 先发一遍再回落，用户要等满一次失败的往返，大图的 base64 还要多编码、多传一遍。
 * 更糟的是这条路径会掩盖真实原因 —— #12 之前的表现就是「发原图等 15 秒然后消息没了」。
 */
class AdaptiveMessagePortTest {

    private class Recorder(private val label: String, private val failure: DshApiException? = null) : MessagePort {
        val sent = mutableListOf<List<PromptPart>>()

        override suspend fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean) {
            sent += parts
            failure?.let { throw it }
        }

        override suspend fun attachment(sessionId: String, attachmentId: String): ByteArray =
            error("$label 不该被问到附件：${attachmentId}")

        override fun toString(): String = label
    }

    private val ready: () -> ChannelState = { ChannelState.WsReady }

    private fun imageOf(rawBytes: Long): PromptPart.ImagePart =
        PromptPart.ImagePart(
            mediaType = "image/jpeg",
            data = ChatContentParts.base64(ByteArray(rawBytes.toInt())),
            name = "photo.jpg",
        )

    @Test
    fun `小图走 WS，一个字节都不必过 REST`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val parts = listOf(PromptPart.TextPart("看这张"), imageOf(120 * 1024))

        AdaptiveMessagePort(ws, rest, ready).sendMessage("s-1", parts, steer = false)

        assertEquals("小图应该走 WS", 1, ws.sent.size)
        assertTrue("小图不该碰 REST，实际 ${rest.sent.size} 次", rest.sent.isEmpty())
    }

    @Test
    fun `超预算的大图直接走 REST，不先试一次注定失败的 WS 帧`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val parts = listOf(PromptPart.TextPart("看这张"), imageOf(2 * 1024 * 1024))

        AdaptiveMessagePort(ws, rest, ready).sendMessage("s-1", parts, steer = false)

        assertTrue("大图不该走 WS，实际发了 ${ws.sent.size} 次", ws.sent.isEmpty())
        assertEquals("大图应该原样交给 REST（不压缩、不裁剪）", 1, rest.sent.size)
        assertEquals("发出去的 parts 必须与入参完全一致", parts, rest.sent.single())
    }

    @Test
    fun `多张中等图合起来超预算也走 REST`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        // 三张各 200 KiB：单看每张都「不到 500K」，但 base64 合计已经越过 500 KiB 预算。
        val parts = listOf(imageOf(200 * 1024), imageOf(200 * 1024), imageOf(200 * 1024))

        AdaptiveMessagePort(ws, rest, ready).sendMessage("s-1", parts, steer = false)

        assertTrue("三张 200K 合计超预算，不该走 WS", ws.sent.isEmpty())
        assertEquals(1, rest.sent.size)
    }

    @Test
    fun `纯文本消息照旧走 WS`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")

        AdaptiveMessagePort(ws, rest, ready).sendMessage("s-1", listOf(PromptPart.TextPart("你好")), false)

        assertEquals(1, ws.sent.size)
        assertTrue(rest.sent.isEmpty())
    }

    @Test
    fun `通道已经降级成 REST 时，小图也不必再问 WS`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val down: () -> ChannelState = { ChannelState.WsDown("桥重启了") }

        AdaptiveMessagePort(ws, rest, down).sendMessage("s-1", listOf(imageOf(64 * 1024)), false)

        assertTrue("降级后不该碰 WS", ws.sent.isEmpty())
        assertEquals(1, rest.sent.size)
    }
}
