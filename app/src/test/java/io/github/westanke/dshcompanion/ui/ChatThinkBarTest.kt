package io.github.westanke.dshcompanion.ui

import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.LiveChatBlock
import io.github.westanke.dshcompanion.ui.chat.liveThinkingText
import io.github.westanke.dshcompanion.ui.chat.thinkBarText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考条两个纯函数的测试。
 *
 * 为什么值得单独测：原来流式思考是消息流里的一个 ActivityRow，改成底部固定一条之后，
 * 「取哪一段」和「怎么截断」都变成了看不见的规则 —— 界面上一行字，边界全在代码里。
 * 这两条规则又是**照抄网页版**的（chat.html 的 setLive / setThinking），
 * 抄错了肉眼很难发现：少截一段只是「看着有点长」，取了头部而不是尾部才会露馅。
 *
 * 只测纯函数，不碰 Compose —— `ChatThinkBar` 的位置（消息区下方、输入区上方）
 * 是布局事实，由阅读 + 真机验证，不是单测能替代的。
 */
class ChatThinkBarTest {

    private fun block(index: Int, kind: ChatItemKind, text: String) =
        LiveChatBlock(index = index, kind = kind, text = text)

    // ── liveThinkingText：只认流式的 REASONING ──

    @Test
    fun liveThinkingKeepsOnlyReasoningBlocks() {
        val blocks = listOf(
            block(0, ChatItemKind.REASONING, "先看一眼"),
            block(1, ChatItemKind.ASSISTANT, "这是正文，不该进思考条"),
            block(2, ChatItemKind.TOOL, "工具调用"),
            block(3, ChatItemKind.REASONING, "再确认一下"),
        )

        assertEquals("先看一眼 再确认一下", liveThinkingText(blocks))
    }

    @Test
    fun liveThinkingIsEmptyWhenThereIsNoReasoning() {
        // 这一条对应「没有思考就整条隐藏」：空串正是隐藏的判据。
        assertEquals("", liveThinkingText(emptyList()))
        assertEquals(
            "",
            liveThinkingText(listOf(block(0, ChatItemKind.ASSISTANT, "只有正文"))),
        )
    }

    @Test
    fun liveThinkingPreservesBlockOrder() {
        // 顺序按 blocks 原顺序，不排序、不按 index 重排（index 是流式分块号，不是显示序）。
        val blocks = listOf(
            block(7, ChatItemKind.REASONING, "乙"),
            block(2, ChatItemKind.REASONING, "甲"),
        )

        assertEquals("乙 甲", liveThinkingText(blocks))
    }

    // ── thinkBarText：折空白 + 留尾部 ──

    @Test
    fun blankThinkingRendersNothing() {
        // 判据是 isEmpty()，所以这三类都必须折成空串 —— 否则思考条会留下一条空白。
        assertEquals("", thinkBarText(""))
        assertEquals("", thinkBarText("   "))
        assertEquals("", thinkBarText("\n\n\t "))
    }

    @Test
    fun whitespaceIsCollapsedToSingleSpaces() {
        assertEquals("正在 想 什么", thinkBarText("  正在\n\n想   什么\t"))
    }

    @Test
    fun shortThinkingIsKeptVerbatim() {
        val text = "检查一下 roots 解析"

        assertEquals(text, thinkBarText(text))
    }

    @Test
    fun exactlyMaxCharsIsNotTruncated() {
        // 边界按网页版的 `t.length > 140` 取：等于上限**不**截。写成 >= 就会白白多一个省略号。
        val text = "x".repeat(140)

        assertEquals(text, thinkBarText(text))
        assertEquals(140, thinkBarText(text).length)
    }

    @Test
    fun longerThinkingKeepsTheTailNotTheHead() {
        // 思考是往前推进的，最新那段才有信息量 —— 这条断言就是在钉「取尾不取头」。
        val text = "很久以前说的第一句" + "b".repeat(200)

        val result = thinkBarText(text)

        assertTrue(result.startsWith("…"))
        assertFalse(result.contains("很久以前说的第一句"))
        assertEquals("…" + text.takeLast(140), result)
    }

    @Test
    fun defaultLimitIsOneHundredAndForty() {
        val text = "c".repeat(200)

        assertEquals("…" + text.takeLast(140), thinkBarText(text))
        assertEquals(141, thinkBarText(text).length) // 省略号 1 个字符 + 140 个正文
    }

    @Test
    fun customLimitIsHonoured() {
        val text = "0123456789"

        assertEquals("…" + "789", thinkBarText(text, maxChars = 3))
        assertEquals(text, thinkBarText(text, maxChars = 10))
    }

    @Test
    fun truncationHappensAfterWhitespaceCollapsing() {
        // 顺序很重要：先折空白再截尾，否则尾部会留一堆换行，看起来像思考条空了。
        val text = "头" + "\n".repeat(50) + "尾"

        assertEquals("头 尾", thinkBarText(text, maxChars = 10))
    }

    // ── 两条规则串起来（组合后的实际行为）──

    @Test
    fun endToEndLiveThinkingFeedsTheBarAsOneLine() {
        val blocks = listOf(
            block(0, ChatItemKind.REASONING, "第一步\n\n想清楚了"),
            block(1, ChatItemKind.REASONING, "   第二步接着想  "),
        )

        assertEquals("第一步 想清楚了 第二步接着想", thinkBarText(liveThinkingText(blocks)))
    }
}
