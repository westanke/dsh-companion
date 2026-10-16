package io.github.westanke.dshcompanion.ui

import io.github.westanke.dshcompanion.ui.chat.composerHint
import io.github.westanke.dshcompanion.ui.chat.ComposerHint
import io.github.westanke.dshcompanion.ui.chat.hasPhysicalKeyboard
import io.github.westanke.dshcompanion.ui.chat.isSoftKeyboardEvent
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单行药丸输入区里两条**看不见的规则**。
 *
 * 为什么值得测：这次改版把「运行中回车会排队还是插话」从输入框下方那一行独立 Text
 * 挪进了占位符（照网页版 chat.html:1399 直接改 textarea 的 placeholder）。
 * 于是占位符成了唯一说明，而它选错资源在屏幕上只是「一行小字不太对」——
 * 真机扫一眼很难发现，所以把「选哪一段」钉在这里。
 *
 * 只测纯函数，不测 Compose 布局：药丸是一行、+ / 输入框 / 发送三个入口还在，
 * 是布局事实，靠阅读与真机验证，单测替代不了。
 */
class ComposerPillTest {

    @Test
    fun idleAlwaysUsesThePlainPlaceholder() {
        // 空闲时没有可插话的轮次，「繁忙时默认行为」设置不该影响占位符。
        assertEquals(ComposerHint.IDLE, composerHint(sessionRunning = false, steerIsDefault = false))
        assertEquals(ComposerHint.IDLE, composerHint(sessionRunning = false, steerIsDefault = true))
    }

    @Test
    fun runningUsesTheSteerPlaceholderWhenSteerIsTheDefault() {
        assertEquals(ComposerHint.RUNNING_STEER, composerHint(sessionRunning = true, steerIsDefault = true))
    }

    @Test
    fun runningUsesTheQueuePlaceholderWhenQueueIsTheDefault() {
        assertEquals(ComposerHint.RUNNING_QUEUE, composerHint(sessionRunning = true, steerIsDefault = false))
    }

    // ── 外接物理键盘：空闲文案追加「Ctrl+Enter 发送」 ──

    @Test
    fun idleWithPhysicalKeyboardAppendsTheCtrlEnterHint() {
        // 软键盘的换行键与物理 Enter 在 onKeyEvent 里派发同一个 Key.Enter，事件分不出来源，
        // 所以 Enter 一律换行、发送只走 Ctrl+Enter —— 这条规则不写进占位符，
        // 平板 + 外接键盘的用户根本不知道该怎么发送。
        assertEquals(
            ComposerHint.IDLE_PHYSICAL,
            composerHint(sessionRunning = false, steerIsDefault = false, physicalKeyboard = true),
        )
        assertEquals(
            ComposerHint.IDLE_PHYSICAL,
            composerHint(sessionRunning = false, steerIsDefault = true, physicalKeyboard = true),
        )
    }

    @Test
    fun runningHintIgnoresThePhysicalKeyboard() {
        // 运行中的两段文案本身已在说「发送即插话/排队」，再拼一段会超出占位符的一行容量。
        assertEquals(
            ComposerHint.RUNNING_STEER,
            composerHint(sessionRunning = true, steerIsDefault = true, physicalKeyboard = true),
        )
        assertEquals(
            ComposerHint.RUNNING_QUEUE,
            composerHint(sessionRunning = true, steerIsDefault = false, physicalKeyboard = true),
        )
    }

    @Test
    fun physicalKeyboardDefaultsToFalseSoPhonesKeepTheShortHint() {
        // 默认值 = false：不传该参数的老调用点（手机路径）行为不变。
        assertEquals(
            ComposerHint.IDLE,
            composerHint(sessionRunning = false, steerIsDefault = false),
        )
    }

    // ── hasPhysicalKeyboard：Configuration.keyboard 三态 ──

    @Test
    fun qwertyKeyboardCountsAsPhysical() {
        assertTrue(hasPhysicalKeyboard(Configuration().apply { keyboard = Configuration.KEYBOARD_QWERTY }))
    }

    @Test
    fun touchOnlyKeyboardIsNotPhysical() {
        assertFalse(hasPhysicalKeyboard(Configuration().apply { keyboard = Configuration.KEYBOARD_NOKEYS }))
    }

    @Test
    fun undefinedKeyboardFallsBackToSoftKeyboard() {
        // 不确定时按软键盘处理：提示短一点不会错，误报「有物理键盘」才会打扰触摸用户。
        assertFalse(hasPhysicalKeyboard(Configuration().apply { keyboard = Configuration.KEYBOARD_UNDEFINED }))
    }

    // ── isSoftKeyboardEvent：按键事件来源判据 ──

    @Test
    fun softKeyboardFlagMarksImeEvents() {
        // FLAG_SOFT_KEYBOARD = 2：IME 通过 sendKeyEvent 送进来的事件都带它。
        assertTrue(isSoftKeyboardEvent(android.view.KeyEvent.FLAG_SOFT_KEYBOARD))
        // 与别的 flag 并存时也要认出来（IME 可能同时带 FLAG_FROM_SYSTEM 等）。
        assertTrue(
            isSoftKeyboardEvent(
                android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_FROM_SYSTEM,
            ),
        )
    }

    @Test
    fun physicalKeyboardEventsDoNotCarryTheSoftFlag() {
        // 外接键盘：不带 FLAG_SOFT_KEYBOARD（这才是能当 Ctrl+Enter 用的事件）。
        assertFalse(isSoftKeyboardEvent(0))
        assertFalse(isSoftKeyboardEvent(android.view.KeyEvent.FLAG_FROM_SYSTEM))
        assertFalse(isSoftKeyboardEvent(android.view.KeyEvent.FLAG_EDITOR_ACTION))
    }
}

