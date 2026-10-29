package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 目标（goal）写操作的**能力面**：编辑、暂停、恢复、清除。
 *
 * ## 为什么只有 WS 一套实现（没有 REST 兜底）
 *
 * 桥的 REST 路由表 `src/host/router.ts` 里 `goal` **零命中** —— 目标的读端（投影
 * `values.goal`）跟着 `GET /chat/sessions/{id}/history` 一起下来，写端却只有
 * `/ws/mobile` 的四个写帧（`src/host/ws-goals.ts` 的 `GoalsHandler`）。所以本端口是
 * 整个 WS 改造里**唯一的能力增量**：P1-P3 是换通道，P4 是「以前做不到，现在能做」。
 *
 * 因为不存在 REST 兜底，通道不可用时**不能**像 [WsSessionPort] 那样退回 REST，
 * 只能抛结构化错误让 UI 说清原因。这就是 [UnsupportedGoalPort] 与
 * [GoalError.CHANNEL_NOT_READY] 存在的理由：宁可弹一句实话，也不能点了没反应。
 *
 * ## 四帧的 wire 形状（请求 type == 应答 kind，同名）
 *
 * | 请求 | 必填 |
 * |---|---|
 * | `goal-edit` | `sessionId` + `ref:{id,revision}` + (`objective` 或 `maxGoalRounds` 至少一个) |
 * | `goal-pause` / `goal-resume` / `goal-clear` | `sessionId` + `ref:{id,revision}` |
 *
 * `ref.revision` 必须是**正整数**（桥侧 `readGoalRef`，`ws-goals.ts:255-262`）：
 * 内核拿它做 compare-and-set 乐观锁，缺 id 或缺正整数 revision 会被桥直接判
 * `bad-request`。所以本端口**先本地校验再发帧** —— 明知会被拒的帧不应该占用带宽，
 * 更不该让用户以为"点了在转圈"。
 *
 * ## 应答只带 ref，不带完整目标体
 *
 * `goal-edit` / `goal-pause` / `goal-resume` 的 data 是 `{sessionId, ref?}`，
 * `goal-clear` 是 `{sessionId, cleared:true}`。**没有新的目标正文**，所以调用方
 * 成功后必须重新读一次投影（`history` 的 `values.goal` 或 `goal` 帧），
 * 不能把应答里的 `ref` 当成完整状态拼进界面。
 *
 * ## 三种失败要分开表达（UI 的处理完全不同）
 *
 * 1. [GoalError.CHANNEL_NOT_READY]：WS 没就绪 → 「稍后重试」，**不要**记住"不支持"；
 * 2. [GoalError.UNSUPPORTED]：内核没有 `goals` Remote → 这是**能力缺失**，
 *    记进状态后**后续不再发帧**，UI 弹「这个内核不支持目标管理」；
 * 3. [GoalError.REVISION_STALE]：revision 过期被内核拒 → 重新拉目标拿新 revision，
 *    让用户确认后再发一次，**绝不静默重试**（用户可能已经不想改了）。
 *
 * 3 的判据见 [isStaleRevisionError]：桥侧 `forwardError` 原样透传内核的 `ApiError.code`，
 * 没有固化成文档码，所以只能按「码集合 + message 关键字」双路识别，并用 `@patch` 记账。
 */
internal interface GoalPort {

    /**
     * 改目标正文与/或轮次上限。两个都为空是调用方的 bug（见 [GoalError.EDIT_EMPTY]）。
     *
     * @param objective 新的目标正文；null = 不改，空白串按"不改"处理（桥侧 `trimmedString` 同样口径）。
     * @param maxGoalRounds 新的轮次上限；必须是正整数，null = 不改。
     */
    suspend fun edit(
        sessionId: String,
        ref: GoalRef,
        objective: String? = null,
        maxGoalRounds: Int? = null,
    ): GoalAck

    suspend fun pause(sessionId: String, ref: GoalRef): GoalAck

    suspend fun resume(sessionId: String, ref: GoalRef): GoalAck

    suspend fun clear(sessionId: String, ref: GoalRef): GoalAck
}

/**
 * 一次写操作的回执。**只有版本信息，没有目标正文**（见 [GoalPort] 的说明）。
 *
 * @param cleared 仅 `goal-clear` 为 true；其余操作恒为 false。
 * @param revision 应答 `data.ref.revision`；桥没回 `ref` 时为 null（不代表操作失败）。
 */
internal data class GoalAck(val cleared: Boolean = false, val revision: Int? = null)

/**
 * 目标写操作需要的乐观锁引用：桥侧 `ref:{id,revision}`。
 *
 * 两个字段都必须来自**最近一次读到的目标**（[io.github.westanke.dshcompanion.data.GoalInfo]），
 * 不允许自己造一个：
 * - `id` 是内核的目标 id，换会话/重新制定目标都会变；
 * - `revision` 每次内核改目标都会 +1，拿旧值发过去会被 compare-and-set 拒绝。
 */
internal data class GoalRef(val id: String, val revision: Int)

/** 四帧的名字。请求 type 与应答 kind 同名，所以只需要一份常量。 */
internal object GoalFrames {
    const val EDIT = "goal-edit"
    const val PAUSE = "goal-pause"
    const val RESUME = "goal-resume"
    const val CLEAR = "goal-clear"
}

/** 本端口自己产的、以及从桥侧码翻译过来的失败码。 */
internal object GoalError {

    /** 内核没有 `goals` Remote —— 能力缺失，记住后不再发帧。 */
    const val UNSUPPORTED = "GOAL_UNSUPPORTED"

    /** 通道不是 [ChannelState.usesWs]（或 router 还没装好）—— 暂时发不出去，可重试。 */
    const val CHANNEL_NOT_READY = "GOAL_CHANNEL_NOT_READY"

    /** 内核的 compare-and-set 拒绝了这个 revision —— 必须重新读目标再让用户确认。 */
    const val REVISION_STALE = "GOAL_REVISION_STALE"

    /** 读到的目标缺 id 或缺正整数 revision，构造不出 [GoalRef]。 */
    const val REF_MISSING = "GOAL_REF_MISSING"

    /** 编辑时 objective 与 maxGoalRounds 都没给。 */
    const val EDIT_EMPTY = "GOAL_EDIT_EMPTY"
}

/**
 * 走 `/ws/mobile` 的四帧实现。
 *
 * @param router 装配层装好的请求配对层（`HarnessViewModel.wsRouter`）；null = 还没连 WS。
 * @param channel 当前通道状态；**每次写之前都现取**，因为通道可能在你犹豫的时候断了。
 * @param kernelUnsupported 上次是否已被判定「这个内核不支持目标管理」；true 时直接抛错不发帧。
 * @param onKernelUnsupported 第一次撞到 `unsupported` 时回调，让装配层把标志位记下来。
 */
internal class WsGoalPort(
    private val router: WsRequestRouter?,
    private val channel: () -> ChannelState,
    private val kernelUnsupported: () -> Boolean = { false },
    private val onKernelUnsupported: () -> Unit = {},
) : GoalPort {

    override suspend fun edit(
        sessionId: String,
        ref: GoalRef,
        objective: String?,
        maxGoalRounds: Int?,
    ): GoalAck {
        val trimmed = objective?.trim()?.takeIf(String::isNotEmpty)
        if (trimmed == null && maxGoalRounds == null) {
            throw DshApiException(0, GoalError.EDIT_EMPTY, "目标内容与轮次上限至少要改一项")
        }
        if (maxGoalRounds != null && maxGoalRounds <= 0) {
            throw DshApiException(0, GoalError.EDIT_EMPTY, "轮次上限必须是正整数")
        }
        val payload = goalPayload(sessionId, ref) {
            trimmed?.let { put("objective", it) }
            maxGoalRounds?.let { put("maxGoalRounds", it) }
        }
        return write(GoalFrames.EDIT, sessionId, payload)
    }

    override suspend fun pause(sessionId: String, ref: GoalRef): GoalAck =
        write(GoalFrames.PAUSE, sessionId, goalPayload(sessionId, ref))

    override suspend fun resume(sessionId: String, ref: GoalRef): GoalAck =
        write(GoalFrames.RESUME, sessionId, goalPayload(sessionId, ref))

    override suspend fun clear(sessionId: String, ref: GoalRef): GoalAck =
        write(GoalFrames.CLEAR, sessionId, goalPayload(sessionId, ref))

    /**
     * 发一帧并等应答。返回回执，不返回目标正文（见 [GoalPort]）。
     */
    private suspend fun write(type: String, sessionId: String, payload: JsonObject): GoalAck {
        val active = readyRouter()
        val frame = try {
            active.request(
                requestType = type,
                expectedKind = type,
                payload = payload,
                // 同一会话的目标写操作本来就被桥串行化，但会话 id 仍要参与配对：
                // 用户可以在两个会话之间来回切，回帧必须落到发起它的那个会话上。
                matcher = { WsFrames.dataOf(it).sessionIdOf() == sessionId },
            )
        } catch (error: DshApiException) {
            throw translate(error)
        }
        val data = WsFrames.dataOf(frame)
        return GoalAck(
            cleared = data.booleanOf("cleared") == true,
            revision = (data["ref"] as? JsonObject)?.get("revision")?.let { (it as? JsonPrimitive)?.intOrNull },
        )
    }

    /** 三道闸门：能力缺失 → 通道没就绪 → router 还没装。任一条都**不发帧**。 */
    private fun readyRouter(): WsRequestRouter {
        if (kernelUnsupported()) {
            throw DshApiException(0, GoalError.UNSUPPORTED, "这个内核不支持目标管理")
        }
        if (!channel().usesWs) {
            throw DshApiException(0, GoalError.CHANNEL_NOT_READY, "目标管理需要 WebSocket 通道，当前通道未就绪")
        }
        return router
            ?: throw DshApiException(0, GoalError.CHANNEL_NOT_READY, "目标管理需要 WebSocket 通道，当前通道未就绪")
    }

    /** 桥侧错误码 → 本端口的三种语义之一；其余原样透传（含 `SESSION_FORBIDDEN`）。 */
    private fun translate(error: DshApiException): DshApiException {
        val code = error.code.trim().substringAfterLast('/').uppercase()
        if (code == "UNSUPPORTED") {
            onKernelUnsupported()
            return DshApiException(error.status, GoalError.UNSUPPORTED, error.message)
        }
        if (isStaleRevisionError(code, error.message)) {
            return DshApiException(error.status, GoalError.REVISION_STALE, error.message)
        }
        return error
    }
}

/**
 * 通道/内核根本给不出写能力时的实现：**每一次调用**都立刻抛 [GoalError.UNSUPPORTED]。
 *
 * 存在的意义是让装配层永远拿得到一个非空 [GoalPort]（UI 不必写空判断），
 * 同时保证"没有实现"和"实现但不可用"在界面上的表现完全一致 —— 都是一句实话加一个原因。
 */
internal class UnsupportedGoalPort(
    private val reason: String = "这个内核不支持目标管理",
) : GoalPort {

    override suspend fun edit(sessionId: String, ref: GoalRef, objective: String?, maxGoalRounds: Int?): GoalAck =
        throw unsupported()

    override suspend fun pause(sessionId: String, ref: GoalRef): GoalAck = throw unsupported()

    override suspend fun resume(sessionId: String, ref: GoalRef): GoalAck = throw unsupported()

    override suspend fun clear(sessionId: String, ref: GoalRef): GoalAck = throw unsupported()

    private fun unsupported() = DshApiException(0, GoalError.UNSUPPORTED, reason)
}

/** 四帧共用的 `{sessionId, ref:{id,revision}}`，额外的键由 [extra] 追加。 */
private fun goalPayload(sessionId: String, ref: GoalRef, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject {
    require(sessionId.isNotBlank()) { "sessionId 不能为空" }
    // 明知会被桥判 bad-request 的帧不发（桥侧 readGoalRef 的同一判据）。
    if (ref.id.isBlank() || ref.revision <= 0) {
        throw DshApiException(0, GoalError.REF_MISSING, "这个目标没有可用的 id/revision，无法安全执行修改")
    }
    return buildJsonObject {
        put("sessionId", sessionId)
        put(
            "ref",
            buildJsonObject {
                put("id", ref.id.trim())
                put("revision", ref.revision)
            },
        )
        extra()
    }
}

/**
 * revision 过期 / 冲突的判据（补丁说明见函数上方的 `@patch` 块）。
 */
// @patch
// id: goal-revision-stale-detection
// for: 内核 goals Remote 的 compare-and-set 拒绝（revision 过期）在 ws-goals.ts 里走 forwardError 原样透传，码名未固化进桥的 wire 文档
// why: 不改码名就只能猜；不识别的话过期 revision 会以 WS_ERROR 之类的泛码冒上来，界面只能说"失败"，用户不知道该刷新重读
// until: 桥在 docs/project/API.md 固化了 revision 冲突的专用错误码并进了合约测试
// test: app/src/test/java/io/github/westanke/dshcompanion/data/ws/GoalPortTest.kt
internal fun isStaleRevisionError(code: String?, message: String?): Boolean {
    val normalized = code?.trim()?.takeIf(String::isNotEmpty)?.substringAfterLast('/')?.uppercase()
    if (normalized != null && normalized in STALE_REVISION_CODES) return true
    val text = message?.lowercase().orEmpty()
    if (text.isEmpty()) return false
    // 只在同时提到 revision/version 与冲突语义时才算：否则 "tool revision 3" 这种无关报错会被误判。
    val mentionsRevision = "revision" in text || "rev" in text || "version" in text
    val mentionsConflict = "stale" in text || "mismatch" in text || "conflict" in text || "expected" in text
    return mentionsRevision && mentionsConflict
}

/** 见过的冲突码名（大写、去掉 `ns/` 前缀后的样子）。 */
private val STALE_REVISION_CODES = setOf(
    "CONFLICT",
    "GOAL_CONFLICT",
    "REVISION_CONFLICT",
    "REVISION_MISMATCH",
    "STALE_REVISION",
    "VERSION_CONFLICT",
    "PRECONDITION_FAILED",
)

/** 读 `data.sessionId`；缺失返回 null。与 `WsPorts.kt` 里那份同义（那边是 private，无法复用）。 */
private fun JsonObject.sessionIdOf(): String? = (this["sessionId"] as? JsonPrimitive)?.contentOrNull

/** 读布尔字段；非布尔或缺席返回 null（不把 "false" 当 false 之外的任何东西）。 */
private fun JsonObject.booleanOf(key: String): Boolean? = when (val raw = this[key]) {
    is JsonPrimitive -> when (raw.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}
