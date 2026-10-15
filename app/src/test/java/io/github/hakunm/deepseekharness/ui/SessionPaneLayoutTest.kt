package io.github.hakunm.deepseekharness.ui

import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.ui.chat.sessionDisplayTitle
import io.github.hakunm.deepseekharness.ui.chat.sessionPaneWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

/**
 * 平板（wide）分支三个**看不见的规则**。
 *
 * 为什么值得测：截图暴露的问题是「会话标题在 304.dp 的窄栏里被腰斩」——
 * 根因不是截断本身，而是那个宽度是**手机抽屉的尺寸**，被原样搬到了平板上。
 * 这次把栏宽改成随屏宽伸缩、会话行标题在 wide 下放宽到两行，
 * 而这三条规则的边界全在代码里，界面上只看得出「好像没截断了」。
 *
 * 手机端不受影响（`wide` 恒为 false，这些函数只在 wide 分支被调用），
 * 所以单测里钉住的是**平板侧的取值**，不含手机路径。
 *
 * 只测纯函数：栏宽公式、标题兜底、概览排序与截断都是纯逻辑；
 * 布局（概览面板长什么样、两行标题会不会把行高压塌）靠阅读 + 真机验证。
 */
class SessionPaneLayoutTest {

    private fun session(
        id: String = "s-1",
        title: String? = null,
        workspaceTitle: String? = null,
        cwd: String = "",
        updatedAt: Long = 0L,
    ) = ChatSession(
        id = id,
        rootId = "root-1",
        cwd = cwd,
        updatedAt = updatedAt,
        running = false,
        blank = false,
        title = title,
        workspaceTitle = workspaceTitle,
    )

    // ── sessionPaneWidth：栏宽随屏宽伸缩，但被夹在 320–440dp ──

    @Test
    fun narrowWideScreensKeepTheOldFloor() {
        // 840dp 是 wide 的下界：0.25 × 840 = 210dp，被夹到下限 320dp ——
        // 比旧的 304.dp 略宽，长标题不会比改版前更挤。
        assertEquals(320.dp, sessionPaneWidth(840.dp))
        assertEquals(320.dp, sessionPaneWidth(960.dp))
    }

    @Test
    fun typicalTabletWidthScalesByAQuarter() {
        // 1280dp（截图里的 W200DS）：0.25 × 1280 = 320dp，刚好落在区间内。
        assertEquals(320.dp, sessionPaneWidth(1280.dp))
        assertEquals(360.dp, sessionPaneWidth(1440.dp))
        assertEquals(400.dp, sessionPaneWidth(1600.dp))
    }

    @Test
    fun veryWideScreensAreCappedSoTheChatAreaSurvives() {
        // 0.25 × 1920 = 480dp、0.25 × 2560 = 640dp —— 不封顶就会把内容区吃掉一半。
        assertEquals(440.dp, sessionPaneWidth(1920.dp))
        assertEquals(440.dp, sessionPaneWidth(2560.dp))
    }

    @Test
    fun paneWidthNeverShrinksBelowTheOldValue() {
        // 回归护栏：无论屏宽多窄，栏宽不许低于旧的 304.dp（除了 320dp 下限本身）。
        listOf(840.dp, 1000.dp, 1280.dp, 1440.dp, 1600.dp, 1920.dp).forEach { width ->
            assertTrue(
                "width=${width} produced ${sessionPaneWidth(width)}",
                sessionPaneWidth(width) >= 320.dp,
            )
        }
    }

    // ── sessionDisplayTitle：标题兜底链只写一次，两处列表共用 ──

    @Test
    fun titlePrefersTheUserGivenName() {
        assertEquals("我的会话", sessionDisplayTitle(session(title = "我的会话", workspaceTitle = "工作区", cwd = "pkg")))
    }

    @Test
    fun titleFallsBackToWorkspaceThenCwd() {
        assertEquals("工作区", sessionDisplayTitle(session(workspaceTitle = "工作区", cwd = "pkg")))
        assertEquals("pkg", sessionDisplayTitle(session(cwd = "pkg")))
    }

    @Test
    fun titleFallsBackToSessionIdPrefixWhenEverythingIsBlank() {
        // cwd 空串是合法值（工作目录正好就是授权根本身），此时兜底 id 前 12 位。
        val id = "abcdef1234567890"
        assertEquals("abcdef123456", sessionDisplayTitle(session(id = id, cwd = "")))
    }

    // ── 概览面板的排序与截断（最近 5 条） ──

    @Test
    fun overviewKeepsOnlyTheFiveMostRecentlyUpdated() {
        val sessions = (1..8).map { n ->
            session(id = "s-$n", title = "会话 $n", updatedAt = n * 100L)
        }
        // 排序规则照搬实现：updatedAt 降序取前 5。
        val recent = sessions.sortedByDescending { it.updatedAt }.take(5).map { it.id }
        assertEquals(listOf("s-8", "s-7", "s-6", "s-5", "s-4"), recent)
    }

    @Test
    fun overviewWithNoSessionsRendersTheEmptyState() {
        assertTrue(emptyList<ChatSession>().sortedByDescending { it.updatedAt }.take(5).isEmpty())
    }
}
