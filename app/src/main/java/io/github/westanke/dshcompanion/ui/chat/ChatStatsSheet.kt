package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.GoalAction
import io.github.westanke.dshcompanion.GoalFailure
import io.github.westanke.dshcompanion.GoalFailureKind
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.GoalInfo
import io.github.westanke.dshcompanion.data.formatContextWindow
import io.github.westanke.dshcompanion.data.formatDuration
import io.github.westanke.dshcompanion.data.formatPercent
import io.github.westanke.dshcompanion.data.sessionAnalyticsOf
import io.github.westanke.dshcompanion.data.sessionStatsOf
import io.github.westanke.dshcompanion.dismissGoalFailure
import io.github.westanke.dshcompanion.goalUnsupportedForHost
import io.github.westanke.dshcompanion.reportGoalUnsupported
import io.github.westanke.dshcompanion.requestGoalClear
import io.github.westanke.dshcompanion.requestGoalEdit
import io.github.westanke.dshcompanion.requestGoalPause
import io.github.westanke.dshcompanion.requestGoalResume
import io.github.westanke.dshcompanion.retryGoalAction

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
 *
 * ## 目标区：只读展示 → 可操作（WS 改造 P4）
 *
 * 目标区原来只有 objective / phase / rev / 轮次上限四行字。P4 起它是 App 里**唯一**
 * 能写内核状态的地方，而写操作只有 `/ws/mobile` 给（桥的 REST 路由表里 `goal` 零命中），
 * 所以这里必须自己处理三种「写不了」：
 *
 * 1. 内核没有 goals 服务 → 弹「这个内核不支持目标管理」，并且**之后不再发帧**；
 * 2. WS 通道没就绪 → 弹「需要 WebSocket 通道」，通道恢复后可重试；
 * 3. revision 过期 → 重新读回最新 revision，**问用户**要不要按最新版本再试一次。
 *
 * 按钮**始终显示**（不藏、不灰）：灰按钮在真机上等于「点了没反应」，
 * 本项目已经踩过这个坑（见 `ui/chat/SpeechInput.kt` 里 `enabled` 那段注释）。宁可多弹一次窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatStatsSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 与 ChatDetail 的投影缓存同一个判据：`state.history` 是整份替换的对象，
    // 引用相等就说明没变，不必重算一遍。
    val stats = remember(state.history) { sessionStatsOf(state.history) }
    val analytics = remember(state.history) { sessionAnalyticsOf(state.history) }
    val goal = analytics.goal
    val goalAction = state.goalAction
    val pending = goalAction.pending != null
    // 「这个内核不支持」是按**主机**记的（见 `goalUnsupportedForHost`），所以不能只看
    // `state.goalAction.unsupportedHost` 非空 —— 用户可能已经切到另一台支持的内核上了。
    // 这里读 ViewModel 的活判据；连接一变 `state` 就变，重组时自然重算。
    val blocked = viewModel.goalUnsupportedForHost()
    var editing by remember(goal?.id) { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }

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

            goal?.let { info ->
                StatsSection(stringResource(R.string.stats_section_goal))
                Text(
                    info.objective,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val meta = buildList {
                    info.phase?.let { add("${stringResource(R.string.stats_goal_phase)} $it") }
                    info.revision?.let { add("rev $it") }
                    info.maxGoalRounds?.let { add("${stringResource(R.string.stats_goal_rounds)} $it") }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 新按钮加在既有只读文本**之后**，不动上面的三行（真机验收过的 T-01/T-02 展示）。
                GoalActionRow(
                    info = info,
                    pending = pending,
                    onPause = {
                        if (blocked) viewModel.reportGoalUnsupported() else viewModel.requestGoalPause()
                    },
                    onResume = {
                        if (blocked) viewModel.reportGoalUnsupported() else viewModel.requestGoalResume()
                    },
                    onEdit = {
                        if (blocked) viewModel.reportGoalUnsupported() else editing = true
                    },
                    onClear = {
                        if (blocked) viewModel.reportGoalUnsupported() else confirmingClear = true
                    },
                )
                if (blocked) {
                    // 原因写在按钮下面，不靠弹窗也能看见；弹窗只是让"点了"有反馈。
                    Text(
                        stringResource(R.string.goal_unsupported_inline),
                        modifier = Modifier.padding(top = 2.dp),
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

        // 三个对话框挂在面板内容之后、同一个 ModalBottomSheet 里：它们由**状态**驱动
        // （编辑中 / 待确认清除 / 有失败要说明），不依赖 goal 是否还在 —— 清除成功后
        // goal 就没了，失败弹窗却不能跟着消失。
        if (editing && goal != null) {
            GoalEditDialog(
                goal = goal,
                onDismiss = { editing = false },
                onSubmit = { objective, rounds ->
                    editing = false
                    viewModel.requestGoalEdit(objective, rounds)
                },
            )
        }
        if (confirmingClear && goal != null) {
            AlertDialog(
                onDismissRequest = { confirmingClear = false },
                title = { Text(stringResource(R.string.goal_clear_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.goal_clear_body,
                            goal.objective.take(120),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingClear = false
                            viewModel.requestGoalClear()
                        },
                    ) { Text(stringResource(R.string.goal_clear_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingClear = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        goalAction.failure?.let { failure ->
            GoalFailureDialog(
                failure = failure,
                revision = goal?.revision,
                onDismiss = { viewModel.dismissGoalFailure() },
                onRetry = { viewModel.retryGoalAction() },
            )
        }
    }
}

/**
 * 目标区的四个按钮。
 *
 * 按 `phase` 决定给哪几个（与桥/内核的语义一致）：
 * - `active` → 暂停 · 编辑 · 清除
 * - `paused` → 恢复 · 编辑 · 清除
 * - **缺 `phase`**（老内核的投影不带这个字段）→ 按 `active` 给暂停：目标存在时最常见的状态就是
 *   active，而暂停/恢复都带 `ref` 的 compare-and-set 保护 —— 真已经是暂停态就别扭一点、内核会拒，
 *   我们把原因弹出来；这比「一个能点的都没有」有用得多。
 * - 其它认不出的值 → 只给编辑 · 清除（明确是别的状态，就别猜该暂停还是该恢复）。
 *
 * 编辑与清除排在后面：它们是**改内容**和**毁内容**，比暂停更"重"，不该抢在第一位。
 */
@Composable
private fun GoalActionRow(
    info: GoalInfo,
    pending: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    val phase = info.phase?.lowercase()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 14.dp, end = 4.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        if (phase == "paused") {
            TextButton(onClick = onResume, enabled = !pending) {
                Text(stringResource(R.string.goal_action_resume))
            }
        } else if (phase == "active" || phase == null) {
            TextButton(onClick = onPause, enabled = !pending) {
                Text(stringResource(R.string.goal_action_pause))
            }
        }
        TextButton(onClick = onEdit, enabled = !pending) {
            Text(stringResource(R.string.goal_action_edit))
        }
        TextButton(onClick = onClear, enabled = !pending) {
            Text(stringResource(R.string.goal_action_clear))
        }
    }
}

/**
 * 编辑目标：正文 + 轮次上限。
 *
 * 两个输入框各自的"空"含义不同，这一点必须写清楚，否则用户清空正文会以为能清掉目标：
 * - 正文留空 = **不改**（桥侧 `trimmedString` 同样把空白当没给）；
 * - 轮次上限留空 = **不改**；
 * - 两样都没改 → 本地就拦下来，不发帧（发过去桥也会回 bad-request，白跑一趟）。
 */
@Composable
private fun GoalEditDialog(
    goal: GoalInfo,
    onDismiss: () -> Unit,
    onSubmit: (objective: String?, maxGoalRounds: Int?) -> Unit,
) {
    var objective by remember(goal.id) { mutableStateOf(goal.objective) }
    var rounds by remember(goal.id) { mutableStateOf(goal.maxGoalRounds?.toString().orEmpty()) }
    var formError by remember(goal.id) { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = objective.trim()
        val newObjective = trimmed.takeIf { it.isNotEmpty() && it != goal.objective }
        val roundsText = rounds.trim()
        val newRounds = if (roundsText.isEmpty()) null else roundsText.toIntOrNull()
        if (roundsText.isNotEmpty() && (newRounds == null || newRounds <= 0)) {
            formError = R.string.goal_edit_rounds_invalid
            return
        }
        val roundsChanged = newRounds != null && newRounds != goal.maxGoalRounds
        if (newObjective == null && !roundsChanged) {
            formError = R.string.goal_edit_need_change
            return
        }
        onSubmit(newObjective, if (roundsChanged) newRounds else null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = objective,
                    onValueChange = {
                        objective = it
                        formError = null
                    },
                    label = { Text(stringResource(R.string.goal_edit_objective)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rounds,
                    onValueChange = {
                        rounds = it.filter(Char::isDigit)
                        formError = null
                    },
                    label = { Text(stringResource(R.string.goal_edit_rounds)) },
                    supportingText = { Text(stringResource(R.string.goal_edit_rounds_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                formError?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 失败说明弹窗。四种「写不了」给四种正文，原因永远比结果重要。 */
@Composable
private fun GoalFailureDialog(
    failure: GoalFailure,
    revision: Int?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val title = when (failure.kind) {
        GoalFailureKind.UNSUPPORTED -> R.string.goal_failure_unsupported_title
        GoalFailureKind.CHANNEL_NOT_READY -> R.string.goal_failure_channel_title
        GoalFailureKind.REVISION_STALE -> R.string.goal_failure_stale_title
        GoalFailureKind.REF_MISSING -> R.string.goal_failure_ref_title
        GoalFailureKind.OTHER -> R.string.goal_failure_other_title
    }
    val body = when (failure.kind) {
        GoalFailureKind.UNSUPPORTED -> stringResource(R.string.goal_failure_unsupported_body)
        GoalFailureKind.CHANNEL_NOT_READY -> stringResource(R.string.goal_failure_channel_body)
        GoalFailureKind.REVISION_STALE -> stringResource(
            R.string.goal_failure_stale_body,
            revision?.toString() ?: "?",
        )
        GoalFailureKind.REF_MISSING -> stringResource(R.string.goal_failure_ref_body)
        // 其余错误把服务端原话带上：本端翻译不了的码，原文比笼统一句话有用得多。
        GoalFailureKind.OTHER -> failure.detail
            ?: stringResource(R.string.goal_failure_other_body)
    }
    val retry = failure.retry != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = {
            if (retry) {
                TextButton(onClick = onRetry) {
                    Text(
                        stringResource(
                            if (failure.kind == GoalFailureKind.REVISION_STALE) {
                                R.string.goal_failure_stale_retry
                            } else {
                                R.string.retry
                            },
                        ),
                    )
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (retry) {
            {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            null
        },
    )
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
