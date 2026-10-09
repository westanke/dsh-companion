package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 一条助手正文切出来的片段：普通文字，或一个 dsh-ui 交互卡片。
 *
 * 现状：网页版把 ```` ```dsh-ui ```` 围栏交给渲染器画成真组件；App 原来把它当普通
 * 代码块，于是用户看到的是一坨裸 JSON —— **网页版有的东西，手机上没有**。
 */
sealed interface MessageSegment {
    data class Prose(val text: String) : MessageSegment
    data class Ui(val json: String) : MessageSegment
}

private const val FENCE_MARK = "```"
private const val LANG_MARK = "dsh-ui"

/** 标记之间的空白：空格或制表符。 */
private val WHITESPACE = Regex("[ \\t]+")

/**
 * 判断一行围栏头是不是 dsh-ui 卡片。
 *
 * **为什么不能只匹配 ` ```dsh-ui `**：genui skill 自己的文档里就有两种合法写法 ——
 * ` ```dsh-ui ` 与 ` ```json dsh-ui `（后者标了 JSON 高亮）。而模型写出来的是哪种并不
 * 统一。只认精确前缀的后果是：网页版能渲染的卡片，App 里退化成一个 JSON 代码块 ——
 * 用户看到的正是「有的能弹、有的显示成 md 模式」。
 *
 * 判据是**标记里含有 `dsh-ui`**，而不是位置或顺序：
 * - ` ```json dsh-ui ` 含 dsh-ui → 是；
 * - ` ```dsh-ui ` 含 dsh-ui → 是；
 * - ` ```json `、` ```mermaid `、普通 ` ``` ` → 不是；
 * - ` ```dsh-ui-bad ` 含前缀但不是标记 → 不认（否则会吞掉别的语言名）。
 */
private fun isGenUiInfo(line: String): Boolean {
    if (!line.startsWith(FENCE_MARK)) return false
    val info = line.trim().removePrefix(FENCE_MARK).trim()
    if (info.isEmpty()) return false
    return info.split(WHITESPACE).any { it == LANG_MARK }
}

/**
 * 把助手正文按 dsh-ui 围栏切开。
 *
 * 四条硬规则，每条都对应一个真实的坑：
 *
 * 1. **必须有闭合围栏才切**。流式输出时围栏是逐字到达的，开头 ``` 已出现而结尾还没到；
 *    此时就切出去会得到半截 JSON，卡片解析必然失败，用户看到的是卡片凭空消失。
 *    所以未闭合时**整段按原文保留**，等闭合了再切。退化行为是「显示原始代码块」，
 *    比「显示一个闪烁的加载框」诚实。
 *
 * 2. **只看行首的围栏头**。正文中间出现的 ` ``` ` 是代码块，不能当卡片边界 ——
 *    否则一条「教你写 dsh-ui 围栏」的消息会被自己切成两半。
 *
 * 3. **围栏内容原样透传，不在这里解析**。解析失败要能在 UI 层给出「这条卡片语法有问题」
 *    的提示，而不是让整条消息消失。所以本函数只做切割，JSON 合法性交给 [parseGenUi]。
 *
 * 4. **空卡片不切**。`{"items":[]}` 切出来是个空壳，渲染成空气比不切更糟。
 */
fun splitMessageBody(body: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    // 用「已消费到哪」这一个游标推进，不用 StringBuilder 累加：
    // 单一状态来源，杜绝 tail 与游标对不上的情况。
    var cursor = 0
    while (cursor < body.length) {
        val open = fenceHeadAt(body, cursor) ?: break
        val headEnd = body.indexOf('\n', open)
        // 围栏头后面没有换行 —— 流式输出正好停在这一行，等下一片再切。
        if (headEnd < 0) return listOf(MessageSegment.Prose(body))
        val close = body.indexOf(FENCE_MARK, headEnd + 1)
        // 未闭合：**一字不动地**把整段交回去，连空白都不动。
        // 流式输出时每个中间态都会走到这里，用户看到的是逐字长出来的代码块；
        // 等闭合的那一次才切成卡片。trim 会在每次重渲染时轻微抖动，所以刻意不做。
        if (close < 0) return listOf(MessageSegment.Prose(body))
        body.substring(cursor, open).trim().takeIf(String::isNotEmpty)?.let {
            segments += MessageSegment.Prose(it)
        }
        val json = body.substring(headEnd + 1, close)
        if (json.isNotBlank()) segments += MessageSegment.Ui(json)
        val after = body.indexOf('\n', close + FENCE_MARK.length)
        cursor = if (after < 0) body.length else after + 1
    }
    body.substring(cursor).trim().takeIf(String::isNotEmpty)?.let {
        segments += MessageSegment.Prose(it)
    }
    return segments.ifEmpty { listOf(MessageSegment.Prose(body)) }
}

/**
 * 从 `from` 起找下一个 dsh-ui 围栏头的起始下标。
 *
 * 逐行找而不是 `indexOf("dsh-ui")`：语言标记必须**独占一行且位于行首**。
 * 正文里出现「见 dsh-ui 文档」这种字样时绝不能当成边界。
 */
private fun fenceHeadAt(body: String, from: Int): Int? {
    var lineStart = from
    while (lineStart <= body.length) {
        val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
        val line = body.substring(lineStart, lineEnd)
        // 只在行首是 ``` 的行上做判断；缩进过的围栏不算。
        if (line.startsWith(FENCE_MARK) && isGenUiInfo(line)) return lineStart
        if (lineEnd >= body.length) break
        lineStart = lineEnd + 1
    }
    return null
}

/** 一个组件节点：保留原始 JSON 供渲染器读取。 */
data class GenUiNode(val type: String, val value: JsonObject)

data class GenUiSpec(
    val title: String? = null,
    val gap: Int = 12,
    val items: List<GenUiNode> = emptyList(),
)

/**
 * 解析一段 dsh-ui JSON。
 *
 * 返回 `null` 表示**这条卡片无法显示**，UI 层据此回落到「原始代码块」——
 * 与网页版的「坏组件被丢弃、其余照常」一致，但多一层保险：整段坏掉时至少内容还在。
 * 单个节点坏掉只丢那个节点，不牵连整份 spec。
 */
fun parseGenUi(source: String): GenUiSpec? {
    val root = runCatching { json.parseToJsonElement(source) }.getOrNull() as? JsonObject ?: return null
    val items = root.array("items").orEmpty().mapNotNull(::node)
    if (items.isEmpty()) return null
    return GenUiSpec(title = root.str("title"), gap = root.int("gap") ?: 12, items = items)
}

private fun node(element: JsonElement): GenUiNode? {
    val value = element as? JsonObject ?: return null
    val type = value.str("type")?.takeIf(String::isNotBlank) ?: return null
    if (type !in SUPPORTED) return null
    return GenUiNode(type, value)
}

/**
 * 只列「我们真的渲染了」的类型。
 *
 * 列全词表没有意义 —— 渲染不了还认得，等于让用户点一个没反应的按钮，
 * 那比明确地不显示更糟。未知 type 走 [node] 的丢弃分支。
 */
private val SUPPORTED = setOf(
    "text", "row", "col", "grid", "card", "divider", "spacer",
    "stat", "badge", "progress", "list", "table", "keyvalue", "timeline",
    "code", "json", "diff", "callout", "steps", "chart",
    "button", "radio", "submit",
)

/** `items` 里的子节点；布局容器（row/col/grid/card）用。 */
fun GenUiNode.children(): List<GenUiNode> = value.array("items").orEmpty().mapNotNull(::node)

/**
 * 一条列表项。
 *
 * `list` 的 `items` 允许纯字符串，也允许 `{title, desc}` 对象，两种都要收。
 */
data class GenUiListItem(val title: String, val desc: String? = null)

fun GenUiNode.listItems(): List<GenUiListItem> = value.array("items").orEmpty().mapNotNull { entry ->
    when (entry) {
        is JsonPrimitive -> entry.content.takeIf(String::isNotBlank)?.let { GenUiListItem(it) }
        is JsonObject -> {
            val title = entry.str("title")
                ?: entry.str("label")
                ?: entry.str("content")
            if (title.isNullOrBlank()) null else GenUiListItem(title, entry.str("desc"))
        }

        else -> null
    }
}

/** `button` 的文案。 */
fun GenUiNode.label(): String? =
    value.str("label") ?: value.str("content") ?: value.str("title")

/** 卡片式节点的标题。 */
fun GenUiNode.title(): String? = value.str("title") ?: value.str("label")

/**
 * 正文文字，去掉行内标记。
 *
 * 只剥 `**加粗**` / `==高亮==` / `` `代码` `` 三种，不做完整 Markdown —— 网页版能渲染
 * 的是行内富文本这一层，表格和围栏本来就不该出现在文字字段里。
 */
fun GenUiNode.inlineText(key: String = "content"): String =
    inlineMarkup(value.str(key).orEmpty())

fun inlineMarkup(raw: String): String = raw
    .replace("**", "")
    .replace("==", "")
    .replace(Regex("`([^`]*)`"), "$1")

fun GenUiNode.int(key: String): Int? = (value[key] as? JsonPrimitive)?.intOrNull

fun GenUiNode.number(key: String): Double? = (value[key] as? JsonPrimitive)?.doubleOrNull

fun GenUiNode.bool(key: String): Boolean? = (value[key] as? JsonPrimitive)?.booleanOrNull

fun GenUiNode.str(key: String): String? = (value[key] as? JsonPrimitive)?.content

/**
 * 一条助手消息的 dsh-ui 诊断报告。
 *
 * **为什么需要它**：这条链路上「围栏没被识别」「JSON 坏了」「节点类型不认识」
 * 三种失败在屏幕上长得几乎一模一样 —— 都是一段看着别扭但读得懂的文本。
 * 没有实机可验证时，靠截图猜是哪一种，等于每次改代码都在掷骰子。
 * 这里把三段式（切分 → 解析 → 分发）的每一步结果都变成一行可读文字，
 * 用户截图发回来就是完整现场。
 *
 * [summary] 一行摘要，直接显示在消息下方；
 * [details] 全文，包含各节点类型与被丢弃的类型名。
 */
data class GenUiDiagnosis(val summary: String, val details: String)

/**
 * 对一条消息正文跑一遍三段式诊断，**不改变渲染行为**。
 *
 * 关键点：这里刻意**不调用** [splitMessageBody] 与 [parseGenUi] 的结果去做判断，
 * 而是独立复算一遍，这样「解析器认为成功但渲染仍失败」这种不一致也会暴露出来。
 */
fun diagnoseGenUi(body: String): GenUiDiagnosis {
    val scan = fenceScan(body)
    val fenceCount = scan.heads
    val jsonFence = scan.genUiHeads
    val segments = splitMessageBody(body)
    val uiCount = segments.count { it is MessageSegment.Ui }
    if (uiCount == 0) {
        // 没有卡片段。三种成因对用户的意义完全不同，必须分开报：
        //
        // 1. 一个围栏头都没有 → 本条本来就是纯文字，正常；
        // 2. **认出了 dsh-ui 围栏头**但没切出卡片段 → 流式输出中围栏尚未闭合，正常，
        //    绝不能报成故障，否则用户会盯着一个「⚠」等它自己消失；
        // 3. 见到**无语言标记**的 ``` → 真故障，最可疑：正文里混进了没带 dsh-ui 的卡片，
        //    网页版能认出来而 App 认不出 ——「有的弹得出来、有的显示成 md 模式」正是这个形态。
        //
        // 刻意不区分它是头还是闭合：裸 ``` 天生二义（两个一组闭合、落单一个才是头），
        // 单看字符猜不出是谁。**如实报数量**，让用户截图发回完整现场，比猜一个结论有用。
        return when {
            jsonFence > 0 -> GenUiDiagnosis(
                "dsh-ui 围栏已识别，尚未闭合（流式输出中）",
                "围栏头=$jsonFence 闭合=0 —— 等输出结束会自动切成卡片",
            )

            scan.bareMarkers > 0 -> GenUiDiagnosis(
                "⚠ 见到 ${scan.bareMarkers} 个无标记 ``` 围栏，疑似卡片没写 dsh-ui",
                "带标记的围栏头=$fenceCount dsh-ui头=0 裸标记=${scan.bareMarkers}" +
                    " —— 若正文里本该是卡片，网页版能渲染而 App 认不出",
            )

            else -> GenUiDiagnosis("无 dsh-ui 围栏（正常，本条纯文字）", "fences=0")
        }
    }
    val parsed = segments.filterIsInstance<MessageSegment.Ui>().map { it to parseGenUi(it.json) }
    val bad = parsed.filter { it.second == null }
    val good = parsed.filter { it.second != null }
    val nodes = parsed.mapNotNull { it.second }.flatMap { spec -> spec.items }
    // 「被丢弃」的节点其实在 parseGenUi 里就已经滤掉了（node() 对未知 type 返回 null），
    // 所以这里报告的是**幸存下来的**类型分布；要看到被丢弃的类型名得看 summary 里的提示。
    val known = nodes.groupingBy { it.type }.eachCount()
    val parts = buildList {
        add("ui=$uiCount")
        if (bad.isNotEmpty()) add("JSON坏=${bad.size}")
        add("节点=${nodes.size}(${known.keys.joinToString("/").ifEmpty { "无" }})")
    }
    val summary = if (bad.isEmpty()) "dsh-ui ✓ ${parts.joinToString(" ")}" else "⚠ 卡片 JSON 解析失败 ×${bad.size}"
    val details = buildString {
        appendLine("围栏头=$fenceCount（其中 dsh-ui=$jsonFence）")
        appendLine("切出片段=${segments.size}，其中卡片段=$uiCount")
        appendLine("解析成功=${good.size} 失败=${bad.size}")
        appendLine("节点类型：${known.entries.joinToString(", ") { "${it.key}×${it.value}" }.ifEmpty { "（无）" }}")
        if (bad.isNotEmpty()) {
            val seg = bad.first().first
            val err = runCatching { json.parseToJsonElement(seg.json) }.exceptionOrNull()?.message
            appendLine("首个失败片段：${err ?: "返回 null（结构不符或 items 为空）"}")
        }
    }
    return GenUiDiagnosis(summary, details)
}

/**
 * 扫一遍正文，数出行首围栏里有多少**头**、其中多少是 dsh-ui 的头。
 *
 * 为什么必须带状态：裸 ``` （无语言标记）看起来和闭合围栏一模一样，
 * 单看一行无法区分「开头」还是「结尾」。所以用 insideFence 记录当前是否在围栏内 ——
 * 在里面遇到的裸 ``` 是闭合，在外面遇到的就是开头。
 * 早先的版本直接数行首 ```，于是每段卡片都被算成 2 个围栏，
 * 报出「见到 2 个围栏但都没被识别」这种把人往错方向引的话。
 *
 * [bareMarkers] 单独记裸标记数：它们天生二义（可能是无标记代码块的**头**，
 * 也可能只是闭合行），扫不准就如实报数量，不硬猜哪个是头。
 */
private fun fenceScan(body: String): FenceScan {
    var heads = 0
    var genUiHeads = 0
    var bare = 0
    var insideFence = false
    var lineStart = 0
    while (lineStart <= body.length) {
        val lineEnd = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
        val line = body.substring(lineStart, lineEnd)
        if (line.startsWith(FENCE_MARK)) {
            val info = line.trim().removePrefix(FENCE_MARK).trim()
            when {
                info.isEmpty() -> {
                    bare++
                    insideFence = !insideFence
                }
                insideFence -> Unit // 围栏内部又出现标记行：忽略，不能算头
                else -> {
                    heads++
                    if (isGenUiInfo(line)) genUiHeads++
                    insideFence = true
                }
            }
        }
        if (lineEnd >= body.length) break
        lineStart = lineEnd + 1
    }
    return FenceScan(heads, genUiHeads, bare)
}

private data class FenceScan(val heads: Int, val genUiHeads: Int, val bareMarkers: Int)

/** 公开的 JsonObject 访问器：UI 层要直接读 `pairs`/`rows`/`diffs` 这些嵌套结构。 */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private val json = Json { ignoreUnknownKeys = true }