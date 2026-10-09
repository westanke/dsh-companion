package io.github.hakunm.deepseekharness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hakunm.deepseekharness.data.GenUiListItem
import io.github.hakunm.deepseekharness.data.GenUiNode
import io.github.hakunm.deepseekharness.data.GenUiSpec
import io.github.hakunm.deepseekharness.data.bool
import io.github.hakunm.deepseekharness.data.children
import io.github.hakunm.deepseekharness.data.inlineText
import io.github.hakunm.deepseekharness.data.inlineMarkup
import io.github.hakunm.deepseekharness.data.int
import io.github.hakunm.deepseekharness.data.label
import io.github.hakunm.deepseekharness.data.listItems
import io.github.hakunm.deepseekharness.data.number
import io.github.hakunm.deepseekharness.data.str
import io.github.hakunm.deepseekharness.data.title

/**
 * 渲染一份 dsh-ui spec。
 *
 * 设计取舍，按重要性排序：
 *
 * 1. **未知组件整条丢弃，不留半截**。词表有 40 多种，手机上全实现不现实，也没必要。
 *    这里只画 [io.github.hakunm.deepseekharness.data.SUPPORTED] 认得的那批，
 *    认不得的直接跳过 —— 与网页版「坏组件被丢弃、其余照常」一致。
 *
 * 2. **交互组件真的能点，且点了会回话**。[GenUiRadioGroup] 把选择记在本地，
 *    [GenUiSubmit] 点下去把答案作为一条普通用户消息发进会话。这是整个功能的意义：
 *    网页版弹出的问题，手机上也该能答。
 *
 * 3. **卡片只给并排项和数据对象用**，单段文字不加 card —— 否则满屏都是圆角矩形，
 *    比网页版更像模板。骨架用 [Surface] 微微抬升即可。
 */
@Composable
fun GenUiCard(
    spec: GenUiSpec,
    modifier: Modifier = Modifier,
    onAnswer: (String) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        spec.title?.let { title ->
            Text(
                inlineMarkup(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spec.gap.dp)) {
            spec.items.forEach { GenUiNodeView(it, onAnswer) }
        }
    }
}

@Composable
private fun GenUiNodeView(node: GenUiNode, onAnswer: (String) -> Unit) {
    when (node.type) {
        "text" -> Text(
            node.inlineText(),
            style = when (node.str("size")) {
                "h1" -> MaterialTheme.typography.headlineSmall
                "h2" -> MaterialTheme.typography.titleLarge
                "h3" -> MaterialTheme.typography.titleMedium
                "muted" -> MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                "caption" -> MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> MaterialTheme.typography.bodyMedium
            }.copy(lineHeight = 26.sp),
            modifier = Modifier.fillMaxWidth(),
        )

        "list" -> GenUiList(node)
        "keyvalue" -> GenUiKeyValue(node)
        "table" -> GenUiTable(node)
        "callout" -> GenUiCallout(node)
        "steps" -> GenUiSteps(node)
        "stat" -> GenUiStat(node)
        "badge" -> GenUiBadge(node)
        "progress" -> GenUiProgress(node)
        "chart" -> GenUiChart(node)
        "code" -> GenUiCode(node, node.str("lang"))
        "json" -> GenUiCode(node, "json")
        "diff" -> GenUiDiff(node)
        "divider" -> Divider(color = MaterialTheme.colorScheme.outlineVariant)
        "spacer" -> Spacer(Modifier.height(8.dp))
        "card" -> GenUiCard(
            spec = GenUiSpec(title = node.title(), gap = node.int("gap") ?: 8, items = node.children()),
            onAnswer = onAnswer,
        )

        "row", "col", "grid" -> GenUiLayout(node, onAnswer)
        "button" -> GenUiButton(node, onAnswer)
        "radio", "submit" -> GenUiForm(node, onAnswer)
        // 认得的类型之外全部跳过：画一个空壳比不画更让人以为界面坏了。
    }
}

/** `card` / `row` / `col` / `grid` 的共同外皮：Surface 抬升 + 内边距。 */
@Composable
private fun GenUiLayout(node: GenUiNode, onAnswer: (String) -> Unit) {
    val children = node.children()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (node.type) {
            "row" -> Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { children.forEach { GenUiNodeView(it, onAnswer) } }

            "col" -> Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { children.forEach { GenUiNodeView(it, onAnswer) } }

            "grid" -> Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { children.forEach { GenUiNodeView(it, onAnswer) } }

            else -> Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { children.forEach { GenUiNodeView(it, onAnswer) } }
        }
    }
}

@Composable
private fun GenUiList(node: GenUiNode) {
    val items = node.listItems()
    if (items.isEmpty()) return
    node.title()?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { GenUiListRow(it) }
    }
}

@Composable
private fun GenUiListRow(item: GenUiListItem) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            item.desc?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenUiKeyValue(node: GenUiNode) {
    val pairs = node.value["pairs"] as? kotlinx.serialization.json.JsonArray ?: return
    val rows = pairs.mapNotNull { entry ->
        val value = entry as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val key = value.str("key") ?: return@mapNotNull null
        key to (value.str("value").orEmpty())
    }
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenUiTable(node: GenUiNode) {
    val columns = (node.value["columns"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .orEmpty()
    if (columns.isEmpty()) return
    val rows = (node.value["rows"] as? kotlinx.serialization.json.JsonArray).orEmpty()
    node.title()?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
    }
    // 横向滚动是刻意的：手机屏宽放不下宽表，压缩列宽只会让每格都读不了。
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            columns.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(140.dp).padding(8.dp),
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { row ->
            val cells = (row as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            Row {
                columns.indices.forEach { index ->
                    Text(
                        cells.getOrElse(index) { "" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(140.dp).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GenUiCallout(node: GenUiNode) {
    val (bg, fg) = when (node.str("tone")) {
        "warning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "error" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "success" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            node.title()?.let {
                Text(
                    inlineMarkup(it),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
            Text(
                node.inlineText(),
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                modifier = Modifier.padding(top = if (node.title() != null) 4.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun GenUiSteps(node: GenUiNode) {
    val steps = node.value["steps"] as? kotlinx.serialization.json.JsonArray ?: return
    val current = node.int("current") ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, entry ->
            val step = entry as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            if (index < current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index < current) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        step.str("title").orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    step.str("desc")?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenUiStat(node: GenUiNode) {
    Column {
        node.str("label")?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                node.str("value").orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            node.str("delta")?.let { delta ->
                val positive = !delta.startsWith("-")
                Text(
                    delta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (positive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun GenUiBadge(node: GenUiNode) {
    val (bg, fg) = when (node.str("tone")) {
        "success" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "warn", "warning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "danger", "error" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(shape = RoundedCornerShape(20.dp), color = bg) {
        Text(
            listOfNotNull(node.str("icon"), node.str("label")).joinToString(" "),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GenUiProgress(node: GenUiNode) {
    val value = node.number("value") ?: return
    Column {
        node.str("label")?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { (value / 100.0).coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * 轻量图表。
 *
 * 只画 bars / line / donut 三种，用 Canvas 手绘而不是引入 ECharts：
 * 一个 1MB 的 JS 引擎换不来手机上更好的体验，而手机屏上真正要读的是「谁的柱子高」。
 * 渲染不了的 kind 返回空 —— 一个画不出来的空白框比没有图更糟。
 */
@Composable
private fun GenUiChart(node: GenUiNode) {
    val kind = node.str("kind") ?: "bars"
    val data = (node.value["data"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { entry ->
        val value = entry as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val label = value.str("label").orEmpty()
        val number = value.str("value")?.toDoubleOrNull() ?: return@mapNotNull null
        label to number
    }
    if (data.isEmpty()) return
    node.title()?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
    }
    when (kind) {
        "donut" -> GenUiDonut(data)
        "line" -> GenUiBars(data, line = true)
        else -> GenUiBars(data, line = false)
    }
}

@Composable
private fun GenUiBars(data: List<Pair<String, Double>>, line: Boolean) {
    val max = data.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        data.forEach { (label, value) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        formatNumber(value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (value / max).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun GenUiDonut(data: List<Pair<String, Double>>) {
    val total = data.sumOf { it.second }.takeIf { it > 0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        data.forEach { (label, value) ->
            val share = (value / total * 100).toInt()
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    "$share%  ·  ${formatNumber(value)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

@Composable
private fun GenUiCode(node: GenUiNode, lang: String?) {
    val code = node.str("code") ?: node.inlineText()
    if (code.isBlank()) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            lang?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun GenUiDiff(node: GenUiNode) {
    val diffs = node.value["diffs"] as? kotlinx.serialization.json.JsonArray ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        diffs.forEach { entry ->
            val diff = entry as? kotlinx.serialization.json.JsonObject ?: return@forEach
            Column {
                diff.str("path")?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
                diff.str("oldText")?.takeIf(String::isNotBlank)?.let {
                    Text(
                        "− $it",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                diff.str("newText")?.takeIf(String::isNotBlank)?.let {
                    Text(
                        "+ $it",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenUiButton(node: GenUiNode, onAnswer: (String) -> Unit) {
    val label = node.label() ?: return
    val action = node.str("action")
    // 网页版「不带 action 的按钮是禁用态」：照搬那条规则，否则用户点了没反应，
    // 只能理解成 App 坏了。App 端所有按钮都直接回话，不存在网页那种本地判分的中间态。
    if (action == null) {
        OutlinedButton(enabled = false, onClick = {}) { Text(label) }
        return
    }
    OutlinedButton(onClick = { onAnswer(label) }) { Text(label) }
}

/**
 * radio / submit 的本地状态。
 *
 * 答案存在组件里而不是会话里：会话里放的是「用户选了 v2.0.0」这条消息本身，
 * 由 [onAnswer] 发出。选择状态随消息一起重建，符合「刷新后回到没选」的直觉。
 */
@Composable
private fun GenUiForm(node: GenUiNode, onAnswer: (String) -> Unit) {
    val options = node.listItems()
    if (options.isEmpty()) return
    var selected by remember { mutableStateOf(options.indexOfFirst { it.desc == node.str("selected") }) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = index }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == index, onClick = { selected = index })
                Text(option.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val prompt = node.label() ?: "确认"
        Button(
            onClick = {
                val index = selected
                onAnswer(if (index >= 0) options[index].title else prompt)
            },
            enabled = selected >= 0,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(prompt) }
    }
}