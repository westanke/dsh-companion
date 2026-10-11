package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死「流式块的身份靠 id，不靠 seq」这条约定。
 *
 * [LiveChatState.displayItems] 给每一块的 `seq` 都是 [Int.MAX_VALUE] —— 那是它内部
 * 排序用的占位值，**不是**序号。WebView 版页面按 id 分组正是因此：曾按 `seq:kind`
 * 分过一次，结果同一段推理与正文被并进同一个气泡，表现为「流式时两段话糊成一段」。
 *
 * 这条断言看着多余，但一旦有人把 seq 当序号用，它会立刻变红。
 */
class LiveChatGroupingTest {

    private fun delta(index: Int, kind: ChatItemKind, text: String) = ChatDelta(
        sessionId = "s1",
        turn = 1,
        step = 2,
        index = index,
        kind = kind,
        text = text,
    )

    /* append 是**返回新对象**的纯函数（data class + copy），不是就地修改；
       在 apply 里调 append 等于把结果扔掉，状态永远停在空 blocks。 */
    private fun live(vararg deltas: ChatDelta): LiveChatState =
        deltas.fold(LiveChatState("s1", 1, 2)) { acc, d -> acc.append(d) }

    @Test
    fun seqIsAPlaceholderAndMustNotBeUsedForGrouping() {
        val items = live(
            delta(0, ChatItemKind.REASONING, "想一下"),
            delta(1, ChatItemKind.ASSISTANT, "回答"),
        ).displayItems()

        assertEquals(2, items.size)
        // 全部相等：所以任何拿 seq 当分组键的写法都会把它们并成一条。
        assertEquals(1, items.map { it.seq }.distinct().size)
        assertEquals(Int.MAX_VALUE, items.first().seq)
    }

    @Test
    fun idDistinguishesBlocksOfTheSameKind() {
        val items = live(
            delta(0, ChatItemKind.ASSISTANT, "第一段"),
            delta(1, ChatItemKind.ASSISTANT, "第二段"),
        ).displayItems()

        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
        // 同一 kind 下仍要分得开 —— 这正是分组键选 id 的意义。
        assertEquals(items[0].kind, items[1].kind)
    }

    @Test
    fun appendingToTheSameBlockConcatenatesText() {
        val state = live()
            .append(delta(0, ChatItemKind.ASSISTANT, "前半"))
            .append(delta(0, ChatItemKind.ASSISTANT, "后半"))

        val items = state.displayItems()
        assertEquals(1, items.size)
        assertEquals("前半后半", items[0].body)
        assertTrue(items[0].streaming)
    }

    @Test
    fun emptyBlocksAreDropped() {
        val items = live(delta(0, ChatItemKind.ASSISTANT, "")).displayItems()
        assertTrue(items.isEmpty())
    }

    @Test
    fun aNewTurnDiscardsThePreviousRunInsteadOfMixingThem() {
        val state = live(delta(0, ChatItemKind.ASSISTANT, "第一轮"))
            .append(delta(0, ChatItemKind.ASSISTANT, "第二轮").copy(turn = 9, step = 9))

        // turn/step 一变，append 会**整个换掉** base —— 这是刻意的：
        // 上一轮的内容随后由 REST 历史补回来，两边都留着就成了重复显示。
        // 断言 1 条而不是 2 条，正是把这条约定钉住。
        val items = state.displayItems()
        assertEquals(1, items.size)
        assertEquals("第二轮", items[0].body)
        assertEquals(9, state.turn)
    }
}
