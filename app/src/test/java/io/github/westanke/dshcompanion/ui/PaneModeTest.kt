package io.github.westanke.dshcompanion.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宽度档位的边界回归。
 *
 * ## 为什么这条测试值得存在
 *
 * 改造前 `App.kt` 是一个裸布尔 `val wide = maxWidth >= 840.dp`。把它收成三档纯函数时，
 * 唯一不能出错的事是：**手机端（< 840dp）必须仍然走 `wide=false`**。
 * 一旦阈值写错一个像素，手机上的抽屉/分栏就会切换到平板布局 —— 而那属于
 * AGENTS.md 里那条「手机端既有视觉零改动」的硬约束，靠肉眼绝对看不出来。
 *
 * 所以这里把**每个档位的两侧边界**都钉住，且额外断言 840 这个语义等价性：
 * `isWideLayout(w) == (w >= 840.dp)` 对一批代表性宽度逐个成立。
 */
class PaneModeTest {

    @Test
    fun `四个档位的边界逐一对应`() {
        // Compact 上界：599 还是手机、600 进入 Medium。
        assertEquals(PaneMode.Compact, paneModeFor(0.dp))
        assertEquals(PaneMode.Compact, paneModeFor(360.dp))
        assertEquals(PaneMode.Compact, paneModeFor(599.dp))
        // Medium：600–839（手机横屏、小平板分屏）。
        assertEquals(PaneMode.Medium, paneModeFor(600.dp))
        assertEquals(PaneMode.Medium, paneModeFor(700.dp))
        assertEquals(PaneMode.Medium, paneModeFor(839.dp))
        // Expanded：840–1199 —— **840 是既有 wide 的下界，不得改动**。
        assertEquals(PaneMode.Expanded, paneModeFor(840.dp))
        assertEquals(PaneMode.Expanded, paneModeFor(1000.dp))
        assertEquals(PaneMode.Expanded, paneModeFor(1199.dp))
        // Large：1200+（WindowManager 1.5 的 large 档起点）。
        assertEquals(PaneMode.Large, paneModeFor(1200.dp))
        assertEquals(PaneMode.Large, paneModeFor(1600.dp))
    }

    /**
     * **核心等价性**：`isWideLayout` 必须与改造前的 `maxWidth >= 840.dp` 逐一相同。
     *
     * 这条是「手机端零改动」在代码层的唯一机检 —— 逐点比对而不是只测边界，
     * 是因为判据将来若被改成 `>` 或别的常量，边界测试可能恰好仍通过。
     */
    @Test
    fun `isWideLayout 与原 840dp 判据二进制等价`() {
        val widths = listOf(
            320.dp, 360.dp, 411.dp, 412.dp, 480.dp, 599.dp, 600.dp, 720.dp,
            839.dp, 840.dp, 841.dp, 1024.dp, 1199.dp, 1200.dp, 1600.dp, 2000.dp,
        )
        for (width in widths) {
            val expected = width >= 840.dp
            assertEquals(
                "宽度 $width 的 wide 判据与改造前的 `maxWidth >= 840.dp` 不一致",
                expected,
                isWideLayout(width),
            )
        }
    }

    /** 手机上的两个真实宽度必须**永远**不是 wide（AGENTS.md 的硬约束）。 */
    @Test
    fun `360 与 412 的手机宽度不是 wide`() {
        assertFalse(isWideLayout(360.dp))
        assertFalse(isWideLayout(412.dp))
    }

    /** Medium（600–839）也不算 wide：它是「宽一点的手机 / 分屏」，仍走手机布局。 */
    @Test
    fun `Medium 档不启用平板布局`() {
        assertFalse(isWideLayout(600.dp))
        assertFalse(isWideLayout(839.dp))
        assertTrue(isWideLayout(840.dp))
    }
}
