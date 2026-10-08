package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按下发送之后走哪条路」的决策测试。
 *
 * 这段逻辑此前根本不存在：旧的 Composer 用全局 `state.busy` 去禁用两个发送按钮，
 * 于是智能体运行时**既不能排队也不能插话**（发送键还会变成转圈）。把它抽出来单测，
 * 是为了让「空闲 / 运行中 × 默认 / 反向」这几个组合有据可查，而不是靠手点验证。
 */
class ComposerSendPolicyTest {

    @Test
    fun idleAlwaysQueuesEvenWhenPreferenceIsSteer() {
        // 空闲时没有正在运行的一轮可插话，steer 无意义，必须回落到 queue（等价普通发送）。
        assertEquals(BusySendMode.QUEUE, ComposerSendPolicy.resolve(busy = false, preferred = BusySendMode.STEER))
        assertEquals(BusySendMode.QUEUE, ComposerSendPolicy.resolve(busy = false, preferred = BusySendMode.QUEUE))
    }

    @Test
    fun idleIgnoresTheAlternateKeyToo() {
        // 空闲时按 Ctrl/Cmd+Enter 也不该产生 steer —— 没有可插话的轮次。
        assertEquals(
            BusySendMode.QUEUE,
            ComposerSendPolicy.resolve(busy = false, preferred = BusySendMode.STEER, alternate = true),
        )
    }

    @Test
    fun runningUsesTheConfiguredPreference() {
        assertEquals(BusySendMode.QUEUE, ComposerSendPolicy.resolve(busy = true, preferred = BusySendMode.QUEUE))
        assertEquals(BusySendMode.STEER, ComposerSendPolicy.resolve(busy = true, preferred = BusySendMode.STEER))
    }

    @Test
    fun theAlternateKeyFlipsTheBehaviourWhileRunning() {
        // 这正是「Cmd/Ctrl+Enter 使用另一行为」的可验证形式。
        assertEquals(
            BusySendMode.STEER,
            ComposerSendPolicy.resolve(busy = true, preferred = BusySendMode.QUEUE, alternate = true),
        )
        assertEquals(
            BusySendMode.QUEUE,
            ComposerSendPolicy.resolve(busy = true, preferred = BusySendMode.STEER, alternate = true),
        )
    }

    @Test
    fun oppositeIsAnInvolution() {
        assertEquals(BusySendMode.QUEUE, BusySendMode.STEER.opposite())
        assertEquals(BusySendMode.STEER, BusySendMode.QUEUE.opposite())
        assertEquals(BusySendMode.QUEUE, BusySendMode.QUEUE.opposite().opposite())
    }

    @Test
    fun canSubmitRequiresBothTextAndWritePermission() {
        assertTrue(ComposerSendPolicy.canSubmit("hi", canWrite = true))
        assertFalse(ComposerSendPolicy.canSubmit("", canWrite = true))
        // 只有空白也不算内容。
        assertFalse(ComposerSendPolicy.canSubmit("   \n ", canWrite = true))
        assertFalse(ComposerSendPolicy.canSubmit("hi", canWrite = false))
    }
}
