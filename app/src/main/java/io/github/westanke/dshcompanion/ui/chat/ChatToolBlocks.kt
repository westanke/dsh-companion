package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ToolBlockView

/**
 * 工具调用的**专用块**渲染（对标官方 Web 的 ReadBlock / TerminalBlock / DiffBlock；
 * 依据《官方Web界面与APP功能对比 2026-10-18》第一梯队①）。
 *
 * 分发在 data 层（[toolBlockView]），这里只认 [ToolBlockView]：
 * - [ReadBlockView]：文件路径头 + 内容（内核已带 `N: ` 行号前缀，直接等宽展示）；
 * - [TerminalBlockView]：`$ command` 提示符行 + 暗底等宽输出；
 * - [DiffBlockView]：旧/新文本对照（`- ` 红 / `+ ` 绿前缀）；
 * - [ToolBlockView.Generic]：回 `ChatToolDetail.kt` 的原通用渲染，一字未改。
 *
 * 三种专用块都保留「复制」能力 —— 手机上开不了终端，把输出复制出来是唯一带现场的办法
 * （与 [ToolTextBlock] 同一原则）。每个块独立可滚（纵向 + 横向两轴），高度有上限。
 */

/** 专用块共用的暗色代码底（深浅主题都用暗底：终端/代码的「黑底」是用户的稳定预期）。 */
private val codeBackground: Color = Color(0xFF14171C)

private val codeOnBackground: Color = Color(0xFFD7DCE3)

/**
 * 按分发结果渲染专用块。调用方（[ToolCallDetailBlock]）保留头部（工具名/状态/耗时），
 * 这里只替换原来的「输入 + 输出」两段。
 */
@Composable
internal fun ToolSpecialBlock(view: ToolBlockView) {
    when (view) {
        is ToolBlockView.Read -> ReadBlockView(view)
        is ToolBlockView.Terminal -> TerminalBlockView(view)
        is ToolBlockView.Diff -> DiffBlockView(view)
        is ToolBlockView.Generic -> GenericToolArguments(view.formattedArguments)
    }
}

// ---------------------------------------------------------------- read

@Composable
private fun ReadBlockView(view: ToolBlockView.Read) {
    val rangeLabel = when {
        view.offset == null && view.limit == null -> null
        else -> {
            val from = view.offset ?: 1
            val to = if (view.limit != null) from + view.limit - 1 else null
            if (to != null) "L$from–$to" else "L$from–"
        }
    }
    SpecialBlockFrame(header = {
        BlockHeaderRow(
            title = view.path.ifBlank { stringResource(R.string.tool_read_title) },
            badge = rangeLabel,
        )
    }) {
        if (view.body.isEmpty()) {
            BlockHintText(stringResource(R.string.tool_detail_no_result))
        } else {
            ScrollableCodeText(text = view.body, maxHeightDp = 260)
        }
    }
}

// ---------------------------------------------------------------- terminal

@Composable
private fun TerminalBlockView(view: ToolBlockView.Terminal) {
    SpecialBlockFrame(header = {
        BlockHeaderRow(
            title = stringResource(R.string.tool_terminal_title),
            badge = view.description?.takeIf { it.isNotBlank() },
        )
    }) {
        if (view.command.isNotBlank()) {
            // `$ ` 提示符是纯装饰：不能用 stringResource（它是语法符号不是文案）。
            Text(
                "$ " + view.command,
                color = codeOnBackground,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1D232C))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                softWrap = false,
            )
        }
        if (view.running) {
            BlockHintText(stringResource(R.string.tool_detail_pending))
        } else if (view.output.isBlank()) {
            BlockHintText(stringResource(R.string.tool_detail_empty_result))
        } else {
            ScrollableCodeText(text = view.output, maxHeightDp = 260)
        }
    }
}

// ---------------------------------------------------------------- diff

@Composable
private fun DiffBlockView(view: ToolBlockView.Diff) {
    val newFileLabel = stringResource(R.string.tool_diff_new_file)
    val removeLabel = stringResource(R.string.tool_diff_remove)
    val addLabel = stringResource(R.string.tool_diff_add)
    val replaceAllLabel = stringResource(R.string.tool_diff_replace_all)
    SpecialBlockFrame(header = {
        BlockHeaderRow(
            title = view.path.ifBlank { newFileLabel },
            badge = when {
                view.replaceAll -> replaceAllLabel
                view.oldText == null -> newFileLabel
                else -> null
            },
        )
    }) {
        if (view.oldText == null) {
            // write：新建文件，全文按「+ 」前缀渲染。
            view.newText?.let {
                ScrollableCodeText(
                    text = it.lineSequence().joinToString("\n") { line -> "+ $line" },
                    maxHeightDp = 260,
                    added = true,
                )
            } ?: BlockHintText(addLabel)
        } else {
            if (view.oldText.isNotBlank()) {
                DiffSideLabel(removeLabel)
                ScrollableCodeText(
                    text = view.oldText.lineSequence().joinToString("\n") { line -> "- $line" },
                    maxHeightDp = 160,
                    removed = true,
                )
            }
            if (view.newText != null) {
                if (view.oldText.isNotBlank()) DiffSideLabel(addLabel)
                ScrollableCodeText(
                    text = view.newText.lineSequence().joinToString("\n") { line -> "+ $line" },
                    maxHeightDp = 160,
                    added = true,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- generic（回通用）

/** Generic 分支：只渲染输入（与原 ToolCallDetailBlock 的输入段一致），输出由调用方兜底。 */
@Composable
private fun GenericToolArguments(formattedArguments: String) {
    if (formattedArguments.isBlank()) {
        BlockHintText(stringResource(R.string.tool_detail_no_args))
    } else {
        ToolTextBlock(
            label = stringResource(R.string.tool_detail_input),
            text = formattedArguments,
            isError = false,
            maxHeightDp = 160,
        )
    }
}

// ---------------------------------------------------------------- 共用小件

/** 专用块外壳：暗底圆角 + 可选头部行（路径/标题 + 徽标）。 */
@Composable
private fun SpecialBlockFrame(
    header: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = codeBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            header?.invoke()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) { content() }
        }
    }
}

@Composable
private fun BlockHeaderRow(title: String, badge: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f, fill = false),
            color = codeOnBackground,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
        )
        badge?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 8.dp),
                color = Color(0xFF8FA3BF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** 暗底可滚正文：两轴滚动 + 复制。 */
@Composable
private fun ScrollableCodeText(text: String, maxHeightDp: Int, removed: Boolean = false, added: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copiedHint = stringResource(R.string.copied)
    val lineColor = when {
        removed -> Color(0xFFFF8A80)
        added -> Color(0xFF9BE29B)
        else -> codeOnBackground
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .copyOnClick(text, context, copiedHint),
    ) {
        Text(
            text,
            color = lineColor,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            softWrap = false,
        )
    }
}

/** 点击整块复制正文（单击复制；拖动手势仍归滚动，二者不冲突）。 */
private fun Modifier.copyOnClick(
    text: String,
    context: android.content.Context,
    copiedHint: String,
): Modifier = this.clickable {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tool-block", text))
    android.widget.Toast.makeText(context, copiedHint, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun BlockHintText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color(0xFF8FA3BF),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DiffSideLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color(0xFF8FA3BF),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
    )
}
