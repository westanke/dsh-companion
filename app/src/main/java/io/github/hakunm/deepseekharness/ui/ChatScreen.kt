package io.github.hakunm.deepseekharness.ui

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
import io.github.hakunm.deepseekharness.ui.chat.Composer
import io.github.hakunm.deepseekharness.ui.chat.ChatThinkBar
import io.github.hakunm.deepseekharness.ui.chat.ChatDetail
import io.github.hakunm.deepseekharness.ui.chat.ChatItem
import io.github.hakunm.deepseekharness.ui.chat.SessionFilesSheet
import io.github.hakunm.deepseekharness.ui.chat.SessionPane
import io.github.hakunm.deepseekharness.ui.chat.SessionOverviewPane
import io.github.hakunm.deepseekharness.ui.chat.NewSessionSheet
import io.github.hakunm.deepseekharness.ui.chat.WorkspaceManagementSheet
import io.github.hakunm.deepseekharness.selectSession

internal fun sessionDisplayTitle(session: ChatSession): String =
    session.title ?: session.workspaceTitle ?: session.cwd.ifBlank { session.id.take(12) }

/**
 * 平板（wide）分支里会话列表栏的宽度。
 *
 * 只被 wide 分支调用（手机/窄屏永远走固定布局，不经过这里）；
 * 比例 0.25 给长标题留足横向空间，下限 320.dp 保证不会比旧的 304.dp 更窄，
 * 上限 440.dp 避免把内容区挤没。纯函数，可单测钉住边界。
 */

internal fun sessionPaneWidth(maxWidth: Dp): Dp =
    (maxWidth * 0.25f).coerceIn(320.dp, 440.dp)

@Composable
fun ChatScreen(state: HarnessState, viewModel: HarnessViewModel, wide: Boolean) {
    var createSheet by remember { mutableStateOf(false) }
    var workspaceSheet by remember { mutableStateOf(false) }
    val selected = state.selectedSessionId

    // 会话**列表**两条路共用（它是原生资产，换掉没有收益）；只有详情页有两个实现。
    // WebView 版尚未过真机验证，所以由 AppPreferences.useWebChat 决定，默认走原生。
    //
    // 第二个参数 wide 只影响**原生**详情页（ConversationHeader 的标题行数等）；
    // WebView 版自己渲染，不消费它。
    val detail: @Composable (() -> Unit, Boolean) -> Unit = { onBack, wideFlag ->
        if (state.useWebChat) {
            ChatWebScreen(state, viewModel, onBack, Modifier.fillMaxSize())
        } else {
            ChatDetail(state, viewModel, onBack, Modifier.fillMaxSize(), wideFlag)
        }
    }

    if (wide) {
        // 会话列表栏宽**随屏宽伸缩**（见 sessionPaneWidth）：旧的 304.dp 是手机抽屉宽度，
        // 直接照搬到平板，长标题在 840–1440dp 的常见平板宽度里几乎必然被腰斩。
        // BoxWithConstraints 只包 wide 分支 —— 手机路径保持原样，像素级零改动。
        //
        // paneWidth 在 BoxWithConstraints 的 lambda 开头取出（不进 Row 的 content），
        // 否则嵌套 receiver（BoxWithConstraintsScope → RowScope）会让 maxWidth 解析歧义。
        //
        // 曾试过给会话列表加收起态（v2.2.39，48dp 竖条开关、收起时把 320–440dp
        // 全让给详情），用户实测后要求回到本形态：列表常驻、不收起。
        // 一级导航（App.kt 的 navExpanded）仍然默认收起 —— 那是另一处，不受此影响。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = sessionPaneWidth(maxWidth)
            Row(Modifier.fillMaxSize()) {
                SessionPane(
                    state,
                    viewModel,
                    { createSheet = true },
                    { workspaceSheet = true },
                    Modifier.width(paneWidth).fillMaxHeight(),
                    wide = true,
                )
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                if (selected == null) {
                    // 未选会话时右侧不再是一整块空白：放一个概览（主机状态 + 最近会话 + 新建入口）。
                    SessionOverviewPane(
                        state,
                        viewModel,
                        { createSheet = true },
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    detail({}, true)
                }
            }
        }
    } else if (selected == null) {
        SessionPane(state, viewModel, { createSheet = true }, { workspaceSheet = true }, Modifier.fillMaxSize())
    } else {
        detail({ viewModel.selectSession(null) }, false)
    }
    if (createSheet) NewSessionSheet(state, viewModel) { createSheet = false }
    if (workspaceSheet) WorkspaceManagementSheet(state, viewModel) { workspaceSheet = false }
}

/**
 * 会话展示标题（[SessionRow] 与 [SessionOverviewPane] 共用同一口径）。
 *
 * 优先级：用户起的 `title` → `workspaceTitle` → 相对工作目录 `cwd`（空串时兜底 id 前 12 位）。
 * 抽成纯函数是为了让「显示什么」这一条规则只写一次 —— 两处列表各抄一遍迟早漂移。
 */


/**
 * 会话详情页的**唯一入口**；列表层与详情层在这里分流。
 *
 * ## 为什么拆出 `ui/chat/` 下一层
 *
 * 原文件 3882 行，是 `docs/project/TECH-DEBT.md` 里登记的**第一号债**
 * （「会话详情页单文件承载：消息流、输入区、思考条、工具行、文件弹层、`@` 面板、手势分发。
 *   改一处要读 3 600 行」）。按 `webview-port-todo.md` 的 PORT-* 分组逐组拆成
 *   `ui/chat/` 子模块，**一行实现都没改**，只把组件搬到按职责划分的文件里：
 *
 * | 文件 | 职责 |
 * |---|---|
 * | `ChatScreen.kt`（本文件） | 入口与宽窄屏分流 + 两个列表层纯函数 |
 * | `chat/ChatDetail.kt` | 详情页骨架（顶栏 + 消息流 + 挂载点） |
 * | `chat/ChatSessionPane.kt` | 会话列表 / 概览 / 单行 |
 * | `chat/ChatMessageItems.kt` | 消息项 + 工具调用详情 + 顶栏 `⋯` 权限段 |
 * | `chat/ChatComposer.kt` | 输入区 + 思考条 + `@` / `/` 补全面板 |
 * | `chat/ChatApprovals.kt` | 审批面板 + 问答卡片 |
 * | `chat/ChatSessionFiles.kt` | 会话文件面板（含 HTML 结构预览） |
 * | `chat/ChatSheets.kt` | 模型 / 工作区 / 新建会话三个弹层 |
 * | `chat/ChatToast.kt` | Toast 与剪贴板助手（原私有 `toast`，多处共用） |
 *
 * 跨文件调用的组件由 `private` 提升为 `internal`（仍只在 `ui.chat` 包内可见）；
 * 两个纯函数（[sessionDisplayTitle] / [sessionPaneWidth]）保持 `internal` 不动 ——
 * 它们本来就被 `SessionPaneLayoutTest` 直接调用。
 *
 * **行为零变化**：`testDebugUnitTest` 27 类 / 290 用例与拆分前完全一致。
 *
 * WebView 版详情页（`ui/ChatWebScreen.kt`）刻意**不**放进这个包：那是另一条实现路径
 * （`assets/chat.html`），与原生组件没有共用代码，见 `TECH-DEBT.md` 的 DEBT-001。
 */
