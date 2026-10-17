package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.ui.chat.liveThinkingText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 钉死「`chat.message.delta` 缺 turn / step 也必须被接受」这条约定。
 *
 * 真机症状（2026-10-17）：正文正在流式渲染，底部状态条却一直显示
 * 「内容由 AI 生成，请甄别后使用」—— 思考内容该出现的地方一片空白。
 * 屏上探针读数是 `chat.message.delta × 54 但 reasoning=0、liveChat=null`。
 *
 * 根因：插件转发 chunk 时 turn/step 是**条件性**的
 * （`...(typeof assistantData.turn === 'number' ? {turn} : {})`），
 * 内核这一版的 `assistant/chunk` 不带这两个字段；客户端 `chatDelta()`
 * 原来写成 `turn ?: return null`，于是**每一帧都被静默丢弃**。
 *
 * 这两个字段只用于「按 turn/step 切段」，不参与正文渲染，所以缺失兜底 0。
 * 这条测试就是为了让任何人把它改回 `return null` 时立刻变红。
 */
class ChatDeltaToleranceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(data: String): WorkspaceEvent = WorkspaceEvent(
        id = "frame-1",
        type = "chat.message.delta",
        time = 1L,
        data = Json.parseToJsonElement(data) as JsonObject,
    )

    @Test
    fun `缺 turn 与 step 的 delta 仍被接受并兜底为 0`() {
        val delta = event(
            """{"sessionId":"s1","kind":"reasoning","text":"在想…","index":0}"""
        ).chatDelta()

        assertNotNull("缺 turn/step 不该让整帧被丢掉", delta)
        assertEquals(0, delta!!.turn)
        assertEquals(0, delta.step)
        assertEquals(ChatItemKind.REASONING, delta.kind)
        assertEquals("在想…", delta.text)
    }

    @Test
    fun `带 turn 与 step 时原样保留`() {
        val delta = event(
            """{"sessionId":"s1","kind":"text","text":"答案","index":2,"turn":7,"step":3}"""
        ).chatDelta()

        assertNotNull(delta)
        assertEquals(7, delta!!.turn)
        assertEquals(3, delta.step)
        assertEquals(ChatItemKind.ASSISTANT, delta.kind)
    }

    /** 兜底成同一段（0/0）之后，思考块与正文块仍按 index 归到同一个 LiveChatState。 */
    @Test
    fun `turn 兜底为 0 时思考与正文进同一个 liveChat`() {
        val reasoning = event(
            """{"sessionId":"s1","kind":"reasoning","text":"先算一下","index":0}"""
        ).chatDelta()!!
        val text = event(
            """{"sessionId":"s1","kind":"text","text":"等于 1591","index":1}"""
        ).chatDelta()!!

        val state = LiveChatState(reasoning.sessionId, 0, 0).append(reasoning).append(text)

        // turn/step 兜底成同一段（0/0），两个块因此归到同一个 LiveChatState。
        assertEquals(0, state.turn)
        assertEquals(2, state.blocks.size)
        assertEquals(1, state.blocks.count { it.kind == ChatItemKind.REASONING })
        // 这是修复的落点：思考条终于拿得到文本。
        assertEquals("先算一下", liveThinkingText(state.blocks))
    }

    /** sessionId / index / text 才是真必填 —— 缺它们才是无法归属的帧。 */
    @Test
    fun `缺 sessionId 或 index 或 text 仍然被拒绝`() {
        assertEquals(null, event("""{"kind":"text","text":"x","index":0}""").chatDelta())
        assertEquals(null, event("""{"sessionId":"s1","kind":"text","text":"x"}""").chatDelta())
        assertEquals(null, event("""{"sessionId":"s1","kind":"text","index":0}""").chatDelta())
        assertEquals(null, event("""{"sessionId":"s1","kind":"unknown","text":"x","index":0}""").chatDelta())
    }
}
