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
}
