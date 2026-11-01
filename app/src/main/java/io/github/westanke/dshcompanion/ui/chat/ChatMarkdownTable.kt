package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.ast.ASTNode
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
 *
 * 4. 第五版（#9，2026-11-01）：**≥3 列的数据表列宽从「均分」改为「按内容估宽」**。
 *    第四版对 ≥3 列全部 `weight(1f)` 均分，4 列表每列只剩 ~90dp，长词（`TopAppBar`、
 *    `1080×2400`）被竖线与屏缘裁断，密排成小字墙 —— 真机截图对比鸿蒙版（同表可读）立项 #9。
 *    对照移植鸿蒙实现（`dsh-companion-harmony/entry/src/main/ets/core/MarkdownLite.ts:326`
 *    `markdownTableColumnWidths` + `MarkdownView.ets:267` 渲染层）：估宽夹 `[64, 240]dp`、
 *    总宽超屏走最外层横向滚动。**注意第一版被否的是「库的固定列宽不感知内容」，不是横向
 *    滚动本身** —— 这里的列宽是内容估出来的，超屏横滚只是兜底，三者缺一不可，别再走回头路。
 *    ≤2 列的字段表仍走第四版布局（Whaleness 定稿，勿动）。
 */
internal data class MarkdownTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

/**
 * 从一段 Markdown 里解析表格；**不是表格就返回 null**（调用方回落到默认渲染）。
 *
 * 只认「以 `|` 开头且以 `|` 结尾」的行，其余一律忽略。
 *
 * ## 调用方必须先切片（#13）
 *
 * 这里的「把所有 `|…|` 行收进同一批」只有在**输入就是一张表**时才成立。渲染库交给
 * `table` 回调的 `MarkdownComponentModel.content` 是**整篇 markdown 全文**，直接喂进来会把
 * 后面每一张表的行都并进第一批：第二张表的表头行与 `|---|---|` 分隔行被 `normalizeColumns`
 * 截成第一张表的列数，渲染成「# 内容」「--- ---」两行数据，第二张表整列消失（真机 #13 截图）。
 * 所以调用方要先用 [tableNodeSlice] 按 AST 节点切出这一张表，再调用本函数。
 *
 * 兜底：即使切片出错，含**多个分隔行**的输入也判为非表格（返回 null，回落纯文本）——
 * 一张合法的 GFM 表格只有一个分隔行，多出来说明上游切错了，宁可显示原文也不显示错结构。
 */
internal fun parseMarkdownTable(content: String): MarkdownTable? {
    val lines = content.lines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") && it.length > 1 }
    if (lines.size < 3) return null

    val cells = lines.map { splitTableRow(it) }
    // 第二行必须是 |---|---| 这样的分隔行：这是表格与「竖线列表」的区别。
    if (!isSeparatorRow(cells[1])) return null
    // 一张表只能有一个分隔行；出现第二个说明输入是「多张表拼在一起」，别硬渲染。
    if (cells.drop(2).any(::isSeparatorRow)) return null

    val header = cells[0]
    // 列数不齐时以表头为准补齐/截断：截断会丢数据，补齐只多出空单元格，选后者。
    val width = header.size
    val rows = cells.drop(2).map { row -> normalizeColumns(row, width) }
    if (rows.isEmpty()) return null
    return MarkdownTable(header, rows)
}

/**
 * 把渲染库给的整篇 markdown 按 TABLE 节点的偏移**切成一张表的文本**（#13）。
 *
 * `org.intellij.markdown` 产出的 `TABLE` 节点，`startOffset` 正好是表头行的 `|`，
 * `endOffset` 是最后一行数据的行尾 —— 不含尾随空行，切出来是干净的单表文本。
 *
 * 偏移越界时（理论上不该发生）夹回合法范围，而不是抛异常：渲染路径上一次崩溃
 * 会让整条消息消失，比表格画错严重得多。
 */
internal fun tableNodeSlice(content: String, node: ASTNode): String =
    tableSliceRange(content, node.startOffset, node.endOffset)

/** [tableNodeSlice] 的纯函数内核：便于单测直接断言夹取边界，不需要构造 ASTNode。 */
internal fun tableSliceRange(content: String, startOffset: Int, endOffset: Int): String {
    val from = startOffset.coerceIn(0, content.length)
    val to = endOffset.coerceIn(from, content.length)
    return content.substring(from, to)
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

// ─────────────────────────────────────────────────────────────────────────────
// #9 内容感知列宽：对照鸿蒙 `dsh-companion-harmony/entry/src/main/ets/core/MarkdownLite.ts`
// 的 `markdownTableColumnWidths`（326-356 行）移植。以下是纯函数，单测见
// `ChatMarkdownTableTest`，不依赖 Compose —— 估宽是近似值，真正的排版交给 Text 折行。
// ─────────────────────────────────────────────────────────────────────────────

/** 列宽兜底下限（dp）：再窄一列就只剩省略号，横向滚反而更好读（鸿蒙同值 64）。 */
internal const val TABLE_MIN_COLUMN_DP = 64f

/** 单列上限（dp）：不然一个长句会把整张表撑成横向无限滚；超宽文字在列内折行（鸿蒙同值 240）。 */
internal const val TABLE_MAX_COLUMN_DP = 240f

/** 估宽时给单元格预留的左右内边距合计（dp）——与渲染层单元格留白（start 10 + end 10）同量级。 */
private const val TABLE_CELL_PADDING_DP = 20f

/** 列与列之间的间隔合计（dp）：VerticalDivider(1dp) + 左右各 8dp 留白 = 17dp。 */
internal const val TABLE_COLUMN_GAP_DP = 17f

/**
 * 西文字符的估宽系数：0.60 而非更小的 0.55（鸿蒙 `MarkdownLite.ts:292-303` 实测教训）。
 * 0.55 在真机上贴边到被反噬 —— 比例字体下小写字母平均约 0.55~0.58 em，`bundleName` 这类
 * 长标识符估出的列宽恰好比实际窄几个像素，被强制在词内断开（显示成 `bundleNam` / `e`）。
 * 0.60 留约 8% 余量，才能稳定「整格折行、单词不断」。
 */
private const val NARROW_CHAR_UNITS = 0.60f

/**
 * 一个字符占多少「字宽」：全角 1，西文 0.60。
 *
 * 只用来估列宽，不求精确 —— 汉字几乎都是正方形、西文接近半宽，这个比例足够挑出
 * 「这一列该给两格还是二十格」。码点区间照抄鸿蒙 `charWidthUnit`
 * （`MarkdownLite.ts:292-303`），标 `@patch` 五要素见鸿蒙源码注释。
 */
internal fun charWidthUnits(ch: Char): Float {
    val code = ch.code
    val wide = code in 0x1100..0x115f || code == 0x2329 || code == 0x232a ||
        code in 0x2e80..0xa4cf ||
        code in 0xac00..0xd7a3 ||
        code in 0xf900..0xfaff ||
        code in 0xfe30..0xfe6f ||
        code in 0xff00..0xff60 ||
        code in 0xffe0..0xffe6
    return if (wide) 1f else NARROW_CHAR_UNITS
}

/** 逐字符求和的显示宽度（字宽单位，非 dp）。 */
internal fun displayWidthUnits(text: String): Float {
    var units = 0f
    for (ch in text) units += charWidthUnits(ch)
    return units
}

/**
 * 估宽前剥掉行内标记（**粗体** / `代码` / [链接](url) / ![](图片) 等），
 * 对照鸿蒙「先过 `inlineMarkup` 再调估宽」的约定：否则 `[文字](很长的链接)` 里那段
 * URL 会把列宽抬到上限，整张表变得又空又宽。轻量正则即可 —— 估宽不求精确，只求
 * 不被标记语法虚抬。
 */
internal fun stripInlineMarkup(cell: String): String = cell
    .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1") // 图片：alt 留下、URL 吃掉
    .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1") // 链接：文字留下、URL 吃掉
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // 粗体
    .replace(Regex("__([^_]+)__"), "$1") // 粗体（下划线）
    .replace(Regex("~~([^~]+)~~"), "$1") // 删除线
    .replace(Regex("\\*([^*]+)\\*"), "$1") // 斜体
    .replace(Regex("`+"), "") // 反引号残余（成对在上面已被拆掉，这里兜不成对的）

/**
 * 每列估宽（dp）：取「表头 + 全部数据行」里最长的那格，乘字号加内边距，夹在上下限之间。
 *
 * 与鸿蒙的差异：安卓 ≥3 列表头与正文同为 `bodyMedium` 字号（鸿蒙表头略大），所以
 * `max(表头, 数据格)` 在乘字号之前取即可，不需要两个字号分别估。空表头返回空列表。
 *
 * @param fontSizeDp 单元格正文字号，传 `bodyMedium.fontSize` 换算成的 dp（含系统字体缩放）。
 */
internal fun markdownTableColumnWidthsDp(
    header: List<String>,
    rows: List<List<String>>,
    fontSizeDp: Float,
): List<Float> = List(header.size) { column ->
    var units = displayWidthUnits(stripInlineMarkup(header[column]))
    for (row in rows) {
        val cell = row.getOrNull(column).orEmpty()
        units = maxOf(units, displayWidthUnits(stripInlineMarkup(cell)))
    }
    val estimated = units * fontSizeDp + TABLE_CELL_PADDING_DP
    estimated.coerceIn(TABLE_MIN_COLUMN_DP, TABLE_MAX_COLUMN_DP)
}

/**
 * 把估宽分配进可用宽度：装得下就把剩余宽度按各列估宽比例摊回（表格仍撑满整行，
 * 与两列表的视觉一致）；装不下返回 null，调用方转横向滚动模式（列宽保持估宽定死）。
 */
internal fun resolveColumnWidthsDp(estimatedDp: List<Float>, availableDp: Float): List<Float>? {
    if (estimatedDp.isEmpty()) return emptyList()
    if (availableDp <= 0f) return null
    val gaps = TABLE_COLUMN_GAP_DP * (estimatedDp.size - 1)
    val spare = availableDp - estimatedDp.sum() - gaps
    if (spare < 0f) return null
    val sum = estimatedDp.sum()
    if (sum <= 0f) return estimatedDp
    return estimatedDp.map { it + spare * it / sum }
}

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
 * ≤2 列走第四版窄字段名布局（Whaleness 定稿）；≥3 列走 #9 内容感知列宽：
 * 每列按内容估宽（`markdownTableColumnWidthsDp`），装得下就摊回剩余宽度撑满整行，
 * 装不下转横向滚动兜底 —— 对照鸿蒙「横滚只加最外层」，列宽不被无界约束带偏。
 *
 * 单元格**仍然不设 `maxLines`**：估宽的上限（240dp）负责约束一列别无限宽，
 * 超出的文字在列内折行 —— 折行是安全方向，截断不是。
 */
@Composable
internal fun ChatMarkdownTable(table: MarkdownTable, modifier: Modifier = Modifier) {
    if (table.header.size < 3) {
        ChatMarkdownNarrowTable(table, modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // 正文字号换算成 dp（含系统字体缩放）：估宽乘它才接近「实际渲染宽度」。
        val fontSizeDp = with(density) {
            MaterialTheme.typography.bodyMedium.fontSize.toDp().value
        }
        val estimated = markdownTableColumnWidthsDp(table.header, table.rows, fontSizeDp)
        val resolved = resolveColumnWidthsDp(estimated, maxWidth.value)
        if (resolved != null) {
            ChatMarkdownTableFrame(
                table = table,
                modifier = Modifier.fillMaxWidth(),
                columnWidths = resolved,
            )
        } else {
            // 总估宽超屏：横向滚动兜底。Surface 按估宽总和定宽（含列间 17dp 间隔），
            // 内部的表头底色与分隔线在确定宽度里照常工作。
            val contentWidth = with(density) {
                (estimated.sum() + TABLE_COLUMN_GAP_DP * (estimated.size - 1)).dp
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChatMarkdownTableFrame(
                    table = table,
                    modifier = Modifier.width(contentWidth),
                    columnWidths = estimated,
                )
            }
        }
    }
}

/** 两列字段表：第四版 Whaleness 定稿布局（左字段名窄、右值宽且折行），#9 不动它。 */
@Composable
private fun ChatMarkdownNarrowTable(table: MarkdownTable, modifier: Modifier = Modifier) {
    ChatMarkdownTableFrame(table = table, modifier = modifier, columnWidths = null)
}

/** 表格外框：一道边 + 圆角 + 表头深底 + 行分隔。列宽策略（摊回/横滚/两列）由调用方决定。 */
@Composable
private fun ChatMarkdownTableFrame(
    table: MarkdownTable,
    modifier: Modifier,
    columnWidths: List<Float>?,
) {
    // 外框一道边 + 圆角：图2 Whaleness 那边也是「一张有边框的表」，
    // 没有这道边，内部再多分隔线也还是一段文字。
    Surface(
        modifier = modifier,
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
                    columnWidths = columnWidths,
                )
            }
            // ── 数据行 ──
            table.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TableRow(
                    cells = row,
                    isHeader = false,
                    showBottomDivider = false,
                    columnWidths = columnWidths,
                )
            }
        }
    }
}

/** 表格的一行：每列之间一道竖线；列宽按 `columnWidths` 定死（null 时回落到旧均分逻辑）。 */
@Composable
private fun TableRow(
    cells: List<String>,
    isHeader: Boolean,
    showBottomDivider: Boolean,
    columnWidths: List<Float>? = null,
) {
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
                val fixedWidth = columnWidths?.getOrNull(columnIndex)
                when {
                    // 两列表的第一列是字段名：窄列、最多两行、超出省略（Whaleness 定稿布局，勿动）。
                    //
                    // #13：**必须剥掉行内标记**。这一列当初直接 Text(cell)，于是 `**Gitee**（主站）`
                    // 把星号原样画出来 —— 同一张表第二列走 TableCellValue → Markdown，粗体正常，
                    // 两列行为不一致。这里不做完整 Markdown 渲染（那会把 labelMedium 换成 bodyMedium，
                    // 抹平字段名与值的层级），只剥壳：字段名需要的是「加粗的文字」，不是嵌套排版。
                    fixedWidth == null && columnIndex == 0 && cells.size < 3 -> {
                        Text(
                            stripInlineMarkup(cell),
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
                    }
                    fixedWidth == null -> {
                        // 旧均分逻辑（columnWidths 为 null 的兜底路径）：weight(1f)。
                        // ≥3 列正常情况下已走上面的定宽分支，这里只剩异常兜底（列数对不上）。
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(
                                    start = if (columnIndex == 0) 10.dp else 0.dp,
                                    end = 10.dp,
                                ),
                        ) {
                            TableCellValue(cell, isHeader)
                        }
                    }
                    else -> {
                        // #9 内容感知定宽列：width 而非 weight —— 短内容列窄、长内容列宽，
                        // 这是可读性的全部来源（对照鸿蒙 `width()` 定死的列）。
                        Box(
                            Modifier
                                .width(fixedWidth.dp)
                                .padding(
                                    start = if (columnIndex == 0) 10.dp else 0.dp,
                                    end = 10.dp,
                                ),
                        ) {
                            TableCellValue(cell, isHeader)
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

/**
 * 单元格内容：能安全走行内 Markdown 就渲染 Markdown（`2026-10-13-07-39-52/` 这种
 * 带反引号的路径原先**原样显示反引号**，v2.2.44 真机验收 W2 —— 表格是自绘的，
 * 正文里那套 GFM 规则必须在这里重新接上）；含换行、表格、列表、围栏代码块的
 * 单元格仍走纯文本 —— 自绘布局里嵌一个块级结构只会错位。
 *
 * 纯文本的颜色区分表头（onSurface）与数据（onSurfaceVariant），与两列字段表一致；
 * Markdown 分支的颜色由排版主题决定（现状如此，表头通常是纯文本走不到这条）。
 */
@Composable
private fun TableCellValue(cell: String, isHeader: Boolean) {
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
