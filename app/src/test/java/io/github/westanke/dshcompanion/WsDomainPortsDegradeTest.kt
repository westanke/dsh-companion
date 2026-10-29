package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CommandExecution
import io.github.westanke.dshcompanion.data.CommandResult
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.ModelProviderGroup
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.ModelView
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.SessionModels
import io.github.westanke.dshcompanion.data.ws.ChannelState
import io.github.westanke.dshcompanion.data.ws.CommandPort
import io.github.westanke.dshcompanion.data.ws.HitlPort
import io.github.westanke.dshcompanion.data.ws.ModelPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 退化等价测试：老桥（upgrade 404 → [ChannelState.RestOnly]）下三域端口的行为
 * 必须与改造前**逐条一致** —— 也就是每个动作都原样落到 REST 实现，参数不增不减，
 * 且完全不碰 WS 实现。
 *
 * 这是这次改造的「不改行为」判据：P3 只是换通道，不产生任何新功能，所以退化路径
 * 一旦多调一次 WS、或丢掉一个参数，就是回归。
 *
 * 另外钉住读/写两条回落规则的不对称：
 * - 读操作：任何 WS 传输失败（含超时）都可以回落 REST；
 * - 写操作：只有帧**没发出去**才回落 —— 超时可能已经产生了副作用，重发就是重复副作用。
 */
class WsDomainPortsDegradeTest {

    private val models = SessionModels(
        current = ModelSelection("anthropic", "opus"),
        routable = true,
        groups = listOf(ModelProviderGroup("anthropic", "Anthropic", listOf(ModelView("opus", "Opus")))),
    )
    private val commands = listOf(CommandDescriptor("/model", "切换模型"))
    private val execution = CommandExecution("cmd-1", CommandResult("ok"))

    /** 记录调用的假端口。 */
    private class Recorder(
        private val label: String,
        private val failure: DshApiException? = null,
    ) : HitlPort, CommandPort, ModelPort {
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            calls += call
            failure?.let { throw it }
        }

        override suspend fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean) =
            record("$label.decideApproval($sessionId,$approvalId,$allowOnce).also{}")

        override suspend fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>) =
            record("$label.answerQuestions($sessionId,$callId,${answers.joinToString("|") { it.id }})")

        override suspend fun commands(sessionId: String): List<CommandDescriptor> {
            record("$label.commands($sessionId)")
            return emptyList()
        }

        override suspend fun executeCommand(sessionId: String, line: String): CommandExecution {
            record("$label.executeCommand($sessionId,$line)")
            return CommandExecution("cmd-1", CommandResult("ok"))
        }

        override suspend fun sessionModels(sessionId: String): SessionModels {
            record("$label.sessionModels($sessionId)")
            return SessionModels(ModelSelection("anthropic", "opus"), true, emptyList())
        }

        override suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection {
            record("$label.selectModel($sessionId,${selection.provider}/${selection.model})")
            return selection
        }
    }

    private fun hitl(ws: HitlPort, rest: HitlPort, state: () -> ChannelState) = AdaptiveHitlPort(ws, rest, state)
    private fun command(ws: CommandPort, rest: CommandPort, state: () -> ChannelState) =
        AdaptiveCommandPort(ws, rest, state)
    private fun model(ws: ModelPort, rest: ModelPort, state: () -> ChannelState) = AdaptiveModelPort(ws, rest, state)

    // ------------------------------------------------------- 退化：一次都别碰 WS

    @Test
    fun `RestOnly 下 HITL 两个写动作全部走 REST 且参数不变`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val port = hitl(ws, rest) { ChannelState.RestOnly }

        port.decideApproval("s-1", "ap-9", allowOnce = true)
        port.answerQuestions("s-1", "call-7", listOf(QuestionAnswer("q1", listOf("A"), "自定义")))

        assertEquals(
            listOf(
                "rest.decideApproval(s-1,ap-9,true).also{}",
                "rest.answerQuestions(s-1,call-7,q1)",
            ),
            rest.calls,
        )
        assertTrue("RestOnly 下不许碰 WS 实现，实际 ${ws.calls}", ws.calls.isEmpty())
    }

    @Test
    fun `RestOnly 下命令域全部走 REST`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val port = command(ws, rest) { ChannelState.RestOnly }

        port.commands("s-1")
        port.executeCommand("s-1", "/permission full")

        assertEquals(listOf("rest.commands(s-1)", "rest.executeCommand(s-1,/permission full)"), rest.calls)
        assertTrue(ws.calls.isEmpty())
    }

    @Test
    fun `RestOnly 下模型域全部走 REST`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val port = model(ws, rest) { ChannelState.RestOnly }

        port.sessionModels("s-1")
        port.selectModel("s-1", ModelSelection("anthropic", "sonnet", "low"))

        assertEquals(
            listOf("rest.sessionModels(s-1)", "rest.selectModel(s-1,anthropic/sonnet)"),
            rest.calls,
        )
        assertTrue(ws.calls.isEmpty())
    }

    @Test
    fun `WsDown 与未建传输同样走 REST`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")

        command(ws, rest) { ChannelState.WsDown("socket closed") }.commands("s-1")
        model(ws, rest) { ChannelState.WsDown("socket closed") }.sessionModels("s-1")
        hitl(ws, rest) { ChannelState.WsDown("socket closed") }.decideApproval("s-1", "ap-1", false)

        assertEquals(
            listOf("rest.commands(s-1)", "rest.sessionModels(s-1)", "rest.decideApproval(s-1,ap-1,false).also{}"),
            rest.calls,
        )
        assertTrue("WsDown 没有可用通道，不该发帧", ws.calls.isEmpty())
    }

    // ------------------------------------------------------- 正常：WsReady 走 WS

    @Test
    fun `WsReady 且 WS 成功时 REST 一次都不调`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")
        val ready : () -> ChannelState = { ChannelState.WsReady }

        hitl(ws, rest, ready).decideApproval("s-1", "ap-9", true)
        command(ws, rest, ready).commands("s-1")
        model(ws, rest, ready).sessionModels("s-1")
        model(ws, rest, ready).selectModel("s-1", ModelSelection("anthropic", "opus"))

        assertEquals(4, ws.calls.size)
        assertTrue("WS 成功就不该回落，实际 ${rest.calls}", rest.calls.isEmpty())
    }

    @Test
    fun `WsDegraded 仍算 usesWs 继续走 WS`() = runBlocking {
        val ws = Recorder("ws")
        val rest = Recorder("rest")

        command(ws, rest) { ChannelState.WsDegraded("capability missing") }.commands("s-1")

        assertEquals(listOf("ws.commands(s-1)"), ws.calls)
        assertTrue(rest.calls.isEmpty())
    }

    // ------------------------------------------------------- 读/写回落的不对称

    @Test
    fun `读操作遇 WS_TIMEOUT 回落 REST`() = runBlocking {
        val ws = Recorder("ws", DshApiException(408, "WS_TIMEOUT", "ws timeout"))
        val rest = Recorder("rest")
        val ready: () -> ChannelState = { ChannelState.WsReady }

        command(ws, rest, ready).commands("s-1")
        model(ws, rest, ready).sessionModels("s-1")

        assertEquals(listOf("rest.commands(s-1)", "rest.sessionModels(s-1)"), rest.calls)
    }

    @Test
    fun `写操作遇 WS_TIMEOUT 不回落 因为不知道服务端做没做`() = runBlocking {
        val timeout = DshApiException(408, "WS_TIMEOUT", "ws timeout")
        val ready: () -> ChannelState = { ChannelState.WsReady }

        val hitlFailure = runCatching {
            hitl(Recorder("ws", timeout), Recorder("rest"), ready).decideApproval("s-1", "ap-9", true)
        }.exceptionOrNull()
        assertEquals("WS_TIMEOUT", (hitlFailure as DshApiException).code)

        val execFailure = runCatching {
            command(Recorder("ws", timeout), Recorder("rest"), ready).executeCommand("s-1", "/clear")
        }.exceptionOrNull()
        assertEquals("WS_TIMEOUT", (execFailure as DshApiException).code)

        val selectFailure = runCatching {
            model(Recorder("ws", timeout), Recorder("rest"), ready).selectModel("s-1", ModelSelection("a", "b"))
        }.exceptionOrNull()
        assertEquals("WS_TIMEOUT", (selectFailure as DshApiException).code)

        val rest = Recorder("rest")
        runCatching { command(Recorder("ws", timeout), rest, ready).executeCommand("s-1", "/clear") }
        assertTrue("写操作超时后绝不能回落（会重复副作用），实际 ${rest.calls}", rest.calls.isEmpty())
    }

    @Test
    fun `写操作帧没发出去时回落 REST`() = runBlocking {
        val sendFailed = DshApiException(0, "WS_SEND_FAILED", "send failed")
        val ready: () -> ChannelState = { ChannelState.WsReady }
        val rest = Recorder("rest")

        hitl(Recorder("ws", sendFailed), rest, ready).decideApproval("s-1", "ap-9", false)
        command(Recorder("ws", sendFailed), rest, ready).executeCommand("s-1", "/permission full")
        model(Recorder("ws", sendFailed), rest, ready).selectModel("s-1", ModelSelection("anthropic", "opus"))

        assertEquals(
            listOf(
                "rest.decideApproval(s-1,ap-9,false).also{}",
                "rest.executeCommand(s-1,/permission full)",
                "rest.selectModel(s-1,anthropic/opus)",
            ),
            rest.calls,
        )
    }

    @Test
    fun `WS_NOT_READY 同样算帧没发出去`() = runBlocking {
        val notReady = DshApiException(0, "WS_NOT_READY", "not ready")
        val rest = Recorder("rest")

        hitl(Recorder("ws", notReady), rest) { ChannelState.WsReady }.decideApproval("s-1", "ap-1", true)

        assertEquals(listOf("rest.decideApproval(s-1,ap-1,true).also{}"), rest.calls)
    }

    @Test
    fun `提问答复的业务失败不许回落 REST`() = runBlocking {
        // 桥回 accepted:false（QUESTION_*）—— 这是业务结果，不是传输失败。
        val rejected = DshApiException(409, "QUESTION_NOT_PENDING", "提问已不在等待中")
        val rest = Recorder("rest")
        val ready: () -> ChannelState = { ChannelState.WsReady }

        val failure = runCatching {
            hitl(Recorder("ws", rejected), rest, ready)
                .answerQuestions("s-1", "call-7", listOf(QuestionAnswer("q1", emptyList())))
        }.exceptionOrNull()

        assertEquals("QUESTION_NOT_PENDING", (failure as DshApiException).code)
        assertTrue(
            "accepted:false 是业务失败，重发到 REST 只会再被拒一次；实际 ${rest.calls}",
            rest.calls.isEmpty(),
        )
    }

    @Test
    fun `非传输类的 WS 失败不回落 直接抛给调用方`() = runBlocking {
        val business = DshApiException(400, "bad-request", "命令不支持选项")
        val ready: () -> ChannelState = { ChannelState.WsReady }
        val rest = Recorder("rest")

        val failure = runCatching {
            model(Recorder("ws", business), rest, ready).sessionModels("s-1")
        }.exceptionOrNull()
        assertEquals("bad-request", (failure as DshApiException).code)

        val readFailure = runCatching {
            command(Recorder("ws", business), rest, ready).commands("s-1")
        }.exceptionOrNull()
        assertEquals("bad-request", (readFailure as DshApiException).code)

        assertTrue("业务错误回落等于掩盖问题，实际 ${rest.calls}", rest.calls.isEmpty())
    }
}
