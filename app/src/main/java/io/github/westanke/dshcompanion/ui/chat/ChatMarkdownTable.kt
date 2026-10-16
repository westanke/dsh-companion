package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Markdown 表格的**纵向卡片**渲染。
 *
 * ## 为什么要自己渲染（真机反馈）
 *
 * 渲染库 `com.mikepenz.markdown` 自带的表格是**横向滚动 + 固定列宽**的（内部
 * `tableMaxWidth` / `widthIn` 那一套）：手机上表格一宽，右侧就被**截断**，看不到也划不顺。
 * 用户原话是「渲染的表格直接截断，应该自动换行」。
 *
 * 该库的 `MarkdownComponents` 是开放接口（有 `getTable()`），所以不必换库、也不必 fork ——
 * 覆盖 `table` 这一项即可。
 *
 * ## 为什么选「纵向卡片」而不是「加横向滚动」
 *
 * 横滑在手机上对**多列表格**是不友好的读法：每次只能看到两三列，还得一边滑一边记列名。
 * 纵向卡片把每一行数据拆成「字段名 + 值」的键值对，**长值自然折行**，滑一下就能读完整行 ——
 * 这正是「自动换行」要的效果。
 *
 * 代价是行与行之间不再横向对齐；对排查型表格（配置项、参数说明、对比）来说，
 * 读得完比看得齐更重要。
 *
 * ## 解析为什么用文本而不是 AST
 *
 * `model.content` 里能直接拿到这段 Markdown 的原文，按 `|` 切分即可；
 * 走 AST 要处理 `org.intellij.markdown` 的节点类型，而这套解析规则**可以单测** ——
 * 分隔行、空单元格、列数不齐、转义竖线都是边界。
 */
internal data class MarkdownTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

/**
 * 从一段 Markdown 里解析表格；**不是表格就返回 null**（调用方回落到库的默认渲染）。
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

/**
 * 纵向卡片表格：一行数据一张卡片，卡片内是「字段名 + 值」。
 *
 * 值这一侧**刻意不设 `maxLines`**：Compose 的 `Text` 默认就是自动折行的，
 * 而默认渲染之所以截断，正是因为库那边给了固定列宽 —— 这里从根上去掉那个约束。
 */
@Composable
internal fun ChatMarkdownTable(table: MarkdownTable, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        table.rows.forEach { row ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    row.forEachIndexed { index, cell ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 5.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        // 字段名取表头；行比表头短时（理论上 normalizeColumns 已补齐）用序号兜底。
                        val name = table.header.getOrNull(index) ?: "#${index + 1}"
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                name.ifBlank { "#${index + 1}" },
                                modifier = Modifier.width(96.dp).padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                cell.ifBlank { "—" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
