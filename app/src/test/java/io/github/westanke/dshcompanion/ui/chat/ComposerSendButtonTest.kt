package io.github.westanke.dshcompanion.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发送键三态的回归测试（`composerSendButton`）。
 *
 * ## 用户定的规则（2026-10-19）
 *
 * 「发送按钮应该是个停止的方形，但只有输入框无文字的时候。有文字或附件就变回发送，
 * 只有无内容时才点一下停止会话。」
 *
 * 这三条**有优先级**，抽成纯函数就是为了把优先级钉死 —— 写在 UI 的内联 `if` 里，
 * 后人换个顺序顺手就改错了，而屏幕上看不出区别，要等用户在运行中打字才发现
 * 「我的字发不出去」。
 */
class ComposerSendButtonTest {

    @Test
    fun sendsWheneverThereIsSomethingToSendEvenWhileRunning() {
        // 最关键的一条：**有内容优先于「运行中」**。
        assertEquals(ComposerSendButton.SEND, composerSendButton(sessionRunning = true, canSubmit = true))
        assertEquals(ComposerSendButton.SEND, composerSendButton(sessionRunning = false, canSubmit = true))
    }

    @Test
    fun stopsOnlyWhenRunningAndTheComposerIsEmpty() {
        assertEquals(ComposerSendButton.STOP, composerSendButton(sessionRunning = true, canSubmit = false))
    }

    @Test
    fun staysDisabledWhenIdleAndEmpty() {
        // 空闲 + 空：透明底灰箭头，点了不该有反应（更不该变成停止 —— 没东西可停）。
        assertEquals(ComposerSendButton.DISABLED, composerSendButton(sessionRunning = false, canSubmit = false))
    }
}
