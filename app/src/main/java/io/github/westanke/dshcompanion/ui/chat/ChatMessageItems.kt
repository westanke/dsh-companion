package io.github.westanke.dshcompanion.ui.chat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
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
import io.github.westanke.dshcompanion.ui.GenUiCard

/**
 * 消息气泡本身：用户消息（正文 + 图片附件）、助手正文（Markdown + dsh-ui 卡片），
 * 以及那条可展开的 dsh-ui 诊断行。
 *
 * 入口是 [ChatItem]，按 `ChatItemKind` 分派 USER / ASSISTANT 两类；
 * 终局失败与折叠活动行在 `ChatActivityRows.kt`，工具详情在 `ChatToolDetail.kt`，
 * 顶栏权限段在 `ChatPermissionMenu.kt`。
 *
 * 两条**手势规则**写在 [ChatItem] 里，别改错：
 * - 外层 `combinedClickable` 只声明 onClick / onDoubleClick、**不声明 onLongClick**，
 *   所以长按会穿透到内层 `SelectionContainer` 弹出选择手柄；
 * - 给外层补 `onLongClick` 会把长按吃掉、手柄再也不出现 —— 早期版本正是这么翻车的
 *   （用户原话：「不能选择性复制，只能复制整段」）。
 *
 * 从 `ui/ChatScreen.kt`（3882 行）拆出，实现逐字未改。
 */
@Composable

internal fun ChatItem(

    item: ChatDisplayItem,

    images: Map<String, ByteArray> = emptyMap(),

    failures: Map<String, String> = emptyMap(),

    onLoadImage: (String) -> Unit = {},

    onAnswer: (String) -> Unit = {},

    genUiDebug: Boolean = false,

    /**

     * 「重试上一轮」；null = 不显示重试按钮（例如登出停止的失败，重试必然再失败一次）。

     */

    onRetry: (() -> Unit)? = null,

    /**

     * 「从这条消息分叉」；null = 不给入口。只有**用户消息**会真正显示（见 `data/ChatForkPoint.kt`）。

     */

    onForkAt: ((Int) -> Unit)? = null,

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

    // 组合期取文案，双击 toast 才能跟随界面语言（回调里 context.getString 不会随重组刷新）
    val copiedHint = stringResource(R.string.copied)

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

                            toast(context, copiedHint)

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

    // 2026-10-17 八修：这里必须有一层 **Column**。
    //
    // SelectionContainer 把直接子节点当**兄弟**排布（inline-like），所以在没有 Column 的
    // 情况下，正文与 [ChatMessageFooter] 会**并排成同一行** —— 真机截图里 09:03 时间戳
    // 跑到屏幕左边、与绿色气泡重叠，就是这么来的（第五版把 footer 从 fillMaxWidth()
    // 改成 wrapContentWidth() 之后更明显：宽度不再撑满，它直接顶到左边去了）。
    //
    // 包一层 Column 之后：正文在上、时间戳与分叉入口在下，各自占一行，互不挤压。
    // SelectionContainer 仍然覆盖全部子节点，长按选词不受影响。
    Column(Modifier.fillMaxWidth()) {

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

        // 登出停止（stopped）不给重试按钮：那一轮的失败原因是账号登出，重试只是白跑一趟。
        ChatItemKind.FAILURE -> item.failure?.let { failure ->
            TurnFailureRow(failure, onRetry = if (failure.stopped) null else onRetry)
        }

        // 输出 token 上限：截断不是失败，单独一条黄色警示（内核 turn-max-tokens 节点）。
        ChatItemKind.TRUNCATED -> TruncatedNoticeRow()

        else -> ActivityRow(item)

    }

    // 时间戳与「从这条分叉」都在这一行里（拆到 ChatMessageFooter.kt 是为了守住 800 行预算）。
    // 在上面那层 Column 里 —— 与正文**上下排列**而不是并排（八修的根因）。
    ChatMessageFooter(item = item, onForkAt = onForkAt)

    } // Column：让正文与时间戳上下排列（SelectionContainer 会把子节点并排）

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

internal fun AssistantBody(

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
                flavour = GFMFlavourDescriptor(),

                // 代码块的高亮 / 语言标签 / 复制键都在这份组件里（同目录 ChatCodeBlock.kt）：
                // 不传 components 时用的是渲染库自己的代码块，既没有高亮，复制也不给提示。

                components = chatMarkdownComponents,

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
                flavour = GFMFlavourDescriptor(),

                    components = chatMarkdownComponents,

                    modifier = Modifier.fillMaxWidth(),

                )



                is MessageSegment.Ui -> {

                    val spec = remember(segment.json) { parseGenUi(segment.json) }

                    if (spec == null) {

                        // 解析失败不吞内容：回落成代码块，用户至少还能看到原文。

                        Markdown(

                            content = "```json\n${segment.json}\n```",

                            typography = compactMarkdownTypography(),
                flavour = GFMFlavourDescriptor(),

                            components = chatMarkdownComponents,

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

internal fun GenUiDebugLine(diagnosis: GenUiDiagnosis) {

    var expanded by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    // 组合期取文案，长按 toast 才能跟随界面语言
    val copiedHint = stringResource(R.string.copied)

    Column(

        Modifier

            .fillMaxWidth()

            .padding(top = 4.dp)

            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = {

                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(ClipData.newPlainText("genui-debug", diagnosis.details))

                toast(context, copiedHint)

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

 * 成功那一档（解码、点开全屏、保存到相册）搬去了 `ChatImageViewer.kt` 的 [ChatImageThumbnail]：

 * 那里保留了同一套判定 —— 字节取到了但**不是有效图片**时单独给一句提示，因为那说明

 * 数据有问题、与网络无关，不该让用户去重试网络。

 */

@Composable

internal fun MessageAttachments(

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

                // 「取到字节了」这一档整块交给 ChatImageViewer.kt：解码、尺寸上限、点开全屏、
                // 保存到相册全是「一张图片」自己的事。留在这里的只有与本条附件有关的判定 ——
                // 加载中还是失败（谁都还没有字节，根因不在图片）。

                bytes != null -> ChatImageThumbnail(attachment.name, bytes)



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

internal fun AttachmentNotice(message: String) {

    Text(

        message,

        color = MaterialTheme.colorScheme.onSurfaceVariant,

        style = MaterialTheme.typography.labelSmall,

    )

}
