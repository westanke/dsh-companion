package io.github.westanke.dshcompanion.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态条（思考 / AI 标识）的切换判据。
 *
 * 用户要求（2026-10-17）：「把『内容由 AI 生成，请甄别后使用』与显示思考过程的重叠在一栏显示，
 * 有思考内容的时候显示思考内容，没有的时候再显示这个」。
 *
 * [ChatStatusBar] 就是按这个判据在 [ChatThinkBar] 与 [AiGeneratedNotice] 之间切换的；
 * 判据本身是 `thinkBarText(liveThinking).isNotEmpty()`，所以这里直接守住它 ——
 * 换实现时这条测试能立刻说出「行为变了」。
 */
class ChatStatusBarTest {

    @Test
    fun `shows thinking when there is thinking content`() {
        assertTrue(thinkBarText("正在读取配置文件…").isNotEmpty())
    }

    /** 空白串等同于没有思考 —— 不能渲染出一条只有转义空白的思考条。 */
    @Test
    fun `blank input counts as no thinking`() {
        assertTrue(thinkBarText("").isEmpty())
        assertTrue(thinkBarText("   \n\t ").isEmpty())
    }

    /** 只有空格的 reasoning 段（流式首帧常见）也不该顶掉标识。 */
    @Test
    fun `whitespace-only reasoning does not take over the notice`() {
        assertEquals("", thinkBarText("\u00a0\u3000"))
    }

    /** 判据要与 thinkBarText 的实现一致：它做归一化，空串归一化后仍是空串。 */
    @Test
    fun `notice shows when thinking is absent`() {
        // 无思考 → thinkBarText 为空 → 状态条显示 AI 标识。
        assertTrue(thinkBarText("").isEmpty())
        // 有思考 → 显示思考（前缀省略号 + 尾部 140 字符内容，共 141 字符）。
        val text = thinkBarText("a".repeat(500))
        assertEquals(141, text.length)
        assertTrue(text.startsWith("…"))
        assertEquals("a".repeat(140), text.drop(1))
    }
}
