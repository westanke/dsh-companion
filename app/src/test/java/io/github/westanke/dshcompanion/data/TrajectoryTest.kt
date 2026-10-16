package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹的数据层。
 *
 * 判错的后果是**排查时被误导**：把两轮合成一轮、把工具调用算到别的轮次、
 * 或者显示一个负数的耗时。这些都不会崩，只会让人对着错误的证据下结论。
 */
class TrajectoryTest {

    private fun event(
        type: String,
        seq: Int,
        time: Long,
        turn: Int? = null,
        name: String? = null,
    ): HistoryEntry {
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        if (turn != null) fields["turn"] = JsonPrimitive(turn)
        if (name != null) fields["name"] = JsonPrimitive(name)
        return HistoryEntry(
            SessionEvent(type = type, seq = seq, time = time, data = JsonObject(fields)),
        )
    }

    private fun history(vararg entries: HistoryEntry) = ChatHistory(events = entries.toList(), hasMore = false)

    @Test
    fun `groups events by turn and orders by sequence`() {
        val turns = trajectoryOf(history(
            event("assistant/message", seq = 5, time = 50, turn = 1),
            event("user/message", seq = 4, time = 40, turn = 1),
            event("user/message", seq = 9, time = 90, turn = 2),
        ))
        assertEquals(listOf(1, 2), turns.map { it.turn })
        // 组内按 seq 排序：事件数组的顺序不可信。
        assertEquals(listOf(4, 5), turns[0].nodes.map { it.seq })
    }

    /** 归属不明的事件**不丢弃** —— 它们仍是「当时发生过什么」的证据。 */
    @Test
    fun `events without a turn fall into the unknown bucket without being dropped`() {
        val turns = trajectoryOf(history(
            event("user/message", seq = 1, time = 10),
            event("step/end", seq = 2, time = 20, turn = 3),
        ))
        assertEquals(listOf(UNKNOWN_TURN, 3), turns.map { it.turn })
        assertEquals(TrajectoryNodeKind.User, turns[0].nodes.single().kind)
    }

    /** 不认得的事件类型留成 Other，而不是消失在页面上。 */
    @Test
    fun `unrecognized event types are kept as other nodes`() {
        val turns = trajectoryOf(history(event("something/new", seq = 1, time = 10, turn = 1)))
        assertEquals(TrajectoryNodeKind.Other, turns.single().nodes.single().kind)
    }

    @Test
    fun `maps the known event types`() {
        val turns = trajectoryOf(history(
            event("user/message", seq = 1, time = 10, turn = 1),
            event("assistant/message", seq = 2, time = 20, turn = 1),
            event("tool/call", seq = 3, time = 30, turn = 1, name = "bash"),
            event("deliverables/presented", seq = 4, time = 40, turn = 1),
            event("turn/end", seq = 5, time = 50, turn = 1),
        ))
        assertEquals(
            listOf(
                TrajectoryNodeKind.User,
                TrajectoryNodeKind.Assistant,
                TrajectoryNodeKind.Tool,
                TrajectoryNodeKind.Deliverable,
                TrajectoryNodeKind.TurnEnd,
            ),
            turns.single().nodes.map { it.kind },
        )
        assertEquals("bash", turns.single().nodes[2].label)
        assertEquals(1, turns.single().toolCalls)
    }

    /** 拿不到工具名就返回 null（UI 用类型名兜底），**不猜**别的字段。 */
    @Test
    fun `a tool call without a name has no label`() {
        val turns = trajectoryOf(history(event("tool/call", seq = 1, time = 10, turn = 1)))
        assertNull(turns.single().nodes.single().label)
    }

    @Test
    fun `computes the wall-clock span of a turn`() {
        val turns = trajectoryOf(history(
            event("user/message", seq = 1, time = 1_000, turn = 1),
            event("assistant/message", seq = 2, time = 3_500, turn = 1),
        ))
        assertEquals(1_000L, turns.single().startedAt)
        assertEquals(3_500L, turns.single().endedAt)
        assertEquals(2_500L, turns.single().durationMillis)
    }

    /** 时间戳缺失或乱序时给 null，而不是一个负数 —— 负数的耗时比"没有"更误导。 */
    @Test
    fun `never reports a negative duration`() {
        val missing = trajectoryOf(history(
            event("user/message", seq = 1, time = 0, turn = 1),
            event("assistant/message", seq = 2, time = 0, turn = 1),
        ))
        assertNull(missing.single().durationMillis)

        val reversed = trajectoryOf(history(
            event("user/message", seq = 1, time = 900, turn = 1),
            event("assistant/message", seq = 2, time = 100, turn = 1),
        ))
        assertNull(reversed.single().durationMillis)
    }

    /** 原始 `data` 原样带上（详情里的「原始」一栏靠它），未知字段不能被吞掉。 */
    @Test
    fun `keeps the raw event data verbatim`() {
        val raw = JsonObject(mapOf("turn" to JsonPrimitive(1), "unknownField" to JsonPrimitive("keep me")))
        val turns = trajectoryOf(
            ChatHistory(
                events = listOf(HistoryEntry(SessionEvent("tool/call", 1, 10, raw))),
                hasMore = false,
            ),
        )
        assertEquals(raw, turns.single().nodes.single().raw)
        assertEquals("keep me", (turns.single().nodes.single().raw?.get("unknownField") as? JsonPrimitive)?.content)
    }

    /** 短耗时：不足一分钟给一位小数，够一分钟给 `m:ss` 且秒数补零。 */
    @Test
    fun `formats short durations`() {
        assertEquals("0.0s", shortDuration(0))
        assertEquals("0.5s", shortDuration(500))
        assertEquals("12.3s", shortDuration(12_345))
        assertEquals("59.9s", shortDuration(59_999))
        assertEquals("1:00", shortDuration(60_000))
        assertEquals("3:05", shortDuration(185_000))
    }

    @Test
    fun `an empty or missing history has no turns`() {
        assertTrue(trajectoryOf(null).isEmpty())
        assertTrue(trajectoryOf(history()).isEmpty())
        // 顺带盯住一个容易写错的点：空组不该凭空产生一轮。
        assertEquals(emptyList<Int>(), trajectoryOf(history()).map { it.turn })
    }
}
