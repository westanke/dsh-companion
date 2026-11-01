package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文路径链接化（`linkifyKnownFiles`）的回归测试。
 *
 * 背景（2026-10-19 真机反馈）：「会话中有个文件，我试了打不开」——
 * 模型在正文里写的是**纯文本**文件名（`📄 dsh-public-…md`），不是 Markdown 链接，
 * 于是点了毫无反应。同一文件从「本会话的文件」面板点得开，说明链路本身没问题。
 *
 * 这个函数的风险不对称：**漏加链接只是点不动**（用户还能去面板里开），
 * **加错链接会打开另一个文件**。所以下面一半用例在钉「不该加的时候别加」。
 */
class ChatPathLinksTest {
    private val report = "/media/wangke/OFFICE/workspace/report/dsh-public-deployments-项目适配评估_2026-10-19.md"

    @Test
    fun turnsABareFileNameIntoALinkThatPointsAtTheRealPath() {
        val text = "已落盘：📄 dsh-public-deployments-项目适配评估_2026-10-19.md"

        val out = linkifyKnownFiles(text, listOf(report))

        assertTrue(out.contains("[dsh-public-deployments-项目适配评估_2026-10-19.md]($report)"))
        // 链接的显示文本仍是原文件名 —— 界面上看不出被改过，只是能点了。
        assertTrue(out.startsWith("已落盘：📄 "))
    }

    @Test
    fun leavesTextAloneWhenTheFileIsUnknown() {
        val text = "已落盘：something-else.md"
        assertEquals(text, linkifyKnownFiles(text, listOf(report)))
    }

    @Test
    fun skipsAmbiguousNamesThatExistInTwoPlaces() {
        // 同名多路径：点开后打开哪一个是**猜的**，而猜错会打开另一个目录里的同名文件。
        val other = "/media/wangke/OFFICE/workspace/archive/dsh-public-deployments-项目适配评估_2026-10-19.md"
        val text = "见 dsh-public-deployments-项目适配评估_2026-10-19.md"

        assertEquals(text, linkifyKnownFiles(text, listOf(report, other)))
    }

    @Test
    fun doesNotWrapAnExistingMarkdownLinkTwice() {
        // 模型已经写成链接时不能再包一层，否则得到 [[text](path)](path)。
        val text = "[看报告](dsh-public-deployments-项目适配评估_2026-10-19.md)"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals(text, out)
    }

    @Test
    fun onlyLinksTheFirstOccurrenceInAMessage() {
        val text = "报告 dsh-public-deployments-项目适配评估_2026-10-19.md 已生成，再看 dsh-public-deployments-项目适配评估_2026-10-19.md"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals(1, Regex("""\]\(""").findAll(out).count())
    }

    @Test
    fun ignoresEmptyInputAndEmptyKnownList() {
        assertEquals("", linkifyKnownFiles("", listOf(report)))
        assertEquals("abc", linkifyKnownFiles("abc", emptyList()))
    }

    @Test
    fun prefersTheLongerNameWhenTwoNamesOverlap() {
        // `a.md` 是 `report-a.md` 的后缀：必须先匹配长的，否则会在长名中间插一条链接。
        val short = "/w/a.md"
        val long = "/w/report-a.md"
        val text = "文件 report-a.md"

        val out = linkifyKnownFiles(text, listOf(short, long))

        assertTrue(out.contains("[$long]" .removePrefix("[") ) || out.contains("report-a.md]($long)"))
        assertFalse(out.contains("[a.md]($short)report"))
    }

    // ---- #10：行内代码（反引号）里的文件名 ----
    //
    // 真机取证：模型习惯写 `report.md`，早先实现产出 `[report.md](/abs/path)`，
    // 渲染器按 CommonMark 当行内代码整段显示原文 —— 屏幕上一串点不动的长路径。

    @Test
    fun linksAFileNameWrappedInInlineCode() {
        val text = "已落盘：`dsh-public-deployments-项目适配评估_2026-10-19.md`"

        val out = linkifyKnownFiles(text, listOf(report))

        // 反引号必须被一起吃掉，否则又变回「代码里塞链接语法」。
        assertEquals("已落盘：[dsh-public-deployments-项目适配评估_2026-10-19.md]($report)", out)
        assertFalse(out.contains('`'))
    }

    @Test
    fun keepsInlineCodeUntouchedWhenItIsNotJustTheFileName() {
        // 代码里出现文件名不等于「这段代码就是那个文件」——往代码里注入 Markdown 会让它原样显示。
        val text = "跑一下 `cat dsh-public-deployments-项目适配评估_2026-10-19.md | head` 就行"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals(text, out)
    }

    @Test
    fun leavesFencedCodeBlocksAlone() {
        val text = "```bash\ncat dsh-public-deployments-项目适配评估_2026-10-19.md\n```"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals(text, out)
    }

    @Test
    fun linksBothBareTextAndInlineCodeInTheSameMessage() {
        val text = "见 `dsh-public-deployments-项目适配评估_2026-10-19.md`，或直接读 dsh-public-deployments-项目适配评估_2026-10-19.md"

        val out = linkifyKnownFiles(text, listOf(report))

        // 约束 3 不变：整条消息里该文件名只链接一次。
        assertEquals(1, Regex("""\]\(""").findAll(out).count())
        assertTrue(out.contains("[dsh-public-deployments-项目适配评估_2026-10-19.md]($report)"))
    }

    @Test
    fun keepsInlineCodeWithDoubleBackticksIntact() {
        // 等长反引号闭合：``x`` 里的单反引号是字面量，不能被当成代码段的边界。
        val text = "标记 ``a`b`` 与 `dsh-public-deployments-项目适配评估_2026-10-19.md`"

        val out = linkifyKnownFiles(text, listOf(report))

        assertTrue(out.contains("``a`b``"))
        assertTrue(out.contains("[dsh-public-deployments-项目适配评估_2026-10-19.md]($report)"))
    }

    @Test
    fun keepsUnclosedBacktickAsPlainText() {
        // 流式输出里半截反引号很常见：不能因此把后半行全当代码吞掉。
        val text = "见 ` 半截 与 dsh-public-deployments-项目适配评估_2026-10-19.md"

        val out = linkifyKnownFiles(text, listOf(report))

        assertTrue(out.contains("见 ` 半截"))
        assertTrue(out.contains("[dsh-public-deployments-项目适配评估_2026-10-19.md]($report)"))
    }

    @Test
    fun unwrapsAMarkdownLinkThatWasWrappedInInlineCode() {
        // 用户截图里的原始形态：模型把整条链接套进了反引号 —— 屏幕上是一条
        // 「长得像链接却点不动的等宽文本」。
        val name = "dsh-public-deployments-项目适配评估_2026-10-19.md"
        val text = "见 `[$name]($report)`"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals("见 [$name]($report)", out)
    }

    @Test
    fun keepsInlineCodeLinkUntouchedWhenTargetIsAnotherFile() {
        // 目标不是已知文件（比如网页）：不能擅自把它从代码里解放出来。
        val name = "dsh-public-deployments-项目适配评估_2026-10-19.md"
        val text = "见 `[$name](https://example.com/x)`"

        val out = linkifyKnownFiles(text, listOf(report))

        assertEquals(text, out)
    }
}
