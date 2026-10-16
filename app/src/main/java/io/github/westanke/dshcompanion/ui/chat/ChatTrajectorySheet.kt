package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.TrajectoryNode
import io.github.westanke.dshcompanion.data.TrajectoryNodeKind
import io.github.westanke.dshcompanion.data.TrajectoryTurn
import io.github.westanke.dshcompanion.data.UNKNOWN_TURN
import io.github.westanke.dshcompanion.data.shortDuration
import io.github.westanke.dshcompanion.data.trajectoryOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 轨迹页：把历史按**轮次**折成时间轴，点节点展开它的**原始载荷**。
 *
 * ## 它与会话流的区别（决定了这里的一切）
 *
 * 会话流是**给人读的**：只留正文与工具摘要，其余折叠。轨迹是**给人查的**：
 * 「这一步为什么慢」「它到底调了什么」看的是这个页面。所以这里
 * **不过滤任何事件**（不认得的类型也在，见 `data/Trajectory.kt`），
 * 而且每个节点都能展开看**当时真正收到的 JSON** —— 只给解析结果等于把
 * 「我们不认识的字段」永久藏起来，而那正是排查时最需要看的。
 *
 * ## 为什么点开才渲染
 *
 * 一段原始 JSON 可能有几百行。默认全部展开会让 sheet 变成一堵墙，
 * 也会让每次重组都去高亮所有节点（`ChatCodeBlock` 里有按内容的缓存，
 * 但没必要为看不见的内容付这份钱）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTrajectorySheet(state: HarnessState, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 与其它面板同一个判据：`state.history` 是整份替换的对象，引用相等即「没变」。
    val turns = remember(state.history) { trajectoryOf(state.history) }
    var expandedSeq by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trajectory), style = MaterialTheme.typography.titleLarge)
            if (turns.isEmpty()) {
                Text(
                    stringResource(R.string.trajectory_empty),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    turns.forEach { turn ->
                        item(key = "turn-${turn.turn}") { TurnHeader(turn) }
                        items(turn.nodes, key = { "node-${it.seq}" }) { node ->
                            TrajectoryNodeRow(
                                node = node,
                                expanded = expandedSeq == node.seq,
                                onToggle = {
                                    expandedSeq = if (expandedSeq == node.seq) null else node.seq
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnHeader(turn: TrajectoryTurn) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (turn.turn == UNKNOWN_TURN) {
                stringResource(R.string.trajectory_unknown_turn)
            } else {
                stringResource(R.string.trajectory_turn, turn.turn)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        if (turn.toolCalls > 0) {
            Text(
                stringResource(R.string.trajectory_tools, turn.toolCalls),
                modifier = Modifier.padding(end = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // 耗时可能为 null（时间戳乱序或缺失）—— 那时**什么都不显示**，
        // 而不是显示一个编出来的数字（理由见 TrajectoryTurn.durationMillis）。
        turn.durationMillis?.let { duration ->
            Text(
                shortDuration(duration),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TrajectoryNodeRow(node: TrajectoryNode, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${node.seq}",
                modifier = Modifier.width(52.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                nodeTypeLabel(node.kind),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            node.label?.let { label ->
                Text(
                    label,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (expanded) {
            val raw = node.raw
            if (raw == null) {
                Text(
                    stringResource(R.string.trajectory_no_payload),
                    modifier = Modifier.padding(top = 4.dp, start = 52.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                ChatCodeBlock(
                    code = prettyJson(raw),
                    language = "json",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 52.dp),
                )
            }
        }
    }
}

/** 缩进过的 JSON：紧凑的一行在详情里没法读。 */
private val prettyJsonFormat = Json { prettyPrint = true }

private fun prettyJson(raw: JsonObject): String =
    runCatching { prettyJsonFormat.encodeToString(JsonObject.serializer(), raw) }
        .getOrElse { raw.toString() }

@Composable
private fun nodeTypeLabel(kind: TrajectoryNodeKind): String = stringResource(
    when (kind) {
        TrajectoryNodeKind.User -> R.string.trajectory_user
        TrajectoryNodeKind.Assistant -> R.string.trajectory_assistant
        TrajectoryNodeKind.Tool -> R.string.trajectory_tool
        TrajectoryNodeKind.Deliverable -> R.string.trajectory_deliverable
        TrajectoryNodeKind.TurnEnd -> R.string.trajectory_turn_end
        TrajectoryNodeKind.Other -> R.string.trajectory_other
    },
)
