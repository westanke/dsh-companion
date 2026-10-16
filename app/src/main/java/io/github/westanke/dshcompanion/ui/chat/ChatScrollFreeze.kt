package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.westanke.dshcompanion.data.ChatDisplayItem
import kotlinx.coroutines.delay

/**
 * 滚动期间冻结「流式快照」，返回这一刻该渲染的那份列表。
 *
 * 从 `ChatDetail.kt` 拆出（2026-10-17）：那个容器文件加上投影缓存后越过 800 行预算，
 * 而这段逻辑本来就自成一体 —— 它只做一件事：把「数据源」与「渲染快照」分开。
 *
 * ## 症状（对齐 Whaleness 的 80ms 稳定窗口）
 *
 * 智能体正在流式输出时，用户若往上翻历史，每一个 token 都会重建列表数据源 ——
 * LazyColumn 于是每帧都在测量新内容，手指下的滚动一顿一顿；
 * 而用户此刻压根不在看末尾，那些更新纯属白干。
 *
 * ## 做法
 *
 * 滚动中保留**停手那一刻**的快照；停手后再等 80ms 才灌入最新 ——
 * 那 80ms 留给惯性滑动与高度收敛，立刻灌入会让正在停稳的位置跳一下。
 *
 * ## 为什么新消息也一起冻结
 *
 * 而不是「有新条目就立即插入」：滚动中插入条目会直接改变可见区下方的内容高度，
 * 用户正读的那一行会被顶走 —— 比晚几秒看到新消息难受得多。
 * 底部的「跳到最新」按钮本来就负责把他带回去。
 */
@Composable
internal fun rememberFrozenItems(items: List<ChatDisplayItem>, scrolling: Boolean): List<ChatDisplayItem> {
    var settled by remember { mutableStateOf(true) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            settled = false
        } else {
            delay(80)
            settled = true
        }
    }
    var frozen by remember { mutableStateOf(items) }
    LaunchedEffect(settled) {
        if (settled) frozen = items
    }
    return if (settled) items else frozen
}
