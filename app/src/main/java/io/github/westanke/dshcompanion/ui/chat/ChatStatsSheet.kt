package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import io.github.westanke.dshcompanion.data.formatContextWindow
import io.github.westanke.dshcompanion.data.formatDuration
import io.github.westanke.dshcompanion.data.formatPercent
import io.github.westanke.dshcompanion.data.sessionAnalyticsOf
import io.github.westanke.dshcompanion.data.sessionStatsOf

/**
 * 会话统计面板。
 *
 * ## 两块数据，两种来源
 *
 * | 分节 | 来源 | 口径 |
 * |---|---|---|
 * | 执行（轮次/助手消息/工具调用/事件数） | [sessionStatsOf] 数历史事件 | **客户端**视角，逐条数出来的 |
 * | 目标 / 内核统计 / Token 用量 / 上下文 / 智能体团队 | [sessionAnalyticsOf] 读会话投影 | **内核**视角，内核自己算的 |
 *
 * 两套「轮次」会同时出现（一个客户端数的、一个内核报的），刻意都保留：两者不相等本身就
 * 是信息（说明事件流有丢失、或内核把注入消息也算进去了）。合并成一个数字反而把差异藏掉。
 *
 * ## 拿不到时明说，不给估算
 *
 * 投影里没有 `tokenUsage` / `contextPressure` 时仍显示原来的说明文案：一个不可靠的
 * 数字比没有数字更坏 —— 用户会拿它当真实占用做决策。详细理由见 `data/SessionStats.kt`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatStatsSheet(state: HarnessState, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 与 ChatDetail 的投影缓存同一个判据：`state.history` 是整份替换的对象，
    // 引用相等就说明没变，不必重算一遍。
    val stats = remember(state.history) { sessionStatsOf(state.history) }
    val analytics = remember(state.history) { sessionAnalyticsOf(state.history) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.session_stats), style = MaterialTheme.typography.titleLarge)
            if (stats.isEmpty) {
                Text(
                    stringResource(R.string.stats_empty),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                StatsSection(stringResource(R.string.stats_section_execution))
                StatsRow(stringResource(R.string.stats_turns), stats.turns.toString())
                StatsRow(stringResource(R.string.stats_assistant), stats.assistantMessages.toString())
                StatsRow(stringResource(R.string.stats_tools), stats.toolCalls.toString())
                StatsRow(stringResource(R.string.stats_events), stats.events.toString())
                stats.lastSeq?.let { seq ->
                    StatsRow(stringResource(R.string.stats_through), "#$seq")
                }
            }

            analytics.goal?.let { goal ->
                StatsSection(stringResource(R.string.stats_section_goal))
                Text(
                    goal.objective,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val meta = buildList {
                    goal.phase?.let { add("${stringResource(R.string.stats_goal_phase)} $it") }
                    goal.revision?.let { add("rev $it") }
                    goal.maxGoalRounds?.let { add("${stringResource(R.string.stats_goal_rounds)} $it") }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            analytics.kernelStats?.let { kernel ->
                StatsSection(stringResource(R.string.stats_section_kernel))
                kernel.turns?.let { StatsRow(stringResource(R.string.stats_turns), it.toString()) }
                kernel.steps?.let { StatsRow(stringResource(R.string.stats_kernel_steps), it.toString()) }
                kernel.llmMs?.let {
                    StatsRow(stringResource(R.string.stats_kernel_llm), formatDuration(it))
                }
                kernel.decodeTokens?.let {
                    StatsRow(stringResource(R.string.stats_kernel_decode), it.toString())
                }
            }

            analytics.tokenUsage?.let { usage ->
                StatsSection(stringResource(R.string.stats_section_usage))
                StatsRow(stringResource(R.string.stats_usage_input), usage.inputTokens.toString())
                StatsRow(stringResource(R.string.stats_usage_output), usage.outputTokens.toString())
                StatsRow(stringResource(R.string.stats_usage_cache_read), usage.cacheReadTokens.toString())
                StatsRow(stringResource(R.string.stats_usage_cache_write), usage.cacheWriteTokens.toString())
                StatsRow(stringResource(R.string.stats_usage_reasoning), usage.reasoningTokens.toString())
                usage.cacheHitRate?.let {
                    StatsRow(stringResource(R.string.stats_usage_hit), formatPercent(it))
                }
            }

            analytics.contextPressure?.let { pressure ->
                StatsSection(stringResource(R.string.stats_section_context))
                val used = pressure.pressureTokens
                val window = pressure.contextWindow
                if (used != null && window != null && window > 0) {
                    StatsRow(
                        stringResource(R.string.stats_context_pressure),
                        "${formatContextWindow(used.toLong())} / ${formatContextWindow(window.toLong())}",
                    )
                    pressure.fraction?.let {
                        StatsRow(stringResource(R.string.stats_context_percent), formatPercent(it))
                    }
                } else {
                    used?.let {
                        StatsRow(stringResource(R.string.stats_context_pressure), formatContextWindow(it.toLong()))
                    }
                    window?.let {
                        StatsRow(stringResource(R.string.stats_context_window), formatContextWindow(it.toLong()))
                    }
                }
                pressure.surfaceTokens?.let {
                    StatsRow(stringResource(R.string.stats_context_surface), formatContextWindow(it.toLong()))
                }
            }

            if (analytics.agentTeam != null || analytics.subagentCount != null) {
                StatsSection(stringResource(R.string.stats_section_team))
                analytics.agentTeam?.let { team ->
                    StatsRow(stringResource(R.string.stats_team_members), team.memberCount.toString())
                    StatsRow(stringResource(R.string.stats_team_tasks), team.taskCount.toString())
                }
                analytics.subagentCount?.let {
                    StatsRow(stringResource(R.string.stats_subagents), it.toString())
                }
            }

            if (!analytics.hasUsage) {
                Text(
                    stringResource(R.string.stats_usage_unavailable),
                    modifier = Modifier.padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatsSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 2.dp))
    Text(
        title,
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
