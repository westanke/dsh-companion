package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限档位投影的回归测试（盯 `ChatPresentation.permissionSelect` 的 `@patch`）。
 *
 * ## 这条缺陷的真机症状
 *
 * v2.2.44 真机验收：发送区上方**没有**权限档位 Pill，顶栏 `⋯` 菜单里也**没有**权限项。
 * 而 `PARITY.md` 当时把它标成了 `done` —— 那是假的。
 *
 * 根因是两层的，缺一不可：
 *  1. 内核那条 `permissions` wire 视图是 `wire.view: state => ({ currentValue })`，
 *     **只产当前值**；候选项在另一个远程服务 `permissionPresets.catalog()` 上。
 *  2. 旧实现从投影里读 `options[]`，读不到 → `permissionSelect()` 恒为 null，
 *     而 Pill 的 `if (permission == null || permission.options.isEmpty()) return`
 *     把「读不到候选项」升级成「整个入口不渲染」。
 *
 * 所以现在的约定是：**当前值来自投影，候选项来自目录接口，两者任一缺失都不该让入口消失。**
 */
class PermissionProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun historyWithPermissions(permissionsJson: String): ChatHistory = ChatHistory(
        events = emptyList(),
        hasMore = false,
        projections = json.parseToJsonElement("""{"values":{"permissions":$permissionsJson}}"""),
    )

    @Test
    fun readsTheLegacyShapeWithCurrentValueAndInlineOptions() {
        // 老形态：currentValue + options 都在投影里（BFF 还没上线时）。
        val history = historyWithPermissions(
            """{"currentValue":"workspace-write","options":[{"value":"read-only","name":"只读"},{"value":"workspace-write","name":"工作区写入"}]}""",
        )

        val select = history.permissionSelect(null)!!
        assertEquals("workspace-write", select.currentValue)
        assertEquals(listOf("read-only", "workspace-write"), select.options.map(PermissionOption::value))
    }

    @Test
    fun readsTheCurrentKernelShapeWhereOnlyPresetAndSandboxExist() {
        // 新形态（实测内核给的）：`currentValue` 与 `options` **都没有**。
        // 旧实现在这里 return null，Pill 与菜单权限项整块消失。
        val history = historyWithPermissions(
            """{"preset":"danger-full-access","sandbox":"danger-full-access","approval":"never","seeded":false}""",
        )

        val select = history.permissionSelect(null)!!
        assertEquals("danger-full-access", select.currentValue)
        // 没有候选项，但**不能为 null** —— 这正是整个修复的核心。
        assertTrue(select.options.isEmpty())
    }

    @Test
    fun fallsBackToTheCatalogForCandidatesWhenTheProjectionHasNone() {
        val history = historyWithPermissions(
            """{"preset":"workspace-write","sandbox":"workspace-write","approval":"ask"}""",
        )
        val catalog = PermissionCatalog(
            options = listOf(
                PermissionOption("workspace-write", "工作区写入", "只写工作区"),
                PermissionOption("danger-full-access", "完全访问"),
            ),
            defaultPreset = "workspace-write",
        )

        val select = history.permissionSelect(catalog)!!
        assertEquals("workspace-write", select.currentValue)
        assertEquals(listOf("workspace-write", "danger-full-access"), select.options.map(PermissionOption::value))
    }

    @Test
    fun prefersInlineOptionsOverTheCatalog() {
        // 投影里已经有内联清单时不要被目录覆盖：投影是「这个会话」的事实，目录是全局的。
        val history = historyWithPermissions(
            """{"currentValue":"read-only","options":[{"value":"read-only","name":"内联只读"}]}""",
        )
        val catalog = PermissionCatalog(options = listOf(PermissionOption("danger-full-access", "目录完全访问")))

        val select = history.permissionSelect(catalog)!!
        assertEquals(listOf("read-only"), select.options.map(PermissionOption::value))
    }

    @Test
    fun fallsBackToSandboxWhenPresetIsAbsent() {
        val history = historyWithPermissions("""{"sandbox":"read-only","approval":"ask"}""")

        assertEquals("read-only", history.permissionSelect(null)!!.currentValue)
    }

    @Test
    fun returnsNullOnlyWhenTheProjectionSaysNothingUsable() {
        // 「什么都没有」仍然必须返回 null —— UI 据此隐藏整条，而不是显示一个编出来的档位。
        assertNull(historyWithPermissions("""{"approval":"never"}""").permissionSelect(null))
        assertNull(ChatHistory(emptyList(), false, json.parseToJsonElement("""{"values":{}}"""))
            .permissionSelect(null))
        assertNull(ChatHistory(emptyList(), false, null).permissionSelect(null))
    }

    @Test
    fun optionWithoutANameFallsBackToItsValueRatherThanBeingDropped() {
        // 内核的 `custom` 档位只保证有 value。丢掉它等于菜单里凭空少一行；
        // 保留一个空名字则更糟：用户看到一行没有文字的选项。
        val history = historyWithPermissions("""{"currentValue":"custom","options":[{"value":"custom"}]}""")

        val select = history.permissionSelect(null)!!
        assertEquals("custom", select.options.single().name)
    }
}