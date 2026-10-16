package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「历史里该显示什么」的口径。
 *
 * 这两条过滤决定的是用户看到的内容边界：思考混进消息流会让正文被推理片段切碎，
 * 而上下文注入漏出来会在每条消息之间插一排折叠块。都不会崩，只会让人不想看。
 */
class ChatVisibleItemsTest {

    private fun item(kind: ChatItemKind, seq: Int, body: String = "内容") =
        ChatDisplayItem(id = "item-$seq", seq = seq, kind = kind, body = body)

    private val everything = listOf(
        item(ChatItemKind.USER, 1, "问题"),
        item(ChatItemKind.REASONING, 2, "思考"),
        item(ChatItemKind.CONTEXT, 3, "上下文注入"),
        item(ChatItemKind.TOOL, 4, "工具"),
        item(ChatItemKind.ASSISTANT, 5, "回答"),
    )

    /** 思考永不显示 —— 无论开关怎么摆。 */
    @Test
    fun `reasoning never shows`() {
        assertEquals(
            listOf(ChatItemKind.USER, ChatItemKind.CONTEXT, ChatItemKind.TOOL, ChatItemKind.ASSISTANT),
            visibleItemsOf(everything, showContext = true).map { it.kind },
        )
        assertEquals(
            listOf(ChatItemKind.USER, ChatItemKind.TOOL, ChatItemKind.ASSISTANT),
            visibleItemsOf(everything, showContext = false).map { it.kind },
        )
    }

    /** 开关打开时上下文注入回到流里（用户在设置页明确要过它）。 */
    @Test
    fun `context shows only when the preference is on`() {
        val withContext = visibleItemsOf(everything, showContext = true)
        assertEquals(1, withContext.count { it.kind == ChatItemKind.CONTEXT })
        assertEquals(0, visibleItemsOf(everything, showContext = false).count { it.kind == ChatItemKind.CONTEXT })
    }

    /** 顺序必须原样保持：这是消息流，不是集合。 */
    @Test
    fun `keeps the original order`() {
        val visible = visibleItemsOf(everything, showContext = false)
        assertEquals(listOf(1, 4, 5), visible.map { it.seq })
    }

    /** 空历史（还没加载）不该炸，也不该凭空造条目。 */
    @Test
    fun `an empty history yields nothing`() {
        assertEquals(emptyList<ChatDisplayItem>(), visibleItemsOf(emptyList(), showContext = false))
        assertEquals(emptyList<ChatDisplayItem>(), visibleHistoryItems(null, showContext = true))
    }
}
