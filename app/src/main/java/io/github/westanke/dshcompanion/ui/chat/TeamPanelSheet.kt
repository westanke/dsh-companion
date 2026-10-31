package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.closeSubagent
import io.github.westanke.dshcompanion.openSubagent
import io.github.westanke.dshcompanion.sendMessage
import io.github.westanke.dshcompanion.data.AgentTeamProjection
import io.github.westanke.dshcompanion.data.SubagentRow
import io.github.westanke.dshcompanion.data.TeamMemberRow
import io.github.westanke.dshcompanion.data.TeamTaskRow
import io.github.westanke.dshcompanion.data.agentTeamProjectionOf

/**
 * 智能团队**只读**面板（会话 ⋯ 菜单 →「智能团队」一级直达）。
 *
 * ## 为什么只读
 *
 * 对齐官方 Web 端 `dsh-experimental-client-ui-agent-team`：团队的组建、派任务、私信
 * 都是 Lead agent 通过它自己持有的模型工具（spawn_teammate / send_message /
 * team_task_*）完成的，人类在任何一端都不直接写团队状态。手机端同样只做监工窗口 ——
 * 名册、任务板、子会话目录全部来自会话投影 `agentTeam` / `subagentCatalog`
 * （见 [AgentTeamProjection]）。
 *
 * ## 唯一的「管理」入口：自然语言指挥
 *
 * 底部三枚 chips（新成员 / 派任务 / 问进度）只是把一句话填进对话框，用户确认后当作
 * **普通用户消息**发给当前 Lead 会话；Lead 收到后自己决定调哪个团队工具。
 * 不走任何特殊协议，所以老桥、未启用团队插件的内核也不会报错（只是没人接话）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPanelSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 投影随 history 整份替换；remember 跟住它，流式刷新后下次打开就是新数据。
    val team = remember(state.history) { agentTeamProjectionOf(state.history) }
    var draft by remember { mutableStateOf<String?>(null) }
    val canWrite = "chat.write" in state.device?.scopes.orEmpty()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Groups,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.team_panel_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                stringResource(R.string.team_readonly_hint),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            if (team == null) {
                Text(
                    stringResource(R.string.team_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 460.dp),
                ) {
                    team.failure?.let { reason ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    stringResource(R.string.team_failure, reason),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    TeamSection(stringResource(R.string.team_section_members))
                    if (team.members.isEmpty()) {
                        EmptyHint(stringResource(R.string.team_empty_members))
                    } else {
                        team.members.forEach { MemberCard(it) }
                    }

                    TeamSection(stringResource(R.string.team_section_tasks))
                    if (team.tasks.isEmpty()) {
                        EmptyHint(stringResource(R.string.team_empty_tasks))
                    } else {
                        team.tasks.forEach { TaskCard(it) }
                    }

                    TeamSection(stringResource(R.string.team_section_subagents))
                    if (team.subagents.isEmpty()) {
                        EmptyHint(stringResource(R.string.team_empty_subagents))
                    } else {
                        team.subagents.forEach { row ->
                            SubagentCard(row) { viewModel.openSubagent(row.id, row.label) }
                        }
                    }
                }
            }

            // 自然语言指挥条：唯一的「管理」动作，出口是给 Lead 发普通消息。
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(
                stringResource(R.string.team_command_section),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            CommandChips(enabled = canWrite) { preset -> draft = preset }
        }
    }

    draft?.let { initial ->
        CommandDialog(
            initial = initial,
            onDismiss = { draft = null },
            onConfirm = { text ->
                draft = null
                viewModel.sendMessage(text, steer = false)
                onClose()
            },
        )
    }

    // 点开某个成员：独立窗口盖在面板之上，关掉就回到名册（不连面板一起关）。
    state.subagentView?.let { view ->
        SubagentDialog(view = view, viewModel = viewModel) { viewModel.closeSubagent() }
    }
}

@Composable
private fun TeamSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun MemberCard(member: TeamMemberRow) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                member.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RoleChip(if (member.isLead) R.string.team_role_lead else R.string.team_role_teammate)
            PhaseChip(member.phase)
        }
    }
}

@Composable
private fun TaskCard(task: TeamTaskRow) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.subject,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TaskStatusChip(task.status)
            }
            if (task.description.isNotBlank()) {
                Text(
                    task.description,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            task.ownerName?.let {
                Text(
                    stringResource(R.string.team_task_owner, it),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.blockedBy.isNotEmpty()) {
                Text(
                    stringResource(R.string.team_task_blocked_by, task.blockedBy.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.writeScopes.isNotEmpty()) {
                Text(
                    stringResource(R.string.team_task_scopes, task.writeScopes.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!task.ready) {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp).let { it },
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        stringResource(R.string.team_task_not_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentCard(row: SubagentRow, onClick: () -> Unit) {
    val title = row.label ?: row.id.take(16)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // 整张卡可点：只点文字的话，用户得精准命中那行小字才打得开，
            // 而这张卡在 460dp 面板里本来就只有两行高。
            .clickable(onClickLabel = stringResource(R.string.team_subagent_open), onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.label != null) {
                    Text(
                        row.id,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SubagentModeChip(row.mode)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RoleChip(labelRes: Int) {
    AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(labelRes)) })
}

@Composable
private fun PhaseChip(phase: String) {
    val res = when (phase) {
        "provisioning" -> R.string.team_phase_provisioning
        "failed" -> R.string.team_phase_failed
        else -> R.string.team_phase_active
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(res)) },
        colors = if (phase == "failed") {
            AssistChipDefaults.assistChipColors(
                disabledLabelColor = MaterialTheme.colorScheme.error,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

@Composable
private fun TaskStatusChip(status: String) {
    val res = when (status) {
        "in_progress" -> R.string.team_task_in_progress
        "completed" -> R.string.team_task_completed
        "deleted" -> R.string.team_task_deleted
        else -> R.string.team_task_pending
    }
    AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(res), maxLines = 1) })
}

@Composable
private fun SubagentModeChip(mode: String?) {
    val res = when (mode) {
        "one-shot" -> R.string.team_subagent_mode_one_shot
        "continuable" -> R.string.team_subagent_mode_continuable
        "external" -> R.string.team_subagent_mode_external
        else -> R.string.team_subagent_mode_unknown
    }
    AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(res)) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandChips(enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommandChip(
            labelRes = R.string.team_command_new_member,
            icon = Icons.Outlined.PersonAdd,
            promptRes = R.string.team_command_new_member_prompt,
            enabled = enabled,
            onPick = onPick,
        )
        CommandChip(
            labelRes = R.string.team_command_new_task,
            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            promptRes = R.string.team_command_new_task_prompt,
            enabled = enabled,
            onPick = onPick,
        )
        CommandChip(
            labelRes = R.string.team_command_progress,
            icon = Icons.Outlined.QueryStats,
            promptRes = R.string.team_command_progress_prompt,
            enabled = enabled,
            onPick = onPick,
        )
    }
}

@Composable
private fun CommandChip(
    labelRes: Int,
    icon: ImageVector,
    promptRes: Int,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    // 点击时把该意图对应的话术模板送进对话框，由用户补全后再发给 Lead。
    val preset = stringResource(promptRes)
    FilterChip(
        selected = false,
        enabled = enabled,
        onClick = { onPick(preset) },
        label = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    )
}

/**
 * 指挥确认对话框：chip 只负责选定意图，真正发送前让用户补全 / 改写 ——
 * 「新成员」「派任务」这类指令没有具体内容时直接发出去只会让 Lead 反问一遍。
 */
@Composable
private fun CommandDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_command_section)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Text(
                    stringResource(R.string.team_command_edit_hint),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.team_command_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.team_command_cancel))
            }
        },
    )
}
