package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class ChatItemKind { USER, ASSISTANT, REASONING, CONTEXT, TOOL }

/**
 * 会话历史里的一个图片附件。
 *
 * 注意这里**只有引用没有字节**：消息发送时图片是 base64 内联的，但存进历史后就变成
 * `{type:'image', attachment:{attachmentId:'sha256:…', …}}` —— 字节在服务端的附件存储里。
 * 所以显示一张历史图片需要第二步：拿 [attachmentId] 去 `/api/v1/attachments/:id` 取字节。
 *
 * 所有字段都有默认值：这是从服务端事件里解出来的结构，缺字段比多字段常见得多
 * （本项目已经因为严格解码踩过三次坑）。
 */
data class ChatAttachment(
    val attachmentId: String = "",
    val mediaType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    val name: String? = null,
)

data class ChatDisplayItem(
    val id: String,
    val seq: Int,
    val kind: ChatItemKind,
    val title: String? = null,
    val body: String = "",
    val streaming: Boolean = false,
    /** 这条消息附带的图片；只发图不发字的消息正是靠它才不会被丢掉。 */
    val attachments: List<ChatAttachment> = emptyList(),
)

data class ChatDelta(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val index: Int,
    val kind: ChatItemKind,
    val text: String,
)

data class LiveChatBlock(val index: Int, val kind: ChatItemKind, val text: String)

data class LiveChatState(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val blocks: List<LiveChatBlock> = emptyList(),
    val revision: Long = 0,
) {
    fun append(delta: ChatDelta): LiveChatState {
        val base = if (sessionId == delta.sessionId && turn == delta.turn && step == delta.step) this else {
            LiveChatState(delta.sessionId, delta.turn, delta.step)
        }
        val existing = base.blocks.indexOfFirst { it.index == delta.index }
        val next = if (existing < 0) {
            base.blocks + LiveChatBlock(delta.index, delta.kind, delta.text)
        } else {
            base.blocks.toMutableList().also { blocks ->
                val previous = blocks[existing]
                blocks[existing] = LiveChatBlock(
                    delta.index,
                    delta.kind,
                    if (previous.kind == delta.kind) previous.text + delta.text else delta.text,
                )
            }
        }
        return base.copy(blocks = next.sortedBy(LiveChatBlock::index), revision = base.revision + 1)
    }

    fun displayItems(): List<ChatDisplayItem> = blocks.filter { it.text.isNotEmpty() }.map { block ->
        ChatDisplayItem(
            id = "live-$turn-$step-${block.index}-${block.kind}",
            seq = Int.MAX_VALUE,
            kind = block.kind,
            body = block.text,
            streaming = true,
        )
    }
}

fun WorkspaceEvent.chatDelta(): ChatDelta? {
    if (type != "chat.message.delta") return null
    val value = data as? JsonObject ?: return null
    val kind = when (value.stringValue("kind")) {
        "text" -> ChatItemKind.ASSISTANT
        "reasoning" -> ChatItemKind.REASONING
        else -> return null
    }
    return ChatDelta(
        sessionId = value.stringValue("sessionId") ?: return null,
        turn = value.intValue("turn") ?: return null,
        step = value.intValue("step") ?: return null,
        index = value.intValue("index") ?: return null,
        kind = kind,
        text = value.stringValue("text") ?: return null,
    )
}

fun ChatHistory.displayItems(): List<ChatDisplayItem> = events.flatMap(::displayItems)

fun ChatHistory.todoItems(): List<TodoItem> {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return emptyList()
    return (values["todos"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val content = item.stringValue("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val status = item.stringValue("status") ?: return@mapNotNull null
        TodoItem(content, status)
    }
}

fun ChatHistory.permissionSelect(): PermissionSelect? {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return null
    val raw = values.objectValue("permissions") ?: return null
    val currentValue = raw.stringValue("currentValue") ?: return null
    val options = (raw["options"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val value = option.stringValue("value") ?: return@mapNotNull null
        val name = option.stringValue("name") ?: return@mapNotNull null
        PermissionOption(value, name, option.stringValue("description"))
    }
    return PermissionSelect(options, currentValue)
}

private fun displayItems(entry: HistoryEntry): List<ChatDisplayItem> = when (entry.event.type) {
    "user/message" -> userItems(entry.event)
    "assistant/message" -> assistantItems(entry.event)
    "tool/call" -> listOf(toolItem(entry))
    else -> emptyList()
}

private fun userItems(event: SessionEvent): List<ChatDisplayItem> {
    val data = event.data as? JsonObject ?: return emptyList()
    val source = data.objectValue("source")
    val kind = source?.stringValue("kind")
    val body = contentText(data["content"])
    val attachments = contentAttachments(data["content"])
    if (kind == "user") {
        // 判据是「有文字**或**有附件」。旧代码只判文字，于是「只发一张图、不打字」
        // 的消息会整条消失 —— 用户看到的正是"App 里看不见图片"。
        if (body.isBlank() && attachments.isEmpty()) return emptyList()
        return listOf(ChatDisplayItem(
            id = "${event.seq}-user",
            seq = event.seq,
            kind = ChatItemKind.USER,
            body = body,
            attachments = attachments,
        ))
    }
    return listOf(ChatDisplayItem(
        id = "${event.seq}-context",
        seq = event.seq,
        kind = ChatItemKind.CONTEXT,
        title = contextLabel(source),
        body = body,
        attachments = attachments,
    ))
}

/**
 * 从 content 数组里取出图片附件引用。
 *
 * 只认 `type == "image"`；`type == "file"` 的凭证（receiptId）指向的是一次上传的暂存区，
 * 与服务端持久化的附件存储不是同一套东西，硬接只会拿到 404。
 */
private fun contentAttachments(content: JsonElement?): List<ChatAttachment> {
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

private fun assistantItems(event: SessionEvent): List<ChatDisplayItem> {
    val outer = event.data as? JsonObject ?: return emptyList()
    val message = outer.objectValue("message") ?: outer
    val content = message["content"]
    val blocks = content as? JsonArray ?: return contentText(content).takeIf(String::isNotBlank)?.let {
        listOf(ChatDisplayItem("${event.seq}-assistant", event.seq, ChatItemKind.ASSISTANT, body = it))
    }.orEmpty()
    val result = mutableListOf<ChatDisplayItem>()
    val prose = mutableListOf<String>()
    fun flushProse() {
        if (prose.isEmpty()) return
        result += ChatDisplayItem(
            id = "${event.seq}-assistant-${result.size}",
            seq = event.seq,
            kind = ChatItemKind.ASSISTANT,
            body = prose.joinToString("\n\n"),
        )
        prose.clear()
    }
    for (block in blocks) {
        val value = block as? JsonObject ?: continue
        when (value.stringValue("type")) {
            "text" -> value.stringValue("text")?.takeIf(String::isNotBlank)?.let(prose::add)
            "reasoning" -> {
                flushProse()
                value.stringValue("text")?.takeIf(String::isNotBlank)?.let { reasoning ->
                    result += ChatDisplayItem(
                        id = "${event.seq}-reasoning-${result.size}",
                        seq = event.seq,
                        kind = ChatItemKind.REASONING,
                        body = reasoning,
                    )
                }
            }
        }
    }
    flushProse()
    return result
}

private fun toolItem(entry: HistoryEntry): ChatDisplayItem {
    val event = entry.event
    val data = event.data as? JsonObject
    val name = data?.stringValue("name").orEmpty()
    val view = (entry.view as? JsonObject)?.objectValue("view")
    val viewTitle = view?.stringValue("title")
    val description = view?.stringValue("description")
    val arguments = data?.stringValue("arguments")
    return ChatDisplayItem(
        id = "${event.seq}-tool",
        seq = event.seq,
        kind = ChatItemKind.TOOL,
        title = toolTitle(name),
        body = description ?: argumentsSummary(arguments) ?: viewTitle ?: name,
    )
}

private fun contentText(content: JsonElement?): String = when (content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { block ->
        val value = block as? JsonObject ?: return@mapNotNull null
        when (value.stringValue("type")) {
            "text", "reasoning" -> value.stringValue("text")
            else -> null
        }
    }.filter(String::isNotBlank).joinToString("\n\n")
    else -> ""
}

private fun contextLabel(source: JsonObject?): String? {
    source ?: return null
    return when (val kind = source.stringValue("kind")) {
        "agent-instructions" -> source.arrayLabels("changes", "path").ifEmpty { null }
        "session-reference" -> source.arrayLabels("references", "label").ifEmpty { null }
        "plugin" -> source.stringValue("plugin") ?: kind
        "skill-invocation" -> source.stringValue("name") ?: kind
        else -> kind
    }
}

private fun toolTitle(name: String): String = when {
    name == "bash" -> "Bash"
    name == "pwsh" -> "Pwsh"
    "skill" in name.lowercase() -> "Skill"
    name == "read" || name == "web_fetch" -> "Read"
    name == "grep" || name == "glob" || name == "web_search" -> "Search"
    name == "write" -> "Write"
    name == "edit" -> "Edit"
    name == "run_code" -> "Code"
    else -> "Tool call"
}

private fun argumentsSummary(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parsed = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    for (key in listOf("description", "path", "file_path", "query", "pattern", "url", "command", "name", "package")) {
        parsed.stringValue(key)?.takeIf(String::isNotBlank)?.let { return it.lineSequence().first() }
    }
    return null
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.arrayLabels(member: String, field: String): String {
    val values = (this[member] as? JsonArray).orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.stringValue(field)?.takeIf(String::isNotBlank)
    }.distinct()
    return values.joinToString(", ")
}

private val json = Json { ignoreUnknownKeys = true }
