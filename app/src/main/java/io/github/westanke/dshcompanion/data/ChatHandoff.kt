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

/**
 * Markdown 图片：`![alt](target)`，允许 `![alt](target "title")` 这种带 title 的写法。
 *
 * 为什么需要这条：模型（和本项目的助手）在正文里贴图时用的是标准 Markdown 图片语法，
 * 目标往往是**服务器上的绝对路径**（如 `/media/…/c1.png`）。渲染层不认这种图，
 * 又不像交接说明那样有专用标记 —— 于是整段图被丢掉，正文里留一块**莫名空白**，
 * 看起来像内容丢了。这里把它摘出来，复用交接图那条已经验证过的「绝对路径取字节」通路。
 */
internal val MARKDOWN_IMAGE_PATTERN = Regex("""!\[([^\]]*)]\(\s*([^)]+?)\s*\)""")

/**
 * 从括号内容里取真正的目标：去掉可选的 `"title"` 与 `<…>` 包裹。
 *
 * 目标里**允许有空格** —— 本项目的截图路径就长这样（`…/报告/截图 c1.png`）。
 * 因此正则只能取到「右括号为止」，title 与尖括号在这里剥。
 */
internal fun markdownImageTarget(raw: String): String {
    val trimmed = raw.trim().removeSurrounding("<", ">").trim()
    if (!trimmed.endsWith("\"")) return trimmed
    val cut = trimmed.lastIndexOf('"', trimmed.length - 2)
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}

/**
 * 正文里**能显示**的图片路径：绝对路径，且扩展名是已知图片格式。
 *
 * 只认绝对路径是刻意的 —— 相对路径在会话里没有可信基座（工作区根、插件目录、当前文件所在目录
 * 三种解释都说得通），猜错了等于去读一个不存在的文件。
 */
internal fun markdownImagePathsIn(part: String): List<String> = MARKDOWN_IMAGE_PATTERN
    .findAll(part)
    .mapNotNull { match ->
        val target = markdownImageTarget(match.groupValues[2])
        target.takeIf { it.startsWith("/") && handoffMediaType(it.substringAfterLast('/')).isNotEmpty() }
    }
    .toList()

/**
 * 把 Markdown 图片从正文里改写成「能在 App 里看到的东西」：
 *
 * - **本地绝对路径的图片** → 整段摘掉（它已经变成附件，由附件区渲染缩略图 + 点开大图）；
 * - **http(s) 图片** → 降级成普通链接（App 不做远程图片加载，别留一块白洞）；
 * - 其它（相对路径、非图片扩展名）→ 有 alt 就留 alt 文字，没有就摘掉。
 */
internal fun stripMarkdownImages(part: String): String = MARKDOWN_IMAGE_PATTERN
    .replace(part) { match ->
        val alt = match.groupValues[1].trim()
        val target = markdownImageTarget(match.groupValues[2])
        when {
            target.startsWith("/") && handoffMediaType(target.substringAfterLast('/')).isNotEmpty() -> ""
            // 剩下的一律降级成**链接**，绝不把 `![](…)` 原样留给 Markdown 渲染器。
            // 渲染器会为图片语法预留一块「图片位」却画不出东西（没有图片加载器），
            // 屏幕上就是一块说不清来历的空白 —— 真机 2026-10-22 反馈的「图片显示了寂寞」就是它。
            // 变成链接至少让「这里原本有张图、图在哪」看得见、点得动。
            else -> "[" + alt.ifBlank { target.substringAfterLast('/') } + "](" + target + ")"
        }
    }
    // 图被摘掉后会留下空行：连着的 3 个以上换行压成 1 个空行，正文别出现莫名的大段留白。
    .let { MULTI_BLANK_LINE_PATTERN.replace(it, "\n\n") }

/** 三个及以上的连续换行 —— 摘完图片留下的空行由它压平。 */
private val MULTI_BLANK_LINE_PATTERN = Regex("""\n{3,}""")

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
