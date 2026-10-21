package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.effectiveFontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置（主题三档 + 字号缩放）的纯逻辑回归。
 *
 * 为什么这几条值得单测：它们都是**取错值不会崩、只会悄悄难看**的地方 ——
 * NaN 字号让整个界面白屏、跟随系统写反导致双重放大、枚举按 ordinal 存导致
 * 升级后深浅色互换。这几类问题在真机上都要靠「用户截图 + 我们猜」才能定位，
 * 而它们本质上是纯函数，一条断言就能钉死。
 */
class AppearancePreferencesTest {

    // ——— normalizeFontScale：偏好层的兜底 ———

    @Test
    fun `字号上限被收敛到 1_5`() {
        assertEquals(1.5f, normalizeFontScale(2.0f), 0.0001f)
    }

    @Test
    fun `字号下限被收敛到 0_8`() {
        assertEquals(0.8f, normalizeFontScale(0.2f), 0.0001f)
    }

    @Test
    fun `区间内的字号原样保留`() {
        assertEquals(1.0f, normalizeFontScale(1.0f), 0.0001f)
        assertEquals(1.3f, normalizeFontScale(1.3f), 0.0001f)
    }

    /**
     * NaN / Inf 必须回落到 1f。
     *
     * 这一条是**白屏防线**：坏值若进了 Compose 的 Density，界面会以 NaN 尺寸
     * 测量——不崩溃、没日志，只是什么都不显示。那种症状最难定位。
     */
    @Test
    fun `非法字号回落到 1f`() {
        assertEquals(1f, normalizeFontScale(Float.NaN), 0.0001f)
        assertEquals(1f, normalizeFontScale(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1f, normalizeFontScale(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    // ——— InterfaceStyle：三档主题的判据 ———

    @Test
    fun `跟随系统时直接取系统值`() {
        assertTrue(InterfaceStyle.SYSTEM.isDark(systemDark = true))
        assertFalse(InterfaceStyle.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `强制浅色时忽略系统值`() {
        assertFalse(InterfaceStyle.LIGHT.isDark(systemDark = true))
    }

    @Test
    fun `强制深色时忽略系统值`() {
        assertTrue(InterfaceStyle.DARK.isDark(systemDark = false))
    }

    // ——— 生效字号：界面读的那一个值 ———

    @Test
    fun `勾选跟随系统时生效字号是 1f`() {
        val state = HarnessState(fontScale = 1.4f, fontFollowsSystem = true)
        assertEquals(1f, state.effectiveFontScale, 0.0001f)
    }

    @Test
    fun `未勾选时生效字号就是偏好值`() {
        val state = HarnessState(fontScale = 1.4f, fontFollowsSystem = false)
        assertEquals(1.4f, state.effectiveFontScale, 0.0001f)
    }

    /**
     * 默认状态必须是「不放大」：升级到本版本的用户此前没有这两个字段，
     * 默认值若取成非 1f，所有人打开 App 都会发现字变大了。
     */
    @Test
    fun `默认状态不放大也不强制主题`() {
        val state = HarnessState()
        assertEquals(1f, state.effectiveFontScale, 0.0001f)
        assertEquals(InterfaceStyle.SYSTEM, state.interfaceStyle)
        assertFalse(state.fontFollowsSystem)
    }

    /**
     * 配色主题的默认值必须是 PAPER。
     *
     * 与上面那条同一个理由，但多一层分量：PAPER 是「与改造前逐字段相同」的那套配色，
     * 默认值一旦漂成别的主题，**所有用户升级后第一眼就会看到界面变样** ——
     * 而这是本项目「新增可选外观而不是改版」的承诺。
     * （PAPER 本身是否仍等于旧色值，由 `AppThemeTest` 逐字段盯着。）
     */
    @Test
    fun `默认配色主题是 PAPER`() {
        assertEquals(AppTheme.PAPER, HarnessState().appTheme)
    }
}
