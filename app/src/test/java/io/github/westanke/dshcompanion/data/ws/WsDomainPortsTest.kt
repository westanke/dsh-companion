package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.QuestionAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 三域 WS 端口（[WsHitlPort] / [WsCommandPort] / [WsModelPort]）的帧与解码测试。
 *
 * 全程**手工构造回帧**、不经 MockWebServer —— 与 [WsRequestRouterTest] 同一个理由：
 * 真桥的回显行为是不对称的，假服务器会老实回显 requestId，反而测不出真实配对路径。
 * 这里模拟的是**不发 requestId 的老桥**（回帧里没有这个键），只有 kind + 判别字段可用。
 *
 * 重点钉住三件事：
 * 1. `approval-response` 的请求 type 与应答 kind **同名**，判别只能靠 `approvalId`；
 * 2. `question-response` 的 `accepted:false` 是业务失败，要翻成与 REST 一致的 `QUESTION_*` 码；
 * 3. `select-model` 的应答 kind 是 **`sent`**（与 `message` 共用），必须靠 `data.type` 互斥 ——
 *    这一条是 P3 最容易踩的坑。
 */
class WsDomainPortsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 让在途请求可以合法失败：用 supervisorScope，否则子协程失败会取消整个测试体。 */
    private fun case(body: suspend CoroutineScope.() -> Unit) =
        runBlocking<Unit> { supervisorScope { body() } }

    /** 假连接：记录出站帧，允许测试注入回帧 / 推送帧。 */
    private class Link {
        private val sent = mutableListOf<JsonObject>()
        private val pushed = mutableListOf<JsonObject>()

        val router: WsRequestRouter = WsRequestRouter(
            sender = { frame -> synchronized(sent) { sent += frame }; true },
            onEvent = { frame -> synchronized(pushed) { pushed += frame } },
        )

        fun sentTypes(): List<String> = synchronized(sent) { sent.map { WsFrames.typeOf(it)!! } }

        /**
         * 出站帧的载荷。
         *
         * 注意：**客户端帧是扁平的**（`{type, requestId, sessionId, ...}`，没有 `data` 外壳）——
         * `WsFrames.request` 把 payload 逐键铺在顶层；只有服务端帧才有 `data` 对象。
         */
        fun sentData(index: Int): JsonObject = synchronized(sent) { sent[index] }

        fun pushedKinds(): List<String> = synchronized(pushed) { pushed.mapNotNull { WsFrames.kindOf(it) } }

        suspend fun awaitSent(count: Int) {
            repeat(200) {
                if (sentTypes().size >= count) return
                delay(10)
            }
            assertEquals("等不到第 $count 个出站帧", count, sentTypes().size)
        }

        /** 注入一条**不带 requestId** 的回帧（老桥行为）。 */
        fun reply(kind: String, data: JsonObject) = router.onFrame(
            buildJsonObject {
                put("kind", kind)
                put("data", data)
            },
        )
    }

    // ---------------------------------------------------------------- HITL

    @Test
    fun `审批决定发 approval-response 并按 approvalId 配对`() = case {
        val link = Link()
        val port = WsHitlPort(link.router, json)

        val job = async { port.decideApproval("s-1", "ap-9", allowOnce = false) }
        link.awaitSent(1)
        assertEquals(WsClientFrame.APPROVAL_RESPONSE, link.sentTypes().single())
        assertEquals("rejected", link.sentData(0)["outcome"]?.jsonPrimitive?.content)

        // 先来一条**别的审批**的确认：请求 type 与应答 kind 同名，只能靠 approvalId 认。
        link.reply(
            WsServerFrame.APPROVAL_RESPONSE,
            buildJsonObject {
                put("sessionId", "s-1")
                put("approvalId", "ap-OTHER")
                put("accepted", true)
            },
        )
        assertEquals("别的审批的确认不许完成在途请求", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.APPROVAL_RESPONSE,
            buildJsonObject {
                put("sessionId", "s-1")
                put("approvalId", "ap-9")
                put("outcome", "rejected")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }

    @Test
    fun `approval-resolved 是推送帧 不许完成在途审批`() = case {
        val link = Link()
        val port = WsHitlPort(link.router, json)

        val job = async { port.decideApproval("s-1", "ap-9", allowOnce = true) }
        link.awaitSent(1)
        assertEquals("allowed-once", link.sentData(0)["outcome"]?.jsonPrimitive?.content)

        // 桥在 approval-response 之后**额外推**这条；它没有 requestId 且不是应答。
        link.reply(
            WsServerFrame.APPROVAL_RESOLVED,
            buildJsonObject {
                put("sessionId", "s-1")
                put("approvalId", "ap-9")
                put("outcome", "allowed-once")
            },
        )
        delay(50)
        assertEquals("推送帧不许完成在途请求", 1, link.router.pendingCount())
        assertTrue(
            "推送帧应走 onEvent 出口，而不是被当成应答",
            WsServerFrame.APPROVAL_RESOLVED in link.pushedKinds(),
        )

        link.reply(
            WsServerFrame.APPROVAL_RESPONSE,
            buildJsonObject {
                put("sessionId", "s-1")
                put("approvalId", "ap-9")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
    }

    @Test
    fun `提问答复发 question-answer 且答案字段名与 REST 一致`() = case {
        val link = Link()
        val port = WsHitlPort(link.router, json)

        val job = async {
            port.answerQuestions("s-1", "call-7", listOf(QuestionAnswer("q1", listOf("选项A"))))
        }
        link.awaitSent(1)
        assertEquals(WsClientFrame.QUESTION_ANSWER, link.sentTypes().single())
        val data = link.sentData(0)
        assertEquals("call-7", data["callId"]?.jsonPrimitive?.content)
        val answers = data["answers"] as JsonArray
        assertEquals("q1", (answers[0] as JsonObject)["id"]?.jsonPrimitive?.content)

        link.reply(
            WsServerFrame.QUESTION_RESPONSE,
            buildJsonObject {
                put("callId", "call-7")
                put("action", "answer")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
    }

    @Test
    fun `提问答复 accepted false 翻成与 REST 一致的业务码`() = case {
        val link = Link()
        val port = WsHitlPort(link.router, json)

        val job = async { port.answerQuestions("s-1", "call-7", listOf(QuestionAnswer("q1", emptyList()))) }
        link.awaitSent(1)
        // 这是**正常应答**里的业务失败，不是 error 帧（ws-hitl.ts:66-75）。
        link.reply(
            WsServerFrame.QUESTION_RESPONSE,
            buildJsonObject {
                put("callId", "call-7")
                put("action", "answer")
                put("accepted", false)
                put("reason", "not-pending")
            },
        )
        val failure = runCatching { withTimeout(2_000) { job.await() } }.exceptionOrNull()
        assertTrue("应当抛 DshApiException，实际 $failure", failure is DshApiException)
        assertEquals("QUESTION_NOT_PENDING", (failure as DshApiException).code)
        // 码值必须与 REST 侧一致，否则 describeAnswerFailure 的本地化文案会掉到兜底分支。
        assertEquals(409, failure.status)
    }

    @Test
    fun `提问答复的其余 reason 也各有对应的业务码`() = case {
        val mapping = mapOf(
            "reply-queued" to "QUESTION_REPLY_QUEUED",
            "bad-response" to "QUESTION_ANSWER_INVALID",
            "not-answerable" to "QUESTION_NOT_ANSWERABLE",
            "who-knows" to "QUESTION_ANSWER_REJECTED",
        )
        mapping.forEach { (reason, expected) ->
            val link = Link()
            val port = WsHitlPort(link.router, json)
            val job = async { port.answerQuestions("s-1", "call-$reason", listOf(QuestionAnswer("q1", emptyList()))) }
            link.awaitSent(1)
            link.reply(
                WsServerFrame.QUESTION_RESPONSE,
                buildJsonObject {
                    put("callId", "call-$reason")
                    put("accepted", false)
                    put("reason", reason)
                },
            )
            val failure = runCatching { withTimeout(2_000) { job.await() } }.exceptionOrNull()
            assertTrue("reason=$reason 应当抛 DshApiException", failure is DshApiException)
            assertEquals("reason=$reason 的码值不对", expected, (failure as DshApiException).code)
        }
    }

    // ---------------------------------------------------------------- 命令

    @Test
    fun `命令清单解码 name description 与可选 input hint`() = case {
        val link = Link()
        val port = WsCommandPort(link.router, json)

        val job = async { port.commands("s-1") }
        link.awaitSent(1)
        assertEquals(WsClientFrame.COMMANDS, link.sentTypes().single())
        assertEquals("s-1", link.sentData(0)["sessionId"]?.jsonPrimitive?.content)

        link.reply(
            WsServerFrame.COMMANDS,
            buildJsonObject {
                put("sessionId", "s-1")
                put(
                    "commands",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "/model")
                                put("description", "切换模型")
                                put("input", buildJsonObject { put("hint", "选择模型") })
                            },
                        )
                        add(
                            buildJsonObject {
                                put("name", "/permission")
                                put("description", "权限档位")
                            },
                        )
                    },
                )
            },
        )

        val commands = withTimeout(2_000) { job.await() }
        assertEquals(2, commands.size)
        assertEquals("/model", commands[0].name)
        assertEquals("切换模型", commands[0].description)
        assertEquals("input 是可选的：带了就解出 hint", "选择模型", commands[0].input?.hint)
        assertNull("没带 input 时必须为 null，而不是造一个空对象", commands[1].input)
    }

    @Test
    fun `执行命令的解码 result 字段`() = case {
        val link = Link()
        val port = WsCommandPort(link.router, json)

        val job = async { port.executeCommand("s-1", "/permission full") }
        link.awaitSent(1)
        assertEquals(WsClientFrame.COMMAND_EXECUTE, link.sentTypes().single())
        assertEquals("/permission full", link.sentData(0)["line"]?.jsonPrimitive?.content)

        link.reply(
            WsServerFrame.COMMAND_EXECUTED,
            buildJsonObject {
                // 多出来的 sessionId 不该影响解码（端口会先摘掉它）。
                put("sessionId", "s-1")
                put("commandId", "cmd-3")
                put(
                    "result",
                    buildJsonObject {
                        put("kind", "ok")
                        put("text", "权限已切换")
                        put("sourceEventSeq", 12)
                    },
                )
            },
        )

        val execution = withTimeout(2_000) { job.await() }
        assertEquals("cmd-3", execution.commandId)
        assertEquals("ok", execution.result.kind)
        assertEquals("权限已切换", execution.result.text)
        assertEquals(12, execution.result.sourceEventSeq)
    }

    // ---------------------------------------------------------------- 模型

    @Test
    fun `模型清单解码 current routable groups failures`() = case {
        val link = Link()
        val port = WsModelPort(link.router, json)

        val job = async { port.sessionModels("s-1") }
        link.awaitSent(1)
        assertEquals(WsClientFrame.MODELS, link.sentTypes().single())
        assertEquals("models 帧必须带 sessionId", "s-1", link.sentData(0)["sessionId"]?.jsonPrimitive?.content)

        link.reply(
            WsServerFrame.MODELS,
            buildJsonObject {
                put("sessionId", "s-1")
                put(
                    "current",
                    buildJsonObject {
                        put("provider", "anthropic")
                        put("model", "opus")
                        put("reasoningEffort", "high")
                    },
                )
                put("routable", true)
                put(
                    "groups",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "anthropic")
                                put("name", "Anthropic")
                                put(
                                    "models",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "opus")
                                                put("name", "Opus")
                                                put("contextWindow", 200_000)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "failures",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("provider", "openai")
                                put("message", "no api key")
                            },
                        )
                    },
                )
            },
        )

        val models = withTimeout(2_000) { job.await() }
        assertEquals("opus", models.current.model)
        assertEquals("high", models.current.reasoningEffort)
        assertTrue(models.routable)
        assertEquals("anthropic", models.groups.single().id)
        assertEquals("Opus", models.groups.single().models.single().name)
        assertEquals("openai", models.failures.single().provider)
    }

    @Test
    fun `切模型走 sent kind 且按 data type 判别`() = case {
        val link = Link()
        val port = WsModelPort(link.router, json)

        val job = async { port.selectModel("s-1", ModelSelection("anthropic", "sonnet", "low")) }
        link.awaitSent(1)
        assertEquals(WsClientFrame.SELECT_MODEL, link.sentTypes().single())
        val payload = link.sentData(0)
        assertEquals("sonnet", payload["model"]?.jsonPrimitive?.content)
        assertEquals("low", payload["reasoningEffort"]?.jsonPrimitive?.content)

        // 一条 message 的 sent 确认不该被切模型认领（同 kind、同 sessionId、同样 accepted）。
        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.MESSAGE)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        assertEquals("message 的确认不许完成切模型请求", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_MODEL)
                put("sessionId", "s-1")
                put("accepted", true)
                put(
                    "selected",
                    buildJsonObject {
                        put("provider", "anthropic")
                        put("model", "sonnet")
                        put("reasoningEffort", "low")
                    },
                )
            },
        )

        val selected = withTimeout(2_000) { job.await() }
        assertEquals("sonnet", selected.model)
        assertEquals("low", selected.reasoningEffort)
    }

    @Test
    fun `发消息的 sent 确认不会被迟到的 select-model 抢走`() = case {
        val link = Link()
        val messages = WsMessagePort(link.router, json) { "zh" }

        val job = async { messages.sendMessage("s-1", listOf(PromptPart.TextPart("你好")), steer = false) }
        link.awaitSent(1)
        assertEquals(WsClientFrame.MESSAGE, link.sentTypes().single())

        // 关键回归：这条与 message 的确认**长得一样**（sent + sessionId + accepted），
        // 只有 data.type 不同。P3 之前 message 的 matcher 会把它认成自己的确认。
        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_MODEL)
                put("sessionId", "s-1")
                put("accepted", true)
                put("selected", buildJsonObject { put("provider", "anthropic"); put("model", "opus") })
            },
        )
        delay(50)
        assertEquals("select-model 的确认不许完成发消息", 1, link.router.pendingCount())

        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.MESSAGE)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )
        withTimeout(2_000) { job.await() }
        assertEquals(0, link.router.pendingCount())
    }

    @Test
    fun `select-model 应答缺 selected 时抛 WS_BAD_FRAME 而不是崩溃`() = case {
        val link = Link()
        val port = WsModelPort(link.router, json)

        val job = async { port.selectModel("s-1", ModelSelection("anthropic", "sonnet")) }
        link.awaitSent(1)
        link.reply(
            WsServerFrame.SENT,
            buildJsonObject {
                put("type", WsClientFrame.SELECT_MODEL)
                put("sessionId", "s-1")
                put("accepted", true)
            },
        )

        val failure = runCatching { withTimeout(2_000) { job.await() } }.exceptionOrNull()
        assertTrue("应当抛 DshApiException，实际 $failure", failure is DshApiException)
        assertEquals("WS_BAD_FRAME", (failure as DshApiException).code)
    }

    @Test
    fun `commands 应答缺 commands 字段时回空表 而不是抛`() = case {
        val link = Link()
        val port = WsCommandPort(link.router, json)

        val job = async { port.commands("s-1") }
        link.awaitSent(1)
        link.reply(
            WsServerFrame.COMMANDS,
            buildJsonObject { put("sessionId", "s-1") },
        )
        assertTrue(withTimeout(2_000) { job.await() }.isEmpty())
    }

    @Test
    fun `models 应答字段不全时抛 WS_BAD_FRAME 让读操作回落`() = case {
        val link = Link()
        val port = WsModelPort(link.router, json)

        val job = async { port.sessionModels("s-1") }
        link.awaitSent(1)
        // 只给 sessionId，缺 current/routable/groups —— 解码必须失败，且失败要能被
        // AdaptiveModelPort 的 catch(DshApiException) 接住（SerializationException 接不住）。
        link.reply(WsServerFrame.MODELS, buildJsonObject { put("sessionId", "s-1") })

        val failure = runCatching { withTimeout(2_000) { job.await() } }.exceptionOrNull()
        assertTrue("应当抛 DshApiException，实际 $failure", failure is DshApiException)
        assertEquals("WS_BAD_FRAME", (failure as DshApiException).code)
        assertTrue("JsonPrimitive 导入被使用", link.sentTypes().isNotEmpty())
    }
}
