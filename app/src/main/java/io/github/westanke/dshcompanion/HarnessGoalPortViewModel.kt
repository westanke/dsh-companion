package io.github.westanke.dshcompanion

import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.GoalInfo
import io.github.westanke.dshcompanion.data.sessionAnalyticsOf
import io.github.westanke.dshcompanion.data.ws.ChannelState
import io.github.westanke.dshcompanion.data.ws.GoalError
import io.github.westanke.dshcompanion.data.ws.GoalPort
import io.github.westanke.dshcompanion.data.ws.GoalRef
import io.github.westanke.dshcompanion.data.ws.WsGoalPort
import io.github.westanke.dshcompanion.debug.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目标（goal）写操作的装配层：**四个写帧 + 就地失败态**。
 *
 * ## 为什么单独一个文件，而不是塞进 `HarnessWsPortsViewModel.kt`
 *
 * 目标写操作是整个 WS 改造里唯一的新功能，它**没有 REST 兜底**（桥的 `router.ts` 里
 * `goal` 零命中），所以它既不需要 `PortSet` 那套「WS 优先 / REST 回落」的自适应选路，
 * 也不需要跟着三域端口一起被装配。这层只做四件事：
 *
 * 1. 把 `wsRouter` + 当前 [ChannelState] 交给 [WsGoalPort]；
 * 2. 从**当前会话的投影**里取 `ref:{id,revision}`（用户界面上的目标值是唯一可信来源）；
 * 3. 失败就地弹窗（不走全局 error 条，也不占全局 `busy`）；
 * 4. **记住**「这个内核不支持目标管理」，之后不再发帧。
 *
 * ## 与 `launchOperation` 的分工（刻意不共用）
 *
 * `launchOperation` 会把失败写进 `state.error` 并在整个请求期间把 `busy` 置真。
 * 目标写操作会**就地在统计面板里**问「rev 过期了，要按最新版本重试吗」，
 * 这种交互塞进全局错误条会立刻被下一次刷新冲掉；`busy` 还会连带禁用发送按钮。
 * 所以这里照 `HarnessSpeechViewModel` 的先例自管状态 + 代际判据。
 */
internal fun HarnessViewModel.goalPort(): GoalPort = WsGoalPort(
    router = wsRouter,
    channel = { goalChannelState() },
    kernelUnsupported = { goalUnsupportedForHost() },
    onKernelUnsupported = { rememberGoalUnsupported() },
)

/** 当前通道状态；没有传输层时按只有 REST 处理（写操作因此不可用）。 */
internal fun HarnessViewModel.goalChannelState(): ChannelState =
    wsTransport?.state?.value ?: ChannelState.RestOnly

/**
 * 「这个内核不支持目标管理」的**按主机**记忆。
 *
 * 按主机而不是全局一个布尔：用户可以把 App 切到另一台跑着新版内核的机器，
 * 那边是支持的。全局布尔会让新机器也永远是"不支持"，只能重启 App 才能恢复 ——
 * 这类"记住得太久"的降级比不降级更让人困惑。
 */
internal fun HarnessViewModel.goalUnsupportedForHost(): Boolean {
    val key = goalHostKey() ?: return false
    return mutableState.value.goalAction.unsupportedHost == key
}

/** 把「不支持」钉在当前主机上；后续同一主机的写操作直接失败、不再发帧。 */
internal fun HarnessViewModel.rememberGoalUnsupported() {
    val key = goalHostKey() ?: return
    if (mutableState.value.goalAction.unsupportedHost == key) return
    DiagLog.i("GOAL", "内核不支持目标管理：记在 $key 上，后续不再发帧")
    update { copy(goalAction = goalAction.copy(unsupportedHost = key)) }
}

/** 主机身份：同一个地址 + 同一个连接 id 才算同一台内核。 */
private fun HarnessViewModel.goalHostKey(): String? =
    active?.let { "${it.endpoint.baseUrl}#${it.host.id}" }

/** 从投影里的目标读 `ref`；缺 id 或缺正整数 revision 时返回 null（不能安全写入）。 */
internal fun goalRefOf(goal: GoalInfo?): GoalRef? {
    val id = goal?.id?.takeIf(String::isNotBlank) ?: return null
    val revision = goal.revision?.takeIf { it > 0 } ?: return null
    return GoalRef(id = id, revision = revision)
}

/** 用户点了「暂停」。 */
internal fun HarnessViewModel.requestGoalPause() = performGoal(GoalAction.Pause)

/** 用户点了「恢复」。 */
internal fun HarnessViewModel.requestGoalResume() = performGoal(GoalAction.Resume)

/** 用户点了「清除」（界面已做过二次确认）。 */
internal fun HarnessViewModel.requestGoalClear() = performGoal(GoalAction.Clear)

/**
 * 用户在编辑对话框里点了「保存」。
 *
 * @param objective 新目标正文；空白串按"不改"处理。
 * @param maxGoalRounds 新轮次上限；null 或非正数按"不改"处理（界面侧已拦一次，这里再兜一次底）。
 */
internal fun HarnessViewModel.requestGoalEdit(objective: String?, maxGoalRounds: Int?) =
    performGoal(
        GoalAction.Edit(
            objective = objective?.trim()?.takeIf(String::isNotEmpty),
            maxGoalRounds = maxGoalRounds?.takeIf { it > 0 },
        ),
    )

/** 关掉就地失败弹窗。 */
internal fun HarnessViewModel.dismissGoalFailure() {
    update { copy(goalAction = goalAction.copy(failure = null)) }
}

/**
 * 通道/内核给不出写能力时，界面上的按钮**仍然可点**，点进来落到这里弹一句实话。
 *
 * 为什么不把按钮直接藏掉或灰掉：灰按钮在真机上等于"点了没反应"，用户会以为是卡了
 * 或者以为自己点偏了 —— 这个坑本项目已经踩过一次（语音按钮 `enabled` 不含提示分支，
 * 见 `docs/project/SpeechInput.kt` 的注释）。宁可多一次点击，也要让原因出现。
 */
internal fun HarnessViewModel.reportGoalUnsupported() {
    update {
        copy(
            goalAction = goalAction.copy(
                failure = GoalFailure(GoalFailureKind.UNSUPPORTED, null),
            ),
        )
    }
}

/** rev 过期后用户确认「按最新版本重试」：重新发一次**同一个动作**（ref 取当前投影里的新值）。 */
internal fun HarnessViewModel.retryGoalAction() {
    val failure = mutableState.value.goalAction.failure ?: return
    val action = failure.retry ?: return
    performGoal(action)
}

/**
 * 四个写操作的唯一执行入口。
 *
 * 代际判据：`sessionId` 在 return 时必须还是当前会话，否则结果（成功或失败）整体丢弃 ——
 * 与 `launchOperation` 同一个纪律，因为用户完全可能在你等应答的时候切走。
 */
private fun HarnessViewModel.performGoal(action: GoalAction) {
    val snapshot = mutableState.value
    if (snapshot.goalAction.pending != null) return
    val sessionId = snapshot.selectedSessionId
    if (sessionId == null) {
        update { copy(goalAction = goalAction.copy(failure = GoalFailure(GoalFailureKind.OTHER, null))) }
        return
    }
    val ref = goalRefOf(sessionAnalyticsOf(snapshot.history).goal)
    if (ref == null) {
        // 读不到 id/revision：不发帧。刷新一次历史通常就能补上（投影可能还没到）。
        DiagLog.w("GOAL", "$action 缺 ref：投影里没有 id 或正整数 revision，不发帧")
        update { copy(goalAction = goalAction.copy(failure = GoalFailure(GoalFailureKind.REF_MISSING, null))) }
        refreshHistory()
        return
    }
    update { copy(goalAction = goalAction.copy(pending = action, failure = null)) }
    viewModelScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) { dispatchGoal(action, sessionId, ref) }
        }
        if (mutableState.value.selectedSessionId != sessionId) {
            DiagLog.w("GOAL", "$action 的结果被丢弃：会话已经切走了")
            update { copy(goalAction = goalAction.copy(pending = null)) }
            return@launch
        }
        result.onSuccess { ack ->
            DiagLog.i("GOAL", "$action 成功：rev=${ack.revision} cleared=${ack.cleared}")
            update { copy(goalAction = goalAction.copy(pending = null, failure = null)) }
            // 应答只带 ref / cleared，**没有新的目标正文** → 必须重新读一次投影。
            refreshHistory()
        }
        result.onFailure { error ->
            if (error is CancellationException) throw error
            DiagLog.w("GOAL", "$action 失败：${error::class.java.simpleName}: ${error.message}")
            var failure = goalFailureOf(error, action)
            // 其余业务码（典型：SESSION_FORBIDDEN）走错误码表翻成人话，别把英文原文丢给用户。
            if (failure.kind == GoalFailureKind.OTHER && error is DshApiException) {
                failure = failure.copy(detail = messageOf(error))
            }
            update { copy(goalAction = goalAction.copy(pending = null, failure = failure)) }
            if (failure.kind == GoalFailureKind.REVISION_STALE) {
                // rev 过期：先把目标重新读回来（拿到新 revision），再等用户确认。
                // 绝不静默重试 —— 用户可能已经不想改了，也可能别人刚改过。
                refreshHistory()
            }
        }
    }
}

/** 真正发帧的那一步。单独一个函数，便于失败映射只写一次。 */
private suspend fun HarnessViewModel.dispatchGoal(
    action: GoalAction,
    sessionId: String,
    ref: GoalRef,
): io.github.westanke.dshcompanion.data.ws.GoalAck {
    val port = goalPort()
    return when (action) {
        is GoalAction.Edit -> port.edit(sessionId, ref, action.objective, action.maxGoalRounds)
        GoalAction.Pause -> port.pause(sessionId, ref)
        GoalAction.Resume -> port.resume(sessionId, ref)
        GoalAction.Clear -> port.clear(sessionId, ref)
    }
}

/** 异常 → 界面要说的那种失败。只有「rev 过期」与「通道没就绪」值得给重试按钮。 */
internal fun goalFailureOf(error: Throwable, action: GoalAction?): GoalFailure {
    val code = (error as? DshApiException)?.code
    val detail = error.message
    return when (code) {
        GoalError.UNSUPPORTED -> GoalFailure(GoalFailureKind.UNSUPPORTED, detail)
        GoalError.REVISION_STALE -> GoalFailure(GoalFailureKind.REVISION_STALE, detail, retry = action)
        GoalError.CHANNEL_NOT_READY -> GoalFailure(GoalFailureKind.CHANNEL_NOT_READY, detail, retry = action)
        GoalError.REF_MISSING -> GoalFailure(GoalFailureKind.REF_MISSING, detail)
        else -> GoalFailure(GoalFailureKind.OTHER, detail)
    }
}

/**
 * 目标写操作的界面态。挂在 [HarnessState] 上，因为要跨「bottom sheet 重开」存活：
 * 用户保存后若失败，弹窗得还在；关掉面板再打开也不该丢。
 *
 * @param unsupportedHost 「这个内核不支持目标管理」记住在哪台主机上（见 [goalUnsupportedForHost]）。
 * @param pending 正在执行的动作；非 null 时按钮显示进度并禁止重复点击。
 * @param failure 要就地弹出来的失败说明；null = 不弹。
 */
data class GoalActionState(
    val unsupportedHost: String? = null,
    val pending: GoalAction? = null,
    val failure: GoalFailure? = null,
)

/** 用户能发起的四个动作（也是失败后重试要照原样再发一次的东西）。 */
sealed interface GoalAction {

    /** 改正文与/或轮次上限；两个都为 null 时下发帧本身就会报错。 */
    data class Edit(val objective: String?, val maxGoalRounds: Int?) : GoalAction

    data object Pause : GoalAction

    data object Resume : GoalAction

    data object Clear : GoalAction
}

/** 失败的种类 —— 决定弹窗标题、正文与有没有重试按钮。 */
enum class GoalFailureKind {
    /** 内核没有 goals Remote：能力缺失，记住后不再发帧。 */
    UNSUPPORTED,

    /** WS 没就绪：稍后重试即可，**不要**记住"不支持"。 */
    CHANNEL_NOT_READY,

    /** revision 过期：已重新读回目标，需用户确认后再发一次。 */
    REVISION_STALE,

    /** 投影里读不到 id / 正整数 revision：刷新一次通常就能补上。 */
    REF_MISSING,

    /** 其余（含会话越权 `SESSION_FORBIDDEN`、网络失败）：原样说原因，不给重试。 */
    OTHER,
}

/**
 * 一次失败的完整描述。
 *
 * @param detail 服务端/端口给的原始说明；可能为 null（本端"缺 ref"这类判断没有远端文案）。
 * @param retry 重试要重发的动作；null = 不给重试按钮（重发也没意义或需要用户先改输入）。
 */
data class GoalFailure(
    val kind: GoalFailureKind,
    val detail: String? = null,
    val retry: GoalAction? = null,
)
