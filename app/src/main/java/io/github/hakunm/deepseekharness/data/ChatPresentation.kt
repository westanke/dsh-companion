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
    /**
     * 工具调用的动作短语，如 ["运行了代码", "修改了文件"]。
     *
     * 单独放一个字段而不是揉进 [title]：连续的多个工具调用要**合并成一行**，
     * 合并就是把各条的短语按首次出现顺序去重求并集。揉成字符串就得再拆开，
     * 那是在用分隔符当数据结构。
     */
    val actions: List<String> = emptyList(),
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

/**
 * 流式状态：**只装当前这一段（turn+step）**。
 *
 * 「turn/step 一变就整个换掉 base」是刻意的 —— 上一轮/上一段的内容由 REST 历史补回来，
 * 两边都留着就成了重复显示（见 LiveChatGroupingTest）。
 *
 * 但这条约定有个前提：**历史真的会被推回页面**。
 * 那个前提长期不成立（ChatWebScreen 里一处早退判据写反了，逢「liveChat 刚被置空」
 * 就 return），于是重置清掉的那部分没有任何东西补回来 —— 真机上就是
 * 「一直在同一个位置说这一段，就把上一段没了」。修的是那处推送，不是这里。
 */
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

fun ChatHistory.displayItems(): List<ChatDisplayItem> = mergeToolRuns(events.flatMap(::displayItems))

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

/**
 * 会话里 agent **显式交付**给用户的一个文件。
 *
 * 来源是内核的结构化事件 `deliverables/presented`（`present` 工具触发）：
 * `data.files[] = { path, description }` —— 绝对路径 + 人写的说明，
 * **不是**从工具参数里猜出来的。
 */
data class ChatDeliverable(
    val path: String,
    val description: String? = null,
    val seq: Int = 0,
)

/**
 * 取出会话里被显式交付的文件。
 *
 * 与 [io.github.hakunm.deepseekharness.data.SessionFileRefs] 是**两回事**：
 * 那个是从工具参数里嗅「提到过哪些路径」（带一堆刻意的取舍与白名单），
 * 这个读的是内核明说的「这是我给你的东西」，可靠度高得多、还带说明。
 *
 * 为什么以前 App 里看不到交付物：[displayItems] 只认 user/message、assistant/message、
 * tool/call 三类，`deliverables/presented` 落到 `else -> emptyList()` 被整类丢掉。
 * 于是用户问「我要你写的报告呢、你打好的包呢」—— 数据一直在日志里，就是没人读。
 *
 * 同一个路径出现多次时保留**最后一次**的说明（后写的更新）。
 */
fun ChatHistory.deliverables(): List<ChatDeliverable> {
    val out = LinkedHashMap<String, ChatDeliverable>()
    events.forEach { entry ->
        if (entry.event.type != "deliverables/presented") return@forEach
        val data = entry.event.data as? JsonObject ?: return@forEach
        val files = data["files"] as? JsonArray ?: return@forEach
        files.forEach { raw ->
            val file = raw as? JsonObject ?: return@forEach
            val path = file.stringValue("path")?.takeIf { it.isNotBlank() } ?: return@forEach
            out[path] = ChatDeliverable(path, file.stringValue("description"), entry.event.seq)
        }
    }
    return out.values.toList()
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
    val actions = toolActionsFor(name, arguments)
    return ChatDisplayItem(
        id = "${event.seq}-tool",
        seq = event.seq,
        kind = ChatItemKind.TOOL,
        // 标题就是动作短语（官方口径），不再是 "Code" / "Read" 这种英文类别词。
        title = actions.joinToString("，"),
        body = description ?: argumentsSummary(arguments) ?: viewTitle ?: name,
        actions = actions,
    )
}

/**
 * 把**连续**的工具调用合并成一项，标题报动作摘要。
 *
 * 与 WebView 页的 `toolGroupSummary` 同一口径：各条短语按首次出现顺序去重求并集，
 * 超过 4 条收尾加「等」。原生原先是一次调用一行，一跑几十次就是一屏行；
 * 网页版是一组一行。
 *
 * 只合并**相邻**的 TOOL —— 中间夹了正文就断开，那说明确实是两段工作。
 */
private fun mergeToolRuns(items: List<ChatDisplayItem>): List<ChatDisplayItem> {
    val out = mutableListOf<ChatDisplayItem>()
    var run = mutableListOf<ChatDisplayItem>()
    fun flush() {
        if (run.isEmpty()) return
        if (run.size == 1) { out += run[0]; run = mutableListOf(); return }
        val actions = mutableListOf<String>()
        run.forEach { item -> item.actions.forEach { if (it !in actions) actions += it } }
        val title = when {
            actions.isEmpty() -> run.size.toString() + " 次工具调用"
            actions.size > 4 -> actions.take(4).joinToString("，") + "等"
            else -> actions.joinToString("，")
        }
        out += run[0].copy(
            id = "tools-${run.first().seq}-${run.last().seq}",
            title = title,
            body = run.joinToString("\n") { it.body },
            actions = actions,
        )
        run = mutableListOf()
    }
    items.forEach { item ->
        if (item.kind == ChatItemKind.TOOL) run += item else { flush(); out += item }
    }
    flush()
    return out
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

/**
 * 一个工具调用的**动作短语**，如 ["运行了代码", "已写入文件", "执行了命令"]。
 *
 * 措辞与判定方式都照官方 Web 界面还原（`@deepseek-ai/dsh-client-ui-chat`）：
 * 那边是内核给一个 `summary.counts[{kind}]`，按 kind 取 i18n 文案再拼；
 * 我们的会话事件里**没有**这个结构化字段（`tool/call` 无 view/description、
 * `tool/result` 无 meta，已逐条核实），所以只能按工具名与参数近似还原。
 *
 * `run_code` 的处理来自真实数据统计：1129 次调用的 arguments 里，
 * `tools.bash` 748 次、`tools.edit` 158、`tools.read` 116、`tools.write` 53；
 * 其中 `bash` + `write` 这一组合 42 例，对应的正是官方那句
 * 「运行了代码，已写入文件，执行了命令」——所以扫参数是**结论**，不是猜测。
 *
 * 顺序按官方措辞对齐：edit 先于 write（官方是「修改了文件，已写入文件」）。
 */
private val INNER_ACTIONS = listOf(
    "tools.edit" to "修改了文件",
    "tools.write" to "已写入文件",
    "tools.read" to "读取了文件",
    "tools.bash" to "执行了命令",
    "tools.grep" to "搜索了内容",
    "tools.glob" to "搜索了文件",
)

internal fun toolActionsFor(name: String, arguments: String?): List<String> {
    val raw = arguments.orEmpty()
    if (name == "run_code") {
        val out = mutableListOf("运行了代码")
        INNER_ACTIONS.forEach { (needle, phrase) ->
            if (needle in raw && phrase !in out) out += phrase
        }
        return out
    }
    val byName = mapOf(
        "bash" to "执行了命令",
        "pwsh" to "执行了命令",
        "read" to "读取了文件",
        "write" to "已写入文件",
        "edit" to "修改了文件",
        "grep" to "搜索了内容",
        "glob" to "搜索了文件",
        "web_fetch" to "已访问网页",
        "web_search" to "已搜索网页",
    )
    byName[name]?.let { return listOf(it) }
    if (name.startsWith("mcp__")) return listOf("调用了 " + name.removePrefix("mcp__").replace("__", " · "))
    return listOf(name.ifBlank { "工具" })
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
