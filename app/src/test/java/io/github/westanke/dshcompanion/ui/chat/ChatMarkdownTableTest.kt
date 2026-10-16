package io.github.westanke.dshcompanion.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
