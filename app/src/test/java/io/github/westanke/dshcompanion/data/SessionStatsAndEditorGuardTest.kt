package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.OpenDocument
import io.github.westanke.dshcompanion.ui.chat.isInlineOnly
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.44 真机验收里 W1 / W2 / W3 三条缺陷的回归测试。
 *
 * 三条都是「界面显示的东西与事实不符」，而它们各自的**数据层**判据都能单测 ——
 * 界面那层要真机，这里盯住别让人把判据改回错的那个。
 */
class SessionStatsAndEditorGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ———— W1：会话统计的「轮次」口径 ————

    @Test
    fun countsOnlyRealUserTurnsAndIgnoresInjectedContext() {
        // 真机实测：界面显示轮次 9，用户实际只发过 2 条，其余 7 条全是内核注入。
        val history = ChatHistory(
            events = listOf(
                entry(1, """{"content":[{"type":"text","text":"第一条"}],"source":{"kind":"user"}}"""),
                entry(2, """{"content":[{"type":"text","text":"运行时上下文"}],"source":{"kind":"runtime-context"}}"""),
                entry(3, """{"content":[{"type":"text","text":"技能清单"}],"source":{"kind":"skill-catalog"}}"""),
                entry(4, """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"好"}]}}""", "assistant/message"),
                entry(5, """{"content":[{"type":"text","text":"第二条"}],"source":{"kind":"user"}}"""),
                entry(6, """{"content":[{"type":"text","text":"时间上下文"}],"source":{"kind":"time-context"}}"""),
            ),
            hasMore = false,
        )

        val stats = sessionStatsOf(history)
        assertEquals("注入的上下文不该算用户轮次", 2, stats.turns)
        assertEquals(1, stats.assistantMessages)
        assertEquals(6, stats.events)
    }

    @Test
    fun treatsEntriesWithoutSourceAsUserTurns() {
        // 老内核不产 `source` 字段。判不出就按「是」处理 —— 那是旧口径的行为，
        // 不能因为新字段缺失就让老会话的统计全变成 0。
        val history = ChatHistory(
            events = listOf(entry(1, """{"content":[{"type":"text","text":"老格式消息"}]}""")),
            hasMore = false,
        )

        assertEquals(1, sessionStatsOf(history).turns)
    }

    @Test
    fun statsStayEmptyWithoutHistory() {
        assertTrue(sessionStatsOf(null).isEmpty)
        assertEquals(0, sessionStatsOf(ChatHistory(emptyList(), false)).turns)
    }

    // ———— W2：表格单元格里的行内 Markdown ————

    @Test
    fun acceptsInlineCodeInTableCellsForInlineRendering() {
        // 验收截图里的原句：`` `2026-10-13-07-39-52/` `` 原样显示反引号。
        assertTrue(isInlineOnly("`2026-10-13-07-39-52/`"))
        assertTrue(isInlineOnly("**粗体** 与 `代码`"))
        assertTrue(isInlineOnly("普通的一段文字"))
    }

    @Test
    fun rejectsBlockLevelCellsThatWouldBreakTheTwoColumnLayout() {
        // 自绘的两列布局里嵌块级结构必然错位，错位比「反引号原样显示」更糟。
        assertFalse(isInlineOnly("第一行\n第二行"))
        assertFalse(isInlineOnly("| 管道 | 表格 |"))
        assertFalse(isInlineOnly("- 列表项"))
        assertFalse(isInlineOnly("# 标题"))
        assertFalse(isInlineOnly("> 引用"))
        assertFalse(isInlineOnly("```\n代码块\n```"))
    }

    // ———— W3：编辑器的未保存保护 ————

    @Test
    fun detectsUnsavedChangesAgainstTheTextItOpenedWith() {
        val document = OpenDocument(entry = sampleEntry(), text = "原始内容", originalText = "原始内容")
        assertFalse(document.hasUnsavedChanges)

        assertTrue(document.copy(text = "原始内容加了一行").hasUnsavedChanges)
        // 改回去同样算「有改动」：判据是「与打开时不同」，不是「与上一帧不同」。
        assertFalse(document.copy(text = "原始内容").hasUnsavedChanges)
    }

    @Test
    fun treatsNonTextDocumentsAsNeverDirty() {
        // 图片 / PDF 没有 text，originalText 也是 null —— 不该弹「未保存」确认。
        val document = OpenDocument(entry = sampleEntry(), bytes = byteArrayOf(1, 2, 3))
        assertFalse(document.hasUnsavedChanges)
        assertNull(document.originalText)
    }

    @Test
    fun savedDocumentIsNoLongerDirty() {
        // 存完之后 originalText 跟着换成刚写进去的内容 —— 再点返回不该再拦一次。
        val saved = OpenDocument(entry = sampleEntry(), text = "改过的内容", originalText = "改过的内容")
        assertFalse(saved.hasUnsavedChanges)
    }

    private fun sampleEntry() = FileEntry(
        name = "note.md",
        path = "note.md",
        kind = "file",
        size = 8,
        modifiedAt = 0.0,
        writable = true,
    )

    private fun entry(seq: Int, data: String, type: String = "user/message") = HistoryEntry(
        event = SessionEvent(type = type, seq = seq, time = seq.toLong(), data = json.parseToJsonElement(data)),
    )
}
