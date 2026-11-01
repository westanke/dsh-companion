package io.github.westanke.dshcompanion.data

/**
 * 把消息正文里**已知的会话文件**变成可点链接。
 *
 * ## 为什么需要
 *
 * 模型经常在正文里顺手写一句「已落盘：`dsh-public-deployments-项目适配评估_2026-10-19.md`」
 * 或者带个 📄 图标加文件名 —— 那是**纯文本**，不是 Markdown 链接，于是 App 只把它当
 * 一句话渲染，用户去点它自然毫无反应（2026-10-19 真机反馈的就是这个：
 * 「会话中有个文件，我试了打不开」；同一文件从「本会话的文件」面板点得开，
 * 说明链路本身没问题，缺的只是**正文里那句文字不是链接**）。
 *
 * ## 为什么按「文件名」匹配，而不是整条绝对路径
 *
 * 模型写进正文的通常只有**文件名**（整条 `/media/wangke/...` 太长，它一般省略）。
 * 所以拿会话已知文件的 `fileName` 去正文里找。
 *
 * ## 三条安全约束（宁可不加链接，也不打开错的文件）
 *
 * 1. **只有唯一文件名才替换**：同一个文件名同时出现在多个目录时**跳过** ——
 *    点开后打开另一个目录里的同名文件，比「点不开」严重得多。
 * 2. **不碰已有的 Markdown 链接 / 代码块**：正文里 `[text](path)` 已经能点，
 *    再包一层会得到 `[[text](path)](path)`；围栏代码块与行内代码里的一切都是**字面量**，
 *    往里塞 Markdown 语法只会让它原样显示。
 * 3. **只替换第一次出现**：同一消息里重复提到同一个文件名时，只让第一处可点，
 *    避免整段文字被链接语法淹没。
 *
 * ## 行内代码（#10，2026-11-01 真机取证）
 *
 * 模型写文件名时**习惯性**套一对反引号（`` `report.md` ``）。早先的实现只替换文件名本身、
 * 把反引号原样留着，于是产出 `` `[report.md](/abs/path)` `` —— 渲染器按 CommonMark 把
 * 整段当**行内代码**原样显示，屏幕上就出现一长串 `[文件名](/media/wangke/...)` 的死文本，
 * 点不动（用户截图：「安卓直接显示的路径」）。这不是渲染器坏了，是**我们喂给它的
 * Markdown 本身自相矛盾**。
 *
 * 现在的处理：行内代码的内容**恰好**是某个已知文件名时，连反引号一起换成链接
 * （`` `report.md` `` → `[report.md](/abs/path)`）；内容不是「就是那个文件名」时
 * （例如 `` `/media/.../ChatMarkdownTable.kt` `` 这种整条路径、或者一句带文件名的代码）
 * **原样保留**，绝不往代码里注入 Markdown。代价是这一小段失去等宽样式 —— 但用户要的是
 * 「点了能打开文件」，一个点不动的等宽路径对他毫无价值；鸿蒙版也是这个取向（它压根不
 * 识别行内代码，一律按链接渲染），两端因此对齐。
 */
internal fun linkifyKnownFiles(text: String, knownAbsolutePaths: Collection<String>): String {
    if (text.isEmpty() || knownAbsolutePaths.isEmpty()) return text

    // 文件名 → 绝对路径；同名多路径的整条丢弃（见约束 1）。
    val uniqueByName: Map<String, String> = knownAbsolutePaths
        .mapNotNull { path ->
            val name = path.substringAfterLast('/').takeIf(String::isNotBlank) ?: return@mapNotNull null
            name to path
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, paths) -> paths.distinct() }
        .filterValues { it.size == 1 }
        .mapValues { (_, paths) -> paths.single() }

    if (uniqueByName.isEmpty()) return text

    // 长名优先：`a.md` 不能抢在 `report-a.md` 前面命中。
    val ordered = uniqueByName.entries.sortedByDescending { it.key.length }
    // 约束 3：整条消息里每个文件名只链接一次，跨行/跨行内代码统一计数。
    val linked = HashSet<String>()
    val out = StringBuilder(text.length + 64)

    // 围栏代码块内部是字面量（约束 2）。围栏符号只认同种闭合，与 Markdown 规范一致。
    var fenceMarker: Char? = null
    val lines = text.split('\n')
    for ((index, line) in lines.withIndex()) {
        if (index > 0) out.append('\n')
        val fenceHead = fenceHeadOf(line)
        if (fenceHead != null) {
            if (fenceMarker == null) {
                fenceMarker = fenceHead
            } else if (fenceMarker == fenceHead) {
                fenceMarker = null
            }
            out.append(line)
            continue
        }
        if (fenceMarker != null) {
            out.append(line)
            continue
        }
        forEachInlineSegment(line) { isCode, raw, content ->
            if (!isCode) {
                out.append(linkifyPlainText(raw, ordered, linked))
                return@forEachInlineSegment
            }
            val name = content.trim()
            val absolutePath = uniqueByName[name]
            if (absolutePath != null && name !in linked) {
                out.append('[').append(name).append("](").append(absolutePath).append(')')
                linked += name
            } else {
                // 同源场景：行内代码里**整段就是**一条指向已知文件的 Markdown 链接
                // （模型偶尔把 `[name](path)` 也套进反引号）。这里把它解出来 —— 否则
                // 屏幕上是「一条长得像链接却点不动的等宽文本」，正是用户截图里的样子。
                val unwrapped = unwrapLinkToKnownFile(content, uniqueByName)
                if (unwrapped != null) out.append(unwrapped) else out.append(raw)
            }
        }
    }
    return out.toString()
}

/** 行首（允许缩进）围栏头返回围栏符号，否则 null。 */
private fun fenceHeadOf(line: String): Char? {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("```") -> '`'
        trimmed.startsWith("~~~") -> '~'
        else -> null
    }
}

/**
 * 把一行拆成「行内代码段」与「普通文字段」依次回调。
 *
 * 行内代码用**等长**的反引号串闭合（`` ``a`b`` `` 里那个单反引号是字面量），所以闭合
 * 要按长度配对而不是「下一个反引号」。找不到闭合时按普通文字处理 —— 流式输出里
 * 半截的反引号很常见，不能因此把后半行全当代码吞掉。
 *
 * 回调参数：是否代码段、原文（含分隔符）、内容（不含分隔符）。
 */
private inline fun forEachInlineSegment(
    line: String,
    action: (isCode: Boolean, raw: String, content: String) -> Unit,
) {
    var index = 0
    var textStart = 0
    while (index < line.length) {
        if (line[index] != '`') {
            index++
            continue
        }
        var run = 1
        while (index + run < line.length && line[index + run] == '`') run++
        var probe = index + run
        var closeAt = -1
        while (probe < line.length) {
            if (line[probe] == '`') {
                var closeRun = 1
                while (probe + closeRun < line.length && line[probe + closeRun] == '`') closeRun++
                if (closeRun == run) {
                    closeAt = probe
                    break
                }
                probe += closeRun
            } else {
                probe++
            }
        }
        if (closeAt < 0) {
            index += run
            continue
        }
        if (index > textStart) action(false, line.substring(textStart, index), line.substring(textStart, index))
        action(true, line.substring(index, closeAt + run), line.substring(index + run, closeAt))
        index = closeAt + run
        textStart = index
    }
    if (textStart < line.length) action(false, line.substring(textStart), line.substring(textStart))
}

/** 普通文字里的裸文件名 → 链接；已有的 Markdown 链接靠括号边界躲开。 */private fun linkifyPlainText(
    text: String,
    ordered: List<Map.Entry<String, String>>,
    linked: MutableSet<String>,
): String {
    var result = text
    for ((name, absolutePath) in ordered) {
        if (name in linked || !result.contains(name)) continue
        // 边界：前后不能紧贴 URL 字符**与方/圆括号** —— 后者正是
        // `[看报告](xxx.md)` 的形状；只排除 \w 会把里面那层再包一遍，
        // 得到 `[看报告]([xxx.md](/abs/path))`（单测 `doesNotWrapAnExistingMarkdownLinkTwice`
        // 抓到的就是这个）。
        val pattern = Regex("""(?<![\w./\-()\[\]])""" + Regex.escape(name) + """(?![\w/\-()\[\]])""")
        val match = pattern.find(result) ?: continue
        result = result.replaceRange(match.range, "[$name]($absolutePath)")
        linked += name
    }
    return result
}

/**
 * 行内代码内容**整段**是一条指向已知文件的 Markdown 链接时，返回去掉反引号后的链接；
 * 否则返回 null（交给调用方原样输出）。
 *
 * 只认「整段就是一个链接」：`[name](path)` 前后有任何别的字符都不算 —— 那说明这段代码
 * 是别的东西（一行命令、一段表达式），往里注入 Markdown 只会让它原样显示。
 * 目标路径必须命中已知文件集合，避免把任意 URL 也从代码里解放出来。
 */
private fun unwrapLinkToKnownFile(
    content: String,
    uniqueByName: Map<String, String>,
): String? {
    val trimmed = content.trim()
    val match = LINK_ONLY.matchEntire(trimmed) ?: return null
    val name = match.groupValues[1]
    val target = match.groupValues[2].trim()
    val known = uniqueByName[name] ?: return null
    // 目标可能写的是绝对路径、也可能只写了文件名；两者都接受，但必须是**同一个文件**。
    return when {
        target == known -> "[$name]($known)"
        target == name -> "[$name]($known)"
        else -> null
    }
}

/** `[显示文字](目标)` 且前后无杂物。目标里允许成对的括号（路径里偶尔有）。 */
private val LINK_ONLY = Regex("""^\[([^\]\n]+)\]\(([^()\n]*(?:\([^()\n]*\)[^()\n]*)*)\)$""")
