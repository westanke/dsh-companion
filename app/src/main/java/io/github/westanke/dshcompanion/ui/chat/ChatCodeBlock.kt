package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import io.github.westanke.dshcompanion.R

/**
 * 聊天气泡正文的 Markdown 组件：换掉**代码块与表格**，其余组件沿用渲染库的默认实现。
 *
 * 为什么两个代码回调都要换：Markdown 把代码分给两条互不相干的路 —— 围栏块（```kotlin）
 * 走 `codeFence`，四空格缩进的块走 `codeBlock`。只覆盖 `codeBlock` 的话，模型输出里
 * **几乎全部**的代码（清一色围栏写法）一行都不会变，表现为「明明传了 components，
 * 界面却像没传」。
 *
 * 为什么语言与正文不自己从 AST 里切：语言标签要取 `FENCE_LANG` 叶子，正文要取首尾
 * 围栏行之间的偏移再 `replaceIndent`，而且这些偏移是**整篇 markdown 的绝对偏移**
 * （`MarkdownComponentModel.content` 是全文，不是这一段）—— 自己重写一遍只会更脆。
 * 渲染库已经把这件事做完了，我们只接管「画成什么样」：[MarkdownCodeFence] /
 * [MarkdownCodeBlock] 的 `block` 参数拿到的就是现成的 `(code, language, style)`。
 *
 * 为什么放在文件级 val：组件实例无状态，每次重组新建一份，等于让整棵 Markdown 子树
 * 每帧都判「组件换了」并全量重绘。
 */
internal val chatMarkdownComponents: MarkdownComponents = markdownComponents(
    codeFence = { model ->
        MarkdownCodeFence(
            content = model.content,
            node = model.node,
            style = model.typography.code,
            block = { code, language, style ->
                ChatCodeBlock(code = code, language = language, style = style)
            },
        )
    },
    codeBlock = { model ->
        MarkdownCodeBlock(
            content = model.content,
            node = model.node,
            style = model.typography.code,
            block = { code, language, style ->
                ChatCodeBlock(code = code, language = language, style = style)
            },
        )
    },
    // 表格：渲染库自带的表格是**横向滚动 + 固定列宽**（内部 tableMaxWidth / widthIn 那一套），
    // 手机上表格一宽右侧就被截断。真机反馈：「渲染的表格直接截断，应该自动换行」。
    //
    // 该库的 MarkdownComponents 是开放接口（有 getTable()），所以不必换库 —— 覆盖这一项即可。
    // 解析失败时**不吞内容**：回落到 MarkdownText，原文至少还在。
    table = { model ->
        val table = parseMarkdownTable(model.content)
        if (table == null) {
            MarkdownText(
                content = model.content,
                node = model.node,
                modifier = Modifier.fillMaxWidth(),
                style = model.typography.text,
            )
        } else {
            ChatMarkdownTable(table = table, modifier = Modifier.fillMaxWidth())
        }
    },
)

/**
 * 一个代码块：**语言标签 + 复制按钮 + 高亮 + 横向滚动**。
 *
 * 为什么长行不折行（`softWrap = false` + `horizontalScroll`）：代码的换行位置是有意义的，
 * 自动折行会把一行拆成两截、缩进对不齐，读起来「变成一坨」；横向滚动是编辑器与网页端
 * 一致的读法。
 *
 * 为什么语言为空时**保留整条顶栏**（只空着标签）：顶栏高度若随语言有无上下跳，复制按钮
 * 就会在消息之间错位，用户按肌肉记忆点下去会点到隔壁去。宁可留一行空标签。
 *
 * 复制的是**原始代码文本**（`block` 给的 `code`），不是屏幕上带标签的整块 —— 用户要粘进
 * 编辑器，多一行 ```kotlin 就是语法错误。提示走 [copyText]（对话页共用的一次性反馈）。
 */
@Composable
internal fun ChatCodeBlock(code: String, language: String?, style: TextStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 高亮只取决于「这段代码 + 这个语言」，与主题无关（色板取的是深浅底都能读的中间调，
    // 见 ChatCodeHighlight.kt），所以按内容记忆：滚动与流式重组不会重算整段。
    val highlighted = remember(code, language) { highlightCode(code, language) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language?.trim().orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                CodeCopyButton(onClick = { copyText(context, "code", code) })
            }
            Text(
                highlighted,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
                style = style,
                softWrap = false,
            )
        }
    }
}

/**
 * 代码块右上角的复制键。
 *
 * 为什么不用 `IconButton`：它自带 48dp 的最小触控尺寸，会把顶栏撑得比代码本身还高，
 * 一条两行的代码块会变成「一大块空顶栏 + 两行字」。这里用内边距自己凑出约 30dp 的
 * 可点区（`clickable` 写在 `padding` 之前，点的范围包含内边距本身）。
 */
@Composable
private fun CodeCopyButton(onClick: () -> Unit) {
    Icon(
        Icons.Outlined.ContentCopy,
        contentDescription = stringResource(R.string.tool_detail_copy),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(7.dp)
            .size(15.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
