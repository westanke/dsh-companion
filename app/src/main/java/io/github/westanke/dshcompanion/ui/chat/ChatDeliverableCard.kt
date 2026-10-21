package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind

/**
 * 消息流里的**显式交付物卡片**（`ChatItemKind.DELIVERABLE`）。
 *
 * 对齐桌面端：agent 用 present 工具交付的文件，在**轮次收尾**位置钉一张边框卡
 * （文档图标 + 文件名 + agent 写的说明）。数据在 `ChatPresentation.displayItems()`：
 * 按 `deliverables/presented` 事件缓冲，每轮 `turn/end` 处冲刷 —— 所以卡片总在
 * 收尾消息之后、下一轮提问之前，与桌面端 turnTail 的钉法一致。
 *
 * ## 为什么单独一个文件
 *
 * `ChatMessageItems.kt` 777 行，贴近 `docs/project/TECH-DEBT.md` 的 800 行预算，
 * 卡片放进去就得拆别的；单独一个文件也把「消息流卡片」与「文件清单弹层」的
 * 职责边界显式化。
 *
 * ## 版式为什么是「紧凑卡」而不是 [SessionFileRow]
 *
 * [SessionFileRow] 是文件清单弹层里的**盘点行**（文件名 + 说明 + 完整路径两行），
 * 弹层里路径是主参照；消息流里它占两行 monospace，一次交付多个文件时把正文挤没了。
 * 卡片走紧凑版式：文件名 + 说明，完整路径与预览点开走 [openSessionFile]（与正文里
 * 的文件链接同一条路）—— 桌面端也是这个信息密度。
 *
 * 手势：单击整卡 = 预览。不挂 combinedClickable/长按 —— 卡片在 `ChatItem` 的
 * `SelectionContainer` 里，长按留给选择手柄（见 `ChatMessageItems.kt` 的手势规则）。
 */
@Composable
internal fun DeliverableCardRow(
    item: ChatDisplayItem,
    onOpenFile: (String) -> Unit,
) {
    // kind==DELIVERABLE 却没有 deliverable 字段是数据层的 bug；直接不渲染，
    // 不显示一张空卡（空卡看起来像渲染坏了）。
    val file = item.deliverable ?: return
    require(item.kind == ChatItemKind.DELIVERABLE)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { onOpenFile(file.path) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.weight(1f)) {
                // 文件名放不下时横向滚动而不是截断 —— 与 [SessionFileRow] 同一条纪律：
                // 这里的名字常常又长又前缀雷同，截出来分不清是哪个。
                Text(
                    file.path.substringAfterLast('/').ifBlank { file.path },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // agent 为交付物写的说明（「本轮排障报告」「v2.2.48 安装包」这种）。
                file.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        desc,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
