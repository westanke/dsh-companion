package io.github.hakunm.deepseekharness.data

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 发图片 / 发文件的**线上格式**回归。
 *
 * 这些断言不是照着文档臆造的，而是钉住三件已经在真实环境里踩过的坑：
 * 1. 旧格式必须保持 —— 只发文本时仍然是 `{"text": ...}`，不能无脑换成 `content` 数组，
 *    否则已发布的老服务端插件会直接不认这条消息；
 * 2. 判别器是 `type`，而且 `mediaType` 这类"恰好等于默认值"的字段**不能被省略**
 *    （默认 Json 会把等于默认值的字段丢掉，那会发出一个没有 mediaType 的 image part）；
 * 3. 空内容必须在客户端就被拒绝，而不是发一个服务端必然报错的空 prompt。
 */
class ChatContentPartsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ——— 纯逻辑：请求体形状 ———

    @Test
    fun plainTextMessageKeepsTheLegacyBodyShape() {
        val body = ChatContentParts.messageBody(
            parts = listOf(PromptPart.TextPart("hello")),
            mode = "queue",
            clientTimeZone = "Asia/Shanghai",
            clientRequestId = "req-1",
        )

        assertEquals("hello", body["text"]?.jsonPrimitive?.content)
        assertFalse("纯文本不该出现 content 数组", body.containsKey("content"))
        assertEquals("queue", body["mode"]?.jsonPrimitive?.content)
        assertEquals("Asia/Shanghai", body["clientTimeZone"]?.jsonPrimitive?.content)
        assertEquals("req-1", body["clientRequestId"]?.jsonPrimitive?.content)
    }

    @Test
    fun mixedContentUsesTypedContentArrayInsteadOfTheLegacyTextKey() {
        val part = ChatContentParts.imagePart("image/png", "png-bytes".toByteArray())
        val body = ChatContentParts.messageBody(
            parts = listOf(PromptPart.TextPart("看看这个"), part, PromptPart.FilePart("receipt-1")),
            mode = "steer",
            clientTimeZone = "Asia/Shanghai",
            clientRequestId = "req-2",
        )

        assertFalse("带附件时不能再用旧格式", body.containsKey("text"))
        assertEquals("steer", body["mode"]?.jsonPrimitive?.content)
        val content = body["content"]!!.jsonArray
        assertEquals(3, content.size)

        val text = content[0].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.content)
        assertEquals("看看这个", text["text"]?.jsonPrimitive?.content)

        val image = content[1].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        // mediaType 恰好等于数据类的默认值；默认 Json 会把它省略掉，这条断言就是防这个。
        assertEquals("image/png", image["mediaType"]?.jsonPrimitive?.content)
        assertEquals(Base64.getEncoder().encodeToString("png-bytes".toByteArray()), image["data"]?.jsonPrimitive?.content)
        // name 没传，就不该出现这个键（也不要出现 "name":null）。
        assertFalse(image.containsKey("name"))

        val file = content[2].jsonObject
        assertEquals("file", file["type"]?.jsonPrimitive?.content)
        assertEquals("receipt-1", file["receiptId"]?.jsonPrimitive?.content)
        assertEquals("file part 只该有两个键", 2, file.size)
    }

    @Test
    fun imageOnlyMessageIsAllowedButWhitespaceOnlyContentIsRejected() {
        val imageOnly = ChatContentParts.messageBody(
            parts = listOf(ChatContentParts.imagePart("image/jpeg", "jpg".toByteArray(), "a.jpg")),
            mode = "queue",
            clientTimeZone = "UTC",
            clientRequestId = "req-3",
        )
        assertEquals(1, imageOnly["content"]!!.jsonArray.size)
        assertEquals("a.jpg", imageOnly["content"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)

        // 空列表、以及「只有一串空格」都算空内容：本地拒绝，别让它变成一个必然失败的空 prompt。
        assertThrows(IllegalArgumentException::class.java) {
            ChatContentParts.messageBody(emptyList(), "queue", "UTC", "req-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatContentParts.messageBody(listOf(PromptPart.TextPart("   ")), "queue", "UTC", "req-5")
        }
    }

    @Test
    fun imagePartRejectsOversizedBytesBeforeTheyReachTheNetwork() {
        val tooLarge = ByteArray(ChatContentParts.MAX_IMAGE_BYTES.toInt() + 1)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ChatContentParts.imagePart("image/png", tooLarge)
        }
        assertEquals("IMAGE_TOO_LARGE", failure.message)

        // 恰好等于上限是允许的（边界不能差一字节就拒）。
        val atLimit = ChatContentParts.imagePart("image/png", ByteArray(ChatContentParts.MAX_IMAGE_BYTES.toInt()))
        assertTrue(atLimit is PromptPart.ImagePart)
    }

    @Test
    fun base64IsStandardAlphabetAndUploadPayloadCarriesName() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0x3F)
        assertEquals(Base64.getEncoder().encodeToString(bytes), ChatContentParts.base64(bytes))
        assertFalse("必须是标准 base64，不是 URL-safe", ChatContentParts.base64(bytes).contains('-'))

        val payload = ChatContentParts.uploadPayload("hello".toByteArray(), "report.pdf")
        assertEquals("aGVsbG8=", payload["data"]?.jsonPrimitive?.content)
        assertEquals("report.pdf", payload["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun imageMediaTypePrefersTheDeclaredTypeAndFallsBackToTheExtension() {
        assertEquals("image/webp", ChatContentParts.imageMediaType("image/webp", "photo.bin"))
        assertEquals("image/jpeg", ChatContentParts.imageMediaType(null, "photo.JPEG"))
        assertEquals("image/png", ChatContentParts.imageMediaType("application/octet-stream", "shot.png"))
        // 选择器只给了个没有扩展名的 URI，兜底成 png 也好过发不出去。
        assertEquals("image/png", ChatContentParts.imageMediaType(null, "content://media/1234"))
    }

    @Test
    fun readAtMostKeepsSmallStreamsWholeAndFlagsOversizedOnes() {
        // 小于上限：原样读完，调用方按长度 <= limit 放行。
        val small = ByteArray(500) { it.toByte() }
        assertArrayEquals(small, ChatContentParts.readAtMost(1024, small.inputStream()))

        // 正好等于上限：也算读完（边界不能差一字节就拒）。
        val exact = ByteArray(1024) { 7 }
        assertArrayEquals(exact, ChatContentParts.readAtMost(1024, exact.inputStream()))

        // 超限：只读出 limit + 1 字节就停手 —— 多出的一字节是"还没读完"的证据，
        // 调用方据此拒绝，绝不会把截断的内容当成完整文件发出去。
        val huge = ByteArray(100_000) { 1 }
        val read = ChatContentParts.readAtMost(1024, huge.inputStream())
        assertEquals(1025, read.size)
        assertTrue("限读必须真的省内存，而不是读完再截断", read.size < huge.size)
    }

    // ——— 线上回归：真的发出去的请求 ———

    @Test
    fun sendMessagePostsTheLegacyTextBodyForPlainText() {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        client.sendMessage("session-1", "hello", steer = false)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/sessions/session-1/messages", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("hello", body["text"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("content"))
    }

    @Test
    fun sendMessagePostsTheContentArrayWhenAttachmentsArePresent() {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        client.sendMessage(
            "session-1",
            listOf(
                PromptPart.TextPart("看看这个"),
                ChatContentParts.imagePart("image/png", "png".toByteArray(), "shot.png"),
                PromptPart.FilePart("receipt-9"),
            ),
            steer = true,
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("steer", body["mode"]?.jsonPrimitive?.content)
        val content = body["content"]!!.jsonArray
        assertEquals(listOf("text", "image", "file"), content.map { it.jsonObject["type"]?.jsonPrimitive?.content })
        assertEquals("shot.png", content[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("receipt-9", content[2].jsonObject["receiptId"]?.jsonPrimitive?.content)
    }

    @Test
    fun uploadAttachmentPostsStandardBase64ToTheSessionScopedRoute() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"receiptId":"r-1","name":"report.pdf"}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val uploaded = client.uploadAttachment("session/1", "hello".toByteArray(), "report.pdf")

        assertEquals("r-1", uploaded.receiptId)
        assertEquals("report.pdf", uploaded.name)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/sessions/session%2F1/attachments", request.path)
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("aGVsbG8=", body["data"]?.jsonPrimitive?.content)
        assertEquals("report.pdf", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun uploadedAttachmentToleratesAMissingOptionalField() {
        // 服务端将来省略 name（或发 null）时，上传流程不能因为一个可选字段就断掉。
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"receiptId":"r-2"}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val uploaded = client.uploadAttachment("session-1", byteArrayOf(1, 2, 3), "x.bin")

        assertEquals("r-2", uploaded.receiptId)
        assertEquals("", uploaded.name)
    }
}
