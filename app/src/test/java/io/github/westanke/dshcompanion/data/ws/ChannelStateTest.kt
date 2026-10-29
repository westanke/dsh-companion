package io.github.westanke.dshcompanion.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级状态机（设计 §3.2）的判据。
 *
 * 这些断言的价值在于**顺序**：`RestOnly` 与 `WsDown` 混淆会让「老桥」被无限重连
 * （每秒打一次永远 404 的 upgrade），而 `WsDegraded` 与 `WsDown` 混淆会让「缺能力位」
 * 每次都触发一轮无意义的重连。两条都是真机上很难看出来、只能靠日志猜的故障。
 */
class ChannelStateTest {

    // ───────────────────────── hello 之后 ─────────────────────────

    @Test
    fun `hello 齐备时进 WsReady`() {
        val state = decideAfterHello(
            protocol = 3,
            capabilities = setOf("session-list-v1", "history-v1", "turn-cancel-v1"),
            required = setOf("session-list-v1", "history-v1", "turn-cancel-v1"),
        )
        assertEquals(ChannelState.WsReady, state)
    }

    @Test
    fun `hello 多给能力位不影响判定`() {
        val state = decideAfterHello(
            protocol = 3,
            capabilities = setOf("session-list-v1", "history-v1", "turn-cancel-v1", "approvals-v1", "questions-v1"),
            required = setOf("session-list-v1"),
        )
        assertEquals(ChannelState.WsReady, state)
    }

    @Test
    fun `缺能力位进 WsDegraded 且 reason 列出缺的位名`() {
        val state = decideAfterHello(
            protocol = 3,
            capabilities = setOf("session-list-v1"),
            required = setOf("session-list-v1", "history-v1", "turn-cancel-v1"),
        )
        assertTrue(state is ChannelState.WsDegraded)
        val reason = (state as ChannelState.WsDegraded).reason
        assertTrue("reason 里应列出 history-v1：$reason", reason.contains("history-v1"))
        assertTrue("reason 里应列出 turn-cancel-v1：$reason", reason.contains("turn-cancel-v1"))
        assertFalse("已具备的位不该出现在缺位里：$reason", reason.contains("session-list-v1"))
    }

    @Test
    fun `缺位列表按字典序稳定排序`() {
        val state = decideAfterHello(
            protocol = 3,
            capabilities = emptySet(),
            required = setOf("turn-cancel-v1", "history-v1", "session-list-v1"),
        ) as ChannelState.WsDegraded
        assertEquals("缺少能力位：history-v1,session-list-v1,turn-cancel-v1", state.reason)
    }

    @Test
    fun `协议版本过低进 WsDegraded 而不是 RestOnly`() {
        // 关键区分：协议版本低是「连上了但谈不拢」，不是「没有这条通道」——
        // 判成 RestOnly 会永久放弃 WS，而桥升级后本可以恢复。
        val state = decideAfterHello(
            protocol = 2,
            capabilities = setOf("session-list-v1", "history-v1", "turn-cancel-v1"),
            required = setOf("session-list-v1"),
        )
        assertTrue(state is ChannelState.WsDegraded)
        assertTrue((state as ChannelState.WsDegraded).reason.contains("协议版本 2"))
    }

    @Test
    fun `required 为空集时任何能力位都算齐`() {
        val state = decideAfterHello(protocol = 3, capabilities = emptySet(), required = emptySet())
        assertEquals(ChannelState.WsReady, state)
    }

    // ───────────────────────── upgrade 被拒 ─────────────────────────

    @Test
    fun `404 405 426 400 501 都是确定性拒绝`() {
        listOf(404, 405, 426, 400, 501).forEach { code ->
            assertTrue("HTTP $code 应判为确定性拒绝", isDefinitiveUpgradeRejection(code))
        }
    }

    @Test
    fun `401 403 与 5xx 不是确定性拒绝`() {
        // 鉴权失败可能是令牌轮换，5xx 可能是桥在重启 —— 两者都该退避重连，不能判死。
        listOf(401, 403, 500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code 不该判为确定性拒绝", isDefinitiveUpgradeRejection(code))
        }
    }

    @Test
    fun `101 与 200 不是拒绝`() {
        assertFalse(isDefinitiveUpgradeRejection(101))
        assertFalse(isDefinitiveUpgradeRejection(200))
    }

    // ───────────────────────── 子协议被拒 ─────────────────────────

    @Test
    fun `hello 之前的 4001 判为子协议被拒`() {
        assertTrue(isProtocolRejected(closeCode = 4001, helloSeen = false))
    }

    @Test
    fun `hello 之后的 4001 是断线重连而不是通道不存在`() {
        // 令牌被服务端撤销时也是 4001。那种情况该重连（用户可能重新配对），
        // 判成 RestOnly 会让 App 在用户重新配对后依然拒绝用 WS。
        assertFalse(isProtocolRejected(closeCode = 4001, helloSeen = true))
    }

    @Test
    fun `正常关闭码不是子协议被拒`() {
        listOf(1000, 1001, 1006, 1011).forEach { code ->
            assertFalse("close code $code 不该判为子协议被拒", isProtocolRejected(code, helloSeen = false))
        }
    }

    // ───────────────────────── 派生属性 ─────────────────────────

    @Test
    fun `WsReady 与 WsDegraded 都算能用 WS`() {
        assertTrue(ChannelState.WsReady.usesWs)
        assertTrue(ChannelState.WsDegraded("x").usesWs)
    }

    @Test
    fun `WsDown 与 RestOnly 不能用 WS`() {
        assertFalse(ChannelState.WsDown("x").usesWs)
        assertFalse(ChannelState.RestOnly.usesWs)
    }

    @Test
    fun `只有 RestOnly 是终态`() {
        assertTrue(ChannelState.RestOnly.isTerminal)
        assertFalse(ChannelState.WsReady.isTerminal)
        assertFalse(ChannelState.WsDegraded("x").isTerminal)
        assertFalse(ChannelState.WsDown("x").isTerminal)
    }

    @Test
    fun `data class 状态按 reason 相等 data object 按单例`() {
        assertEquals(ChannelState.WsDegraded("a"), ChannelState.WsDegraded("a"))
        assertFalse(ChannelState.WsDegraded("a") == ChannelState.WsDegraded("b"))
        assertEquals(ChannelState.WsDown("a"), ChannelState.WsDown("a"))
        assertEquals(ChannelState.WsReady, ChannelState.WsReady)
    }
}
