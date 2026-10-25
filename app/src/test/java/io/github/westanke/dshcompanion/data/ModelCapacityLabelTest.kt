package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatContextWindow] 的边界。
 *
 * 为什么值得单独测：这个函数决定模型行里那行小字的全部内容，而它的三个分支
 * （M / K / 原值）各自的进位点与小数位是**写死**的产品口径 —— 一旦有人把 1024
 * 改成 1000，`1048576` 就会从 `1M` 变成 `1049K`，而这种退化在界面上看着「还挺正常」，
 * 没人会报 bug。
 */
class ModelCapacityLabelTest {
    @Test
    fun `shows a megatoken window as one M`() {
        // DSH 0.2.1 的 deepseek-flash 就是这个数。
        assertEquals("1.0M", formatContextWindow(1_048_576))
    }

    @Test
    fun `keeps one decimal below ten million tokens`() {
        assertEquals("1.5M", formatContextWindow(1_572_864))
    }

    @Test
    fun `rounds to whole millions at ten and above`() {
        assertEquals("16M", formatContextWindow(16_777_216))
    }

    @Test
    fun `falls back to K under a million`() {
        assertEquals("128K", formatContextWindow(131_072))
    }

    @Test
    fun `keeps one decimal below ten thousand tokens`() {
        assertEquals("1.5K", formatContextWindow(1_536))
    }

    @Test
    fun `prints small windows verbatim`() {
        assertEquals("512", formatContextWindow(512))
    }

    @Test
    fun `uses 1024 as the step, not 1000`() {
        // 1000 会落进「原值」分支；1024 才是 1K。这条钉的是进制选择本身。
        assertEquals("1.0K", formatContextWindow(1024))
        assertEquals("1000", formatContextWindow(1000))
    }
}
