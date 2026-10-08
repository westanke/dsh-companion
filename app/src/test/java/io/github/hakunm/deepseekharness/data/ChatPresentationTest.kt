package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatPresentationTest {
    @Test
    fun keepsReadableConversationRowsAndDropsProtocolLifecycleEvents() {
        val history = ChatHistory(
            events = listOf(
                entry("turn/start", 1, """{"turn":1}"""),
                entry("user/message", 2, """{"content":[{"type":"text","text":"Build the feature"}],"source":{"kind":"user"}}"""),
                entry(
                    "user/message",
                    3,
                    """{"content":[{"type":"text","text":"Injected instructions"}],"source":{"kind":"agent-instructions","changes":[{"path":"AGENTS.md"}]}}""",
                ),
                entry(
                    "assistant/message",
                    4,
                    """{"turn":1,"step":1,"message":{"content":[{"type":"reasoning","text":"Inspect the repository"},{"type":"tool-call","id":"call-1","name":"bash","arguments":"{}"},{"type":"text","text":"The implementation is ready."}]}}""",
                ),
                entry(
                    "tool/call",
                    5,
                    """{"turn":1,"step":1,"callId":"call-1","name":"bash","arguments":"{\"command\":\"pnpm test\",\"description\":\"Run tests\"}"}""",
                    """{"for":"call","view":{"card":"terminal","title":"pnpm test","description":"Run tests"}}""",
                ),
                entry(
                    "tool/call",
                    6,
                    """{"turn":1,"step":1,"callId":"call-2","name":"mystery","arguments":"{raw protocol payload"}""",
                    """{"for":"call","view":{"title":"Safe host title"}}""",
                ),
                entry("step/end", 7, """{"turn":1,"step":1}"""),
                entry("turn/end", 8, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
            hasMore = false,
        )

        val items = history.displayItems()

        assertEquals(
            listOf(
                ChatItemKind.USER,
                ChatItemKind.CONTEXT,
                ChatItemKind.REASONING,
                ChatItemKind.ASSISTANT,
                ChatItemKind.TOOL,
                ChatItemKind.TOOL,
            ),
            items.map { it.kind },
        )
        assertEquals("AGENTS.md", items[1].title)
        assertEquals("The implementation is ready.", items[3].body)
        assertEquals("Bash", items[4].title)
        assertEquals("Run tests", items[4].body)
        assertEquals("Safe host title", items[5].body)
        assertFalse(items[5].body.contains("raw protocol payload"))
        assertFalse(items.any { it.body.contains("turn/end") || it.body.contains("sourceEventSeqs") })
    }

    @Test
    fun foldsStableWebSocketDeltasIntoAReadableLiveAssistantMessage() {
        val first = WorkspaceEvent(
            "1",
            "chat.message.delta",
            1,
            json.parseToJsonElement(
                """{"sessionId":"session-1","turn":2,"step":1,"index":0,"kind":"text","text":"实时"}""",
            ),
        ).chatDelta()!!
        val second = WorkspaceEvent(
            "2",
            "chat.message.delta",
            2,
            json.parseToJsonElement(
                """{"sessionId":"session-1","turn":2,"step":1,"index":0,"kind":"text","text":"输出"}""",
            ),
        ).chatDelta()!!

        val live = LiveChatState(first.sessionId, first.turn, first.step).append(first).append(second)

        assertEquals("实时输出", live.displayItems().single().body)
        assertEquals(ChatItemKind.ASSISTANT, live.displayItems().single().kind)
        assertEquals(2, live.revision)
    }

    @Test
    fun readsTodoAndPermissionControlsFromSessionProjections() {
        val history = ChatHistory(
            events = emptyList(),
            hasMore = false,
            projections = json.parseToJsonElement(
                """{"values":{"todos":[{"content":"Inspect API","status":"completed"},{"content":"Build UI","status":"in_progress"}],"permissions":{"currentValue":"workspace-write","options":[{"value":"read-only","name":"Read Only"},{"value":"workspace-write","name":"Workspace Write","description":"Write inside the workspace"},{"value":"danger-full-access","name":"Full access"}]}}}""",
            ),
        )

        assertEquals(listOf("Inspect API", "Build UI"), history.todoItems().map(TodoItem::content))
        assertEquals(listOf("completed", "in_progress"), history.todoItems().map(TodoItem::status))
        val permissions = history.permissionSelect()!!
        assertEquals("workspace-write", permissions.currentValue)
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), permissions.options.map(PermissionOption::value))
        assertEquals("Write inside the workspace", permissions.options[1].description)
    }

    @Test
    fun keepsAnImageOnlyMessageThatHasNoTextAtAll() {
        // 这是用户真机报的 bug：Web 端发一张图、不打字，App 里整条消息消失。
        // 旧实现用 `body.takeIf(String::isNotBlank)` 判定，于是没有文字的消息被直接丢弃。
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 7,
            data = """{"source":{"kind":"user"},"content":[{"type":"image","attachment":{
                "attachmentId":"sha256:9759b45815c74eb5289a46074ad655a0",
                "mediaType":"image/jpeg","width":1080,"height":2440,"bytes":143847,
                "name":"shot.jpg"}}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals(ChatItemKind.USER, items[0].kind)
        assertEquals("", items[0].body)
        assertEquals(1, items[0].attachments.size)
        assertEquals("sha256:9759b45815c74eb5289a46074ad655a0", items[0].attachments[0].attachmentId)
        assertEquals("image/jpeg", items[0].attachments[0].mediaType)
    }

    @Test
    fun stillDropsEventsWithNeitherTextNorAttachment() {
        // 放宽判据不能顺手把所有空事件都放进来：协议层的空 user/message 仍应被丢掉，
        // 否则会话里会冒出一堆点不动的空气泡。
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 8,
            data = """{"source":{"kind":"user"},"content":[{"type":"text","text":"   "}]}""",
        )), hasMore = false)
        assertEquals(0, history.displayItems().size)
    }

    @Test
    fun ignoresFilePartsBecauseTheirReceiptIsNotAnAttachmentId() {
        // `type:"file"` 的 receiptId 指向一次上传的暂存凭证，与服务端持久化的附件存储
        // 不是同一套东西；当成图片去取只会得到 404，所以这里必须排除。
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 9,
            data = """{"source":{"kind":"user"},"content":[
                {"type":"text","text":"看附件"},
                {"type":"file","receiptId":"receipt-abc"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals("看附件", items[0].body)
        assertEquals(0, items[0].attachments.size)
    }

    private fun entry(type: String, seq: Int, data: String, view: String? = null) = HistoryEntry(
        event = SessionEvent(type, seq, seq.toLong(), json.parseToJsonElement(data)),
        view = view?.let(json::parseToJsonElement),
    )
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
