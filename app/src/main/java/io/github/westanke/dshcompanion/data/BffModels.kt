package io.github.westanke.dshcompanion.data

import kotlinx.serialization.Serializable

/**
 * BFF REST 三项新能力的视图模型（分块上传 / 定时任务 / 官方语音）。
 *
 * 契约出处：`dsh-remote-bridge/docs/project/BFF-REST-CONTRACT.md`。与 `Models.kt` 同一套
 * 序列化风格，铁律照旧：
 *
 * 1. **每个字段都给默认值** —— `Json { ignoreUnknownKeys = true }` 只放过「多出来的键」，
 *    缺键照样抛异常（本项目因缺键炸过三次：`AgentPreset.trust` / `PluginInventory.profile` /
 *    `ResolvedPath`）。
 * 2. 服务端可能显式发 `null` 的字段必须声明为可空 —— 非空属性遇到 `null` 不会退回默认值，
 *    而是整条响应解码失败。
 * 3. 时间一律 **epoch 毫秒**（契约要求整数），用 `Long`。
 */

/** 契约里的服务端固定块大小（1 MiB）。服务端可以在握手响应里给别的值，客户端必须照用。 */
const val DEFAULT_UPLOAD_CHUNK_SIZE: Long = 1_048_576L

/** 契约的附件上限（256 MiB）。超过这个值连申请都不用发，服务端会 413。 */
const val MAX_UPLOAD_BYTES: Long = 256L * 1_048_576L

/**
 * 走分块上传的门槛（4 MiB）。
 *
 * 为什么不是 0：小于这个体积的文件用既有的 base64 单请求路径**更省事也更快**（一个来回，
 * 不用申请 uploadId、不用管断点续传），而分块路径的价值在「大文件可续、可看进度」。
 * 为什么不是 24 MiB（既有的 [ChatContentParts.MAX_FILE_BYTES]）：那个是「能不能发」的硬上限，
 * 而这里要的是「从多大开始值得分块」—— 4 MiB 的 base64 已经 ~5.3 MiB 的 JSON，
 * 在手机网络下开始明显卡手。
 */
const val CHUNKED_UPLOAD_THRESHOLD: Long = 4L * 1_048_576L

/** 语音转写的服务端超时（契约写死 180s）。客户端读超时必须比它长，否则先于服务端放弃。 */
const val TRANSCRIBE_TIMEOUT_SECONDS: Long = 180L

/** 契约的音频上限（解码后 4 MiB）。 */
const val MAX_AUDIO_BYTES: Int = 4 * 1_048_576

/**
 * `POST …/attachments/uploads` 的响应：一次分块上传的句柄。
 *
 * [alreadyReceived] 是**断点续传的关键**：同会话里同名同大小的未过期上传会被服务端复用，
 * 这里返回已收到的块下标，客户端跳过它们即可，不必重传。
 */
@Serializable
data class UploadSession(
    val uploadId: String = "",
    /** 服务端指定的块大小；客户端必须照用。缺省给契约值，避免 0 除。 */
    val chunkSize: Long = DEFAULT_UPLOAD_CHUNK_SIZE,
    val expiresAt: Long = 0,
    val alreadyReceived: List<Int> = emptyList(),
)

/** `PUT …/chunks/{index}` 的响应：已收块数与总块数。 */
@Serializable
data class ChunkAck(
    val uploadId: String = "",
    val received: Int = 0,
    val count: Int = 0,
)

/** `GET …/uploads/{uploadId}` 的响应：断点查询。 */
/**
 * 分块上传收尾（201）的原始响应：`{id,name,size,contentType}`。
 *
 * 契约里这个 `id` 就是既有 `POST …/attachments` 返回的 receiptId，但字段名不同 ——
 * 直接拿 [UploadedAttachment] 解会把 id 当未知键丢掉（`ignoreUnknownKeys` 会放过它），
 * 表现为「附件上传成功但发出去时引用不到」。所以先解这一层，再显式映射。
 */
@Serializable
data class CompletedUpload(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    val contentType: String = "",
)

@Serializable
data class UploadStatus(
    val uploadId: String = "",
    val name: String = "",
    val size: Long = 0,
    val chunkSize: Long = DEFAULT_UPLOAD_CHUNK_SIZE,
    val receivedCount: Int = 0,
    val count: Int = 0,
    val expiresAt: Long = 0,
    val missing: List<Int> = emptyList(),
)

/**
 * 一条定时任务。
 *
 * ## `enabled` 的语义（2026-10-29 与桥实现对齐后修正）
 *
 * 内核 `ScheduleRecord` **没有暂停操作**：`PATCH {"enabled":false}` 会回
 * `400 BODY_INVALID`（`details.reason = "ENABLED_UNSUPPORTED"`）。所以：
 *
 * - 列表里的 `enabled=false` 只表示「这条任务已经结束」（跑完/被删前最后一次快照），
 *   客户端**不能**用它当「用户主动暂停」来展示开关的打开态；
 * - 真正的「停用」= `DELETE`（删除，不可恢复）；
 * - `PATCH {"enabled":true}` 只对**未结束**的任务有意义，已结束的回
 *   `409 CONFLICT` + `details.reason = "SCHEDULE_ENDED"`。
 *
 * 等内核补上暂停语义再改回 PATCH —— 届时只需改 [ScheduleToggle] 与这里的两行注释。
 *
 * ## `createdAt` 恒为 null，**不可据此排序**
 *
 * 内核记录里没有创建时间字段，桥保留这个字段只是为了契约形状稳定，恒发 `null`。
 * 所以这里声明成可空 `Long?`（非空 `Long` 遇到显式 `null` 会让**整条响应**解码失败），
 * 列表排序一律只用 [nextRunAt]（见 `SchedulePolicy.displayOrder`）。
 */
@Serializable
data class ScheduleItem(
    val id: String = "",
    val sessionId: String = "",
    val text: String = "",
    val cron: String = "",
    /** IANA 时区名（`Asia/Shanghai`）。服务端校验，非法会 400 BODY_INVALID。 */
    val timezone: String = "",
    val enabled: Boolean = true,
    /** **恒为 null**（内核没有创建时间字段）；不可据此排序，用 [nextRunAt]。 */
    val createdAt: Long? = null,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
)

/** `GET /chat/schedules` 的响应信封。 */
@Serializable
data class ScheduleList(val items: List<ScheduleItem> = emptyList())

@Serializable
data class SpeechProvider(
    val id: String = "",
    val name: String = "",
    val languages: List<String> = emptyList(),
)

/** 当前选中的语音服务与语言。 */
@Serializable
data class SpeechSelection(
    val providerId: String = "",
    val language: String = "",
)

/**
 * `GET /chat/speech/catalog` 的响应。
 *
 * **注意 [available] 与「路由不存在」是两件事**：内核没注入语音服务时，服务端照样
 * 回 `200 {available:false, reason:"…"}`；只有老插件才会对这个路径 404。界面对前者
 * 显示「本机没有可用的语音服务 + reason」，对后者**不显示入口**（升级插件才行）。
 */
@Serializable
data class SpeechCatalog(
    val available: Boolean = false,
    val providers: List<SpeechProvider> = emptyList(),
    val selected: SpeechSelection? = null,
    /** 不可用的人话原因（如「当前内核未注入语音服务」）；available=true 时为 null。 */
    val reason: String? = null,
)

/** `POST /chat/speech/transcriptions` 的响应。空文本 `text==""` 是**正常结果**，不是错误。 */
@Serializable
data class TranscriptionView(
    val text: String = "",
    val providerId: String = "",
    val language: String = "",
    val durationMs: Long = 0,
)
