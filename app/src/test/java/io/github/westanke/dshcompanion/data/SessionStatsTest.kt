package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行统计。
 *
 * 数错了不会崩，只会让用户看到一个**假的**工作量（「我明明跑了 20 轮，它说 3 轮」），
 * 而统计面板的全部价值就是让人相信这些数字。
 */
class SessionStatsTest {

    private fun entry(type: String, seq: Int) =
        HistoryEntry(SessionEvent(type = type, seq = seq, time = 0, data = kotlinx.serialization.json.JsonNull))

    private fun history(vararg events: Pair<String, Int>) =
        ChatHistory(events = events.map { entry(it.first, it.second) }, hasMore = false)

    @Test
    fun `counts each event type`() {
        val stats = sessionStatsOf(history(
            "user/message" to 1,
            "assistant/message" to 2,
            "tool/call" to 3,
            "tool/call" to 4,
            "user/message" to 5,
            "assistant/message" to 6,
        ))
        assertEquals(2, stats.turns)
        assertEquals(2, stats.assistantMessages)
        assertEquals(2, stats.toolCalls)
        assertEquals(6, stats.events)
        assertEquals(6, stats.lastSeq)
    }

    /** 未识别的类型要计数进 `events`，但绝不能落到三个明细里（那是「事件总数」的用途）。 */
    @Test
    fun `unknown event types only count toward the total`() {
        val stats = sessionStatsOf(history(
            "user/message" to 1,
            "step/end" to 2,
            "turn/end" to 3,
            "assistant/reasoning" to 4,
        ))
        assertEquals(1, stats.turns)
        assertEquals(0, stats.assistantMessages)
        assertEquals(0, stats.toolCalls)
        assertEquals(4, stats.events)
    }

    /** 统计的「新鲜度」看的是最大序号，不是最后一条的位置（事件顺序不该被当成前提）。 */
    @Test
    fun `reports the highest sequence number as the watermark`() {
        val stats = sessionStatsOf(history("user/message" to 9, "assistant/message" to 4))
        assertEquals(9, stats.lastSeq)
    }

    @Test
    fun `an empty or missing history yields empty stats`() {
        assertTrue(sessionStatsOf(null).isEmpty)
        assertTrue(sessionStatsOf(history()).isEmpty)
        assertNull(sessionStatsOf(null).lastSeq)
        // 空历史不该出现「0 轮 0 工具」这种看着像结论的明细，isEmpty 由 events 决定。
        assertEquals(0, sessionStatsOf(null).events)
    }
}
