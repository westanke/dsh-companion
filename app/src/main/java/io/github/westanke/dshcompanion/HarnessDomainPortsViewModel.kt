package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CommandExecution
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.SessionModels
import io.github.westanke.dshcompanion.data.ws.ChannelState
import io.github.westanke.dshcompanion.data.ws.CommandPort
import io.github.westanke.dshcompanion.data.ws.HitlPort
import io.github.westanke.dshcompanion.data.ws.ModelPort
import io.github.westanke.dshcompanion.data.ws.RestCommandPort
import io.github.westanke.dshcompanion.data.ws.RestHitlPort
import io.github.westanke.dshcompanion.data.ws.RestModelPort
import io.github.westanke.dshcompanion.data.ws.WsCommandPort
import io.github.westanke.dshcompanion.data.ws.WsHitlPort
import io.github.westanke.dshcompanion.data.ws.WsModelPort
import io.github.westanke.dshcompanion.data.ws.WsRequestRouter

/**
 * P3 三域（HITL / 命令 / 模型）的**装配层**：把 WS 实现与 REST 实现按 [ChannelState] 串起来。
 *
 * 与 `HarnessWsPortsViewModel.kt` 的 `PortSet` / `AdaptiveSessionPort` 同构，拆成独立文件
 * 只是因为那两个文件各自贴着 800 行预算（`ArchitectureGuardTest` 机检）。
 *
 * ## 回落判据在这里，不在端口实现里
 *
 * 读/写两条规则（见 `WsDomainPorts.kt` 的 KDoc）与 P2 **共用同一份判据函数**
 * （`isFrameNeverSent` / `isWsTransportFailure`），不在这里另写一份 —— 两份判据迟早会漂，
 * 而漂的后果是「写操作在超时后偷偷重试」，那正是这套设计最想避免的事。
 *
 * ## 退化路径
 *
 * `!usesWs`（RestOnly / WsDown / 还没建传输）时**直接**调 REST 实现，与改造前调的完全
 * 是同一个 `DshClient` 方法 —— 老桥（upgrade 404）下这两条路径逐字节一致。
 */
internal class DomainPorts(
    val hitl: HitlPort,
    val command: CommandPort,
    val model: ModelPort,
)

/**
 * 建好三套自适应端口。
 *
 * 只由 `connectWsTransport` 调用：那时 router 与 transport 都已就位，`state` 闭包也指向
 * 活的传输状态，端口不必自己去读 `wsTransport` 字段。
 */
internal fun HarnessViewModel.buildDomainPorts(
    router: WsRequestRouter,
    api: DshClient,
    state: () -> ChannelState,
): DomainPorts = DomainPorts(
    hitl = AdaptiveHitlPort(WsHitlPort(router, api.json), RestHitlPort { client ?: api }, state),
    command = AdaptiveCommandPort(WsCommandPort(router, api.json), RestCommandPort { client ?: api }, state),
    model = AdaptiveModelPort(WsModelPort(router, api.json), RestModelPort { client ?: api }, state),
)

/** 取 HITL 端口。传输没建（冷启动、未连接、无令牌）时直接给 REST 实现。 */
internal fun HarnessViewModel.hitlPort(): HitlPort =
    domainPorts?.hitl ?: RestHitlPort { requireApi() }

/** 取命令端口。理由同 [hitlPort]。 */
internal fun HarnessViewModel.commandPort(): CommandPort =
    domainPorts?.command ?: RestCommandPort { requireApi() }

/** 取模型端口。理由同 [hitlPort]。 */
internal fun HarnessViewModel.modelPort(): ModelPort =
    domainPorts?.model ?: RestModelPort { requireApi() }

/** 按 [ChannelState] 选通道的 [HitlPort]。两个动作都是**写**，只在帧没发出去时回落。 */
internal class AdaptiveHitlPort(
    private val ws: HitlPort,
    private val rest: HitlPort,
    private val state: () -> ChannelState,
) : HitlPort {

    override suspend fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean) {
        if (!state().usesWs) return rest.decideApproval(sessionId, approvalId, allowOnce)
        try {
            ws.decideApproval(sessionId, approvalId, allowOnce)
        } catch (failure: DshApiException) {
            // 审批绝不幂等：超时后重发可能让同一次工具调用被「允许」两次。
            if (isFrameNeverSent(failure)) rest.decideApproval(sessionId, approvalId, allowOnce) else throw failure
        }
    }

    override suspend fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>) {
        if (!state().usesWs) return rest.answerQuestions(sessionId, callId, answers)
        try {
            ws.answerQuestions(sessionId, callId, answers)
        } catch (failure: DshApiException) {
            // 注意：桥的 accepted:false 也是 DshApiException（QUESTION_* 码），但它不是传输失败，
            // 所以 isFrameNeverSent 为 false → 原样抛给调用方的 describeAnswerFailure。
            if (isFrameNeverSent(failure)) rest.answerQuestions(sessionId, callId, answers) else throw failure
        }
    }
}

/** 按 [ChannelState] 选通道的 [CommandPort]：`commands` 读、`executeCommand` 写。 */
internal class AdaptiveCommandPort(
    private val ws: CommandPort,
    private val rest: CommandPort,
    private val state: () -> ChannelState,
) : CommandPort {

    override suspend fun commands(sessionId: String): List<CommandDescriptor> {
        if (!state().usesWs) return rest.commands(sessionId)
        return try {
            ws.commands(sessionId)
        } catch (failure: DshApiException) {
            if (isWsTransportFailure(failure)) rest.commands(sessionId) else throw failure
        }
    }

    override suspend fun executeCommand(sessionId: String, line: String): CommandExecution {
        if (!state().usesWs) return rest.executeCommand(sessionId, line)
        return try {
            ws.executeCommand(sessionId, line)
        } catch (failure: DshApiException) {
            // 斜杠命令未必幂等（`/permission` 幂等，别人的自定义命令不保证），按写操作处理。
            if (isFrameNeverSent(failure)) rest.executeCommand(sessionId, line) else throw failure
        }
    }
}

/** 按 [ChannelState] 选通道的 [ModelPort]：`sessionModels` 读、`selectModel` 写。 */
internal class AdaptiveModelPort(
    private val ws: ModelPort,
    private val rest: ModelPort,
    private val state: () -> ChannelState,
) : ModelPort {

    override suspend fun sessionModels(sessionId: String): SessionModels {
        if (!state().usesWs) return rest.sessionModels(sessionId)
        return try {
            ws.sessionModels(sessionId)
        } catch (failure: DshApiException) {
            if (isWsTransportFailure(failure)) rest.sessionModels(sessionId) else throw failure
        }
    }

    override suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection {
        if (!state().usesWs) return rest.selectModel(sessionId, selection)
        return try {
            ws.selectModel(sessionId, selection)
        } catch (failure: DshApiException) {
            if (isFrameNeverSent(failure)) rest.selectModel(sessionId, selection) else throw failure
        }
    }
}
