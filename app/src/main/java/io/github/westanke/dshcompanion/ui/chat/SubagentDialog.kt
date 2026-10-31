package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.SubagentView
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.displayItems

/**
 * 团队成员（子会话）自己的对话，只读。
 *
 * ## 为什么是「独立窗口」而不是在团队面板里下钻
 *
 * 团队面板是个 460dp 高的 ModalBottomSheet，塞对话进去只剩两三条的视野。这里要的是
 * 「翻一翻这个成员干了什么」，所以给一个接近全屏的窗口。用 [Dialog] 而不是再套一层
 * ModalBottomSheet：后者嵌套时 sheet 手势与返回键的归属会打架，Dialog 有自己的 window，
 * 关掉它就回到面板，层级天然清楚。
 *
 * ## 只读是设计而不是偷懒
 *
 * 这里**没有输入框**。写成员会话会让「谁在指挥谁」变得含混 —— 官方 Web 端同样只允许
 * 经 Lead 的模型工具去写。手机端要看内容，就只给看。
 *
 * 数据来源是桥的只读窄口 `GET /chat/sessions/{lead}/subagents/{sub}/messages`
 * （见 `HarnessViewModel.openSubagent`）。普通 `/messages` 对子会话 id 必然 404。
 */
@Composable
internal fun SubagentDialog(
    view: SubagentView,
    viewModel: HarnessViewModel,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        // usePlatformDefaultWidth = false：默认 Dialog 宽度是屏宽的 ~90% 且高度自适应，
        // 对话列表会被压成一条缝。关掉它才有「接近全屏」的版面。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .padding(vertical = 24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.subagent_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            view.label ?: view.subagentId.take(16),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                HorizontalDivider()

                when {
                    view.loading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    // PLUGIN_TOO_OLD 是 VM 塞进来的内部标记（端点 404 = 桥太旧），
                    // 直接显示会变成一句没人看得懂的英文常量。
                    view.failure != null -> FailureCard(
                        if (view.failure == "PLUGIN_TOO_OLD") {
                            stringResource(R.string.subagent_plugin_too_old)
                        } else {
                            view.failure
                        },
                    )

                    else -> {
                        // displayItems() 是纯函数（逐事件映射），随 history 缓存一次即可。
                        val items = remember(view.history) { view.history?.displayItems().orEmpty() }
                        if (items.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.subagent_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth().weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(items, key = { it.id }) { item -> SubagentMessageRow(item) }
                            }
                            Text(
                                stringResource(R.string.subagent_readonly_hint),
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一条消息。
 *
 * 刻意**不复用** `ChatItem`：那个组件带图片加载、重试、分叉、审批等一整套交互回调，
 * 在只读窗口里全是无效入口（点了要么没反应，要么把用户带进主会话的上下文）。
 * 这里只画「谁说的 + 说了什么」，工具/上下文/思考这类过程项直接略过 ——
 * 想看细枝末节应该回主会话，而不是在这个窗口里重建一套。
 */
@Composable
private fun SubagentMessageRow(item: ChatDisplayItem) {
    val (label, container) = when (item.kind) {
        ChatItemKind.USER ->
            stringResource(R.string.subagent_role_user) to MaterialTheme.colorScheme.secondaryContainer

        ChatItemKind.ASSISTANT ->
            stringResource(R.string.subagent_role_assistant) to MaterialTheme.colorScheme.surfaceVariant

        ChatItemKind.FAILURE ->
            stringResource(R.string.subagent_role_failure) to MaterialTheme.colorScheme.errorContainer

        // 过程项（思考/工具/上下文/截断/交付）不进这个只读窗口。
        else -> return
    }
    val body = item.body.ifBlank { item.title.orEmpty() }
    if (body.isBlank()) return

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                body,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
