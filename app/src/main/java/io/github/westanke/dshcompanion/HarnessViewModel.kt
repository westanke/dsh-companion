package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.baselineBehind
import io.github.westanke.dshcompanion.data.seqVerdict
import io.github.westanke.dshcompanion.data.EventDebouncer
import io.github.westanke.dshcompanion.data.SeqVerdict
import io.github.westanke.dshcompanion.data.RequestGate
import io.github.westanke.dshcompanion.data.InterfaceStyle
import io.github.westanke.dshcompanion.data.AppTheme
import io.github.westanke.dshcompanion.data.parseBridgeCapabilities
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.debug.DiagLog
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
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.isTurnMaxTokens
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
 * App 的**唯一 ViewModel**：持有 [HarnessState]，并把跨域共用的基础设施收在这里。
 *
 * ## 为什么拆成多个文件
 *
 * 原文件 2277 行，把「连接 + 会话 + 文件 + 插件 + 设置 + 事件流」全放在一个类里 ——
 * `docs/project/TECH-DEBT.md` 登记的第二号债（「改一处要读 1900 行」）。现在按域拆开：
 *
 * | 文件 | 职责 |
 * |---|---|
 * | `HarnessViewModel.kt`（本文件） | 状态容器、事件流接入、刷新调度、公共工具 |
 * | `HarnessModels.kt` | `HarnessState` 与跨层流转的数据载体 |
 * | `HarnessEventRules.kt` | 事件帧与输入行的纯判定（有单测） |
 * | `HarnessConnectionViewModel.kt` | 多主机 / 多地址的配对、探活、断开 |
 * | `HarnessSessionsViewModel.kt` | 会话 CRUD、工作区、发送、会话文件面板 |
 * | `HarnessInteractionsViewModel.kt` | 审批与问答 |
 * | `HarnessAttachmentsViewModel.kt` | 待发附件与历史图片 |
 * | `HarnessFilesViewModel.kt` | 文件浏览、编辑、上传下载与回收站 |
 * | `HarnessPluginsViewModel.kt` | 插件启停与模型提供商 |
 *
 * 域文件里的函数是 **[HarnessViewModel] 的扩展函数**，不是子类 —— 调用点写法完全不变
 * （`viewModel.loadDirectory()` 照样成立），因此 `ui/` 下所有界面的**行为**一行都没改。
 * 代价是原先 `private` 的若干成员要提升为 `internal`（仍只在本 module 内可见）。
 *
 * **行为零变化**：`testDebugUnitTest` 27 类 / 290 用例与拆分前完全一致。
 */
/**
 * 「文件」页与面板里**可编辑文本**的字节上限（2 MiB）。
 *
 * 图片 / PDF 各有自己的上限（见 `data/PreviewKind.kt` 的 `PREVIEW_*_MAX_BYTES`）——
 * 2 MiB 对一张手机截图太紧，但解码前整份字节都要进内存，所以按类型各给一条闸。
 */
internal const val MAX_EDIT_BYTES = 2L * 1024 * 1024

/**
 * 面板只读预览的**字符上限**。整个文件的完整内容仍然会按 [MAX_EDIT_BYTES] 读进
 * `document`（供「文件」页的编辑器使用），这里截断只是为了不让一个超大 Text
 * 把底部面板的滚动拖垮。
 */
internal const val PREVIEW_CHARS = 20_000

/** UTF-8 BOM 的三个字节；保存时要原样还回去，否则别的编辑器会认错编码。 */
internal val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/** 一段字节解成文本的结果：文本 + 有没有 BOM + 原文件用什么行尾。 */
internal data class DecodedText(val text: String, val hasBom: Boolean, val lineEnding: String)

/** `loadSessionFile` 的产物：面板要展示的结果 +「文件」页要跟着切换的状态。 */
internal data class SessionFileOutcome(
    val open: SessionFileOpen,
    val rootId: String? = null,
    val currentPath: String? = null,
    val document: OpenDocument? = null,
)

/** `chat.turn.failed` 帧落在哪一轮：`sessionId` + `turn` + `step`。 */
internal data class LiveKey(val sessionId: String, val turn: Int, val step: Int)




class HarnessViewModel(application: Application) : AndroidViewModel(application) {
    internal val keyValueStore = SharedPreferencesKeyValueStore(application)
    internal val hostStore = HostStore(keyValueStore, KeystoreSecretBox())
    internal val coordinator = ConnectionCoordinator()
    /** 旧版令牌用的是另一个 Keystore 别名，迁移时必须用它解密。 */
    internal val legacySecretBox = KeystoreSecretBox(KeystoreSecretBox.LEGACY_DEVICE_TOKEN_ALIAS)
    internal val preferences = AppPreferences(application)
    /** 正在下载中的附件 id。界面每次组合都会调 loadAttachmentImage，靠它去重。 */
    internal val pendingAttachments = mutableSetOf<String>()

    /**
     * 因**授权**失败的附件 id：允许重试。
     *
     * 与 [HarnessState.attachmentFailures] 分开，是因为「记住失败」和「允许重试」
     * 两件事不能共用一个容器 —— 把「可重试」写进界面文案前缀会漏到用户眼前，
     * 而塞进 map 的 value 里又要靠字符串嗅探，迟早出错。这里显式分一份集合。
     */
    internal val retryableAttachmentFailures = mutableSetOf<String>()
    internal val mutableState = MutableStateFlow(HarnessState().withPreferences())
    val state: StateFlow<HarnessState> = mutableState.asStateFlow()

    /**
     * 连接页门闩：**进程级**，刻意不放进 [HarnessState]。
     *
     * [HarnessState] 在三个地方被整份重建：初始、[disconnect]、[loadSnapshot]（见 [withPreferences]）。
     * 把门闩塞进那份状态，连上之后就会被重建冲掉 —— 用户看到的是「刚进聊天页又被弹回连接页」。
     * 放这里则只有一个来源能改变它：[passConnectGate]。
     *
     * 判据刻意**不是** [HarnessState.connected]。冷启动会先自动重连最近用过的那台电脑，
     * 连上之后 connected 就是 true；拿它当放行条件，等于又跳过了连接页 ——
     * 而「启动无论怎样，第一屏都必须是连接页」正是这次要修的毛病。
     */
    internal val connectGatePassed = MutableStateFlow(false)
    val connectGate: StateFlow<Boolean> = connectGatePassed.asStateFlow()

    /**
     * 放行连接页，进入 App。
     *
     * 只由**用户的主动动作**调用：连接已保存的电脑、导入配置文本、手动配对，
     * 三处都在**发起时**调用（见 ConnectScreen 的 `onEnterApp`）。
     * 失败时 connected 仍为 false，界面自然还停在连接页，语义自洽。
     * 重复调用无害（幂等）。
     */
    fun passConnectGate() { connectGatePassed.value = true }

    /**
     * 把偏好里存着的设置盖到状态上。
     *
     * [HarnessState] 在三个地方被整个重建：初始、[disconnect]、[loadSnapshot]。
     * 每一处都必须带上这些字段 —— 漏一处，用户就会发现「我的设置又变回去了」。
     * 实际上此前三处全都没带，于是 `genUiDebug` 从来没被恢复过、
     * 而 `busySendMode` 每次断开连接都会被打回默认。
     *
     * 与其在三个构造点各抄一遍（加字段时必然漏），不如统一走这里。
     */
    internal fun HarnessState.withPreferences(): HarnessState = copy(
        busySendMode = preferences.busySendMode,
        genUiDebug = preferences.genUiDebug,
        useWebChat = preferences.useWebChat,
        showContext = preferences.showContext,
        // 诊断日志开关同样要在这里盖：漏了它，切一次连接就被打回默认值（关）——
        // 而那时日志可能还在写，用户会以为「关了还在记」，比不记更难排查。
        diagLog = preferences.diagLog,
        // 外观三项同样必须在这里盖 —— 漏了它们，用户切一次连接
        // 就会看到主题被打回「跟随系统」、字号被打回 1.0（与上面那段注释同一个坑）。
        interfaceStyle = preferences.interfaceStyle,
        // 配色主题同理：漏了它，用户切一次连接，自己挑的暖米/青灰就被打回纸感。
        appTheme = preferences.appTheme,
        fontScale = preferences.fontScale,
        fontFollowsSystem = preferences.fontFollowsSystem,
    )

    /**
     * 当前活跃连接。null 表示未连接。
     *
     * 旧实现是一个可变的 `client` 字段；改成「活跃连接」是因为现在一个主机可能有多个地址，
     * 光有 client 无法回答「我现在连的是哪台电脑的哪个地址」—— 而这正是诊断面板和
     * 后续自动切换都需要的。
     */
    internal var active: ActiveConnection? = null
    /**
     * 请求代际闸门：换会话 / 换主机 / 断开时作废在飞请求的结果。
     * 它挡的是「旧会话的响应回来覆盖新会话状态」这类**静默错乱**（详见 `data/RequestGate.kt`）。
     */
    internal val requestGate = RequestGate()
    /**
     * 目录刷新的**去抖**闸门。
     *
     * 旧实现是「每来一帧 `file.*` 就发一次 `entries()` 请求」。事件是逐个到的，
     * 而内核批量改写文件（构建产物、格式化多个文件、同步一批文件）时会连着发很多帧 ——
     * 于是一个本该「事件驱动的界面」变成了**事件驱动的轮询风暴**：手机连发几十个目录请求，
     * 服务端逐个应答，电量与流量都白烧。
     *
     * 窗口取 500ms（比 history 那一路的 400ms 略长）：目录变化没那么紧急，
     * 而用户正在浏览时多看半秒旧列表完全无感。语义与取舍见 `data/EventDebouncer.kt`。
     */
    internal val directoryDebouncer = EventDebouncer(viewModelScope, 500L) { loadDirectory() }
    internal val client: DshClient? get() = active?.client

    /**
     * 当前活跃连接；null 表示未连接。
     *
     * 暴露它是为了让 WebView 版聊天页复用同一条链路：页面里的 REST 调用
     * 交给 [io.github.westanke.dshcompanion.web.ChatWebBridge] 转发到
     * `DshClient`，于是多地址选路、令牌注入、超时与重试都白拿，
     * 不必在 JS 里另开一条会把令牌写进页面的 fetch。
     *
     * 只读：拿到的人可以调接口，但改不了选路结果——选路仍由本类决定。
     */
    fun activeConnection(): ActiveConnection? = active
    internal var eventSubscription: Closeable? = null
    /**
     * 事件流是否因熄屏而主动暂停（见 [pauseEvents]）。
     *
     * 用它区分「用户主动断开」与「熄屏暂停」：前者 `active` 已被清空、不该被
     * [resumeEvents] 复活；后者连接还在，亮屏就该接回去。没有这个标志的话，
     * 亮屏会把用户刚刚手动断开的连接又拉起来。
     */
    @Volatile internal var eventsPaused = false
    internal var chatRefreshJob: Job? = null
    @Volatile internal var chatRefreshPending = false
    @Volatile internal var chatSessionRefreshPending = false
    @Volatile internal var liveFinalizeTarget: LiveKey? = null
    // WS 优先改造（P2）的装配状态。实现全在 HarnessWsPortsViewModel.kt。
    internal var wsTransport: io.github.westanke.dshcompanion.data.ws.MobileWsTransport? = null
    internal var wsRouter: io.github.westanke.dshcompanion.data.ws.WsRequestRouter? = null
    internal var wsEventPort: io.github.westanke.dshcompanion.data.ws.WsEventPort? = null
    internal var wsSubscribedSessionId: String? = null
    internal var ports: PortSet? = null

    /** P3 三域（HITL / 命令 / 模型）的自适应端口。实现全在 HarnessDomainPortsViewModel.kt。 */
    internal var domainPorts: DomainPorts? = null

    init {
        // 诊断日志开关：冷启动后到用户进设置页之前，会话页可能已被点开好几轮 ——
        // 那几次正是要观察的对象。放在 init 里，开关是「开」的包从第一条日志起就记。
        // 理由与 MainActivity.onCreate 那两行的分工见 HarnessDiagViewModel.kt 顶部。
        syncDiagLogFromPreferences()
        restoreConnection()
    }

    fun clearError() = update { copy(error = null) }

    /** 走全局错误通道（App 层会转成 snackbar）；文案在这里就已经是人话。 */
    internal fun notify(message: String) = update { copy(error = message) }

    /**
     * 附件相关的提示必须是本地化文案，而构造参数 `application` 不是属性，
     * 只能经 `getApplication()` 拿。这两句是要用户照做的（「先压缩再试」），
     * 不能退化成一句 `IMAGE_TOO_LARGE`。
     */
    internal fun appString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    /** 当前选中的会话是否正在运行（决定「排队 / 插话」有没有区别）。 */
    fun selectedSessionRunning(): Boolean {
        val id = mutableState.value.selectedSessionId ?: return false
        return isSessionRunning(id)
    }

    /**
     * 这个会话是否正在运行。
     *
     * 刻意不限于「当前选中的会话」：`chat.turn.end` 判「这一轮刚结束」时，
     * 用的是事件里的那个 sessionId —— 它可能不是当前正在看的那个
     * （比如用户切了会话、另一个会话这时才跑完）。
     *
     * 会话列表里查不到时**返回 false**：那份列表来自快照，可能还没刷新到这一轮，
     * 此时把它当成「已结束」只会多补拉一次 GET，不会漏 —— 反过来若当成还在跑，
     * 兜底就永远不触发，那才是真的漏。
     */
    internal fun isSessionRunning(sessionId: String): Boolean =
        mutableState.value.sessions.firstOrNull { it.id == sessionId }?.running == true

    /** 改「智能体运行时」的默认提交行为，并立刻落盘（下次启动保持）。 */
    fun setBusySendMode(mode: BusySendMode) {
        preferences.busySendMode = mode
        update { copy(busySendMode = mode) }
    }

    /** 会话详情页是否走 WebView 版（assets/chat.html）。立刻生效，不需要重启。 */
    fun setUseWebChat(enabled: Boolean) {
        preferences.useWebChat = enabled
        update { copy(useWebChat = enabled) }
    }

    /**
     * 聊天页是否显示「上下文注入」块。默认关 —— 那些是系统每轮塞进来的东西，
     * 手机端没人看，却会在每条消息之间插一排折叠块。
     * 改了立刻生效：ChatWebScreen 有一个 effect 专门把它推给页面。
     */
    fun setShowContext(enabled: Boolean) {
        preferences.showContext = enabled
        update { copy(showContext = enabled) }
    }

    /** 开关 dsh-ui 诊断行；立刻生效，不需要重启。 */
    fun setGenUiDebug(enabled: Boolean) {
        preferences.genUiDebug = enabled
        update { copy(genUiDebug = enabled) }
    }

    /**
     * 开关诊断日志（窗口 / 会话操作 / 屏幕变化）。
     *
     * 实现在 `HarnessDiagViewModel.kt`（行数预算），这里只转发，调用点写法不变。
     */
    fun setDiagLog(enabled: Boolean) = setDiagLogImpl(enabled)

    /**
     * 改界面明暗风格；立刻生效（主题在 App 顶层读状态，改完下一帧就是新配色）。
     *
     * 与「语言」不同：语言要重建 Activity 才彻底生效（系统 Locale 是进程级配置），
     * 所以那条留在设置页用 AppCompatDelegate 走；明暗与字号都是纯 Compose 状态。
     */
    fun setInterfaceStyle(style: InterfaceStyle) {
        preferences.interfaceStyle = style
        update { copy(interfaceStyle = style) }
    }

    /**
     * 改**配色主题**（PAPER / WARM / MIST / INK）；立刻生效，不需要重启。
     *
     * 与 [setInterfaceStyle] 同一条路：偏好写盘 + 状态更新，两者必须一起做 ——
     * 只写偏好界面不会变（不是 Compose 状态），只改状态重启后会丢。
     * 主题在 `MainActivity` 的 `setContent` 最外层消费，所以改完**下一帧**全 App 换色，
     * 连接页、会话页、设置页同时变，不会出现「设置页变了、退回去还是旧色」的半截状态。
     */
    fun setAppTheme(theme: AppTheme) {
        preferences.appTheme = theme
        update { copy(appTheme = theme) }
    }

    /** 改应用内字号倍数；立刻生效，取值由偏好层收敛到 0.8–1.5。 */
    fun setFontScale(scale: Float) {
        preferences.fontScale = scale
        update { copy(fontScale = preferences.fontScale) }
    }

    /**
     * 字号是否完全跟随系统。
     *
     * 打开时**不清空**用户已调过的倍数：来回切换应当能回到原来那档，
     * 而不是每次都要重新拖一遍滑块（滑块位置读的就是那个被保留的值）。
     */
    fun setFontFollowsSystem(enabled: Boolean) {
        preferences.fontFollowsSystem = enabled
        update { copy(fontFollowsSystem = enabled) }
    }

    /**
     * 记下「用户已经主动选过界面语言」。
     *
     * 真正的语言切换仍由 UI 层的 `setAppLanguage` 走 `AppCompatDelegate`
     * （它要重建 Activity 才彻底生效，不是 Compose 状态）。这里只落一个标志，
     * 让下次冷启动**不要再**替用户做决定 —— 否则「跟随系统」会被打回中文。
     */
    fun markLanguageChosen() {
        preferences.languageChosen = true
    }

    /**
     * 熄屏暂停事件流；亮屏恢复（用户定下的策略：**熄屏=断连，亮屏=实时**）。
     *
     * 为什么不是断开连接：`disconnect()` 会把 `active` 一起清掉，亮屏后得重新
     * 走一遍探活 + 配对校验（还要弹「恢复中」），代价远大于收益。熄屏时真正费电的
     * 只有那条 WebSocket —— WiFi 射频与 TCP keepalive 全程在线、后台还会被系统
     * 反复唤醒。所以只关流、**保留已建立的连接与令牌**，亮屏时直接把流接回去。
     *
     * 为什么听 `ACTION_SCREEN_OFF` 而不是 `onStop`：切到别的 App（分屏、切后台
     * 但屏幕还亮着）不该断 —— 用户看得到屏幕，实时性仍然有价值；而合上盖子/按电源键
     * 熄屏时，用户看不到内容，保持连接纯属白耗电。用广播能精确区分这两种情形。
     *
     * 恢复时**必须补一次快照**：熄屏期间事件流是关着的，期间落盘的消息一条都没收到，
     * 不补的话亮屏后看到的是熄屏那一刻的旧内容，得等下一次事件来才刷新
     * （用户会以为「手机卡住了」）。这里复用既有的 [scheduleChatRefresh]，
     * 它自带 400ms 静默去抖与单飞，连发几次也只拉一次。
     */
    fun pauseEvents() {
        if (eventSubscription == null) return
        eventsPaused = true
        eventSubscription?.close()
        eventSubscription = null
        // 状态里保留 connected=true：连接本身（主机、令牌、端点）都还在，
        // 只是事件流暂停。把它标成未连接会让界面闪一下「已断开」再跳回来。
    }

    fun resumeEvents() {
        if (!eventsPaused) return
        eventsPaused = false
        val connection = active ?: return
        connectEvents(connection.client)
        // 补上熄屏期间错过的一切：当前会话的历史 + 会话列表。
        chatSessionRefreshPending = true
        scheduleChatRefresh()
        // 还要单独补两类**交互性**状态：审批面板与待答问题。
        //
        // 它们不走 history —— 熄屏期间若正好有审批在等、或 Agent 提问了，
        // 事件流是关着的，这两张卡不会自己出现。用户亮屏后看到的是一片安静，
        // 而电脑端其实在等他的决定 —— 「我明明没看到有东西问我」是最坏的体验。
        // 这两个方法各自带「会话切换后丢弃结果」的保护（比对 selectedSessionId），
        // 直接调用是安全的。
        refreshPendingApprovals()
        refreshPendingQuestions()
    }

    /**
     * 打开事件通道（**WS 优先，REST 兜底**，见 `HarnessWsPortsViewModel.kt`）。
     *
     * 语义与 P2 改造前一致：老的 SSE 流照旧由 `api.events(...)` 建立，
     * 只是现在多了一步「先试着把 WS 挂上」。`ChannelState` 判不了 WsReady/WsDegraded 时，
     * [reconcileEventChannel] 走的就是 `openRestEvents(api)` —— 与改造前逐字节相同。
     *
     * listener 主体搬到了 `HarnessWsPortsViewModel.kt` 的 [handleWorkspaceEvent]：
     * VM 已顶到 800 行预算，而那段有 187 行，不搬塞不进来。搬迁是逐字的，未改一行逻辑。
     */
    internal fun connectEvents(api: DshClient) {
        eventSubscription?.close()
        eventSubscription = null
        connectWsTransport(api)
        // connectWsTransport 内部的 reconcileEventChannel 只在 WS 可用时挂流；
        // 这里兜底保证「WS 不可用」时也一定有一条 REST 流在跑。
        if (eventSubscription == null) {
            eventSubscription = openRestEvents(api)
            reconcileEventChannel()
        }
    }

    /**
     * 拉一次完整快照。
     *
     * 是 `suspend` 而不是普通函数：会话列表现在经 [sessionPort] 走（WS 可用时用 `sessions` 帧），
     * 而 Port 接口是 suspend 的。两个调用点（`refreshDashboard` 与连接建立）本来就都在
     * 协程里、且已切到 `Dispatchers.IO`，加个 suspend 不改变任何执行上下文。
     */
    /**
     * @param base 上一份状态。快照只负责**服务端字段**，其余（连接级运行态、会话正文、
     *   面板开关、权限目录、目标记忆……）一律从 [base] 原样带过来。
     *
     *   为什么必须有这个参数：`HarnessState` 有 72 个字段，而快照只填 16 个。原来是
     *   `HarnessState(...)` 全新构造，于是每次刷新（顶栏刷新 / 导航面板刷新，共三处
     *   `refreshDashboard`）都会把另外 56 个字段打回默认值 —— 用户点一下刷新就"掉线"
     *   （`eventsConnected`）、权限档位切换消失（`permissionCatalog`）、「关于」页
     *   显示版本未知（`bridgeVersion`）、定时任务入口凭空消失（`bridgeCapabilities`）、
     *   正在看的对话正文变空（`history`）。此前只在调用点手工补了 2 个字段，
     *   剩下的 54 个仍在漏 —— 加一个补一个必然漏，所以改成"默认全带、只覆盖服务端那份"。
     */
    internal suspend fun loadSnapshot(
        api: DshClient,
        device: DeviceView,
        base: HarnessState = HarnessState(),
    ): HarnessState {
        val roots = if ("files.read" in device.scopes) api.roots() else emptyList()
        val canReadChat = "chat.read" in device.scopes
        val sessions = if (canReadChat) sessionPort(api).sessions() else emptyList()
        val chatWorkspaces = if (canReadChat) api.chatWorkspaces() else emptyList()
        val agentPresets = if (canReadChat) api.agentPresets() else emptyList()
        val canReadSettings = "settings.read" in device.scopes
        val selectedRoot = roots.firstOrNull()?.id
        val directory = selectedRoot?.let { api.entries(it) }
        return base.copy(
            restoring = false,
            connected = true,
            endpoint = api.endpoint,
            endpointDraft = api.endpoint.removeSuffix("/api/v1"),
            healthOk = true,
            device = device,
            roots = roots,
            sessions = sessions,
            chatWorkspaces = chatWorkspaces,
            agentPresets = agentPresets,
            providerSettings = if (canReadSettings) api.providerSettings() else null,
            pluginInventory = if (canReadSettings) api.pluginInventory() else null,
            settingsNamespaces = if (canReadSettings) loadSettingsNamespaces(api) else null,
            selectedRootId = selectedRoot,
            directory = directory,
            trash = if ("files.read" in device.scopes) api.trash() else emptyList(),
        ).withPreferences()   // 快照是整份重建的，偏好字段必须重新盖回去
    }

    internal fun scheduleChatRefresh() {
        chatRefreshPending = true
        if (chatRefreshJob?.isActive == true) return
        chatRefreshJob = viewModelScope.launch {
            // 去抖窗口，不是轮询周期。
            //
            // 旧实现是 `delay(1_000)` 之后回 while 条件，而**每一个事件帧都会把
            // chatRefreshPending 重新置 true**（事件分发末尾那个 `else ->` 兜底分支
            // 什么都命中）。于是模型流式输出期间，这一秒内只要来过任何一帧，循环就
            // 继续转 —— 实际表现是**整个流式期间每秒拉一次全量 history**，几百条消息
            // 反复下载。用户侧的体感就是「一用 APP 电量掉得快」。
            //
            // 现在改成：等到**安静 400ms** 才拉一次，且一轮只拉一次。流式输出期间
            // 事件不断到来，窗口一直被重置、任务一直不起跑；等这一轮消息落定、
            // 事件安静下来，才做那唯一一次 history() —— 屏幕上该有的内容一次到位，
            // 实时过程由 liveChat 那一路负责渲染（见 renderLive），不必靠轮询补。
            //
            // 400ms 是取舍：短于它会漏掉「最后一条消息落盘」的信号（那就得再补一次），
            // 长于它用户会看到正文延迟。liveChat 的 delta 推送不受这个窗口影响。
            delay(400)
            while (chatRefreshPending || chatSessionRefreshPending) {
                chatRefreshPending = false
                val refreshSessions = chatSessionRefreshPending
                chatSessionRefreshPending = false
                val finalizeTarget = liveFinalizeTarget
                liveFinalizeTarget = null
                val api = client ?: break
                val id = mutableState.value.selectedSessionId
                if (id != null) {
                    // 走 Port：WS 可用时用 history 帧（分页/刷新都带 beforeSeq=null，
                    // 所以这里仍走 REST —— 首屏需要 projections，WS 帧不带）。
                    runCatching { withContext(Dispatchers.IO) { sessionPort().history(id) } }
                        .onSuccess { history ->
                            if (mutableState.value.selectedSessionId == id) update {
                                val clearLive = finalizeTarget != null && liveChat?.let {
                                    it.sessionId == finalizeTarget.sessionId && it.turn == finalizeTarget.turn && it.step == finalizeTarget.step
                                } == true
                                copy(history = history, liveChat = if (clearLive) null else liveChat)
                            }
                        }
                        .onFailure { error ->
                            if (error !is DshApiException || error.code != "RATE_LIMITED") {
                                update { copy(error = messageOf(error)) }
                            } else {
                                if (finalizeTarget != null) liveFinalizeTarget = finalizeTarget
                                chatRefreshPending = true
                            }
                        }
                }
                if (refreshSessions) {
                    runCatching { withContext(Dispatchers.IO) { sessionPort().sessions() } }
                        .onSuccess { sessions -> update { copy(sessions = sessions) } }
                        .onFailure { error ->
                            if (error is DshApiException && error.code == "RATE_LIMITED") {
                                chatSessionRefreshPending = true
                            } else {
                                update { copy(error = messageOf(error)) }
                            }
                        }
                }
                // 限流重试之外的正常路径到这里就该收工了。
                // 保留一个短的「再确认一次」窗口：处理 history 的这段时间里可能又来了新帧，
                // 那一帧之后仍然安静 400ms 才算真正静默，这里给的是这个静默窗口，不是轮询周期。
                delay(400)
                if (!chatRefreshPending && !chatSessionRefreshPending) break
            }
        }
    }

    internal fun update(transform: HarnessState.() -> HarnessState) {
        mutableState.update { current -> current.transform() }
    }

    override fun onCleared() {
        chatRefreshJob?.cancel()
        directoryDebouncer.cancel()
        eventSubscription?.close()
        // WS 传输自带退避重连协程：不关掉它会在 ViewModel 销毁后继续拨号。
        closeWsTransport()
        // 协调层自带后台 scope 来跑阻塞式健康检查（协程取消打不断 OkHttp 的阻塞调用），
        // 不显式关闭的话，探活期间挂死的调用会一直占着 IO 线程直到 OkHttp 自身超时。
        coordinator.close()
        super.onCleared()
    }

    /**
     * 异常 → 给人看的句子。分三层：
     *
     * 1. [DshApiException]（服务端错误码）→ 按码查 [localizedErrorRes] 本地化表；
     * 2. 网络层 IOException 家族 → 按**异常类型**本地化（以前直接露英文原文）。
     *    **子类必须在父类 [SocketException] / [IOException] 之前判断**。
     * 3. 其余未知异常 → 保留原文：能搜、能贴给开发者（认出不硬猜）。
     *
     * 实现在 `HarnessErrorText.kt`（行数预算），这里只转发。
     */
     internal fun messageOf(error: Throwable): String = messageOfImpl(this, error)

     /**
      * 把服务端那句英文错误换成用户语言的句子（表在 `data/ErrorMessages.kt`）。
      *
      * 认不出的错误码**保留原文**：编一句像模像样的话只会把人带偏，而原文还能搜、能贴给开发者。
      * 以前这里一律拼成 `CODE: English message.`，于是中文界面下也全是英文。
      *
      * 实现同样搬去了 `HarnessErrorText.kt`（行数预算；调用点写法不变）。
      */
     internal fun localizedError(code: String?, raw: String): String =
         localizedErrorImpl(this, code, raw)

}
