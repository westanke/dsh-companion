package io.github.westanke.dshcompanion.ui.chat

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格前导空行规范化的回归用例。
 *
 * 守的是 2026-10-20 真机 bug：模型把表格紧贴在标题行下（中间没空行），
 * intellij-markdown 不产出 TABLE 节点，整段显示成一坨竖线文字。
 * 证据与对照实验见工作区 `研究类/table-debug/Probe.java`。
 */
class ChatMarkdownNormalizeTest {

    /** 现场原文（摘自真实会话）：标题行与表头之间无空行。 */
    private val realWorld = """
        本机实测结果（非纸上谈兵）：

        **三层结构**
        | 层 | 安装形态 | 升级方式 |
        |---|---|---|
        | 内核 | npm 全局 | `npm i -g` |
        | 桌面壳 | deb（/opt，root） | 不用动 |

        **关键发现**
        1. 第一条
    """.trimIndent()

    // ── 纯函数行为 ──────────────────────────────────────────────

    @Test
    fun `inserts a blank line before a table glued to the previous line`() {
        val out = normalizeMarkdownTables(realWorld)
        // 「**三层结构**」与表头之间必须被插入一个空行。
        assertTrue(out.contains("**三层结构**\n\n| 层 | 安装形态 | 升级方式 |"))
    }

    @Test
    fun `does not duplicate an existing blank line`() {
        val already = "标题\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        val out = normalizeMarkdownTables(already)
        assertEquals(already, out)
        // 幂等：再处理一次结果不变。
        assertEquals(out, normalizeMarkdownTables(out))
    }

    @Test
    fun `does not insert before a table at document start`() {
        val doc = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(doc, normalizeMarkdownTables(doc))
    }

    /** 只有竖线、没有分隔行 → 是普通文字，绝不能乱插空行。 */
    @Test
    fun `leaves pipe text without a separator row untouched`() {
        val prose = "参数：\n| a | b |\n| 1 | 2 |\n后面的话"
        assertEquals(prose, normalizeMarkdownTables(prose))
    }

    /** 围栏代码块里的「伪表格」不能被补行，否则破坏代码块内容。 */
    @Test
    fun `does not touch tables inside a fenced code block`() {
        val fenced = "示例：\n" +
            "```markdown\n" +
            "| a | b |\n" +
            "|---|---|\n" +
            "| 1 | 2 |\n" +
            "```\n" +
            "结束"
        assertEquals(fenced, normalizeMarkdownTables(fenced))
    }

    /** 流式输出中分隔行还没到 → 不补行，避免中间态空行抖动。 */
    @Test
    fun `does not insert while the separator row has not arrived yet`() {
        val halfStreamed = "三层结构\n| 层 | 安装形态 |\n"
        assertEquals(halfStreamed, normalizeMarkdownTables(halfStreamed))
    }

    /** 表格后边界本来就不受解析器影响，函数不应多手在后面也插行。 */
    @Test
    fun `does not insert after the table`() {
        val gluedAfter = "| a | b |\n|---|---|\n| 1 | 2 |\n紧接着的文字"
        assertEquals(gluedAfter, normalizeMarkdownTables(gluedAfter))
    }

    /** 对齐冒号 / 无空格分隔行也要认得分隔行。 */
    @Test
    fun `recognizes alignment colons in the separator row`() {
        val out = normalizeMarkdownTables("标题\n|a|b|\n|:-:|-:|\n|1|2|")
        assertTrue(out.contains("标题\n\n|a|b|"))
    }

    @Test
    fun `preserves missing trailing newline`() {
        val noTrailingNl = "标题\n| a |\n|---|\n| 1 |"
        assertFalse(normalizeMarkdownTables(noTrailingNl).endsWith("\n\n"))
    }

    // ── 端到端：同款解析器 AST 断言（钉死修复真的有效） ───────────

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private fun hasTable(markdown: String): Boolean {
        val root = parser.buildMarkdownTreeFromString(markdown)
        fun walk(node: ASTNode): Boolean =
            node.type.toString().contains("TABLE") || node.children.any { walk(it) }
        return walk(root)
    }

    @Test
    fun `real world text renders as PARAGRAPH before fix and as TABLE after`() {
        assertFalse("修复前：无空行写法不应有 TABLE（这正是 bug 现场）", hasTable(realWorld))
        assertTrue("修复后：补了空行必须产出 TABLE 节点", hasTable(normalizeMarkdownTables(realWorld)))
    }
}
