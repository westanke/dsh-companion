package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用专用块分发的门禁（官方 Web ReadBlock / TerminalBlock / DiffBlock 对标）。
 *
 * 铁律：**解析失败一律回落 Generic** —— 专用块是增强不是依赖，
 * 这批用例专门喂畸形输入（坏 JSON、缺字段、空 result）钉住这条退路。
 */
class ChatToolBlocksTest {

    private fun tool(name: String, arguments: String, result: String? = null, failed: Boolean = false) =
        ToolCallDetail(callId = "c1", name = name, arguments = arguments, result = result, failed = failed)

    // ------------------------------------------------------------ 分发

    @Test
    fun `bash 分发为 Terminal 且保留命令与输出`() {
        val view = toolBlockView(tool("bash", """{"command":"ls -la","description":"列目录"}""", "file1\nfile2"))
        assertTrue(view is ToolBlockView.Terminal)
        view as ToolBlockView.Terminal
        assertEquals("ls -la", view.command)
        assertEquals("列目录", view.description)
        assertEquals("file1\nfile2", view.output)
        assertFalse(view.running)
    }

    @Test
    fun `bash 无结果是运行中`() {
        val view = toolBlockView(tool("bash", """{"command":"sleep 5"}""", result = null))
        assertTrue(view is ToolBlockView.Terminal && view.running)
    }

    @Test
    fun `read 分发为 Read 并剥出正文`() {
        val result = "<path>/tmp/a.kt</path>\n<type>file</type>\n<content>\n1: package a\n2: class B\n</content>"
        val view = toolBlockView(tool("read", """{"file_path":"/tmp/a.kt","offset":1,"limit":120}""", result))
        assertTrue(view is ToolBlockView.Read)
        view as ToolBlockView.Read
        assertEquals("/tmp/a.kt", view.path)
        assertEquals(1, view.offset)
        assertEquals(120, view.limit)
        assertTrue(view.hasLineNumbers)
        assertTrue(view.body.startsWith("1: package a"))
    }

    @Test
    fun `read_image 与无行号正文`() {
        val view = toolBlockView(tool("read_image", """{"file_path":"/tmp/p.png"}""", "<path>/tmp/p.png</path>\n<content>…</content>"))
        assertTrue(view is ToolBlockView.Read)
        view as ToolBlockView.Read
        assertFalse(view.hasLineNumbers)
    }

    @Test
    fun `edit 分发为 Diff 且带 replace_all`() {
        val view = toolBlockView(
            tool("edit", """{"file_path":"/a.kt","old_string":"val a","new_string":"val b","replace_all":true}"""),
        )
        assertTrue(view is ToolBlockView.Diff)
        view as ToolBlockView.Diff
        assertEquals("/a.kt", view.path)
        assertEquals("val a", view.oldText)
        assertEquals("val b", view.newText)
        assertTrue(view.replaceAll)
    }

    @Test
    fun `write 分发为新建 Diff 无旧文`() {
        val view = toolBlockView(tool("write", """{"file_path":"/n.md","content":"# hi"}"""))
        assertTrue(view is ToolBlockView.Diff)
        view as ToolBlockView.Diff
        assertNull(view.oldText)
        assertEquals("# hi", view.newText)
        assertFalse(view.replaceAll)
    }

    @Test
    fun `未知工具回落 Generic`() {
        val view = toolBlockView(tool("grep", """{"pattern":"x"}""", "match"))
        assertTrue(view is ToolBlockView.Generic)
        assertTrue((view as ToolBlockView.Generic).formattedArguments.contains("pattern"))
    }

    // ------------------------------------------------------------ 畸形输入回落

    @Test
    fun `坏 JSON 回落 Generic`() {
        val view = toolBlockView(tool("read", "{not-json", "x"))
        assertTrue(view is ToolBlockView.Generic)
    }

    @Test
    fun `空参数不崩且按工具名正常分发`() {
        // 空参数对任何工具都是合法输入（= 没有参数），应正常分发而不是崩或回落
        val bash = toolBlockView(tool("bash", "", ""))
        assertTrue(bash is ToolBlockView.Terminal && (bash as ToolBlockView.Terminal).command.isEmpty())
        val read = toolBlockView(tool("read", "", "x"))
        assertTrue(read is ToolBlockView.Read && (read as ToolBlockView.Read).path.isEmpty())
        // JSON 非对象（如 null / 数组）才回落 Generic
        assertTrue(toolBlockView(tool("read", "null", "x")) is ToolBlockView.Generic)
    }

    @Test
    fun `read result 缺 content 包装时正文退回原样`() {
        val view = toolBlockView(tool("read", """{"file_path":"/a"}""", "裸输出"))
        view as ToolBlockView.Read
        assertEquals("裸输出", view.body)
        assertFalse(view.hasLineNumbers)
    }

    @Test
    fun `read result 为 null 时正文为空`() {
        val view = toolBlockView(tool("read", """{"file_path":"/a"}""", null))
        view as ToolBlockView.Read
        assertEquals("", view.body)
    }

    // ------------------------------------------------------------ ANSI 剥离

    @Test
    fun `无转义序列时原样返回`() {
        assertEquals("plain text", stripAnsiEscapes("plain text"))
    }

    @Test
    fun `CSI 颜色码被剥除`() {
        assertEquals("done", stripAnsiEscapes("\u001B[32mdone\u001B[0m"))
        assertEquals("ab", stripAnsiEscapes("a\u001B[1;34mb"))
    }

    @Test
    fun `OSC 序列被剥除`() {
        assertEquals("ok", stripAnsiEscapes("\u001B]0;title\u0007ok"))
        assertEquals("ok", stripAnsiEscapes("\u001B]8;;http://x\u001B\\ok"))
    }

    @Test
    fun `残缺序列不吞正文`() {
        // 残缺的 CSI（后跟非参数/非终止字节）：吞掉 ESC[，其后的正文保留
        assertEquals("\ntail", stripAnsiEscapes("\u001B[3\ntail"))
        // 未闭合的 OSC：吞到结尾，不输出乱码
        assertEquals("head", stripAnsiEscapes("head\u001B]0;never-closed"))
    }

    // ------------------------------------------------------------ 行号判据

    @Test
    fun `行号判据只认N冒号空格前缀`() {
        val (with, a) = extractReadBody("<content>\n1: a\n2: b\n</content>")
        assertTrue(a); assertTrue(with.startsWith("1: a"))
        val (_, b) = extractReadBody("<content>\nplain\n</content>")
        assertFalse(b)
        // 空行开头的正文：判据看首个非空行
        val (_, c) = extractReadBody("<content>\n\n7: mid\n</content>")
        assertTrue(c)
    }
}
