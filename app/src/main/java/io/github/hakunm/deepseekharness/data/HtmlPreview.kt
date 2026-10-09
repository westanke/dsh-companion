package io.github.hakunm.deepseekharness.data

/**
 * 从 HTML 里抽出**结构**，供 Compose 画成原生控件。
 *
 * 为什么不用 WebView 直接渲染：WebView 会执行页面里的 `<script>`、加载外链、发起网络
 * 请求。这里读的是「会话里出现过的任意文件」—— 用 WebView 等于把手机浏览器的一次请求
 * 权限交给一份不可信文件。这里只做**纯文本抽取**：没有 JS、没有网络、没有样式执行。
 *
 * 抽出来的是标题、段落、列表项、引用、代码块、链接、表格行 —— 也就是「一份 HTML 读起来
 * 说了什么」。标签与实体在这里就被消化掉，UI 层不需要知道 HTML 的存在。
 */
data class HtmlBlock(
    val kind: Kind,
    val text: String,
    val href: String? = null,
) {
    enum class Kind { HEADING, PARAGRAPH, LIST_ITEM, QUOTE, CODE, LINK }
}

/**
 * 极简 HTML → [HtmlBlock] 抽取。
 *
 * 刻意只支持最常见的标签，**不是**通用解析器：
 * - 段落/换行 → [HtmlBlock.Kind.PARAGRAPH]
 * - `h1`–`h6` → [HtmlBlock.Kind.HEADING]
 * - `li` → [HtmlBlock.Kind.LIST_ITEM]（无序/有序列表都不区分，UI 统一画成项目符号；
 *   区分有序列表就要维护计数器，而编号在跳过嵌套时会错位）
 * - `blockquote` → [HtmlBlock.Kind.QUOTE]
 * - `pre` / `code` → [HtmlBlock.Kind.CODE]（代码保留原文，不剥行内标记）
 * - `a href=` → [HtmlBlock.Kind.LINK]
 * - `script` / `style` 的内容**整体丢弃**，连同标签本身
 *
 * 为什么 `script` 的内容要丢而不是当文本显示：一段 `function f(){...}` 对读者没有信息量，
 * 却是这份文件里最容易被误读成「App 在执行它」的部分。
 */
fun extractHtmlBlocks(source: String): List<HtmlBlock> {
    val blocks = mutableListOf<HtmlBlock>()
    var cursor = 0
    var paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        paragraph.setLength(0)
        if (text.isNotEmpty()) blocks += HtmlBlock(HtmlBlock.Kind.PARAGRAPH, text)
    }

    while (cursor < source.length) {
        val open = source.indexOf('<', cursor)
        if (open < 0) {
            paragraph.append(decodeHtmlEntities(source.substring(cursor)))
            break
        }
        if (open > cursor) {
            paragraph.append(decodeHtmlEntities(source.substring(cursor, open)))
        }
        val close = source.indexOf('>', open)
        if (close < 0) {
            // 标签没闭合（流式写入的半个标签）：剩下的当纯文本，别把内容整个丢掉。
            paragraph.append(decodeHtmlEntities(source.substring(open)))
            break
        }
        val tag = source.substring(open + 1, close).trim()
        val closing = tag.startsWith("/")
        val name = tag.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
        // script/style 连标签带内容一起跳过 —— 位置靠后说明标签已经开了。
        if (name == "script" || name == "style") {
            flushParagraph()
            val closingTag = "</$name"
            val end = source.indexOf(closingTag, close, ignoreCase = true)
            cursor = if (end < 0) source.length else source.indexOf('>', end).let { if (it < 0) source.length else it + 1 }
            continue
        }
        // 一个**开标签**意味着新的一段开始：先把之前攒下的正文收掉。
        // 闭合标签一律不做事 —— `</ul>`、`</div>` 这种容器闭合不该产生任何效果。
        //
        // 顺序很要紧：**先 flush 再抽自己的内容**。反过来（先抽后 flush）的话，
        // `<li>甲</li>` 的「甲」会和它前面的正文粘成一段。
        // 早先的两个 bug 都出在这里，一度让标题分支和列表分支互相串味。
        if (closing || name in VOID_OR_INLINE) {
            cursor = close + 1
            continue
        }
        flushParagraph()
        cursor = close + 1
        when (name) {
            "li", "tr" -> {
                val text = readTextUntil(source, cursor, setOf(name))
                if (text.isNotBlank()) blocks += HtmlBlock(HtmlBlock.Kind.LIST_ITEM, text)
                cursor = skipToClose(source, cursor, name)
            }

            "blockquote" -> {
                val text = readTextUntil(source, cursor, setOf("blockquote"))
                if (text.isNotBlank()) blocks += HtmlBlock(HtmlBlock.Kind.QUOTE, text)
                cursor = skipToClose(source, cursor, "blockquote")
            }

            // `pre` 与 `code` 常常是嵌套的（`<pre><code>…</code></pre>`）。
            // 早先这里把两者都放进 stops，于是 `<pre>` 内部一撞见 `<code>` 就停，
            // 再按 `</pre>` 跳过 —— `</code>` 变成孤儿标签，代码块整个抽不出来。
            //
            // 正确的做法：**只认自己这一个标签的闭合**，中间遇到别的标签当分隔符
            // （代码块里的换行与缩进才是信息，不能因为一个 <span> 就断掉）。
            "pre", "code" -> {
                val text = readTextUntil(source, cursor, setOf(name), collapseWhitespace = false)
                if (text.isNotBlank()) blocks += HtmlBlock(HtmlBlock.Kind.CODE, text)
                cursor = skipToClose(source, cursor, name)
            }

            "a" -> {
                val text = readTextUntil(source, cursor, setOf("a"))
                val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
                if (text.isNotBlank()) blocks += HtmlBlock(HtmlBlock.Kind.LINK, text, href)
                cursor = skipToClose(source, cursor, "a")
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = readTextUntil(source, cursor, setOf(name))
                if (text.isNotBlank()) blocks += HtmlBlock(HtmlBlock.Kind.HEADING, text)
                cursor = skipToClose(source, cursor, name)
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * 从 [from] 起读纯文本，直到遇到 [stops] 里任一标签的起始。
 *
 * [collapseWhitespace] 为 false 时**保留换行与缩进** —— 代码块里的缩进是信息，
 * 压成一行之后 `if (x) {` 和它的函数体就贴成了一行，读者得自己猜层级。
 */
private fun readTextUntil(
    source: String,
    from: Int,
    stops: Set<String>,
    collapseWhitespace: Boolean = true,
): String {
    var cursor = from
    val builder = StringBuilder()
    while (cursor < source.length) {
        val open = source.indexOf('<', cursor)
        if (open < 0) {
            builder.append(source.substring(cursor))
            break
        }
        builder.append(source.substring(cursor, open))
        val close = source.indexOf('>', open)
        if (close < 0) {
            builder.append(source.substring(open))
            break
        }
        val name = source.substring(open + 1, close).trim().removePrefix("/")
            .substringBefore(' ').substringBefore('/').lowercase()
        if (name in stops) break
        builder.append(' ')
        cursor = close + 1
    }
    val text = builder.toString()
    return if (collapseWhitespace) text.replace(WHITESPACE_RUN, " ").trim() else text.trim()
}

/** 跳过 [name] 的闭合标签；没找到就返回源串末尾，不吞掉后续内容。 */
private fun skipToClose(source: String, from: Int, name: String): Int {
    val marker = "</$name"
    val end = source.indexOf(marker, from, ignoreCase = true)
    if (end < 0) return source.length
    val close = source.indexOf('>', end)
    return if (close < 0) source.length else close + 1
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * 不打断段落的标签：行内标记与自闭合标签。
 *
 * 它们出现时**不能** flush —— `<b>粗体</b>` 里的正文要和它前面那句连成一段，
 * 每遇一个行内标签就断开的话，一段话会被切成十几段。
 */
private val VOID_OR_INLINE = setOf(
    "b", "i", "em", "strong", "span", "u", "s", "small", "sub", "sup", "mark",
    "br", "hr", "img", "meta", "link", "input", "source",
)

/**
 * HTML 实体的最小还原。
 *
 * 只覆盖真正常见的五个（`&amp;` 这类转义，以及 `&nbsp;`）—— 完整的实体表有 2000 多个，
 * 为了显示效果去引一整个库不值得；而 `&#123;` 这种数字实体在正文里偶尔出现，顺手支持。
 */
fun decodeHtmlEntities(raw: String): String {
    if (!raw.contains('&')) return raw
    return buildString(raw.length) {
        var cursor = 0
        while (cursor < raw.length) {
            val amp = raw.indexOf('&', cursor)
            if (amp < 0) {
                append(raw, cursor, raw.length)
                break
            }
            append(raw, cursor, amp)
            val semicolon = raw.indexOf(';', amp)
            // 实体名有长度上限；找不到分号就不是实体，当普通 & 处理。
            if (semicolon < 0 || semicolon - amp > 10) {
                append('&')
                cursor = amp + 1
                continue
            }
            val body = raw.substring(amp + 1, semicolon)
            val resolved = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.toChar()?.toString()

                body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChar()?.toString()
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" || body == "#39" -> "'"
                body == "nbsp" -> " "
                else -> null
            }
            if (resolved == null) {
                append('&')
                cursor = amp + 1
            } else {
                append(resolved)
                cursor = semicolon + 1
            }
        }
    }
}
