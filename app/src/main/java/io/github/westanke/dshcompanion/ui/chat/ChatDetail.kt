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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatAttachment
import io.github.westanke.dshcompanion.data.ChatTurnFailure
import io.github.westanke.dshcompanion.data.localizedErrorRes
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
import io.github.westanke.dshcompanion.data.TodoItem
import io.github.westanke.dshcompanion.data.displayItems
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
 * 从 `ui/ChatScreen.kt`（3882 行）拆出。原文件是 `docs/project/TECH-DEBT.md` 登记的
 * 第一号债（「改一处要读 3 600 行」），按 `webview-port-todo.md` 的 PORT-* 分组逐组拆成
 * `ui/chat/` 子模块，**每个函数的实现逐字未改**，只是换了所在文件。
 */
@Composable
internal fun ChatDetail(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    wide: Boolean = false,
) {
    val selected = state.sessions.find { it.id == state.selectedSessionId }
    if (selected == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.select_session))
        }
        return
    }
    // 输入框内容存在 ViewModel 而不是本地 state：发送失败时要把原文还回来，
    // 而「失败」这个事只有 ViewModel 知道。本地 remember 一旦被 message = "" 清掉就找不回了。
    var message by remember(selected.id) { mutableStateOf(state.pendingText) }
    // 发送失败时 ViewModel 会把原文写回 pendingText，这里跟着回填输入框。
    // 用 LaunchedEffect 而不是把 message 直接绑成 state.pendingText：
    // 后者会让用户正在输入的字被发送成功后的清空覆盖掉，敲一半的字会凭空蒸发。
    LaunchedEffect(state.pendingText) {
        if (state.pendingText.isNotBlank()) message = state.pendingText
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
    var tasksExpanded by rememberSaveable(selected.id) { mutableStateOf(true) }

    // Markdown 里的链接会被注解成 `LinkAnnotation.Url`，由 Compose 的 Text 通过
    // `LocalUriHandler` 打开。没有这个 provider 时用的是系统默认 handler，它只会把
    // uri 丢给 Intent —— 而我们消息里的链接大量是**服务端绝对路径**（以及我一度写过的
    // 相对路径），Intent 处理不了就静默失败，用户看到的现象是「点了没反应」。
    //
    // 所以这里换成分流处理：服务端路径交给 roots/resolve（复用已测过的链路），
    // http(s) 交系统浏览器，其余明确告知不支持，绝不猜。
    val context = LocalContext.current
    val linkHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                when (val target = ChatLinkTarget.classify(uri)) {
                    is ChatLinkTarget.ServerPath -> viewModel.openSessionFile(target.path)

                    is ChatLinkTarget.Web -> {
                        val opened = runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
                        }.isSuccess
                        if (!opened) toast(context, context.getString(R.string.link_open_failed))
                    }

                    is ChatLinkTarget.Unsupported -> toast(
                        context,
                        when (target.reason) {
                            // 相对路径无法定位是有意为之：服务端只接受绝对路径，
                            // 猜一个基准目录可能打开**另一个**文件。
                            ChatLinkTarget.Reason.RELATIVE_PATH, ChatLinkTarget.Reason.EMPTY ->
                                context.getString(R.string.link_relative_path)
                            ChatLinkTarget.Reason.ANCHOR ->
                                context.getString(R.string.link_anchor_unsupported)
                            ChatLinkTarget.Reason.UNKNOWN_SCHEME ->
                                context.getString(R.string.link_scheme_unsupported)
                        },
                    )
                }
            }
        }
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
    val historyItems = state.history?.displayItems().orEmpty()
        .filter { it.kind != ChatItemKind.REASONING }
        .filter { showContext || it.kind != ChatItemKind.CONTEXT }
    val displayItems = historyItems + liveItems
    val todos = state.history?.todoItems().orEmpty()
    val listState = rememberLazyListState()
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
    val isAtBottom by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) true else {
                visible.last().index >= listState.layoutInfo.totalItemsCount - 3
            }
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(displayItems.size, state.liveChat?.revision) {
        if (displayItems.isNotEmpty() && isAtBottom) {
            val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
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
                permission = state.history?.permissionSelect(),
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
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
            items(displayItems, key = { it.id }) { item ->
                ChatItem(
                    item = item,
                    images = state.attachmentImages,
                    failures = state.attachmentFailures,
                    onLoadImage = viewModel::loadAttachmentImage,
                    // 卡片里的选择按钮等价于用户在输入框打了一行字再发送：
                    // 走同一条 send 通道，模型看到的就是一条普通的 user 消息。
                    onAnswer = { answer -> viewModel.sendMessage(answer, steer = false) },
                    genUiDebug = state.genUiDebug,
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
        val scrolling = listState.isScrollInProgress
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
        // 待回答问题（ask_user_question）。
        //
        // 位置与审批面板同一块地方（消息区下方、输入区上方），但**不顶掉输入区**：
        // 审批是内核阻塞着等决定，用户此刻没别的事可做；而方案 A 只做 `continued` 的问题 ——
        // Agent 已经继续跑了，答复是补交，把输入框拿走只会让用户连话都发不出去。
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
        // 思考条：消息区**下方**、输入区**上方**的那一条固定位置（网页版 #thinkbar 的位置）。
        // 有思考才渲染，没有时整条不存在 —— 不用 AnimatedVisibility 收起，
        // 那会留下一条带边框的空缝，在输入区上方永久挡着一行。
        ChatThinkBar(liveThinking)
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
    if (filesSheet) {
        SessionFilesSheet(state, viewModel) {
            filesSheet = false
            viewModel.closeSessionFile()
        }
    }
}

@Composable
internal fun ConversationHeader(
    session: ChatSession,
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    onOpenFiles: () -> Unit,
    onModelClick: () -> Unit,
    permission: PermissionSelect?,
    permissionEnabled: Boolean,
    onPermissionSelect: (String) -> Unit,
    canSteer: Boolean,
    onSteer: () -> Unit,
    wide: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 10.dp else 2.dp)) {
            // 标题在窄屏（手机）永远单行；平板（wide）下允许两行 —— 兜底到长 cwd 路径时
            // 不会在顶栏被腰斩，短标题（用户起的名字）依然只占一行，布局无感。
            Text(
                sessionDisplayTitle(session),
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusLabel(
                if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                state.eventsConnected,
            )
            if (session.pendingInteraction == "approval") ApprovalStatusLabel(Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onOpenFiles) {
            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.session_files))
        }
        IconButton(onClick = viewModel::refreshHistory, enabled = !state.busy) {
            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
        }
        AnimatedVisibility(session.running, enter = fadeIn(), exit = fadeOut()) {
            IconButton(
                onClick = viewModel::cancelRun,
                enabled = "chat.write" in state.device?.scopes.orEmpty(),
            ) {
                Icon(Icons.Outlined.Cancel, stringResource(R.string.cancel_run), tint = MaterialTheme.colorScheme.error)
            }
        }
        // ⋯ 菜单（网页版 chat.html:283 的 #more，位置一样：标题右边、停止键旁边）。
        //
        // 输入区压成**一行药丸**后，模型 / 权限 / 插话这些入口在输入区里没有位置了，
        // 所以整体搬到这里。它们只是**换了容器，不是换了语义** ——
        // 模型仍走既有的 ModelSheet（智能体切换本来就在那个面板里，不重复做），
        // 权限仍是只读 / 工作区写入 / 完整访问三档 + 完整访问二次确认，
        // 插话仍是固定的 steer 提交（不受设置里「繁忙时默认行为」影响）。
        var moreOpen by remember { mutableStateOf(false) }
        var confirmFullAccess by remember { mutableStateOf(false) }
        val currentModel = state.sessionModels?.current
        val agentName = state.agentPresets.find { it.id == session.agentPreset }?.name ?: session.agentPreset
        val modelSummary = listOfNotNull(agentName, currentModel?.provider, currentModel?.model)
            .joinToString(" · ")
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions))
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.model))
                            if (modelSummary.isNotBlank()) {
                                Text(
                                    modelSummary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    onClick = {
                        moreOpen = false
                        onModelClick()
                    },
                    enabled = state.sessionModels != null && state.sessionModels.routable,
                    leadingIcon = { Icon(Icons.Outlined.ModelTraining, null) },
                )
                permission?.takeIf { it.options.isNotEmpty() }?.let { permissionValue ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionMenuItems(
                        value = permissionValue,
                        enabled = permissionEnabled,
                        onSelect = { selected ->
                            moreOpen = false
                            onPermissionSelect(selected)
                        },
                        onRequestFullAccess = {
                            moreOpen = false
                            confirmFullAccess = true
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.steer))
                            Text(
                                stringResource(R.string.busy_send_mode_steer_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    onClick = {
                        moreOpen = false
                        onSteer()
                    },
                    enabled = canSteer,
                    leadingIcon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                )
            }
        }
        // 完整访问的二次确认：与原来挂在输入区那个按钮上的对话框是同一套要求 ——
        // 必须勾选 acknowledged，确认键才会亮。
        if (confirmFullAccess) {
            FullAccessConfirmDialog(
                onDismiss = { confirmFullAccess = false },
                onConfirm = {
                    confirmFullAccess = false
                    onPermissionSelect("danger-full-access")
                },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 「本会话的文件」面板。
 *
 * 刻意做成一个**独立面板**，不去动消息气泡的渲染（那块一千多行，风险与收益不匹配）：
 * 列表由 [SessionFileRefs] 从已加载的会话历史里提取，点一条就交给
 * [HarnessViewModel.openSessionFile] 把内容取回来。
 *
 * 为什么非要服务端帮一下：会话事件里是**服务器绝对路径**，而 `/roots` 刻意不返回根的
 * 绝对路径，客户端手上永远缺这一环，只能由 `roots/resolve` 完成这次转换。
 */
