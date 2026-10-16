package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「重试」该重发哪条消息。
 *
 * 这条判据的错法都不会崩：取错会**重发错内容**（用户看到自己没打过的话被送出去），
 * 取到空正文会送出一条空消息。两种都只能靠这里的断言拦住。
 */
class ChatRetryTest {

    private fun item(kind: ChatItemKind, body: String, id: String = "x", seq: Int = 1) =
        ChatDisplayItem(id = id, seq = seq, kind = kind, body = body)

    @Test
    fun `picks the last user message`() {
        val items = listOf(
            item(ChatItemKind.USER, "第一条", id = "u1", seq = 1),
            item(ChatItemKind.ASSISTANT, "回答", id = "a1", seq = 2),
            item(ChatItemKind.USER, "第二条", id = "u2", seq = 3),
        )
        assertEquals("第二条", retryableUserText(items))
    }

    /** 只发图/附件不发字的消息 body 为空 —— 重发它会变成一条空消息。 */
    @Test
    fun `skips user messages without text`() {
        val items = listOf(
            item(ChatItemKind.USER, "有正文", id = "u1", seq = 1),
            item(ChatItemKind.USER, "", id = "u2", seq = 3),
        )
        assertEquals("有正文", retryableUserText(items))
    }

    @Test
    fun `returns null when there is no user message at all`() {
        assertNull(retryableUserText(emptyList()))
        assertNull(retryableUserText(listOf(item(ChatItemKind.ASSISTANT, "只有回答"))))
    }

    /** 失败行与工具行夹在中间时，仍要往上找到那条用户消息（失败行永远紧跟它）。 */
    @Test
    fun `looks past trailing internal rows`() {
        val items = listOf(
            item(ChatItemKind.USER, "问题", id = "u1", seq = 1),
            item(ChatItemKind.TOOL, "工具", id = "t1", seq = 2),
            item(ChatItemKind.FAILURE, "", id = "f1", seq = 3),
        )
        assertEquals("问题", retryableUserText(items))
    }

    /** 重发必须逐字还原：空白与换行都是用户打的内容，不能顺手 trim 掉。 */
    @Test
    fun `keeps the text verbatim`() {
        val original = "第一行\n  缩进  \n\ttab"
        assertEquals(original, retryableUserText(listOf(item(ChatItemKind.USER, original))))
    }
}
