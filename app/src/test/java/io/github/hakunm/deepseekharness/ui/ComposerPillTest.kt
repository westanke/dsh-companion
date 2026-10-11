package io.github.hakunm.deepseekharness.ui

import org.junit.Assert.assertEquals
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
}
