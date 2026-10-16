package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话显示分组的两件事：**二分边界**与**摘要措辞**。
 *
 * 为什么值得单测：分组错了不会崩，只会表现为「有一段话不见了」或者「一组干的事被劈成两组」——
 * 真机上极难当场归因（页面只是看起来少了一行）。摘要措辞同理：单位换算差一档，
 * 用户看到的是「耗时 0 秒」，看起来像功能坏了。
 *
 * 最要紧的一条是 [flatteningEveryGroupingReproducesTheInputExactly]：**分组不许丢项**。
 * 它是**穷举**断言（6 种 kind、长度 1..4 共 1554 条序列），因为分区函数的 bug
 * 恰恰只在某些相邻组合上露头，挑几个例子是挑不出来的。
 *
 * 数据层是纯函数：不读系统时间，时间只从入参（[groupChatDisplay] 的 `timeOf`）取，
 * 所以下面每一条断言都是确定的。
 */
class ChatDisplayGroupsTest {

    // ── 二分边界 ──

    @Test
    fun emptyInputProducesNoEntries() {
        assertTrue(groupChatDisplay(emptyList()).isEmpty())
    }

    @Test
    fun aSingleMessageStaysAsIs() {
        val user = item(ChatItemKind.USER, "1-user")

        val entries = groupChatDisplay(listOf(user))

        assertEquals(1, entries.size)
        assertEquals(ChatDisplayEntry.Message(user), entries.single())
    }

    @Test
    fun threeConsecutiveProcessItemsBecomeOneGroup() {
        val items = listOf(
            item(ChatItemKind.CONTEXT, "1-context"),
            item(ChatItemKind.TOOL, "2-tool", tools = listOf(tool("a"))),
            item(ChatItemKind.TOOL, "3-tool", tools = listOf(tool("b"))),
        )

        val entries = groupChatDisplay(items)

        assertEquals(1, entries.size)
        val group = entries.single() as ChatDisplayEntry.ProcessGroup
        assertEquals(items, group.items)
        assertEquals(3, group.items.size)
        assertEquals(2, group.summary.toolCalls)
        assertEquals(1, group.summary.contextItems)
    }

    @Test
    fun messageProcessMessageKeepsThreeEntriesInOrder() {
        val user = item(ChatItemKind.USER, "1-user")
        val reasoning = item(ChatItemKind.REASONING, "2-reasoning")
        val assistant = item(ChatItemKind.ASSISTANT, "3-assistant")

        val entries = groupChatDisplay(listOf(user, reasoning, assistant))

        assertEquals(3, entries.size)
        assertEquals(ChatDisplayEntry.Message(user), entries[0])
        assertTrue(entries[1] is ChatDisplayEntry.ProcessGroup)
        assertEquals(listOf(reasoning.id), groups(entries).single().items.map { it.id })
        assertEquals(ChatDisplayEntry.Message(assistant), entries[2])
    }

    @Test
    fun processGroupsAtBothEndsAreBothFlushed() {
        val entries = groupChatDisplay(
            listOf(
                item(ChatItemKind.CONTEXT, "1-context"),
                item(ChatItemKind.REASONING, "2-reasoning"),
                item(ChatItemKind.USER, "3-user"),
                item(ChatItemKind.TOOL, "4-tool"),
                item(ChatItemKind.TOOL, "5-tool"),
            ),
        )

        assertEquals(3, entries.size)
        // 末尾那一组靠收尾的 flush 才出现 —— 漏掉它就会整组消失。
        assertTrue(entries.first() is ChatDisplayEntry.ProcessGroup)
        assertTrue(entries.last() is ChatDisplayEntry.ProcessGroup)
        assertEquals(listOf("1-context", "2-reasoning"), groups(entries)[0].items.map { it.id })
        assertEquals(listOf("4-tool", "5-tool"), groups(entries)[1].items.map { it.id })
        assertEquals(listOf("3-user"), messages(entries).map { it.id })
    }

    @Test
    fun aSingleProcessItemStillBecomesAGroup() {
        // 只有 1 项也照样产组：折不折、展不展是 UI 的判断，数据层不做这个决定
        //（做了的话 UI 就没有「这一项其实是一组」的信息了）。
        val entries = groupChatDisplay(listOf(item(ChatItemKind.CONTEXT, "1-context")))

        assertEquals(1, entries.size)
        val group = entries.single() as ChatDisplayEntry.ProcessGroup
        assertEquals(1, group.items.size)
        assertEquals("1-context", group.id)
        assertEquals(1, group.summary.contextItems)
        assertEquals(0, group.summary.toolCalls)
    }

    @Test
    fun groupIdIsTheFirstItemIdSoItSurvivesARebuild() {
        // 稳定 key 决定「展开状态跟不跟得住列表重建」，取首项 id 是刻意的。
        val first = item(ChatItemKind.CONTEXT, "7-context")
        val group = groups(groupChatDisplay(listOf(first, item(ChatItemKind.TOOL, "8-tool")))).single()

        assertEquals("7-context", group.id)
        assertEquals(first, group.items.first())
    }

    // ── 消息类项必须真的挡住分区（折错了用户看不到正文） ──

    @Test
    fun failureRowsAreNeverFoldedIntoTheProcessGroup() {
        // 终局失败是用户唯一能看到「为什么失败」的地方，两边各夹一次工具调用，
        // 结果必须是 3 个 entry（组 / 失败 / 组），失败那条独立成行。
        val failure = ChatDisplayItem(
            id = "3-failure",
            seq = 3,
            kind = ChatItemKind.FAILURE,
            failure = ChatTurnFailure(message = "请求过于频繁", code = "429"),
        )

        val entries = groupChatDisplay(
            listOf(item(ChatItemKind.TOOL, "1-tool"), failure, item(ChatItemKind.TOOL, "4-tool")),
        )

        assertEquals(3, entries.size)
        assertEquals(listOf(failure), messages(entries))
        assertEquals(2, groups(entries).size)
    }

    @Test
    fun assistantBodyBreaksTheGroup() {
        val entries = groupChatDisplay(
            listOf(
                item(ChatItemKind.REASONING, "1-reasoning"),
                item(ChatItemKind.ASSISTANT, "2-assistant"),
                item(ChatItemKind.TOOL, "3-tool"),
            ),
        )

        assertEquals(3, entries.size)
        assertTrue(entries[1] is ChatDisplayEntry.Message)
        // 正文把它们劈成两组：中间有结论，说明确实是两段工作，硬并会让「这组干了什么」失去意义。
        assertEquals(listOf("1-reasoning"), groups(entries)[0].items.map { it.id })
        assertEquals(listOf("3-tool"), groups(entries)[1].items.map { it.id })
    }

    // ── 计数 ──

    @Test
    fun mergedToolRowCountsEveryCallNotJustTheRow() {
        // mergeToolRuns 会把**相邻**调用压成一行（明细都在 tools 里）。
        // 数行数会把「连续 3 次调用」报成 1 次，摘要就废了 —— 所以按明细条数计。
        val merged = item(
            ChatItemKind.TOOL,
            "6-tools",
            tools = listOf(tool("a"), tool("b"), tool("c")),
        )

        val group = groups(groupChatDisplay(listOf(merged))).single()

        assertEquals(3, group.summary.toolCalls)
        assertEquals(1, group.items.size)
    }

    @Test
    fun toolRowWithoutDetailsStillCountsAsOneCall() {
        // 拿不到 tool/call 数据时会退化成一条没有明细的工具行，它仍然是一次调用。
        val group = groups(groupChatDisplay(listOf(item(ChatItemKind.TOOL, "1-tool")))).single()

        assertEquals(1, group.summary.toolCalls)
    }

    // ── 摘要措辞（title 的四种分支） ──

    @Test
    fun titleUsesSecondsBelowOneMinute() {
        val summary = ProcessGroupSummary(toolCalls = 5, contextItems = 3, startedAt = 1_000, finishedAt = 13_000)

        assertEquals("耗时 12 秒 · 3 项上下文 · 5 次工具调用", summary.title())
    }

    @Test
    fun titleUsesMinutesAndOmitsTheSecondsPartWhenItIsZero() {
        assertEquals("耗时 1 分 12 秒", ProcessGroupSummary(0, 0, 0, 72_000).title())
        // 秒为 0 → 省掉秒段。
        assertEquals("耗时 2 分", ProcessGroupSummary(0, 0, 0, 120_000).title())
        // 超过一小时也只到「分」为止：标题只有一行。
        assertEquals("耗时 60 分", ProcessGroupSummary(0, 0, 0, 3_600_000).title())
        // 不足 1 秒单列一档：向下取整会印成「耗时 0 秒」，那读起来就是「没有耗时」，
        // 与「整段省略」的表达撞车，用户分不清。
        assertEquals("耗时 不足 1 秒", ProcessGroupSummary(0, 0, 0, 300).title())
    }

    @Test
    fun titleShowsOnlyCountsWhenThereIsNoTime() {
        assertEquals("1 项上下文 · 2 次工具调用", ProcessGroupSummary(2, 1, null, null).title())
        // 只有一头有时间也不显示耗时：区间不完整就不是区间。
        assertEquals("2 次工具调用", ProcessGroupSummary(2, 0, 1_000, null).title())
        assertEquals("2 次工具调用", ProcessGroupSummary(2, 0, null, 1_000).title())
    }

    @Test
    fun titleFallsBackToProcessWhenEverythingIsZero() {
        // 组头不能是空白 —— 空白看起来像渲染坏了。
        assertEquals("过程", ProcessGroupSummary(0, 0, null, null).title())
    }

    @Test
    fun titleOmitsZeroCountSegments() {
        assertEquals("耗时 12 秒 · 3 次工具调用", ProcessGroupSummary(3, 0, 0, 12_000).title())
        assertEquals("耗时 12 秒 · 1 项上下文", ProcessGroupSummary(0, 1, 0, 12_000).title())
    }

    @Test
    fun titleSkipsUntrustworthySpans() {
        // 时钟倒退（休眠恢复 / NTP 校时）：实测日志里有一条 bash 差 9821 秒，那不是执行时间。
        assertNull(ProcessGroupSummary(2, 0, 5_000, 1_000).durationMs())
        assertEquals("2 次工具调用", ProcessGroupSummary(2, 0, 5_000, 1_000).title())
        // 同一毫秒：区间等于没有信息。
        assertNull(ProcessGroupSummary(2, 0, 5_000, 5_000).durationMs())
        // 可信区间照常给出来。
        assertEquals(12_000L, ProcessGroupSummary(0, 0, 1_000, 13_000).durationMs())
    }

    // ── 时间来源的接缝 ──

    @Test
    fun durationNeedsAnExplicitTimeSourceBecauseItemsCarryNoneYet() {
        val items = listOf(item(ChatItemKind.CONTEXT, "1-context"), item(ChatItemKind.TOOL, "2-tool"))

        // 默认取不到时间：ChatDisplayItem 上目前**没有**时间字段，所以没有「耗时」段。
        // 这是刻意的诚实 —— 宁可少一段，也不编一个耗时。
        assertEquals(
            "1 项上下文 · 1 次工具调用",
            groups(groupChatDisplay(items)).single().summary.title(),
        )

        // 传了时间来源就点亮：UI 侧按 id 查事件时间即可，本文件不用改。
        val times = mapOf("1-context" to 1_000L, "2-tool" to 73_000L)
        val wired = groups(groupChatDisplay(items) { times[it.id] }).single().summary

        assertEquals(1_000L, wired.startedAt)
        assertEquals(73_000L, wired.finishedAt)
        assertEquals("耗时 1 分 12 秒 · 1 项上下文 · 1 次工具调用", wired.title())
    }

    // ── 属性断言：分组不许丢项（本文件最重要的回归） ──

    @Test
    fun flatteningEveryGroupingReproducesTheInputExactly() {
        val sequences = allKindSequences()
        assertEquals(1554, sequences.size)

        var processGroups = 0
        var messageEntries = 0

        sequences.forEach { kinds ->
            val input = kinds.mapIndexed { index, kind -> item(kind, "${index + 1}-$kind") }
            val entries = groupChatDisplay(input)
            val flat = flatten(entries)

            // 一条都不能丢、一条都不能多、顺序不能变。
            assertEquals(input, flat)
            assertEquals(input.map { it.id }, flat.map { it.id })
            assertEquals(input.toSet(), flat.toSet())

            // 反面保险：正文与失败行永远不许出现在组里。
            groups(entries).forEach { group ->
                assertTrue(
                    "正文/失败行被折进了过程组：$kinds -> ${group.items.map { it.kind }}",
                    group.items.none {
                        it.kind == ChatItemKind.USER ||
                            it.kind == ChatItemKind.ASSISTANT ||
                            it.kind == ChatItemKind.FAILURE ||
                            it.failure != null
                    },
                )
            }

            // 全是过程项 → 恰好一组；全是消息项 → 一个组都没有。
            if (kinds.all { it == ChatItemKind.CONTEXT || it == ChatItemKind.REASONING || it == ChatItemKind.TOOL }) {
                assertEquals("过程项应收成一组：$kinds", 1, groups(entries).size)
            }
            if (kinds.all { it == ChatItemKind.USER || it == ChatItemKind.ASSISTANT || it == ChatItemKind.FAILURE }) {
                assertTrue("消息项不该产组：$kinds", groups(entries).isEmpty())
            }

            processGroups += groups(entries).size
            messageEntries += messages(entries).size
        }

        // 摊平断言单独看是**弱**的：一个「全部当消息」的实现也能通过它。
        // 所以再钉一次两类都真的出现过（上面那两条 if 是精确断言，这里是总量兜底）。
        assertTrue("一条过程组都没产出，说明分区根本没生效", processGroups > 0)
        assertTrue("一条消息都没留下，说明正文被折进去了", messageEntries > 0)
    }

    // ── 夹具 ──

    /** 造一条展示项：id 形如 `"3-user"`，序号从 id 里取，方便回看顺序有没有乱。 */
    private fun item(
        kind: ChatItemKind,
        id: String,
        tools: List<ToolCallDetail> = emptyList(),
    ) = ChatDisplayItem(
        id = id,
        seq = id.substringBefore('-').toIntOrNull() ?: 0,
        kind = kind,
        tools = tools,
    )

    private fun tool(callId: String) = ToolCallDetail(callId = callId, name = "bash", arguments = "{}")

    /** 把分组结果摊平回一串 item —— 属性断言靠它。 */
    private fun flatten(entries: List<ChatDisplayEntry>): List<ChatDisplayItem> =
        entries.flatMap { entry ->
            when (entry) {
                is ChatDisplayEntry.Message -> listOf(entry.item)
                is ChatDisplayEntry.ProcessGroup -> entry.items
            }
        }

    private fun groups(entries: List<ChatDisplayEntry>): List<ChatDisplayEntry.ProcessGroup> =
        entries.filterIsInstance<ChatDisplayEntry.ProcessGroup>()

    private fun messages(entries: List<ChatDisplayEntry>): List<ChatDisplayItem> =
        entries.filterIsInstance<ChatDisplayEntry.Message>().map { it.item }

    /**
     * 所有长度 1..4 的 kind 序列：6 + 36 + 216 + 1296 = 1554 条。
     *
     * 穷举而不是挑例子：分区的 bug 只在这类**相邻组合**上露头，
     * 比如「过程-消息-过程-过程」和「消息-过程-过程-消息」的收尾差别。
     */
    private fun allKindSequences(maxLength: Int = 4): List<List<ChatItemKind>> {
        val out = mutableListOf<List<ChatItemKind>>()
        fun walk(prefix: List<ChatItemKind>) {
            if (prefix.isNotEmpty()) out += prefix
            if (prefix.size >= maxLength) return
            ChatItemKind.entries.forEach { walk(prefix + it) }
        }
        walk(emptyList())
        return out
    }
}
