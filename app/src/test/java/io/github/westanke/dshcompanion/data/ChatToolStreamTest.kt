package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时工具流（bridge v2.12.0）的解析与合并判据。
 *
 * 背景：内核的 `tool/call` / `tool/result` 在桥里落进兜底分支被整段丢掉，
 * bridge v2.12.0 起原样透传成 `chat.tool.call` / `chat.tool.result`，
 * 并给 `chat.message.delta` 加了 `tool-call` / `tool-call-end` 两个 kind。
 * App 侧要靠这些帧在运行期显示「正在跑哪个工具」，所以判据必须钉死：
 *
 * 1. 参数增量是**追加**（官方 `args.append`），不是覆盖；
 * 2. 阶段**只前进不倒退**（晚到的 preparing 帧不能把 running 打回去）；
 * 3. `hasReplyContent` 缺失 = 「桥没说」，返回 null 而不是 false
 *    （老桥上每个回合都必须还能收尾）。
 */
class ChatToolStreamTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    private fun sessionEvent(type: String, data: String, seq: Int = 1) =
        HistoryEntry(SessionEvent(type = type, seq = seq, time = 100L, data = obj(data)))

    // ------------------------------------------------------------ 参数增量

    @Test
    fun `tool-call 增量帧是 preparing 且参数追加`() {
        val first = toolCallDeltaFromFrame(obj("""{"kind":"tool-call","callId":"c1","name":"write","argumentsDelta":"{\"a\":"}"""))
        assertNotNull(first)
        assertEquals("c1", first!!.callId)
        assertEquals("write", first.name)
        assertEquals(ToolStreamPhase.PREPARING, first.phase)
        assertTrue(first.argumentsAppend)

        val second = toolCallDeltaFromFrame(obj("""{"kind":"tool-call","callId":"c1","argumentsDelta":"1}"}"""))
        val merged = ToolStreamState(sessionId = "s1").merge(first).merge(second!!)
        val entry = merged.entries.single()
        assertEquals("""{"a":1}""", entry.arguments)
        assertEquals(ToolStreamPhase.PREPARING, entry.phase)
    }

    @Test
    fun `tool-call-end 定稿并把阶段推到 running`() {
        val entry = toolCallDeltaFromFrame(obj("""{"kind":"tool-call-end","callId":"c1","name":"write","arguments":"{\"a\":1}"}"""))
        assertNotNull(entry)
        assertEquals(ToolStreamPhase.RUNNING, entry!!.phase)
        assertFalse(entry.argumentsAppend)
        assertEquals("""{"a":1}""", entry.arguments)
    }

    @Test
    fun `定稿之后再来的增量帧不改已经定稿的参数`() {
        val state = ToolStreamState(sessionId = "s1")
            .merge(toolCallDeltaFromFrame(obj("""{"kind":"tool-call-end","callId":"c1","arguments":"{\"a\":1}"}"""))!!)
            .merge(toolCallDeltaFromFrame(obj("""{"kind":"tool-call","callId":"c1","argumentsDelta":"zzz"}"""))!!)
        assertEquals("""{"a":1}""", state.entries.single().arguments)
        assertEquals(ToolStreamPhase.RUNNING, state.entries.single().phase)
    }

    @Test
    fun `缺 callId 的帧不产出条目`() {
        assertNull(toolCallDeltaFromFrame(obj("""{"kind":"tool-call","name":"write"}""")))
        assertNull(toolCallDeltaFromFrame(obj("""{"kind":"tool-call","callId":""}""")))
        assertNull(toolCallDeltaFromFrame(null))
    }

    @Test
    fun `正文 delta 不走工具这条路`() {
        assertNull(toolCallDeltaFromFrame(obj("""{"kind":"text","text":"你好"}""")))
        assertNull(toolCallDeltaFromFrame(obj("""{"kind":"reasoning","text":"想"}""")))
    }

    // ------------------------------------------------------------ call / result

    @Test
    fun `chat_tool_call 帧变成 running 并记住开始时间`() {
        val entry = toolStreamEntryFromFrame(
            "chat.tool.call",
            obj("""{"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}","turn":1,"step":1}"""),
            500L,
        )
        assertNotNull(entry)
        assertEquals(ToolStreamPhase.RUNNING, entry!!.phase)
        assertEquals("bash", entry.name)
        assertEquals(500L, entry.startedAt)
        assertNull(entry.result)
    }

    @Test
    fun `chat_tool_result 帧变成 done 并带出结果`() {
        val entry = toolStreamEntryFromFrame(
            "chat.tool.result",
            obj(
                """{"turn":1,"step":1,"message":{"role":"tool","toolCallId":"c1","isError":false,"content":[{"type":"text","text":"file1\nfile2"}]}}"""
            ),
            900L,
        )
        assertNotNull(entry)
        assertEquals(ToolStreamPhase.DONE, entry!!.phase)
        assertEquals("file1\nfile2", entry.result)
        assertFalse(entry.failed)
        assertEquals(900L, entry.finishedAt)
    }

    @Test
    fun `结果帧带 error 时算失败并记下内核分类`() {
        val entry = toolStreamEntryFromFrame(
            "chat.tool.result",
            obj(
                """{"message":{"role":"tool","toolCallId":"c1","isError":true,"content":[{"type":"text","text":"boom"}]},"error":{"name":"FsError","code":"FS_SANDBOX_DENIED"}}"""
            ),
            900L,
        )
        assertNotNull(entry)
        assertTrue(entry!!.failed)
        assertEquals("FS_SANDBOX_DENIED", entry.errorCode)
    }

    @Test
    fun `结果帧退回 source_callId 也能配上`() {
        val entry = toolStreamEntryFromFrame(
            "chat.tool.result",
            obj("""{"message":{"role":"tool","source":{"kind":"tool","callId":"c9"},"content":[]}}"""),
            900L,
        )
        assertNotNull(entry)
        assertEquals("c9", entry!!.callId)
    }

    @Test
    fun `不认的事件类型不产出条目`() {
        assertNull(toolStreamEntryFromFrame("chat.message.committed", obj("""{"role":"assistant"}"""), 1L))
        assertNull(toolStreamEntryFromFrame("chat.turn.end", obj("""{"turn":1}"""), 1L))
    }

    // ------------------------------------------------------------ 合并与阶段

    @Test
    fun `同 callId 的后到帧覆盖而不是追加新条目`() {
        val state = ToolStreamState(sessionId = "s1")
            .merge(toolStreamEntryFromFrame("chat.tool.call", obj("""{"callId":"c1","name":"bash"}"""), 500L)!!)
            .merge(toolStreamEntryFromFrame("chat.tool.result", obj("""{"message":{"toolCallId":"c1","content":[]}}"""), 900L)!!)
        assertEquals(1, state.entries.size)
        assertEquals(ToolStreamPhase.DONE, state.entries.single().phase)
    }

    @Test
    fun `阶段只前进不倒退`() {
        val state = ToolStreamState(sessionId = "s1")
            .merge(toolStreamEntryFromFrame("chat.tool.call", obj("""{"callId":"c1"}"""), 500L)!!)
            .merge(toolCallDeltaFromFrame(obj("""{"kind":"tool-call","callId":"c1","argumentsDelta":"x"}"""))!!)
        assertEquals(ToolStreamPhase.RUNNING, state.entries.single().phase)
    }

    @Test
    fun `耗时只在起止都有且不倒退时给出`() {
        val entry = ToolStreamEntry(callId = "c1", startedAt = 100L, finishedAt = 300L)
        assertEquals(200L, entry.durationMs)
        assertNull(ToolStreamEntry(callId = "c1", startedAt = 100L).durationMs)
        assertNull(ToolStreamEntry(callId = "c1", finishedAt = 100L).durationMs)
        assertNull(ToolStreamEntry(callId = "c1", startedAt = 300L, finishedAt = 100L).durationMs)
    }

    // ------------------------------------------------------------ hasReplyContent

    @Test
    fun `hasReplyContent 为 true 或 false 时如实返回`() {
        assertEquals(true, hasReplyContentOf(obj("""{"hasReplyContent":true}""")))
        assertEquals(false, hasReplyContentOf(obj("""{"hasReplyContent":false}""")))
    }

    @Test
    fun `桥没说的时候返回 null 而不是 false`() {
        // 老桥没有这个字段：返回 null 才能让调用方保持旧行为
        // （「每条 assistant 消息都当结束」）。当成 false 会让老桥的回合永远不收尾。
        assertNull(hasReplyContentOf(obj("""{"role":"assistant"}""")))
        assertNull(hasReplyContentOf(null))
    }

    // ------------------------------------------------------------ 去重

    @Test
    fun `历史里的工具结果 callId 被认出来`() {
        val history = ChatHistory(
            events = listOf(
                sessionEvent("tool/call", """{"callId":"c1","name":"bash"}"""),
                sessionEvent(
                    "tool/result",
                    """{"message":{"role":"tool","toolCallId":"c1","content":[]}}""",
                    seq = 2,
                ),
            ),
            hasMore = false,
        )
        assertEquals(setOf("c1"), history.toolResultCallIds())
        assertEquals(setOf("c1"), history.toolCallIds())
    }

    @Test
    fun `没有结果的调用不算已经落库完成`() {
        val history = ChatHistory(
            events = listOf(sessionEvent("tool/call", """{"callId":"c1","name":"bash"}""")),
            hasMore = false,
        )
        assertEquals(emptySet<String>(), history.toolResultCallIds())
    }

    @Test
    fun `摘掉被实时行顶着的工具明细 整行都丢`() {
        val item = ChatDisplayItem(
            id = "1-tool",
            seq = 1,
            kind = ChatItemKind.TOOL,
            tools = listOf(ToolCallDetail(callId = "c1", name = "bash")),
        )
        assertTrue(listOf(item).withoutToolCalls(setOf("c1")).isEmpty())
        assertEquals(1, listOf(item).withoutToolCalls(setOf("c2")).size)
    }

    @Test
    fun `一行里有两次调用时只摘掉其中一次`() {
        val item = ChatDisplayItem(
            id = "1-tool",
            seq = 1,
            kind = ChatItemKind.TOOL,
            tools = listOf(
                ToolCallDetail(callId = "c1", name = "bash"),
                ToolCallDetail(callId = "c2", name = "read"),
            ),
        )
        val kept = listOf(item).withoutToolCalls(setOf("c1"))
        assertEquals(1, kept.size)
        assertEquals(listOf("c2"), kept.single().tools.map { it.callId })
    }

    @Test
    fun `非工具行原样保留`() {
        val item = ChatDisplayItem(id = "1-user", seq = 1, kind = ChatItemKind.USER, body = "你好")
        assertEquals(listOf(item), listOf(item).withoutToolCalls(setOf("c1")))
    }

    // ------------------------------------------------------------ 展示项

    @Test
    fun `实时行用官方措辞并标成还在跑`() {
        val entry = ToolStreamEntry(callId = "c1", name = "bash", arguments = """{"command":"ls"}""")
        val item = toolStreamDisplayItem(entry, "正在调用工具")
        assertEquals("live-tool-c1", item.id)
        assertEquals(ChatItemKind.TOOL, item.kind)
        assertTrue(item.title.orEmpty().startsWith("正在调用工具"))
        assertTrue(item.streaming)
        assertEquals("c1", item.tools.single().callId)
    }

    @Test
    fun `出结果之后不再算还在跑`() {
        val entry = ToolStreamEntry(callId = "c1", name = "bash", phase = ToolStreamPhase.DONE)
        val item = toolStreamDisplayItem(entry, null)
        assertFalse(item.streaming)
    }
}
