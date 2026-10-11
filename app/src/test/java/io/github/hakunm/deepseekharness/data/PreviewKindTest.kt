package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(PreviewKind.BINARY, previewKind("archive.tar.gz", null))
        assertEquals(PreviewKind.BINARY, previewKind("app.apk", "application/octet-stream"))
        assertEquals(PreviewKind.BINARY, previewKind("song.mp3", "text/plain"))
        assertEquals(PreviewKind.BINARY, previewKind("font.woff2", null))
    }

    @Test
    fun `图片与 PDF 从二进制里分出来 且扩展名优先于 MIME`() {
        // 这条是新分流规则的核心：两者已经从 BINARY_EXTENSIONS 挪走。
        // 留在原集合里的话，二进制判定在前，图片分支一行都跑不到。
        assertEquals(PreviewKind.IMAGE, previewKind("shot.png", "text/plain"))
        assertEquals(PreviewKind.IMAGE, previewKind("photo.JPEG", "application/octet-stream"))
        assertEquals(PreviewKind.IMAGE, previewKind("anim.gif", null))
        assertEquals(PreviewKind.IMAGE, previewKind("modern.webp", "text/plain"))
        assertEquals(PreviewKind.IMAGE, previewKind("ios.heic", null))
        assertEquals(PreviewKind.PDF, previewKind("doc.pdf", ""))
        assertEquals(PreviewKind.PDF, previewKind("manual.PDF", "text/plain"))
    }

    @Test
    fun `没有扩展名时图片与 PDF 由 MIME 兜底`() {
        assertEquals(PreviewKind.IMAGE, previewKind("screenshot", "image/png"))
        assertEquals(PreviewKind.PDF, previewKind("scan", "application/pdf"))
        assertEquals(PreviewKind.BINARY, previewKind("clip", "video/mp4"))
    }

    @Test
    fun `魔数能把无名文件升级成图片 但不推翻明确的文本类型`() {
        // 服务端 MIME 不可靠（项目里为此吃过亏）：名字与 MIME 都认不出来时，头部字节说了算。
        assertEquals(PreviewKind.IMAGE, previewKindOf("blob", null, PNG_HEADER))
        assertEquals(PreviewKind.IMAGE, previewKindOf("blob.bin", "application/octet-stream", JPEG_HEADER))
        // 名字已经明确说是 Markdown / HTML 的，尊重名字 —— 那是用户点开它的意图。
        assertEquals(PreviewKind.MARKDOWN, previewKindOf("README.md", null, PNG_HEADER))
        assertEquals(PreviewKind.HTML, previewKindOf("page.html", null, PNG_HEADER))
        // 认不出来就维持原判
        assertEquals(PreviewKind.BINARY, previewKindOf("blob.bin", null, byteArrayOf(1, 2, 3, 4)))
        // 没有字节时等价于 previewKind
        assertEquals(PreviewKind.IMAGE, previewKindOf("a.png", null, null))
        assertEquals(PreviewKind.SOURCE, previewKindOf("notes.txt", null, null))
    }

    @Test
    fun `魔数只认真的图片头 两个字母不算`() {
        assertEquals(ImageFormat.PNG, sniffImageFormat(PNG_HEADER))
        assertEquals(ImageFormat.JPEG, sniffImageFormat(JPEG_HEADER))
        assertEquals(ImageFormat.GIF, sniffImageFormat("GIF89a".toByteArray() + ByteArray(8)))
        assertEquals(
            ImageFormat.WEBP,
            sniffImageFormat("RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()),
        )
        // 「BM…」开头的文本不是位图：BMP 还要看偏移 10 处的 DIB 头长度。
        assertNull(sniffImageFormat("BMW 是一辆车，不是位图".toByteArray()))
        // 半截文件不瞎猜
        assertNull(sniffImageFormat(null))
        assertNull(sniffImageFormat(byteArrayOf(1, 2, 3)))
        assertNull(sniffImageFormat(byteArrayOf(0x89.toByte(), 0x50.toByte())))
    }

    @Test
    fun `图片与 PDF 各有各的字节上限 别的类型没有`() {
        assertEquals(PREVIEW_IMAGE_MAX_BYTES, binaryPreviewByteLimit(PreviewKind.IMAGE))
        assertEquals(PREVIEW_PDF_MAX_BYTES, binaryPreviewByteLimit(PreviewKind.PDF))
        // 文本走编辑器自己的 MAX_EDIT_BYTES，不在这里再定义一条
        assertNull(binaryPreviewByteLimit(PreviewKind.SOURCE))
        assertNull(binaryPreviewByteLimit(PreviewKind.BINARY))
        // 两条闸必须不同：共用一个数字就退化回「所有二进制一个待遇」了
        assertTrue(PREVIEW_PDF_MAX_BYTES > PREVIEW_IMAGE_MAX_BYTES)
    }

    @Test
    fun `采样倍率是 2 的幂 且压在像素上限以内`() {
        // 4000×3000 = 12 MP，刚好等于上限，不缩
        assertEquals(1, sampleSizeFor(4000, 3000))
        // 8000×6000 = 48 MP → 缩 2 倍
        assertEquals(2, sampleSizeFor(8000, 6000))
        // 20000×20000 = 400 MP → 缩 8 倍（400/64 = 6.25 MP）
        assertEquals(8, sampleSizeFor(20_000, 20_000))
        // 退化输入不炸：宁可原样解，也不要 inSampleSize = 0（库会抛）
        assertEquals(1, sampleSizeFor(0, 0))
        assertEquals(1, sampleSizeFor(-5, 100))
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

    private companion object {
        /** 真头部：PNG 的 8 字节签名 + 两个占位字节。 */
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(), 0, 0,
        )

        /** 真头部：JPEG 的 SOI + 段起始标记。 */
        val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
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
