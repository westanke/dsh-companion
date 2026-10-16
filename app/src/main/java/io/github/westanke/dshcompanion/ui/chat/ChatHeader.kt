package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.cancelRun
import io.github.westanke.dshcompanion.refreshHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.PermissionSelect
import io.github.westanke.dshcompanion.ui.StatusLabel

/**
 * 会话顶栏：标题 / 连接状态 / 文件 / 刷新 / 停止 / ⋯ 菜单。
 *
 * 从 `ChatDetail.kt` 拆出（2026-10-17）：那个容器文件加上「统计」「轨迹」两个面板入口后
 * 越过 800 行预算。顶栏本来就自成一体 —— 它只依赖 `HarnessState` 与几个回调，
 * 与消息流的渲染没有任何耦合。
 */
@Composable
internal fun ConversationHeader(
    session: ChatSession,
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    onOpenFiles: () -> Unit,
    onModelClick: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenTrajectory: () -> Unit,
    permission: PermissionSelect?,
    permissionEnabled: Boolean,
    onPermissionSelect: (String) -> Unit,
    canSteer: Boolean,
    onSteer: () -> Unit,
    wide: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 10.dp else 2.dp)) {
            // 标题在窄屏（手机）永远单行；平板（wide）下允许两行 —— 兜底到长 cwd 路径时
            // 不会在顶栏被腰斩，短标题（用户起的名字）依然只占一行，布局无感。
            Text(
                sessionDisplayTitle(session),
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusLabel(
                if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                state.eventsConnected,
            )
            if (session.pendingInteraction == "approval") ApprovalStatusLabel(Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onOpenFiles) {
            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.session_files))
        }
        IconButton(onClick = viewModel::refreshHistory, enabled = !state.busy) {
            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
        }
        AnimatedVisibility(session.running, enter = fadeIn(), exit = fadeOut()) {
            IconButton(
                onClick = viewModel::cancelRun,
                enabled = "chat.write" in state.device?.scopes.orEmpty(),
            ) {
                Icon(Icons.Outlined.Cancel, stringResource(R.string.cancel_run), tint = MaterialTheme.colorScheme.error)
            }
        }
        // ⋯ 菜单（网页版 chat.html:283 的 #more，位置一样：标题右边、停止键旁边）。
        //
        // 输入区压成**一行药丸**后，模型 / 权限 / 插话这些入口在输入区里没有位置了，
        // 所以整体搬到这里。它们只是**换了容器，不是换了语义** ——
        // 模型仍走既有的 ModelSheet（智能体切换本来就在那个面板里，不重复做），
        // 权限仍是只读 / 工作区写入 / 完整访问三档 + 完整访问二次确认，
        // 插话仍是固定的 steer 提交（不受设置里「繁忙时默认行为」影响）。
        var moreOpen by remember { mutableStateOf(false) }
        var confirmFullAccess by remember { mutableStateOf(false) }
        val currentModel = state.sessionModels?.current
        val agentName = state.agentPresets.find { it.id == session.agentPreset }?.name ?: session.agentPreset
        val modelSummary = listOfNotNull(agentName, currentModel?.provider, currentModel?.model)
            .joinToString(" · ")
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions))
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                // 会话统计（执行维度）。放在菜单最前：它是**查看**类操作，
                // 与下面的「模型 / 权限 / 插话」这些**改变**类操作分开。
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trajectory)) },
                    onClick = {
                        moreOpen = false
                        onOpenTrajectory()
                    },
                    leadingIcon = { Icon(Icons.Outlined.List, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_stats)) },
                    onClick = {
                        moreOpen = false
                        onOpenStats()
                    },
                    leadingIcon = { Icon(Icons.Outlined.Info, null) },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.model))
                            if (modelSummary.isNotBlank()) {
                                Text(
                                    modelSummary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    onClick = {
                        moreOpen = false
                        onModelClick()
                    },
                    enabled = state.sessionModels != null && state.sessionModels.routable,
                    leadingIcon = { Icon(Icons.Outlined.ModelTraining, null) },
                )
                permission?.takeIf { it.options.isNotEmpty() }?.let { permissionValue ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionMenuItems(
                        value = permissionValue,
                        enabled = permissionEnabled,
                        onSelect = { selected ->
                            moreOpen = false
                            onPermissionSelect(selected)
                        },
                        onRequestFullAccess = {
                            moreOpen = false
                            confirmFullAccess = true
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.steer))
                            Text(
                                stringResource(R.string.busy_send_mode_steer_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    onClick = {
                        moreOpen = false
                        onSteer()
                    },
                    enabled = canSteer,
                    leadingIcon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                )
            }
        }
        // 完整访问的二次确认：与原来挂在输入区那个按钮上的对话框是同一套要求 ——
        // 必须勾选 acknowledged，确认键才会亮。
        if (confirmFullAccess) {
            FullAccessConfirmDialog(
                onDismiss = { confirmFullAccess = false },
                onConfirm = {
                    confirmFullAccess = false
                    onPermissionSelect("danger-full-access")
                },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
