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
import androidx.compose.ui.text.style.TextAlign
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

/**
 * 输入区：一整条**药丸**（网页版 `#row` 的位置与形态）。
 *
 * 三样东西在药丸里：`+`（附件两项）· 输入框 · 发送。模型 / 权限 / 插话这些入口搬到了
 * 详情页顶栏的 `⋯` 菜单（见 `ChatDetail.kt`）。
 *
 * 两条**看不见的规则**值得单独说：
 * - 占位符承载「运行中发送即排队/插话」与「Ctrl+Enter 发送」（外接键盘时），
 *   选哪一段由 [composerHint] 决定，有单测（`ComposerPillTest`）；
 * - 接了物理键盘时**不弹软键盘**，且必须在输入框**获得焦点那一刻** hide ——
 *   组合时执行是空操作，IME 接管后物理键盘的 Ctrl+Enter 会被整个吞掉。
 *
 * 底部挂着思考条（[ChatThinkBar]）：它是「运行状态」而不是「会话内容」，
 * 位置固定在消息区与输入区之间，**不进** LazyColumn（否则随消息滚动、把正文挤开）。
 */
@Composable
internal fun Composer(
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
    physicalKeyboard: Boolean = false,
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
    // 占位符必须一行放得下（下面 maxLines = 1 + Ellipsis），所以只留「运行中 + 发送去哪」；
    // 外接物理键盘时再追加「Ctrl+Enter 发送」（见 composerHint 的 physicalKeyboard 分支）。
    val hint = composerHint(sessionRunning, defaultIsSteer, physicalKeyboard)
    val placeholder = when (hint) {
        ComposerHint.IDLE -> stringResource(R.string.message_hint)
        // 两段拼接而不是新增资源：空闲短文案 + 快捷键提示。用 stringResource 取两段再拼，
        // 语言切换时两段都跟着切（写死一串中英混排不行）。
        ComposerHint.IDLE_PHYSICAL -> stringResource(R.string.message_hint) +
            " · " + stringResource(R.string.message_hint_ctrl_enter)
        ComposerHint.RUNNING_QUEUE -> stringResource(R.string.message_hint_running_queue)
        ComposerHint.RUNNING_STEER -> stringResource(R.string.message_hint_running_steer)
    }
    // 接了物理键盘时**不弹软键盘**：平板 + 外接键盘是常态，点一下输入框弹出占掉半屏的
    // 软键盘纯属浪费（而且 Android 不会因为接了外接键盘就自动不弹）。
    //
    // 关键在**时机**：必须在输入框**获得焦点的那一刻** hide，而不是 Composer 组合时。
    // 组合时执行的那一版（本版之前）是空操作 —— 那时输入框还没焦点，hide 什么都不会发生；
    // 等用户点输入框，软键盘照弹，物理键盘的 Enter / Ctrl+Enter 全被 IME 吞掉
    // （onKeyEvent 一个事件都收不到，表现为「回车就是换行」——换行正是 IME 的行为）。
    //
    // 做法：BasicTextField 挂 onFocusChanged，焦点为真即 hide。不主动 requestFocus
    // （没有需要「一进页面就抢焦点」的场景），所以不需要 FocusRequester。
    // 不依赖实验 API，也不改 KeyboardOptions（那会连 IME action 一起改掉）。
    // 手机端 physicalKeyboard 恒为 false，整段不执行，行为零变化。
    val keyboardController = LocalSoftwareKeyboardController.current
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(composerFocused, physicalKeyboard) {
        if (composerFocused && physicalKeyboard) keyboardController?.hide()
    }
    Column(Modifier.fillMaxWidth()) {
        // 两个补全面板**互斥**：`/` 只在整行以斜杠开头时才出（官方命令是行首语法），
        // `@` 只在光标落在一个提及词里时才出。同一时刻两者不可能同时成立 ——
        // 但万一将来判据放宽，这里先立规矩：斜杠优先，避免两个列表叠在一起。
        val mentioning = MentionQuery.activeStart(value) != null
        if (mentioning) {
            // 会话目录的两种形态都要：相对根的 `cwd` 用来在条目路径里做前缀替换，
            // 绝对形态 `cwdAbsolute` 用来拼出真正能用的绝对路径。
            val mentionSession = state.sessions.firstOrNull { it.id == state.selectedSessionId }
            MentionSuggestions(
                value = value,
                entries = state.mentionEntries,
                failed = state.mentionFailed,
                cwd = mentionSession?.cwd,
                cwdAbsolute = mentionSession?.cwdAbsolute,
                // 选中后**只替换「@…末尾」那一段**，不是整行替换：
                // 用户很可能已经写了半句「帮我看看 @xxx」，整行替换会把前半句吃掉。
                onSelect = { inserted -> onValueChange(MentionQuery.applySelection(value, inserted)) },
            )
        } else {
            CommandSuggestions(value, state.commands, onValueChange)
        }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                // 焦点变化驱动上面的软键盘 hide：接了物理键盘时，
                                // 用户每点一次输入框都要收一次软键盘（见那段注释）。
                                .onFocusChanged { composerFocused = it.isFocused }
                                .onKeyEvent { event ->
                                // 物理键盘（外接键盘、桌面模式）的 Enter。
                                // 软键盘的「发送」键走下面的 KeyboardActions.onSend。
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onKeyEvent false
                                val alternate = event.isCtrlPressed || event.isMetaPressed
                                // 软键盘派发的事件一律放行换行：触摸屏上按不出「真的 Ctrl」，
                                // IME 偶尔补发的带修饰键事件也是同一个来源，若不拦就会被当成
                                // Ctrl+Enter 误发。只有物理键盘的 Ctrl+Enter 才走到发送。
                                if (!alternate) return@onKeyEvent false
                                if (isSoftKeyboardEvent(event.nativeKeyEvent.flags)) {
                                    return@onKeyEvent false
                                }
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

internal enum class ComposerHint { IDLE, IDLE_PHYSICAL, RUNNING_QUEUE, RUNNING_STEER }

/**
 * 输入框占位符该显示哪一段。
 *
 * [physicalKeyboard] 为真时（外接键盘 / 桌面模式）空闲文案追加「Ctrl+Enter 发送」——
 * 软键盘的换行键与物理 Enter 在 `onKeyEvent` 里派发同一个 `Key.Enter`，事件本身分不出来源，
 * 所以 Enter 一律换行、发送只走 Ctrl+Enter。这条规则不写出来，硬键盘用户根本不知道
 * 该怎么发送（平板 + 外接键盘是常态场景）。
 *
 * 为什么只加在 IDLE 态：运行中的两段文案（RUNNING_QUEUE / RUNNING_STEER）本身已在说
 * 「发送即插话/排队」，再拼一段会超出一行放不下（placeholder 是 maxLines = 1 + Ellipsis）。
 */

internal fun composerHint(
    sessionRunning: Boolean,
    steerIsDefault: Boolean,
    physicalKeyboard: Boolean = false,
): ComposerHint = when {
    // 空闲时没有可插话的轮次，设置里的「繁忙时默认行为」不该影响占位符。
    !sessionRunning -> if (physicalKeyboard) ComposerHint.IDLE_PHYSICAL else ComposerHint.IDLE
    steerIsDefault -> ComposerHint.RUNNING_STEER
    else -> ComposerHint.RUNNING_QUEUE
}

/**
 * 当前是否接了物理键盘（外接蓝牙/USB 键盘、平板桌面模式）。
 *
 * 判据是 `Configuration.keyboard == KEYBOARD_QWERTY`：它表示「设备具备物理 QWERTY 键盘」，
 * 折叠屏展开、平板桌面模式、外接键盘三种情况都归它。`KEYBOARD_NOKEYS` 是纯触摸设备（手机），
 * `KEYBOARD_UNDEFINED` 交给 false —— 不确定时按软键盘处理，提示短一点不会错。
 */

internal fun hasPhysicalKeyboard(configuration: android.content.res.Configuration): Boolean =
    configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY

/**
 * 这个按键事件是不是**软键盘**（IME）派发的。
 *
 * 判据是 Android `KeyEvent.FLAG_SOFT_KEYBOARD`（值为 2）：IME 通过
 * `InputConnection.sendKeyEvent()` 送进来的事件都带这个 flag，外接键盘的不带。
 *
 * 为什么需要它：软键盘的「换行」键与外接键盘的 Enter 在 Compose `onKeyEvent` 里
 * 派发**同一个** `Key.Enter`，`isCtrlPressed` 也可能被 IME 补成 true
 * （某些输入法的组合键实现）。不区分来源就会把「触摸屏上按了个换行」
 * 当成「Ctrl+Enter」误发出去。拦这道 flag 之后：
 * 触摸屏的 Enter 一律换行；只有物理键盘的 Ctrl+Enter 才发送。
 */

internal fun isSoftKeyboardEvent(nativeFlags: Int): Boolean =
    nativeFlags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0

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

/**
 * 状态条：**思考条与「内容由 AI 生成」标识合并成同一条**（用户要求，2026-10-17）。
 *
 * 原来两者是并排的两行（思考条 + 标识条），思考一来就把标识挤到上面去，那一栏就散了。
 * 现在合成一条：**有思考内容时显示思考**（可展开），**没有思考时才显示标识**。
 *
 * 为什么这样合并而不是「标识常驻、思考叠上去」：
 * 那一栏只有一行多的空间，两条各占一行必然互相挤压；而思考只在流式期间存在，
 * 标识却是常驻的合规提示 —— 让「短生命周期的运行状态」让位给「常驻标识」不划算，
 * 反过来才对：思考在时用户看思考（标识在上一屏的输入框旁仍能看到），不在时标识补位。
 *
 * 位置仍然固定在消息区**下方**、输入区**上方**（网页版 `#thinkbar` 的位置）：
 * 它是「运行状态 / 标识」而不是「会话内容」，进 LazyColumn 会随消息滚走。
 */
@Composable
internal fun ChatStatusBar(liveThinking: String) {
    val thinking = thinkBarText(liveThinking)
    if (thinking.isNotEmpty()) {
        ChatThinkBar(liveThinking)
    } else {
        AiGeneratedNotice()
    }
}

/**
 * 生成合成内容标识条（《人工智能生成合成内容标识办法》第四条：应当在生成合成内容或者
 * 交互场景界面中添加、可以被用户明显感知到的**显式标识**）。
 *
 * 为什么放在这里（输入区正上方、消息区正下方）：
 * - 与 [ChatThinkBar] 同为「非消息内容」的常驻条，位置固定、**不进** LazyColumn ——
 *   进了列表就会随消息滚走，用户看几屏之后就再也见不到，标识也就失去意义；
 * - 输入框是用户产生 AI 内容的地方，标识贴在它正上方，看到与生成之间没有心智隔断；
 * - 尺寸走 `labelSmall` + `onSurfaceVariant`，刻意弱于正文 —— 这是常驻合规提示，
 *   不是正文的一部分，抢注意力会干扰长会话阅读。
 *
 * 现在由 [ChatStatusBar] 按「有无思考」在它和思考条之间切换（2026-10-17 合并）。
 *
 * 为什么只有一行、不做成弹窗或一次性浮层：
 * 第四条的措辞是「界面中添加…提示标识」，常驻优于一次性提示；弹窗类方案会打断会话，
 * 且「已关闭不再提示」之后用户就再也看不到标识，反而更不合规。
 *
 * 免责范围说明：本 App 是**纯客户端**，生成方是用户自己部署的 DSH 服务端、模型由用户
 * 自行配置，内容不出用户自己的地址。这条标识是按「从严」口径加的，不改变本 App
 * 在资质上的定性（那由服务端承担，见 CHANGELOG-DEV v2.2.41 备注）。
 */
@Composable
internal fun AiGeneratedNotice() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.ai_generated_notice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ChatThinkBar(raw: String) {
    val text = thinkBarText(raw)
    if (text.isEmpty()) return
    // 默认只露**尾部一行**；点一下展开全文。
    //
    // 为什么值得加展开：只显示尾部 140 字符时，用户想知道「它到底在想什么」
    // 是做不到的（前面全被截掉）。对比对象 Whaleness 的思考块是可折叠的固定
    // "Think" 行 —— 我们这条更需要展开，因为我们的摘要本身就是正文的尾巴。
    //
    // 折叠态用 rememberSaveable：这条在流式期间随每个 token 重组，
    // 用 remember 会在这类高频重组里丢掉展开态（用户刚点开就又合上）。
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
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
                Icon(
                    Icons.Outlined.ExpandMore,
                    // contentDescription 留空：整行本身可点，且旁边就是思考正文，
                    // 再报一次「展开」只会让 TalkBack 多说一遍废话。
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    raw.trim(),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 高度上限 + 内部滚动：思考可能很长，任由它撑开会把
                        // 整个输入区顶出屏幕（这条面板贴在输入区上方）。
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
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
