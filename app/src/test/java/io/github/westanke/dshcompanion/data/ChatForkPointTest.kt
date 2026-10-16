package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分叉点的判定。
 *
 * 判错的后果不是崩溃，而是**给内核传一个无效分叉点**（弹一个用户看不懂的错误），
 * 或者在不该出现的地方挂上一个按钮（消息流被一堆次要动作塞满）。
 */
class ChatForkPointTest {

    private fun item(kind: ChatItemKind, seq: Int, body: String = "内容") =
        ChatDisplayItem(id = "item-$seq", seq = seq, kind = kind, body = body)

    @Test
    fun `user messages are fork points`() {
        assertEquals(7, forkPointOf(item(ChatItemKind.USER, 7)))
    }

    /** 助手消息不给入口：每条都挂按钮会让消息流毛糙，而分叉场景几乎总是「从某次提问重来」。 */
    @Test
    fun `assistant messages are not fork points`() {
        assertNull(forkPointOf(item(ChatItemKind.ASSISTANT, 8)))
    }

    @Test
    fun `process rows are not fork points`() {
        assertNull(forkPointOf(item(ChatItemKind.TOOL, 9)))
        assertNull(forkPointOf(item(ChatItemKind.CONTEXT, 10)))
        assertNull(forkPointOf(item(ChatItemKind.REASONING, 11)))
    }

    /** seq = 0 不是有效分叉点：给内核传它只会换回一个用户看不懂的报错。 */
    @Test
    fun `zero and negative sequence numbers are rejected`() {
        assertNull(forkPointOf(item(ChatItemKind.USER, 0)))
        assertNull(forkPointOf(item(ChatItemKind.USER, -3)))
    }
}
