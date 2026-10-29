package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.PlayCircle
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
import io.github.westanke.dshcompanion.openSchedulePanel
import io.github.westanke.dshcompanion.refreshHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.PermissionSelect
import io.github.westanke.dshcompanion.data.supportsSchedules
import io.github.westanke.dshcompanion.data.supportsSessionJobs
import io.github.westanke.dshcompanion.ui.ConnectionOfflineColor
import io.github.westanke.dshcompanion.ui.ConnectionOnlineColor
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
    onOpenJobs: () -> Unit,
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
            // 状态行：连接状态（绿/红点）+ 权限档位 Pill 同一行。
            //
            // 权限 Pill 原先贴在**输入区上方**、还 `.fillMaxWidth()` 独占一整条，
            // 用户 2026-10-19 反馈「影响页面可视区域」。挪到这里既省掉那一行，
            // 语义也更顺：它和「已连接」一样，都是**当前会话的处境**。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusLabel(
                    if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                    state.eventsConnected,
                    // 绿=已连接、红=已断开（用户要求：这两态要一眼可辨）。
                    activeColor = ConnectionOnlineColor,
                    inactiveColor = ConnectionOfflineColor,
                )
                PermissionPill(permission, permissionEnabled, onPermissionSelect)
            }
            if (session.pendingInteraction == "approval") ApprovalStatusLabel(Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onOpenFiles) {
            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.session_files))
        }
        // 「刷新」与「停止运行」**已收进 ⋯ 菜单**（用户 2026-10-19 要求：顶栏太挤，
        // 这两个按钮的功能挪到三个点里面去）。
        // 顶栏只留「本会话的文件」（它是这个页面的主入口，值得占一格）。
        // ⋯ 菜单（网页版 chat.html:283 的 #more，位置一样：标题右边、停止键旁边）。
        //
        // 输入区压成**一行药丸**后，模型 / 权限 / 插话这些入口在输入区里没有位置了，
        // 所以整体搬到这里。它们只是**换了容器，不是换了语义** ——
        // 模型仍走既有的 ModelSheet（智能体切换本来就在那个面板里，不重复做），
        // 权限仍是只读 / 工作区写入 / 完整访问三档 + 完整访问二次确认，
        // 插话仍是固定的 steer 提交（不受设置里「繁忙时默认行为」影响）。
        var moreOpen by remember { mutableStateOf(false) }
        var confirmFullAccess by remember { mutableStateOf(false) }
        // 定时任务面板由顶栏自己持有：`ChatDetail.kt` 正好 800 行（预算线），
        // 把「开/关一个 sheet」这种事塞进去等于逼它下次拆文件。
        var scheduleOpen by remember { mutableStateOf(false) }
        val currentModel = state.sessionModels?.current
        val agentName = state.agentPresets.find { it.id == session.agentPreset }?.name ?: session.agentPreset
        val modelSummary = listOfNotNull(agentName, currentModel?.provider, currentModel?.model)
            .joinToString(" · ")
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions))
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                // 从顶栏收进来的两个**动作**（用户 2026-10-19）：刷新与停止运行。
                // 放最前面，与「查看类」（轨迹/统计）分开 —— 前者是立刻生效的动作，
                // 后者是打开一个面板。
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.refresh)) },
                    onClick = {
                        moreOpen = false
                        viewModel.refreshHistory()
                    },
                    enabled = !state.busy,
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cancel_run)) },
                    onClick = {
                        moreOpen = false
                        viewModel.cancelRun()
                    },
                    // 只在真的跑着的时候可点：没事可停时点它只会让人以为坏了。
                    enabled = session.running && "chat.write" in state.device?.scopes.orEmpty(),
                    leadingIcon = {
                        Icon(Icons.Outlined.Cancel, null, tint = MaterialTheme.colorScheme.error)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                // 后台任务入口按能力位显示：老 bridge 上这条路由是 404，
                // 显示入口只会让用户点进去看报错（见 BridgeCapabilities.supportsSessionJobs）。
                if (supportsSessionJobs(state.bridgeCapabilities)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.jobs_title)) },
                        onClick = {
                            moreOpen = false
                            onOpenJobs()
                        },
                        leadingIcon = { Icon(Icons.Outlined.PlayCircle, null) },
                    )
                }
                // 定时任务：与会话绑定（cron 到点由电脑端触发，跑在同一个会话里）。
                // 与「后台任务」一样按能力位显示 —— 老桥没有这条路由，入口只配换来一个 404。
                if (supportsSchedules(state.bridgeCapabilities)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_menu_entry)) },
                        onClick = {
                            moreOpen = false
                            viewModel.openSchedulePanel(session.id)
                            scheduleOpen = true
                        },
                        leadingIcon = { Icon(Icons.Outlined.Schedule, null) },
                    )
                }
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
                // 判据从「有候选项」改成「有权限投影」：候选项拿不到时（老 bridge 没有
                // /chat/permission-presets 路由）仍要显示当前档位那一行。
                // 此前这个 `takeIf { it.options.isNotEmpty() }` 与 Pill 里的 early-return
                // 是同一个病根的两处表现 —— v2.2.44 真机上权限入口整块消失就是它俩干的。
                permission?.let { permissionValue ->
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
                            // 灰置时的副标题换成「怎么才能用」，而不是继续描述它是干什么的 ——
                            // 用户 2026-10-19 问「追加引导是干啥的？一直是灰色」：
                            // 灰着的原因（输入框没内容 / 会话没在跑）写在按钮上，就不用猜了。
                            Text(
                                if (canSteer) stringResource(R.string.busy_send_mode_steer_desc)
                                else stringResource(R.string.steer_requires_input),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
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
        if (scheduleOpen) {
            ScheduleSheet(state, viewModel) { scheduleOpen = false }
        }
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
