package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话文件的预览分派与 HTML 抽取。
 *
 * 这批用例钉的是**两条硬规则**：
 *
 * 1. 扩展名优先于 contentType。用户在本地机器上的 MIME 五花八门
 *    （`text/x-markdown`、`text/plain`、空串都见过），拿 MIME 当主判据就会得到
 *    「有时渲染有时不渲染」的最坏体验 —— 而用户点开 `.md` 的意图从来不含糊。
 * 2. 二进制永远不走文本预览。`application/octet-stream` 配 `.png` 是常态，
 *    把二进制当 UTF-8 解出来只会得到一屏替换字符。
 */
class PreviewKindTest {

    @Test
    fun `markdown 按扩展名识别 不看 MIME`() {
        assertEquals(PreviewKind.MARKDOWN, previewKind("README.md", "text/plain"))
        assertEquals(PreviewKind.MARKDOWN, previewKind("docs/guide.markdown", "application/octet-stream"))
        assertEquals(PreviewKind.MARKDOWN, previewKind("notes.MD", null))
        assertEquals(PreviewKind.MARKDOWN, previewKind("a/b/c/page.mdx", ""))
    }

    @Test
    fun `html 走结构化预览`() {
        assertEquals(PreviewKind.HTML, previewKind("report.html"))
        assertEquals(PreviewKind.HTML, previewKind("report.HTM", "text/plain"))
        assertEquals(PreviewKind.HTML, previewKind("page.xhtml", null))
    }

    @Test
    fun `二进制按扩展名优先 即便 MIME 说 text`() {
        assertEquals(PreviewKind.BINARY, previewKind("a.png", "text/plain"))
        assertEquals(PreviewKind.BINARY, previewKind("archive.tar.gz", null))
        assertEquals(PreviewKind.BINARY, previewKind("app.apk", "application/octet-stream"))
        assertEquals(PreviewKind.BINARY, previewKind("doc.pdf", ""))
    }

    @Test
    fun `源码按扩展名识别`() {
        assertEquals(PreviewKind.SOURCE, previewKind("Main.kt"))
        assertEquals(PreviewKind.SOURCE, previewKind("build.gradle.kts"))
        assertEquals(PreviewKind.SOURCE, previewKind("data.json", null))
        assertEquals(PreviewKind.SOURCE, previewKind("notes.txt", null))
    }

    @Test
    fun `扩展名不认识时退回 contentType`() {
        assertEquals(PreviewKind.SOURCE, previewKind("mystery.qqq", "text/plain"))
        assertEquals(PreviewKind.BINARY, previewKind("mystery.qqq", "application/octet-stream"))
        assertEquals(PreviewKind.SOURCE, previewKind("noext", "text/markdown"))
    }

    @Test
    fun `没有扩展名也没有 MIME 时按源码处理 不当二进制`() {
        // 宁可让用户看到可复制的原文，也不要一片空白加一个「不可用」。
        assertEquals(PreviewKind.SOURCE, previewKind("LICENSE", null))
        assertEquals(PreviewKind.SOURCE, previewKind("/abs/path/Makefile", null))
    }
}

class HtmlPreviewTest {

    private fun texts(kind: HtmlBlock.Kind) =
        extractHtmlBlocks(SAMPLE).filter { it.kind == kind }.map { it.text }

    @Test
    fun `抽出标题与段落`() {
        val blocks = extractHtmlBlocks(SAMPLE)
        assertTrue(blocks.any { it.kind == HtmlBlock.Kind.HEADING && it.text == "标题" })
        assertTrue(blocks.any { it.kind == HtmlBlock.Kind.PARAGRAPH && it.text == "第一段。" })
        assertTrue(blocks.any { it.kind == HtmlBlock.Kind.PARAGRAPH && it.text == "第二段。" })
    }

    @Test
    fun `抽出列表项并丢掉项目符号`() {
        assertEquals(listOf("甲", "乙"), texts(HtmlBlock.Kind.LIST_ITEM))
    }

    @Test
    fun `script 与 style 的内容整体丢弃`() {
        val blocks = extractHtmlBlocks(SAMPLE)
        // 这是本文件里最重要的一条：预览绝不能显示、更不能执行脚本。
        assertFalse(blocks.any { it.text.contains("alert") })
        assertFalse(blocks.any { it.text.contains("body{") })
    }

    @Test
    fun `链接保留 href`() {
        val link = extractHtmlBlocks(SAMPLE).first { it.kind == HtmlBlock.Kind.LINK }
        assertEquals("示例", link.text)
        assertEquals("https://example.com", link.href)
    }

    @Test
    fun `代码块保留原文 不剥标记`() {
        val code = extractHtmlBlocks(SAMPLE).first { it.kind == HtmlBlock.Kind.CODE }
        assertEquals("**不是粗体**", code.text)
    }

    @Test
    fun `实体被还原`() {
        assertEquals("A & B < C > D", decodeHtmlEntities("A &amp; B &lt; C &gt; D"))
        assertEquals("引号\"单引'", decodeHtmlEntities("引号&quot;单引&apos;"))
        assertEquals("A", decodeHtmlEntities("&#65;"))
        // 不是实体的 & 保持原样，不能被误吞成半个字符。
        assertEquals("Tom & Jerry", decodeHtmlEntities("Tom & Jerry"))
    }

    @Test
    fun `未闭合的标签不会吞掉剩余内容`() {
        // 流式写入的半个文件：宁可把标签当文本显示，也不要返回空预览。
        val blocks = extractHtmlBlocks("<p>前半段<p>后半段")
        assertTrue(blocks.any { it.text.contains("后半段") })
    }

    private companion object {
        val SAMPLE = """
            <html><head><style>body{color:red}</style></head>
            <body>
              <script>alert('x')</script>
              <h1>标题</h1>
              <p>第一段。</p>
              <ul><li>甲</li><li>乙</li></ul>
              <p>第二段。</p>
              <pre><code>**不是粗体**</code></pre>
              <a href="https://example.com">示例</a>
            </body></html>
        """.trimIndent()
    }
}
