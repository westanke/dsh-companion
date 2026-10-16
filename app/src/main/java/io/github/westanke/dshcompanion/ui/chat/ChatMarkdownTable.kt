package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Markdown 表格渲染：**两列**（左字段名、右值），右列自然折行。
 *
 * ## 为什么不是「一列一个字段的竖排卡片」
 *
 * 第一版就是竖排卡片（每个字段单独一张卡、字段名与值上下排）。真机反馈「**表格没可读性了**」：
 * 竖排卡片把「阶段 / 做什么 / 目的」这种**多字段行**拆成三截，读一行要在三张卡之间跳，
 * 而且每张卡自带背景与圆角，视觉噪音比原来的表格还大。
 *
 * 对照 Whaleness（`dsh-study/实施-2026-10-17/截图/图2-whaleness.jpg`）后改成现在这样：
 * **每行数据一行**，左边字段名（窄列、固定宽）、右边值（**宽列、自然折行**），
 * 字段与字段之间用细分隔线隔开 —— 与网页端表格的阅读顺序一致，长值也不会被切掉。
 *
 * ## 表格为什么能出现（第一版的隐藏坑）
 *
 * 渲染库 `com.mikepenz.markdown` 默认用 **CommonMark** flavour —— 它**不解析 GFM 表格**，
 * 所以 `MarkdownComponents.table` 这个回调**根本不会被调用**，表格会被当成普通段落渲染
 * （表现就是「阶段 / 做什么 / 目的」竖排密排、没有表格结构）。
 * 修法：给 `Markdown(...)` 传 `flavour = GFMFlavourDescriptor()`，
 * 表格节点才会被解析出来，本组件才会接上。
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
private val LABEL_MAX_WIDTH = 108.dp

/**
 * 一行数据一行：左边字段名、右边值。
 *
 * 右侧的值**刻意不设 `maxLines`**：Compose 的 `Text` 默认就是自动折行的，
 * 而默认渲染之所以截断，正是因为库那边给了固定列宽 —— 这里从根上去掉那个约束。
 */
@Composable
internal fun ChatMarkdownTable(table: MarkdownTable, modifier: Modifier = Modifier) {
    // 每行一张 Surface 而不是 Card：Whaleness 那边也是「一块浅底 + 细分隔线」，
    // 不需要圆角浮起的效果（那是表单控件的感觉，不是阅读材料）。
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            table.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                row.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            table.header.getOrNull(columnIndex) ?: "#${columnIndex + 1}",
                            modifier = Modifier
                                .widthIn(max = LABEL_MAX_WIDTH)
                                .padding(end = 10.dp, top = 2.dp, bottom = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            cell.ifBlank { "—" },
                            modifier = Modifier.weight(1f).padding(top = 2.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
