package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.InterfaceStyle
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
import io.github.westanke.dshcompanion.data.PermissionCatalog
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
import io.github.westanke.dshcompanion.data.ChatDisplayItem
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
 * App 的**UI 状态**与几个跨层流转的数据载体。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出：这里只放「长什么样」，不放「怎么变更」。
 *
 * [HarnessState] 是不可变快照，ViewModel 通过 `copy` 整份替换推进它 —— 所以每个字段的
 * 注释都写了「为什么要有这个字段」，那是它存在的全部理由。其中三个偏好字段
 * （[useWebChat] / [showContext] / [genUiDebug]）**必须在状态里**，不能由 UI 直接读
 * SharedPreferences：那样读出来的不是 Compose 状态，改了不触发重组。
 */
data class OpenDocument(
    val entry: FileEntry,
    val text: String? = null,
    val etag: String = "",
    val hasBom: Boolean = false,
    val lineEnding: String = "\n",
    val contentType: String? = null,
    /**
     * 原始字节，**只有图片 / PDF 这类要交给解码器的东西才留**。
     *
     * 文本预览走 [text]（已解成 String），再留一份字节纯属浪费；而图片 / PDF 没法从
     * String 还原，必须留原字节 —— 这是它们与文本预览最本质的区别。
     */
    val bytes: ByteArray? = null,
    /**
     * 预览是被**主动跳过**的（文件超过该类文件的上限），而不是「还没加载」。
     *
     * 两者在界面上完全不同：前者要给「文件过大 + 下载」的出路，后者只是还没读。
     */
    val skippedForSize: Boolean = false,
    /**
     * 打开时的原始文本 —— 「有没有未保存改动」就靠和它逐字比。
     *
     * 为什么不在 UI 里拿 `remember { text }` 存一份：编辑器的值会随输入法、
     * 重组、进程重建变化，而**返回键**那条路径不一定经过同一个 Composable 的
     * 生命周期（预览 ↔ 编辑 ↔ 返回都可能重建）。判定放进数据层才有一份权威值。
     *
     * 为 null 表示这不是文本文件（图片 / PDF / 超大文件），谈不上改动。
     */
    val originalText: String? = null,
) {
    /** 编辑器里的内容与打开时不一致，且这是能编辑的文本文件。 */
    val hasUnsavedChanges: Boolean
        get() = originalText != null && text != originalText
}

/**
 * 「本会话的文件」面板里正在查看的那一条。
 *
 * 故意不复用全局 `error` 通道：路径不在授权根内是**正常结果**（agent 提到的文件未必
 * 授权给这台手机），用户需要的是面板里一句解释，而不是一次全局错误提示。
 * [preview] 截断到 [HarnessViewModel] 里的预览上限；[tooLarge] / [outsideRoots] /
 * [failure] 三者互斥地说明「为什么看不到内容」。
 */
data class SessionFileOpen(
    /** 发起这次打开操作的会话 id。切到别的会话后，这个结果就不该再显示。 */
    val sessionId: String,
    /** 会话里原样的绝对路径（请求的目标）。 */
    val requestedPath: String,
    val loading: Boolean = true,
    val resolved: ResolvedPath? = null,
    val preview: String? = null,
    /** 图片 / PDF 的原始字节；文本预览不用这个字段（见 [OpenDocument.bytes]）。 */
    val bytes: ByteArray? = null,
    val truncated: Boolean = false,
    val tooLarge: Boolean = false,
    /** 服务端说不属于任何授权根（或不是普通文件）。 */
    val outsideRoots: Boolean = false,
    /** 其他失败原因，已是可直接展示的文本。 */
    val failure: String? = null,
)

/**
 * 一条已经准备好、等着随消息一起发出去的附件。
 *
 * 图片在 [part] 里已经内联好了 base64（[imageBytes] 只是留一份原图给界面画缩略图，
 * 不会重复编码）；文件在 [part] 里是上传换来的 `receiptId`。
 *
 * [id] 与 [part] 分开：同一个文件可以被选两次，而去重、删除都必须按「这一条」来认，
 * 不能按内容认。
 */
data class PendingAttachment(
    val id: String,
    val part: PromptPart,
    val name: String,
    val sizeBytes: Long = 0,
    /** 仅图片：原图字节，供缩略图使用。 */
    val imageBytes: ByteArray? = null,
)

sealed interface ApprovalUiState {
    data object None : ApprovalUiState
    data object Loading : ApprovalUiState
    data class Pending(val items: List<PendingApproval>) : ApprovalUiState
    data class Deciding(val item: PendingApproval, val items: List<PendingApproval>) : ApprovalUiState
}

data class HarnessState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val endpointDraft: String = "http://192.168.1.20:3090",
    val healthOk: Boolean = false,
    val connected: Boolean = false,
    val endpoint: String = "",
    // ——— 多主机 / 多地址连接层（新） ———
    /** 已保存的全部电脑。 */
    val hosts: List<Host> = emptyList(),
    /** 当前连上的电脑 id。 */
    val activeHostId: String? = null,
    /** 当前实际使用的地址 id（多地址里的胜出者）。 */
    val activeEndpointId: String? = null,
    /** 最近一次探活的逐地址结果，用于诊断面板。 */
    val endpointProbes: List<EndpointProbe> = emptyList(),
    /** 正在并发探活/建连。 */
    val connecting: Boolean = false,
    /** 界面上被选中查看/编辑的电脑（不一定是当前连上的那台）。 */
    val selectedHostId: String? = null,
    val device: DeviceView? = null,
    val roots: List<RootView> = emptyList(),
    val sessions: List<ChatSession> = emptyList(),
    val chatWorkspaces: List<ChatWorkspace> = emptyList(),
    val agentPresets: List<AgentPreset> = emptyList(),
    val workspacePickerRootId: String? = null,
    val workspacePickerPath: String = "",
    val workspacePickerDirectory: DirectoryPage? = null,
    val sessionModels: SessionModels? = null,
    val commands: List<CommandDescriptor> = emptyList(),
    val providerSettings: ProviderSettings? = null,
    /** 插件清单；null 表示尚未读取（未连接、无 settings.read 权限或读取失败）。 */
    val pluginInventory: PluginInventory? = null,
    val discoveredModels: List<ProviderModel> = emptyList(),
    val selectedSessionId: String? = null,
    val history: ChatHistory? = null,
    val liveChat: LiveChatState? = null,

    /**
     * 「回答被截断」提示**所属的会话**（null = 当前没有这条提示）。
     *
     * 为什么要单独存而不是从 history 里算：内核 `sessions.history` 不返回 `turn/end`，
     * 历史里根本没有这条信息（真机实测 2026-10-18）。所以它只能由实时帧写入、
     * 记在状态里跨撤销刷新存活；带上 sessionId 是为了切会话时自动失效，
     * 不必在每个切换入口记得清一次。
     */
    val truncatedNoticeSession: String? = null,
    /** 与 [truncatedNoticeSession] 成对：那条提示本身（`ChatItemKind.TRUNCATED`）。 */
    val truncatedNotice: ChatDisplayItem? = null,
    val approvalState: ApprovalUiState = ApprovalUiState.None,
    /**
     * 当前会话待回答的问题（插件 `/questions`，只留 [QUESTION_STATE_CONTINUED] 的那些）。
     *
     * 与 [approvalState] **分开**存：审批是内核阻塞着等决定、必须占用输入区；
     * 问题是 Agent 已经继续跑了、答复属于补交，不能顶掉输入框。两者的状态机也不同
     * （审批有 Loading/Deciding，问题只有「待答 / 提交中 / 已答」），混一个字段迟早分不清。
     */
    val questions: List<PendingQuestionSet> = emptyList(),
    /** 正在提交答案的那一组（null = 空闲）。既驱动 loading，也是防重复提交的闸。 */
    val answeringCallId: String? = null,
    /** 已经提交成功的 callId：卡片转「已回答」态，不再显示可操作控件。 */
    val answeredCallIds: Set<String> = emptySet(),
    val eventsConnected: Boolean = false,
    val selectedRootId: String? = null,
    val currentPath: String = "",
    val directory: DirectoryPage? = null,
    /**
     * 当前会话**工作目录**（cwd）下的条目，供输入框 `@` 补全用。
     *
     * 与 [directory] 刻意分开：那个是「文件」页的浏览状态（用户自己点来点去、
     * 会切到别的根与子目录），而 `@` 永远指向**本会话的 cwd**，两者互不影响 ——
     * 共用一个字段会导致「在文件页翻到别处，回会话里 `@` 就补全错目录」。
     *
     * 为 null 表示还没加载（或本会话解析不出工作目录，见 [loadMentionEntries]）。
     */
    val mentionEntries: List<FileEntry>? = null,
    /**
     * `@` 补全这次**尝试过但没拿到**（网络失败、会话没有可用工作目录…）。
     *
     * 与 [mentionEntries] 的 null 配合才能分清三态：`null + false` 是「还在读」，
     * `null + true` 是「读不到」，**空列表才是「目录真的是空的」**。
     * 少这一位的话，「加载失败」会被界面说成「这个目录还是空的」—— 把失败谎报成空，
     * 和上轮插件页那句「package.json 可能损坏」是同一类毛病。
     */
    val mentionFailed: Boolean = false,
    /**
     * 权限档位**候选项**清单（`GET /chat/permission-presets`，bridge ≥ 2.4.2）。
     *
     * 与会话投影里的当前档位刻意分开存：内核把清单放在独立远程服务上，
     * 投影只给 `currentValue`（见 `ChatPresentation.permissionSelect`）。
     * 清单是**进程级**的、一进连接取一次就够，不随会话变化，所以刷新时不必重取。
     *
     * 为 null 表示**还没取到或取不到**：老 bridge 没有这条路由（404），或请求失败。
     * 这时 UI 仍应显示当前档位，只是不给切换菜单 —— 把「读不到清单」升级成
     * 「整个权限入口消失」，就是 v2.2.44 那个缺陷本身。
     */
    val permissionCatalog: PermissionCatalog? = null,
    /**
     * 输入框上方是否**开着补全面板**（`@` 文件补全，或 `/` 命令补全）。
     *
     * 为什么这要进共享状态、而不是留在 ChatScreen 里：它要影响**外层抽屉的手势**。
     * Material3 的 `ModalNavigationDrawer` 默认从屏幕任意位置横向拖动都能开，
     * 而补全面板正好是贴着输入框、又长又宽的浮层 —— 用户在面板上横向划一下想看看
     * 长文件名，抽屉就弹出来了（"会话/文件/插件/模型/设置"）。它和列表滚动在同一个
     * 手势通道上抢触摸，所以只能把「面板开着」这个事实告诉外层。
     *
     * 与 WebView 那条 `webChatOpen` 是同一类修法：**关掉手势不影响正常使用**，
     * 左上角汉堡按钮照样能开抽屉。
     */
    val composerOverlay: Boolean = false,
    val document: OpenDocument? = null,
    /** 会话文件面板正在查看的那一条（null 表示面板显示路径列表）。 */
    val sessionFileOpen: SessionFileOpen? = null,
    val trash: List<TrashEntry> = emptyList(),
    /**
     * 智能体正在运行时，Enter 与主发送按钮的默认行为。
     *
     * 与 [busy] 的区别很重要：[busy] 是「有任何操作在跑」（连刷新都算），
     * 而这里关心的是「**这个会话的 agent 是否在运行**」—— 只有后者才谈得上排队与插话。
     * 早期实现用 [busy] 去禁用发送按钮，结果是任何一次后台刷新都会让用户发不出消息。
     */
    val busySendMode: BusySendMode = BusySendMode.QUEUE,
    /** dsh-ui 诊断行开关，默认关；见 [AppPreferences.genUiDebug]。 */
    val genUiDebug: Boolean = false,
    /**
     * 会话详情页走 WebView 版还是原生；见 [AppPreferences.useWebChat]。
     *
     * 这几个偏好项**必须放在状态里**，不能由 UI 直接去读 SharedPreferences：
     * 那样读出来的不是 Compose 状态，改了不触发重组 —— 用户点了开关没反应，
     * 得等别的原因引起重组时它才「跳」过去（真机反馈：
     * 「你开关他不变化，必须开关一次别的按钮他才变化」）。
     */
    val useWebChat: Boolean = false,
    /** 是否显示上下文注入块；见 [AppPreferences.showContext]。 */
    val showContext: Boolean = false,
    /**
     * 诊断日志（窗口 / 会话操作 / 屏幕变化）总开关；见 [AppPreferences.diagLog]。
     *
     * 同样必须进状态：UI 里那个开关要能**立刻**显示成已开/已关，
     * 而屏上诊断浮层的显隐也由它驱动 —— 两者都得是 Compose 状态才会跟着变。
     */
    val diagLog: Boolean = false,
    /**
     * 界面明暗风格（跟随系统 / 浅色 / 深色）；见 [AppPreferences.interfaceStyle]。
     *
     * 同样必须进状态：主题在三处被整个重建（初始、disconnect、loadSnapshot），
     * 只在 setter 里改一次的话，用户切一次连接就会被系统配色顶回去。
     */
    val interfaceStyle: InterfaceStyle = InterfaceStyle.SYSTEM,
    /** 应用内字号倍数；见 [AppPreferences.fontScale]。 */
    val fontScale: Float = 1f,
    /** 字号是否完全跟随系统；见 [AppPreferences.fontFollowsSystem]。 */
    val fontFollowsSystem: Boolean = false,
    /**
     * 服务端自报的插件版本（来自 `connection.ready`）。**仅用于诊断展示**，
     * 不作为功能判据 —— 能不能用某功能看的是能力位，不是版本号。
     */
    val bridgeVersion: String? = null,
    /**
     * 服务端自报的能力位（来自 `connection.ready`；旧服务端为空集 = **未知**）。
     *
     * 空集不等于「一条能力都没有」：调用方必须先确认真的读到了清单
     * （见 [io.github.westanke.dshcompanion.data.parseBridgeCapabilities]），
     * 否则每个旧服务端都会弹出假警告。
     */
    val bridgeCapabilities: Set<String> = emptySet(),
    /**
     * 已归档会话（按需拉取；空列表 = 没拉过或确实没有）。
     *
     * 与 [sessions] 分开存而不是合并成一个列表 + 标记位：归档视图是一个**独立的入口**，
     * 合并会让「当前列表是哪些」这件事散落到每个渲染分支里。
     */
    val archivedSessions: List<ChatSession> = emptyList(),
    /**
     * 已并入的最后一个**持久事件序号**（断档检测用）。
     *
     * 换会话必须清空 —— 不同会话各有自己的序号序列，拿 A 的序号去比 B 的事件会**必然误判**。
     * 判据见 `data/ChatSeqMonitor.kt`。
     */
    val lastEventSeq: Int? = null,
    /**
     * 服务端最近一次下发的**订阅基线**（`chat.session.snapshot` 的 `cursor`）；
     * null = 还没收到过（旧服务端不会发，此时不做基线比较，见 ChatSeqMonitor）。
     */
    val baselineCursor: Int? = null,
    /**
     * 发送中的输入框原文；发送成功后清空，失败时保留以便还回输入框。
     *
     * 为什么需要它：输入框是本地 UI 状态，发请求在协程里，两者对不上时
     * 「用户刚打的字」就凭空消失了 —— 而用户没有任何办法找回。
     */
    val pendingText: String = "",
    /**
     * 已取回的会话图片附件字节，键是 `attachmentId`。
     *
     * 为什么要缓存：会话历史会随每次刷新重建，而图片字节要另发一次请求才拿得到。
     * 不缓存的话，每刷新一次就把所有历史图片重新下载一遍。
     */
    val attachmentImages: Map<String, ByteArray> = emptyMap(),
    /**
     * 取图失败的 id，值是**人类可读的原因**而不是裸 id。
     *
     * 刻意带原因：早期版本只有一个 `Set<String>`，界面上写死一句「图片已不可用
     * （可能已被清理）」。于是 404（附件真没了）、403（缺 files.read 授权）、
     * 401（授权失效）、解码失败在屏幕上长得一模一样 —— 用户看到「可能已被清理」，
     * 我也只能照着猜。带上原因之后，截图发回来就是答案。
     */
    val attachmentFailures: Map<String, String> = emptyMap(),
    /**
     * 待发附件条。发送时与文本一起组装成 content 数组，发送成功（或用户在输入区点删除）
     * 之后才消失。
     *
     * 刻意**不**参与「发送键是否可用」之外的任何禁用判断，更不跟 [busy] 挂钩：
     * 上一轮的缺陷正是用 [busy] 禁用发送键，导致智能体运行期间根本发不出消息。
     */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** 有文件正在上传换 `receiptId`。只用来显示进度，不用来禁用发送键。 */
    val attachmentUploading: Boolean = false,
    val error: String? = null,
)

/**
 * 实际生效的应用内字号倍数。
 *
 * 勾了「跟随系统」时固定 1f —— 系统那份放大由 `Density.fontScale` 自己带，
 * 这里若再返回用户存过的倍数就会**双重放大**（用户会看到「勾了跟随系统反而更大」）。
 * 放在状态上而不是偏好里，是为了让界面读的是**已归一化**的值：偏好层面对
 * NaN / 越界值做过兜底，但界面不该关心那件事。
 */
val HarnessState.effectiveFontScale: Float
    get() = if (fontFollowsSystem) 1f else fontScale
