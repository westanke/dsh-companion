package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接页「此刻通不通」三条判据的回归用例。
 *
 * ## 为什么这三条必须单测
 *
 * 2026-10-20 真机验收发现的两个缺陷，**根因是同一个**：`HarnessState.connected`
 * 只在连接成功时置 true，全仓没有任何代码把它置回 false —— 它的真实语义是
 * 「曾经连上过」，但三处代码都当成了「此刻还连着」：
 *
 * 1. 连接卡片据此显示「已连接」（断网了还说连着）；
 * 2. 按钮点击据此短路掉重连（点了没反应，还被门闩弹回）；
 * 3. 回前台复核据此判断要不要跑（若沿用错判据，就永远不跑）。
 *
 * 单测跑 JVM、没有真机网络，所以要盯的**不是**「探活有没有发出去」，
 * 而是「判据在历史值与现状不一致时站哪边」。这三条函数的每个分支都对应一种真机情形，
 * 改错任何一个，用户都会重新看到那句「已连接」。
 */
class ConnectionLivenessTest {

    // ---------------------------------------------------------------- 回前台复核

    @Test
    fun `停在连接页且手上有一条连接 才需要复核`() {
        assertTrue(
            "连接页（门闩未放行）+ 有连接：正是要复核的情形",
            shouldRecheckActiveConnection(
                connectGatePassed = false,
                connected = true,
                hasActiveConnection = true,
                connecting = false,
            ),
        )
    }

    @Test
    fun `已进 App 且连着 不重复探活`() {
        assertFalse(
            "进了 App 之后事件流（eventsConnected）盯着链路，再发健康检查是重复的",
            shouldRecheckActiveConnection(
                connectGatePassed = true,
                connected = true,
                hasActiveConnection = true,
                connecting = false,
            ),
        )
    }

    @Test
    fun `已进 App 但状态已是未连接 仍要复核`() {
        // 这条是从 `connectGatePassed && connected` 那个朴素的早退判据里救回来的：
        // 用户进了 App、断网被 markDisconnected 之后**退回连接页**，此时门闩仍是通过的，
        // 但 connected 已经是 false —— 必须继续复核，否则界面会一直停在「未连接」而不再自愈。
        assertTrue(
            shouldRecheckActiveConnection(
                connectGatePassed = true,
                connected = false,
                hasActiveConnection = true,
                connecting = false,
            ),
        )
    }

    @Test
    fun `没有活动连接时不复核`() {
        assertFalse(
            "冷启动还没连上 / 用户主动断开过：没有可复核的对象",
            shouldRecheckActiveConnection(
                connectGatePassed = false,
                connected = false,
                hasActiveConnection = false,
                connecting = false,
            ),
        )
    }

    @Test
    fun `正在建连时不复核`() {
        assertFalse(
            "并发探活/建连自己会写权威结果，别抢",
            shouldRecheckActiveConnection(
                connectGatePassed = false,
                connected = false,
                hasActiveConnection = true,
                connecting = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 按钮要不要重连

    @Test
    fun `当前活跃且客户端还在 点按钮不重连（纯进入）`() {
        assertFalse(
            shouldReconnectOnTap(
                hostId = "h1",
                activeHostId = "h1",
                hasActiveConnection = true,
            ),
        )
    }

    @Test
    fun `当前活跃但客户端已丢 点按钮必须重连`() {
        // 这就是真机上「点了没反应」的现场：state.connected 可能还是 true，
        // 但 active 已经被清掉（disconnect）或从未建立。若沿用 connected 当判据，
        // 这里会被短路成「直接进去」，随后被门闩弹回 —— 一件事都没发生。
        assertTrue(
            shouldReconnectOnTap(
                hostId = "h1",
                activeHostId = "h1",
                hasActiveConnection = false,
            ),
        )
    }

    @Test
    fun `点另一台电脑 必须重连`() {
        assertTrue(
            shouldReconnectOnTap(
                hostId = "h2",
                activeHostId = "h1",
                hasActiveConnection = true,
            ),
        )
    }

    @Test
    fun `没有任何活跃主机时 点任意一台都重连`() {
        assertTrue(
            shouldReconnectOnTap(
                hostId = "h1",
                activeHostId = null,
                hasActiveConnection = false,
            ),
        )
    }

    // ---------------------------------------------------------------- 卡片三态

    @Test
    fun `活跃且连着 是已连接`() {
        assertEquals(HostCardState.CONNECTED, hostCardState("h1", "h1", connected = true))
    }

    @Test
    fun `活跃但断了 是已断开（不能显示成没连过）`() {
        // 真机缺陷的直接体现：这两种之前都没有状态点，屏幕上完全一样。
        assertEquals(HostCardState.DISCONNECTED, hostCardState("h1", "h1", connected = false))
    }

    @Test
    fun `不是当前活跃的那台 一律是空闲态`() {
        assertEquals(HostCardState.IDLE, hostCardState("h2", "h1", connected = true))
        assertEquals(HostCardState.IDLE, hostCardState("h2", "h1", connected = false))
        assertEquals(HostCardState.IDLE, hostCardState("h2", null, connected = false))
    }

    @Test
    fun `三态在同一台电脑上互斥且覆盖`() {
        // 穷举一个 hostId 的全部输入组合：(activeHostId ∈ {自身, 别的, null}) × (connected ∈ {true, false})
        val combos = listOf("h1", "h2", null).flatMap { active ->
            listOf(true, false).map { connected -> active to connected }
        }
        val states = combos.map { (active, connected) -> hostCardState("h1", active, connected) }
        // 只该出现两种：自身 → CONNECTED/DISCONNECTED；别的或 null → IDLE。
        assertTrue("自身+连着必须是 CONNECTED", hostCardState("h1", "h1", true) == HostCardState.CONNECTED)
        assertTrue("自身+断了必须是 DISCONNECTED", hostCardState("h1", "h1", false) == HostCardState.DISCONNECTED)
        assertEquals(
            "h1 只有「自身」这两个组合不是 IDLE，其余 ${combos.size} 个组合全是 IDLE",
            combos.size - 2,
            states.count { it == HostCardState.IDLE },
        )
    }
}
