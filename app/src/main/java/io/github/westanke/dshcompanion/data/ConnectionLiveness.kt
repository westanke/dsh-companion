package io.github.westanke.dshcompanion.data

/**
 * 连接页的「此刻通不通」判据 —— 三个纯函数，全部可单测。
 *
 * ## 为什么单独抽出来
 *
 * 这条 bug（2026-10-20）的根因不是某一处写错，而是**同一个历史状态位被三处各自解释**：
 *
 * | 位置 | 它假设 `connected` 是 | 实际语义 |
 * |---|---|---|
 * | 回前台复核要不要跑 | 「此刻还连着」 | 「曾经连上过」 |
 * | 卡片显示什么状态 | 同上 | 同上 |
 * | 按钮点下去要不要重连 | 同上 | 同上 |
 *
 * 三处都错在同一个字上，但错法各不相同 —— 只修其中一处，另外两处会继续错。
 * 所以把判据做成**带名字的纯函数**：每一处错的假设都变成一个有测试盯着的名字，
 * 将来再加第四处也只会调用同一批函数，不会各自再猜一遍。
 *
 * 放在 `data/` 而不是 `ui/`：判据不依赖 Compose，能在 JVM 上直接跑。
 */

/**
 * 回前台时要不要给这条连接做一次健康检查。
 *
 * 只有「还停在连接页、而且手上真有一条连接」才值得探 —— 其余情形各自有别的机制管：
 * - 已进 App 且连着：事件流（`eventsConnected`）盯着，断线自己会翻；
 * - 没有活动连接：没有可探的对象（冷启动还没连、或用户主动断开过）；
 * - 正在并发探活/建连：别抢，它自己会写权威结果。
 */
internal fun shouldRecheckActiveConnection(
    connectGatePassed: Boolean,
    connected: Boolean,
    hasActiveConnection: Boolean,
    connecting: Boolean,
): Boolean {
    if (connectGatePassed && connected) return false
    if (!hasActiveConnection) return false
    if (connecting) return false
    return true
}

/**
 * 点某台电脑的按钮时，要不要真的发起连接。
 *
 * 判据是「这台是不是当前活跃连接 **且** 客户端还握在手里」，**不是** `connected`：
 * 后者是历史值，断网后可能还是 true。用历史值当短路条件，按钮就成了哑巴 ——
 * 既不重连，又被门闩（`!connectGatePassed || !state.connected`）判回连接页，
 * 真机表现就是「点了没反应」。
 */
internal fun shouldReconnectOnTap(
    hostId: String,
    activeHostId: String?,
    hasActiveConnection: Boolean,
): Boolean = !(hostId == activeHostId && hasActiveConnection)

/**
 * 连接卡片的三种状态。
 *
 * 「连过但此刻不通」必须与「从来没连过」**分开展示**：两者之前都没有状态点，
 * 屏幕上长得一模一样，用户只能凭记忆猜这台以前是不是连上过。
 */
internal enum class HostCardState {
    /** 当前活跃连接，且复核/连接成功过：绿点「已连接」，按钮是「进入应用」。 */
    CONNECTED,

    /** 是当前活跃连接、但链路已断：红点「已断开」，按钮是「重新连接」。 */
    DISCONNECTED,

    /** 其余（从没连过，或不是当前活跃的那台）：无状态点，按钮是「连接」。 */
    IDLE,
}

internal fun hostCardState(hostId: String, activeHostId: String?, connected: Boolean): HostCardState = when {
    hostId != activeHostId -> HostCardState.IDLE
    connected -> HostCardState.CONNECTED
    else -> HostCardState.DISCONNECTED
}
