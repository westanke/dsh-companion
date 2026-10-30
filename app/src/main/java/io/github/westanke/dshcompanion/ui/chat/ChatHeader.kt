package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
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
import androidx.compose.ui.text.font.FontWeight
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
    onOpenTeam: () -> Unit,
    onOpenTrajectory: () -> Unit,
    onOpenJobs: () -> Unit,
    permission: PermissionSelect?,
    permissionEnabled: Boolean,
    onPermissionSelect: (String) -> Unit,
    canSteer: Boolean,
    onSteer: () -> Unit,
    wide: Boolean = false,
) {
    // 两段式顶栏（2026-10-30 真机反馈重排）：
    //  ① 标题行只放 返回 / 标题 / ⋯ —— 长标题不再被文件按钮分走 48dp；
    //  ② 状态行独占整行：已连接 · 权限 · 智能团队 从左排满（左缘前移到屏幕边距，
    //     不再缩在返回键+标题的窄列里），文件按钮用 Spacer 顶到行尾 —— 小屏上
    //     「智能团队」胶囊也能完整显示，不再被裁。
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        }
            // 标题在窄屏（手机）永远单行；平板（wide）下允许两行 —— 兜底到长 cwd 路径时
            // 不会在顶栏被腰斩，短标题（用户起的名字）依然只占一行，布局无感。
            Text(
                sessionDisplayTitle(session),
                Modifier.weight(1f).padding(start = if (onBack == null) 10.dp else 2.dp),
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        // ⋯ 菜单留在标题行右缘（网页版 chat.html:283 的 #more 同位）：它是整个会话的动作总入口。
        // 「刷新」与「停止运行」也收在里面（用户 2026-10-19 要求：顶栏太挤，挪进三个点）。
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
                // 智能团队：会话 ⋯ 菜单一级直达（用户 2026-10-30 要求不藏在统计面板里）。
                // 无能力位门控：数据随 history.projections 始终返回，老桥/未启用团队时面板自己显示空态。
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.team_menu_entry)) },
                    onClick = {
                        moreOpen = false
                        onOpenTeam()
                    },
                    leadingIcon = { Icon(Icons.Outlined.Groups, null) },
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
        // 状态行独占整行（不再挤在标题的窄列里）：
        // 已连接 / 权限 / 智能团队 三个状态从左排；Spacer 把文件按钮顶到行尾 ——
        // 「已连接」从返回键右缘前移到屏幕边距，文件按钮后移，中间胶囊在小屏放得下、不被裁。
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack == null) 10.dp else 4.dp, bottom = 2.dp),
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
            // 「智能团队」胶囊（2026-10-30 用户要求：紧跟权限档位 Pill 之后；
            // 一点直达只读团队面板，不用再翻 ⋯ 菜单）。
            TeamEntryPill(onClick = onOpenTeam)
            if (session.pendingInteraction == "approval") ApprovalStatusLabel()
            Spacer(Modifier.weight(1f))
            // 「本会话的文件」（这个页面的主入口，值得占一格）挪到状态行行尾，
            // 不再与长标题抢标题行的 48dp。
            IconButton(onClick = onOpenFiles, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    stringResource(R.string.session_files),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 标题副行的「智能团队」快捷胶囊（2026-10-30）。
 *
 * 用户要求紧跟权限档位 Pill 之后：权限回答「这个会话能动多大」，
 * 团队胶囊回答「这个会话里有谁在干活」，两者并排语义自洽。
 * 品牌主色容器底，与三档语义色（蓝/琥珀/红）拉开区分；点击打开 [TeamPanelSheet]
 * （状态由 `ChatDetail.kt` 的 teamSheet 持有，本胶囊只发回调）。
 */
@Composable
private fun TeamEntryPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Groups,
            contentDescription = stringResource(R.string.team_menu_entry),
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            stringResource(R.string.team_menu_entry),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
