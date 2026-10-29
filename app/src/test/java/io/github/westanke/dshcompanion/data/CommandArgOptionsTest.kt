package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/model ` 与 `/permission ` 二级菜单的回归测试。
 *
 * 盯的是两件事：
 *  1. **什么时候出菜单** —— 只有「命令名已定 + 参数还没打」那一刻。多一个字都不出，
 *     否则用户想手敲某个不在清单里的值时会被菜单一直挡着。
 *  2. **选项从哪来** —— 全部来自公开 REST（会话投影 `permissions` / `/chat/sessions/{id}/models`），
 *     不猜版本、不调私有接口。这与桥 `ws-commands.ts` 的 `command-options` 帧语义一致，
 *     但走的是 App 本来就有的两条数据源。
 */
class CommandArgOptionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun historyWithPermissions(permissionsJson: String): ChatHistory = ChatHistory(
        events = emptyList(),
        hasMore = false,
        projections = json.parseToJsonElement("""{"values":{"permissions":$permissionsJson}}"""),
    )

    private fun models(): SessionModels = SessionModels(
        current = ModelSelection("anthropic", "claude-sonnet-4"),
        routable = true,
        groups = listOf(
            ModelProviderGroup(
                id = "anthropic",
                name = "Anthropic",
                models = listOf(
                    ModelView("claude-sonnet-4", "Claude Sonnet 4"),
                    ModelView("claude-opus-4", "Claude Opus 4"),
                ),
            ),
            ModelProviderGroup(
                id = "openai",
                name = "OpenAI",
                models = listOf(ModelView("gpt-5", "GPT-5")),
            ),
        ),
    )

    // ---- 判据 ----

    @Test
    fun onlyTreatsTrailingSpaceAfterKnownCommandAsArgumentSlot() {
        assertTrue(isAtCommandArgument("/permission "))
        assertTrue(isAtCommandArgument("/model "))
        assertTrue(isAtCommandArgument("/model  ")) // 多打空格仍是参数位
        assertFalse(isAtCommandArgument("/permission")) // 命令名还没确认
        assertFalse(isAtCommandArgument("/permission read")) // 已经在打参数了
        assertFalse(isAtCommandArgument("/compact ")) // 没有二级菜单的命令
        assertFalse(isAtCommandArgument("permission ")) // 少了斜杠
        assertFalse(isAtCommandArgument(" /model ")) // 前面有空格，不是命令位
        assertFalse(isAtCommandArgument(""))
    }

    @Test
    fun exposesTypedArgumentSoCallerCanTellItApart() {
        assertNull(commandArgument("/model "))
        assertNull(commandArgument("/permission"))
        assertEquals("read-only", commandArgument("/permission read-only"))
    }

    // ---- 权限档位 ----

    @Test
    fun buildsPermissionOptionsFromInlineProjection() {
        val history = historyWithPermissions(
            """{"currentValue":"workspace-write","options":[{"value":"read-only","name":"只读"},{"value":"workspace-write","name":"工作区写入","description":"可写工作区"}]}""",
        )

        val options = permissionCommandOptions(history)

        assertEquals(listOf("只读", "工作区写入"), options.map(CommandArgOption::label))
        assertEquals(listOf(false, true), options.map(CommandArgOption::selected))
        assertEquals("可写工作区", options[1].detail)
    }

    @Test
    fun fallsBackToCatalogWhenProjectionHasNoInlineOptions() {
        // 内核新版投影只给当前值，候选项在 /chat/permission-presets —— 与顶栏 Pill 同源。
        val history = historyWithPermissions("""{"currentValue":"read-only"}""")
        val catalog = PermissionCatalog(
            options = listOf(
                PermissionOption("read-only", "只读"),
                PermissionOption("workspace-write", "工作区写入"),
            ),
            defaultPreset = "read-only",
        )

        val options = permissionCommandOptions(history, catalog)

        assertEquals(2, options.size)
        assertEquals(listOf(true, false), options.map(CommandArgOption::selected))
    }

    @Test
    fun returnsNoPermissionOptionsWithoutProjection() {
        assertTrue(permissionCommandOptions(null).isEmpty())
        assertTrue(permissionCommandOptions(ChatHistory(emptyList(), false, null)).isEmpty())
    }

    // ---- 模型 ----

    @Test
    fun flattensModelGroupsAndMarksCurrent() {
        val options = modelCommandOptions(models())

        assertEquals(
            listOf("Claude Sonnet 4", "Claude Opus 4", "GPT-5"),
            options.map(CommandArgOption::label),
        )
        assertEquals(listOf(true, false, false), options.map(CommandArgOption::selected))
        // 副标题是分组名：同名模型跨 provider 时靠它区分。
        assertEquals(listOf("Anthropic", "Anthropic", "OpenAI"), options.map(CommandArgOption::detail))
        val first = options.first() as CommandArgOption.Model
        assertEquals("anthropic", first.provider)
        assertEquals("claude-sonnet-4", first.model)
    }

    @Test
    fun returnsNoModelOptionsWithoutCatalog() {
        assertTrue(modelCommandOptions(null).isEmpty())
        assertTrue(modelCommandOptions(models().copy(groups = emptyList())).isEmpty())
    }

    // ---- 合流 ----

    @Test
    fun routesToTheRightGroupByCommandName() {
        val history = historyWithPermissions("""{"currentValue":"read-only","options":[{"value":"read-only","name":"只读"}]}""")

        assertEquals(1, commandArgOptions("/permission ", history, models()).size)
        assertEquals(3, commandArgOptions("/model ", history, models()).size)
        // 参数已开始 → 不出菜单
        assertTrue(commandArgOptions("/model gpt", history, models()).isEmpty())
        // 没有二级菜单的命令 → 不出菜单
        assertTrue(commandArgOptions("/compact ", history, models()).isEmpty())
    }
}
