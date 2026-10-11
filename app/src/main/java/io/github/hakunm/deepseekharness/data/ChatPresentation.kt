package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import androidx.annotation.StringRes
import io.github.hakunm.deepseekharness.R

enum class ChatItemKind { USER, ASSISTANT, REASONING, CONTEXT, TOOL, FAILURE }

/**
 * 一轮模型运行的**终局失败**（限流 429、配额用尽、密钥无效、登出停止…）。
 *
 * 与服务端 `chat.turn.failed` 帧的 `data.failure` 一一对应。所有字段都有默认值：
 * 这是从事件里解出来的结构，而服务端的 `code` 可能是空串（免费额度限流就是），
 * 所以 [code] 必须**可区分「空串」与「没有」** —— 客户端显示错误码那一位时，
 * 空串也应该原样显示，不能当成缺失。
 *
 * [stopped] 为 true 表示这不是模型故障，而是「退出登录导致任务停止」。
 */
data class ChatTurnFailure(
    val message: String = "",
    val code: String? = null,
    val stopped: Boolean = false,
) {
    /**
     * 给人看的一行标题 —— 用**资源 id** 表达，具体语言交给界面。
     *
     * 这里以前直接写死中文（「任务已停止」/「本轮运行失败」），于是英文界面下也弹中文。
     * data 层不碰 Context，所以只给 id，渲染时再取字符串。
     */
    @get:StringRes
    val titleRes: Int get() = if (stopped) R.string.turn_stopped_title else R.string.turn_failed_title
}

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
    /**
     * 纯文本模型发图时的**交接文件路径**（服务器绝对路径），非交接图是 null。
     *
     * 为什么单独一个字段：这类图**不在附件存储里**（那只存会话里的原生 image 部件），
     * 它是插件落在会话工作目录 `.dsh-images/` 下的普通文件。所以取字节不能走
     * `readAttachment`，要走「路径 → 授权根」的官方解析（`resolvePath` + `readFile`）——
     * 那条路客户端早就有了，正是「已接入的图片显示方法」。
     */
    val handoffPath: String? = null,
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
    /**
     * 工具调用的**逐条明细**（输入 / 输出 / 成败 / 耗时）。
     *
     * 为什么是列表：连续的多次调用会被 [mergeToolRuns] 合并成一行，而详情视图要求
     * **每条都能单独展开** —— 只留第一条就等于把其余调用的输出全丢了。
     * 空列表 = 这一项不是工具调用，或者拿不到 tool/call 数据。
     */
    val tools: List<ToolCallDetail> = emptyList(),
    /**
     * 这一项是不是一轮的**终局失败**（限流 / 配额 / 密钥 / 登出停止）。
     *
     * 为什么单独一个字段而不是塞进 [body]：失败行有自己的一套版式（标题 + 正文 +
     * 错误码），而 [body] 在所有渲染器里都是「一段 Markdown 正文」。
     * 塞进去的话每个渲染器都要反向嗅探，与其那样不如直接给类型。
     */
    val failure: ChatTurnFailure? = null,
)

/**
 * 一次工具调用的完整明细 —— 详情视图要渲染的东西都在这儿。
 *
 * 字段全部对着**真实会话日志**（105 份 `session.v4.jsonl.zstd`，共 19050 条 tool/result）：
 * - `tool/call` 的 data：`turn`/`step`/`callId`/`name`/`arguments`；`arguments` 是**字符串**
 *   （12471/12471 都能 JSON.parse），所以要格式化后再显示。
 * - `tool/result` 的 data.message：`role`/`source`/`toolCallId`/`content`/`isError`/`id`，
 *   六个 key 19050/19050 全在；`content` **一定是数组**（19050/19050），元素只有
 *   `text`（19050）与 `image`（73）两种；`isError` 为 true 的 689 条。
 * - 关联字段用 `message.toolCallId`（与 `message.source.callId` 实测完全相等：8006/8006）。
 */
data class ToolCallDetail(
    val callId: String = "",
    val name: String = "",
    /** 内核给的原始 arguments（JSON 字符串，未格式化）。空串 = 这个工具没有参数。 */
    val arguments: String = "",
    /** 结果正文；`null` = 还没有 `tool/result`（例如仍在运行）。空串是合法的「有结果但没文字」。 */
    val result: String? = null,
    /**
     * 内核 `message.isError`。
     *
     * 注意它**不等于**「命令没报错」：实测样本里 `curl` 超时、输出全是 stderr 的那条
     * `isError` 仍是 false。它只是内核自己标记的工具级失败。
     */
    val failed: Boolean = false,
    /** `result.time - call.time`（毫秒）。配不上、或时钟倒退时是 `null`，UI 直接不显示这一行。 */
    val durationMs: Long? = null,
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

/**
 * 从 `chat.turn.failed` 帧里取出失败详情。
 *
 * 判据是 **type 精确等于** `chat.turn.failed`（插件 `normalizeDshEvent` 专门为
 * `turn/end` + `reason.kind === 'error'` 发的那帧），不是「任何带 failure 的帧」。
 * 这样做的好处是：插件没升级、老服务端只发普通 `chat.turn.end` 时，这里安静返回
 * null，客户端仍旧走原来的收尾路径，不会因为一个新字段抽风。
 */
fun WorkspaceEvent.chatTurnFailure(): ChatTurnFailure? {
    if (type != "chat.turn.failed") return null
    val value = data as? JsonObject ?: return null
    val failure = value.objectValue("failure") ?: return null
    return ChatTurnFailure(
        message = failure.stringValue("message").orEmpty(),
        // 注意：空串 code 要**留空串**，不能退回 null。免费额度限流的 code 就是 ""，
        // 而 UI 要照原样显示「错误码：」后面那一格。
        code = failure.stringValue("code"),
        stopped = failure.booleanValue("stopped") == true,
    )
}

fun ChatHistory.displayItems(): List<ChatDisplayItem> {
    // 先按 callId 把 tool/result 建成索引，再逐条渲染：
    // 结果的 callId 藏在 `data.message.toolCallId` 里，配对要跨事件 ——
    // 单看一条 entry 是配不上的（这正是以前 tool/result 整类被丢掉的原因）。
    val results = toolResultsByCallId(events)
    return mergeToolRuns(events.flatMap { displayItems(it, results) })
}

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

private fun displayItems(
    entry: HistoryEntry,
    toolResults: Map<String, ToolResultInfo> = emptyMap(),
): List<ChatDisplayItem> = when (entry.event.type) {
    "user/message" -> userItems(entry.event)
    "assistant/message" -> assistantItems(entry.event)
    "tool/call" -> listOf(toolItem(entry, toolResults))
    // 历史里的 `turn/end` 失败帧。
    //
    // 为什么必须在历史里也渲染：失败发生后用户往往**先退出再进会话**，
    // 或者往下翻记录。实时帧只到一次，过了就没了；历史是唯一能把
    // 「这一轮失败了、失败原因是什么」长期留住的地方。
    "turn/end" -> listOfNotNull(turnEndItem(entry.event))
    else -> emptyList()
}

/**
 * 历史里的 `turn/end` 帧 → 失败行；不是失败就返回 null（整条不渲染）。
 *
 * 判据与插件 `turnFailureOf` **同一套**：`reason.kind === 'error'`，
 * 或 `aborted` 且原因是退出登录。`max-tokens` 不在这里 —— 那是「回答被截断」，
 * 官方渲染成黄色警示而非失败，客户端按普通收尾处理，不误报。
 */
private fun turnEndItem(event: SessionEvent): ChatDisplayItem? {
    val data = event.data as? JsonObject ?: return null
    val reason = data.objectValue("reason") ?: return null
    val failure = when (reason.stringValue("kind")) {
        "error" -> {
            // error 不是对象的兜底与插件 `turnFailureOf` 一致（`String(error)`）：
            // 内核偶尔会给一个字符串错误，那时消息本身就是信息，不能整条丢掉。
            val error = reason["error"]
            val errorObj = error as? JsonObject
            ChatTurnFailure(
                message = errorObj?.stringValue("message")
                    ?: (error as? JsonPrimitive)?.contentOrNull
                    // 文案交给界面：按 code 本地化（认不出就显示标题那一句）。
                    // 这里写死任何一种语言，都会在另一种语言界面上出错。
                    ?: "",
                code = errorObj?.stringValue("code"),
                stopped = false,
            )
        }
        "aborted" -> {
            val inner = reason.objectValue("reason")
            if (inner?.stringValue("kind") == "hook" && inner.stringValue("reason") == "deepseek-account/signed-out") {
                ChatTurnFailure(
                    // 文案由界面按 code 本地化（`err_signed_out`）；这里留空，
                    // 免得 data 层再写死一种语言。
                    message = "",
                    code = "ACCOUNT_SIGNED_OUT",
                    stopped = true,
                )
            } else null
        }
        else -> null
    } ?: return null
    // message 为空（AUTH 被内核抹空）时仍然要显示 —— 那一行的标题与错误码就是信息。
    if (failure.message.isBlank() && failure.code.isNullOrEmpty()) return null
    return ChatDisplayItem(
        id = "${event.seq}-turn-failure",
        seq = event.seq,
        kind = ChatItemKind.FAILURE,
        failure = failure,
    )
}

private fun userItems(event: SessionEvent): List<ChatDisplayItem> {
    val data = event.data as? JsonObject ?: return emptyList()
    val source = data.objectValue("source")
    val kind = source?.stringValue("kind")
    // 交接说明**只可能出现在用户消息里**（插件改写的是用户发的那条 content）。
    // 上下文注入的正文里时不时就引用着路径 —— 记忆库文本、README 示例、上一轮的工具输出，
    // 拿真实会话跑一遍就抓到了：不设这道闸，那些正文会被切碎，还会凭空多出几张读不到的假附件。
    val isUser = kind == "user"
    val parts = contentTextParts(data["content"])
    val handoffPaths = if (isUser) parts.flatMap(::handoffPathsIn).distinct() else emptyList()
    // 按**行**摘而不是整条 part 摘：实测插件把说明放在独立 part 里，但万一哪天和用户
    // 正文同处一条 part，整条摘掉会把用户自己打的话一起吞了。
    val body = if (isUser) {
        parts.map(::stripHandoffLines).filter(String::isNotBlank).joinToString("\n\n")
    } else {
        contentText(data["content"])
    }
    val attachments = contentAttachments(data["content"]) + handoffPaths.map(::handoffAttachment)
    if (isUser) {
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

/** 交接说明里给机器读的那一行的标记；靠它把该行从正文里摘掉。 */
private const val HANDOFF_SOURCE_MARKER = "[Image: source: "

/** 交接说明里给人看的那一行的开头（`🖼️ 图片 1「shot.jpg」（…）`）。 */
private const val HANDOFF_PREAMBLE_MARKER = "🖼️ 图片 "

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
private val HANDOFF_SOURCE_PATTERN = Regex("""\[Image: source:\s*(.+)]""")

/** 把交接说明的两行从一段正文里摘掉，其余原样保留。 */
private fun stripHandoffLines(part: String): String = part
    .lineSequence()
    .filterNot { line ->
        line.contains(HANDOFF_SOURCE_MARKER) || line.trimStart().startsWith(HANDOFF_PREAMBLE_MARKER)
    }
    .joinToString("\n")
    .trim()

/** 一条 text part 里出现的交接路径（正常是 0 或 1 条）。 */
private fun handoffPathsIn(part: String): List<String> = HANDOFF_SOURCE_PATTERN
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
private fun handoffAttachment(path: String): ChatAttachment {
    val name = path.substringAfterLast('/').ifBlank { path }
    return ChatAttachment(
        attachmentId = HANDOFF_ATTACHMENT_PREFIX + path,
        mediaType = handoffMediaType(name),
        name = name,
        handoffPath = path,
    )
}

private fun handoffMediaType(name: String): String =
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

private fun toolItem(entry: HistoryEntry, toolResults: Map<String, ToolResultInfo>): ChatDisplayItem {
    val event = entry.event
    val data = event.data as? JsonObject
    val name = data?.stringValue("name").orEmpty()
    val view = (entry.view as? JsonObject)?.objectValue("view")
    val viewTitle = view?.stringValue("title")
    val description = view?.stringValue("description")
    val arguments = data?.stringValue("arguments")
    val actions = toolActionsFor(name, arguments)
    val callId = data?.stringValue("callId").orEmpty()
    val result = toolResults[callId]
    return ChatDisplayItem(
        id = "${event.seq}-tool",
        seq = event.seq,
        kind = ChatItemKind.TOOL,
        // 标题就是动作短语（官方口径），不再是 "Code" / "Read" 这种英文类别词。
        title = actions.joinToString("，"),
        body = description ?: argumentsSummary(arguments) ?: viewTitle ?: name,
        actions = actions,
        tools = listOf(
            ToolCallDetail(
                callId = callId,
                name = name,
                arguments = arguments.orEmpty(),
                result = result?.text,
                failed = result?.failed == true,
                durationMs = toolDurationMs(event.time, result?.time),
            ),
        ),
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
            // 合并的是**显示**，不是数据：每一调的明细都要留着，
            // 详情视图才能逐条展开（只留第一条 = 其余调用的输出全丢）。
            tools = run.flatMap { it.tools },
        )
        run = mutableListOf()
    }
    items.forEach { item ->
        if (item.kind == ChatItemKind.TOOL) run += item else { flush(); out += item }
    }
    flush()
    return out
}

private fun contentText(content: JsonElement?): String =
    contentTextParts(content).filter(String::isNotBlank).joinToString("\n\n")

/**
 * 把 content 拆成逐条文本。
 *
 * 与 [contentText] 的区别是**不合并**：交接说明必须能被整条摘掉，
 * 合并之后再按行切割就成了「拿分隔符当数据结构」。
 */
private fun contentTextParts(content: JsonElement?): List<String> = when (content) {
    is JsonPrimitive -> listOfNotNull(content.contentOrNull)
    is JsonArray -> content.mapNotNull { block ->
        val value = block as? JsonObject ?: return@mapNotNull null
        when (value.stringValue("type")) {
            "text", "reasoning" -> value.stringValue("text")
            else -> null
        }
    }

    else -> emptyList()
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

/**
 * 一条 `tool/result` 的可用信息（内部结构，只在这个文件里流转）。
 *
 * `time` 留下来是为了算耗时：事件顶层的 `time` 与 `tool/call` 的相减就是墙钟耗时。
 */
private data class ToolResultInfo(val text: String, val failed: Boolean, val time: Long)

/**
 * 把 `tool/result` 按 callId 建索引。
 *
 * 关联字段优先用 `data.message.toolCallId`，退路是 `data.message.source.callId`：
 * 实测两者在 8006/8006 条里完全相等，但只认一个的话，内核哪天只留另一个就全配不上了。
 * 两个都拿不到的行直接跳过（宁可不显示，也不猜）。
 */
private fun toolResultsByCallId(events: List<HistoryEntry>): Map<String, ToolResultInfo> {
    val out = HashMap<String, ToolResultInfo>()
    events.forEach { entry ->
        val event = entry.event
        if (event.type != "tool/result") return@forEach
        val data = event.data as? JsonObject ?: return@forEach
        val message = data.objectValue("message") ?: return@forEach
        val callId = message.stringValue("toolCallId")
            ?: message.objectValue("source")?.stringValue("callId")
            ?: return@forEach
        out[callId] = ToolResultInfo(
            text = toolResultText(message),
            failed = message.booleanValue("isError") == true,
            time = event.time,
        )
    }
    return out
}

/**
 * 一个工具结果的正文。
 *
 * 内核的 `message.content` 实测**一定是数组**（19050/19050），元素只有两种：
 * `text`（取 text）与 `image`（只有附件引用，字节在服务端附件存储里）。
 * 图片这里用一行占位写出来，而不是静默丢掉 —— 「这里本来有一张图」也是信息。
 *
 * 空段丢掉、段间空行，与网页版 `partsText(content, 'text')` 同一口径。
 */
private fun toolResultText(message: JsonObject): String {
    val content = message["content"] ?: return ""
    val parts = content as? JsonArray ?: return (content as? JsonPrimitive)?.contentOrNull.orEmpty()
    return parts.mapNotNull { raw ->
        val part = raw as? JsonObject ?: return@mapNotNull null
        when (part.stringValue("type")) {
            "text" -> part.stringValue("text")
            "image" -> {
                val attachment = part.objectValue("attachment")
                val name = attachment?.stringValue("name")?.takeIf(String::isNotBlank) ?: "attachment"
                val width = attachment?.intValue("width") ?: 0
                val height = attachment?.intValue("height") ?: 0
                if (width > 0 && height > 0) "[image $name ${width}×$height]" else "[image $name]"
            }
            else -> null
        }
    }.filter(String::isNotBlank).joinToString("\n\n")
}

/**
 * 耗时 = `tool/result.time - tool/call.time`（都是 epoch 毫秒）。
 *
 * 结果为负说明时钟倒退（机器休眠恢复、NTP 校时——实测日志里有一条 bash 差 9821 秒，
 * 那是挂起时间不是执行时间）。这种情况**不显示**，而不是显示一个负数或 0：
 * 宁可少一行，也不要瞎编。
 */
internal fun toolDurationMs(callTime: Long, resultTime: Long?): Long? {
    if (resultTime == null) return null
    val delta = resultTime - callTime
    return if (delta < 0) null else delta
}

/**
 * 耗时的人类可读格式。
 *
 * 刻意**不进字符串资源**：ms / s / m / h 是通用单位，翻成「毫秒/秒/分」只会更长、
 * 并不会更好读；而这一行本来就是元数据。边界都在单测里钉住。
 */
internal fun formatToolDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> "${"%.1f".format(Locale.US, ms / 1000.0)} s"
    ms < 3_600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    else -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
}

/**
 * 把内核给的 arguments 格式化成可读 JSON。
 *
 * `arguments` 是**字符串**（实测 12471/12471 可解析），塞进界面之前要缩进；
 * 但解析失败就原样返回 —— 宁可给一坨原始文本，也不要把参数藏起来
 * （真机上曾经因为「坏 JSON 一律丢弃」而让整条调用看起来没有输入）。
 */
internal fun prettyToolArguments(raw: String): String {
    if (raw.isBlank()) return ""
    val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), element) }.getOrNull() ?: raw
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.arrayLabels(member: String, field: String): String {
    val values = (this[member] as? JsonArray).orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.stringValue(field)?.takeIf(String::isNotBlank)
    }.distinct()
    return values.joinToString(", ")
}

private val json = Json { ignoreUnknownKeys = true }

/** 只用于把 arguments 缩进显示；解析仍走 [json]。 */
private val prettyJson = Json { prettyPrint = true }
