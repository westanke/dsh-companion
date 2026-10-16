package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.sessionStatsOf

/**
 * 会话统计面板（**执行维度**：轮次 / 助手消息 / 工具调用 / 事件数 / 数据截至）。
 *
 * ## 为什么没有 Token 用量与上下文压力
 *
 * 那两块需要内核给出 usage（输入 / 输出 / 缓存读写 / 推理）。我方 bridge 原样透传内核事件，
 * 而「历史事件里有没有 usage」**从本仓库不可证**（`node_modules` 下没有内核包）。
 *
 * 所以这里**明说拿不到**，而不是留白 —— 留白会让用户以为我们漏做了；也不给"按字符数估算"
 * 的数字 —— 估算误差可能成倍，而用户会拿它当真实占用做决策。一个不可靠的数字比没有更坏。
 * 详细理由见 `data/SessionStats.kt`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatStatsSheet(state: HarnessState, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 与 ChatDetail 的投影缓存同一个判据：`state.history` 是整份替换的对象，
    // 引用相等就说明没变，不必重数一遍。
    val stats = remember(state.history) { sessionStatsOf(state.history) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.session_stats), style = MaterialTheme.typography.titleLarge)
            if (stats.isEmpty) {
                Text(
                    stringResource(R.string.stats_empty),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                StatsRow(stringResource(R.string.stats_turns), stats.turns.toString())
                StatsRow(stringResource(R.string.stats_assistant), stats.assistantMessages.toString())
                StatsRow(stringResource(R.string.stats_tools), stats.toolCalls.toString())
                StatsRow(stringResource(R.string.stats_events), stats.events.toString())
                stats.lastSeq?.let { seq ->
                    StatsRow(stringResource(R.string.stats_through), "#$seq")
                }
            }
            Text(
                stringResource(R.string.stats_usage_unavailable),
                modifier = Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
