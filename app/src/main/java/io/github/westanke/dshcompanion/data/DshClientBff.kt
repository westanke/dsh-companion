package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * BFF REST 三项新能力的端点（分块上传 / 定时任务 / 官方语音）。
 *
 * ## 为什么是 `DshClient` 的扩展函数而不是塞进 `DshClient.kt`
 *
 * `DshClient.kt` 已经 **794 行**（预算 800），三项新能力光端点就有 12 个、再加请求体与
 * 错误码处理，塞进去必然超预算；而「唯一网络边界」的实质是**全 App 只有一个 OkHttp 客户端
 * 与一套鉴权/错误处理**，不是「所有方法都必须写在同一文件里」。这里每个方法都复用
 * `DshClient` 的 internal helper（`get`/`post`/`patch`/`execute`/`pathUrl`/`ensureSuccess`），
 * 没有新建客户端、没有自己的超时与头，`ArchitectureGuardTest` 的 OkHttpClient 断言照旧绿。
 *
 * ## 契约出处
 *
 * `dsh-remote-bridge/docs/project/BFF-REST-CONTRACT.md`。全部路径相对 `/api/v1`（`base` 已含），
 * Bearer 鉴权由 helper 统一加；时间字段都是 epoch 毫秒。
 */

// ————————————————————————— 分块上传 —————————————————————————

private fun DshClient.uploadsUrl(sessionId: String) =
    pathUrl("chat", "sessions", sessionId, "attachments", "uploads")

private fun DshClient.uploadUrl(sessionId: String, uploadId: String) =
    pathUrl("chat", "sessions", sessionId, "attachments", "uploads", uploadId)

/**
 * 申请一次分块上传（`POST …/attachments/uploads`，201）。
 *
 * 服务端会**复用**同会话里同名同大小且未过期的上传，此时 [UploadSession.alreadyReceived]
 * 带回已收块 —— 断点续传不需要客户端自己记账。`chunkSize` 以服务端为准。
 */
fun DshClient.beginUpload(
    sessionId: String,
    name: String,
    size: Long,
    contentType: String? = null,
): UploadSession {
    val body = buildJsonObject {
        put("name", name)
        put("size", size)
        if (!contentType.isNullOrBlank()) put("contentType", contentType)
    }
    return post(uploadsUrl(sessionId), body, JsonObject.serializer(), UploadSession.serializer())
}

/**
 * 传一块（`PUT …/uploads/{uploadId}/chunks/{index}`）。
 *
 * **原始二进制**，不是 JSON、也不是 base64 —— 块本来就是字节，包一层 base64 只会让
 * 1 MiB 变成 1.33 MiB。`index` 从 **0** 起；重复 PUT 幂等覆盖，所以「传了但没收到 ack」
 * 直接重传是安全的。
 */
fun DshClient.putChunk(sessionId: String, uploadId: String, index: Int, bytes: ByteArray): ChunkAck {
    val url = pathUrl("chat", "sessions", sessionId, "attachments", "uploads", uploadId, "chunks", index.toString())
    return executeJson(Request.Builder().url(url).put(bytes.toRequestBody(DshClient.OCTET_STREAM)), ChunkAck.serializer())
}

/**
 * 收尾（`POST …/uploads/{uploadId}/complete`，201）→ 与既有单请求上传同形的附件视图。
 *
 * 缺块时服务端回 `409 CONFLICT` + `details.missing`，调用方用 [missingChunks] 取出来补齐后
 * **重试一次**（契约允许）。完成后 uploadId 立即失效，再 complete 是 404。
 */
fun DshClient.completeUpload(sessionId: String, uploadId: String): UploadedAttachment {
    val completed = post(
        pathUrl("chat", "sessions", sessionId, "attachments", "uploads", uploadId, "complete"),
        JsonObject(emptyMap()),
        JsonObject.serializer(),
        CompletedUpload.serializer(),
    )
    // 契约的 `id` 就是既有附件视图的 receiptId（字段名不同，见 CompletedUpload 的注释）。
    return UploadedAttachment(receiptId = completed.id, name = completed.name)
}

/** 查断点（`GET …/uploads/{uploadId}`）：已收块数 / 总块数 / 还缺哪些块。 */
fun DshClient.uploadStatus(sessionId: String, uploadId: String): UploadStatus =
    get(uploadUrl(sessionId, uploadId), UploadStatus.serializer())

/** 放弃这次上传（`DELETE …/uploads/{uploadId}`，204）。失败不影响用户继续用 App。 */
fun DshClient.cancelUpload(sessionId: String, uploadId: String) {
    execute(Request.Builder().url(uploadUrl(sessionId, uploadId)).delete()).use { ensureSuccess(it) }
}

// ————————————————————————— 定时任务 —————————————————————————

/**
 * 列定时任务（`GET /chat/schedules`）。[sessionId] 为 null 时列**全部**会话的。
 *
 * 老 bridge 没有这条路由：`get` 会抛 404 的 [DshApiException]，调用方用 [isRouteMissing]
 * 判定后把入口藏起来（见 `supportsSchedules`），不整页报错。
 */
fun DshClient.schedules(sessionId: String? = null): List<ScheduleItem> {
    val url = pathUrl("chat", "schedules").newBuilder().apply {
        if (!sessionId.isNullOrBlank()) addQueryParameter("sessionId", sessionId)
    }.build()
    return get(url, ScheduleList.serializer()).items
}

/** 新建（`POST /chat/schedules`，201）。[text] 1..8000、[cron] 五段、[timezone] 必须 IANA。 */
fun DshClient.createSchedule(
    sessionId: String,
    text: String,
    cron: String,
    timezone: String,
): ScheduleItem {
    val body = buildJsonObject {
        put("sessionId", sessionId)
        put("text", text)
        put("cron", cron)
        put("timezone", timezone)
    }
    return post(pathUrl("chat", "schedules"), body, JsonObject.serializer(), ScheduleItem.serializer())
}

/**
 * 部分更新（`PATCH /chat/schedules/{id}`）。`enabled=false` 是**暂停**，不是删除。
 *
 * 只把**非 null** 的字段写进请求体：契约的字段都是可选的，发 `"enabled": null` 会被
 * 服务端的 schema 判成非法（显式 null 不是「不改」）。
 */
fun DshClient.patchSchedule(
    id: String,
    enabled: Boolean? = null,
    text: String? = null,
    cron: String? = null,
    timezone: String? = null,
): ScheduleItem {
    val body = buildJsonObject {
        if (enabled != null) put("enabled", enabled)
        if (text != null) put("text", text)
        if (cron != null) put("cron", cron)
        if (timezone != null) put("timezone", timezone)
    }
    return patch(pathUrl("chat", "schedules", id), body, JsonObject.serializer(), ScheduleItem.serializer())
}

/** 删除（`DELETE /chat/schedules/{id}`，204）。 */
fun DshClient.deleteSchedule(id: String) {
    execute(Request.Builder().url(pathUrl("chat", "schedules", id)).delete()).use { ensureSuccess(it) }
}

// ————————————————————————— 官方语音 —————————————————————————

/**
 * 语音服务目录（`GET /chat/speech/catalog`）。
 *
 * 关键区别：内核没注入语音服务时服务端回 **200 `available:false`**，不是 404 ——
 * 界面要把「服务不可用（附 reason）」与「老插件没这条路由（藏入口）」分开说，
 * 否则用户会按「升级插件」去修一个根本没装语音服务的内核。
 */
fun DshClient.speechCatalog(): SpeechCatalog =
    get(pathUrl("chat", "speech", "catalog"), SpeechCatalog.serializer())

/**
 * 整段转写（`POST /chat/speech/transcriptions`）。[audioBase64] 是 base64 的 WAV
 * （16 kHz 单声道 PCM16，契约要求），解码后 ≤ 4 MiB。
 *
 * 走 [DshClient.executeSlowJson]：服务端超时 180s，共享客户端只有 45s。
 * 空结果（`text=""`）是**正常返回**，不是错误 —— 用户可能就是没说话。
 */
fun DshClient.transcribe(
    audioBase64: String,
    language: String? = null,
    providerId: String? = null,
): TranscriptionView {
    val body = buildJsonObject {
        put("audio", audioBase64)
        if (!language.isNullOrBlank()) put("language", language)
        if (!providerId.isNullOrBlank()) put("providerId", providerId)
    }
    val request = Request.Builder()
        .url(pathUrl("chat", "speech", "transcriptions"))
        .post(body.toString().toRequestBody(DshClient.JSON_MEDIA))
    return executeSlowJson(request, TranscriptionView.serializer())
}

/** 记住用户的语音服务/语言选择（`PUT /chat/speech/config`），返回更新后的目录视图。 */
fun DshClient.configureSpeech(providerId: String? = null, language: String? = null): SpeechCatalog {
    val body = buildJsonObject {
        if (!providerId.isNullOrBlank()) put("providerId", providerId)
        if (!language.isNullOrBlank()) put("language", language)
    }
    return put(pathUrl("chat", "speech", "config"), body, JsonObject.serializer(), SpeechCatalog.serializer())
}

// ————————————————————————— 错误判定 —————————————————————————

/**
 * 这条 404 是不是「服务端压根没这个路由」（老插件）。
 *
 * 只对**读取类目录端点**（`GET /chat/schedules`、`GET /chat/speech/catalog`）使用：
 * 那里的 404 只可能来自路由缺失。按会话/按 id 的写操作 404 还可能是「会话不存在」
 * 「uploadId 过期」，那些要按普通错误显示给用户，不能当成「功能不存在」。
 */
internal fun DshApiException.isRouteMissing(): Boolean =
    status == 404 && code in setOf("NOT_FOUND", "ROUTE_NOT_FOUND", "HTTP_ERROR")

/**
 * 从 `409 CONFLICT` 的 `details.missing` 里取出缺块下标。
 *
 * 解析容错：字段缺失、类型不对、下标为负一律丢弃 —— 拿不到缺块清单时调用方会退回
 * 「按 [planChunks] 重算一遍再传」，而不是崩。
 */
internal fun DshApiException.missingChunks(): List<Int> {
    val details = details as? JsonObject ?: return emptyList()
    val array = details["missing"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
        .filter { it >= 0 }
}
