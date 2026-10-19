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

/**
 * 顶栏 `⋯` 菜单里的**权限段**：三档选项与完整访问的二次确认。
 *
 * 输入区压成一行药丸后，模型 / 权限 / 插话这些入口整体搬到了顶栏 —— 它们只是
 * **换了容器，不是换了语义**：权限仍是只读 / 工作区写入 / 完整访问三档，
 * 完整访问仍需在 [FullAccessConfirmDialog] 里勾选 `acknowledged` 才能确认。
 * 切换走命令 `/permission <preset>`，本文件不发任何请求。
 *
 * 从 `ui/ChatScreen.kt`（3882 行）拆出，实现逐字未改。
 */
@Composable

internal fun PermissionMenuItems(

    value: PermissionSelect,

    enabled: Boolean,

    onSelect: (String) -> Unit,

    onRequestFullAccess: () -> Unit,

) {

    // 候选项为空时**仍然**渲染「访问权限 · 当前档位」那一行 ——
    // 那是用户在菜单里唯一能看到「我现在是什么权限档位」的地方。
    // 只有这一行、下面没有可选项时，菜单是只读的（点了什么都不会发生），
    // 但「看不见」比「看得见却切不了」更让人怀疑 App 坏了。
    // （v2.2.44 真机验收里，这一行连同整个权限入口一起消失了。）
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

                    // 描述走**本地化**，不用服务端原文（否则中文界面下中英混显，用户 2026-10-19 要求全中文）。
                    permissionDescription(option.value, option.description)?.takeIf(String::isNotBlank)?.let { description ->

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

internal fun FullAccessConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {

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

internal fun permissionLabel(value: String?, fallback: String?): String = when (value) {

    "read-only" -> stringResource(R.string.permission_read_only)

    "workspace-write" -> stringResource(R.string.permission_workspace_write)

    "danger-full-access" -> stringResource(R.string.permission_full_access)

    "custom" -> stringResource(R.string.permission_custom)

    else -> fallback ?: value.orEmpty()

}



/**
 * 档位**描述**的本地化。
 *
 * 为什么需要它：描述原先直接来自服务端（内核 `Config.presets[].description` 是英文），
 * 于是中文界面下会出现「完整访问 · Full file access without approval prompts.」这种
 * **中英混显**（用户 2026-10-19 明确要求：中文就全中文，英文就全英文）。
 *
 * 已知档位一律用 App 自己的资源（标题与描述同一套语言）；
 * **未知档位**才退回服务端原文 —— 那是我们自己没见过的档位，翻不了就不假装翻。
 */
@Composable
internal fun permissionDescription(value: String?, fallback: String?): String? = when (value) {

    "read-only" -> stringResource(R.string.permission_read_only_desc)

    "workspace-write" -> stringResource(R.string.permission_workspace_write_desc)

    "danger-full-access" -> stringResource(R.string.permission_full_access_desc)

    "custom" -> stringResource(R.string.permission_custom_desc)

    else -> fallback

}

internal fun permissionIcon(value: String?): ImageVector = when (value) {

    "workspace-write" -> Icons.Outlined.Edit

    "danger-full-access" -> Icons.Outlined.WarningAmber

    else -> Icons.Outlined.Lock

}
