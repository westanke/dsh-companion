package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 收到终局失败帧后，本地把会话标成「不在运行」。
 *
 * 背景：模型限流（429）之后，服务端把会话标成 not-running 与客户端收到失败帧
 * 之间有几百毫秒。这段空档里「停止」按钮还亮着、占位符还写着「运行中…」，
 * 用户看着就以为还在跑 —— 于是继续傻等。这正是用户报的 bug：
 * 「要不然客户端一直在傻傻的等」。
 *
 * 所以这一步刻意在本地先做，随后 `GET /sessions` 用权威数据再盖一次。
 */
class MarkSessionStoppedTest {

    private fun session(id: String, running: Boolean) =
        ChatSession(id = id, rootId = "root", cwd = "/tmp", updatedAt = 0L, running = running, blank = false)

    @Test
    fun `失败的会话立刻停下来`() {
        val sessions = listOf(session("s1", running = true), session("s2", running = true))

        val result = markSessionStopped(sessions, "s1")

        assertEquals(listOf(false, true), result.map { it.running })
    }

    @Test
    fun `不动其它会话`() {
        // 失败帧带的是它自己那个 sessionId —— 用户此时可能正看着另一个会话，
        // 把别人也停下来就是另一个 bug。
        val sessions = listOf(session("s1", running = true), session("s2", running = true))

        val result = markSessionStopped(sessions, "s2")

        assertEquals(listOf(true, false), result.map { it.running })
    }

    @Test
    fun `没有可停的会话就原样返回`() {
        // 已经是停的状态 / id 对不上：都不该造出新列表。
        // 这不只是省事 —— Compose 看到同一个实例会跳过重组。
        val already = listOf(session("s1", running = false))
        val other = listOf(session("s2", running = true))

        assertSame(already, markSessionStopped(already, "s1"))
        assertSame(other, markSessionStopped(other, "missing"))
        assertSame(emptyList<ChatSession>(), markSessionStopped(emptyList(), "s1"))
    }
}
