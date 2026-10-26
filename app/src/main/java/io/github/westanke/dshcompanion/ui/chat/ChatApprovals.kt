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
import io.github.westanke.dshcompanion.ui.SemanticWarnColor
import io.github.westanke.dshcompanion.ui.SemanticWarnContainer
import io.github.westanke.dshcompanion.ui.OnSemanticWarnContainer
import java.io.InputStream

/**
 * 两类**交互卡片**：工具调用审批（[ApprovalPanel]）与待答问题（[QuestionCard]）。
 *
 * 两者都占「消息区下方、输入区上方」那块地方，但**只有审批顶掉输入区**：审批是内核
 * 阻塞着等决定，用户此刻没别的事可做；问题是 Agent 已经继续跑之后的补答，拿掉输入框
 * 只会让用户连话都发不出去。
 *
 * 两个纯函数（[isAnswered] / [buildAnswers]）放这里以便单测（`QuestionAnswerTest`）：
 * 提交按钮的启用条件就是它们对所有题取与，而「没选项的题只看 custom」最容易漏掉 ——
 * 漏了的表现是按钮永远点不亮。
 */
@Composable
internal fun ApprovalStatusLabel(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = SemanticWarnColor,
        )
        Text(
            stringResource(R.string.awaiting_approval),
            color = SemanticWarnColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun ApprovalLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.approval_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ApprovalPanel(
    approval: PendingApproval,
    count: Int,
    deciding: Boolean,
    canWrite: Boolean,
    onReject: () -> Unit,
    onAllowOnce: () -> Unit,
) {
    val fullAccess = approval.risk == "full-access"
    var showRiskDialog by rememberSaveable(approval.id) { mutableStateOf(false) }
    var acknowledged by rememberSaveable(approval.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (fullAccess) SemanticWarnColor else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (fullAccess) Icons.Outlined.WarningAmber else Icons.Outlined.Build,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (fullAccess) SemanticWarnColor else MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.approval_required), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (count > 1) stringResource(R.string.approval_count, approval.toolName, count)
                        else approval.toolName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (deciding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            approval.reason?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            approval.detail?.takeIf(String::isNotBlank)?.let { detail ->
                Text(
                    stringResource(R.string.approval_command_preview),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer {
                    Text(
                        detail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 144.dp)
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (fullAccess) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = SemanticWarnColor,
                    )
                    Text(
                        stringResource(R.string.full_access),
                        color = SemanticWarnColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onReject, enabled = canWrite && !deciding) {
                    Text(stringResource(R.string.reject))
                }
                Button(
                    onClick = {
                        if (fullAccess) {
                            acknowledged = false
                            showRiskDialog = true
                        } else onAllowOnce()
                    },
                    enabled = canWrite && !deciding,
                ) {
                    Text(stringResource(R.string.allow_once))
                }
            }
        }
    }
    if (showRiskDialog) {
        AlertDialog(
            onDismissRequest = { showRiskDialog = false; acknowledged = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = SemanticWarnColor) },
            title = { Text(stringResource(R.string.confirm_full_access)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.full_access_warning))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text(stringResource(R.string.full_access_ack), Modifier.padding(start = 4.dp))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRiskDialog = false; acknowledged = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            confirmButton = {
                Button(
                    onClick = { showRiskDialog = false; onAllowOnce() },
                    enabled = acknowledged,
                ) {
                    Text(stringResource(R.string.confirm_allow_once))
                }
            },
        )
    }
}

/**
 * 一道题算不算答完了：有选项的要选中至少一个，没选项的要自由文本非空。
 *
 * 抽成纯函数是为了能测（见 `QuestionAnswerTest`）：提交按钮的启用条件就是它对所有题取与，
 * 而「没选项的题只看 custom」这条最容易在改动里被漏掉 —— 漏了的表现是按钮永远点不亮。
 */

internal fun isAnswered(question: ChatQuestion, selected: Collection<String>, custom: String): Boolean =
    if (question.options.isEmpty()) custom.isNotBlank() else selected.isNotEmpty()

/**
 * 把界面上的勾选与输入收成请求体。
 *
 * 两条规则：
 * - 有选项的题只带 `selected`，并**按选项原顺序**过滤 —— 用户点选的先后顺序不该影响请求体；
 * - 没选项的题只带 `custom`（去掉首尾空白）。
 *
 * 顺序与 [questions] 一致，`id` 原样回带。
 */

internal fun buildAnswers(
    questions: List<ChatQuestion>,
    selected: Map<String, Set<String>>,
    custom: Map<String, String>,
): List<QuestionAnswer> = questions.map { question ->
    if (question.options.isEmpty()) {
        QuestionAnswer(
            id = question.id,
            selected = emptyList(),
            custom = custom[question.id].orEmpty().trim(),
        )
    } else {
        val picked = selected[question.id].orEmpty()
        QuestionAnswer(
            id = question.id,
            selected = question.options.map { it.label }.filter { it in picked },
        )
    }
}

/**
 * 待回答问题卡片（`ask_user_question`，方案 A 只做 `continued`）。
 *
 * 与审批面板的关系：两者都占「消息区下方、输入区上方」那块地方，但**只有审批顶掉输入区**。
 * 审批是内核阻塞着等决定，用户此刻没别的事可做；问题是 Agent 已经继续跑之后的补答，
 * 拿掉输入框只会让用户连话都发不出去。
 *
 * 外形沿用审批卡那一套（Surface + 描边 + 抬头行 + 底部按钮），信息量与网页版一一对应：
 * 抬头、每题的 header / question / detail、选项及其说明、没有选项时给自由文本。
 * 抬头用主色而不是审批那个警告色：它要的是「等你回答」，不是「危险」。
 */

@Composable
internal fun QuestionCard(
    set: PendingQuestionSet,
    answered: Boolean,
    submitting: Boolean,
    canWrite: Boolean,
    onSubmit: (List<QuestionAnswer>) -> Unit,
) {
    // 草稿按 callId 记住：同一组题在重组之间不能丢勾选；换了一组就从零开始，
    // 免得上一组的答案漏到下一组（那是「提交上去的答案张冠李戴」这类事故的源头）。
    val selected = remember(set.callId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(set.callId) { mutableStateMapOf<String, String>() }
    val interactive = canWrite && !submitting && !answered
    val complete = set.questions.isNotEmpty() && set.questions.all { question ->
        isAnswered(question, selected[question.id].orEmpty(), custom[question.id].orEmpty())
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (answered) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
        ),
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (answered) Icons.Outlined.TaskAlt else Icons.Outlined.QuestionAnswer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (answered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (answered) R.string.question_answered else R.string.question_waiting,
                        ),
                        color = if (answered) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (set.questions.size > 1) {
                        Text(
                            stringResource(R.string.question_count, set.questions.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            set.questions.forEach { question ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    question.header?.takeIf(String::isNotBlank)?.let { header ->
                        Text(
                            header,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(question.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    question.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (question.options.isEmpty()) {
                        // 没有选项 = 这题要自由文本，正好对应协议里的 custom。
                        OutlinedTextField(
                            value = custom[question.id].orEmpty(),
                            onValueChange = { custom[question.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = interactive,
                            label = { Text(stringResource(R.string.answer_your_own)) },
                            minLines = 1,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else {
                        question.options.forEach { option ->
                            val checked = selected[question.id]?.contains(option.label) == true
                            val toggle = {
                                val current = selected[question.id].orEmpty()
                                selected[question.id] = when {
                                    // 单选：再点一次已选中的就是取消（与网页版 radio 的行为对齐不了，
                                    // 那里 radio 点不掉；这里允许取消是为了让用户能改主意，不会误提交）。
                                    !question.multiSelect -> if (checked) emptySet() else setOf(option.label)
                                    checked -> current - option.label
                                    else -> current + option.label
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(enabled = interactive) { toggle() }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (question.multiSelect) {
                                    Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = interactive)
                                } else {
                                    RadioButton(selected = checked, onClick = { toggle() }, enabled = interactive)
                                }
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                    option.description?.takeIf(String::isNotBlank)?.let { description ->
                                        Text(
                                            description,
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

            if (!answered) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (submitting) {
                        Text(
                            stringResource(R.string.answer_submitting),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Button(
                        onClick = { if (complete) onSubmit(buildAnswers(set.questions, selected, custom)) },
                        enabled = interactive && complete,
                    ) {
                        Text(stringResource(R.string.submit_answer))
                    }
                }
            }
        }
    }
}
