package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.westanke.dshcompanion.debug.DiagLog

/*
 * ============================================================================
 * 会话页滚动落底的诊断日志
 * ============================================================================
 *
 * ## 为什么单独一个文件
 *
 * `ChatDetail.kt` 的「进会话落底」逻辑改了九版才对（2026-10-17），
 * 期间全靠屏上探针的数字定位。本轮要把那套一次性探针固化成持续日志，
 * 但日志本身有二十多行 —— 塞回 `ChatDetail.kt` 会把它推过 800 行预算
 * （`ArchitectureGuardTest` 第 1 条会直接红），而为一个调试特性去
 * `docs/project/TECH-DEBT.md` 立债务账，是把「诊断代码」和「业务债」混为一谈。
 *
 * 所以抽到这里：`ChatDetail.kt` 只留三行调用，预算回到线内，
 * 台账仍然干净。**诊断代码不进业务文件的行数账。**
 *
 * ## 怎么读这些日志
 *
 * 一次正常的进会话落底，日志形状是这样的（时间顺序）：
 *
 * ```
 * #12 SCROLL 落底 effect 启动：会话 abc（totalItems=0）
 * #13 SCROLL 落底循环开始：等内容用 3 拍（totalItems=49）
 * #14 SCROLL 落底下推 5 拍 moved=600 first=0/0 total=49
 * #19 SCROLL 落底下推 10 拍 moved=600 first=2/1200 total=49
 * #64 SCROLL 落底循环结束：scrollBy=0（物理底部），共 40 拍
 * ```
 *
 * 出问题时的形状（对照下面「判读表」）：
 *
 * ```
 * #12 SCROLL 落底 effect 启动：会话 abc（totalItems=49）   ← 一进来就有 49 项
 * #13 SCROLL 落底循环退出：isScrollInProgress=true（用户拖动），已推 0 拍
 * ```
 *
 * ## 进会话落底：九版教训与正确形状
 *
 * 这段逻辑（`ChatDetail.kt` 的 `LaunchedEffect(selected.id)`）改了九版才做对，
 * 2026-10-17 定型。**每一版都有理由、每一版都没用**，所以记在这里，
 * 免得将来有人再走一遍。当前实现是：进会话启动独立循环，每 32ms 推 600px，
 * `scrollBy` 返回 0（已在物理底部）即停，用户真拖动就退出循环交还控制权。
 *
 * ① 「跳到底部」按钮消失**不是**落底的判据：它的显隐条件是 `!isAtBottom`，
 *    而 `isAtBottom` = 「最后一项在前 3 项之内」，宽松得很。实测按钮已消失时
 *    实际还差 46 项 —— 拿它当验收标准等于自己给自己发合格证。
 * ② `LaunchedEffect(selected.id)` 只在会话 id 变化时跑**一次**：首次进入时历史
 *    还没回来，直接 return，之后再没有第二次触发。
 * ③ `filter { totalItemsCount > 0 }.first()` 同病：首次非零时列表只有
 *    「加载更早消息」一项，滚过去等于滚到那一项。
 * ④ 「等 totalItemsCount 连续两次相同」也不行：历史**分批到达**，count 一直在变。
 * ⑤ **把「当前不在底部」当成「用户滑过历史」—— 这是最致命的一处。**
 *    屏上探针实测：外层 `total=49 vis=3 last=2 first=0/0 atBottom=false`，
 *    内层 `count=49 atBottom=false lastIdx=49` —— collect 正常发射，
 *    但加载期间「视口还在 0、count 已涨到 49」，"不在底部" 成立，
 *    于是被判定成「用户翻过历史」，自动滚动**永久关闭**。
 *    **数据加载与用户滑动在布局上表现完全一样**，只有 `isScrollInProgress`
 *    （只有真手势拖动才为 true）能区分 —— 用户手势必须是唯一置位依据。
 * ⑥ `animateScrollToItem(count - 1, scrollOffset = -N)` 不生效：那是 Compose 对
 *    「目标项已在视口内」的约定，而最后一项比一屏还高，压根还没进视口。
 * ⑦ 每帧只 `scrollBy` 一次也不够：collect 只在读数变化时发射，
 *    发射次数推不完一屏 1719px 的高度（实测停在 first=30/54）。
 *
 * 为什么必须独立于 snapshotFlow：collect **只在读数变化时**发射，而落底是一次
 * 「持续下推」的动作。真机取证（屏上探针）：
 *   改前：first=0/0  ——  collect 里判「不在底部」= 用户滑过 → 永久停掉；
 *   改后：first=48/825 → 每发一次只 scrollBy 一次，最后停在 30/54（数据分批到达，
 *                            collect 发射次数有限，推不完一屏 1719px 的高度）。
 *
 * 现在：进会话就启动循环，每 32ms 推 600px，`scrollBy` 返回 0（已在物理底部）即停。
 * 用户一旦真的拖动列表就退出循环（dragging），把控制权交回用户。
 * 实测 first 从 0/0 → 48/4560（推了三屏多）。
 */

/** 「等内容」阶段最多等几拍（每拍 50ms，60 拍 = 3 秒）。与 ChatDetail 里的循环保持同一个数。 */
private const val GUARD_TICKS = 60

/**
 * 落底循环开始前的第一条：证明 effect 真的跑了。
 *
 * 这条与 [ChatDetail] 里 `LaunchedEffect(selected.id)` 的组合日志配对：
 * 没有这条 = effect 压根没执行（或渲染的不是原生页，那就是路由问题）；
 * 有这条没有后面的循环日志 = effect 跑了但循环立刻退出。
 */
internal fun logScrollEffectStart(sessionId: String, totalItems: Int) {
    DiagLog.i(
        DiagLog.Cat.SCROLL,
        "落底 effect 启动：会话 $sessionId（totalItems=$totalItems）",
    )
}

/** 循环等到了内容，开始下推。 */
internal fun logScrollLoopStart(guard: Int, totalItems: Int) {
    DiagLog.i(
        DiagLog.Cat.SCROLL,
        "落底循环开始：等内容用 $guard 拍（totalItems=$totalItems）",
    )
}

/**
 * 循环被用户手势打断。
 *
 * 这是**唯一正常**的退出路径之一：用户主动拖动时把控制权交还，
 * 不该继续跟着推。若这条出现在「用户根本没碰屏幕」的情况下，
 * 说明 `isScrollInProgress` 被别的东西置真了（例如嵌套滚动容器）。
 */
internal fun logScrollLoopAbortedByUser(ticks: Int) {
    DiagLog.i(
        DiagLog.Cat.SCROLL,
        "落底循环退出：isScrollInProgress=true（用户拖动），已推 $ticks 拍",
    )
}

/** 推到列表物理底部，正常收工。 */
internal fun logScrollLoopFinished(ticks: Int) {
    DiagLog.i(
        DiagLog.Cat.SCROLL,
        "落底循环结束：scrollBy=0（物理底部），共 $ticks 拍",
    )
}

/**
 * 每 5 拍记一次下推进度。
 *
 * 刻意降频：32ms 一拍，一次落底上百拍，逐条写会把 `diag.log` 写满
 * （环形覆盖虽不至于爆盘，但会把窗口/路由那些更关键的日志顶掉）。
 */
internal fun logScrollTick(ticks: Int, moved: Float, state: LazyListState) {
    if (ticks % 5 != 0) return
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    DiagLog.d(
        DiagLog.Cat.SCROLL,
        "落底下推 $ticks 拍 moved=${moved.toInt()} " +
            "first=${first?.index}/${first?.offset} total=${info.totalItemsCount}",
    )
}

/** 循环跑满拍数上限还没到底 —— 通常是列表极长或 scrollBy 被别的东西抵消。 */
internal fun logScrollLoopExhausted(ticks: Int, totalItems: Int) {
    DiagLog.w(
        DiagLog.Cat.SCROLL,
        "落底循环耗尽：已推 $ticks 拍仍未到底（totalItems=$totalItems）",
    )
}

/** 等了 [GUARD_TICKS] 拍列表还是空的：历史没回来，循环空转退出。 */
internal fun logScrollNoContent(sessionId: String) {
    DiagLog.w(
        DiagLog.Cat.SCROLL,
        "落底循环未开始：等 ${GUARD_TICKS} 拍后 totalItems 仍为 0（会话 $sessionId，历史没回来）",
    )
}

/**
 * 流式期间的贴底跟随。
 *
 * ## 为什么要带「可见区间」这几个数
 *
 * 真机实测（2026-10-18）：一个「运行中」的会话，`isAtBottom` **持续为 false**，
 * 贴底跟随一次都没触发 —— 而代码注释承诺「没滑过就一直跟着新内容贴底」。
 * 只有 `entries=41 total=42` 这两个数**判不出**是谁的锅：
 *   · 视图根本没在底部（用户/代码把位置甩走了）？
 *   · 还是 `isAtBottom` 的判据本身失灵（在底部却算成不在）？
 * 把 `visible.last().index` 打出来就能一眼分开：若它远小于 `total-1`，
 * 是**位置不在底部**；若它已等于 `total-1` 而 `atBottom=false`，是**判据失灵**。
 *
 * ## 数字口径（真机踩过）
 *
 * [entries] 是分组后真正交给 LazyColumn 的条目数（`displayEntries.size`），
 * 与 `layoutInfo.totalItemsCount` 才可比。初版传的是分组**前**的 `displayItems.size`，
 * 于是出现「entries 60 / total 57」这种看着像 bug 的组合 —— 分组会把若干条消息
 * 折进一个过程组，条目数本来就**少于**消息项数。数字没错，错的是并排显示两个口径。
 *
 * ## 决策不变就不记
 *
 * 这三个数的组合在流式期间每帧都在变，逐帧落盘会把日志淹没（实测一小时刷出 200+ 条）。
 * 所以只在**决策**（是否触发、以及是否在底部）变化时记一条；
 * 数字本身留给 `atBottom` 翻转的那一帧，足够定位。
 */
internal fun logScrollFollow(
    state: androidx.compose.foundation.lazy.LazyListState,
    entries: Int,
    liveItems: Int,
    wantsBottom: Boolean,
    atBottomNow: Boolean,
    animated: Boolean,
    lastDecision: String?,
): String? {
    if (entries <= 0) return lastDecision // 列表还空着，没什么可记的

    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    val last = info.visibleItemsInfo.lastOrNull()
    val decision = if (wantsBottom) "触发" else "未触发"

    // 决策没变 → 不记（这是把 200+ 条压到个位数的关键）。
    // 但「未触发」时若可见区间变了，仍值得记一条：那说明视图在动。
    val span = "${first?.index}-${last?.index}/${info.totalItemsCount}"
    val key = "$decision|$atBottomNow|$span"
    if (key == lastDecision) return lastDecision

    DiagLog.i(
        DiagLog.Cat.SCROLL,
        // 同时打「意图」与「瞬时位置」：两者不一致（意图=想贴底、位置=不在底部）
        // 正是这次修复要治的场景 —— 旧代码在这种时刻会拒绝跟随。
        "贴底跟随$decision：意图=$wantsBottom 在底部=$atBottomNow entries=$entries live=$liveItems " +
            "可见=$span first=${first?.index}/${first?.offset} last=${last?.index}/${last?.offset} " +
            "animate=$animated",
    )
    return key
}

/** `snapshotFlow { totalItemsCount }` 每次发射 —— 历史分批到达的可观察证据。 */
internal fun logScrollCountEmitted(count: Int) {
    DiagLog.d(DiagLog.Cat.SCROLL, "snapshotFlow collect 发射：totalItemsCount=$count")
}

/**
 * 粘性「想待在底部」意图 —— 贴底跟随的守卫。
 *
 * 这是 `ChatDetail.kt` 的注释一直承诺、但此前**从未实现**的 `userScrolled`：
 *
 * > 持续贴底的守卫是 `!userScrolled`：用户一旦主动滑过就停手，没滑过就一直跟着新内容贴底
 *
 * ## 为什么不能拿 `isAtBottom` 当守卫（真机实测 2026-10-18）
 *
 * `isAtBottom` 是**瞬时几何位置**。流式输出每来一段新内容就把底部推远一点 →
 * 它立刻变 false → 跟随被自己的守卫挡住 → 越走越远。
 * 日志上表现为「贴底跟随未触发」刷几百条、有效跟随一次都没有。
 *
 * ## 判据的两个方向都必须准确
 *
 * | 方向 | 用什么 | 为什么不能用别的 |
 * |---|---|---|
 * | 撤销（用户翻走了） | `interactionSource` 的 **`DragInteraction.Start`** | `isScrollInProgress` 对**程序滚动**（`animateScrollToItem` / `scrollBy`）同样为 true，而贴底跟随自己就会触发它 —— 用它会把「程序自己滚」误判成「用户翻走了」，闭环锁死 |
 * | 恢复（用户回到时间线末尾） | `isAtBottom` | 用户滚到底，意图自然回来；这是唯一没有歧义的信号 |
 *
 * [atBottom] 收 `State<Boolean>` 而不是 `Boolean`：要对它做 `snapshotFlow`。
 * 传值的话只能订阅调用点那次组合的值，之后再变这个 effect 也不知道。
 */
@Composable
internal fun rememberWantsBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    atBottom: androidx.compose.runtime.State<Boolean>,
    sessionId: String?,
): Boolean {
    // 换会话重置为「想待在底部」：进会话本来就该贴底。
    var wantsBottom by remember(sessionId) { mutableStateOf(true) }

    // 用户真的上手拖了 → 撤销贴底意图。这是唯一可靠的「主动滑过」判据。
    LaunchedEffect(sessionId) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                wantsBottom = false
            }
        }
    }
    // 任何时候真的到了底部 → 恢复贴底意图。
    LaunchedEffect(sessionId) {
        snapshotFlow { atBottom.value }.collect { if (it) wantsBottom = true }
    }

    return wantsBottom
}
