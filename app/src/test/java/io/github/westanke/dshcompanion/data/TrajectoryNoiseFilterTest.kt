package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死「轨迹只收有语义的记录」这条约定。
 *
 * 真机症状（2026-10-17）：轨迹面板第一屏是 #0~#26 一整列「其他事件」，有效信息要往下翻。
 * 对着真实会话数了一下，265 个事件里 **130 个是噪音**：
 * `step/start` 42 + `step/end` 42 + `session-log-deepseek/delivery-accepted` 42 +
 * `agent/inbox/spliced` 4。
 *
 * 这些是实现细节（每步的边界帧、内部投递日志），不是「发生过什么」的证据。
 * 官方 Web 的轨迹（`@deepseek-ai/dsh-client-ui-trajectory`）只收
 * User / Assistant / Tool / compaction，step 仅作边界标记 —— 这里对齐它。
 *
 * `tool/result` 也刻意排除：它是 `tool/call` 的产物、详情在工具行里能展开；
 * 单独成行会让一次调用占两行，读起来像调了两次。
 */
class TrajectoryNoiseFilterTest {

    private fun event(seq: Int, type: String, turn: Int? = 1, time: Long = seq.toLong()): HistoryEntry {
        val fields = mutableMapOf<String, JsonElement>()
        if (turn != null) fields["turn"] = JsonPrimitive(turn)
        return HistoryEntry(
            SessionEvent(type = type, seq = seq, time = time, data = JsonObject(fields)),
        )
    }

    private fun history(vararg entries: HistoryEntry) = ChatHistory(events = entries.toList(), hasMore = false)

    @Test
    fun `step 边界与内部日志不进轨迹`() {
        val turns = trajectoryOf(
            history(
                event(1, "turn/start"),
                event(2, "step/start"),
                event(3, "user/message"),
                event(4, "assistant/message"),
                event(5, "step/end"),
                event(6, "session-log-deepseek/delivery-accepted"),
                event(7, "agent/inbox/spliced"),
                event(8, "turn/end"),
            ),
        )
        val nodes = turns.flatMap { it.nodes }
        assertEquals("只该留下 user/message、assistant/message、turn/end 三条", 3, nodes.size)
        assertEquals(
            listOf(TrajectoryNodeKind.User, TrajectoryNodeKind.Assistant, TrajectoryNodeKind.TurnEnd),
            nodes.map { it.kind },
        )
    }

    @Test
    fun `tool-result 不单独成行`() {
        val nodes = trajectoryOf(
            history(event(1, "tool/call"), event(2, "tool/result"), event(3, "assistant/message")),
        ).flatMap { it.nodes }
        assertEquals(2, nodes.size)
        assertTrue(nodes.none { it.kind == TrajectoryNodeKind.Other })
    }

    @Test
    fun `真噪音全部过滤后只剩有语义的记录`() {
        // 复刻真机那份 265 事件的形状（按比例缩小）。
        val events = mutableListOf<HistoryEntry>()
        var seq = 1
        repeat(42) {
            events += event(seq++, "step/start")
            events += event(seq++, "session-log-deepseek/delivery-accepted", turn = null)
            events += event(seq++, "step/end")
        }
        events += event(seq++, "user/message")
        events += event(seq++, "assistant/message")
        events += event(seq++, "tool/call")
        events += event(seq, "tool/result")

        val nodes = trajectoryOf(history(*events.toTypedArray())).flatMap { it.nodes }
        assertEquals("42 轮噪音后只剩 3 条语义记录", 3, nodes.size)
    }

    @Test
    fun `无 turn 的语义记录归到轮次之间而不是被丢掉`() {
        val turns = trajectoryOf(
            history(
                event(1, "assistant/message", turn = null),
                event(2, "assistant/message", turn = 3),
            ),
        )
        assertEquals(2, turns.size)
        // 0 = UNKNOWN_TURN，UI 显示为「轮次之间」（对齐官方 Between turns），
        // 且**永远垫底** —— 边缘记录不该占掉第一屏（真机反馈「第一屏全是其他事件」）。
        assertEquals(3, turns.first().turn)
        assertEquals(UNKNOWN_TURN, turns.last().turn)
    }
}
