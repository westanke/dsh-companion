package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.data.DeviceView
import io.github.westanke.dshcompanion.data.DirectoryPage
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.DshConnectionException
import io.github.westanke.dshcompanion.data.FileEntry
import io.github.westanke.dshcompanion.data.handoffPathOf
import io.github.westanke.dshcompanion.data.LiveChatState
import io.github.westanke.dshcompanion.data.RootView
import io.github.westanke.dshcompanion.data.AgentPreset
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.PendingApproval
import io.github.westanke.dshcompanion.data.PendingQuestionSet
import io.github.westanke.dshcompanion.data.PluginInventory
import io.github.westanke.dshcompanion.data.ProviderModel
import io.github.westanke.dshcompanion.data.ProviderPatch
import io.github.westanke.dshcompanion.data.ProviderSettings
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.ResolvedPath
import io.github.westanke.dshcompanion.data.SessionModels
import io.github.westanke.dshcompanion.data.ActiveConnection
import io.github.westanke.dshcompanion.data.AppPreferences
import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.ComposerSendPolicy
import io.github.westanke.dshcompanion.data.ConnectionCoordinator
import io.github.westanke.dshcompanion.data.ConnectionShare
import io.github.westanke.dshcompanion.data.Credential
import io.github.westanke.dshcompanion.data.CredentialKind
import io.github.westanke.dshcompanion.data.Endpoint
import io.github.westanke.dshcompanion.data.EndpointKind
import io.github.westanke.dshcompanion.data.EndpointProbe
import io.github.westanke.dshcompanion.data.Host
import io.github.westanke.dshcompanion.data.HostStore
import io.github.westanke.dshcompanion.data.KeystoreSecretBox
import io.github.westanke.dshcompanion.data.LegacyConnectionMigration
import io.github.westanke.dshcompanion.data.SharedPreferencesKeyValueStore
import io.github.westanke.dshcompanion.data.TrashEntry
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import io.github.westanke.dshcompanion.data.chatDelta
import io.github.westanke.dshcompanion.data.chatTurnFailure
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.binaryPreviewByteLimit
import io.github.westanke.dshcompanion.data.previewKind
import io.github.westanke.dshcompanion.data.previewKindOf
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 「交互」域：工具调用**审批**与内核**提问**的拉取、提交与决定。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出，成员函数改成 **[HarnessViewModel] 的扩展函数**。
 *
 * 这两块共用「内核在等一个决定」的形状，但**状态机完全不同**，混在一起最容易互相抄错：
 *
 * | | 审批 | 提问 |
 * |---|---|---|
 * | 谁在等 | 内核**阻塞**着等 | Agent 已继续跑，答复是补交 |
 * | 界面占位 | 顶掉输入区（用户此刻没别的事可做） | 不顶输入区（否则连话都发不出去） |
 * | 防重 | 本地 `Deciding` 态 + 插件 | `answeringCallId` + 已答集合 + 插件 409 |
 *
 * 拉取**只对当前选中的会话**发一次 GET，绝不遍历会话列表、也不做后台轮询 ——
 * 插件的 `resolveAgent` 是「resolve **or resume**」，对冷会话调会把那边已经停下的 Agent 拉活。
 */
internal fun HarnessViewModel.refreshPendingApprovals() {
    val api = client ?: return
    val id = mutableState.value.selectedSessionId ?: return
    viewModelScope.launch {
        val result = runCatching { withContext(Dispatchers.IO) { api.pendingApprovals(id) } }
        if (mutableState.value.selectedSessionId != id) return@launch
        result
            .onSuccess { approvals -> update { copy(approvalState = approvals.toApprovalState()) } }
            .onFailure { error ->
                update {
                    copy(
                        approvalState = ApprovalUiState.None,
                        error = if (error is DshApiException && error.code == "RATE_LIMITED") null else messageOf(error),
                    )
                }
            }
    }
}

/**
 * 取当前会话的待回答问题。
 *
 * **只对当前选中的会话调**，一次一个 GET。插件的 `resolveAgent` 是「resolve **or resume**」，
 * 对冷会话调会把那边已经停下的 Agent 拉活 —— 所以这里永远只看 `selectedSessionId`，
 * 绝不遍历 [HarnessState.sessions]，也不做任何后台轮询。
 *
 * 失败**刻意静默**：这条路会挂在全轮的 turn 事件上，插件版本旧（404）或一次网络抖
 * 都会失败；把错误塞进 snackbar 就等于每轮弹一次。列表也**保留原样**不清空 ——
 * 已经显示出来的卡片不该因为一次刷新失败就消失（清空只在换会话时发生）。
 */
internal fun HarnessViewModel.refreshPendingQuestions() {
    val api = client ?: return
    val id = mutableState.value.selectedSessionId ?: return
    viewModelScope.launch {
        val result = runCatching { withContext(Dispatchers.IO) { api.pendingQuestions(id) } }
        // 期间用户可能已经切走：这份结果只对发起它的那个会话有效。
        if (mutableState.value.selectedSessionId != id) return@launch
        result.onSuccess { sets ->
            update { copy(questions = sets.filter { it.state == QUESTION_STATE_CONTINUED }) }
        }
    }
}

/**
 * 回答一组问题（`ask_user_question`）。
 *
 * 防重复提交两道闸：本地 [HarnessState.answeringCallId] 非空时直接忽略后续点击，
 * 已经答过的 callId 也不再发；插件的 409 是最后一道防线，但不该让用户点到那一步。
 */
internal fun HarnessViewModel.answerQuestions(callId: String, answers: List<QuestionAnswer>) {
    val api = client ?: return
    val id = mutableState.value.selectedSessionId ?: return
    if (mutableState.value.answeringCallId != null) return
    if (callId in mutableState.value.answeredCallIds) return
    viewModelScope.launch {
        update { copy(answeringCallId = callId, error = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) { api.answerQuestions(id, callId, answers) }
        }
        if (mutableState.value.selectedSessionId != id) return@launch
        result
            .onSuccess {
                update {
                    copy(
                        answeringCallId = null,
                        answeredCallIds = answeredCallIds + callId,
                    )
                }
                // 问一次服务端：答复被内核吃掉了的话，这条问题就该从列表里消失；
                // 还在的话卡片已经转成「已回答」，不会被误当成没答。
                refreshPendingQuestions()
            }
            .onFailure { error ->
                update { copy(answeringCallId = null, error = describeAnswerFailure(error)) }
            }
    }
}

/**
 * 提交失败的人话。
 *
 * 插件用 409 区分两种**完全不同**的情况：答复已经在队列里（你答过了）与问题已经不作答
 * （这条作废了）。把 code 原样甩给用户，他既看不懂也不知道该不该重试。
 */
internal fun HarnessViewModel.describeAnswerFailure(failure: Throwable): String = when {
    failure is DshApiException && failure.code == "QUESTION_REPLY_QUEUED" ->
        appString(R.string.answer_already_queued)

    failure is DshApiException && failure.code == "QUESTION_NOT_PENDING" ->
        appString(R.string.answer_not_pending)

    failure is DshApiException && (failure.status == 403 || failure.code.contains("FORBIDDEN") ||
        failure.code.contains("SCOPE")) ->
        appString(R.string.answer_no_write)

    else -> appString(R.string.answer_failed, messageOf(failure))
}

internal fun HarnessViewModel.decideApproval(approval: PendingApproval, allowOnce: Boolean) {
    val api = client ?: return
    val id = mutableState.value.selectedSessionId ?: return
    if (approval.sessionId != id) return
    val items = when (val current = mutableState.value.approvalState) {
        is ApprovalUiState.Pending -> current.items
        is ApprovalUiState.Deciding -> current.items
        else -> listOf(approval)
    }
    viewModelScope.launch {
        update { copy(approvalState = ApprovalUiState.Deciding(approval, items), error = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                api.decideApproval(id, approval.id, allowOnce)
                api.pendingApprovals(id) to sessionPort().sessions()
            }
        }.onSuccess { (approvals, sessions) ->
            if (mutableState.value.selectedSessionId == id) {
                update { copy(approvalState = approvals.toApprovalState(), sessions = sessions) }
                chatSessionRefreshPending = true
                scheduleChatRefresh()
            }
        }.onFailure { error ->
            if (mutableState.value.selectedSessionId == id) {
                update { copy(approvalState = ApprovalUiState.Pending(items), error = messageOf(error)) }
            }
        }
    }
}
