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
import io.github.westanke.dshcompanion.ChunkUploadProgress
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
 * 输入区的两类附属 UI：**待发附件条**（[AttachmentChip]）与**当前任务面板**（[TodoPanel]）。
 *
 * 附件缩略图按需**降采样**解码（只解到约 96px），而不是把 8 MiB 的原图整张读进 Bitmap —
 * 那样几张图就能把输入区卡住。做法是先用 `inJustDecodeBounds` 只读尺寸，再按 2 的幂降采样。
 *
 * 文件名与体积从 ContentResolver 取（`OpenableColumns`），取不到就退回 URI 末段与 -1。
 * 这三个助手与「文件」页同款，只是那边走服务器路径、这边走本地 uri。
 *
 * 从 `ui/chat/ChatComposer.kt` 拆出（原 `ui/ChatScreen.kt` 3882 行的一部分）。
 * 实现逐字未改，只把 `private` 提升为 `internal`：输入区在 `ChatComposer.kt`，要调用它们。
 */

/**
 * 待发附件条上的一条：图片给缩略图，其他给图标；名称、体积与删除键都在这里。
 *
 * 缩略图按需**降采样**解码（只解到约 96px），而不是把 8 MiB 的原图整张读进 Bitmap —
 * 那样几张图就能把输入区卡住。
 */
/**
 * 上传进度条：分块上传时显示**真实块数**，不是只转圈。
 *
 * 「已传 3/12 块」比一个无限转的圈有用得多 —— 前者能让用户判断「再等十秒」还是「切出去干别的」，
 * 后者只告诉他「App 还在忙」。续传（resumed）要单独一句：同名同大小重传时服务端会接着上次的块，
 * 用户看到「继续上传 5/12」就知道不用从头等。
 */
@Composable
internal fun UploadProgressChip(
    progress: ChunkUploadProgress?,
    uploading: Boolean,
    onRetry: () -> Unit,
) {
    if (!uploading && progress == null) return
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress?.failed == true) {
            Text(
                progress.error ?: stringResource(R.string.attachment_upload_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        } else {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            Text(
                when {
                    progress == null -> stringResource(R.string.attachment_uploading)
                    progress.resumed ->
                        stringResource(R.string.attachment_upload_resumed, progress.sent, progress.total)
                    else -> stringResource(R.string.attachment_upload_chunk_progress, progress.sent, progress.total)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
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
            // 删除键：**布局 48dp、视觉 30dp**（O-10）。
            //
            // 改造前写的是 `IconButton(modifier = Modifier.size(30.dp))` ——
            // `IconButton` 自己会施加 `minimumInteractiveComponentSize`（48dp 触摸扩展），
            // 但按官方 API 文档的警告，**显式 size 会先限制布局约束**，那个扩展就不能
            // 保证生效；附件缩略图密集区容易点不中（MASTER.md:38 要求 ≥ 48dp）。
            //
            // 正确用法是把视觉尺寸挪到 Icon 上，IconButton 用默认 48dp 布局：
            // 触摸区达标，图标还是 15dp，**看起来跟以前一样**。
            IconButton(onClick = onRemove) {
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
internal fun decodeThumbnail(bytes: ByteArray, maxPx: Int = 96): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() }.getOrNull()
}

/** 与「文件」页同款：从 ContentResolver 取显示名，取不到就退回 URI 的末段。 */
internal fun attachmentName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
}

internal fun attachmentSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
    }
    return -1L
}

internal fun Context.contentType(uri: Uri): String? = contentResolver.getType(uri)

@Composable
internal fun TodoPanel(todos: List<TodoItem>, expanded: Boolean, onToggle: () -> Unit) {
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
