package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Markdown 代码块与代码预览的轻量高亮（零依赖）。
 *
 * 为什么自己写而不是引库：本项目的工具调用参数与结果几乎全是 JSON，围栏代码块也以
 * JSON / Kotlin / Shell 为主。为这几种语言引一套语法高亮依赖，换来的 APK 体积和
 * R8 keep 规则都不划算，所以只做两件事：JSON 单遍扫描 + 通用关键字规则。
 *
 * 为什么 JSON 用字符扫描而不是「一条正则 + lookahead 判键」（对比对象 iOS 侧的做法）：
 * 「后一个非空白字符是冒号」在正则里只能写成前视断言，而本模块将来可能下沉到 KMP
 * 共享层 —— Kotlin/Native 上的后向断言与重复分组在长文本上会退化甚至爆栈。
 * 单遍扫描天然 O(n) 且无回溯，顺手把「字符串里的冒号不算键」这类边界一次写清楚。
 *
 * 同一条理由也把**通用规则里的字符串**从正则改成了扫描：`"(?:\\.|[^"\\])*"` 这类
 * 「非捕获组 + 星号」的写法靠**递归**处理重复组，长串字面量会直接爆栈 ——
 * JVM 实测（java.util.regex，Kotlin/JVM 的 Regex 就是它）：20 万字符的
 * 「一个引号 + 长串字母」、完整的 20 万字符长字符串、`"\"` 反复 20 万字符，
 * 三种输入都抛 StackOverflowError（不是变慢，是抛）。剩下的正则（行注释、数字、
 * 标识符、标签名）都不带重复组，是明确定义的线性匹配。
 *
 * 契约：返回值的 `text` 与入参 [code] **逐字符一致** —— 只加样式，不改字符、不 trim、
 * 不补换行。调用方按原文排版与复制，这里动一个字符，行号和剪贴板内容就全歪了。
 *
 * 认不出的语言标签一律返回无样式文本，不抛异常：围栏标签是模型写的自由文本，
 * 高亮失败绝不能连累消息渲染。
 */

/**
 * 色板：亮色/深色由调用方主题决定，这里给的是**兼容色** ——
 * 饱和度和明度都取中段，在浅色与深色背景上都读得出来。
 * 调用方目前不接受色板参数，等哪天要跟随主题，只需替换本 object 里的取值。
 */
private object CodePalette {
    /** 语言关键字（`if` / `fun` / `class`…）。 */
    val keyword = Color(0xFF0B6E63)

    /** 字符串字面量（含围栏语言里的引号本身）。 */
    val string = Color(0xFF8A4B1F)

    /** 数字（含负号与小数点）。 */
    val number = Color(0xFF4A3FBF)

    /** JSON 的字面量 `true` / `false` / `null`。 */
    val literal = Color(0xFF9A3412)

    /** JSON 的键：与 [keyword] 同值但语义独立，将来想分开调色不必改调用点。 */
    val key = Color(0xFF0B6E63)

    /** 注释。 */
    val comment = Color(0xFF6B7280)
}

/**
 * 一段候选样式，`[start, endExclusive)` 半开区间。
 *
 * [priority] 只用于**同起点**时的排序，越小越优先（见 [pickNonOverlapping]）。
 */
private class CodeSpan(
    val start: Int,
    val endExclusive: Int,
    val color: Color,
    val priority: Int,
) {
    val length: Int get() = endExclusive - start
}

/** 字符串最优先：`"// 不是注释"` 里注释规则也会命中，不能让它赢。 */
private const val PRIORITY_STRING = 0

/** 注释次之：注释里出现的关键字不该亮。 */
private const val PRIORITY_COMMENT = 1

/** 关键字再次：标识符里的关键字片段（其实被词边界挡掉了，这里兜底）。 */
private const val PRIORITY_KEYWORD = 2

/** 数字最低：`1e5` 这类被字符串/注释包住的数字要让位。 */
private const val PRIORITY_NUMBER = 3

/** JSON 的三种字面量。 */
private val JSON_LITERALS = listOf("true", "false", "null")

/**
 * 通用规则的参数。
 *
 * [hashComments]：`#` 注释只属于部分语言 —— CSS 的 `#fff` 是颜色值，误判成注释会让
 * 色值整段变灰，所以按语言开关而不是全局启用。
 * [tagNames]：HTML/XML 里真正该亮的是标签名，而不是通用关键字（`class` 这种在标记语言里
 * 只是属性名），所以这一类走标签名规则、跳过关键字集合。
 */
private class LanguageRule(
    val keywords: Set<String> = emptySet(),
    val ignoreCase: Boolean = false,
    val hashComments: Boolean = false,
    val tagNames: Boolean = false,
)

private val KOTLIN_KEYWORDS = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "break",
    "continue", "class", "interface", "object", "data", "sealed", "enum", "annotation",
    "companion", "init", "constructor", "this", "super", "import", "package", "is", "as",
    "in", "out", "by", "where", "try", "catch", "finally", "throw", "typealias", "suspend",
    "inline", "noinline", "crossinline", "reified", "tailrec", "operator", "infix", "override",
    "open", "abstract", "internal", "private", "protected", "public", "lateinit", "const",
    "external", "actual", "expect", "vararg", "dynamic", "null", "true", "false",
)

private val JAVA_KEYWORDS = setOf(
    "class", "interface", "enum", "extends", "implements", "public", "private", "protected",
    "static", "final", "abstract", "void", "int", "long", "double", "float", "boolean", "char",
    "byte", "short", "new", "this", "super", "if", "else", "switch", "case", "default", "for",
    "while", "do", "break", "continue", "return", "try", "catch", "finally", "throw", "throws",
    "import", "package", "instanceof", "synchronized", "volatile", "transient", "native",
    "strictfp", "assert", "null", "true", "false", "var", "record", "sealed", "permits", "yield",
)

/**
 * JS/TS 关键字。刻意**不收** `string` / `number` / `boolean` 这类型别名：
 * 在 JS 里它们是常见的变量名，收进来会把普通变量点亮成关键字。
 */
private val JS_KEYWORDS = setOf(
    "function", "var", "let", "const", "if", "else", "for", "while", "do", "switch", "case",
    "default", "break", "continue", "return", "class", "extends", "super", "new", "this",
    "typeof", "instanceof", "delete", "in", "of", "void", "yield", "async", "await", "try",
    "catch", "finally", "throw", "import", "export", "from", "as", "static", "get", "set",
    "interface", "type", "enum", "implements", "public", "private", "protected", "readonly",
    "declare", "namespace", "abstract", "null", "undefined", "true", "false", "keyof", "infer",
    "satisfies", "never", "unknown",
)

/** 含少量约定俗成的内建名（`print` / `self`）：它们在 Python 代码里就是「会亮」的那类词。 */
private val PYTHON_KEYWORDS = setOf(
    "def", "class", "if", "elif", "else", "for", "while", "return", "yield", "lambda", "import",
    "from", "as", "with", "try", "except", "finally", "raise", "pass", "break", "continue", "in",
    "is", "not", "and", "or", "None", "True", "False", "global", "nonlocal", "del", "assert",
    "async", "await", "match", "case", "self", "print",
)

/** Shell 的 `if` / `fi` / `done` 大小写都合法，所以这条规则忽略大小写。 */
private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
    "in", "function", "select", "time", "return", "break", "continue", "local", "export",
    "readonly", "declare", "typeset", "unset", "shift", "eval", "exec", "exit", "source",
    "alias", "set", "echo", "printf", "read", "trap", "wait", "kill", "test", "true", "false",
    "sudo",
)

private val YAML_KEYWORDS = setOf("true", "false", "null", "yes", "no", "on", "off")

private val CSS_KEYWORDS = setOf(
    "display", "position", "margin", "padding", "border", "color", "background", "font", "width",
    "height", "flex", "grid", "none", "block", "inline", "absolute", "relative", "fixed",
    "sticky", "important", "media", "import", "keyframes", "transform", "transition", "animation",
    "opacity", "content", "auto", "inherit", "initial", "unset", "calc", "var", "rgba", "rgb",
    "hsl", "url", "top", "left", "right", "bottom",
)

/**
 * 语言标签 → 规则。标签先做「去空白 + 小写」归一化；
 * 表里没有的一律按认不出来处理（返回无样式），所以这张表就是支持范围本身。
 */
private val LANGUAGE_RULES: Map<String, LanguageRule> = run {
    val kotlin = LanguageRule(keywords = KOTLIN_KEYWORDS)
    val java = LanguageRule(keywords = JAVA_KEYWORDS)
    val script = LanguageRule(keywords = JS_KEYWORDS)
    val python = LanguageRule(keywords = PYTHON_KEYWORDS, hashComments = true)
    val shell = LanguageRule(keywords = SHELL_KEYWORDS, ignoreCase = true, hashComments = true)
    val yaml = LanguageRule(keywords = YAML_KEYWORDS, hashComments = true)
    val markup = LanguageRule(tagNames = true)
    val css = LanguageRule(keywords = CSS_KEYWORDS)
    mapOf(
        "kotlin" to kotlin, "kt" to kotlin, "kts" to kotlin,
        "java" to java,
        "js" to script, "ts" to script,
        "python" to python, "py" to python,
        "shell" to shell, "sh" to shell, "bash" to shell,
        "yaml" to yaml, "yml" to yaml,
        "xml" to markup, "html" to markup,
        "css" to css,
    )
}

/**
 * 给 [code] 加样式；围栏语言标签为 [language]（可为 null 或空白）。
 *
 * 返回值的 `text` 恒等于 [code]（见文件头契约）。
 */
internal fun highlightCode(code: String, language: String?): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString(code)

    // 不带 Locale 的 lowercase()：土耳其语环境下 "I" 会被折成 "ı"，语言标签就认不出来了。
    val label = language?.trim()?.lowercase().orEmpty()
    val spans: List<CodeSpan> = when {
        label == "json" -> jsonSpans(code)
        // 标签为空时「猜」一次：工具结果十有八九是裸 JSON，而空标签在围栏里很常见。
        label.isEmpty() -> if (looksLikeJson(code)) jsonSpans(code) else emptyList()
        else -> LANGUAGE_RULES[label]?.let { genericSpans(code, it) } ?: emptyList()
    }
    if (spans.isEmpty()) return AnnotatedString(code)

    return buildAnnotatedString {
        append(code)
        for (span in spans) {
            addStyle(SpanStyle(color = span.color), span.start, span.endExclusive)
        }
    }
}

/**
 * 内容是否「看起来像 JSON」：去掉首尾空白后，以 `{`/`[` 开头且以 `}`/`]` 结尾。
 *
 * 为什么用这个粗糙判据：这里只决定要不要**尝试** JSON 规则，扫出来的东西本来就是
 * 尽力而为；引 JSON 解析器反而会把这个纯函数变成「可能抛异常」的函数，
 * 也会把「畸形 JSON 照样渲染」这条底线搭进去。
 */
private fun looksLikeJson(code: String): Boolean {
    val first = code.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return false
    val last = code.indexOfLast { !it.isWhitespace() }
    val open = code[first]
    val close = code[last]
    return (open == '{' || open == '[') && (close == '}' || close == ']')
}

// ── JSON：单遍字符扫描 ──────────────────────────────────────────────────────

/**
 * JSON 只认四类：键、字符串、数字、字面量。
 *
 * 键与值的区别只看「字符串之后第一个非空白字符是不是冒号」—— 扫描时顺手看一眼即可，
 * 既不需要前视断言，也不需要先建语法树。
 */
private fun jsonSpans(code: String): List<CodeSpan> {
    val spans = ArrayList<CodeSpan>()
    var i = 0
    while (i < code.length) {
        val c = code[i]
        if (c == '"') {
            val end = stringEnd(code, i, quote = '"')
            val color = if (nextSignificant(code, end) == ':') CodePalette.key else CodePalette.string
            spans.add(CodeSpan(i, end, color, PRIORITY_STRING))
            i = end
        } else if (c == '-' || c.isDigit()) {
            val end = numberEnd(code, i)
            val before = if (i == 0) ' ' else code[i - 1]
            val after = if (end < code.length) code[end] else ' '
            // 两侧都要是词边界：`1e` / `12abc` 这种畸形片段不按数字着色，避免半截变紫。
            if (end > i && !isIdentifierPart(before) && !isIdentifierPart(after)) {
                spans.add(CodeSpan(i, end, CodePalette.number, PRIORITY_NUMBER))
                i = end
            } else {
                i++
            }
        } else if (c == 't' || c == 'f' || c == 'n') {
            val end = literalEnd(code, i)
            if (end > i) {
                spans.add(CodeSpan(i, end, CodePalette.literal, PRIORITY_KEYWORD))
                i = end
            } else {
                i++
            }
        } else {
            i++
        }
    }
    return spans
}

/**
 * 从 [start] 处的引号扫到配对的闭引号之后；没找到闭引号就停在行尾（或串尾）。
 *
 * 未闭合必须能返回（而不是报错）：流式输出的半截字符串每一帧都会被送到这里。
 * 停在**行尾**而不是串尾，是为了不让一个落单的引号把后面几十行代码全吞进字符串 ——
 * 那会让整段代码失去高亮，比不着色更糟。
 */
private fun stringEnd(code: String, start: Int, quote: Char): Int {
    var j = start + 1
    while (j < code.length) {
        val c = code[j]
        // 反斜杠转义：连同被转义的字符一起跳掉，所以 `\"` 不会提前结束字符串。
        if (c == '\\') {
            j += 2
            continue
        }
        if (c == quote) return j + 1
        if (c == '\n') return j
        j++
    }
    return code.length
}

/**
 * 通用代码里的字符串：单遍扫描，双引号与单引号各来一遍。
 *
 * 为什么不用契约里给的 `"(?:\\.|[^"\\])*"`：它不违反「禁后向断言 / 嵌套捕获重复分组」
 * 的字面规定，但**实测会爆栈** —— 重复组靠递归实现，20 万字符的长字符串字面量、
 * 或 `"\"` 反复 20 万字符，在 JVM 上都抛 StackOverflowError（不是变慢，是抛）。
 * 而流式输出送来的半截长字符串恰好是那个形状，一旦抛异常整段消息就渲染不出来。
 * 扫描版每个字符只过一遍，与 [stringEnd] 共用实现。
 */
private fun quotedSpans(code: String, quote: Char): List<CodeSpan> {
    val spans = ArrayList<CodeSpan>()
    var i = 0
    while (i < code.length) {
        if (code[i] != quote) {
            i++
            continue
        }
        val end = stringEnd(code, i, quote)
        spans.add(CodeSpan(i, end, CodePalette.string, PRIORITY_STRING))
        i = end
    }
    return spans
}

/** 返回 [from] 之后第一个非空白字符；到串尾返回 NUL（与 `:` 永不相等，省掉可空判断）。 */
private fun nextSignificant(code: String, from: Int): Char {
    var j = from
    while (j < code.length && code[j].isWhitespace()) j++
    return if (j < code.length) code[j] else '\u0000'
}

/** 扫一个 JSON 数字（含负号、小数、指数）；不是数字（如孤立的 `-`）时返回 [start]。 */
private fun numberEnd(code: String, start: Int): Int {
    var j = start
    if (code[j] == '-') j++
    val intFrom = j
    while (j < code.length && code[j].isDigit()) j++
    if (j == intFrom) return start

    if (j + 1 < code.length && code[j] == '.' && code[j + 1].isDigit()) {
        j++
        while (j < code.length && code[j].isDigit()) j++
    }
    if (j < code.length && (code[j] == 'e' || code[j] == 'E')) {
        var k = j + 1
        if (k < code.length && (code[k] == '+' || code[k] == '-')) k++
        val expFrom = k
        while (k < code.length && code[k].isDigit()) k++
        if (k > expFrom) j = k
    }
    return j
}

/** 扫 `true` / `false` / `null`（右侧要词边界）；不是字面量时返回 [start]。 */
private fun literalEnd(code: String, start: Int): Int {
    for (word in JSON_LITERALS) {
        val end = start + word.length
        if (code.startsWith(word, start) && !isIdentifierPart(if (end < code.length) code[end] else ' ')) {
            return end
        }
    }
    return start
}

private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

// ── 通用规则：简单正则 + 关键字集合 ─────────────────────────────────────────

/** 行注释。`[^\n]*` 是线性匹配，不含断言。 */
private val LINE_COMMENT = Regex("//[^\n]*")

/** `#` 注释，只对 Python / Shell / YAML 生效（CSS 的 `#fff` 是颜色值）。 */
private val HASH_COMMENT = Regex("#[^\n]*")

/** `\b` 在这里就是词边界：`abc123` 不会被当成数字。 */
private val NUMBER = Regex("\\b\\d+(\\.\\d+)?\\b")

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** 标签名单独取第 1 组，避免把 `<` 也染上色。 */
private val TAG_NAME = Regex("</?([A-Za-z][A-Za-z0-9:-]*)")

/** 通用高亮：字符串 / 注释 / 数字 / 关键字四类候选，最后消解重叠。 */
private fun genericSpans(code: String, rule: LanguageRule): List<CodeSpan> {
    val candidates = ArrayList<CodeSpan>()
    candidates.addAll(quotedSpans(code, '"'))
    candidates.addAll(quotedSpans(code, '\''))
    for (match in LINE_COMMENT.findAll(code)) {
        candidates.add(CodeSpan(match.range.first, match.range.last + 1, CodePalette.comment, PRIORITY_COMMENT))
    }
    if (rule.hashComments) {
        for (match in HASH_COMMENT.findAll(code)) {
            candidates.add(CodeSpan(match.range.first, match.range.last + 1, CodePalette.comment, PRIORITY_COMMENT))
        }
    }
    candidates.addAll(blockCommentSpans(code))
    for (match in NUMBER.findAll(code)) {
        candidates.add(CodeSpan(match.range.first, match.range.last + 1, CodePalette.number, PRIORITY_NUMBER))
    }

    if (rule.tagNames) {
        for (match in TAG_NAME.findAll(code)) {
            val name = match.groups[1] ?: continue
            candidates.add(CodeSpan(name.range.first, name.range.last + 1, CodePalette.keyword, PRIORITY_KEYWORD))
        }
    } else if (rule.keywords.isNotEmpty()) {
        for (match in IDENTIFIER.findAll(code)) {
            val word = match.value
            val key = if (rule.ignoreCase) word.lowercase() else word
            if (key in rule.keywords) {
                candidates.add(CodeSpan(match.range.first, match.range.last + 1, CodePalette.keyword, PRIORITY_KEYWORD))
            }
        }
    }
    return pickNonOverlapping(candidates)
}

/**
 * 块注释用扫描而不是正则：DOT_MATCHES_ALL 版正则（「斜杠星 + 懒惰匹配 + 星斜杠」三步）
 * 碰上「开了块注释却没闭合」的输入时，每个起点都要一路扫到串尾，20 万字符下就是
 * O(n²)，足以卡住主线程 —— 而流式输出出现半截块注释是常态。
 * 扫描版跳过已配对的区间，整体仍是 O(n)。
 *
 * 上面刻意不写出那两个两字符序列：Kotlin 的块注释**支持嵌套**，注释正文里出现
 * 「星号紧跟斜杠」会提前闭合本段注释，后半行直接变成代码 —— 本文件初版就是这么坏的，
 * 编译器报了一串 Expecting a top level declaration。
 */
private fun blockCommentSpans(code: String): List<CodeSpan> {
    val spans = ArrayList<CodeSpan>()
    var i = 0
    while (true) {
        val from = code.indexOf("/*", i)
        if (from < 0) return spans
        val close = code.indexOf("*/", from + 2)
        val end = if (close < 0) code.length else close + 2
        spans.add(CodeSpan(from, end, CodePalette.comment, PRIORITY_COMMENT))
        i = end
    }
}

/**
 * 重叠消解：按「起点早者优先、同起点按优先级」贪心占位。
 *
 * 为什么要显式消解：字符串里的 `//`、注释里的 `val`、`"if"` 里的 `if` 都会被各自的规则
 * 命中，如果统统交给 AnnotatedString 叠加，最终画面取决于添加顺序 —— 调一下规则的
 * 先后，颜色就悄悄变了。这里一次算清谁赢，与遍历顺序无关。
 */
private fun pickNonOverlapping(candidates: List<CodeSpan>): List<CodeSpan> {
    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedWith(
        // 同起点同优先级时取更长者（`-length` 升序 = 长的排在前面）。
        compareBy({ it.start }, { it.priority }, { -it.length }),
    )
    val picked = ArrayList<CodeSpan>(sorted.size)
    var takenUpTo = 0
    for (span in sorted) {
        if (span.start >= takenUpTo) {
            picked.add(span)
            takenUpTo = span.endExclusive
        }
    }
    return picked
}
