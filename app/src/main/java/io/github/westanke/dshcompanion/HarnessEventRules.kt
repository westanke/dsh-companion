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
 * 事件帧与输入行的**纯判定**：不碰 Android API、不碰网络，除了入参什么都不改。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出。抽成顶层函数而不是 ViewModel 的成员，
 * 是为了能在 JVM 单测里直接调用：
 *
 * | 函数 | 测试 | 钉住的是什么 |
 * |---|---|---|
 * | [isHostCommandLine] | `HostCommandLineTest` | `/名字` 是宿主命令还是技能名（分流规则必须与插件逐字一致） |
 * | [removeQuestion] | `RemoveQuestionTest` | 「这道题不用答了」的两帧就地摘掉；callId 对不上原样返回 |
 * | [isToolResultFrame] | `ToolResultFrameTest` | 提问超时的信号藏在降级帧里，别「来一帧拉一次」 |
 * | [markSessionStopped] | `MarkSessionStoppedTest` | 终局失败后本地立刻置位，不等下一次 GET |
 */
/**
 * 宿主命令名：与插件 `chat-adapter.ts` 的判定正则逐字对应。
 *
 * 小写字母开头，其余只允许小写字母、数字、下划线、连字符。**两侧必须一致** ——
 * 放宽会让客户端认为是命令而服务端说`COMMAND_NOT_FOUND`，收紧则命令永远发不出去。
 */
internal val COMMAND_NAME = Regex("[a-z][a-z0-9_-]*")

/**
 * 这一行是不是**宿主命令**（`/compact`、`/clear` 那种），而不是技能名。
 *
 * ## 为什么必须判别，不能只看 `/` 开头
 *
 * 官方把两样东西都做成斜杠开头：宿主命令，与用户技能（`dsh-client-ui-skill/README.md:28`）。
 * 分流规则由**客户端**做，原文：
 *
 * > A name shared with a host command still resolves to the command —
 * > adjudication claims the line client-side before it ever becomes a prompt.
 *
 * 即**命令优先**；不是命令的 `/name` 应当**原样当普通消息发给模型**（同文件 `:85`
 * 「The user's message reaches the model verbatim, `/name` literal included」），
 * 由 `dsh-tool-skill` 在 pre-step 边界注入技能正文。
 *
 * 我们此前只要输入以 `/` 开头就整条送去 executeCommand，于是**技能一个都调不动**：
 * 技能名不在宿主命令表里 → `COMMAND_NOT_FOUND`，消息压根到不了模型。
 *
 * ## 匹配规则照抄服务端，不自创
 *
 * 插件 `chat-adapter.ts` 判定命令用的是
 * `/^\/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])/u` 加 `name === match[1]`：
 * 命令名只允许小写字母开头、其余小写字母/数字/下划线/连字符，**精确匹配**，
 * 后面可跟空白与参数。两边规则必须一致 —— 这里若放宽，会出现「客户端认为是命令、
 * 服务端说不认识」；收紧则命令永远发不出去。
 *
 * 刻意**不**做大小写不敏感：官方与插件都是精确匹配，我们跟着，避免 `/Compact`
 * 在我们这儿被当成命令、到服务端却被拒。
 *
 * 做成**顶层函数**而不是 ViewModel 的成员，是为了能在 JVM 单测里直接调用 ——
 * 它是纯逻辑，不碰任何 Android API。
 */
internal fun isHostCommandLine(
    line: String,
    commands: List<CommandDescriptor>,
): Boolean {
    // 命令名后面可以带参数（`/compact 3`），所以只取第一段去比对 ——
    // 与服务端 `/^\/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])/u` 的前瞻是同一件事。
    val head = line.takeWhile { !it.isWhitespace() }
    if (!head.startsWith('/')) return false
    val name = COMMAND_NAME.matchEntire(head.removePrefix("/"))?.value ?: return false
    return commands.any { it.name == name }
}

internal fun WorkspaceEvent.dataString(key: String): String? =
    ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun WorkspaceEvent.dataInt(key: String): Int? =
    ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

internal fun List<PendingApproval>.toApprovalState(): ApprovalUiState =
    if (isEmpty()) ApprovalUiState.None else ApprovalUiState.Pending(this)

/**
 * 「待答问题可能变了」的事件类型（用法见 [HarnessViewModel] 的 connectEvents）。
 *
 * 刻意**不含** chat.message.delta：那是 token 级的流式增量，跟着它刷新就等于在轮询。
 * 这里列的都是每轮 / 每条消息一次的结构性事件 —— 少而准。
 */
internal val QUESTION_REFRESH_EVENTS = setOf(
    "chat.message.committed",
    "chat.turn.start",
    "chat.turn.end",
    "chat.session.status",
    "chat.approval.requested",
    "chat.approval.resolved",
)

/**
 * 从待答列表里摘掉一题。
 *
 * 用在插件那两帧上：`chat.userQuestions/answered`（本端或电脑端已作答）与
 * `chat.userQuestions/dismissed`（窗口关闭 / 整轮取消）。
 * 两者都是「这道题已经不用答了」的**确定性事实**，所以就地摘掉 ——
 * 而不是等下一次 `GET /questions` 回来，那只会让卡片多挂一个往返。
 *
 * 纯函数，便于单测：不改入参、callId 对不上时原样返回。
 */
internal fun removeQuestion(
    questions: List<PendingQuestionSet>,
    callId: String,
): List<PendingQuestionSet> =
    if (questions.none { it.callId == callId }) questions
    else questions.filterNot { it.callId == callId }

/**
 * 这一帧是不是 `tool/result`（插件降级成 `chat.session.event` 的那种）。
 *
 * 为什么要单独判：插件把**所有**非特殊会话事件统一降级成 `chat.session.event`，
 * 内容被丢掉，只留 `{sessionId, eventType, seq}`（chat-adapter.ts normalizeDshEvent 末段）。
 * 一轮里这类帧非常多（每个工具调用好几帧），若「来一帧拉一次」，等于自己造了个轮询。
 *
 * 而提问超时的判定完全落在内核那条 tool/result 上：
 * 折叠器读到 `{pending:true}` 的结果才把问题从 open 改成 continued
 *（dsh-user-questions/lib/types/projection.js 的 `tool/result` 分支 + isPendingResult）。
 * 所以**这一帧到达，就是该重新拉取的确切信号** —— 不是启发式，是时序上的事实。
 */
internal fun isToolResultFrame(eventType: String?, eventData: Any?): Boolean {
    if (eventType != "chat.session.event") return false
    val obj = eventData as? kotlinx.serialization.json.JsonObject ?: return false
    return obj["eventType"]?.let { it.toString().trim('"') } == "tool/result"
}

/**
 * 收到终局失败帧后，把那个会话在本地立刻标成「不在运行」。
 *
 * 为什么不能等下一次 `GET /sessions`：服务端把会话标成 not-running 与这帧到达
 * 之间有几百毫秒，而这段时间里「停止」按钮还是亮的、输入框占位符还写着
 * 「运行中…」—— 用户看着就以为还在跑，于是继续傻等。本地先置位，
 * 等权威数据回来再用它盖一次，两头都对。
 *
 * 纯函数，便于单测：不改入参；sessionId 对不上时原样返回。
 */
internal fun markSessionStopped(
    sessions: List<ChatSession>,
    sessionId: String,
): List<ChatSession> =
    if (sessions.none { it.id == sessionId && it.running }) sessions
    else sessions.map { if (it.id == sessionId) it.copy(running = false) else it }
