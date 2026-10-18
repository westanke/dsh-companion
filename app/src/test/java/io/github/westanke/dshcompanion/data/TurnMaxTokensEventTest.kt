package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chat.turn.end` 上 `reason.kind === 'max-tokens'` 的识别。
 *
 * ## 为什么值得一条门禁
 *
 * 用户真机反馈（2026-10-18）：「已达到输出 token 上限…」那段在 App 里看不到。
 * 根因跨三层，其中一层就在这里 —— 判据必须与内核 `turn-max-tokens` 节点的 `match`
 * **逐字一致**，多认一种或漏认一种都会错：
 *
 * - 漏认 → 提示消失（就是这次报的 bug）；
 * - 多认 → 把「正常收尾 / 用户取消 / 其它钩子」也画成「回答被截断」，凭空吓用户。
 *
 * 另外 `aborted` **不是** max-tokens：它是取消，界面上不该出现「被截断」。
 */
class TurnMaxTokensEventTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(type: String, data: String) =
        WorkspaceEvent(id = "e1", type = type, time = 0, data = json.parseToJsonElement(data))

    @Test
    fun `max-tokens 被识别`() {
        assertTrue(event("chat.turn.end", """{"sessionId":"s","reason":{"kind":"max-tokens"}}""").isTurnMaxTokens())
    }

    @Test
    fun `正常收尾与失败都不算截断`() {
        // ok：正常跑完
        assertFalse(event("chat.turn.end", """{"reason":{"kind":"ok"}}""").isTurnMaxTokens())
        // error：终局失败，走 chat.turn.failed 那条红框路，不是黄条
        assertFalse(
            event("chat.turn.end", """{"reason":{"kind":"error","error":{"message":"x"}}}""").isTurnMaxTokens(),
        )
        // aborted：取消（用户/钩子/登出），不是「回答被截断」
        assertFalse(
            event("chat.turn.end", """{"reason":{"kind":"aborted","reason":{"kind":"user"}}}""").isTurnMaxTokens(),
        )
    }

    @Test
    fun `未知 kind 必须安静忽略，不能当成截断`() {
        // 内核将来加新 kind 时，老客户端不能凭空报「被截断」。
        assertFalse(event("chat.turn.end", """{"reason":{"kind":"some-future-kind"}}""").isTurnMaxTokens())
        assertFalse(event("chat.turn.end", """{"reason":{"kind":""}}""").isTurnMaxTokens())
    }

    @Test
    fun `缺 reason 或缺 data 不崩也不误判`() {
        assertFalse(event("chat.turn.end", """{"sessionId":"s"}""").isTurnMaxTokens())
        assertFalse(event("chat.turn.end", """{}""").isTurnMaxTokens())
        // data 不是对象（老版本内核/异常帧）—— 不能抛
        assertFalse(event("chat.turn.end", """[]""").isTurnMaxTokens())
        assertFalse(event("chat.turn.end", """null""").isTurnMaxTokens())
    }

    @Test
    fun `别的类型即使带 max-tokens 的 reason 也不认`() {
        // 判据是「type 精确等于 chat.turn.end」+ reason，不是「任何带这个 reason 的帧」。
        assertFalse(event("chat.message.committed", """{"reason":{"kind":"max-tokens"}}""").isTurnMaxTokens())
        assertFalse(event("chat.turn.start", """{"reason":{"kind":"max-tokens"}}""").isTurnMaxTokens())
    }
}
