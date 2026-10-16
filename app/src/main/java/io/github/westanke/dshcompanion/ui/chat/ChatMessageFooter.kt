package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.chatTimeLabel
import io.github.westanke.dshcompanion.data.forkPointOf
import java.util.Locale
import java.util.TimeZone

/**
 * 消息气泡下方的**元信息与次要动作**一行：时间戳，以及「从这条分叉」。
 *
 * 从 `ChatMessageItems.kt` 拆出（2026-10-17）：那个文件加上这两块后越过 800 行预算，
 * 而这两块的共同点是「都不是消息正文」—— 它们回答的是「什么时候说的」和「能不能从这儿重来」。
 * 拆在一起比留在正文渲染里更清楚：正文那条路以后还会长，元信息这条不会。
 *
 * 两个刻意的呈现决定：
 * - **时间戳只挂在消息上**（用户/助手）：工具行、上下文注入、推理摘要都在过程组里，
 *   逐行配时间是噪音 —— 「多久」由过程组的耗时摘要回答。
 * - **分叉入口只给用户消息**（判据在 `data/ChatForkPoint.kt`）：真实场景几乎总是
 *   「这次提问的走向不对，从这儿重来」；每条消息都挂按钮会让消息流毛糙。
 */
@Composable
internal fun ChatMessageFooter(
    item: ChatDisplayItem,
    onForkAt: ((Int) -> Unit)?,
) {
    val isMessage = item.kind == ChatItemKind.USER || item.kind == ChatItemKind.ASSISTANT
    if (!isMessage) return

    // `item.time == null` 的是流式项（还没落盘），不显示时间。
    item.time?.let { stamp ->
        // 按时间戳缓存 label：避免每次重组都重建 SimpleDateFormat（热路径上的分配）。
        // `now` 在缓存建立时取一次 —— 跨天时标签要等下次重组才更新，对「14:03」无实际影响。
        val label = remember(stamp) {
            chatTimeLabel(stamp, System.currentTimeMillis(), TimeZone.getDefault(), Locale.getDefault())
        }
        Text(
            label,
            // `bottom` 不能省：消息流本身只有 5dp 间距，时间戳若无底距会与**下一条消息**贴在一起 ——
            // 真机反馈「执行了命令下面显示的时间与下一条消息重叠」。
            // 6dp → 10dp（bottom）、3dp → 8dp（top）：用户第三次反馈「时间还是重叠」。
            // 这次两边都拉开 —— 与上方正文/表格底部 8dp、与下一条消息 18dp。
            // （上一轮只加了 bottom，结果时间戳贴着**上面**的表格底部，那也是一种"重叠"。）
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp, start = 4.dp, end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            // 跟随气泡那一侧：用户消息在右、助手在左 —— 时间戳跑到对侧会看着像别人的。
            textAlign = if (item.kind == ChatItemKind.USER) TextAlign.End else TextAlign.Start,
        )
    }

    // 点击只上报序号；**确认对话框由 ChatDetail 那一层持有**（同时只可能有一个待确认的分叉）。
    val forkSeq = forkPointOf(item)
    if (forkSeq != null && onForkAt != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { onForkAt.invoke(forkSeq) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Outlined.CallSplit, null, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.fork_from_here),
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
