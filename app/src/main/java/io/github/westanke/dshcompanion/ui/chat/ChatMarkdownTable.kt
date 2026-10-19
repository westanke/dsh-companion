package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

/**
 * Markdown 表格渲染：**真表格**（表头行 + 边框 + 数据行）。
 *
 * ## 样式依据
 *
 * 对照 Whaleness（`dsh-study/实施-2026-10-17/截图/图2-whaleness.jpg`）定的稿：
 * 表头一行（深色底、白字）、数据行浅底、行与列都有分隔线。这样它**看起来就是一张表** ——
 * 而不是「一段左边有字段名的文字」。
 *
 * 三轮真机反馈的演进（都留在这里，免得再走回头路）：
 * 1. 第一版用渲染库默认表格 → **横向滚动 + 固定列宽**，手机上一宽右侧被切掉；
 * 2. 第二版改成「一字段一张竖排卡片」→ 被反馈「**没可读性了**」（多字段行要跳着看）；
 * 3. 第三版改成「两列 + 无表头」→ 被反馈「**不像表**」（就是普通文字行，缺表头与边框）。
 *
 * 所以现在是第四版：**保留两列布局**（左字段名窄、右值宽且折行），
 * 但补上表头行与分隔线 —— 折行与表格感两个都要，不是二选一。
 */
internal data class MarkdownTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

/**
 * 从一段 Markdown 里解析表格；**不是表格就返回 null**（调用方回落到默认渲染）。
 *
 * 只认「以 `|` 开头且以 `|` 结尾」的行，其余一律忽略 —— 这样不管传进来的是整段消息
 * 还是只有表格的片段，都能正确工作。
 */
internal fun parseMarkdownTable(content: String): MarkdownTable? {
    val lines = content.lines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") && it.length > 1 }
    if (lines.size < 3) return null

    val cells = lines.map { splitTableRow(it) }
    // 第二行必须是 |---|---| 这样的分隔行：这是表格与「竖线列表」的区别。
    if (!isSeparatorRow(cells[1])) return null

    val header = cells[0]
    // 列数不齐时以表头为准补齐/截断：截断会丢数据，补齐只多出空单元格，选后者。
    val width = header.size
    val rows = cells.drop(2).map { row -> normalizeColumns(row, width) }
    if (rows.isEmpty()) return null
    return MarkdownTable(header, rows)
}

/** 按表头列数补齐空单元格或截掉多出来的（转义竖线已经在切分时处理过）。 */
private fun normalizeColumns(row: List<String>, width: Int): List<String> =
    if (row.size == width) row else List(width) { index -> row.getOrNull(index).orEmpty() }

private fun splitTableRow(line: String): List<String> {
    val inner = line.drop(1).dropLast(1)
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < inner.length) {
        val char = inner[index]
        when {
            // 反斜杠转义：`\|` 是内容里的竖线，`\\` 是内容里的反斜杠 —— 只有这两个才消费反斜杠。
            // 别的字符**原样保留反斜杠**：Windows 路径 `F:\work` 若被当成转义，会变成 `F:work`
            // （真机反馈的表格里就有这种路径，而且这条边界正是单测先发现的）。
            char == '\\' && index + 1 < inner.length &&
                (inner[index + 1] == '|' || inner[index + 1] == '\\') -> {
                current.append(inner[index + 1])
                index += 2
            }
            char == '|' -> {
                cells += current.toString().trim()
                current.clear()
                index += 1
            }
            else -> {
                current.append(char)
                index += 1
            }
        }
    }
    cells += current.toString().trim()
    return cells
}

/** `---` / `:--:` / `--:` / `:-:` 都算；空内容或含字母则不是。 */
private fun isSeparatorRow(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { it.matches(Regex("^:?-{1,}:?$")) }

/** 字段名列的宽度上限：超过就省略（右侧的值才是重点）。 */
private val LABEL_MAX_WIDTH = 104.dp

/**
 * 这个单元格能不能安全地走行内 Markdown 渲染。
 *
 * 只认**纯行内**内容：含换行、表格行、列表标记、围栏代码块、标题的单元格一律判否，
 * 回落 `Text` 直出。理由是自绘的两列布局里嵌块级结构必然错位（渲染库给的块间距、
 * 缩进、代码块底色都在这个布局里没有对应的容器），而错位比「反引号原样显示」更糟。
 */
internal fun isInlineOnly(cell: String): Boolean =
    '\n' !in cell && '\r' !in cell &&
        !cell.trimStart().startsWith("#") &&
        !cell.trimStart().startsWith(">") &&
        !cell.trimStart().startsWith("- ") &&
        !cell.trimStart().startsWith("* ") &&
        !cell.contains("|") &&
        !cell.contains("```")

/**
 * 表格本体：**表头行 + 数据行**，行与列都有分隔线。
 *
 * 值这一侧**刻意不设 `maxLines`**：Compose 的 `Text` 默认就是自动折行的，
 * 而默认渲染之所以截断，正是因为库那边给了固定列宽 —— 这里从根上去掉那个约束。
 */
@Composable
internal fun ChatMarkdownTable(table: MarkdownTable, modifier: Modifier = Modifier) {
    // 外框一道边 + 圆角：图2 Whaleness 那边也是「一张有边框的表」，
    // 没有这道边，内部再多分隔线也还是一段文字。
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── 表头行：深色底 + 白字，一眼看出「这是列名」而不是数据 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                TableRow(
                    cells = table.header,
                    isHeader = true,
                    showBottomDivider = true,
                )
            }
            // ── 数据行 ──
            table.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TableRow(cells = row, isHeader = false, showBottomDivider = false)
            }
        }
    }
}

/** 表格的一行：每列之间一道竖线，列宽按内容（字段名列有上限、值列吃掉剩余宽度）。 */
@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean, showBottomDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            cells.forEachIndexed { columnIndex, cell ->
                if (columnIndex > 0) {
                    VerticalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (columnIndex == 0 && cells.size < 3) {
                    // 两列表的第一列是字段名：窄列、最多两行、超出省略（Whaleness 定稿布局，勿动）。
                    Text(
                        cell,
                        modifier = Modifier.widthIn(max = LABEL_MAX_WIDTH).padding(start = 10.dp),
                        color = if (isHeader) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else if (columnIndex == 0) {
                    // 三列及以上的数据表：第一列是有完整内容的实体（如「内核
                    // @deepseek-ai/dsh 0.2.0-rc.2」），不能套字段名窄列把它省略掉。
                    // 与其余列同权、自然折行，只保留左侧留白；颜色仍区分表头/数据。
                    Box(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                        val text = cell.ifBlank { "—" }
                        if (isInlineOnly(text)) {
                            Markdown(
                                content = text,
                                typography = compactMarkdownTypography(),
                                flavour = GFMFlavourDescriptor(),
                                components = chatMarkdownComponents,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text,
                                color = if (isHeader) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    // 其余列是值：吃掉剩余宽度、自然折行。
                    Box(Modifier.weight(1f).padding(end = 10.dp)) {
                        // 单元格接一次行内 Markdown 渲染：`2026-10-13-07-39-52/` 这种
                        // 带反引号的路径原先**原样显示反引号**（v2.2.44 真机验收 W2）——
                        // 表格是自绘的，单元格用 `Text(cell)` 直出，不做行内解析，
                        // 于是正文里那套 GFM 规则在这里整条失效。
                        //
                        // 只走「不含块级构造」的行内判定：含换行、表格、列表、围栏代码块的
                        // 单元格仍走纯文本那条 —— 自绘的两列布局里嵌一个块级结构只会错位。
                        val text = cell.ifBlank { "—" }
                        if (isInlineOnly(text)) {
                            Markdown(
                                content = text,
                                typography = compactMarkdownTypography(),
                                flavour = GFMFlavourDescriptor(),
                                components = chatMarkdownComponents,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (showBottomDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
