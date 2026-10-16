package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死「`text` 与 `content` 二选一」这条线上格式契约。
 *
 * WebView 版聊天页手写请求体时踩过这个坑：一开始「两个都发」（想当然地以为 content
 * 是补充），服务端会当成两条内容，附件就重了。原生 [ChatContentParts.messageBody] 的
 * 分支很清楚 —— 单个非空 text 走 `text`，其他组合走 `content`。
 *
 * 页面侧用 JS 复刻了同一分支，所以这里把原生这侧钉住，作为对照基准。
 */
class MessageBodyShapeTest {

    private val body = { parts: List<PromptPart> ->
        ChatContentParts.messageBody(parts, "queue", "UTC", "cid")
    }

    @Test
    fun singleTextPartUsesTheLegacyTextField() {
        val json = body(listOf(PromptPart.TextPart("你好")))
        assertEquals("你好", json.jsonObject["text"]?.jsonPrimitive?.content)
        assertFalse("单段文本不该带 content", json.jsonObject.containsKey("content"))
    }

    @Test
    fun textWithImageUsesContentArray() {
        val json = body(
            listOf(
                PromptPart.TextPart("看这个"),
                PromptPart.ImagePart("image/png", "AAAA"),
            )
        )
        assertFalse("有附件时不该再发 text", json.jsonObject.containsKey("text"))
        val parts = json.jsonObject["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", parts[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun attachmentOnlyStillUsesContentArray() {
        val json = body(listOf(PromptPart.FilePart("receipt-1")))
        assertFalse(json.jsonObject.containsKey("text"))
        val parts = json.jsonObject["content"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("file", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun blankTextIsDroppedRatherThanSent() {
        val json = body(
            listOf(
                PromptPart.TextPart("   "),
                PromptPart.ImagePart("image/png", "AAAA"),
            )
        )
        val parts = json.jsonObject["content"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("image", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun emptyContentIsRejectedLocally() {
        // 与其发一个服务端必然拒绝的空 prompt，不如本地就报错。
        assertThrows(IllegalArgumentException::class.java) {
            body(listOf(PromptPart.TextPart("  ")))
        }
    }

    @Test
    fun modeAndClientFieldsAlwaysTravel() {
        val json = body(listOf(PromptPart.TextPart("hi")))
        assertEquals("queue", json.jsonObject["mode"]?.jsonPrimitive?.content)
        assertEquals("UTC", json.jsonObject["clientTimeZone"]?.jsonPrimitive?.content)
        assertEquals("cid", json.jsonObject["clientRequestId"]?.jsonPrimitive?.content)
    }
}
