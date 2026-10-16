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
 * 消息流里的**非气泡**两行：终局失败（[TurnFailureRow]）与折叠的活动行（[ActivityRow]）。
 *
 * 失败必须有**自己的颜色和图标**，而且不能被折叠成一行摘要 —— 用户要的就是那句
 * 「为什么失败了」。以前它压根不到客户端：插件把 `turn/end` 的 reason 整段丢掉，
 * 客户端收到的与「正常跑完」一模一样，于是转圈等到天荒地老。
 *
 * 活动行是「其余一切」的默认分支：上下文注入、思考（兜底，正常不显示）、工具调用。
 * 工具行的展开内容在 `ChatToolDetail.kt`。
 *
 * 从 `ChatMessageItems.kt` 拆出，实现逐字未改。
 */
@Composable
internal fun TurnFailureRow(failure: ChatTurnFailure, onRetry: (() -> Unit)? = null) {

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

        localizedRes == R.string.err_request_generic -> stringResource(localizedRes, failure.code.orEmpty())

        else -> stringResource(localizedRes)

    }

    // 在组合期取字符串（而非回调里用 context.getString）：这样切换界面语言时
    // 长按 toast 才会跟随重组刷新，否则一直用旧 Configuration 下的语言。
    val copiedHint = stringResource(R.string.copied)

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

                    toast(context, copiedHint)

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

        // 重试：只有**可重试**的失败才给按钮（登出停止这类重试必然再失败一次，
        // 调用方传 null 就不显示）。按钮自己消费点击，不碰外层的长按复制。
        onRetry?.let { retry ->

            TextButton(

                onClick = retry,

                modifier = Modifier.padding(top = 2.dp),

                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),

            ) {

                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(15.dp))

                Text(stringResource(R.string.retry), modifier = Modifier.padding(start = 4.dp))

            }

        }

    }

}



@Composable
internal fun ActivityRow(item: ChatDisplayItem) {

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
