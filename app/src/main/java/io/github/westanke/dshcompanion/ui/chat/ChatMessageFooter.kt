package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
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
 * 消息气泡下方的**元信息与次要动作**：时间戳，以及「从这条分叉」。
 *
 * 从 `ChatMessageItems.kt` 拆出（2026-10-17）：那个文件加上这两块后越过 800 行预算，
 * 而这两块的共同点是「都不是消息正文」—— 它们回答的是「什么时候说的」和「能不能从这儿重来」。
 * 拆在一起比留在正文渲染里更清楚：正文那条路以后还会长，元信息这条不会。
 *
 * ## 2026-10-17 六修：为什么搬进气泡里面
 *
 * 原来这两个元素挂在 `SelectionContainer` 层（气泡 Column **之外**），时间戳是一条
 * **全宽** `Text`。后果：时间戳的行高与**下一个 item** 的气泡顶部叠在一起 ——
 * 用户连续五次反馈「显示时间与消息重叠」，前四版都在调它的 `top/bottom` padding
 * （3→6→8→10dp）都没用，因为**间距根本不是这里产的**：item 之间由 LazyColumn 的
 * `spacedBy(8.dp)` 负责，而这条全宽 Text 的行高会一直延伸到 item 边界。
 *
 * 现在由调用方把它们放进气泡的 `Column` 里（见 `ChatMessageItems.kt` 的 USER/ASSISTANT
 * 两个分支）：与气泡同宽、同一列，行高不再跨出 item 边界，与下一条消息之间就是干净的
 * `spacedBy(8.dp)` + 气泡内 padding。
 *
 * 两个刻意的呈现决定：
 * - **时间戳只挂在消息上**（用户/助手）：工具行、上下文注入、推理摘要都在过程组里，
 *   逐行配时间是噪音 —— 「多久」由过程组的耗时摘要回答。
 * - **分叉入口只给用户消息**（判据在 `data/ChatForkPoint.kt`）：真实场景几乎总是
 *   「这次提问的走向不对，从这儿重来」；每条消息都挂按钮会让消息流毛糙。
 *
 * @param modifier 由调用方传入对齐方式：用户消息 `Alignment.End`、助手 `Alignment.Start`。
 *   时间戳不再自己 `fillMaxWidth()` + `textAlign` —— 那正是行高跨出 item 边界的原因。
 */
@Composable
internal fun ChatMessageFooter(
    item: ChatDisplayItem,
    onForkAt: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
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
            // 2026-10-17 六修（第五次反馈「显示时间与消息重叠」后的根治）：
            //
            // 原来这条是 **fillMaxWidth()** 的全宽 Text，行高一直横向延伸到 item 边界，
            // 于是与**下一个 item** 的气泡顶部叠在一起。前四版都在调它的 top/bottom padding
            // （3 → 6 → 8 → 10dp）—— 全都没用，因为间距根本不是这里产的：item 之间由
            // LazyColumn 的 `spacedBy(8.dp)` 负责，全宽 Text 的行高却跨过了那个边界。
            //
            // 现在两个手段一起用（缺一不可，这是第六版才凑齐的）：
            // ① `wrapContentWidth()` —— 行高只包住文字本身，不再横向延伸到 item 边界
            //    （这是根治：行高跨边界才是与下一个 item 的气泡顶部相叠的原因）；
            // ② `bottom = 8dp` —— 第五版我把它归零了，结果时间戳（labelSmall ≈ 13dp 行高）
            //    与下一条消息之间只剩 spacedBy(8.dp)，对一条文字仍然太紧，用户第六次反馈。
            //    有了①之后，bottom 不会再被推出边界，可以放心加。
            modifier = modifier
                .wrapContentWidth()
                .padding(top = 6.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    // 点击只上报序号；**确认对话框由 ChatDetail 那一层持有**（同时只可能有一个待确认的分叉）。
    val forkSeq = forkPointOf(item)
    if (forkSeq != null && onForkAt != null) {
        // 同理：不设 bottom padding（与下一条消息的距离由 spacedBy 负责）。
        Row(modifier, horizontalArrangement = Arrangement.End) {
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
