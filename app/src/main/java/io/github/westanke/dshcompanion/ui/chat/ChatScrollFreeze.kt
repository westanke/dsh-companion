package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * 滚动中保留**开始滚动那一刻**的快照；停手后再等 80ms 才回到实时 ——
 * 那 80ms 留给惯性滑动与高度收敛，立刻灌入会让正在停稳的位置跳一下。
 *
 * ## 为什么新消息也一起冻结
 *
 * 而不是「有新条目就立即插入」：滚动中插入条目会直接改变可见区下方的内容高度，
 * 用户正读的那一行会被顶走 —— 比晚几秒看到新消息难受得多。
 * 底部的「跳到最新」按钮本来就负责把他带回去。
 *
 * ## 怎么修好的一场真机事故（2026-10-18）
 *
 * 初版写的是：
 *
 * ```kotlin
 * var frozen by remember { mutableStateOf(items) }        // 首次组合固化
 * LaunchedEffect(settled) { if (settled) frozen = items } // 只在 settled 变化时跑
 * return if (settled) items else frozen
 * ```
 *
 * 它有一个**只在真机上才暴露**的致命组合：
 *
 * 1. 首次组合时 `items` 还是空的（历史还没回来）→ `frozen` 固化成**空表**；
 * 2. `settled` 初值就是 `true`，那个 effect 在首次组合跑过一次之后就**再没跑过**
 *    （它的键 `settled` 没变）→ `frozen` **永远是空表**；
 * 3. 于是任何一次 `scrolling = true` 都会把**空表**交给 LazyColumn；
 * 4. 列表被清空的那一瞬，**Compose 把滚动位置重置为 0**，内容回来后也不恢复。
 *
 * 而第 3 步的触发者恰恰是**贴底跟随自己** —— `listState.animateScrollToItem()`
 * 会把 `isScrollInProgress` 置真。于是闭环形成：
 *
 * > 贴底跟随 → 触发滚动 → 冻结成空表 → 列表清空 → 位置归零 →
 * > `isAtBottom=false` → 跟随被自己的守卫挡住 → 再也不跟随
 *
 * 真机日志（`totalItemsCount` 掉到 1 就是「只剩『加载更早消息』那一个条目」）：
 *
 * ```
 * #18 贴底跟随触发：isAtBottom=true  可见=39-47/48      ← animateScrollToItem
 * #19 snapshotFlow：totalItemsCount=1                  ← 空 snapshot 生效
 * #20 snapshotFlow：totalItemsCount=48
 * #23 贴底跟随未触发：isAtBottom=false 可见=0-8/48 first=0/0  ← 位置归零，再不回来
 * ```
 *
 * **修法**：快照不再「首次组合固化」，改成**开始滚动的那一刻现抓**
 * （用 `rememberUpdatedState` 读到当下最新的 `items`，又不让 effect 依赖它）；
 * 停手 80ms 后置 `null` 表示「回到实时」。`null` 而不是空表，
 * 语义上也不可能把空列表当成有效快照交出去。
 */
@Composable
internal fun rememberFrozenItems(items: List<ChatDisplayItem>, scrolling: Boolean): List<ChatDisplayItem> {
    // 让下面那个 effect 能读到**最新**的 items，但它的键里不含 items ——
    // 否则流式期间每帧重启 effect，等于白冻。
    val latest = rememberUpdatedState(items)
    // null = 不冻结（回到实时）。用 null 而不是空表，见 KDoc 末尾。
    var frozen by remember { mutableStateOf<List<ChatDisplayItem>?>(null) }

    LaunchedEffect(scrolling) {
        if (scrolling) {
            // 开始滚动的这一瞬抓快照 —— 此时 items 一定是最新的，
            // 所以绝不可能像初版那样抓到一个「历史还没回来」的空表。
            frozen = freezeSnapshot(latest.value)
        } else {
            delay(80)
            frozen = null
        }
    }

    return frozen ?: items
}

/**
 * 快照的合法性：**空表不算快照**，返回 `null` 表示「这一拍没什么可冻的」。
 *
 * 抽成顶层纯函数是为了能机检（`ChatScrollFreezeTest`）——
 * `@Composable` 里的状态生命周期在 JVM 单测里测不到，但这条不变式可以：
 *
 * > 任何被当作「冻结快照」交出去的列表都**不得为空**。
 *
 * 真机事故（2026-10-18）的根因正是初版让 `frozen` 在首次组合时固化成空表，
 * 而空表交给 LazyColumn 会把列表清空、进而**把滚动位置重置为 0**。
 * 把不变式钉在这里，将来谁再把空表当有效快照就会红。
 */
internal fun freezeSnapshot(items: List<ChatDisplayItem>): List<ChatDisplayItem>? =
    items.takeIf { it.isNotEmpty() }
