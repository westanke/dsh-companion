package io.github.hakunm.deepseekharness.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.hakunm.deepseekharness.data.ChatDisplayItem
import io.github.hakunm.deepseekharness.data.ChatItemKind
import io.github.hakunm.deepseekharness.data.ChatLinkTarget
import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.data.ChatWorkspace
import io.github.hakunm.deepseekharness.data.AgentPreset
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.data.CommandDescriptor
import io.github.hakunm.deepseekharness.data.ComposerSendPolicy
import io.github.hakunm.deepseekharness.data.ModelSelection
import io.github.hakunm.deepseekharness.data.ModelView
import io.github.hakunm.deepseekharness.data.PendingApproval
import io.github.hakunm.deepseekharness.data.PermissionSelect
import io.github.hakunm.deepseekharness.data.ResolvedPath
import io.github.hakunm.deepseekharness.data.SessionFileRef
import io.github.hakunm.deepseekharness.data.SessionFileRefs
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
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            SessionPane(state, viewModel, { createSheet = true }, { workspaceSheet = true }, Modifier.width(304.dp).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            ChatDetail(state, viewModel, null, Modifier.weight(1f).fillMaxHeight())
        }
    } else if (selected == null) {
        SessionPane(state, viewModel, { createSheet = true }, { workspaceSheet = true }, Modifier.fillMaxSize())
    } else {
        ChatDetail(state, viewModel, { viewModel.selectSession(null) }, Modifier.fillMaxSize())
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
    var message by remember(selected.id) { mutableStateOf("") }
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
    val liveItems = state.liveChat?.takeIf { it.sessionId == selected.id }?.displayItems().orEmpty()
    val displayItems = state.history?.displayItems().orEmpty() + liveItems
    val todos = state.history?.todoItems().orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(displayItems.size, state.liveChat?.revision) {
        if (displayItems.isNotEmpty()) {
            if (liveItems.isNotEmpty()) listState.scrollToItem(displayItems.lastIndex)
            else listState.animateScrollToItem(displayItems.lastIndex)
        }
    }

    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
        Column(modifier.imePadding()) {
            ConversationHeader(selected, state, viewModel, onBack, onOpenFiles = { filesSheet = true })
            if (todos.isNotEmpty()) {
                TodoPanel(todos, tasksExpanded, onToggle = { tasksExpanded = !tasksExpanded })
            }
            LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                )
            }
        }
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
                onModelClick = { modelSheet = true },
                onPermissionSelect = viewModel::selectPermissionPreset,
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

@Composable
private fun SessionFileList(state: HarnessState, onOpen: (String) -> Unit) {
    val history = state.history
    // 解析整段历史是有成本的；只在历史对象变化时重算，而不是每次重组都跑一遍。
    val refs = remember(history) { SessionFileRefs.refs(history?.events.orEmpty()) }
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
                    SelectionContainer {
                        Text(
                            preview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
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

@Composable
private fun SessionFileMeta(resolved: ResolvedPath) {
    val size = resolved.size?.let(::formatFileSize)
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
    onModelClick: () -> Unit,
    onPermissionSelect: (String) -> Unit,
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
    val current = state.sessionModels?.current
    val session = state.sessions.find { it.id == state.selectedSessionId }
    val agentName = state.agentPresets.find { it.id == session?.agentPreset }?.name ?: session?.agentPreset
    Column(Modifier.fillMaxWidth()) {
        CommandSuggestions(value, state.commands, onValueChange)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
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
                Box(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 3.dp, vertical = 2.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.message_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxSize().onKeyEvent { event ->
                            // 物理键盘（外接键盘、桌面模式）的 Enter。
                            // 软键盘的「发送」键走下面的 KeyboardActions.onSend。
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
                            val alternate = event.isCtrlPressed || event.isMetaPressed
                            // 空闲且没按修饰键时放行，让 Enter 保持换行（多行输入是正常需求）。
                            if (!sessionRunning && !alternate) return@onKeyEvent false
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onModelClick,
                        enabled = state.sessionModels != null && state.sessionModels.routable,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Outlined.ModelTraining, null, Modifier.size(18.dp))
                        Text(
                            listOfNotNull(agentName, current?.let { "${it.provider} · ${it.model}" })
                                .joinToString(" · ")
                                .ifBlank { stringResource(R.string.model) },
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                    PermissionControl(
                        value = state.history?.permissionSelect(),
                        enabled = canWrite && !state.busy,
                        onSelect = onPermissionSelect,
                    )
                    // 两个附件入口只在有 chat.write 时出现 —— 没有写权限时选了也发不出去。
                    if (canWrite) {
                        IconButton(onClick = { imageLauncher.launch("image/*") }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.ImageIcon, stringResource(R.string.attach_image), Modifier.size(19.dp))
                        }
                        IconButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.AttachFile, stringResource(R.string.attach_file), Modifier.size(19.dp))
                        }
                    }
                    // 运行中，在发送键旁标出「回车会做什么」——否则用户只能靠试。
                    if (sessionRunning) {
                        Text(
                            stringResource(
                                if (defaultIsSteer) R.string.send_mode_steer else R.string.send_mode_queue,
                            ),
                            modifier = Modifier.padding(end = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.steer)) } },
                        state = rememberTooltipState(),
                    ) {
                        // 插话按钮保留为**显式入口**，固定走 steer，不受设置项影响 ——
                        // 设置项管的是「默认」，而它管的是「我要插话，就现在」。
                        IconButton(onClick = { onSubmit(true) }, enabled = canSubmit) {
                            Icon(Icons.Outlined.SubdirectoryArrowRight, stringResource(R.string.steer))
                        }
                    }
                    Surface(
                        onClick = { onSubmit(defaultIsSteer) },
                        enabled = canSubmit,
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = if (canSubmit) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (canSubmit) {
                            MaterialTheme.colorScheme.onPrimary
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send), Modifier.padding(11.dp))
                    }
                }
            }
        }
    }
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
private fun PermissionControl(
    value: PermissionSelect?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (value == null || value.options.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val current = value.options.find { it.value == value.currentValue }
    val currentLabel = permissionLabel(value.currentValue, current?.name)

    Box {
        TextButton(
            onClick = { menuOpen = true },
            enabled = enabled,
            modifier = Modifier.widthIn(max = 124.dp).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
        ) {
            Icon(permissionIcon(value.currentValue), null, Modifier.size(18.dp))
            Text(
                currentLabel,
                modifier = Modifier.padding(start = 5.dp).weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                stringResource(R.string.access_mode),
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
                        menuOpen = false
                        if (option.value == "danger-full-access") {
                            acknowledged = false
                            confirmFullAccess = true
                        } else onSelect(option.value)
                    },
                    leadingIcon = { Icon(permissionIcon(option.value), null) },
                    trailingIcon = {
                        if (option.value == value.currentValue) Icon(Icons.Outlined.Check, null)
                    },
                )
            }
        }
    }

    if (confirmFullAccess) {
        AlertDialog(
            onDismissRequest = { confirmFullAccess = false; acknowledged = false },
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
                TextButton(onClick = { confirmFullAccess = false; acknowledged = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmFullAccess = false
                        acknowledged = false
                        onSelect("danger-full-access")
                    },
                    enabled = acknowledged,
                ) {
                    Text(stringResource(R.string.enable_full_access))
                }
            },
        )
    }
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
    failures: Set<String> = emptySet(),
    onLoadImage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // 长按复制。
    //
    // 为什么不只依赖 SelectionContainer：它在手机上要长按进入选择模式、再拖手柄选范围，
    // 在滚动的消息流里很难用，而且用户根本不知道该这么操作（他的原话就是「会话内容无法复制」）。
    // 这里额外给一个**一键复制全文**的手势；SelectionContainer 保留，两者互为补充：
    // 想复制片段就选中，想复制整条就长按。
    //
    // 只对非空正文挂手势：工具调用那类没有正文的行长按了也没有意义，不该有反馈。
    val copyable = item.body.isNotBlank()
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (copyable) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
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
        ChatItemKind.ASSISTANT -> Markdown(
            content = item.body,
            typography = compactMarkdownTypography(),
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 2.dp, vertical = 2.dp),
        )
        else -> ActivityRow(item)
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
    failures: Set<String>,
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
                    AttachmentNotice(stringResource(R.string.attachment_unavailable))

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

@Composable
private fun ActivityRow(item: ChatDisplayItem) {
    val expandable = item.kind != ChatItemKind.TOOL && item.body.isNotBlank()
    var expanded by remember(item.id) { mutableStateOf(false) }
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
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (!expanded && item.body.isNotBlank()) {
                Text(
                    " · ${item.body.lineSequence().first()}",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else Spacer(Modifier.weight(1f))
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
            Markdown(
                content = item.body,
                typography = compactMarkdownTypography(),
                modifier = Modifier.padding(start = 27.dp, end = 6.dp, bottom = 8.dp),
            )
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
                        model.reasoning?.defaultEffort ?: current.reasoningEffort,
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
