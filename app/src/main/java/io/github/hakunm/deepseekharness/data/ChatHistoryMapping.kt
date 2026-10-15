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

/**
 * 一条会话历史 → 一串展示项：**逐事件映射**。
 *
 * 入口是 [displayItems]（被 `ChatHistory.displayItems()` 调用，因此是 `internal`）。
 * 逐事件的规则都写在这里：
 * - `user/message` → 用户气泡，或**上下文注入**折叠块（判据是 `source.kind`）；
 * - `assistant/message` → 助手正文，reasoning 单独成项；
 * - `tool/call` → 工具行（见 `ChatToolPresentation.kt` 的 [toolItem]）；
 * - `turn/end` → 终局失败行（不是失败就整条不渲染）。
 *
 * 末尾的 [mergeToolRuns] 把**相邻**的工具行合并成一组（与 WebView 页同一口径）。
 * 交接附件的识别在 `ChatHandoff.kt`，工具行的判定在 `ChatToolPresentation.kt`。
 *
 * 从 `ChatPresentation.kt`（825 行）拆出，实现逐字未改。
 */

internal fun displayItems(
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
/**
 * 把**连续**的工具调用合并成一项，标题报动作摘要。
 *
 * 与 WebView 页的 `toolGroupSummary` 同一口径：各条短语按首次出现顺序去重求并集，
 * 超过 4 条收尾加「等」。原生原先是一次调用一行，一跑几十次就是一屏行；
 * 网页版是一组一行。
 *
 * 只合并**相邻**的 TOOL —— 中间夹了正文就断开，那说明确实是两段工作。
 */
internal fun mergeToolRuns(items: List<ChatDisplayItem>): List<ChatDisplayItem> {
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
