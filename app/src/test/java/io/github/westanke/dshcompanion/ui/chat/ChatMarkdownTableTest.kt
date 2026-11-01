package io.github.westanke.dshcompanion.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 表格解析。
 *
 * 守的是「**别把表格吞掉也别解析错**」：解析失败会让整段表格退化成纯文本（用户看到一坨竖线），
 * 解析错位则会把内容切到错误的列里 —— 后者更难发现，因为它看起来仍然像个表格。
 */
class ChatMarkdownTableTest {

    @Test
    fun `parses a well-formed table`() {
        val table = parseMarkdownTable(
            """
            | 字段 | 说明 |
            | --- | --- |
            | 路径 | F:\work |
            | 沙箱 | workspace-write |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("字段", "说明"), table!!.header)
        assertEquals(listOf(listOf("路径", "F:\\work"), listOf("沙箱", "workspace-write")), table.rows)
    }

    /** 分隔行是「表格」与「竖线列表」的唯一区别：没有它就不是表格。 */
    @Test
    fun `requires a separator row`() {
        assertNull(
            parseMarkdownTable(
                """
                | 字段 | 说明 |
                | 路径 | F:\work |
                """.trimIndent(),
            ),
        )
    }

    /** 对齐标记 `:--:` / `--:` / `:-:` 都合法。 */
    @Test
    fun `accepts alignment colons in the separator row`() {
        assertNotNull(
            parseMarkdownTable(
                """
                | a | b | c |
                | :- | -: | :-: |
                | 1 | 2 | 3 |
                """.trimIndent(),
            ),
        )
    }

    /** 只有表头 + 分隔行、没有数据行 → 不是一个有内容的表格。 */
    @Test
    fun `rejects a table with no data rows`() {
        assertNull(
            parseMarkdownTable(
                """
                | 字段 | 说明 |
                | --- | --- |
                """.trimIndent(),
            ),
        )
    }

    /** 列数不齐：补齐空单元格而不是截断 —— 截断会丢数据。 */
    @Test
    fun `pads short rows and trims long ones to the header width`() {
        val table = parseMarkdownTable(
            """
            | a | b |
            | --- | --- |
            | 1 |
            | 1 | 2 | 3 |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("1", ""), table!!.rows[0])
        assertEquals(listOf("1", "2"), table.rows[1])
    }

    /** 内容里的 `\|` 是**转义竖线**，不是列分隔符。 */
    @Test
    fun `keeps escaped pipes inside cells`() {
        val table = parseMarkdownTable(
            """
            | 命令 | 说明 |
            | --- | --- |
            | a \| b | 二选一 |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("a | b", "二选一"), table!!.rows[0])
    }

    /**
     * **Windows 路径不能被当成转义吃掉**：`F:\work` 若把 `\w` 当转义序列，会变成 `F:work`。
     *
     * 这条边界是单测先发现的（实现第一版见 `\` 就吃掉后一个字符），而真机反馈的表格里
     * 恰好全是这种路径 —— 两边互相印证，所以留一条回归守着。
     */
    @Test
    fun `keeps windows paths intact`() {
        val table = parseMarkdownTable(
            """
            | 路径 | 说明 |
            | --- | --- |
            | F:\work\docs | 工作目录 |
            | C:\\tmp\\x | 双层反斜杠 |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals("F:\\work\\docs", table!!.rows[0][0])
        assertEquals("C:\\tmp\\x", table.rows[1][0])
    }

    /** 空单元格 trim 后为空字符串（渲染侧显示成占位符，不显示成 "null"）。 */
    @Test
    fun `empty cells become empty strings`() {
        val table = parseMarkdownTable(
            """
            | a | b |
            | --- | --- |
            |   | x |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("", "x"), table!!.rows[0])
    }

    /**
     * 混排：表格前后有别的文本时只取表格行 ——
     * `MarkdownComponentModel.content` 给的是**整篇** markdown，不是表格那一段。
     */
    @Test
    fun `extracts the table when surrounded by other text`() {
        val table = parseMarkdownTable(
            """
            下面是对照表：

            | 参数 | 默认 |
            | --- | --- |
            | timeout | 1500 |

            以上。
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("参数", "默认"), table!!.header)
        assertEquals(listOf("timeout", "1500"), table.rows[0])
    }

    /** 完全没有表格 → null（调用方据此回落到默认渲染）。 */
    @Test
    fun `returns null when there is no table at all`() {
        assertNull(parseMarkdownTable("就是一段普通文字，没有表格。"))
        assertNull(parseMarkdownTable("| 只有一个竖线行"))
    }

    /**
     * 三列数据表的第一列是**有完整内容的实体名**（如「内核 `@deepseek-ai/dsh` 0.2.0-rc.2」），
     * 不是字段名，解析时必须原样保留 —— 布局层据此让它自然折行而不是省略成「@deepseek-...」。
     * 守的是 2026-10-20 真机「内核包名被省略号吃掉」那条残留问题。
     */
    @Test
    fun `three column table keeps the full first cell instead of treating it as a label`() {
        val table = parseMarkdownTable(
            """
            | 层 | 安装形态 | 升级方式 |
            |---|---|---|
            | 内核 `@deepseek-ai/dsh` 0.2.0-rc.2 | npm 全局 | `npm i -g @deepseek-ai/dsh@0.2.1-rc.2` |
            | 桌面壳 v0.2.19 | deb（/opt，root） | 已是最新 release，不用动 |
            | 插件（web profile） | pnpm | `dsh plugin --profile web ...` |
            """.trimIndent(),
        )
        assertNotNull(table)
        assertEquals(listOf("层", "安装形态", "升级方式"), table!!.header)
        assertEquals(3, table.rows.size)
        // 第一列整串必须在，不能在数据层就丢字（省略只允许发生在两列字段表的 UI 层）。
        assertEquals("内核 `@deepseek-ai/dsh` 0.2.0-rc.2", table.rows[0][0])
        assertEquals("插件（web profile）", table.rows[2][0])
    }

    // ────────────── #9 内容感知列宽（对照鸿蒙 MarkdownLite.ts 移植） ──────────────

    @Test
    fun `wide chars count one unit and narrow chars 0_6`() {
        assertEquals(1f, charWidthUnits('中'), 0.001f)
        assertEquals(0.60f, charWidthUnits('a'), 0.001f)
        assertEquals(0.60f, charWidthUnits('1'), 0.001f)
        // 全角标点（0xff08）、假名（0x2e80..0xa4cf）、谚文（0xac00..）都算全角。
        assertEquals(1f, charWidthUnits('（'), 0.001f)
        assertEquals(1f, charWidthUnits('あ'), 0.001f)
        assertEquals(1f, charWidthUnits('가'), 0.001f)
    }

    @Test
    fun `display width sums characters`() {
        assertEquals(1.6f, displayWidthUnits("中a"), 0.001f)
        assertEquals(0f, displayWidthUnits(""), 0.001f)
    }

    @Test
    fun `column widths clamp to 64 and 240`() {
        // 全短列：0.6×1×14+20 = 28.4 → 夹到下限 64。
        val narrow = markdownTableColumnWidthsDp(listOf("a", "b"), listOf(listOf("1", "2")), 14f)
        assertEquals(listOf(64f, 64f), narrow)
        // 超长列：100 个西文字符 → 0.6×100×14+20 = 860 → 夹到上限 240（超宽文字列内折行）。
        val wide = markdownTableColumnWidthsDp(listOf("h"), listOf(listOf("x".repeat(100))), 14f)
        assertEquals(listOf(240f), wide)
    }

    @Test
    fun `longer content gets a wider column`() {
        val widths = markdownTableColumnWidthsDp(
            header = listOf("位置", "说明"),
            rows = listOf(listOf("a", "这一列的说明文字很长很长很长很长")),
            fontSizeDp = 14f,
        )
        assertTrue(widths[1] > widths[0])
    }

    /** 链接 URL 不许把列宽抬到上限 —— 估宽前必须剥行内标记（鸿蒙 inlineMarkup 同约定）。 */
    @Test
    fun `inline links do not inflate column width`() {
        val widths = markdownTableColumnWidthsDp(
            listOf("[x](https://example.com/very/long/url)"),
            emptyList(),
            14f,
        )
        assertEquals(listOf(64f), widths)
    }

    @Test
    fun `strip inline markup keeps link text only`() {
        assertEquals("文字", stripInlineMarkup("[文字](https://example.com/a/b/c)"))
        assertEquals("图", stripInlineMarkup("![图](https://example.com/a.png)"))
        assertEquals("粗体", stripInlineMarkup("**粗体**"))
        assertEquals("代码", stripInlineMarkup("`代码`"))
        assertEquals("中a", stripInlineMarkup("**中**a"))
    }

    /** 装得下：剩余宽度按各列估宽比例摊回，列宽之和（不含列间 gap）正好等于可用宽度。 */
    @Test
    fun `fit distributes spare width proportionally`() {
        // est=[100, 200]，gap=17，总需求=317；可用=417 → 剩 100 按比例摊。
        val widths = resolveColumnWidthsDp(listOf(100f, 200f), 417f)!!
        assertEquals(133.33f, widths[0], 0.01f)
        assertEquals(266.67f, widths[1], 0.01f)
        assertEquals(400f, widths.sum(), 0.01f)
    }

    /** 装不下返回 null（调用方转横向滚动，列宽保持估宽定死，不压缩）。 */
    @Test
    fun `fit returns null when content exceeds available width`() {
        assertNull(resolveColumnWidthsDp(listOf(300f, 300f), 417f))
        // 恰好装下（含 17dp gap）：不摊也不缩。
        assertEquals(listOf(200f, 200f), resolveColumnWidthsDp(listOf(200f, 200f), 417f))
    }
}
