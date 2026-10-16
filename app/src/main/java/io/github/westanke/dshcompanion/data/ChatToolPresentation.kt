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
 * 工具调用的**展示口径**：动作短语、连续调用合并、逐条详情与耗时。
 *
 * 三件事各自有据：
 * - [toolActionsFor] 的措辞照官方 Web 界面还原。我们的会话事件里没有
 *   `summary.counts`，只能按工具名与参数近似 —— `run_code` 扫参数那条来自真实统计
 *   （1129 次调用：`tools.bash` 748、`tools.edit` 158、`tools.read` 116、`tools.write` 53），
 *   其中 `bash` + `write` 组合 42 例，正是官方那句「运行了代码，已写入文件，执行了命令」；
 * - [mergeToolRuns] 只合并**相邻**的工具行（中间夹正文即断开），与网页版
 *   `toolGroupSummary` 同一口径；
 * - [toolResultsByCallId] 跨事件配对：结果的 callId 藏在 `data.message.toolCallId`，
 *   单看一条 entry 配不上 —— 这正是以前 `tool/result` 整类被丢掉的原因。
 *
 * 耗时判负（时钟倒退）时**不显示**，而不是显示 0 或负数：实测日志里有一条 bash
 * 差 9821 秒，那是挂起时间不是执行时间。宁可少一行，也不要瞎编。
 *
 * 从 `ChatPresentation.kt`（825 行）拆出，实现逐字未改。
 */

internal fun toolItem(entry: HistoryEntry, toolResults: Map<String, ToolResultInfo>): ChatDisplayItem {
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
        // 时间用于过程组的「耗时」摘要（组内首尾取差）。
        time = event.time,
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

internal fun argumentsSummary(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parsed = runCatching { chatJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
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
internal data class ToolResultInfo(val text: String, val failed: Boolean, val time: Long)

/**
 * 把 `tool/result` 按 callId 建索引。
 *
 * 关联字段优先用 `data.message.toolCallId`，退路是 `data.message.source.callId`：
 * 实测两者在 8006/8006 条里完全相等，但只认一个的话，内核哪天只留另一个就全配不上了。
 * 两个都拿不到的行直接跳过（宁可不显示，也不猜）。
 */
internal fun toolResultsByCallId(events: List<HistoryEntry>): Map<String, ToolResultInfo> {
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
internal fun toolResultText(message: JsonObject): String {
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
    val element = runCatching { chatJson.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return runCatching { chatPrettyJson.encodeToString(JsonElement.serializer(), element) }.getOrNull() ?: raw
}
