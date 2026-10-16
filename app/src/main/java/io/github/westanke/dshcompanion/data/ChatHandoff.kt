package io.github.westanke.dshcompanion.data
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import androidx.annotation.StringRes
import io.github.westanke.dshcompanion.R

/**
 * 「纯文本模型发图」落进会话工作区的那类**交接附件**。
 *
 * 模型只会输出文本时，插件把图片写进工作区 `.dsh-images/`，再往用户消息里插两行说明。
 * 于是客户端要同时做两件事：
 * - **摘掉**那两行（[stripHandoffLines]），别让它们混进用户自己打的字里；
 * - **认出来**并变成可直接显示的附件（[handoffAttachment]），字节走
 *   「绝对路径 → 授权根」的官方解析去取，不在附件存储里。
 *
 * [handoffPathOf] 是两端共用的判据：界面只把 `attachmentId` 传给 ViewModel，
 * 「这个 id 是工作区路径还是附件哈希」必须在客户端与数据层用同一份实现。
 *
 * 从 `ChatPresentation.kt`（825 行）拆出，实现逐字未改。原先的 `private` 助手
 * 改为 `internal`：用户消息的映射在 `ChatHistoryMapping.kt`，要调用它们。
 */

/**
 * 从 content 数组里取出图片附件引用。
 *
 * 只认 `type == "image"`；`type == "file"` 的凭证（receiptId）指向的是一次上传的暂存区，
 * 与服务端持久化的附件存储不是同一套东西，硬接只会拿到 404。
 */
internal fun contentAttachments(content: JsonElement?): List<ChatAttachment> {
    val parts = content as? JsonArray ?: return emptyList()
    return parts.mapNotNull { part ->
        val value = part as? JsonObject ?: return@mapNotNull null
        if (value.stringValue("type") != "image") return@mapNotNull null
        val attachment = value.objectValue("attachment") ?: return@mapNotNull null
        val attachmentId = attachment.stringValue("attachmentId")?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        ChatAttachment(
            attachmentId = attachmentId,
            mediaType = attachment.stringValue("mediaType").orEmpty(),
            width = attachment.intValue("width") ?: 0,
            height = attachment.intValue("height") ?: 0,
            bytes = attachment.intValue("bytes") ?: 0,
            name = attachment.stringValue("name"),
        )
    }
}

/** 交接说明里给机器读的那一行的标记；靠它把该行从正文里摘掉。 */
internal const val HANDOFF_SOURCE_MARKER = "[Image: source: "

/** 交接说明里给人看的那一行的开头（`🖼️ 图片 1「shot.jpg」（…）`）。 */
internal const val HANDOFF_PREAMBLE_MARKER = "🖼️ 图片 "

/** 交接附件的 id 前缀：ViewModel 靠它认出「这个 id 是一条文件路径，不是附件哈希」。 */
internal const val HANDOFF_ATTACHMENT_PREFIX = "handoff:"

/**
 * 从附件 id 里解出交接路径；不是交接附件就返回 `null`。
 *
 * 界面只把 [ChatAttachment.attachmentId] 传给 ViewModel，于是「这个 id 是工作区路径、
 * 还是附件存储里的哈希」这个判据必须在两端共用同一份实现 —— 放这里，单测能直接钉住。
 */
internal fun handoffPathOf(attachmentId: String): String? = attachmentId
    .takeIf { it.startsWith(HANDOFF_ATTACHMENT_PREFIX) }
    ?.removePrefix(HANDOFF_ATTACHMENT_PREFIX)
    ?.takeIf(String::isNotBlank)

/** `[Image: source: /abs/path.jpg]`；路径取到本行最后一个 `]`（路径里可能带空格）。 */
internal val HANDOFF_SOURCE_PATTERN = Regex("""\[Image: source:\s*(.+)]""")

/** 把交接说明的两行从一段正文里摘掉，其余原样保留。 */
internal fun stripHandoffLines(part: String): String = part
    .lineSequence()
    .filterNot { line ->
        line.contains(HANDOFF_SOURCE_MARKER) || line.trimStart().startsWith(HANDOFF_PREAMBLE_MARKER)
    }
    .joinToString("\n")
    .trim()

/** 一条 text part 里出现的交接路径（正常是 0 或 1 条）。 */
internal fun handoffPathsIn(part: String): List<String> = HANDOFF_SOURCE_PATTERN
    .findAll(part)
    .map { it.groupValues[1].trim() }
    .filter(String::isNotBlank)
    .toList()

/**
 * 把一条交接路径变成**可直接显示**的附件。
 *
 * 用 `handoff:<绝对路径>` 当 [ChatAttachment.attachmentId]：它同时是图片缓存的键、
 * 去重的键和「从哪读字节」的唯一依据 —— 路径本身就能定位文件，不必再引一张 id 表。
 */
internal fun handoffAttachment(path: String): ChatAttachment {
    val name = path.substringAfterLast('/').ifBlank { path }
    return ChatAttachment(
        attachmentId = HANDOFF_ATTACHMENT_PREFIX + path,
        mediaType = handoffMediaType(name),
        name = name,
        handoffPath = path,
    )
}

internal fun handoffMediaType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> ""
    }
