package io.github.westanke.dshcompanion.ui.chat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import io.github.westanke.dshcompanion.data.ChatDisplayEntry
import io.github.westanke.dshcompanion.data.groupChatDisplay
import io.github.westanke.dshcompanion.debug.DiagLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.ApprovalUiState
import io.github.westanke.dshcompanion.PendingAttachment
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.SessionFileOpen
import io.github.westanke.dshcompanion.retryLastUserMessage
import io.github.westanke.dshcompanion.forkSession
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatAttachment
import io.github.westanke.dshcompanion.data.ChatTurnFailure
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.truncationItems
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.ToolCallDetail
import io.github.westanke.dshcompanion.data.formatToolDuration
import io.github.westanke.dshcompanion.data.prettyToolArguments
import io.github.westanke.dshcompanion.data.LiveChatBlock
import io.github.westanke.dshcompanion.data.ChatLinkTarget
import io.github.westanke.dshcompanion.data.ChatQuestion
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.AgentPreset
import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.FileEntry
import io.github.westanke.dshcompanion.data.MentionQuery
import io.github.westanke.dshcompanion.data.ComposerSendPolicy
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.ModelView
import io.github.westanke.dshcompanion.data.PendingApproval
import io.github.westanke.dshcompanion.data.PendingQuestionSet
import io.github.westanke.dshcompanion.data.PermissionSelect
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.MessageSegment
import io.github.westanke.dshcompanion.data.parseGenUi
import io.github.westanke.dshcompanion.data.splitMessageBody
import io.github.westanke.dshcompanion.data.GenUiDiagnosis
import io.github.westanke.dshcompanion.data.diagnoseGenUi
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.previewKindOf
import io.github.westanke.dshcompanion.data.HtmlBlock
import io.github.westanke.dshcompanion.data.extractHtmlBlocks
import io.github.westanke.dshcompanion.data.ResolvedPath
import io.github.westanke.dshcompanion.data.SessionFileRef
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.SessionFileRefs
import io.github.westanke.dshcompanion.data.deliverables
import io.github.westanke.dshcompanion.data.linkifyKnownFiles
import io.github.westanke.dshcompanion.data.TodoItem
import io.github.westanke.dshcompanion.data.displayItems
import io.github.westanke.dshcompanion.data.visibleHistoryItems
import io.github.westanke.dshcompanion.data.permissionSelect
import io.github.westanke.dshcompanion.data.todoItems
import java.io.InputStream
import io.github.westanke.dshcompanion.ui.EmptyState
import io.github.westanke.dshcompanion.ui.StatusLabel
import io.github.westanke.dshcompanion.answerQuestions
import io.github.westanke.dshcompanion.attachFile
import io.github.westanke.dshcompanion.attachImage
import io.github.westanke.dshcompanion.cancelRun
import io.github.westanke.dshcompanion.closeSessionFile
import io.github.westanke.dshcompanion.decideApproval
import io.github.westanke.dshcompanion.loadAttachmentImage
import io.github.westanke.dshcompanion.loadOlderHistory
import io.github.westanke.dshcompanion.retryMentionEntries
import io.github.westanke.dshcompanion.openSessionFile
import io.github.westanke.dshcompanion.refreshHistory
import io.github.westanke.dshcompanion.removePendingAttachment
import io.github.westanke.dshcompanion.selectPermissionPreset
import io.github.westanke.dshcompanion.sendMessage
import io.github.westanke.dshcompanion.setComposerOverlay

/**
 * 会话**详情页**骨架：顶栏、消息流，以及各区间的挂载点。
 *
 * 这个文件只放「什么挂在哪儿、按什么顺序」，真正的组件都在同包的兄弟文件里：
 * 列表层 [SessionPane] · 消息项 [ChatItem] · 审批/问答 [ApprovalPanel] / [QuestionCard] ·
 * 输入区/思考条 [Composer] / [ChatThinkBar] · 会话文件面板 [SessionFilesSheet]。
 *
 * 从 `ui/ChatScreen.kt`（3882 行）拆出（曾是 TECH-DEBT 第一号债），实现逐字未改、
 * 只按 `webview-port-todo.md` 的 PORT-* 分组换了所在文件。
 */
/** 每拍下推的像素与间隔：把「比一屏高的最后一项」整段推进视口。 */
private const val SCROLL_DOWN_PX = 600f
private const val SCROLL_TICK_MS = 32L
/** 每轮落底后额外下推的像素：把「比一屏高的最后一项」的剩余部分带进视口。 */
private const val VIEWPORT_PROBE_PX = 1200f

@Composable
internal fun ChatDetail(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    wide: Boolean = false,
) {
    // ── 诊断插桩（debug 包独有，release 下 DiagLog 全静默）───────────────
    // 与 `ui/ChatScreen.kt` 的路由日志配对：那条记「声明要走原生」，
    // 这条记「原生页真的被组合了」。两条都出现 = 原生页确实在渲染，
    // 那么「effect 没跑」就是原生页内部的问题；只有路由那条而没有这条，
    // 说明用户看到的其实是 WebView 页（`state.useWebChat` 为真），
    // 上一轮加在这里的 Log.i 探针一条不打是必然结果。
    //
    // 每进入/切走一次会话打一条：selectedSessionId 是判据，与下方
    // `remember(selected.id)`、`key(selected.id)` 的重建时机天然对齐。
    val selected = state.sessions.find { it.id == state.selectedSessionId }
    if (selected == null) {
        // 空态也要留证据，但它只在「真的没选中会话」时出现一次 —— 用 effect 打，
        // 免得每次重组都刷（真机实测：插桩第一版在组合体里直接打日志，
        // 一次进会话就刷出 300+ 条 COMPOSE，把 ROUTE/SCROLL 这些有用的淹了）。
        LaunchedEffect(state.selectedSessionId) {
            DiagLog.i(
                DiagLog.Cat.COMPOSE,
                "ChatDetail 空态：selectedSessionId=${state.selectedSessionId} 未匹配到会话",
            )
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.select_session))
        }
        return
    }
    // 只在**进入这个会话时**打一条，不是每次重组都打。
    //
    // 为什么用 LaunchedEffect(selected.id) 而不是直接写在组合体里：后者一次进会话
    // 就刷出 300+ 条（真机实测 2026-10-18），ROUTE / SCROLL 那些真正要看的被淹掉。
    // 重组次数本身是另一个话题，不该由这条日志承担。
    LaunchedEffect(selected.id) {
        DiagLog.i(
            DiagLog.Cat.COMPOSE,
            "ChatDetail 渲染：会话 ${selected.id}（wide=$wide useWebChat=${state.useWebChat}）",
        )
    }
    // 待确认的分叉点由**这一层**持有：消息流里同时只可能有一个待确认的分叉，
    // 让每条消息各持一个对话框状态，只会让它们互相看不见。
    var forkAtSeq by remember { mutableStateOf<Int?>(null) }

    // 输入框内容存在 ViewModel 而不是本地 state：发送失败时要把原文还回来，
    // 而「失败」这个事只有 ViewModel 知道。本地 remember 一旦被 message = "" 清掉就找不回了。
    var message by remember(selected.id) { mutableStateOf(state.pendingText) }
    // 发送失败时 ViewModel 会把原文写回 pendingText，这里跟着回填输入框。
    // 用 LaunchedEffect 而不是把 message 直接绑成 state.pendingText：
    // 后者会让用户正在输入的字被发送成功后的清空覆盖掉，敲一半的字会凭空蒸发。
    LaunchedEffect(state.pendingText) {
        if (state.pendingText.isNotBlank()) message = state.pendingText
    }
    // 分叉的确认框拆到 ChatForkDialog.kt（同一个原因：行数预算）。文案也只有那一处。
    forkAtSeq?.let { seq ->
        ChatForkDialog(
            onDismiss = { forkAtSeq = null },
            onConfirm = {
                forkAtSeq = null
                state.selectedSessionId?.let { viewModel.forkSession(it, seq) }
            },
        )
    }
    // 输入框上方的补全面板（`@` 文件 / `/` 命令）开着时，**关掉外层抽屉的横向滑动手势**。
    //
    // 这个浮层贴着输入框、又长又宽，用户在它上面横向划一下（想看被截断的长文件名，
    // 或者只是手抖），`ModalNavigationDrawer` 就把「会话/文件/插件/模型/设置」拉出来了 ——
    // 两者在同一个手势通道上抢触摸，只能在这里把「面板开着」告诉外层。
    // 判据与真正弹出面板的那两处**共用同一个函数**，不会漂移。
    val composerOverlayActive = MentionQuery.activeStart(message) != null ||
        commandSuggestions(message, state.commands).isNotEmpty()
    LaunchedEffect(composerOverlayActive) { viewModel.setComposerOverlay(composerOverlayActive) }
    // 会话切走/页面离开时面板自然消失，但状态还留在 ViewModel 里，会让抽屉手势一直失效 ——
    // 那比误触更难发现（用户只会觉得「抽屉划不开了」）。所以离开时主动清掉。
    DisposableEffect(Unit) {
        onDispose { viewModel.setComposerOverlay(false) }
    }
    var modelSheet by remember(selected.id) { mutableStateOf(false) }
    var filesSheet by remember(selected.id) { mutableStateOf(false) }
    var statsSheet by remember(selected.id) { mutableStateOf(false) }
    var trajectorySheet by remember(selected.id) { mutableStateOf(false) }
    var tasksExpanded by rememberSaveable(selected.id) { mutableStateOf(true) }

    // 链接分发器已拆到 ChatLinkHandlers.kt（含相对路径按会话 cwd 解析，2026-10-19）。
    // 点链接打开文件时**必须同时把面板打开**：`openSessionFile` 只加载数据，
    // 面板由 filesSheet 控制 —— 只加载不开面板，用户看到的就是「点了没反应」。
    val linkHandler = rememberSessionLinkHandler(viewModel, selected.cwdAbsolute) { path ->
        viewModel.openSessionFile(path)
        filesSheet = true
    }
    // 流式这一段也按同一个口径拆开：思考不进消息流，交给底部那条固定的思考条
    // （[ChatThinkBar]）—— 它是「运行状态」而不是「会话内容」。
    val liveChat = state.liveChat?.takeIf { it.sessionId == selected.id }
    val liveItems = liveChat?.displayItems().orEmpty().filter { it.kind != ChatItemKind.REASONING }
    // 思考条只承载**流式这一段**：历史里的 REASONING 在下面已经被过滤掉了，
    // 于是翻历史时 liveChat 为 null，这一条自然整条不显示。
    val liveThinking = liveChat?.let { liveThinkingText(it.blocks) }.orEmpty()

    // 历史里的两类块按 WebView 页的口径过滤（对齐两个模式的观感）：
    //
    // ① 思考过程不再显示 —— 它是给我自己看的，翻历史的人不读。
    //    流式期间那一段也不再进列表：改由底部思考条承载（见 [ChatThinkBar]）。
    //    也就是说，思考在任何时候都不是「会话内容」。
    // ② 上下文注入默认隐藏 —— 系统每轮塞进来的运行上下文/时间上下文/后台任务通知，
    //    会在每条消息之间插一排折叠块，把正文挤得七零八落。
    //    开关在设置页（AppPreferences.showContext），默认关。
    val showContext = state.showContext
    // 投影结果按 (history, showContext) 缓存 —— `displayItems()` 是**逐事件映射的纯函数**，
    // 而这一行每次重组都会跑到：流式期间每秒几十帧，每帧都要把几百条历史事件重新映射一遍
    // 再跑两次 filter，纯属白烧 CPU。`state.history` 是整份替换的对象（只有刷新才换），
    // 所以引用相等就是「没变」的可靠判据。
    //
    // `liveItems` 刻意**不**缓存：它每一帧都会变（append 出的是新对象），加了也是每帧失效的
    // 假优化；而它只有当前 step 的那几个块，成本本来就小。
    //
    // 过滤口径本身在 data/ChatVisibleItems.kt —— 抽出去之后有单测盯着，不再只靠肉眼。
    val historyItems = remember(state.history, showContext) {
        visibleHistoryItems(state.history, showContext)
    }
    // 正文里出现的**已知会话文件**（模型常直接写文件名而不做成 Markdown 链接）
    // 在这里就地变成可点链接：用户点一句「已落盘：xxx.md」就能打开，
    // 而不是像 2026-10-19 真机反馈的那样「点了完全没反应」。
    // 只认唯一文件名、只替换第一次出现，同名多路径一律跳过（宁可不点，也不打开错的文件）。
    val knownFilePaths = remember(state.history) {
        sessionFileList(state.history).map { it.path }
    }
    val displayItems = (historyItems + liveItems + state.truncationItems(selected.id)).map { item ->
        if (item.kind == ChatItemKind.USER || item.kind == ChatItemKind.ASSISTANT) {
            val linked = linkifyKnownFiles(item.body, knownFilePaths)
            if (linked == item.body) item else item.copy(body = linked)
        } else {
            item
        }
    }

    val todos = remember(state.history) { state.history?.todoItems().orEmpty() }
    // 滚动状态**跟着会话走**：换会话就换一个新的 listState。
    //
    // 否则会有两个毛病：① 进新会话停在**顶部**（真机反馈「每次进入会话默认在当前页面顶段，
    // 不符合对话性智能体的特点」）—— rememberLazyListState() 的默认初始项就是 0；
    // ② 新会话会**继承**上一个会话的滚动位置，滑到一半切换时位置莫名其妙。
    //
    // 用 `remember(selected.id) { ... }`（会话 id 变就重建）而不是在 LaunchedEffect 里
    // 手动 scrollToItem(0)：后者只改位置、不改 listState 本身，遇到「内容比屏幕短」的
    // 会话仍然对不齐。（`key(...)` 那个写法在这里编译不过 —— 它要包住一段组合，
    // 而这里只需要一个「随 key 重建的值」，remember 的 key 参数正是干这个的。）
    // （`remember(selected.id) { rememberLazyListState() }` 不行：那个 lambda 不是 @Composable
    //   上下文，里面调不了 rememberLazyListState。用 key(...) 把**使用** listState 的那段
    //   组合包起来才对 —— 但它下面就是 LazyColumn 本体，包一层即可。）
    val listState = key(selected.id) { rememberLazyListState() }

    // 滚动期间冻结渲染快照（症状、做法与 80ms 窗口的取舍都在 ChatScrollFreeze.kt 里）。
    val scrolling = listState.isScrollInProgress
    val visibleItems = rememberFrozenItems(displayItems, scrolling)

    // 把扁平项折成「消息 + 过程组」两类。理由与规则见 ChatProcessGroup.kt 顶部注释。
    //
    // `remember(visibleItems)`：分组是 O(n) 的纯函数，而 visibleItems 在流式期间
    // 每帧都会换成一个新 list —— 不 remember 的话每帧都要重算一遍分组，
    // 那正是「为了省重组反而更慢」的经典翻车。时间从 item 自己身上取
    // （ChatDisplayItem.time，历史项有、流式项为 null → 该组不显示耗时段）。
    val displayEntries = remember(visibleItems) {
        groupChatDisplay(visibleItems) { it.time }
    }
    // 用户是否在底部附近——只在底部时才自动滚动，否则不打扰翻历史。
    //
    // 用 `derivedStateOf` 而不是 `LaunchedEffect` + `mutableStateOf`：
    //   · 旧写法监听 `firstVisibleItemIndex` + `visibleItemsInfo.size`，**每滑一帧都
    //     重新赋值** isAtBottom —— 哪怕值没变（大多数帧都没变），重组依然被触发。
    //   · `derivedStateOf` 只在**读到的值真的变了**时才通知下游；滑动过程中绝大多数
    //     帧 isAtBottom 都是同一个 false，Compose 直接跳过重组。滑得越长省得越多。
    //
    // 判据用 layoutInfo.totalItemsCount（LazyColumn 自己的权威计数）而不是手算
    // displayItems.size + 2：列表里除了消息还有「复制手势提示」「加载更早消息」
    // 「本次产生的文件」三类固定条目，它们随状态有无而增减（hasMore 会变、
    // 提示可以被关掉），手算的 +2 一旦漏掉某一项，误判「已经到底」就会让自动
    // 跟随突然开火，把正在翻历史的人又拽回底部 —— 那正是本条最初要修的问题。
    // 保留 State 句柄而不是只留解包后的值：下面的 `rememberWantsBottom` 要对它
    // 做 snapshotFlow（「用户滚到底 → 恢复贴底意图」），需要能订阅它。
    val isAtBottomState = remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) true else {
                visible.last().index >= listState.layoutInfo.totalItemsCount - 3
            }
        }
    }
    val isAtBottom by isAtBottomState

    // 粘性「想待在底部」意图（= 注释承诺却从未实现的 userScrolled）；判据见 rememberWantsBottom。
    val wantsBottom = rememberWantsBottom(listState, isAtBottomState, selected.id)

    val scope = rememberCoroutineScope()
    // 进会话/切会话后**直接落到底部**（真机反馈：「每次进入会话默认在当前页面顶段，
    // 不符合对话性智能体的特点」）。
    //
    // 为什么单独一条而不是靠上面那条 `LaunchedEffect(displayItems.size, revision)`：
    // 那条的守卫是 `isAtBottom` —— 进会话时列表已经有内容、`isAtBottom` 为 false，
    // 于是它不开火，位置就停在初始的 index 0（顶部）。而 `isAtBottom` 的初始值 true
    // 只在**列表为空**时成立，救不了「一进来就是一屏历史」的常见情况。
    //
    // `key(selected.id)`：切会话时重跑一次（listState 也随之重建，两者保持一致）。
    // 用 animateScrollToItem 而不是 scrollToItem：短列表瞬间跳转没有意义，
    // 长列表给一个几百毫秒的滑动，用户能看出「我在往下走」而不是「页面被重置了」。
    // `snapshotFlow` + `first{...}`：等**列表真的有内容**的那一刻才滚。
    //
    // 为什么不能只用 `LaunchedEffect(selected.id)`：那个 effect 在 id 变化时跑一次，
    // 而首次进入 ChatDetail 时历史往往还没回来（displayItems 为空），守卫 `isNotEmpty()`
    // 直接 return —— 之后再没有第二次触发，位置就停在顶部。用户反馈「进会话默认在顶部
    // 没修好」正是这个原因（前两版都栽在「只跑一次」上）。
    //
    // 这里改成**持续贴底**，不再等「某个时刻」：
    // 前三版分别试过「只跑一次」「等列表有内容」「等 count 连续两次相同」，三版都失败 ——
    // 因为历史是**分批到达**的（第一页 → 后续页 → 流式补齐），count 一直在变，
    // 任何「等某个稳定时刻再滚一次」的判据都会在某一批到达前就结束。
    //
    // 持续贴底的守卫是粘性意图 `wantsBottom`（见上），阈值口径与 `isAtBottom`
    // 保持一致（最后一项在前 3 项之内），免得两者自相矛盾。
    LaunchedEffect(selected.id) {
        // ── 诊断插桩 + 落底循环 ──
        //
        // 这条 effect 是「进会话落底」的入口，上一轮的核心疑点
        // （改了九版、屏上探针实测 collect 正常发射却停在 first=30/54）就在它身上。
        //
        // 两样东西都搬去了 `ChatScrollDiag.kt`，这里只留指针：
        // ① **九版教训**（每一版为什么没用、正确形状是什么）→ 见那个文件的
        //    「进会话落底：九版教训与正确形状」段。它比代码更值得先读。
        // ② **诊断日志** → 同文件的一排 logScrollXxx 函数。塞回来的话这个文件
        //    会超 800 行预算，而为一个调试特性去 TECH-DEBT.md 立债务账，
        //    是把诊断码和业务债混为一谈。
        logScrollEffectStart(selected.id, listState.layoutInfo.totalItemsCount)
        // ── 进会话落底：一条独立的「推到底」循环 ──
        //
        // 这段逻辑改了**九版**才做对。每一版为什么没用、正确形状是什么、
        // 以及「为什么必须独立于 snapshotFlow」，全部写在
        // `ChatScrollDiag.kt` 的「进会话落底：九版教训与正确形状」段 ——
        // **改动前先读那一段**，别再走一遍。
        val scope = this
        val job = scope.launch {
            // 第一拍：等列表真的有内容（首次进入历史还没回来时 totalItemsCount 是 0）。
            var guard = 0
            while (listState.layoutInfo.totalItemsCount == 0 && guard < 60) {
                delay(50); guard++
            }
            if (listState.layoutInfo.totalItemsCount == 0) {
                logScrollNoContent(selected.id)
                return@launch
            }
            logScrollLoopStart(guard, listState.layoutInfo.totalItemsCount)
            var ticks = 0
            var moved = 1f
            while (guard < 400 && listState.layoutInfo.totalItemsCount > 0) {
                // 用户接管了（真拖过）→ 停手，把视图交回用户。
                // 判据用粘性意图而不是 `isScrollInProgress`：后者对**程序滚动**
                // （本函数自己的 scrollBy、跟随用的 animateScrollToItem）同样为真。
                if (!wantsBottom) {
                    logScrollLoopAbortedByUser(ticks)
                    return@launch
                }
                moved = listState.scrollBy(SCROLL_DOWN_PX)
                ticks++
                logScrollTick(ticks, moved, listState)
                // 推不动 = 已到列表物理底部，收工。
                if (moved <= 0f) {
                    logScrollLoopFinished(ticks)
                    break
                }
                delay(SCROLL_TICK_MS)
            }
            // moved 仍是正的 = 拍数用完还没到底（列表极长或 scrollBy 被抵消）。
            if (moved > 0f) logScrollLoopExhausted(ticks, listState.layoutInfo.totalItemsCount)
        }
        // 列表内容继续变化时（历史分页到达）重新推一轮，仍不干扰用户主动滚动。
        snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { count ->
            // 只记「变化」不记「值」：历史分批到达会让 count 连续变，
            // 逐值写会把日志刷爆；而每次变化都值得知道（它决定要不要重新推一轮）。
            logScrollCountEmitted(count)
        }
    }

    // 贴底跟随的「上一次决策」：决策没变就不重复落盘（真机实测：不记这个的话
    // 一个流式会话一小时能刷 200+ 条同样的行，把日志淹没）。见 logScrollFollow 的 KDoc。
    var lastFollowDecision by remember(selected.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(displayItems.size, state.liveChat?.revision) {
        // 流式期间的贴底跟随。守卫是**粘性意图** `wantsBottom`，不是瞬时 `isAtBottom`
        // —— 后者一有新内容就翻 false，会把跟随永久锁死（见 PITFALLS）。
        val total = listState.layoutInfo.totalItemsCount
        val target = (total - 1).coerceAtLeast(0)
        lastFollowDecision = logScrollFollow(
            state = listState,
            entries = displayEntries.size,
            liveItems = liveItems.size,
            wantsBottom = wantsBottom,
            atBottomNow = isAtBottom,
            animated = liveItems.isEmpty(),
            lastDecision = lastFollowDecision,
        )
        if (displayItems.isNotEmpty() && wantsBottom) {
            if (liveItems.isNotEmpty()) listState.scrollToItem(target)
            else listState.animateScrollToItem(target)
        }
    }

    // ⋯ 菜单里的「插话」与药丸上的发送键共用同一套可提交判据（内容非空或有待发附件 + 有写权限）。
    // 审批面板顶掉输入区时（ApprovalUiState 非 None）插话一并不可用 —— 与改版前
    // 「Composer 不渲染、插话键跟着消失」的行为保持一致，不新开一条并发提交的口子。
    val canWrite = "chat.write" in state.device?.scopes.orEmpty()
    val canSteer = state.approvalState == ApprovalUiState.None && canWrite &&
        (ComposerSendPolicy.canSubmit(message, canWrite) || state.pendingAttachments.isNotEmpty())
    // 外接物理键盘检测（Configuration.keyboard）：平板上接蓝牙/USB 键盘、或跑桌面模式时为真。
    // 手机端恒为 false —— 所以下面给 Composer 传的这个参数在手机上永远是默认值，零行为变化。
    val physicalKeyboard = hasPhysicalKeyboard(LocalConfiguration.current)
    // 顶栏 ⋯ 菜单与 Pill 同源：两处各算一次会出现「菜单里有、Pill 没有」的自相矛盾。
    val permission = state.history?.permissionSelect(state.permissionCatalog)

    onBack?.let { BackHandler(enabled = true, onBack = it) }
    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
        Column(modifier.imePadding()) {
            ConversationHeader(
                session = selected,
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onOpenFiles = { filesSheet = true },
                onModelClick = { modelSheet = true },
                onOpenStats = { statsSheet = true },
                onOpenTrajectory = { trajectorySheet = true },
                permission = permission,
                permissionEnabled = canWrite && !state.busy,
                onPermissionSelect = viewModel::selectPermissionPreset,
                canSteer = canSteer,
                onSteer = {
                    viewModel.sendMessage(message, true)
                    message = ""
                },
                wide = wide,
            )
            if (todos.isNotEmpty()) {
                TodoPanel(todos, tasksExpanded, onToggle = { tasksExpanded = !tasksExpanded })
            }
            Box(Modifier.weight(1f)) {
            LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            // 8dp：5dp 时正文 / 表格 / 时间戳三者挤在一起 —— 真机反馈「时间还是重叠」的观感来源之一。
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.history?.hasMore == true) {
                item {
                    TextButton(onClick = viewModel::loadOlderHistory, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.older_messages))
                    }
                }
            }
            if (state.history != null && displayItems.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.ChatBubbleOutline,
                        stringResource(R.string.no_readable_messages),
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            // 渲染用 displayEntries（由 visibleItems 折出，滚动中那份冻结快照），
            // **不是** displayItems：数据源每帧都在变，而滚动期间我们刻意不改列表内容。
            items(displayEntries, key = { entry -> displayEntryKey(entry) }) { entry ->
                ChatDisplayEntryRow(
                    entry = entry,
                    images = state.attachmentImages,
                    failures = state.attachmentFailures,
                    onLoadImage = viewModel::loadAttachmentImage,
                    // 卡片里的选择按钮等价于用户在输入框打了一行字再发送：
                    // 走同一条 send 通道，模型看到的就是一条普通的 user 消息。
                    onAnswer = { answer -> viewModel.sendMessage(answer, steer = false) },
                    genUiDebug = state.genUiDebug,
                    onRetry = viewModel::retryLastUserMessage,
                    onForkAt = { seq -> forkAtSeq = seq },
                )
            }
        }

        // 浮动按钮：不在底部时显示，点击跳到**会话尾部**。
        //
        // 为什么不能直接用 displayItems.lastIndex：列表里除了消息还有别的条目 ——
        // 顶部「复制手势提示」、底部「本次产生的文件」页脚，还有 hasMore 为真时的
        // 「加载更早消息」按钮（它在**列表开头**，见上方 item 块）。少算一项就停在
        // 「看着到底了其实还差一条」的位置，用户会以为按钮没生效。
        //
        // 用 totalItems 反推而不是硬编码 -1：LazyColumn 的 itemCount 是权威值，
        // 以后再加任何固定条目都不用回来改这里。
        //
        // 「滚动中先隐去」：滑动的每一帧都会把按钮画在内容之上，半透明的小圆盘压在
        // 滚过去的文字上，读起来像脏点。等手指松开（isScrollInProgress 变 false）再
        // 让它实体出现，既不挡阅读、出现时又足够醒目。顺带省掉滑动途中的重绘。
        //
        // 点了按钮不用手动把 isAtBottom 改回 true —— 它现在是 derivedStateOf（不可写），
        // 而 animateScrollToItem 滚到底之后 isAtBottom 自己就变 true、按钮自动消失。
        // （`scrolling` 已在列表上方提前定义 —— 那里要用它冻结流式快照，
        //   同一个值不要在这里重新读一遍，否则两处判据将来会漂移。）
        // 半透明 + 滚动中隐去。用 animateFloatAsState 驱动 alpha 而不是
        // AnimatedVisibility：这个位置在 BoxScope/ColumnScope 混排里，
        // AnimatedVisibility 的两个重载（顶层 vs ColumnScope）会被解析歧义卡住
        // （'ColumnScope.AnimatedVisibility cannot be called with an implicit receiver'），
        // 自己驱动 alpha 既绕开那个坑，也顺手把「滚动中先隐去、停手再浮现」平滑化。
        val jumpButtonAlpha by animateFloatAsState(
            targetValue = if (!isAtBottom && !scrolling) 0.88f else 0f,
            animationSpec = tween(durationMillis = 140),
            label = "jump-to-latest-alpha",
        )
        if (jumpButtonAlpha > 0.01f) {
            FloatingActionButton(
                onClick = {
                    val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    if (target > 0) {
                        scope.launch { listState.animateScrollToItem(target) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .size(48.dp)
                    .graphicsLayer { alpha = jumpButtonAlpha },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "跳转到最新",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        }
        // 待回答问题（ask_user_question）：与审批面板同一块地方，但**不顶掉输入区** ——
        // 审批是内核阻塞着等决定；而 `continued` 的问题是补交，拿走输入框用户连话都发不出。
        if (state.questions.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.questions.forEach { set ->
                    QuestionCard(
                        set = set,
                        answered = set.callId in state.answeredCallIds,
                        submitting = state.answeringCallId == set.callId,
                        canWrite = canWrite,
                        onSubmit = { answers -> viewModel.answerQuestions(set.callId, answers) },
                    )
                }
            }
        }
        // 状态条：思考与「内容由 AI 生成」合并同一条（用户要求，2026-10-17）——
        // 有思考时显示思考（可展开），没有时显示标识；位置固定在消息区下方、输入区上方。
        ChatStatusBar(liveThinking)
        // 权限档位 Pill **已挪到会话顶栏**（与「已连接」同一行，用户 2026-10-19 要求）：
        // 它原先在这里独占一条，挤占消息可视区。
        when (val approvals = state.approvalState) {
            ApprovalUiState.None -> Composer(
                value = message,
                onValueChange = { message = it },
                state = state,
                // 判据是「这个会话的 agent 是否在运行」，不是全局 busy ——
                // 后者连一次后台刷新都会置位，会让用户在任何请求期间都发不出消息。
                sessionRunning = viewModel.selectedSessionRunning(),
                attachments = state.pendingAttachments,
                attachmentUploading = state.attachmentUploading,
                onAttachImage = viewModel::attachImage,
                onAttachFile = viewModel::attachFile,
                onRemoveAttachment = viewModel::removePendingAttachment,
                onSubmit = { steer -> viewModel.sendMessage(message, steer); message = "" },
                onRetryMention = { viewModel.retryMentionEntries() },
                onCancel = viewModel::cancelRun,
                physicalKeyboard = physicalKeyboard,
            )
            ApprovalUiState.Loading -> ApprovalLoading()
            is ApprovalUiState.Pending -> approvals.items.firstOrNull()?.let { approval ->
                ApprovalPanel(
                    approval = approval,
                    count = approvals.items.size,
                    deciding = false,
                    canWrite = "chat.write" in state.device?.scopes.orEmpty(),
                    onReject = { viewModel.decideApproval(approval, false) },
                    onAllowOnce = { viewModel.decideApproval(approval, true) },
                )
            }
            is ApprovalUiState.Deciding -> ApprovalPanel(
                approval = approvals.item,
                count = approvals.items.size,
                deciding = true,
                canWrite = "chat.write" in state.device?.scopes.orEmpty(),
                onReject = {},
                onAllowOnce = {},
            )
            }
        }
    }
    if (modelSheet) ModelSheet(state, viewModel) { modelSheet = false }
    if (statsSheet) ChatStatsSheet(state) { statsSheet = false }
    if (trajectorySheet) ChatTrajectorySheet(state) { trajectorySheet = false }
    if (filesSheet) {
        SessionFilesSheet(state, viewModel) {
            filesSheet = false
            viewModel.closeSessionFile()
        }
    }
}

