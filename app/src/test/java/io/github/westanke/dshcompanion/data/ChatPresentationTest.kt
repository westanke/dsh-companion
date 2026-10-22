package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.R
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        // 连续两次工具调用现在**合并成一行**（与 WebView 页同口径），TOOL 由两项变一项。
        assertEquals(
            listOf(
                ChatItemKind.USER,
                ChatItemKind.CONTEXT,
                ChatItemKind.REASONING,
                ChatItemKind.ASSISTANT,
                ChatItemKind.TOOL,
            ),
            items.map { it.kind },
        )
        assertEquals("AGENTS.md", items[1].title)
        assertEquals("The implementation is ready.", items[3].body)

        // 标题不再是 "Bash" 这种英文类别词，而是官方口径的动作短语；
        // 认不出的工具（mystery）按自己的名字列出，不静默吞掉。
        assertEquals(listOf("执行了命令", "mystery"), items[4].actions)
        assertEquals("执行了命令，mystery", items[4].title)
        // 合并后正文把每条都带上，展开时才看得到细节。
        assertTrue(items[4].body.contains("Run tests"))
        assertTrue(items[4].body.contains("Safe host title"))

        // 下面两条是**安全断言**，与呈现方式无关，必须继续成立：
        // 坏掉的 arguments 原文（"{raw protocol payload"）不得进入界面。
        assertFalse(items.any { it.body.contains("raw protocol payload") })
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
        val permissions = history.permissionSelect(null)!!
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

    @Test
    fun showsAHandoffImageAsAnAttachmentInsteadOfAPathLine() {
        // 纯文本模型发图：插件把图落成工作区 `.dsh-images/` 文件，消息里追加一段
        // 「人话 + 机器路径」说明。用户对上一版的反馈原话是「给一堆路径」——
        // 人要的是**看到图**，所以说明整段摘掉、换成可显示的附件。
        val path = "/media/wangke/OFFICE/workspace/.dsh-images/" +
            "24ddaa44f9ed8f8a509af39de29f04c9e2e9f068f826ee312867a877a2ec4ad2.jpg"
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 30,
            data = """{"source":{"kind":"user"},"content":[
                {"type":"text","text":"测试一下 人类能看懂的图片识别"},
                {"type":"text","text":"🖼️ 图片 1「274935.jpg」（当前模型不支持图片输入，已保存到工作目录 .dsh-images/24ddaa….jpg）\n[Image: source: $path]"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals("测试一下 人类能看懂的图片识别", items[0].body)
        assertEquals(1, items[0].attachments.size)
        val attachment = items[0].attachments[0]
        assertEquals("handoff:$path", attachment.attachmentId)
        assertEquals(path, attachment.handoffPath)
        assertEquals("image/jpeg", attachment.mediaType)
        assertEquals("24ddaa44f9ed8f8a509af39de29f04c9e2e9f068f826ee312867a877a2ec4ad2.jpg", attachment.name)
    }

    @Test
    fun markdownImageInAssistantProseBecomesAnAttachment() {
        // 真机反馈：助手正文里贴 `![](/media/…)`，App 不认这种图，正文里留一块莫名其妙的空白，
        // 看着像内容丢了。现在它会被摘成附件，走交接图那条「绝对路径取字节」的通路。
        val path = "/media/wangke/OFFICE/workspace/研究类/report/截图 c1.png"
        val history = ChatHistory(listOf(entry(
            type = "assistant/message",
            seq = 40,
            data = """{"message":{"role":"assistant","content":[
                {"type":"text","text":"先看症状（修复前）：\n\n![修复前]($path)\n\n原因并不是没加载。"}]}}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals("先看症状（修复前）：\n\n原因并不是没加载。", items[0].body)
        assertEquals(1, items[0].attachments.size)
        assertEquals("handoff:$path", items[0].attachments[0].attachmentId)
        assertEquals(path, items[0].attachments[0].handoffPath)
        assertEquals("image/png", items[0].attachments[0].mediaType)
    }

    @Test
    fun markdownImageOnlyAssistantMessageSurvives() {
        // 只贴图、一个字都不写：正文摘干净后是空的，但附件不空 —— 消息必须留下。
        val path = "/media/wangke/OFFICE/workspace/reports/only-image.png"
        val history = ChatHistory(listOf(entry(
            type = "assistant/message",
            seq = 41,
            data = """{"message":{"role":"assistant","content":[
                {"type":"text","text":"![]($path)"}]}}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals("", items[0].body)
        assertEquals(1, items[0].attachments.size)
        assertEquals(path, items[0].attachments[0].handoffPath)
    }

    @Test
    fun remoteMarkdownImageDegradesToALink() {
        val history = ChatHistory(listOf(entry(
            type = "assistant/message",
            seq = 42,
            data = """{"message":{"role":"assistant","content":[
                {"type":"text","text":"看图：![示意图](https://example.test/a.png)"}]}}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        // App 不做远程图片加载：降级成普通链接，不留白洞、也不假装能显示。
        assertEquals("看图：[示意图](https://example.test/a.png)", items[0].body)
        assertTrue(items[0].attachments.isEmpty())
    }

    @Test
    fun keepsAHandoffOnlyMessageAndLeavesNothingInTheBody() {
        // 只发图 + 纯文本模型：摘掉说明后正文是空的，但附件不空 —— 消息必须留下，
        // 否则又回到「App 里整条消息消失」那个老 bug。
        val path = "/root/.dsh-images/aa11bb22.jpg"
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 31,
            data = """{"source":{"kind":"user"},"content":[
                {"type":"text","text":"🖼️ 图片 1「aa.jpg」（当前模型不支持图片输入，已保存到工作目录 .dsh-images/aa11bb22.jpg）\n[Image: source: $path]"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals("", items[0].body)
        assertEquals(1, items[0].attachments.size)
        assertEquals("handoff:$path", items[0].attachments[0].attachmentId)
    }

    @Test
    fun stripsOnlyTheHandoffLinesWhenTheyShareAPartWithUserText() {
        // 实测插件把说明放在独立的 text part 里；这里钉住更坏的形状 —— 万一哪天
        // 和用户正文同处一条 part，也只能删那两行，不能把用户自己打的话一起吞掉。
        val path = "/root/.dsh-images/cc33.jpg"
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 32,
            data = """{"source":{"kind":"user"},"content":[
                {"type":"text","text":"你看这个\n[Image: source: $path]\n后面的字还在"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals("你看这个\n后面的字还在", items[0].body)
        assertEquals(1, items[0].attachments.size)
    }

    @Test
    fun deduplicatesTheSameHandoffPathInsideOneMessage() {
        // 同一张图被写两次（插件未来重发/重试都可能）不该在界面上显示两遍。
        val path = "/root/.dsh-images/dd44.jpg"
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 33,
            data = """{"source":{"kind":"user"},"content":[
                {"type":"text","text":"[Image: source: $path]"},
                {"type":"text","text":"[Image: source: $path]"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals(1, items[0].attachments.size)
    }

    @Test
    fun leavesInjectedContextAloneEvenWhenItMentionsPaths() {
        // 拿真机会话跑出来的坑：上下文注入的正文（记忆库、README 示例、上一轮输出）
        // 经常引用 `.dsh-images/` 路径，甚至就有 `[Image: source: …]` 这种示例文字。
        // 那些不是「用户发的图」，一个字都不能动 —— 否则正文被切碎、还会凭空
        // 多出几张读不到的假附件。
        val history = ChatHistory(listOf(entry(
            type = "user/message",
            seq = 34,
            data = """{"source":{"kind":"plugin"},"content":[
                {"type":"text","text":"示例：机器行写成 [Image: source: /root/.dsh-images/ff66.jpg] 这样"}]}""",
        )), hasMore = false)

        val items = history.displayItems()
        assertEquals(1, items.size)
        assertEquals(ChatItemKind.CONTEXT, items[0].kind)
        assertEquals("示例：机器行写成 [Image: source: /root/.dsh-images/ff66.jpg] 这样", items[0].body)
        assertEquals(0, items[0].attachments.size)
    }

    @Test
    fun readsAHandoffPathOnlyFromTheHandoffPrefix() {
        // ViewModel 只拿到附件 id，所以「走附件存储还是走工作区文件」全靠这一条判据。
        // 钉住两边：交接 id 解出路径、附件哈希 id 一律 null（不能让它去 resolvePath）。
        assertEquals("/root/.dsh-images/ee55.jpg", handoffPathOf("handoff:/root/.dsh-images/ee55.jpg"))
        assertNull(handoffPathOf("sha256:9759b45815c74eb5289a46074ad655a0"))
        assertNull(handoffPathOf("handoff:"))
        assertNull(handoffPathOf(""))
    }

    private fun entry(type: String, seq: Int, data: String, view: String? = null) = HistoryEntry(
        event = SessionEvent(type, seq, seq.toLong(), json.parseToJsonElement(data)),
        view = view?.let(json::parseToJsonElement),
    )
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * 用户真机报的 bug：模型限流（429）失败后，客户端一直转圈等。
     *
     * 链路原来是断的：内核把失败写在 `turn/end` 的 `reason.error` 里，
     * 插件把它整段丢掉，客户端收到的与「正常跑完」一模一样。
     * 所以这里钉死三件事：**失败要显示、正常收尾不显示、截断不算失败**。
     */
    @Test
    fun surfacesATerminalTurnFailureFromTheTurnEndEvent() {
        // 服务端口径原样进界面。code 是空串也照样显示 —— 免费额度限流就是空 code。
        val failure = ChatHistory(
            events = listOf(
                entry("user/message", 2, """{"content":[{"type":"text","text":"帮我查一下"}],"source":{"kind":"user"}}"""),
                entry(
                    "turn/end",
                    900,
                    """{"turn":3,"reason":{"kind":"error","error":{"code":"","message":"您已达到免费用户的 API 速率限制。升级 Token Plan 即可解锁更高限额，继续不间断使用 API。 (request id: 20261011054136674823028JzjiUYGm)","type":"AgnesAI_error"}}}""",
                ),
            ),
            hasMore = false,
        ).displayItems().single { it.kind == ChatItemKind.FAILURE }

        val error = requireNotNull(failure.failure)
        assertEquals(R.string.turn_failed_title, error.titleRes)
        assertTrue(error.message.contains("API 速率限制"))
        // 空串 code 必须**保留空串**：客户端要原样显示错误码那一格，当成缺失就少一列信息。
        assertEquals("", error.code)
        assertFalse(error.stopped)
    }

    /** 登出导致的停止在官方口径里与失败同级，但要打上 stopped 标记（不是模型故障）。 */
    @Test
    fun marksASignOutStopAsStoppedRatherThanAModelFault() {
        val history = ChatHistory(
            events = listOf(
                entry(
                    "turn/end",
                    901,
                    """{"turn":4,"reason":{"kind":"aborted","reason":{"kind":"hook","reason":"deepseek-account/signed-out"}}}""",
                ),
            ),
            hasMore = false,
        )

        val failure = history.displayItems().single()
        assertEquals(ChatItemKind.FAILURE, failure.kind)
        val error = requireNotNull(failure.failure)
        assertTrue(error.stopped)
        assertEquals(R.string.turn_stopped_title, error.titleRes)
        assertEquals("ACCOUNT_SIGNED_OUT", error.code)
    }

    /**
     * 四种**不该**报失败的收尾，一条都不能误报成红框。
     *
     * 误报比漏报更伤：用户没失败却看到「本轮运行失败」，第一反应是客户端坏了，
     * 而真正失败时他反而不信了。`max-tokens` 尤其要排除 —— 那是「回答被截断，
     * 已有输出保留」，内核渲染成黄色警示而非失败。
     */
    @Test
    fun neverReportsANormalOrTruncatedOrCancelledTurnAsAFailure() {
        val history = ChatHistory(
            events = listOf(
                entry("turn/end", 11, """{"turn":1,"reason":{"kind":"ok"}}"""),
                entry("turn/end", 12, """{"turn":2,"reason":{"kind":"completed"}}"""),
                entry("turn/end", 13, """{"turn":3,"reason":{"kind":"max-tokens"}}"""),
                entry("turn/end", 14, """{"turn":4,"reason":{"kind":"aborted","reason":{"kind":"user"}}}"""),
                entry("turn/end", 15, """{"turn":5,"reason":{"kind":"aborted","reason":{"kind":"hook","reason":"some-other-hook"}}}"""),
                // 老帧没有 reason 也不算失败。
                entry("turn/end", 16, """{"turn":6}"""),
            ),
            hasMore = false,
        )

        assertTrue(history.displayItems().none { it.kind == ChatItemKind.FAILURE })
    }

    /** 实时失败帧解出的 message/code/stopped 与历史帧必须一致。 */
    @Test
    fun readsATurnFailureFromTheLiveEventFrame() {
        val frame = WorkspaceEvent(
            "turn-7:failed",
            "chat.turn.failed",
            1234,
            json.parseToJsonElement(
                """{"sessionId":"session-1","eventType":"turn/end","seq":900,"failure":{"message":"您已达到免费用户的 API 速率限制。","code":""}}""",
            ),
        )

        val failure = frame.chatTurnFailure()!!
        assertEquals("您已达到免费用户的 API 速率限制。", failure.message)
        assertEquals("", failure.code)
        assertFalse(failure.stopped)

        // 事件类型对不上就安静返回 null（老插件只发 chat.turn.end，不能因此抽风）。
        val other = WorkspaceEvent("x", "chat.turn.end", 1, json.parseToJsonElement("""{"sessionId":"s"}"""))
        assertNull(other.chatTurnFailure())
    }

    /**
     * `reason.error` 不是对象的兜底。
     *
     * 内核偶尔抛一个字符串错误而不是 `{message, code}`。那时那句话本身就是全部信息，
     * 整条丢掉等于又回到「傻等」—— 而它恰恰可能是最难查的那类故障。
     */
    @Test
    fun keepsAStringShapedTurnError() {
        val history = ChatHistory(
            events = listOf(entry("turn/end", 21, """{"turn":1,"reason":{"kind":"error","error":"模型服务连接被重置"}}""")),
            hasMore = false,
        )

        val failure = history.displayItems().single()
        assertEquals(ChatItemKind.FAILURE, failure.kind)
        assertEquals("模型服务连接被重置", failure.failure?.message)
        assertNull(failure.failure?.code)
    }
}
