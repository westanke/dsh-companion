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
import io.github.westanke.dshcompanion.ui.BinaryFilePreview
import io.github.westanke.dshcompanion.ui.EmptyState
import io.github.westanke.dshcompanion.closeSessionFile
import io.github.westanke.dshcompanion.openSessionFile

/**
 * 「本会话的文件」面板：标题栏文件夹按钮点开的那一块。
 *
 * 刻意做成**独立面板**，不去动消息气泡的渲染：列表由 [SessionFileRefs] 从已加载的会话
 * 历史里提取，点一条交给 `HarnessViewModel.openSessionFile` 把内容取回来。
 *
 * 预览按类型分派：Markdown 交给 Markdown 组件、HTML 走结构化抽取（**不**用 WebView ——
 * 那等于让一份不可信文件拿到执行权）、其余当等宽源码。两处（底部页脚与标题栏弹层）
 * 共用 [sessionFileList] 这一个来源，不可能再走偏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionFilesSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
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

internal fun sessionFileList(history: ChatHistory?): List<SessionFileRef> {
    val delivered = history?.deliverables().orEmpty()
    return if (delivered.isNotEmpty()) {
        delivered.map { SessionFileRef(it.path, count = 1, lastSeq = it.seq, description = it.description) }
    } else {
        SessionFileRefs.refs(history?.events.orEmpty())
    }
}

@Composable
internal fun SessionFileList(state: HarnessState, onOpen: (String) -> Unit) {
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

@Composable
internal fun SessionFileRow(ref: SessionFileRef, onClick: () -> Unit) {
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
            // 文件名放不下时横向滚动，而不是截断 —— 与「文件」页同一个理由：
            // 这里的名字常常又长又前缀雷同，截出来分不清是哪个。
            // 完整路径在下面那行（可换行两行），保留它作为不动的那份参照。
            Text(
                ref.path.substringAfterLast('/').ifBlank { ref.path },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
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
internal fun SessionFileDetail(open: SessionFileOpen, onBack: () -> Unit) {
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
internal fun HtmlStructurePreview(source: String) {
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
internal fun SessionFileMeta(resolved: ResolvedPath) {    val size = resolved.size?.let(::formatFileSize)
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
internal fun SessionFileNotice(icon: ImageVector, text: String) {
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
