package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CommandExecution
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.SessionModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * P3 三域（HITL / 命令 / 模型）的**能力面**接口与两套实现。
 *
 * 与 [WsPorts.kt] 的 `SessionPort` / `MessagePort` / `EventPort` 同构：每个域一个接口，
 * [WsHitlPort] 等走 `/ws/mobile`，[RestHitlPort] 等走 `DshClient` 的 REST 方法，
 * 装配层（`HarnessDomainPortsViewModel.kt`）按 [ChannelState] 选一个。
 *
 * ## 为什么不和 [SessionPort] 挤在同一个文件
 *
 * `WsPorts.kt` 341 行、`HarnessWsPortsViewModel.kt` 620 行，都贴着 800 行预算。
 * 三域加起来约 400 行，硬塞进去只会让两处同时超预算；按域拆文件是本项目既有惯例
 * （`HarnessSchedulesViewModel.kt` / `HarnessSpeechViewModel.kt` 都是这么拆的）。
 *
 * ## 退化判据（与 P2 同一条，不许改）
 *
 * - **读**操作（`commands` / `sessionModels`）：任何 WS 传输失败都回落 REST。
 * - **写**操作（`decideApproval` / `answerQuestions` / `executeCommand` / `selectModel`）：
 *   只有**帧没发出去**（`WS_NOT_READY` / `WS_SEND_FAILED`）才回落 —— 超时意味着不知道
 *   服务端做没做，重试会重复副作用（重复批准一次工具调用、重复切一次模型）。
 *   判据实体在 `HarnessWsPortsViewModel.kt` 末尾的 `isFrameNeverSent` / `isWsTransportFailure`。
 *
 * ## 哪些刻意没接（省得后来人当成遗漏）
 *
 * - `question-cancel`：App 没有「撤回提问」入口，REST 侧也没有对等端点。
 * - `command-options` / `command-select`：`/model`、`/permission` 的二级菜单是本地渲染的，
 *   改成桥的 opaque `optionId` 要连 UI 状态机一起改，收益只是省一次 REST。
 * - `permission` 帧：与 `applyPermissionPreset` 的 REST 路径等价，重复。
 * - `default-model` / `save-default-model`：桥明确回 `NOT_IMPLEMENTED`。
 * - 拉待审批/待回答清单：桥的 WS 侧**没有**这两个帧（`ws-hitl.ts` 只有 3 个写帧），
 *   所以 `refreshPendingApprovals` / `refreshPendingQuestions` 保持 REST，这是已知边界。
 */
internal interface HitlPort {
    /**
     * 审批决定。WS 帧 `approval-response`，`outcome` 只能是 `allowed-once` / `rejected`。
     *
     * 成功后桥还会额外推一条 `approval-resolved` —— 那是推送帧，不要在这里等它。
     */
    suspend fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean)

    /**
     * 回答内核提问。WS 帧 `question-answer`。
     *
     * 桥的应答可能是 `accepted:false` + `reason`（`not-pending` / `reply-queued` /
     * `bad-response` / `not-answerable`）—— 那是**业务失败但传输成功**，所以这里翻译成
     * [DshApiException]，让调用方既有的 `describeAnswerFailure` 分支继续生效
     * （code 取值与 REST 侧逐字一致，本地化文案不用改）。
     */
    suspend fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>)
}

internal interface CommandPort {
    /** 命令清单。WS 帧 `commands`；应答项只有 `name` / `description` / 可选 `input.hint`。 */
    suspend fun commands(sessionId: String): List<CommandDescriptor>

    /** 执行一条斜杠命令。WS 帧 `command-execute`。 */
    suspend fun executeCommand(sessionId: String, line: String): CommandExecution
}

internal interface ModelPort {
    /** 会话级可选模型与路由。WS 帧 `models`（**必须带 sessionId**，桥缺了直接回 error）。 */
    suspend fun sessionModels(sessionId: String): SessionModels

    /** 切会话模型。WS 帧 `select-model`，**应答 kind 是 `sent`**。 */
    suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection
}

// ───────────────────────── WS 实现 ─────────────────────────

/**
 * `HitlPort` 的 WS 实现。
 *
 * 两条帧都**不带 requestId 回显**（`ws-hitl.ts` 是 fire-and-forget 的 `sendFrame`），
 * 所以全部经 [WsRequestRouter]：带 requestId 发出去，桥不回显就靠 kind + matcher 配对。
 * 配对字段是桥写在 `data` 里的：`approval-response` 用 `sessionId` + `approvalId`，
 * `question-response` 只有 `callId`（它不回声 sessionId，所以只能按 callId 认）。
 */
internal class WsHitlPort(
    private val router: WsRequestRouter,
    private val json: Json,
) : HitlPort {

    override suspend fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean) {
        router.request(
            requestType = WsClientFrame.APPROVAL_RESPONSE,
            expectedKind = WsServerFrame.APPROVAL_RESPONSE,
            payload = JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive(sessionId),
                    "approvalId" to JsonPrimitive(approvalId),
                    "outcome" to JsonPrimitive(if (allowOnce) "allowed-once" else "rejected"),
                ),
            ),
            // kind 与请求 type 同名，且随后还会来一条 approval-resolved 推送帧 ——
            // 必须把 approvalId 也纳入判别，否则可能认错成另一个审批的确认。
            matcher = { frame ->
                val data = WsFrames.dataOf(frame)
                data.stringOrNull("sessionId") == sessionId && data.stringOrNull("approvalId") == approvalId
            },
        )
    }

    override suspend fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>) {
        val frame = router.request(
            requestType = WsClientFrame.QUESTION_ANSWER,
            expectedKind = WsServerFrame.QUESTION_RESPONSE,
            payload = buildMap {
                put("sessionId", JsonPrimitive(sessionId))
                put("callId", JsonPrimitive(callId))
                put("answers", json.encodeToJsonElement(ListSerializer(QuestionAnswer.serializer()), answers))
            }.let(::JsonObject),
            matcher = { WsFrames.dataOf(it).stringOrNull("callId") == callId },
        )
        val data = WsFrames.dataOf(frame)
        // 桥只在失败时带 accepted:false；缺字段按成功处理（兼容只回 {callId} 的更老实现）。
        val accepted = (data["accepted"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (accepted) return
        val reason = data.stringOrNull("reason")
        throw DshApiException(409, questionRejectionCode(reason), questionRejectionMessage(reason))
    }
}

/** `CommandPort` 的 WS 实现。`commands` 是读（失败可回落），`command-execute` 是写（只在帧没发出去时回落）。 */
internal class WsCommandPort(
    private val router: WsRequestRouter,
    private val json: Json,
) : CommandPort {

    override suspend fun commands(sessionId: String): List<CommandDescriptor> {
        val frame = router.request(
            requestType = WsClientFrame.COMMANDS,
            expectedKind = WsServerFrame.COMMANDS,
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
            matcher = { WsFrames.dataOf(it).stringOrNull("sessionId") == sessionId },
        )
        val items = WsFrames.dataOf(frame)["commands"] ?: return emptyList()
        return decode("commands") { json.decodeFromJsonElement(ListSerializer(CommandDescriptor.serializer()), items) }
    }

    override suspend fun executeCommand(sessionId: String, line: String): CommandExecution {
        val frame = router.request(
            requestType = WsClientFrame.COMMAND_EXECUTE,
            expectedKind = WsServerFrame.COMMAND_EXECUTED,
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId), "line" to JsonPrimitive(line))),
            matcher = { WsFrames.dataOf(it).stringOrNull("sessionId") == sessionId },
        )
        // 应答 data = {sessionId, commandId, result}；把多出来的 sessionId 摘掉再解，
        // 免得依赖传入 Json 的 ignoreUnknownKeys 配置（那是 DshClient 的实现细节）。
        val data = JsonObject(WsFrames.dataOf(frame) - "sessionId")
        return decode(WsClientFrame.COMMAND_EXECUTE) {
            json.decodeFromJsonElement(CommandExecution.serializer(), data)
        }
    }
}

/** `ModelPort` 的 WS 实现。 */
internal class WsModelPort(
    private val router: WsRequestRouter,
    private val json: Json,
) : ModelPort {

    override suspend fun sessionModels(sessionId: String): SessionModels {
        val frame = router.request(
            requestType = WsClientFrame.MODELS,
            expectedKind = WsServerFrame.MODELS,
            // sessionId 是**必需**的：桥缺了这个字段直接回 MISSING_PARAMS 并让你走 REST
            // （ws-models.ts:53），而全局目录本来也只有 REST 一条路。
            payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
            matcher = { WsFrames.dataOf(it).stringOrNull("sessionId") == sessionId },
        )
        val data = JsonObject(WsFrames.dataOf(frame) - "sessionId")
        return decode(WsClientFrame.MODELS) {
            json.decodeFromJsonElement(SessionModels.serializer(), data)
        }
    }

    override suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection {
        val frame = router.request(
            requestType = WsClientFrame.SELECT_MODEL,
            expectedKind = WsServerFrame.SENT,
            payload = buildMap {
                put("sessionId", JsonPrimitive(sessionId))
                put("provider", JsonPrimitive(selection.provider))
                put("model", JsonPrimitive(selection.model))
                selection.reasoningEffort?.let { put("reasoningEffort", JsonPrimitive(it)) }
            }.let(::JsonObject),
            // ⚠ 这一行是 P3 最容易踩的坑：select-model 的应答 kind 是 `sent`，与 message 同 kind，
            // 且 data 里同样有 sessionId + accepted。只靠 P2 那两条判据会互抢 ——
            // 所以这里必须加上 `type == "select-model"`，而 [WsMessagePort] 那边同时排除它。
            matcher = { frame ->
                val data = WsFrames.dataOf(frame)
                data.stringOrNull("type") == WsClientFrame.SELECT_MODEL &&
                    data.stringOrNull("sessionId") == sessionId
            },
        )
        val data = WsFrames.dataOf(frame)
        val selected = data["selected"] as? JsonObject
            ?: throw DshApiException(0, "WS_BAD_FRAME", "select-model 应答缺少 data.selected")
        return decode(WsClientFrame.SELECT_MODEL) {
            json.decodeFromJsonElement(ModelSelection.serializer(), selected)
        }
    }
}

// ───────────────────────── REST 实现 ─────────────────────────

/**
 * `HitlPort` 的 REST 实现。
 *
 * 刻意**没有**「拉待审批/待回答清单」的方法：不是漏了，而是桥的 WS 侧没有对应的帧，
 * 所以那两个刷新动作永远走 REST，不进端口（`HarnessInteractionsViewModel.kt` 里的注释同理）。
 */
internal class RestHitlPort(private val api: () -> DshClient) : HitlPort {

    override suspend fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean) {
        withContext(Dispatchers.IO) { api().decideApproval(sessionId, approvalId, allowOnce) }
    }

    override suspend fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>) {
        withContext(Dispatchers.IO) { api().answerQuestions(sessionId, callId, answers) }
    }
}

internal class RestCommandPort(private val api: () -> DshClient) : CommandPort {

    override suspend fun commands(sessionId: String): List<CommandDescriptor> =
        withContext(Dispatchers.IO) { api().sessionCommands(sessionId) }

    override suspend fun executeCommand(sessionId: String, line: String): CommandExecution =
        withContext(Dispatchers.IO) { api().executeCommand(sessionId, line) }
}

internal class RestModelPort(private val api: () -> DshClient) : ModelPort {

    override suspend fun sessionModels(sessionId: String): SessionModels =
        withContext(Dispatchers.IO) { api().sessionModels(sessionId) }

    override suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection =
        withContext(Dispatchers.IO) { api().selectModel(sessionId, selection) }
}

// ───────────────────────── 私有工具 ─────────────────────────

/** `data.<key>` 读成字符串；缺失/非字符串返回 null。 */
private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 把应答解成模型对象；形状不对统一抛 `WS_BAD_FRAME`。
 *
 * 为什么必须包一层：`SerializationException` 不是 [DshApiException]，它会直接穿过
 * 自适应层的 `catch (failure: DshApiException)` —— 于是**读**操作本该回落 REST 的地方
 * 会变成一个没人捕获的崩溃。包装之后，读操作按传输失败回落、写操作照旧失败（不重试）。
 */
private inline fun <T> decode(
    requestType: String,
    block: () -> T,
): T = try {
    block()
} catch (failure: SerializationException) {
    throw DshApiException(0, "WS_BAD_FRAME", "WS 应答字段不全（$requestType）：${failure.message}")
}

/**
 * `question-response` 的业务失败 reason → 与 REST 侧逐字一致的错误码。
 *
 * 取值见桥 `questionReason()`（`ws-hitl.ts:138-144`）。码值必须与 REST 分支一致，
 * 否则 `describeAnswerFailure` 的本地化文案会掉到兜底分支（用户看到英文原文）。
 */
private fun questionRejectionCode(reason: String?): String = when (reason) {
    "not-pending" -> "QUESTION_NOT_PENDING"
    "reply-queued" -> "QUESTION_REPLY_QUEUED"
    "bad-response" -> "QUESTION_ANSWER_INVALID"
    "not-answerable" -> "QUESTION_NOT_ANSWERABLE"
    else -> "QUESTION_ANSWER_REJECTED"
}

private fun questionRejectionMessage(reason: String?): String =
    "服务端拒绝这次答复（reason=${reason ?: "unknown"}）"
