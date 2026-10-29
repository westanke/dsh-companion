package io.github.westanke.dshcompanion.ui.chat

import io.github.westanke.dshcompanion.data.CommandDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/` 命令补全面板的过滤规则（`commandSuggestions`）。
 *
 * ## 用户报的 bug（2026-10-29，真机 v2.2.57）
 *
 * 「输入 `/` 命令，不能上滑，其他的命令无法看见」。
 *
 * 真机上桥侧下发 **9 条**命令，而旧实现末尾写着 `.take(5)`：只列出前 5 条，
 * 剩下的 `permission` / `plan` / `tunnel` / `mcp` 被静默吞掉；面板外层又是个不可滚的
 * `Column`，于是那几条**既看不见也够不着** —— 用户唯一的补救是「猜出命令名再手敲」，
 * 等于把发现性彻底废掉。
 *
 * 修法是**两边一起**：过滤不再截断，面板改用 `LazyColumn + heightIn(max = 264.dp)` 自己滚。
 * 这里钉的是前半截 —— 后半截（滚动）是 Compose 布局，只能靠真机截图验收。
 */
class CommandSuggestionsTest {

    private fun commands(vararg names: String) =
        names.map { CommandDescriptor(name = it, description = "$it 的说明") }

    @Test
    fun doesNotTruncateWhenTheHostSendsMoreThanFiveCommands() {
        // 桥侧实测就是这 9 条。旧实现的 `.take(5)` 会在这里只剩 5 条。
        val all = commands(
            "acp", "acp-cache", "export", "feedback", "goal",
            "mcp", "permission", "plan", "tunnel",
        )

        val shown = commandSuggestions("/", all)

        assertEquals("打了 `/` 就该看到全部命令", 9, shown.size)
        assertTrue(
            "被旧实现吞掉的那几条必须能出现",
            shown.map { it.name }.containsAll(listOf("permission", "plan", "tunnel", "mcp")),
        )
    }

    @Test
    fun filtersByPrefixWhileTheUserIsStillTypingTheName() {
        val all = commands("acp", "acp-cache", "export", "goal", "permission", "plan")

        assertEquals(listOf("acp", "acp-cache"), commandSuggestions("/acp", all).map { it.name })
        // 顺序跟随宿主下发的顺序，不在这里重排：面板顺序就是桥上命令表的顺序，
        // 客户端擅自排序会让「同一份命令表在两个客户端上位置不同」。
        assertEquals(listOf("permission", "plan"), commandSuggestions("/p", all).map { it.name })
    }

    @Test
    fun ignoresCaseSoShoutingAtTheKeyboardStillWorks() {
        val all = commands("export", "permission")

        assertEquals(listOf("export"), commandSuggestions("/EXP", all).map { it.name })
    }

    @Test
    fun staysHiddenOnceTheUserStartsTypingArguments() {
        // 空格之后就是在打参数了：命令面板该让位给参数面板（`/model ` / `/permission `），
        // 两个列表叠在一起会互相遮挡，而且点击目标重叠。
        val all = commands("permission", "model")

        assertTrue(commandSuggestions("/permission ", all).isEmpty())
        assertTrue(commandSuggestions("/model read", all).isEmpty())
    }

    @Test
    fun staysHiddenWhenTheLineIsNotASlashCommand() {
        val all = commands("permission")

        assertTrue("普通消息不该弹命令面板", commandSuggestions("你好", all).isEmpty())
        assertTrue("空输入不该弹", commandSuggestions("", all).isEmpty())
        // 斜杠出现在中间（比如路径）也不算命令。
        assertTrue(commandSuggestions("看下 /etc/hosts", all).isEmpty())
    }

    @Test
    fun emptyCatalogMeansNoPanel() {
        // 老桥不给命令列表：面板不能凭空出现一个空壳。
        assertTrue(commandSuggestions("/", emptyList()).isEmpty())
    }
}
