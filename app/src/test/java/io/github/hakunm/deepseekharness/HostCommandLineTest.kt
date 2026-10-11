package io.github.hakunm.deepseekharness

import io.github.hakunm.deepseekharness.data.CommandDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据：哪些 `/xxx` 算**宿主命令**（走 executeCommand），哪些该原样当普通消息发给模型。
 *
 * 这条判据错了的后果是双向的：
 * - 判得太宽 → 技能名被送去执行命令，服务端回 `COMMAND_NOT_FOUND`，**技能一个都调不动**；
 * - 判得太窄 → 宿主命令发不出去，用户打`/compact` 没反应。
 *
 * 规则与插件 `chat-adapter.ts` 的 `/^\/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])/u` +
 * `name === match[1]` 逐字对应，改这里必须同步改那边。
 */
class HostCommandLineTest {

    private val commands = listOf(
        CommandDescriptor("compact", "压缩上下文"),
        CommandDescriptor("clear", "清空会话"),
        CommandDescriptor("git_status", "git 状态"),
    )

    private fun isCommand(line: String) =
        isHostCommandLine(line, commands)

    @Test
    fun `注册过的命令算命令`() {
        assertTrue(isCommand("/compact"))
        assertTrue(isCommand("/clear"))
        assertTrue(isCommand("/git_status"))
    }

    @Test
    fun `命令带参数仍算命令`() {
        assertTrue(isCommand("/compact 3"))
        assertTrue(isCommand("/compact\t3"))
        assertTrue(isCommand("/clear   "))
    }

    @Test
    fun `未注册的名字不当命令（原 bug：技能被吞掉）`() {
        // 这正是用户报的「/技能名 调不动」：以前只要 / 开头就送 executeCommand。
        assertFalse(isCommand("/dataviz"))
        assertFalse(isCommand("/some-skill"))
        assertFalse(isCommand("/"))
    }

    @Test
    fun `命令表为空时一律不当命令`() {
        // 冷启动或取命令失败时 commands 可能是空的；此时不能把用户的输入当命令吞掉。
        assertFalse(isHostCommandLine("/compact", emptyList()))
        assertFalse(isHostCommandLine("/anything", emptyList()))
    }

    @Test
    fun `普通消息不当命令`() {
        assertFalse(isCommand("帮我看下这个文件"))
        assertFalse(isCommand(""))
        assertFalse(isCommand(" /compact"))
        assertFalse(isCommand("路径 //compact"))
    }

    @Test
    fun `命令名形态与服务端正则一致`() {
        // 服务端只认 [a-z][a-z0-9_-]*，所以大写开头的必须**不发**给命令通道：
        // 否则客户端放行、服务端 COMMAND_NOT_FOUND，用户看到的是一句莫名失败。
        assertFalse(isCommand("/Compact"))
        assertFalse(isCommand("/1abc"))
        assertFalse(isCommand("/-abc"))
        assertFalse(isCommand("/a b"))
        assertFalse(isCommand("/a.b"))
    }
}