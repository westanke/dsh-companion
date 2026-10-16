package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码高亮的三条底线，按「坏了会怎样」排序：
 *
 * 1. **原文一个字符都不能动**。高亮只加样式，调用方（Markdown 代码块、文件预览）
 *    按原文排版与复制；这里 trim 一下或补个换行，行号与剪贴板内容就全歪了。
 *    所以第一条断言是全覆盖的：多语言 × 多形态，逐个比 `AnnotatedString.text`。
 * 2. **畸形输入不许抛**（流式输出的半截 JSON 每帧都会送进来）、**认不出的语言不许抛**
 *    （围栏标签是模型写的自由文本）。失败姿态必须是「退化成无样式文本」。
 * 3. **20 万字符不许卡**。这段逻辑跑在消息渲染路径上，长工具结果很常见；
 *    写成 O(n²)（循环里拼 substring、反复全串扫描）在单测里就会现形。
 *
 * 下面的颜色常量是**契约的镜像**：改 `CodePalette` 必须同步改这里，
 * 否则就是悄悄换了配色 —— 这正是本测试要拦的事。
 * 只测纯函数，不碰 Compose 渲染：颜色最终长什么样靠主题与真机看。
 */
class ChatCodeHighlightTest {

    // 与 CodePalette 一一对应（改色板 = 改这里）。
    private val KEY = Color(0xFF0B6E63)
    private val KEYWORD = Color(0xFF0B6E63)
    private val STRING = Color(0xFF8A4B1F)
    private val NUMBER = Color(0xFF4A3FBF)
    private val LITERAL = Color(0xFF9A3412)
    private val COMMENT = Color(0xFF6B7280)

    private val jsonSample = """
        {
          "name": "dsh",
          "count": 42,
          "ratio": -1.5,
          "ok": true,
          "none": null
        }
    """.trimIndent()

    // ── 断言助手 ────────────────────────────────────────────────────────────

    /** 某个颜色覆盖到的原文片段（按出现顺序）；用它看「哪几个词亮了」。 */
    private fun AnnotatedString.spansWith(color: Color): List<String> =
        spanStyles.filter { it.item.color == color }.map { text.substring(it.start, it.end) }

    /** 断言 [token] 恰好被 [color] 覆盖，并把**精确区间**返回给调用方继续钉边界。 */
    private fun spanRangeOf(annotated: AnnotatedString, token: String, color: Color): IntRange {
        val at = annotated.text.indexOf(token)
        if (at < 0) throw AssertionError("样本文本里没有 `$token`")
        val hit = annotated.spanStyles.firstOrNull { it.start == at && it.end == at + token.length }
            ?: throw AssertionError("`$token`（$at..${at + token.length}）没有任何样式")
        if (hit.item.color != color) {
            throw AssertionError("`$token` 的颜色是 ${hit.item.color}，不是期望的 $color")
        }
        return at until at + token.length
    }

    // ── ① 原文一个字符都不能改 ──────────────────────────────────────────────

    @Test
    fun textIsNeverModifiedForAnyLanguage() {
        val samples = listOf(
            "", // 空串
            "   ", // 全空白
            "\n\n",
            "val x = 1 // 中文注释",
            "{\r\n  \"键\": \"值\",\r\n  \"n\": -1.5\r\n}", // CRLF + 非 ASCII
            "#!/bin/bash\nIFS=\$'\\n' echo \"\$1\"\n",
            "def f():\n    return 'x'",
            "<div class=\"a\">文本</div>",
            "line1\nline2\r\nline3",
            "{\"a\": \"未闭合",
            "f(x) { y -> y + 1 } /* 半截块注释",
        )
        val languages = listOf(
            null, "", "   ", "json", "JSON ", "kotlin", "kt", "kts", "java", "js", "ts",
            "python", "py", "shell", "sh", "bash", "yaml", "yml", "xml", "html", "css",
            "rust", "go", "ps1", "未知语言",
        )

        for (sample in samples) {
            for (language in languages) {
                assertEquals(
                    "语言标签 `$language` 改动了原文：`$sample`",
                    sample,
                    highlightCode(sample, language).text,
                )
            }
        }
    }

    @Test
    fun emptyInputIsPlainAnnotatedString() {
        val out = highlightCode("", "json")

        assertEquals("", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    // ── ② JSON：键 / 字符串 / 数字 / 字面量 ──────────────────────────────────

    @Test
    fun jsonKeysValuesNumbersAndLiteralsAreStyledByKind() {
        val out = highlightCode(jsonSample, "json")

        assertEquals(jsonSample, out.text)
        assertEquals(
            listOf("\"name\"", "\"count\"", "\"ratio\"", "\"ok\"", "\"none\""),
            out.spansWith(KEY),
        )
        assertEquals(listOf("\"dsh\""), out.spansWith(STRING))
        assertEquals(listOf("42", "-1.5"), out.spansWith(NUMBER))
        assertEquals(listOf("true", "null"), out.spansWith(LITERAL))
        assertTrue(out.spansWith(COMMENT).isEmpty())
    }

    @Test
    fun jsonSpanRangesAreExactNotWholeString() {
        val out = highlightCode(jsonSample, "json")

        // 键的区间是 [4, 10)：只包住 `"name"` 这 6 个字符，不含冒号与缩进。
        assertEquals(4 until 10, spanRangeOf(out, "\"name\"", KEY))
        // 值的区间同理：`"dsh"` 占 [12, 17)
        assertEquals(12 until 17, spanRangeOf(out, "\"dsh\"", STRING))
        // 粒度断言：5 键 + 1 字符串 + 2 数字 + 2 字面量，一段不多一段不少（防「整块涂色」）
        assertEquals(10, out.spanStyles.size)
    }

    @Test
    fun jsonKeyDetectionIgnoresWhitespaceAndNesting() {
        val spaced = "{ \"a\" : \"b\" ,\n \"c\"\t:\t[1] }"
        val spacedOut = highlightCode(spaced, "json")

        // 冒号前有空白（含换行/制表）照样是键；冒号后的 `"b"` 是值。
        assertEquals(listOf("\"a\"", "\"c\""), spacedOut.spansWith(KEY))
        assertEquals(listOf("\"b\""), spacedOut.spansWith(STRING))

        val nested = "{\"a\":{\"b\":[{\"c\":1}]}}"
        assertEquals(
            listOf("\"a\"", "\"b\"", "\"c\""),
            highlightCode(nested, "json").spansWith(KEY),
        )
        assertEquals(listOf("1"), highlightCode(nested, "json").spansWith(NUMBER))
    }

    @Test
    fun jsonEscapeSequenceDoesNotEndTheString() {
        val code = "{\"a\": \"\\\"x\\\"\", \"b\": 2}"

        val out = highlightCode(code, "json")

        assertEquals(code, out.text)
        // `\"` 不能把字符串提前截断，所以 `"b"` 仍被识别为键（而不是被上一段的残骸吃掉）。
        assertEquals(listOf("\"a\"", "\"b\""), out.spansWith(KEY))
        assertEquals(listOf("2"), out.spansWith(NUMBER))
    }

    @Test
    fun crlfJsonKeepsKeysStyled() {
        val code = "{\r\n  \"键\": \"值\",\r\n  \"n\": 1\r\n}"

        val out = highlightCode(code, "json")

        assertEquals(code, out.text)
        assertEquals(listOf("\"键\"", "\"n\""), out.spansWith(KEY))
        assertEquals(listOf("\"值\""), out.spansWith(STRING))
        assertEquals(listOf("1"), out.spansWith(NUMBER))
    }

    @Test
    fun blankLanguageFallsBackToJsonOnlyWhenContentLooksLikeJson() {
        val json = "  {\"a\": 1}\n"

        // null 与空白标签都归一到「猜一次」：工具结果里裸 JSON 太常见了。
        assertEquals(listOf("1"), highlightCode(json, null).spansWith(NUMBER))
        assertEquals(listOf("1"), highlightCode(json, "   ").spansWith(NUMBER))
        // 数组里的两个数字都要着色（初版这条断言写成了只期望 "1"，是实现对了、断言写错了）。
        assertEquals(listOf("1", "2"), highlightCode("[1, 2]", null).spansWith(NUMBER))

        // 显式标签要归一化（大小写 + 空白）。
        assertEquals(listOf("1"), highlightCode("{\"a\": 1}", " JSON ").spansWith(NUMBER))

        // 不像 JSON 的裸文本不猜：保持无样式，别把散文当 JSON 涂色。
        assertTrue(highlightCode("select 1 from t", null).spanStyles.isEmpty())
    }

    // ── ③ 认不出的语言：无样式、不抛异常 ────────────────────────────────────

    @Test
    fun unknownLanguageGetsPlainText() {
        val code = "fn main() { println!(\"hi\") }"

        for (language in listOf("rust", "go", "c++", "csharp", "ps1", "jsx", "vue", "sql", "未知语言")) {
            val out = highlightCode(code, language)
            assertEquals("语言标签 `$language` 改动了原文", code, out.text)
            assertTrue("语言标签 `$language` 不该产生样式", out.spanStyles.isEmpty())
        }
    }

    // ── 通用规则：关键字 / 字符串 / 数字 / 注释 ─────────────────────────────

    @Test
    fun kotlinRuleStylesKeywordsStringsNumbersAndComments() {
        val code = "val n = 42 // 注释里的 val 不该单独亮\nval s = \"x\""

        val out = highlightCode(code, "kotlin")

        assertEquals(code, out.text)
        assertEquals(listOf("val", "val"), out.spansWith(KEYWORD))
        assertEquals(listOf("\"x\""), out.spansWith(STRING))
        assertEquals(listOf("42"), out.spansWith(NUMBER))
        assertEquals(listOf("// 注释里的 val 不该单独亮"), out.spansWith(COMMENT))
    }

    @Test
    fun stringContentBeatsCommentAndKeywordRules() {
        val code = "val s = \"// 不是注释\"\nval t = \"if\""

        val out = highlightCode(code, "kotlin")

        // 重叠消解的判据：字符串起点更早，所以它赢 —— 顺序反过来写也不该变色。
        assertTrue("字符串里的 // 被当成了注释", out.spansWith(COMMENT).isEmpty())
        assertEquals(listOf("\"// 不是注释\"", "\"if\""), out.spansWith(STRING))
        assertEquals(listOf("val", "val"), out.spansWith(KEYWORD))
    }

    @Test
    fun bashKeywordsAreCaseInsensitive() {
        val code = "IF [ -f x ]; THEN\n  echo \"done 不是关键字\"\nFI"

        val out = highlightCode(code, "sh")

        assertEquals(listOf("IF", "THEN", "echo", "FI"), out.spansWith(KEYWORD))
        assertEquals(listOf("\"done 不是关键字\""), out.spansWith(STRING))
    }

    @Test
    fun pythonHashCommentsAreStyledButCssHashIsNot() {
        val python = "def f(n):  # 计数\n    return n + 1"
        val pythonOut = highlightCode(python, "python")

        assertEquals(listOf("# 计数"), pythonOut.spansWith(COMMENT))
        assertEquals(listOf("def", "return"), pythonOut.spansWith(KEYWORD))
        assertEquals(listOf("1"), pythonOut.spansWith(NUMBER))

        // CSS 的 `#fff` 是色值：按语言开关 `#` 注释，而不是全局启用。
        val css = "a { color: #fff; margin: 0 auto; }"
        val cssOut = highlightCode(css, "css")

        assertTrue("#fff 被当成了注释", cssOut.spansWith(COMMENT).isEmpty())
        assertEquals(listOf("color", "margin", "auto"), cssOut.spansWith(KEYWORD))
    }

    @Test
    fun blockCommentsAreStyledAndLeftUnterminated() {
        val code = "int x = 1; /* 说明\n 多行 */ int y = 2; // 行注释"

        val out = highlightCode(code, "java")

        assertEquals(listOf("/* 说明\n 多行 */", "// 行注释"), out.spansWith(COMMENT))
        assertEquals(listOf("int", "int"), out.spansWith(KEYWORD))
        assertEquals(listOf("1", "2"), out.spansWith(NUMBER))

        // 半截块注释（流式输出常态）要一直染到串尾，而不是整段不回色。
        val half = "fun f() { /* 还没写完"
        assertEquals(listOf("/* 还没写完"), highlightCode(half, "kotlin").spansWith(COMMENT))
    }

    @Test
    fun markupStylesTagNamesInsteadOfKeywords() {
        val code = "<div class=\"row\">文本</div>"

        val out = highlightCode(code, "html")

        assertEquals(listOf("div", "div"), out.spansWith(KEYWORD))
        assertEquals(listOf("\"row\""), out.spansWith(STRING))
    }

    // ── ⑤ 畸形输入：不抛、不退化成「整段无样式」 ───────────────────────────

    @Test
    fun malformedJsonNeverThrowsAndKeepsUsefulStyles() {
        // 未闭合字符串：半截值仍然着色到串尾，不能让后面的内容失去解析。
        val unclosed = "{\"a\": \"未闭合"
        val unclosedOut = highlightCode(unclosed, "json")

        assertEquals(unclosed, unclosedOut.text)
        assertEquals(listOf("\"a\""), unclosedOut.spansWith(KEY))
        assertEquals(listOf("\"未闭合"), unclosedOut.spansWith(STRING))

        // 括号不配平 + 半个指数 `1e` + 孤立负号 `-`：都不许抛，键照旧认出来。
        val broken = "{\"a\": 1e, \"b\": -}"
        val brokenOut = highlightCode(broken, "json")

        assertEquals(broken, brokenOut.text)
        assertEquals(listOf("\"a\"", "\"b\""), brokenOut.spansWith(KEY))

        // 单引号不是 JSON 字符串：`{'k': 1}` 里只有数字 1 该亮。
        val single = "{'k': 1}"
        val singleOut = highlightCode(single, "json")

        assertEquals(single, singleOut.text)
        assertEquals(listOf("1"), singleOut.spansWith(NUMBER))

        // 长未闭合输入（流式场景）：原文不变、不抛。
        val longUnclosed = "{" + "\"k\":\"v\",".repeat(2000)
        assertEquals(longUnclosed, highlightCode(longUnclosed, "json").text)
    }

    @Test
    fun malformedContentIsAlsoSafeOnTheGuessJsonPath() {
        val samples = listOf(
            "{\"a\": \"未闭合",
            "{\"a\": 1e, \"b\": -}",
            "[[[[",
            "}{",
            "{\"a\": 1,}",
        )

        for (sample in samples) {
            val out = highlightCode(sample, null)
            assertEquals("空标签 + 畸形内容改动了原文：`$sample`", sample, out.text)
        }
    }

    // ── ④ 性能：20 万字符不许卡 ────────────────────────────────────────────

    @Test
    fun twoHundredThousandCharsStayFast() {
        val jsonInput = buildString {
            append("[")
            while (length < 200_000) append("{\"k\":\"v\",\"n\":-12.5,\"b\":true},")
            append("]")
        }
        val genericInput = buildString {
            while (length < 200_000) append("val x = 42 // 注释注释注释注释注释注释注释\n")
        }
        assertTrue("样例没到 20 万字符：${jsonInput.length}", jsonInput.length >= 200_000)
        assertTrue("样例没到 20 万字符：${genericInput.length}", genericInput.length >= 200_000)

        // 先确认真的高亮了（否则测的是「早退」路径，等于没测）。
        val jsonOut = highlightCode(jsonInput, "json")
        val genericOut = highlightCode(genericInput, "kotlin")
        assertTrue("JSON 高亮段数太少：${jsonOut.spanStyles.size}", jsonOut.spanStyles.size > 10_000)
        assertTrue("通用高亮段数太少：${genericOut.spanStyles.size}", genericOut.spanStyles.size > 10_000)
        assertEquals(jsonInput, jsonOut.text)
        assertEquals(genericInput, genericOut.text)

        // 阈值放到 3000ms：只为拦 O(n²) 这类量级问题，不跟 CI 抖动较劲。
        val jsonMs = measureTimeMillis { highlightCode(jsonInput, "json") }
        val genericMs = measureTimeMillis { highlightCode(genericInput, "kotlin") }

        assertTrue("JSON 高亮 20 万字符耗时 ${jsonMs}ms", jsonMs < 3000)
        assertTrue("通用高亮 20 万字符耗时 ${genericMs}ms", genericMs < 3000)
    }

    @Test
    fun pathologicalQuoteRunsStayFast() {
        // 这一条是**回归钉子**，钉的是一个真实的崩溃：`"(?:\\.|[^"\\])*"` 用递归处理重复组，
        // 20 万字符的长字符串字面量会让 JVM 抛 StackOverflowError（已用 java.util.regex 实测，
        // Kotlin/JVM 的 Regex 就是它）—— 一旦抛异常，整段消息渲染不出来。
        // 字符串改成单遍扫描后这里应当瞬间跑完；谁把它改回重复组正则，这条会红。
        val longLiteral = "\"" + "a".repeat(200_000) + "\"" // 完整的长字符串字面量
        val quoteThenRuns = "\"" + "a".repeat(200_000) // 只开了头、还没有结尾
        val escapedRuns = "\"\\".repeat(100_000) // 即 `"\"\"\"\...`，20 万字符

        for (sample in listOf(longLiteral, quoteThenRuns, escapedRuns)) {
            for (language in listOf("kotlin", "json")) {
                val out = highlightCode(sample, language)
                assertEquals("语言 `$language` 改动了原文", sample, out.text)
            }
        }
        // 完整字面量只该有一段样式（`longLiteral` 被行尾规则染到串尾的那一段）
        assertEquals(1, highlightCode(longLiteral, "kotlin").spanStyles.size)
        assertEquals(1, highlightCode(quoteThenRuns, "kotlin").spanStyles.size)
        assertEquals(1, highlightCode(escapedRuns, "kotlin").spanStyles.size)

        val literalMs = measureTimeMillis { highlightCode(longLiteral, "kotlin") }
        val jsonLiteralMs = measureTimeMillis { highlightCode(longLiteral, "json") }
        val quotedMs = measureTimeMillis { highlightCode(escapedRuns, "kotlin") }

        assertTrue("通用规则处理 20 万字符长字面量耗时 ${literalMs}ms", literalMs < 3000)
        assertTrue("JSON 规则处理 20 万字符长字面量耗时 ${jsonLiteralMs}ms", jsonLiteralMs < 3000)
        assertTrue("通用规则处理 `\"\\` 重复 20 万字符耗时 ${quotedMs}ms", quotedMs < 3000)
    }
}
