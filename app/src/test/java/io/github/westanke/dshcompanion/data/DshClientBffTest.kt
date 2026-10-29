package io.github.westanke.dshcompanion.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BFF REST 三项新能力（分块上传 / 定时任务 / 语音）的端点契约测试。
 *
 * 每个端点一条 fixture：**钉住路径、方法、请求体形状**，而不只是「能解码」——
 * 路径/方法错了服务端会 404/405，那正是「功能看起来没实现」的经典表现。
 *
 * 另外单独钉住**双向兼容**的两半：
 * - 老 bridge 无该路由 → 404 + [isRouteMissing] 为真 → 上层藏入口（不整页报错）；
 * - 语音服务缺失 → **200 `available:false`**（不是 404），上层必须给出 reason 而不是藏入口。
 */
class DshClientBffTest {
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

    private fun client() = DshClient(server.url("/").toString(), "device-token")

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    // ————————————————————————— 分块上传 —————————————————————————

    @Test
    fun beginUploadPostsMetadataAndAcceptsResumeHints() {
        server.enqueue(
            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
                """{"uploadId":"u-1","chunkSize":1048576,"expiresAt":1760000000000,"alreadyReceived":[0,2]}""",
            ),
        )
        val session = client().beginUpload("s 1", "big.bin", 3_000_000, "application/octet-stream")

        assertEquals("u-1", session.uploadId)
        assertEquals(1_048_576L, session.chunkSize)
        assertEquals(listOf(0, 2), session.alreadyReceived)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path.orEmpty().contains("/api/v1/chat/sessions/s%201/attachments/uploads"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"name\":\"big.bin\""))
        assertTrue(body.contains("\"size\":3000000"))
        assertTrue(body.contains("\"contentType\":\"application/octet-stream\""))
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
    }

    @Test
    fun putChunkSendsRawBytesNotBase64OrJson() {
        server.enqueue(json("""{"uploadId":"u-1","received":1,"count":3}"""))
        val ack = client().putChunk("s-1", "u-1", 0, byteArrayOf(1, 2, 3, 4))

        assertEquals(1, ack.received)
        assertEquals(3, ack.count)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.path.orEmpty().endsWith("/chat/sessions/s-1/attachments/uploads/u-1/chunks/0"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertEquals(4, request.bodySize)
        assertTrue(request.body.readByteArray().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun completeUploadReturnsTheSameAttachmentShapeAsLegacyUpload() {
        server.enqueue(json("""{"id":"receipt-9","name":"notes.txt","size":12,"contentType":"text/plain"}"""))
        val uploaded = client().completeUpload("s-1", "u-1")

        assertEquals("receipt-9", uploaded.receiptId)
        assertEquals("notes.txt", uploaded.name)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path.orEmpty().endsWith("/attachments/uploads/u-1/complete"))
    }

    @Test
    fun missingChunksReadsConflictDetailsSoTheCallerCanRepairOnce() {
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"error":{"code":"CONFLICT","message":"missing chunks","requestId":"r1","details":{"missing":[3,4]}}}""",
            ),
        )
        val failure = runCatching { client().completeUpload("s-1", "u-1") }.exceptionOrNull()

        assertTrue(failure is DshApiException)
        val conflict = failure as DshApiException
        assertEquals(409, conflict.status)
        assertEquals(listOf(3, 4), conflict.missingChunks())
    }

    @Test
    fun missingChunksToleratesMalformedDetails() {
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"error":{"code":"CONFLICT","message":"missing chunks","details":{"missing":["x",-1,2]}}}""",
            ),
        )
        val conflict = runCatching { client().completeUpload("s-1", "u-1") }.exceptionOrNull() as DshApiException
        assertEquals(listOf(2), conflict.missingChunks())
    }

    @Test
    fun uploadStatusAndCancelAddressTheUploadResource() {
        server.enqueue(
            json(
                """{"uploadId":"u-1","name":"a.bin","size":9,"chunkSize":1048576,
                   "receivedCount":1,"count":1,"expiresAt":1,"missing":[]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        val api = client()

        val status = api.uploadStatus("s-1", "u-1")
        assertEquals(1, status.receivedCount)
        api.cancelUpload("s-1", "u-1")

        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertTrue(read.path.orEmpty().endsWith("/attachments/uploads/u-1"))
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.path.orEmpty().endsWith("/attachments/uploads/u-1"))
    }

    // ————————————————————————— 定时任务 —————————————————————————

    @Test
    fun schedulesWithoutSessionIdListsEveryConversation() {
        server.enqueue(json("""{"items":[${SCHEDULE_ITEM}]}"""))
        val items = client().schedules()

        assertEquals(1, items.size)
        assertEquals("0 8 * * *", items.single().cron)
        assertEquals("Asia/Shanghai", items.single().timezone)
        assertNull(items.single().lastRunAt)
        assertEquals(1_760_000_000_000L, items.single().nextRunAt)

        val request = server.takeRequest()
        assertEquals("/api/v1/chat/schedules", request.path)
    }

    @Test
    fun schedulesFilterBySessionId() {
        server.enqueue(json("""{"items":[]}"""))
        client().schedules("s-42")

        assertTrue(server.takeRequest().path.orEmpty().endsWith("/chat/schedules?sessionId=s-42"))
    }

    @Test
    fun createSchedulePostsTheWholeDefinition() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(SCHEDULE_ITEM))
        val item = client().createSchedule("s-1", "整理日志", "0 8 * * *", "Asia/Shanghai")

        assertEquals("sch-1", item.id)
        assertTrue(item.enabled)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/schedules", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"sessionId\":\"s-1\""))
        assertTrue(body.contains("\"text\":\"整理日志\""))
        assertTrue(body.contains("\"cron\":\"0 8 * * *\""))
        assertTrue(body.contains("\"timezone\":\"Asia/Shanghai\""))
    }

    @Test
    fun patchScheduleOmitsNullFieldsInsteadOfSendingJsonNull() {
        server.enqueue(json(SCHEDULE_ITEM))
        client().patchSchedule("sch-1", enabled = true)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/chat/schedules/sch-1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"enabled\":true"))
        // 显式 null 不是「不改」：服务端 schema 会判非法，所以未传的字段必须消失。
        assertFalse(body.contains("null"))
        assertFalse(body.contains("\"cron\""))
    }

    @Test
    fun patchScheduleNeverSendsEnabledFalseBecauseTheKernelHasNoPause() {
        // 桥的实现：`PATCH {enabled:false}` → 400 ENABLED_UNSUPPORTED（停用只能 DELETE）。
        // 这里钉的是**客户端形状**：即便有人又写了 false，也只能由策略层拦掉 ——
        // 所以 `scheduleToggle(false)` 必须给 Delete，见 SchedulePolicyTest。
        assertEquals(ScheduleToggle.DisableByDelete, scheduleToggle(false))
        // 而在协议层，`enabled=true` 是唯一会发出去的 PATCH 形状。
        server.enqueue(json(SCHEDULE_ITEM))
        client().patchSchedule("sch-1", enabled = true)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"enabled\":true"))
    }

    @Test
    fun deleteScheduleAcceptsNoContent() {
        server.enqueue(MockResponse().setResponseCode(204))
        client().deleteSchedule("sch-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/chat/schedules/sch-1", request.path)
    }

    // ————————————————————————— 语音 —————————————————————————

    @Test
    fun speechCatalogKeepsAvailableFalseAsAValidAnswer() {
        server.enqueue(
            json(
                """{"available":false,"providers":[],"selected":null,
                   "reason":"内核未注入语音服务"}""",
            ),
        )
        val catalog = client().speechCatalog()

        assertFalse(catalog.available)
        assertEquals("内核未注入语音服务", catalog.reason)
        assertTrue(catalog.providers.isEmpty())
    }

    @Test
    fun speechCatalogParsesProvidersAndSelection() {
        server.enqueue(
            json(
                """{"available":true,
                   "providers":[{"id":"openai","name":"OpenAI","languages":["zh-CN","en-US"]}],
                   "selected":{"providerId":"openai","language":"zh-CN"}}""",
            ),
        )
        val catalog = client().speechCatalog()

        assertTrue(catalog.available)
        assertEquals("openai", catalog.providers.single().id)
        assertEquals(listOf("zh-CN", "en-US"), catalog.providers.single().languages)
        assertEquals("zh-CN", catalog.selected?.language)
    }

    @Test
    fun transcribePostsBase64WavAndAcceptsEmptyResult() {
        server.enqueue(json("""{"text":"","providerId":"openai","language":"zh-CN","durationMs":1200}"""))
        val result = client().transcribe("QUJD", language = "zh-CN", providerId = "openai")

        // 空文本是**正常结果**（用户没说话），不是错误：不能抛异常。
        assertEquals("", result.text)
        assertEquals(1200L, result.durationMs)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/speech/transcriptions", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"audio\":\"QUJD\""))
        assertTrue(body.contains("\"language\":\"zh-CN\""))
        assertTrue(body.contains("\"providerId\":\"openai\""))
    }

    @Test
    fun configureSpeechPutsTheSelectionAndReturnsTheCatalog() {
        server.enqueue(json("""{"available":true,"providers":[],"selected":{"providerId":"p1","language":"en-US"}}"""))
        val catalog = client().configureSpeech(providerId = "p1", language = "en-US")

        assertEquals("p1", catalog.selected?.providerId)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/chat/speech/config", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"providerId\":\"p1\""))
        assertTrue(body.contains("\"language\":\"en-US\""))
    }

    // ————————————————————————— 双向兼容（老 bridge） —————————————————————————

    @Test
    fun missingCatalogRoutesAreRecognisedAsRouteMissing() {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody(NOT_FOUND))
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody(NOT_FOUND))
        val api = client()

        val sched = runCatching { api.schedules() }.exceptionOrNull() as DshApiException
        val speech = runCatching { api.speechCatalog() }.exceptionOrNull() as DshApiException

        // 这两个 404 只可能来自「路由不存在」——上层据此藏入口，而不是弹错误页。
        assertTrue(sched.isRouteMissing())
        assertTrue(speech.isRouteMissing())
    }

    @Test
    fun serverErrorsAreNotMistakenForMissingRoutes() {
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json").setBody(
                """{"error":{"code":"INTERNAL","message":"boom"}}""",
            ),
        )
        val failure = runCatching { client().schedules() }.exceptionOrNull() as DshApiException
        assertFalse(failure.isRouteMissing())
        assertEquals(500, failure.status)
    }

    @Test
    fun writeRoute404StaysAPlainErrorBecauseItMayMeanUploadExpired() {
        // 同一个 404：读取类目录端点 = 功能不存在；上传的 404 = uploadId 过期/会话不存在。
        // 判定函数本身不区分场景，由调用方按「读取 vs 写入」决定是否藏入口 —— 这里钉住状态与 code。
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody(NOT_FOUND))
        val failure = runCatching { client().putChunk("s-1", "u-expired", 0, byteArrayOf(1)) }.exceptionOrNull()

        assertTrue(failure is DshApiException)
        assertEquals(404, (failure as DshApiException).status)
    }

    private companion object {
        const val SCHEDULE_ITEM = """{"id":"sch-1","sessionId":"s-1","text":"整理日志","cron":"0 8 * * *",
            "timezone":"Asia/Shanghai","enabled":true,"createdAt":1759000000000,
            "lastRunAt":null,"nextRunAt":1760000000000}"""

        const val NOT_FOUND = """{"error":{"code":"NOT_FOUND","message":"route not found","requestId":"r1"}}"""
    }
}
