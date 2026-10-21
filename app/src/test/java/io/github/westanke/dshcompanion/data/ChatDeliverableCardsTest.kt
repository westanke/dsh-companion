package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显式交付物卡片的映射回归（`ChatPresentation.displayItems` 的交付物冲刷）。
 *
 * 用户报的缺口：桌面端会话里 agent 用 present 工具交付的文件有**内联卡片**，
 * 手机端没有 —— 数据（`deliverables/presented` 事件）一直在历史里，只是映射层
 * 把它整类丢掉。修复后卡片钉在**轮次收尾**（收尾消息之后、下一轮提问之前），
 * 对齐桌面端 turnTail 的钉法。
 */
class ChatDeliverableCardsTest {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private fun entry(type: String, seq: Int, data: String) = HistoryEntry(
        event = SessionEvent(type, seq, seq.toLong(), json.parseToJsonElement(data)),
    )

    @Test
    fun pinsDeliverableCardAfterTheClosingMessageOfItsTurn() {
        val history = ChatHistory(
            events = listOf(
                entry("turn/start", 1, """{"turn":1}"""),
                entry("user/message", 2, """{"content":[{"type":"text","text":"Ship the report"}],"source":{"kind":"user"}}"""),
                entry("assistant/message", 3, """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"Here is the report."}]}}"""),
                entry("deliverables/presented", 4, """{"turn":1,"files":[{"path":"/workspace/report.md","description":"本轮排障报告"}]}"""),
                entry("turn/end", 5, """{"turn":1,"reason":{"kind":"completed"}}"""),
                entry("turn/start", 6, """{"turn":2}"""),
                entry("user/message", 7, """{"content":[{"type":"text","text":"Thanks"}],"source":{"kind":"user"}}"""),
            ),
            hasMore = false,
        )

        val items = history.displayItems()

        // 卡片在收尾消息之后、下一轮提问之前 —— 不是钉在 present 工具调用的位置上。
        assertEquals(
            listOf(
                ChatItemKind.USER,
                ChatItemKind.ASSISTANT,
                ChatItemKind.DELIVERABLE,
                ChatItemKind.USER,
            ),
            items.map { it.kind },
        )
        val card = items[2]
        assertEquals("/workspace/report.md", card.deliverable?.path)
        assertEquals("本轮排障报告", card.deliverable?.description)
        // 稳定 key：seq + 批内序号，列表重建时同一张卡就是同一个 key。
        assertEquals("4-deliverable-0", card.id)
        // 卡片不是 Markdown 正文 —— body 保持空，渲染器不做反向嗅探。
        assertEquals("", card.body)
    }

    @Test
    fun appendsPendingDeliverableCardsWhenHistoryHasNoTurnEnd() {
        val history = ChatHistory(
            events = listOf(
                entry("turn/start", 1, """{"turn":1}"""),
                entry("user/message", 2, """{"content":[{"type":"text","text":"Pack it"}],"source":{"kind":"user"}}"""),
                entry("assistant/message", 3, """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"Building."}]}}"""),
                entry("deliverables/presented", 4, """{"turn":1,"files":[{"path":"/workspace/app.apk","description":"v2.2.48 安装包"}]}"""),
            ),
            hasMore = false,
        )

        val items = history.displayItems()

        // 会话尾部没有 turn/end（仍在运行、或老格式历史）：补在最后，不丢。
        assertEquals(
            listOf(ChatItemKind.USER, ChatItemKind.ASSISTANT, ChatItemKind.DELIVERABLE),
            items.map { it.kind },
        )
        assertEquals("/workspace/app.apk", items[2].deliverable?.path)
    }

    @Test
    fun keepsOneCardPerPathWithinATurnUsingTheLatestDescription() {
        val history = ChatHistory(
            events = listOf(
                entry("user/message", 1, """{"content":[{"type":"text","text":"Fix it"}],"source":{"kind":"user"}}"""),
                entry("deliverables/presented", 2, """{"turn":1,"files":[{"path":"/workspace/report.md","description":"初稿"}]}"""),
                entry("deliverables/presented", 3, """{"turn":1,"files":[{"path":"/workspace/report.md","description":"修订稿"}]}"""),
                entry("turn/end", 4, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
            hasMore = false,
        )

        val cards = history.displayItems().filter { it.kind == ChatItemKind.DELIVERABLE }

        // 同一轮里同一个路径重复交付：只留一张卡，说明取最后一次（后写的更新）。
        assertEquals(1, cards.size)
        assertEquals("修订稿", cards.single().deliverable?.description)
    }

    @Test
    fun keepsDeliverableCardsOnAFailedTurn() {
        val history = ChatHistory(
            events = listOf(
                entry("user/message", 1, """{"content":[{"type":"text","text":"Go"}],"source":{"kind":"user"}}"""),
                entry("deliverables/presented", 2, """{"turn":1,"files":[{"path":"/workspace/a.md"}]}"""),
                entry("turn/end", 3, """{"turn":1,"reason":{"kind":"error","error":{"message":"rate limited","code":"429"}}}"""),
            ),
            hasMore = false,
        )

        val items = history.displayItems()

        // 交付发生在失败之前：文件是真的给了，卡片不能跟着失败一起消失。
        assertEquals(ChatItemKind.DELIVERABLE, items[1].kind)
        assertEquals(ChatItemKind.FAILURE, items.last().kind)
        assertEquals("/workspace/a.md", items[1].deliverable?.path)
    }

    @Test
    fun toleratesMissingDescriptionAndEmptyFiles() {
        val history = ChatHistory(
            events = listOf(
                entry("deliverables/presented", 1, """{"turn":1,"files":[]}"""),
                entry("deliverables/presented", 2, """{"turn":1,"files":[{"path":"/workspace/b.md"}]}"""),
                entry("deliverables/presented", 3, """{"not-files":true}"""),
                entry("turn/end", 4, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
            hasMore = false,
        )

        val cards = history.displayItems().filter { it.kind == ChatItemKind.DELIVERABLE }

        // 空 files、缺 files 字段都是安静的空结果；缺说明的卡片照样出，说明位空着。
        assertEquals(1, cards.size)
        assertEquals("/workspace/b.md", cards.single().deliverable?.path)
        assertNull(cards.single().deliverable?.description)
    }

    @Test
    fun keepsDeliverableCardsOutOfProcessGroups() {
        val items = listOf(
            ChatDisplayItem(id = "1", seq = 1, kind = ChatItemKind.TOOL, title = "t"),
            ChatDisplayItem(id = "2", seq = 2, kind = ChatItemKind.DELIVERABLE, deliverable = ChatDeliverable("/workspace/a.md")),
            ChatDisplayItem(id = "3", seq = 3, kind = ChatItemKind.ASSISTANT, body = "done"),
        )

        val entries = groupChatDisplay(visibleItemsOf(items, showContext = true))

        // 卡片是消息类独立行：夹在工具与正文之间也不被折进过程组 ——
        // 折叠错了用户是真的看不到「你给的东西」。
        assertEquals(
            listOf(ChatItemKind.DELIVERABLE, ChatItemKind.ASSISTANT),
            entries.filterIsInstance<ChatDisplayEntry.Message>().map { it.item.kind },
        )
        assertTrue(entries.filterIsInstance<ChatDisplayEntry.ProcessGroup>().single().items.single().kind == ChatItemKind.TOOL)
    }
}
