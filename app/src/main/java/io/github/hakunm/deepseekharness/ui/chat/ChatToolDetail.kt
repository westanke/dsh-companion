package io.github.hakunm.deepseekharness.ui.chat
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
import io.github.hakunm.deepseekharness.data.FileEntry
import io.github.hakunm.deepseekharness.data.MentionQuery
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
 * 一条工具调用的**详情视图**：[ToolCallDetailBlock] 与它内部的 [ToolTextBlock]。
 *
 * 输入是内核原样的 `arguments` 字符串，缩进后显示；输出是 `tool/result` 的 content 里
 * text 段拼起来的结果。两者都能一键复制 —— 手机上开不了终端，把这段输出复制出来发给自己，
 * 是唯一能把现场带走的办法。
 *
 * 两个容易忽略的点：
 * - **长文本必须能滚**：实测单条结果最长 50000 字符（p50 277、p90 2809），
 *   不设高度上限会把整屏撑爆，连「往上翻」都做不到；
 * - **两个轴都要能滚**：终端输出经常是「很宽的一行 + 很多行」，只给纵向滚动的话宽行会被
 *   硬折行，缩进层次就全乱了。
 *
 * [compactMarkdownTypography] 放在这里：它是详情块、助手正文与活动行三处共用的紧凑排版。
 *
 * 从 `ui/ChatScreen.kt`（3882 行）拆出，实现逐字未改。
 */
@Composable

internal fun ToolCallDetailBlock(tool: ToolCallDetail) {

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

internal fun ToolTextBlock(label: String, text: String, isError: Boolean, maxHeightDp: Int) {

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
internal fun compactMarkdownTypography() = markdownTypography(
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
