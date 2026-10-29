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
import io.github.westanke.dshcompanion.data.CommandArgOption
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
 * 输入框上方的两个**补全面板**：`@` 工作目录文件、`/` 宿主命令。
 *
 * 两个面板**互斥**：`/` 只在整行以斜杠开头时才出（官方命令是行首语法），
 * `@` 只在光标落在一个提及词里时才出。同一时刻两者不可能同时成立 ——
 * 但万一将来判据放宽，先立规矩：斜杠优先，避免两个列表叠在一起。
 *
 * **必须可滚动**：此前 `@` 面板是 `Column` + `forEach`，列表本身不动 ——
 * 用户在面板上往上划想翻更多条目时，手势没被任何东西消费，顺着手势通道一路传到外层
 * `ModalNavigationDrawer`，把「会话/文件/插件/模型/设置」抽屉拉了出来。
 * 列表能滚，这个问题才从根上消失（外层的 `composerOverlay` 只是第二道保险）。
 *
 * [commandSuggestions] 抽成独立函数是为了让**外层抽屉**也能问到同一个判据：
 * 面板可见时必须关掉抽屉的横向滑动手势，两处共用一条规则，判据不会漂移。
 */
@Composable
internal fun MentionSuggestions(
    value: String,
    entries: List<FileEntry>?,
    failed: Boolean,
    cwd: String?,
    cwdAbsolute: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val query = MentionQuery.query(value) ?: return
    val matches = entries.orEmpty()
        .filter { it.name.contains(query, ignoreCase = true) }
        // 目录排前面：用户 `@` 一个文件夹继续往里选的场景很常见，
        // 混在一起时文件夹总被文件名淹掉。
        .sortedWith(compareByDescending<FileEntry> { it.kind == "directory" }.thenBy { it.name.lowercase() })
        // 列表已经可滚动（见下方 LazyColumn），就不该再硬截 8 条 —— 一个几十文件的
        // 目录里，8 条几乎等于让人找不到。仍留一个上限：filter/sort 是跑在完整
        // entries 上的，超大目录下没有上限会让每次击键都重算几万条。
        .take(50)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(vertical = 5.dp)) {
            Text(
                stringResource(R.string.workspace_files),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (entries == null) {
                // 三态，别把「读不到」谎报成「空的」：
                //   null + 未失败 → 还在读；null + 失败 → 读不到。
                // 真正的空目录走下面 `matches.isEmpty()` 那条分支（那才是空列表）。
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (failed) R.string.workspace_files_unavailable
                            else R.string.workspace_files_loading,
                        ),
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 「读不到」必须留一条自救路径：只显示一句话的话，用户只能干等
                    // —— 而面板不会再自己加载一次（请求已经失败了）。
                    if (failed) {
                        TextButton(onClick = onRetry, modifier = Modifier.padding(start = 4.dp)) {
                            Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            } else if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.workspace_files_empty),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // **必须可滚动**：此前这里是 `Column` + `forEach`，列表本身不动 ——
                // 用户在面板上往上划想翻更多条目时，手势没被任何东西消费，顺着手势通道
                // 一路传到外层 `ModalNavigationDrawer`，把「会话/文件/插件/模型/设置」
                // 抽屉拉了出来。列表能滚，这个问题才从根上消失（外层的
                // `composerOverlay` 只是第二道保险）。
                //
                // 高度上限：面板贴在输入框上方，铺满整屏会把对话顶没；264dp 大约
                // 5 条半，能滚说明还有更多。
                LazyColumn(Modifier.heightIn(max = 264.dp)) {
                    items(matches, key = { it.path }) { entry ->
                        val isDir = entry.kind == "directory"
                        val absolute = MentionQuery.absolute(cwdAbsolute, cwd.orEmpty(), entry.path)
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                // 目录结尾补 `/` 且**不加空格**：用户大概率要继续往里选，
                                // 直接接着打下一级正好；文件则补空格，把这一条结束掉。
                                .clickable { onSelect("@$absolute${if (isDir) "/" else " "}") }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                null,
                                Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Column(Modifier.weight(1f)) {
                                // 与「文件」页同一个理由：`@` 出来的名字常常又长又前缀雷同，
                                // 截断后分不清是哪个。放不下就横向滚动。
                                // 下面那行相对路径仍原样保留，作为不动的那份参照。
                                Text(
                                    entry.name + if (isDir) "/" else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                )
                                // 第二行给**相对工作目录的路径**：绝对路径往往太长被截断，
                                // 而用户判断「是不是我要的那个」靠的通常就是中间那几级目录。
                                Text(
                                    entry.path,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!isDir && entry.size > 0) {
                                Text(
                                    ChatContentParts.formatBytes(entry.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `/` 命令面板此刻会显示哪几条（空列表 = 不显示）。
 *
 * 抽成独立函数是为了让**外层抽屉**也能问到同一个判据：面板可见时必须关掉抽屉的
 * 横向滑动手势（见 [HarnessUiState.composerOverlay]），否则用户在面板上横划一下就
 * 把「会话/文件/插件/模型/设置」拉出来了。两处共用一条规则，判据不会漂移。
 */

internal fun commandSuggestions(
    value: String,
    commands: List<CommandDescriptor>,
): List<CommandDescriptor> {
    val query = value.removePrefix("/")
    return if (value.startsWith("/") && query.none { it.isWhitespace() }) {
        commands.filter { it.name.startsWith(query, ignoreCase = true) }.take(5)
    } else {
        emptyList()
    }
}

@Composable
internal fun CommandSuggestions(
    value: String,
    commands: List<CommandDescriptor>,
    onSelect: (String) -> Unit,
) {
    val suggestions = commandSuggestions(value, commands)
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
internal fun localizedCommandDescription(command: CommandDescriptor): String? = when (command.name) {
    "compact" -> stringResource(R.string.command_compact_description)
    "export" -> stringResource(R.string.command_export_description)
    "feedback" -> stringResource(R.string.command_feedback_description)
    "goal" -> stringResource(R.string.command_goal_description)
    "permission" -> stringResource(R.string.command_permission_description)
    "plan" -> stringResource(R.string.command_plan_description)
    else -> null
}

/**
 * `/model ` 与 `/permission ` 的二级菜单（对齐桥 `command-options` / `command-select`）。
 *
 * 与上面的 [CommandSuggestions] 是**两个不同层级**：那个选命令本身，这个选命令的参数。
 * 同时只有一层的判据成立（参数位要求命令名后面已经跟了空白，而命令补全要求整行没有空白），
 * 所以不会叠着出现 —— 但调用处仍按顺序渲染，先参数位再命令补全，避免将来判据放宽后打架。
 *
 * 选中即执行（不是把文本填进输入框）：桥的 `command-select` 语义就是「点了就生效」，
 * 填文本再让用户按发送会多一步，而且 `/model` 在桥侧本来就是一次 RPC、不是斜杠命令。
 */
@Composable
internal fun CommandArgOptionsPanel(
    title: String,
    options: List<CommandArgOption>,
    onPick: (CommandArgOption) -> Unit,
) {
    AnimatedVisibility(visible = options.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                // 选项可能几十条（模型目录），限高 + 内部滚动：面板不该把消息区挤没。
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(enabled = !option.selected) { onPick(option) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (option.selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    null,
                                    Modifier.size(19.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                    Modifier.size(19.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.labelLarge)
                                option.detail?.takeIf(String::isNotBlank)?.let { detail ->
                                    Text(
                                        detail,
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
    }
}
