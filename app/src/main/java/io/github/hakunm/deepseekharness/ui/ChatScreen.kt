package io.github.hakunm.deepseekharness.ui

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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.ApprovalUiState
import io.github.hakunm.deepseekharness.PendingAttachment
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.SessionFileOpen
import io.github.hakunm.deepseekharness.data.ChatContentParts
import io.github.hakunm.deepseekharness.data.ChatAttachment
import io.github.hakunm.deepseekharness.data.ChatTurnFailure
import io.github.hakunm.deepseekharness.data.localizedErrorRes
import io.github.hakunm.deepseekharness.data.ChatDisplayItem
import io.github.hakunm.deepseekharness.data.ChatItemKind
import io.github.hakunm.deepseekharness.data.ToolCallDetail
import io.github.hakunm.deepseekharness.data.formatToolDuration
import io.github.hakunm.deepseekharness.data.prettyToolArguments
import io.github.hakunm.deepseekharness.data.LiveChatBlock
import io.github.hakunm.deepseekharness.data.ChatLinkTarget
import io.github.hakunm.deepseekharness.data.ChatQuestion
import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.data.ChatWorkspace
import io.github.hakunm.deepseekharness.data.AgentPreset
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.data.CommandDescriptor
import io.github.hakunm.deepseekharness.data.ComposerSendPolicy
import io.github.hakunm.deepseekharness.data.ModelSelection
import io.github.hakunm.deepseekharness.data.ModelView
import io.github.hakunm.deepseekharness.data.PendingApproval
import io.github.hakunm.deepseekharness.data.PendingQuestionSet
import io.github.hakunm.deepseekharness.data.PermissionSelect
import io.github.hakunm.deepseekharness.data.QuestionAnswer
import io.github.hakunm.deepseekharness.data.MessageSegment
import io.github.hakunm.deepseekharness.data.parseGenUi
import io.github.hakunm.deepseekharness.data.splitMessageBody
import io.github.hakunm.deepseekharness.data.GenUiDiagnosis
import io.github.hakunm.deepseekharness.data.diagnoseGenUi
import io.github.hakunm.deepseekharness.data.PreviewKind
import io.github.hakunm.deepseekharness.data.previewKindOf
import io.github.hakunm.deepseekharness.data.HtmlBlock
import io.github.hakunm.deepseekharness.data.extractHtmlBlocks
import io.github.hakunm.deepseekharness.data.ResolvedPath
import io.github.hakunm.deepseekharness.data.SessionFileRef
import io.github.hakunm.deepseekharness.data.ChatHistory
import io.github.hakunm.deepseekharness.data.SessionFileRefs
import io.github.hakunm.deepseekharness.data.deliverables
import io.github.hakunm.deepseekharness.data.TodoItem
import io.github.hakunm.deepseekharness.data.displayItems
import io.github.hakunm.deepseekharness.data.permissionSelect
import io.github.hakunm.deepseekharness.data.todoItems
import java.io.InputStream

/**
 * 一次性反馈。
 *
 * 刻意用系统 Toast 而不是 Snackbar：ChatDetail 没有 Scaffold/SnackbarHost，
 * 为了一句「已复制」去引入整套宿主结构，改动面远大于收益；而这些反馈都是
 * 「动作已完成」的通知，不需要用户在其中做选择（审批面板那种才需要留在界面上）。
 */
private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

@Composable
fun ChatScreen(state: HarnessState, viewModel: HarnessViewModel, wide: Boolean) {
    var createSheet by remember { mutableStateOf(false) }
    var workspaceSheet by remember { mutableStateOf(false) }
    val selected = state.selectedSessionId

    // 会话**列表**两条路共用（它是原生资产，换掉没有收益）；只有详情页有两个实现。
    // WebView 版尚未过真机验证，所以由 AppPreferences.useWebChat 决定，默认走原生。
    val detail: @Composable (() -> Unit) -> Unit = { onBack ->
        if (state.useWebChat) {
            ChatWebScreen(state, viewModel, onBack, Modifier.fillMaxSize())
        } else {
            ChatDetail(state, viewModel, onBack, Modifier.fillMaxSize())
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            SessionPane(state, viewModel, { createSheet = true }, { workspaceSheet = true }, Modifier.width(304.dp).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            detail({})
        }
    } else if (selected == null) {
        SessionPane(state, viewModel, { createSheet = true }, { workspaceSheet = true }, Modifier.fillMaxSize())
    } else {
        detail { viewModel.selectSession(null) }
    }
    if (createSheet) NewSessionSheet(state, viewModel) { createSheet = false }
    if (workspaceSheet) WorkspaceManagementSheet(state, viewModel) { workspaceSheet = false }
}

@Composable
private fun SessionPane(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onCreate: () -> Unit,
    onManageWorkspaces: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.session_count, state.sessions.size),
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if ("chat.write" in state.device?.scopes.orEmpty()) {
                IconButton(onClick = onManageWorkspaces, enabled = !state.busy) {
                    Icon(Icons.Outlined.FolderOpen, stringResource(R.string.manage_workspaces))
                }
                Surface(
                    onClick = onCreate,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.new_session), Modifier.padding(12.dp))
                }
            }
        }
        if (state.sessions.isEmpty()) {
            EmptyState(
                Icons.Outlined.ChatBubbleOutline,
                stringResource(R.string.no_sessions),
                Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = state.selectedSessionId == session.id,
                        enabled = !state.busy,
                        onClick = { viewModel.selectSession(session.id) },
                        onRename = { viewModel.renameSession(session.id, it) },
                        onFork = { viewModel.forkSession(session.id) },
                        onArchive = { viewModel.archiveSession(session.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
) {
    val displayTitle = session.title ?: session.workspaceTitle ?: session.cwd.ifBlank { session.id.take(12) }
    var menuExpanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var archiveOpen by remember { mutableStateOf(false) }
    var titleDraft by remember(session.id, displayTitle) { mutableStateOf(displayTitle) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                null,
                modifier = Modifier.padding(9.dp),
                tint = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.pendingInteraction == "approval") ApprovalStatusLabel()
                else StatusLabel(
                    if (session.running) stringResource(R.string.session_running) else stringResource(R.string.session_idle),
                    session.running,
                )
                session.workspaceTitle?.takeIf { it != displayTitle }?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.session_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_session)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { titleDraft = displayTitle; renameOpen = true; menuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fork_session)) },
                    leadingIcon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                    onClick = { menuExpanded = false; onFork() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.archive_session)) },
                    leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                    onClick = { archiveOpen = true; menuExpanded = false },
                )
            }
        }
    }
    if (renameOpen) RenameDialog(
        title = stringResource(R.string.rename_session),
        value = titleDraft,
        onValueChange = { titleDraft = it },
        onConfirm = { onRename(titleDraft); renameOpen = false },
        onDismiss = { renameOpen = false },
    )
    if (archiveOpen) AlertDialog(
        onDismissRequest = { archiveOpen = false },
        title = { Text(stringResource(R.string.archive_session_title)) },
        text = { Text(stringResource(R.string.archive_session_warning)) },
        confirmButton = {
            TextButton(onClick = { onArchive(); archiveOpen = false }) { Text(stringResource(R.string.archive_session)) }
        },
        dismissButton = { TextButton(onClick = { archiveOpen = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ChatDetail(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    modifier: Modifier,
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
    // 只取**最后一次运行之后**的文件：新回复还在流式输出时（liveChat != null）不显示，
    // 否则文件列表会随着每个工具调用闪一下 —— 那正是网页版「会话结束后才出现」的由来。
    // 与标题栏文件夹弹层共用同一个来源（见 sessionFileList 的 KDoc）。
    val sessionFiles = remember(state.history, state.liveChat) {
        if (state.liveChat != null) emptyList() else sessionFileList(state.history)
    }
    val listState = rememberLazyListState()
    // 复制手势提示只需出现一次：记住它被关掉，而不是每次进会话都啰嗦一遍。
    var copyHintDismissed by rememberSaveable { mutableStateOf(false) }
    // 用户是否在底部附近——只在底部时才自动滚动，否则不打扰翻历史
    var isAtBottom by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        if (visibleItems.isNotEmpty()) {
            val lastVisibleIndex = visibleItems.last().index
            val totalItems = displayItems.size + 2 // +2 for copy-hint and older-messages button
            isAtBottom = lastVisibleIndex >= totalItems - 3
        }
    }
    LaunchedEffect(displayItems.size, state.liveChat?.revision) {
        if (displayItems.isNotEmpty() && isAtBottom) {
            if (liveItems.isNotEmpty()) listState.scrollToItem(displayItems.lastIndex)
            else listState.animateScrollToItem(displayItems.lastIndex)
        }
    }

    // ⋯ 菜单里的「插话」与药丸上的发送键共用同一套可提交判据（内容非空或有待发附件 + 有写权限）。
    // 审批面板顶掉输入区时（ApprovalUiState 非 None）插话一并不可用 —— 与改版前
    // 「Composer 不渲染、插话键跟着消失」的行为保持一致，不新开一条并发提交的口子。
    val canWrite = "chat.write" in state.device?.scopes.orEmpty()
    val canSteer = state.approvalState == ApprovalUiState.None && canWrite &&
        (ComposerSendPolicy.canSubmit(message, canWrite) || state.pendingAttachments.isNotEmpty())

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
            // 复制手势提示。
            //
            // 为什么需要它：长按既用来「选中片段」又曾经被用来「复制整条」，
            // 两个手势互相抢，用户既选不了也猜不出怎么复制。改成双击复制之后，
            // 行为从旧版换掉了，不说一声用户会以为功能没了 —— 所以明确写在最上面。
            if (displayItems.isNotEmpty() && !copyHintDismissed) {
                item(key = "copy-hint") {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.copy_hint),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            stringResource(R.string.copy_hint_dismiss),
                            modifier = Modifier
                                .combinedClickable(onClick = { copyHintDismissed = true })
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
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
            // 消息流末尾的「本次产生的文件」。
            //
            // 网页版是这个形态：会话结束后，文件直接列在对话下方，不必点任何入口。
            // App 原先只有一个文件夹图标按钮挂在顶栏，用户得先知道它存在 ——
            // 于是「功能其实有，但用户找不到」。这里补上自动呈现的那一份，
            // 顶栏按钮保留（大屏下翻历史时仍然好用），两者不冲突。
            if (state.liveChat == null && sessionFiles.isNotEmpty()) {
                item(key = "session-files-footer") {
                    SessionFilesFooter(sessionFiles, viewModel::openSessionFile)
                }
            }
        }
        // 浮动按钮：不在底部时显示，点击跳到底部
        if (!isAtBottom) {
            FloatingActionButton(
                onClick = {
                    isAtBottom = true
                    if (displayItems.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(displayItems.lastIndex) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .size(48.dp),
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
private fun ConversationHeader(
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
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 10.dp else 2.dp)) {
            Text(
                session.title ?: session.workspaceTitle ?: session.cwd.ifBlank { session.id },
                maxLines = 1,
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionFilesSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    // 只认当前会话的结果：切会话后残留的详情不该再显示（否则会看到上一个会话的文件）。
    val opened = state.sessionFileOpen?.takeIf { it.sessionId == state.selectedSessionId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (opened == null) {
            SessionFileList(state, viewModel::openSessionFile)
        } else {
            SessionFileDetail(opened, viewModel::closeSessionFile)
        }
    }
}

/**
 * 一个会话的「文件清单」——**两处共用同一个来源**。
 *
 * 优先级：内核明说的**交付物**（`deliverables/presented`，present 工具触发，
 * 带绝对路径 + 人写的说明）→ 没有才回退到「从工具参数里嗅提到过哪些路径」。
 *
 * 为什么必须抽出来共用：这两处（会话底部的「本次产生的文件」与标题栏文件夹弹层）
 * 曾经各写各的 —— 底部先改成了交付物，弹层还在嗅参数，于是**同一个会话两处显示的
 * 东西不一样**：底部是 agent 交付的报告与安装包，弹层却是一堆嗅出来的路径
 * （真机反馈：「会话标题栏的文件夹中的不合适」）。共用之后不可能再走偏。
 *
 * 为什么优先交付物：嗅参数那条路有一堆刻意的取舍 —— bash / pwsh / run_code 整体跳过、
 * 只认绝对路径。实测某个会话 1400+ 次工具调用里 run_code 占 1347 次全被跳过、
 * meta.diffs 一次都没出现、read 只有 1 次 —— 列表基本是空的；而 agent 真正交付的东西
 * 走的是另一条从来没被读过的事件。
 */
private fun sessionFileList(history: ChatHistory?): List<SessionFileRef> {
    val delivered = history?.deliverables().orEmpty()
    return if (delivered.isNotEmpty()) {
        delivered.map { SessionFileRef(it.path, count = 1, lastSeq = it.seq, description = it.description) }
    } else {
        SessionFileRefs.refs(history?.events.orEmpty())
    }
}

@Composable
private fun SessionFileList(state: HarnessState, onOpen: (String) -> Unit) {
    val history = state.history
    // 解析整段历史是有成本的；只在历史对象变化时重算，而不是每次重组都跑一遍。
    // 与底部「本次产生的文件」共用同一个来源，避免两处显示不一致。
    val refs = remember(history) { sessionFileList(history) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        Text(stringResource(R.string.session_files), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.session_files_subtitle),
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (refs.isEmpty()) {
            EmptyState(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.session_files_empty),
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(top = 10.dp)) {
            items(refs, key = { it.path }) { ref ->
                SessionFileRow(ref) { onOpen(ref.path) }
            }
            if (history?.hasMore == true) {
                item("session-files-partial") {
                    Text(
                        stringResource(R.string.session_files_partial),
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * 消息流末尾的「本次产生的文件」区块。
 *
 * 形态对齐网页版：会话结束后，文件直接出现在对话下方，不需要用户去找入口。
 * 顶栏那个文件夹按钮保留 —— 翻历史消息时它更省事，两者不重复。
 *
 * 默认折叠：文件多的时候（实测单会话能提取出 30 个路径）铺满一屏会把对话顶没，
 * 但完全不出现又等于没做，所以折叠 + 显示条数，让用户自己决定看不看。
 */
@Composable
private fun SessionFilesFooter(refs: List<SessionFileRef>, onOpen: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { expanded = !expanded }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                stringResource(R.string.session_files_footer),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                refs.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                stringResource(R.string.session_files_footer_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            refs.forEach { ref -> SessionFileRow(ref) { onOpen(ref.path) } }
        }
    }
}

@Composable
private fun SessionFileRow(ref: SessionFileRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                ref.path.substringAfterLast('/').ifBlank { ref.path },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // agent 为交付物写的说明（「本轮排障报告」「v2.2.18 安装包」这种）。
            // 只有显式交付的才有，嗅出来的路径没有 —— 那时 agent 并没有交付的意图。
            ref.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    desc,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 完整路径要能看全：同名文件在不同目录里，只显示文件名是分不出来的。
            Text(
                ref.path,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (ref.count > 1) {
            Text(
                stringResource(R.string.session_file_count, ref.count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SessionFileDetail(open: SessionFileOpen, onBack: () -> Unit) {
    val resolved = open.resolved
    val preview = open.preview
    val failure = open.failure
    // 图片 / PDF 的判据与「文件」页**同一套**（previewKindOf 只是多一层魔数兜底）。
    // 两处必须一致 —— 同一个文件在文件页能看、在会话面板只剩一行字，就是最典型的坏体验。
    val kind = remember(open.requestedPath, resolved?.contentType, preview, open.bytes) {
        previewKindOf(open.requestedPath, resolved?.contentType, open.bytes)
    }
    val binaryPreview = kind == PreviewKind.IMAGE || kind == PreviewKind.PDF
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.session_file_detail),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        SelectionContainer {
            Text(
                open.requestedPath,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        when {
            open.loading -> Box(
                Modifier.fillMaxWidth().height(140.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 2.dp) }

            // 不在授权根内是常见结果，说清楚「为什么点不开」，而不是丢一句网络错误。
            open.outsideRoots -> SessionFileNotice(
                Icons.Outlined.Lock,
                stringResource(R.string.session_file_outside_roots),
            )

            failure != null -> SessionFileNotice(
                Icons.Outlined.WarningAmber,
                // 端点不存在只有一个现实原因：服务端插件版本过旧。把它与普通失败分开，
                // 用户才知道该做什么（升级插件），而不是反复检查自己的路径。
                if (failure == "PLUGIN_TOO_OLD") {
                    stringResource(R.string.session_file_plugin_too_old)
                } else {
                    stringResource(R.string.session_file_failed, failure)
                },
            )

            resolved?.kind == "directory" -> SessionFileNotice(
                Icons.Outlined.Folder,
                stringResource(R.string.session_file_directory),
            )

            // 放在 tooLarge 之前是有意的：图片 / PDF 各有自己的字节上限（24 / 32 MiB），
            // 用文本那条「超过 2 MiB」的提示是错的 —— 顺带在这里给「跳过预览 + 去哪下载」。
            binaryPreview -> Column(Modifier.fillMaxWidth()) {
                // 文件信息（类型 · 体积）与文本预览那条路保持一致：超限时用户至少知道是多大
                resolved?.let { SessionFileMeta(it) }
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 180.dp, max = 420.dp),
                ) {
                    BinaryFilePreview(
                        kind = kind,
                        bytes = open.bytes,
                        sizeBytes = resolved?.size ?: 0L,
                        skippedForSize = open.tooLarge,
                        fallbackMessage = stringResource(R.string.session_file_binary),
                        skippedHint = stringResource(R.string.session_file_files_hint),
                        onDownload = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            open.tooLarge -> SessionFileNotice(
                Icons.Outlined.WarningAmber,
                stringResource(R.string.session_file_too_large),
            )

            preview == null -> SessionFileNotice(
                Icons.Outlined.Description,
                stringResource(R.string.session_file_binary),
            )

            preview.isEmpty() -> SessionFileNotice(
                Icons.Outlined.Description,
                stringResource(R.string.session_file_empty),
            )

            else -> {
                resolved?.let { SessionFileMeta(it) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 按文件类型选渲染方式。原先不分类型、一律当等宽源码画，
                    // 于是 .md 的标题与粗体、.html 的满屏尖括号全都原样显示 ——
                    // 用户看到的是源码而不是文件。Markdown 直接交给 Markdown()，
                    // HTML 走结构化抽取（**不**用 WebView：那等于让一份不可信文件拿到执行权）。
                    when (kind) {
                        PreviewKind.MARKDOWN -> Markdown(
                            content = preview,
                            typography = compactMarkdownTypography(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        PreviewKind.HTML -> HtmlStructurePreview(preview)

                        else -> SelectionContainer {
                            Text(
                                preview,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    if (open.truncated) stringResource(R.string.session_file_truncated)
                    else stringResource(R.string.session_file_files_hint),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * HTML 预览：把抽出来的结构画成原生控件。
 *
 * 刻意不画表格 —— HTML 表格的 colspan/嵌套结构要正确排版得写一个真的排版器，
 * 而为了预览一份文件不值得。把表格行降级成段落，至少内容不丢。
 */
@Composable
private fun HtmlStructurePreview(source: String) {
    val blocks = remember(source) { extractHtmlBlocks(source) }
    if (blocks.isEmpty()) {
        Text(
            source,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block.kind) {
                    HtmlBlock.Kind.HEADING -> Text(
                        block.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    HtmlBlock.Kind.LIST_ITEM -> Row {
                        Text("· ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            block.text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HtmlBlock.Kind.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text(
                            block.text,
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HtmlBlock.Kind.CODE -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            block.text,
                            modifier = Modifier.padding(8.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HtmlBlock.Kind.LINK -> Text(
                        buildString {
                            append(block.text)
                            block.href?.let { append("  ").append(it) }
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    HtmlBlock.Kind.PARAGRAPH -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionFileMeta(resolved: ResolvedPath) {    val size = resolved.size?.let(::formatFileSize)
    val parts = listOfNotNull(resolved.contentType, size)
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        modifier = Modifier.padding(top = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun SessionFileNotice(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KiB".format(size / 1024.0)
    else -> "%.1f MiB".format(size / 1024.0 / 1024.0)
}

@Composable
private fun ApprovalStatusLabel(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            stringResource(R.string.awaiting_approval),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ApprovalLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.approval_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApprovalPanel(
    approval: PendingApproval,
    count: Int,
    deciding: Boolean,
    canWrite: Boolean,
    onReject: () -> Unit,
    onAllowOnce: () -> Unit,
) {
    val fullAccess = approval.risk == "full-access"
    var showRiskDialog by rememberSaveable(approval.id) { mutableStateOf(false) }
    var acknowledged by rememberSaveable(approval.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (fullAccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (fullAccess) Icons.Outlined.WarningAmber else Icons.Outlined.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (fullAccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.approval_required), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (count > 1) stringResource(R.string.approval_count, approval.toolName, count)
                        else approval.toolName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (deciding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            approval.reason?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            approval.detail?.takeIf(String::isNotBlank)?.let { detail ->
                Text(
                    stringResource(R.string.approval_command_preview),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer {
                    Text(
                        detail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 144.dp)
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (fullAccess) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        stringResource(R.string.full_access),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onReject, enabled = canWrite && !deciding) {
                    Text(stringResource(R.string.reject))
                }
                Button(
                    onClick = {
                        if (fullAccess) {
                            acknowledged = false
                            showRiskDialog = true
                        } else onAllowOnce()
                    },
                    enabled = canWrite && !deciding,
                ) {
                    Text(stringResource(R.string.allow_once))
                }
            }
        }
    }
    if (showRiskDialog) {
        AlertDialog(
            onDismissRequest = { showRiskDialog = false; acknowledged = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text(stringResource(R.string.confirm_full_access)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.full_access_warning))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text(stringResource(R.string.full_access_ack), Modifier.padding(start = 4.dp))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRiskDialog = false; acknowledged = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            confirmButton = {
                Button(
                    onClick = { showRiskDialog = false; onAllowOnce() },
                    enabled = acknowledged,
                ) {
                    Text(stringResource(R.string.confirm_allow_once))
                }
            },
        )
    }
}

/**
 * 一道题算不算答完了：有选项的要选中至少一个，没选项的要自由文本非空。
 *
 * 抽成纯函数是为了能测（见 `QuestionAnswerTest`）：提交按钮的启用条件就是它对所有题取与，
 * 而「没选项的题只看 custom」这条最容易在改动里被漏掉 —— 漏了的表现是按钮永远点不亮。
 */
internal fun isAnswered(question: ChatQuestion, selected: Collection<String>, custom: String): Boolean =
    if (question.options.isEmpty()) custom.isNotBlank() else selected.isNotEmpty()

/**
 * 把界面上的勾选与输入收成请求体。
 *
 * 两条规则：
 * - 有选项的题只带 `selected`，并**按选项原顺序**过滤 —— 用户点选的先后顺序不该影响请求体；
 * - 没选项的题只带 `custom`（去掉首尾空白）。
 *
 * 顺序与 [questions] 一致，`id` 原样回带。
 */
internal fun buildAnswers(
    questions: List<ChatQuestion>,
    selected: Map<String, Set<String>>,
    custom: Map<String, String>,
): List<QuestionAnswer> = questions.map { question ->
    if (question.options.isEmpty()) {
        QuestionAnswer(
            id = question.id,
            selected = emptyList(),
            custom = custom[question.id].orEmpty().trim(),
        )
    } else {
        val picked = selected[question.id].orEmpty()
        QuestionAnswer(
            id = question.id,
            selected = question.options.map { it.label }.filter { it in picked },
        )
    }
}

/**
 * 待回答问题卡片（`ask_user_question`，方案 A 只做 `continued`）。
 *
 * 与审批面板的关系：两者都占「消息区下方、输入区上方」那块地方，但**只有审批顶掉输入区**。
 * 审批是内核阻塞着等决定，用户此刻没别的事可做；问题是 Agent 已经继续跑之后的补答，
 * 拿掉输入框只会让用户连话都发不出去。
 *
 * 外形沿用审批卡那一套（Surface + 描边 + 抬头行 + 底部按钮），信息量与网页版一一对应：
 * 抬头、每题的 header / question / detail、选项及其说明、没有选项时给自由文本。
 * 抬头用主色而不是审批那个警告色：它要的是「等你回答」，不是「危险」。
 */
@Composable
private fun QuestionCard(
    set: PendingQuestionSet,
    answered: Boolean,
    submitting: Boolean,
    canWrite: Boolean,
    onSubmit: (List<QuestionAnswer>) -> Unit,
) {
    // 草稿按 callId 记住：同一组题在重组之间不能丢勾选；换了一组就从零开始，
    // 免得上一组的答案漏到下一组（那是「提交上去的答案张冠李戴」这类事故的源头）。
    val selected = remember(set.callId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(set.callId) { mutableStateMapOf<String, String>() }
    val interactive = canWrite && !submitting && !answered
    val complete = set.questions.isNotEmpty() && set.questions.all { question ->
        isAnswered(question, selected[question.id].orEmpty(), custom[question.id].orEmpty())
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (answered) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
        ),
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (answered) Icons.Outlined.TaskAlt else Icons.Outlined.QuestionAnswer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (answered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (answered) R.string.question_answered else R.string.question_waiting,
                        ),
                        color = if (answered) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (set.questions.size > 1) {
                        Text(
                            stringResource(R.string.question_count, set.questions.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            set.questions.forEach { question ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    question.header?.takeIf(String::isNotBlank)?.let { header ->
                        Text(
                            header,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(question.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    question.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (question.options.isEmpty()) {
                        // 没有选项 = 这题要自由文本，正好对应协议里的 custom。
                        OutlinedTextField(
                            value = custom[question.id].orEmpty(),
                            onValueChange = { custom[question.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = interactive,
                            label = { Text(stringResource(R.string.answer_your_own)) },
                            minLines = 1,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else {
                        question.options.forEach { option ->
                            val checked = selected[question.id]?.contains(option.label) == true
                            val toggle = {
                                val current = selected[question.id].orEmpty()
                                selected[question.id] = when {
                                    // 单选：再点一次已选中的就是取消（与网页版 radio 的行为对齐不了，
                                    // 那里 radio 点不掉；这里允许取消是为了让用户能改主意，不会误提交）。
                                    !question.multiSelect -> if (checked) emptySet() else setOf(option.label)
                                    checked -> current - option.label
                                    else -> current + option.label
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(enabled = interactive) { toggle() }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (question.multiSelect) {
                                    Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = interactive)
                                } else {
                                    RadioButton(selected = checked, onClick = { toggle() }, enabled = interactive)
                                }
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                    option.description?.takeIf(String::isNotBlank)?.let { description ->
                                        Text(
                                            description,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!answered) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (submitting) {
                        Text(
                            stringResource(R.string.answer_submitting),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Button(
                        onClick = { if (complete) onSubmit(buildAnswers(set.questions, selected, custom)) },
                        enabled = interactive && complete,
                    ) {
                        Text(stringResource(R.string.submit_answer))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    state: HarnessState,
    sessionRunning: Boolean,
    attachments: List<PendingAttachment>,
    attachmentUploading: Boolean,
    onAttachImage: (name: String, sizeBytes: Long, mediaType: String, openInput: () -> InputStream) -> Unit,
    onAttachFile: (name: String, sizeBytes: Long, openInput: () -> InputStream) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSubmit: (steer: Boolean) -> Unit,
) {
    val canWrite = "chat.write" in state.device?.scopes.orEmpty()
    // 刻意**不**把 state.busy 放进可提交条件：全局 busy 连一次刷新都会置位。
    // 防重复提交靠「提交后立即清空输入框与待发附件」——清空后两者都空，按钮自然失效。
    // 同理：附件上传中也不禁用发送键（那会重犯「运行中就发不出消息」的老毛病），
    // 未上传完的文件干脆还不在待发列表里。
    val canSubmit = ComposerSendPolicy.canSubmit(value, canWrite) || (canWrite && attachments.isNotEmpty())
    val context = LocalContext.current
    // 图片走内核的内联 image part（不需要上传），文件要先换 receiptId。
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = attachmentName(context, uri)
            onAttachImage(name, attachmentSize(context, uri), ChatContentParts.imageMediaType(context.contentType(uri), name)) {
                requireNotNull(context.contentResolver.openInputStream(uri))
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = attachmentName(context, uri)
            onAttachFile(name, attachmentSize(context, uri)) { requireNotNull(context.contentResolver.openInputStream(uri)) }
        }
    }
    val defaultMode = ComposerSendPolicy.resolve(sessionRunning, state.busySendMode)
    val defaultIsSteer = defaultMode == BusySendMode.STEER
    // 「+」（网页版 #attachBtn）点开才选图片还是文件。默认收起：药丸里只有 + / 输入框 / 发送三样。
    var attachMenu by remember { mutableStateOf(false) }
    // 运行中提示不再单独占一行，而是并进占位符 —— 照网页版 chat.html:1399
    // 直接改 textarea 的 placeholder 的做法，省掉输入区上方那一行高度。
    // 占位符必须一行放得下（下面 maxLines = 1 + Ellipsis），所以只留「运行中 + 发送去哪」。
    val placeholder = stringResource(
        when (composerHint(sessionRunning, defaultIsSteer)) {
            ComposerHint.IDLE -> R.string.message_hint
            ComposerHint.RUNNING_QUEUE -> R.string.message_hint_running_queue
            ComposerHint.RUNNING_STEER -> R.string.message_hint_running_steer
        },
    )
    Column(Modifier.fillMaxWidth()) {
        CommandSuggestions(value, state.commands, onValueChange)
        // 一整条药丸（网页版 #row）：边框 + 圆角 22dp 由它统一画，
        // + / 输入框 / 发送都在里面，输入框自己不再画边框和圆角。
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                if (attachments.isNotEmpty() || attachmentUploading) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 3.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        attachments.forEach { attachment ->
                            AttachmentChip(attachment) { onRemoveAttachment(attachment.id) }
                        }
                        if (attachmentUploading) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.attachment_uploading),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // 底部对齐（网页版 #row 是 align-items:flex-end）：输入框长高时，
                    // `+` 与发送键跟着落在底部，而不是垂直居中飘在半空。
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // 附件总入口：点开两项（图片 / 文件），分别走既有的两个 launcher。
                    // launcher 仍在本 composable 作用域里创建（rememberLauncherForActivityResult
                    // 不能搬出 Composer）—— 只是触发它的人从两个平铺按钮换成了这个 +。
                    if (canWrite) {
                        Box {
                            IconButton(
                                onClick = { attachMenu = true },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    stringResource(R.string.add_attachment),
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attachment_pick_image)) },
                                    onClick = {
                                        attachMenu = false
                                        imageLauncher.launch("image/*")
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.ImageIcon, null) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attachment_pick_file)) },
                                    onClick = {
                                        attachMenu = false
                                        fileLauncher.launch(arrayOf("*/*"))
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                                )
                            }
                        }
                    }
                    /* 输入区高度：**随内容长高**（网页版 #ta 是 min-height:36px / max-height:120px）。
                     *
                     * 早先写死 height(34.dp)：敲到第二行文字就被裁掉下半截，看着像控件坏了
                     * （真机截图里第三行只剩个顶）。现在 44dp 起步、最多 120dp，
                     * 一行时 44dp、两行 60dp 左右，到顶后不再长。
                     * 注意：不能用 fillMaxSize —— 它会去撑满 heightIn 的上限（120dp），
                     * 变成「永远很高」。用 fillMaxWidth，让它按自己的内容定高。 */
                    Box(Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp).padding(horizontal = 4.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).onKeyEvent { event ->
                                // 物理键盘（外接键盘、桌面模式）的 Enter。
                                // 软键盘的「发送」键走下面的 KeyboardActions.onSend。
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
                                val alternate = event.isCtrlPressed || event.isMetaPressed
                                // 普通回车一律放行换行——软键盘的「换行」键也会派发 Key.Enter
                                // 到这里（输入法实现差异），不能只当物理键盘处理。
                                // 发送只走两条路：软键盘「发送」action 键 + 旁边发送按钮。
                                if (!alternate) return@onKeyEvent false
                                if (!canSubmit) return@onKeyEvent false
                                val steer = ComposerSendPolicy
                                    .resolve(sessionRunning, state.busySendMode, alternate) == BusySendMode.STEER
                                onSubmit(steer)
                                true
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary),
                            // 运行中才把软键盘的回车变成「发送」；空闲时保持默认换行。
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (sessionRunning) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (canSubmit) onSubmit(defaultIsSteer) },
                            ),
                        )
                    }
                    // 发送键：34dp 圆形主色底白字；不可发送时改成**透明底 + 灰字**，
                    // 而不是整体压透明度 —— 压透明度会连底色一起淡掉，看着像残废控件。
                    // 提交模式仍按设置走（这里传 defaultIsSteer），插话另有 ⋯ 菜单里的固定入口。
                    Surface(
                        onClick = { onSubmit(defaultIsSteer) },
                        enabled = canSubmit,
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = if (canSubmit) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (canSubmit) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send), Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 输入框占位符该显示哪一段。
 *
 * 原来「运行中回车会排队还是插话」是输入框**下方**单独一行 Text；照网页版
 * （chat.html:1399 直接改 textarea 的 placeholder）压进占位符之后，那一行就没了 ——
 * 占位符成了唯一说明。所以把「选哪一段」抽成纯函数并单测（ComposerPillTest），
 * 免得这种「只差一行字」的取舍在界面里悄悄错掉：选错资源在屏幕上只是一行小字。
 */
internal enum class ComposerHint { IDLE, RUNNING_QUEUE, RUNNING_STEER }

internal fun composerHint(sessionRunning: Boolean, steerIsDefault: Boolean): ComposerHint = when {
    // 空闲时没有可插话的轮次，设置里的「繁忙时默认行为」不该影响占位符。
    !sessionRunning -> ComposerHint.IDLE
    steerIsDefault -> ComposerHint.RUNNING_STEER
    else -> ComposerHint.RUNNING_QUEUE
}

/**
 * 流式思考条：消息区**下方**、输入区**上方**的一条固定位置。
 *
 * 口径全部对齐网页版 `#thinkbar`（chat.html:93-101 的样式、setThinking:909-919 的行为）：
 * - 只显示**当前**思考的一行，过长取**尾部** —— 思考是往前推进的，最新那段才有信息量；
 * - 一行放不下就右侧省略号截断，不换行、不把输入区顶下去；
 * - 没有思考时整条**不渲染**（而不是渲染成零高度）—— 空 Surface 照样占掉边框和 padding，
 *   输入区上方会永久多出一条缝。
 *
 * 为什么不放进消息流：它是「运行状态」而不是「会话内容」。插在列表里会随消息滚动、
 * 把正文挤开，一屏行里也根本找不着（这条原来是列表里的一个 ActivityRow）。
 */
@Composable
private fun ChatThinkBar(raw: String) {
    val text = thinkBarText(raw)
    if (text.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 1.5.dp,
                )
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 从流式这一段里取出「当前思考」的原文：只认 REASONING 块，按原顺序用空格接起来。
 *
 * 与网页版 `setLive`（chat.html:2170-2173）同一口径：`filter(kind === "reasoning")` 后
 * `join(" ")`。**只取流式**是刻意的 —— 历史里的思考已在列表层被过滤掉，
 * 翻历史时这条思考条必须消失，否则会拿一段旧思考冒充「正在想」。
 */
internal fun liveThinkingText(blocks: List<LiveChatBlock>): String =
    blocks.filter { it.kind == ChatItemKind.REASONING }.joinToString(" ") { it.text }

/**
 * 思考条那一行的文字：空白折成单个空格、去掉首尾，超过 [maxChars] 只留**尾部**。
 *
 * 抽成纯函数就为了能测（见 `ChatThinkBarTest`）：这条「留尾巴」的规则本身与渲染无关，
 * 但正是用户实际看到的行为，肉眼点界面覆盖不到边界（空串、恰好 140、含换行与缩进）。
 * 数值与网页版一致：`t.length > 140` 时取 `"…" + t.slice(-140)`。
 */
internal fun thinkBarText(raw: String, maxChars: Int = 140): String {
    val text = raw.replace(Regex("\\s+"), " ").trim()
    if (text.length <= maxChars) return text
    return "…" + text.takeLast(maxChars)
}

/**
 * 待发附件条上的一条：图片给缩略图，其他给图标；名称、体积与删除键都在这里。
 *
 * 缩略图按需**降采样**解码（只解到约 96px），而不是把 8 MiB 的原图整张读进 Bitmap —
 * 那样几张图就能把输入区卡住。
 */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    val thumbnail = attachment.imageBytes?.let { bytes ->
        remember(bytes) { decodeThumbnail(bytes) }
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 5.dp, end = 1.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small),
                )
            } else {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.widthIn(max = 132.dp)) {
                Text(
                    attachment.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    ChatContentParts.formatBytes(attachment.sizeBytes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    stringResource(R.string.attachment_remove),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * 缩略图解码：先用 `inJustDecodeBounds` 只读尺寸，再按 2 的幂降采样。
 * 解不出来（不是图片、已损坏）就返回 null，由调用方退回一个图标。
 */
private fun decodeThumbnail(bytes: ByteArray, maxPx: Int = 96): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() }.getOrNull()
}

/** 与「文件」页同款：从 ContentResolver 取显示名，取不到就退回 URI 的末段。 */
private fun attachmentName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
}

private fun attachmentSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
    }
    return -1L
}

private fun Context.contentType(uri: Uri): String? = contentResolver.getType(uri)

@Composable
private fun TodoPanel(todos: List<TodoItem>, expanded: Boolean, onToggle: () -> Unit) {
    val completeCount = todos.count { it.status == "completed" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    stringResource(R.string.current_tasks),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.task_progress, completeCount, todos.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    todos.forEach { todo ->
                        val (icon, tint, statusLabel) = when (todo.status) {
                            "completed" -> Triple(
                                Icons.Outlined.CheckCircle,
                                MaterialTheme.colorScheme.secondary,
                                stringResource(R.string.task_completed),
                            )
                            "in_progress" -> Triple(
                                Icons.Outlined.Edit,
                                MaterialTheme.colorScheme.primary,
                                stringResource(R.string.task_in_progress),
                            )
                            else -> Triple(
                                Icons.Outlined.RadioButtonUnchecked,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                stringResource(R.string.task_pending),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(icon, statusLabel, Modifier.size(18.dp), tint = tint)
                            Text(
                                todo.content,
                                modifier = Modifier.weight(1f),
                                color = if (todo.status == "completed") {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CommandSuggestions(
    value: String,
    commands: List<CommandDescriptor>,
    onSelect: (String) -> Unit,
) {
    val query = value.removePrefix("/")
    val suggestions = if (
        value.startsWith("/") && query.none { it.isWhitespace() }
    ) {
        commands.filter { it.name.startsWith(query, ignoreCase = true) }.take(5)
    } else emptyList()
    AnimatedVisibility(visible = suggestions.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(
                    stringResource(R.string.available_commands),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                suggestions.forEach { command ->
                    val supportingText = localizedCommandDescription(command) ?: command.description.ifBlank {
                        command.input?.hint?.let { stringResource(R.string.command_input_hint, it) }
                            ?: stringResource(R.string.command_input_hint, "")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { onSelect("/${command.name} ") }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Code, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Text("/${command.name}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                supportingText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedCommandDescription(command: CommandDescriptor): String? = when (command.name) {
    "compact" -> stringResource(R.string.command_compact_description)
    "export" -> stringResource(R.string.command_export_description)
    "feedback" -> stringResource(R.string.command_feedback_description)
    "goal" -> stringResource(R.string.command_goal_description)
    "permission" -> stringResource(R.string.command_permission_description)
    "plan" -> stringResource(R.string.command_plan_description)
    else -> null
}

@Composable
private fun PermissionMenuItems(
    value: PermissionSelect,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onRequestFullAccess: () -> Unit,
) {
    if (value.options.isEmpty()) return
    val current = value.options.find { it.value == value.currentValue }
    Text(
        stringResource(R.string.access_mode) + " · " + permissionLabel(value.currentValue, current?.name),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
    value.options.filterNot { it.value == "custom" }.forEach { option ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(permissionLabel(option.value, option.name))
                    option.description?.takeIf(String::isNotBlank)?.let { description ->
                        Text(
                            description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            onClick = {
                if (option.value == "danger-full-access") onRequestFullAccess()
                else onSelect(option.value)
            },
            enabled = enabled,
            leadingIcon = { Icon(permissionIcon(option.value), null) },
            trailingIcon = {
                if (option.value == value.currentValue) Icon(Icons.Outlined.Check, null)
            },
        )
    }
}

/**
 * 完整访问的二次确认。
 *
 * 从原 [PermissionControl] 原样搬出来，要求一个字没改：必须勾选 acknowledged，
 * 确认键才可用。对话框自己持有 acknowledged，关掉再打开一定是未勾选状态
 * （原来也是在打开之前把 acknowledged 重置为 false）。
 */
@Composable
private fun FullAccessConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text(stringResource(R.string.permission_full_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.permission_full_access_warning))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { acknowledged = !acknowledged },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(stringResource(R.string.permission_full_access_ack), Modifier.padding(start = 4.dp))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = acknowledged) {
                Text(stringResource(R.string.enable_full_access))
            }
        },
    )
}

@Composable
private fun permissionLabel(value: String?, fallback: String?): String = when (value) {
    "read-only" -> stringResource(R.string.permission_read_only)
    "workspace-write" -> stringResource(R.string.permission_workspace_write)
    "danger-full-access" -> stringResource(R.string.permission_full_access)
    "custom" -> stringResource(R.string.permission_custom)
    else -> fallback ?: value.orEmpty()
}

private fun permissionIcon(value: String?): ImageVector = when (value) {
    "workspace-write" -> Icons.Outlined.Edit
    "danger-full-access" -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.Lock
}

@Composable
private fun ChatItem(
    item: ChatDisplayItem,
    images: Map<String, ByteArray> = emptyMap(),
    failures: Map<String, String> = emptyMap(),
    onLoadImage: (String) -> Unit = {},
    onAnswer: (String) -> Unit = {},
    genUiDebug: Boolean = false,
) {
    val context = LocalContext.current
    // 复制全文的手势。
    //
    // 为什么是**双击**而不是长按：早期版本给整条消息挂 `combinedClickable(onLongClick = …)`，
    // 结果长按事件被它吃掉，`SelectionContainer` 的选择手柄永远弹不出来 ——
    // 用户只能整段复制，想复制中间一句做不到（他的原话：「不能选择性复制，只能复制整段」）。
    //
    // 关键在 Compose 的手势竞争：`combinedClickable` 会**消费**长按事件，子节点里的
    // `SelectionContainer` 再也收不到，手柄就不出现。把长按让出去、改用双击之后，
    // 选择与复制两个手势各归各的，互不干扰。
    val copyable = item.body.isNotBlank()
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (copyable) {
                    Modifier.combinedClickable(
                        onClick = {},
                        // 双击：命中窗口比单击短得多，但比长按安全 —— 长按要留给选择。
                        onDoubleClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", item.body))
                            toast(context, context.getString(R.string.copied))
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
    // 「长按可选中复制片段」这句提示以前是**空头支票**：整条消息的渲染路径里
    // 根本没有 SelectionContainer，长按自然什么也不弹（用户原话：「双击不出来选择项」）。
    //
    // 手势怎么分：外层 combinedClickable 只声明 onClick/onDoubleClick、**不声明 onLongClick**，
    // 所以它不会消费长按 —— 长按穿透到内层的 SelectionContainer，弹出选择手柄；
    // 双击仍由外层吃掉，复制整条。**千万别给外层补 onLongClick**，那会把长按吃掉、
    // 选择手柄又弹不出来（早期版本正是这么翻车的）。
    //
    // 为什么包在 Box 里面而不是给每个 Text 单独包：助手消息正文是第三方 Markdown
    // 组件渲染的（com.mikepenz.markdown.m3），里面有多少个 Text 由它决定，
    // 在外层包一次就能覆盖全部段落、代码块、列表项。
    SelectionContainer {
    when (item.kind) {
        ChatItemKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                modifier = Modifier.fillMaxWidth(0.78f).widthIn(max = 560.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 文字可以为空 —— 只发一张图不打字是完全正常的用法，
                // 旧实现直接丢弃这种消息，所以图片在 App 里整条不见。
                if (item.body.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            item.body,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                MessageAttachments(item.attachments, images, failures, onLoadImage)
            }
        }
        ChatItemKind.ASSISTANT -> AssistantBody(
            body = item.body,
            streaming = item.streaming,
            onAnswer = onAnswer,
            genUiDebug = genUiDebug,
        )
        // 终局失败：宁可多一个分支，也不要让这条消息落进 else 变成一行空的活动行。
        ChatItemKind.FAILURE -> item.failure?.let { TurnFailureRow(it) }
        else -> ActivityRow(item)
    }
    } // SelectionContainer：长按选词，双击（外层 Box）复制整条
    }
}

/**
 * 助手正文：**文字走 Markdown，卡片走原生组件**。
 *
 * 为什么不能让整段都交给 Markdown：dsh-ui 围栏会被当成普通代码块，
 * 用户看到的是一坨裸 JSON —— 这正是「网页版弹的问题，手机上弹不出来」的根因。
 *
 * 两个退化路径都刻意保留：
 * - 围栏**未闭合**（流式输出中）→ 整段按 Markdown 渲染，等闭合了再切；
 * - 围栏**JSON 坏了** → 该段回落成代码块，而不是让整条消息消失。
 * 用户宁可看到原始内容，也不要看到空白。
 */
@Composable
private fun AssistantBody(
    body: String,
    streaming: Boolean,
    onAnswer: (String) -> Unit,
    genUiDebug: Boolean,
) {
    val segments = remember(body) { splitMessageBody(body) }
    // 诊断必须独立于渲染分支算：只有这样才能暴露「解析器说成功、屏幕上却没有卡片」这类不一致。
    val diagnosis = remember(body, genUiDebug) { if (genUiDebug) diagnoseGenUi(body) else null }
    val plain = segments.all { it is MessageSegment.Prose }
    if (plain) {
        Column {
            Markdown(
                content = body,
                typography = compactMarkdownTypography(),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
            diagnosis?.let { GenUiDebugLine(it) }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Prose -> Markdown(
                    content = segment.text,
                    typography = compactMarkdownTypography(),
                    modifier = Modifier.fillMaxWidth(),
                )

                is MessageSegment.Ui -> {
                    val spec = remember(segment.json) { parseGenUi(segment.json) }
                    if (spec == null) {
                        // 解析失败不吞内容：回落成代码块，用户至少还能看到原文。
                        Markdown(
                            content = "```json\n${segment.json}\n```",
                            typography = compactMarkdownTypography(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        GenUiCard(spec = spec, onAnswer = onAnswer)
                    }
                }
            }
        }
        diagnosis?.let { GenUiDebugLine(it) }
    }
}

/**
 * dsh-ui 诊断行。
 *
 * 点一下在「摘要 / 全文」之间切换：一行灰字塞不下全部信息，但用户截图发回来就够了，
 * 所以摘要页也必须能展开 —— 否则最关键的那句报错会被截断掉，等于白测。
 */
@Composable
private fun GenUiDebugLine(diagnosis: GenUiDiagnosis) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("genui-debug", diagnosis.details))
                toast(context, context.getString(R.string.copied))
            }),
    ) {
        Text(
            diagnosis.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (expanded) {
            Text(
                diagnosis.details,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 渲染一条消息附带的图片。
 *
 * 三态是刻意的：**下载中**显示占位、**失败**显示可读的原因、**成功**显示图片。
 * 少了中间态，慢网络下用户会以为图丢了；把失败也当成"还在加载"，用户会一直等下去。
 *
 * 解码失败（字节取到了但不是有效图片）单独区分：那说明数据有问题，与网络无关，
 * 提示也不该让用户去重试网络。
 */
@Composable
private fun MessageAttachments(
    attachments: List<ChatAttachment>,
    images: Map<String, ByteArray>,
    failures: Map<String, String>,
    onLoadImage: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = Modifier.padding(top = 4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        attachments.forEach { attachment ->
            val bytes = images[attachment.attachmentId]
            LaunchedEffect(attachment.attachmentId) { onLoadImage(attachment.attachmentId) }
            when {
                bytes != null -> {
                    val bitmap = remember(bytes) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bitmap == null) {
                        AttachmentNotice(stringResource(R.string.attachment_undecodable))
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .heightIn(max = 340.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                }

                attachment.attachmentId in failures ->
                    AttachmentNotice(failures[attachment.attachmentId] ?: stringResource(R.string.attachment_unavailable))

                else -> Box(
                    Modifier
                        .size(88.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

@Composable
private fun AttachmentNotice(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * 一轮模型运行的终局失败行。
 *
 * 为什么单独一个 Composable 而不是塞进 [ActivityRow]：失败必须有**自己的颜色和图标**，
 * 而且不能被"折叠成一行摘要" —— 用户要的就是那句"为什么失败了"。
 * 以前它压根不到客户端：插件把 `turn/end` 的 reason 整段丢掉，客户端收到的
 * 与"正常跑完"一模一样，于是转圈等到天荒地老。
 *
 * 三行信息各自有用，缺一行就得靠猜：
 * - 标题：「本轮运行失败」/「任务已停止」（后者是登出，不是模型故障）；
 * - 正文：服务端的原话（429 限流那句话就在这里）；
 * - 错误码：`code`（可能为空串，空也占位，免得行高忽高忽低）。
 */
@Composable
private fun TurnFailureRow(failure: ChatTurnFailure) {
    val context = LocalContext.current
    // 出错文案跟随界面语言：命中错误码就用本地化句子；命不中才退回内核给的原文
    //（英文，但至少是信息，也方便贴给开发者）。**标题与正文都不再写死在 data 层**——
    // 以前那里写死中文，英文界面下照样弹中文。
    // 错误码本身照常显示：它不随语言变，是给排查用的锚点。
    val headline = stringResource(failure.titleRes)
    val localizedRes = localizedErrorRes(failure.code)
    val detail = when {
        localizedRes == null -> failure.message
        // 兜底那条要带上错误码，否则「操作没能完成」等于没说。
        localizedRes == R.string.err_request_generic -> context.getString(localizedRes, failure.code.orEmpty())
        else -> stringResource(localizedRes)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            // 长按复制整条：把 request id 带给电脑端排查是唯一有效的手段。
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "turn-failure",
                            listOfNotNull(headline, detail.ifBlank { null }, failure.code).joinToString("\n"),
                        ),
                    )
                    toast(context, context.getString(R.string.copied))
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.WarningAmber,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                headline,
                modifier = Modifier.padding(start = 7.dp),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
            )
            if (!failure.code.isNullOrEmpty()) {
                Text(
                    failure.code,
                    modifier = Modifier.padding(start = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ActivityRow(item: ChatDisplayItem) {
    // 工具行在合并成一组（多条调用）之后也要能展开看细节；
    // 单次调用的那一行没什么可展的，展开反而是噪音。
    val multiTool = item.kind == ChatItemKind.TOOL && item.body.contains('\n')
    // 有逐条明细（输入 / 输出 / 耗时）就一定可展开 —— 这就是「工具调用详情」的入口。
    val hasTools = item.tools.isNotEmpty()
    val expandable = hasTools || (item.body.isNotBlank() && (item.kind != ChatItemKind.TOOL || multiTool))
    var expanded by remember(item.id) { mutableStateOf(false) }
    // REASONING 这条分支现在只是兜底：历史（historyItems）与流式（liveItems）两处
    // 都已把思考挡在消息流之外，改由底部思考条 [ChatThinkBar] 承载。
    // 万一哪天漏进来一条，至少还显示得出来，而不是变成一行空白。
    val label = when (item.kind) {
        ChatItemKind.CONTEXT -> stringResource(R.string.context_injection)
        ChatItemKind.REASONING -> stringResource(R.string.think)
        ChatItemKind.TOOL -> item.title ?: stringResource(R.string.tool_call)
        else -> ""
    }
    val icon = when (item.kind) {
        ChatItemKind.CONTEXT -> Icons.Outlined.Description
        ChatItemKind.REASONING -> Icons.Outlined.Code
        else -> Icons.Outlined.Build
    }
    // 失败的工具**不展开也要看得见**：用户点开详情最主要的理由就是「刚才那条命令报了什么错」。
    val failed = item.tools.any { it.failed }
    val fallbackName = stringResource(R.string.tool_call)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (failed) Icons.Outlined.WarningAmber else icon,
                null,
                modifier = Modifier.size(16.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                modifier = Modifier.padding(start = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
            )
            item.title?.takeIf { item.kind == ChatItemKind.CONTEXT }?.let {
                Text(" · $it", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 折叠时的预览：优先用原来的正文首行；正文为空（例如只有参数的调用）就退回工具名，
            // 否则这一行看起来像空条目。
            val preview = item.body.lineSequence().firstOrNull().orEmpty().ifBlank {
                item.tools.joinToString("，") { it.name.ifBlank { fallbackName } }
            }
            if (!expanded && preview.isNotBlank()) {
                Text(
                    " · $preview",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else Spacer(Modifier.weight(1f))
            if (failed) {
                Text(
                    stringResource(R.string.tool_detail_failed),
                    modifier = Modifier.padding(end = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expandable) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(expanded) {
            // 有明细就渲染明细（合并组里**每条**一个块），否则退回原来的 Markdown 正文。
            if (hasTools) {
                Column(Modifier.padding(start = 27.dp, end = 6.dp, bottom = 8.dp)) {
                    item.tools.forEachIndexed { index, tool ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        ToolCallDetailBlock(tool)
                    }
                }
            } else {
                Markdown(
                    content = item.body,
                    typography = compactMarkdownTypography(),
                    modifier = Modifier.padding(start = 27.dp, end = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * 一条工具调用的详情块：工具名 + 状态 + 耗时，然后是完整输入与完整输出。
 *
 * 数据全部来自 [ChatDisplayItem.tools]（结构见 data/ChatPresentation.kt 的 [ToolCallDetail]）：
 * 输入是内核原样的 `arguments` 字符串，缩进后显示；输出是 `tool/result` 的 content 里
 * text 段拼起来的结果。两者都能一键复制 —— 手机上开不了终端，
 * 把这段输出复制出来发给自己，是唯一能把现场带走的办法。
 *
 * 长文本必须能滚：实测单条结果最长 50000 字符（p50 277、p90 2809），
 * 不设高度上限会把整屏撑爆，连「往上翻」都做不到。
 */
@Composable
private fun ToolCallDetailBlock(tool: ToolCallDetail) {
    // 状态只有三种：有结果且 isError、有结果没报错、还没有结果（仍在跑）。
    // 注意 isError=false **不代表命令没报错** —— 实测 curl 超时那条也是 false，
    // 所以这里只如实转述内核的标记，不做自己的判断。
    val status = when {
        tool.result == null -> stringResource(R.string.tool_detail_pending)
        tool.failed -> stringResource(R.string.tool_detail_failed)
        else -> stringResource(R.string.tool_detail_ok)
    }
    val statusColor = when {
        tool.failed -> MaterialTheme.colorScheme.error
        tool.result == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tool.name.ifBlank { stringResource(R.string.tool_call) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 耗时：算不出来就没有这一行（不显示 0、也不显示负数）。
            tool.durationMs?.let { duration ->
                Text(
                    formatToolDuration(duration),
                    modifier = Modifier.padding(end = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                status,
                color = statusColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val arguments = prettyToolArguments(tool.arguments)
        if (arguments.isBlank()) {
            Text(
                stringResource(R.string.tool_detail_no_args),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            ToolTextBlock(
                label = stringResource(R.string.tool_detail_input),
                text = arguments,
                isError = false,
                maxHeightDp = 160,
            )
        }
        when {
            tool.result == null -> Text(
                stringResource(R.string.tool_detail_no_result),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            tool.result.isBlank() -> Text(
                stringResource(R.string.tool_detail_empty_result),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            else -> ToolTextBlock(
                label = stringResource(if (tool.failed) R.string.tool_detail_error else R.string.tool_detail_output),
                text = tool.result,
                isError = tool.failed,
                maxHeightDp = 260,
            )
        }
    }
}

/**
 * 一段可滚动、可复制、等宽显示的工具文本（输入或输出）。
 *
 * 两个轴都要能滚：终端输出经常是「很宽的一行 + 很多行」，
 * 只给纵向滚动的话宽行会被硬折行，缩进层次就全乱了；所以 `softWrap = false` +
 * 横向滚动，保持原始排版。
 *
 * 失败用 error 色描边 + error 色正文：这是用户来查这个视图时最想先看到的东西。
 */
@Composable
private fun ToolTextBlock(label: String, text: String, isError: Boolean, maxHeightDp: Int) {
    val context = LocalContext.current
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isError) FontWeight.Medium else FontWeight.Normal,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.tool_detail_copy),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("tool-detail", text))
                            toast(context, context.getString(R.string.copied))
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeightDp.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun compactMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineMedium,
    h2 = MaterialTheme.typography.headlineSmall,
    h3 = MaterialTheme.typography.titleLarge,
    h4 = MaterialTheme.typography.titleMedium,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.titleSmall,
    text = MaterialTheme.typography.bodyMedium,
    code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    quote = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium,
    table = MaterialTheme.typography.bodySmall,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    val sessionModels = state.sessionModels
    val session = state.sessions.find { it.id == state.selectedSessionId }
    val currentPreset = session?.agentPreset
        ?: state.agentPresets.firstOrNull { it.isDefault && it.available }?.id
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.session_configuration), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.session_configuration_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
                if (state.agentPresets.isNotEmpty()) {
                    item("agent-heading") {
                        Text(
                            stringResource(R.string.agent_mode),
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    items(state.agentPresets, key = { "agent:${it.id}" }) { preset ->
                        AgentPresetRow(
                            preset = preset,
                            selected = preset.id == currentPreset,
                            enabled = session?.blank == true && preset.available &&
                                "chat.write" in state.device?.scopes.orEmpty() && !state.busy,
                            onClick = { viewModel.selectAgentPreset(preset.id) },
                        )
                    }
                    item("agent-lock-note") {
                        Text(
                            if (session?.blank == true) stringResource(R.string.agent_change_before_first_message)
                            else stringResource(R.string.agent_locked_after_start),
                            modifier = Modifier.padding(top = 7.dp, bottom = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (sessionModels == null) {
                    item("model-loading") {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                } else {
                    sessionModels.groups.forEach { group ->
                        item(group.id) {
                            Text(
                                group.name,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        items(group.models, key = { "${group.id}:${it.id}" }) { model ->
                            val selected = sessionModels.current.provider == group.id && sessionModels.current.model == model.id
                            ModelRow(group.id, model, selected, sessionModels.current, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPresetRow(
    preset: AgentPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (preset.isDefault) stringResource(R.string.agent_default_format, preset.name) else preset.name,
                color = if (enabled || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
            preset.description?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(
    providerId: String,
    model: ModelView,
    selected: Boolean,
    current: ModelSelection,
    viewModel: HarnessViewModel,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                viewModel.selectModel(
                    ModelSelection(
                        providerId,
                        model.id,
                        model.reasoning?.defaultEffort,
                    ),
                )
            }
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                model.description?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.secondary)
        }
        if (selected && !model.reasoning?.efforts.isNullOrEmpty()) {
            Text(
                stringResource(R.string.reasoning_effort),
                modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                model.reasoning.efforts.forEach { effort ->
                    val active = current.reasoningEffort == effort.id
                    Surface(
                        onClick = { viewModel.selectModel(ModelSelection(providerId, model.id, effort.id)) },
                        shape = CircleShape,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(effort.name, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceManagementSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    var renameTarget by remember { mutableStateOf<ChatWorkspace?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatWorkspace?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.manage_workspaces), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.manage_workspaces_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.chatWorkspaces.isEmpty()) {
                EmptyState(
                    Icons.Outlined.FolderOpen,
                    stringResource(R.string.no_chat_workspaces),
                    Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.chatWorkspaces, key = { it.id }) { workspace ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(workspace.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "/${workspace.path}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = { titleDraft = workspace.title; renameTarget = workspace },
                                enabled = !state.busy,
                            ) { Icon(Icons.Outlined.Edit, stringResource(R.string.rename_workspace)) }
                            IconButton(onClick = { deleteTarget = workspace }, enabled = !state.busy) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    stringResource(R.string.remove_workspace),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    renameTarget?.let { workspace ->
        RenameDialog(
            title = stringResource(R.string.rename_workspace),
            value = titleDraft,
            onValueChange = { titleDraft = it },
            onConfirm = { viewModel.renameWorkspace(workspace.id, titleDraft); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.remove_workspace_title)) },
            text = { Text(stringResource(R.string.remove_workspace_warning, workspace.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteWorkspace(workspace.id); deleteTarget = null }) {
                    Text(stringResource(R.string.remove_workspace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun RenameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    var workspaceId by remember(state.chatWorkspaces) { mutableStateOf(state.chatWorkspaces.firstOrNull()?.id.orEmpty()) }
    var createWorkspace by remember { mutableStateOf(false) }
    var presetId by remember(state.agentPresets) {
        mutableStateOf(state.agentPresets.firstOrNull { it.isDefault && it.available }?.id.orEmpty())
    }
    var workspaceMenu by remember { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    LaunchedEffect(createWorkspace) {
        if (createWorkspace && state.workspacePickerDirectory == null) viewModel.prepareWorkspacePicker()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 18.dp).padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.new_session), style = MaterialTheme.typography.headlineSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                SegmentedButton(
                    selected = !createWorkspace,
                    onClick = { createWorkspace = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.existing_workspace)) }
                SegmentedButton(
                    selected = createWorkspace,
                    onClick = { createWorkspace = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.new_workspace)) }
            }

            if (!createWorkspace) {
                PickerHeading(stringResource(R.string.dhs_workspace))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { workspaceMenu = true },
                        enabled = state.chatWorkspaces.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text(
                            state.chatWorkspaces.find { it.id == workspaceId }?.title
                                ?: stringResource(R.string.no_chat_workspaces),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = workspaceMenu, onDismissRequest = { workspaceMenu = false }) {
                        state.chatWorkspaces.forEach { workspace ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(workspace.title)
                                        Text(
                                            "/${workspace.path}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = { workspaceId = workspace.id; workspaceMenu = false },
                            )
                        }
                    }
                }
            } else {
                PickerHeading(stringResource(R.string.authorized_root))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { rootMenu = true },
                        enabled = state.roots.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text(
                            state.roots.find { it.id == state.workspacePickerRootId }?.label
                                ?: stringResource(R.string.no_roots),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        state.roots.forEach { root ->
                            DropdownMenuItem(
                                text = { Text(root.label) },
                                onClick = { viewModel.selectWorkspacePickerRoot(root.id); rootMenu = false },
                            )
                        }
                    }
                }
                PickerHeading(stringResource(R.string.workspace_directory))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewModel.loadWorkspacePickerDirectory(
                                    state.workspacePickerPath.substringBeforeLast('/', ""),
                                )
                            },
                            enabled = state.workspacePickerPath.isNotEmpty() && !state.busy,
                        ) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.go_up)) }
                        Text(
                            "/${state.workspacePickerPath}",
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val directories = state.workspacePickerDirectory?.entries.orEmpty().filter { it.kind == "directory" }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 80.dp).padding(top = 4.dp),
                ) {
                    if (directories.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_subdirectories),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(directories, key = { it.path }) { directory ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                viewModel.loadWorkspacePickerDirectory(directory.path)
                            }.padding(horizontal = 6.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Text(directory.name, Modifier.weight(1f).padding(start = 10.dp))
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                        }
                    }
                }
            }

            if (state.agentPresets.isNotEmpty()) {
                PickerHeading(stringResource(R.string.agent_mode))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { presetMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.Build, null)
                        Text(
                            state.agentPresets.find { it.id == presetId }?.name
                                ?: stringResource(R.string.server_default),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.server_default)) },
                            onClick = { presetId = ""; presetMenu = false },
                        )
                        state.agentPresets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.name)
                                        preset.description?.let { description ->
                                            Text(
                                                description,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                },
                                enabled = preset.available,
                                onClick = { presetId = preset.id; presetMenu = false },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (createWorkspace) {
                        viewModel.createWorkspaceAndSession(
                            requireNotNull(state.workspacePickerRootId),
                            state.workspacePickerPath,
                            presetId,
                        )
                    } else {
                        viewModel.createSession(workspaceId, presetId)
                    }
                    onClose()
                },
                enabled = !state.busy && if (createWorkspace) {
                    state.workspacePickerRootId != null && state.workspacePickerDirectory != null
                } else workspaceId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                shape = CircleShape,
            ) {
                Text(stringResource(if (createWorkspace) R.string.create_workspace_and_session else R.string.create_session))
            }
        }
    }
}

@Composable
private fun PickerHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 14.dp, bottom = 5.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun SelectionRow(
    title: String,
    supporting: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (supporting.isNotBlank()) {
                Text(
                    supporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.secondary)
    }
}
