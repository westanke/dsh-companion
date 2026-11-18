package io.github.westanke.dshcompanion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉灰幕的命中判据（#21）。
 *
 * 这组用例钉的是 2026-11-18 那个真机 bug 的根因：
 * 一层 alpha = 0 的透明灰幕，因为带着 `.clickable`（= 一次 pointerInput），
 * 在抽屉关闭状态下仍然参与命中测试，把手机端右缘 216px（x ≥ 864px）的点击全吃了 ——
 * 会话详情页「当前任务」面板右端的折叠箭头、顶栏右上角的 ⋯ 菜单、各页面右上角
 * 图标按钮，全都点不动。
 *
 * 判据收成纯函数就是为了让这件事能被单测盖住。
 */
class DrawerScrimHitTestTest {

    @Test
    fun 抽屉关闭且动画已结束时灰幕既不画也不吃点击() {
        val state = drawerScrimState(drawerOpen = false, drawerShift = 0f)
        assertFalse(state.visible)
        assertFalse(state.catchesTaps)
    }

    @Test
    fun 抽屉关闭但滑动动画还在跑时灰幕继续画但不吃点击() {
        // 关抽屉后 drawerOpen 立刻变 false，滑动动画还要跑 250ms。
        // 这段时间灰幕必须继续画（深色主题下否则会突兀闪一下），
        // 但必须**已经不吃点击** —— 否则动画结束前那一下点击还是会被吃掉。
        val state = drawerScrimState(drawerOpen = false, drawerShift = 0.5f)
        assertTrue(state.visible)
        assertFalse(state.catchesTaps)
    }

    @Test
    fun 抽屉关闭且滑动刚起步时灰幕不吃点击() {
        val state = drawerScrimState(drawerOpen = false, drawerShift = 0.02f)
        assertTrue(state.visible)
        assertFalse(state.catchesTaps)
    }

    @Test
    fun 抽屉打开时灰幕既画也吃点击() {
        val state = drawerScrimState(drawerOpen = true, drawerShift = 1f)
        assertTrue(state.visible)
        assertTrue(state.catchesTaps)
    }

    @Test
    fun 抽屉刚打开还没滑开时也吃点击() {
        // 抽屉刚被点开、动画还没起步（shift 还是 0），点右侧应该能关掉抽屉。
        val state = drawerScrimState(drawerOpen = true, drawerShift = 0f)
        assertTrue(state.visible)
        assertTrue(state.catchesTaps)
    }

    @Test
    fun 抽屉关闭时无论滑动进度多少都不吃点击() {
        // 这是 #21 的核心断言：抽屉一关，命中就必须整个还给内容页，
        // 跟滑动进度无关（不能出现「动画某一帧又开始吃点击」）。
        for (shift in listOf(0f, 0.01f, 0.3f, 0.99f, 1f)) {
            val state = drawerScrimState(drawerOpen = false, drawerShift = shift)
            assertFalse("shift=$shift 时不该吃点击", state.catchesTaps)
        }
    }

    @Test
    fun 可见性判据在滑动进度回到零后不再成立() {
        assertFalse(drawerScrimState(drawerOpen = false, drawerShift = 0.01f).visible)
        assertTrue(drawerScrimState(drawerOpen = false, drawerShift = 0.02f).visible)
    }

    @Test
    fun 灰幕状态的两个字段可以独立取值() {
        // visible 与 catchesTaps 必须是两条独立判据：
        // 「还在滑但抽屉已关」这个组合下两者必须不同（画但不点）。
        val closing = drawerScrimState(drawerOpen = false, drawerShift = 0.4f)
        assertEquals(closing.visible, true)
        assertEquals(closing.catchesTaps, false)
    }
}
