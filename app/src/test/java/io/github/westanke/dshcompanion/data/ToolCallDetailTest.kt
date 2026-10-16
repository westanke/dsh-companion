package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用详情（trajectory）的取值规则。
 *
 * 为什么值得单测：这些字段全是**从真实会话事件里挖出来的**（105 份 session.v4.jsonl.zstd、
 * 19050 条 tool/result），而规则本身在界面上一眼看不出来 ——
 * callId 藏在 `data.message.toolCallId`、耗时是两件事件的 time 相减、
 * 合并成一组的多次调用必须每条都留下明细。真机上错了只会表现为
 * 「展开后是空的」或「只剩第一条」，很难当场看出是哪一步错了。
 *
 * 样本形状全部照抄日志原文（见每条用例上的注释）。
 */
class ToolCallDetailTest {

    // ── 配对与耗时 ──

    @Test
    fun pairsResultByToolCallIdAndComputesDuration() {
        // 日志原文形状：tool/call 的 data 有 callId/name/arguments；
        // tool/result 的 data.message 有 toolCallId/content/isError。
        val history = history(
            entry(
                type = "tool/call",
                seq = 5,
                time = 1_000,
                data = """{"turn":1,"step":7,"callId":"call_1","name":"bash","arguments":"{\"command\":\"ls\"}"}""",
            ),
            entry(
                type = "tool/result",
                seq = 6,
                time = 1_140,
                data = """{"turn":1,"step":7,"message":{"role":"tool","source":{"kind":"tool","callId":"call_1"},"toolCallId":"call_1","content":[{"type":"text","text":"列出了 3 个文件"}],"isError":false,"id":"m1"}}""",
            ),
        )

        val tool = history.displayItems().single().tools.single()

        assertEquals("call_1", tool.callId)
        assertEquals("bash", tool.name)
        assertEquals("""{"command":"ls"}""", tool.arguments)
        assertEquals("列出了 3 个文件", tool.result)
        assertEquals(false, tool.failed)
        assertEquals(140L, tool.durationMs!!)
    }

    @Test
    fun fallsBackToSourceCallIdWhenTheMessageHasNoToolCallId() {
        // 实测两者 8006/8006 相等，但只认一个的话，内核哪天只留 source 就全配不上了。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"call_9","name":"read","arguments":"{}"}"""),
            entry(
                "tool/result",
                6,
                1_050,
                """{"message":{"source":{"kind":"tool","callId":"call_9"},"content":[{"type":"text","text":"正文"}],"isError":false}}""",
            ),
        )

        val tool = history.displayItems().single().tools.single()
        assertEquals("call_9", tool.callId)
        assertEquals("正文", tool.result)
        assertEquals(50L, tool.durationMs!!)
    }

    @Test
    fun ignoresResultsThatCarryNoCallIdAtAll() {
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"call_1","name":"bash","arguments":"{}"}"""),
            entry("tool/result", 6, 1_200, """{"message":{"content":[{"type":"text","text":"无处可归"}]}}"""),
        )

        val tool = history.displayItems().single().tools.single()
        assertNull(tool.result)
        assertNull(tool.durationMs)
    }

    @Test
    fun leavesDurationUnknownWhenThereIsNoResultYet() {
        // 还在跑的调用：有 call 没 result。这时既不显示耗时，也不显示结果。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"call_1","name":"bash","arguments":"{}"}"""),
        )

        val tool = history.displayItems().single().tools.single()
        assertNull(tool.result)
        assertNull(tool.durationMs)
    }

    // ── 合并组必须逐条留明细 ──

    @Test
    fun keepsEveryCallOfAMergedRunInOrder() {
        // mergeToolRuns 会把连续调用压成一行；压的是**显示**，明细一条都不能丢。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"a","name":"bash","arguments":"{\"command\":\"one\"}"}"""),
            entry("tool/result", 6, 1_100, """{"message":{"toolCallId":"a","content":[{"type":"text","text":"one ok"}],"isError":false}}"""),
            entry("tool/call", 7, 2_000, """{"callId":"b","name":"read","arguments":"{}"}"""),
            entry("tool/result", 8, 2_250, """{"message":{"toolCallId":"b","content":[{"type":"text","text":"b ok"}],"isError":false}}"""),
            entry("tool/call", 9, 3_000, """{"callId":"c","name":"bash","arguments":"{\"command\":\"three\"}"}"""),
            entry("tool/result", 10, 3_500, """{"message":{"toolCallId":"c","content":[{"type":"text","text":"Error: boom"}],"isError":true}}"""),
        )

        val items = history.displayItems()
        assertEquals(1, items.size)
        val merged = items.single()
        assertEquals(ChatItemKind.TOOL, merged.kind)

        assertEquals(listOf("a", "b", "c"), merged.tools.map { it.callId })
        assertEquals(listOf("bash", "read", "bash"), merged.tools.map { it.name })
        assertEquals(listOf("one ok", "b ok", "Error: boom"), merged.tools.map { it.result })
        assertEquals(listOf(100L, 250L, 500L), merged.tools.map { it.durationMs })
        assertEquals(listOf(false, false, true), merged.tools.map { it.failed })
    }

    // ── 结果正文 ──

    @Test
    fun marksFailureAndKeepsKernelErrorText() {
        // 日志原文里最典型的一条：只有一句 Error: Request timed out。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"t","name":"web_fetch","arguments":"{}"}"""),
            entry(
                "tool/result",
                6,
                1_010,
                """{"message":{"toolCallId":"t","content":[{"type":"text","text":"Error: Request timed out"}],"isError":true}}""",
            ),
        )

        val tool = history.displayItems().single().tools.single()
        assertEquals(true, tool.failed)
        assertEquals("Error: Request timed out", tool.result)
    }

    @Test
    fun joinsTextPartsWithBlankLinesAndNotesImageParts() {
        // 实测 content 里 text 与 image 会同时出现（40 组），图片只有附件引用、没有字节。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"i","name":"screenshot","arguments":"{}"}"""),
            entry(
                "tool/result",
                6,
                1_400,
                """{"message":{"toolCallId":"i","content":[
                    {"type":"text","text":"第一段"},
                    {"type":"image","attachment":{"attachmentId":"sha256:abc","mediaType":"image/png","bytes":2456,"width":320,"height":320,"name":"github.png"}},
                    {"type":"text","text":"第二段"}],"isError":false}}""",
            ),
        )

        val result = history.displayItems().single().tools.single().result!!
        assertTrue(result.contains("第一段"))
        assertTrue(result.contains("第二段"))
        assertTrue(result.contains("[image github.png 320×320]"))
    }

    @Test
    fun keepsAnEmptyResultDistinctFromNoResult() {
        // text 长度实测最小是 0：有结果、但一个字都没有，和「还没有结果」是两回事。
        val history = history(
            entry("tool/call", 5, 1_000, """{"callId":"e","name":"bash","arguments":"{}"}"""),
            entry("tool/result", 6, 1_100, """{"message":{"toolCallId":"e","content":[],"isError":false}}"""),
        )

        val tool = history.displayItems().single().tools.single()
        assertEquals("", tool.result)
        assertEquals(100L, tool.durationMs!!)
    }

    // ── 纯函数 ──

    @Test
    fun durationIsHiddenWhenItCannotBeTrusted() {
        assertNull(toolDurationMs(1_000, null))
        // 时钟倒退（休眠恢复 / NTP 校时）—— 实测日志里有一条 bash 差 9821 秒，那不是执行时间。
        assertNull(toolDurationMs(1_000, 900))
        assertEquals(0L, toolDurationMs(1_000, 1_000)!!)
        assertEquals(500L, toolDurationMs(1_000, 1_500)!!)
    }

    @Test
    fun formatsDurationAtEveryBoundary() {
        assertEquals("0 ms", formatToolDuration(0))
        assertEquals("999 ms", formatToolDuration(999))
        assertEquals("1.0 s", formatToolDuration(1_000))
        assertEquals("1.5 s", formatToolDuration(1_500))
        assertEquals("1m 0s", formatToolDuration(60_000))
        assertEquals("2m 3s", formatToolDuration(123_000))
        assertEquals("1h 0m", formatToolDuration(3_600_000))
        // 实测最大的一条（167336332 ms）也在这一档里，不会溢出成怪值。
        assertEquals("46h 28m", formatToolDuration(167_336_332))
    }

    @Test
    fun prettyArgumentsFormatsJsonAndFallsBackToRawText() {
        val pretty = prettyToolArguments("""{"command":"ls -la","description":"列目录"}""")
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"command\""))
        assertTrue(pretty.contains("ls -la"))

        // 坏 JSON 原样返回：宁可给一坨原始文本，也不要把参数藏起来。
        assertEquals("{raw protocol payload", prettyToolArguments("{raw protocol payload"))
        assertEquals("", prettyToolArguments("   "))
    }

    private fun history(vararg events: HistoryEntry) = ChatHistory(events.toList(), hasMore = false)

    private fun entry(type: String, seq: Int, time: Long, data: String, view: String? = null) = HistoryEntry(
        event = SessionEvent(type, seq, time, json.parseToJsonElement(data)),
        view = view?.let(json::parseToJsonElement),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
