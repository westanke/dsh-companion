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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Lock
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
import kotlinx.coroutines.delay
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
import io.github.westanke.dshcompanion.createSession
import io.github.westanke.dshcompanion.createWorkspaceAndSession
import io.github.westanke.dshcompanion.deleteWorkspace
import io.github.westanke.dshcompanion.loadWorkspacePickerDirectory
import io.github.westanke.dshcompanion.prepareWorkspacePicker
import io.github.westanke.dshcompanion.renameWorkspace
import io.github.westanke.dshcompanion.selectAgentPreset
import io.github.westanke.dshcompanion.selectModel
import io.github.westanke.dshcompanion.selectWorkspacePickerRoot

/**
 * 三个**底部弹层**：模型与智能体（[ModelSheet]）、工作区管理（[WorkspaceManagementSheet]）、
 * 新建会话（[NewSessionSheet]）。
 *
 * 新建会话有两条路径：用**已有**工作区建会话，或先选授权根与子目录**新建**工作区再建会话。
 * 目录选择器（`workspacePicker*`）的状态在 ViewModel 里，这里只负责画与把用户的选择传回去。
 *
 * 这三个弹层都在列表层被唤起（新建入口在工作区按钮旁），与消息流无耦合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    val sessionModels = state.sessionModels
    val session = state.sessions.find { it.id == state.selectedSessionId }
    val currentPreset = session?.agentPreset
        ?: state.agentPresets.firstOrNull { it.isDefault && it.available }?.id
    val expansion = rememberModelGroupExpansion(sessionModels?.current?.provider)
    // 乐观高亮：点下去立刻亮，不等「写 RPC + 重拉 39.7KB sessionModels」这趟往返。
    // 服务端回填的 current 追上 pending 后清空，界面从"本地猜测"无缝交还给"服务端事实"。
    var pending by remember { mutableStateOf<ModelSelection?>(null) }
    // 写失败时服务端事实永远追不上 pending，转圈会一直挂在那儿——比改造前更糟。
    // 所以给本地猜测设一个上限寿命：到点还没被服务端确认就撤销，界面交还服务端事实。
    // 不用 state.busy 判断「这趟跑完了没」：那是全局共享标志，别的操作一结束就会误伤。
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        delay(6_000)
        if (pending == target) pending = null
    }
    LaunchedEffect(sessionModels?.current, pending) {
        val target = pending
        val confirmed = sessionModels?.current
        if (target != null && confirmed != null &&
            confirmed.provider == target.provider && confirmed.model == target.model &&
            confirmed.reasoningEffort == target.reasoningEffort
        ) {
            pending = null
        }
    }
    val shown = pending ?: sessionModels?.current
    val submit: (ModelSelection) -> Unit = { target ->
        // 同一个目标还在路上时再点等于重复发写请求，直接吞掉。
        if (pending != target) {
            pending = target
            viewModel.selectModel(target)
        }
    }
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
                        val expanded = expansion.isOpen(group.id)
                        val holdsCurrent = shown?.provider == group.id
                        item("header:${group.id}") {
                            ModelProviderHeader(
                                name = group.name,
                                modelCount = group.models.size,
                                expanded = expanded,
                                holdsCurrent = holdsCurrent,
                                onToggle = { expansion.toggle(group.id) },
                            )
                        }
                        if (expanded) {
                            items(group.models, key = { "${group.id}:${it.id}" }) { model ->
                                val target = ModelSelection(group.id, model.id, model.reasoning?.defaultEffort)
                                val selected = shown?.provider == group.id && shown.model == model.id
                                ModelRow(
                                    providerId = group.id,
                                    model = model,
                                    selected = selected,
                                    current = shown ?: sessionModels.current,
                                    pending = selected && pending == target,
                                    onSelect = submit,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentPresetRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceManagementSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
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
internal fun RenameDialog(
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
internal fun NewSessionSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    var workspaceId by remember(state.chatWorkspaces) { mutableStateOf(state.chatWorkspaces.firstOrNull()?.id.orEmpty()) }
    var createWorkspace by remember { mutableStateOf(false) }
    var presetId by remember(state.agentPresets) {
        mutableStateOf(state.agentPresets.firstOrNull { it.isDefault && it.available }?.id.orEmpty())
    }
    var workspaceMenu by remember { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    // 权限档位：创建会话时就能定（用户 2026-10-19 要求「创建会话时可以更改权限」）。
    // 默认「跟随服务端」= 不选（空串），创建完不额外发命令。
    var permissionPreset by remember(state.permissionCatalog) {
        mutableStateOf(state.permissionCatalog?.defaultPreset.orEmpty())
    }
    var permissionMenu by remember { mutableStateOf(false) }
    LaunchedEffect(createWorkspace) {
        if (createWorkspace && state.workspacePickerDirectory == null) viewModel.prepareWorkspacePicker()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 候选项来自 bridge 的目录接口；老 bridge 没有那条路由时是空列表 →
    // 这一段不渲染（不显示一个点了没反应的按钮）。
    val permissionOptions = state.permissionCatalog?.options.orEmpty()
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
            // 权限档位（创建会话时就能定，用户 2026-10-19 要求）：
            // 紧跟 Agent 模式 —— 两者都是「这次会话的开局设定」。
            if (permissionOptions.isNotEmpty()) {
                PickerHeading(stringResource(R.string.access_mode))
                PermissionPresetPicker(permissionPreset, permissionOptions) { permissionPreset = it }
            }

            Button(
                onClick = {
                    if (createWorkspace) {
                        viewModel.createWorkspaceAndSession(
                            requireNotNull(state.workspacePickerRootId),
                            state.workspacePickerPath,
                            presetId,
                            permissionPreset,
                        )
                    } else {
                        viewModel.createSession(workspaceId, presetId, permissionPreset)
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
