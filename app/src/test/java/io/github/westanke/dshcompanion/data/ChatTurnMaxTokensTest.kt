package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `turn/end` 的 `max-tokens` 收尾 —— 截断提示必须出现，且**不能**被当成失败。
 *
 * ## 为什么值得一条门禁
 *
 * 用户真机反馈（2026-10-18）：「已达到输出 token 上限…发送“继续”可让模型接着输出」
 * 这段在 App 里**完全看不到**。
 *
 * 根因是两个客户端各自的注释都写着「max-tokens 刻意排除，内核把它渲染成黄色警示」——
 * 可本项目的架构是**客户端自渲染**，内核不会替客户端画任何东西。
 * 于是两边都没画，提示整条消失。这条测试钉住三件事：
 *
 * 1. `max-tokens` 必须产出**一行**（`TRUNCATED`），不再静默丢弃；
 * 2. 它**不能**是 `FAILURE` —— 截断不是失败，报红框会让用户以为整轮白跑；
 * 3. 新增的分支**不能**顺手抢走 `error` / `aborted` 的既有判定。
 *
 * 判据与内核 `turn-max-tokens` 节点的 `match` 一致：
 * `event.type === 'turn/end' && event.data.reason.kind === 'max-tokens'`。
 */
class ChatTurnMaxTokensTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun turnEnd(seq: Int, reason: String) = HistoryEntry(
        event = SessionEvent(
            type = "turn/end",
            seq = seq,
            time = seq.toLong(),
            data = json.parseToJsonElement("""{"turn":1,"reason":$reason}"""),
        ),
    )

    // ---------------------------------------------------------------- 主用例

    @Test
    fun `max-tokens 产出一行 TRUNCATED，而不是静默丢弃`() {
        val items = displayItems(turnEnd(7, """{"kind":"max-tokens"}"""))
        assertEquals("max-tokens 必须产出一行；0 行就是真机那个「提示消失」的 bug", 1, items.size)
        assertEquals(ChatItemKind.TRUNCATED, items.single().kind)
        // 仍然带 seq，列表里能有稳定锚点
        assertEquals(7, items.single().seq)
    }

    @Test
    fun `max-tokens 不是失败 —— 不得落成 FAILURE`() {
        val item = displayItems(turnEnd(8, """{"kind":"max-tokens"}""")).single()
        assertTrue(
            "截断被当成 FAILURE 会让用户看到红框、以为整轮白跑（内核也是黄色 warn 而非 error）",
            item.kind != ChatItemKind.FAILURE,
        )
        assertNull("截断不该带 failure 负载 —— 界面据此决定渲染红框还是黄条", item.failure)
    }

    // ---------------------------------------------------------------- 既有判定不被抢走

    @Test
    fun `error 仍然落成 FAILURE`() {
        val items = displayItems(
            turnEnd(9, """{"kind":"error","error":{"message":"boom","code":"RATE_LIMITED"}}"""),
        )
        assertEquals(1, items.size)
        assertEquals(ChatItemKind.FAILURE, items.single().kind)
        assertEquals("RATE_LIMITED", items.single().failure?.code)
    }

    @Test
    fun `登出导致的 aborted 仍然落成 FAILURE 且标记 stopped`() {
        // 内核把「登出停止」表达成 aborted 里再嵌一层 hook reason，形状照抄真实帧。
        val reason = """{"kind":"aborted","reason":{"kind":"hook","reason":"deepseek-account/signed-out"}}"""
        val items = displayItems(turnEnd(10, reason))
        assertEquals(1, items.size)
        assertEquals(ChatItemKind.FAILURE, items.single().kind)
        assertTrue("登出停止要给 stopped=true（界面据此不给重试按钮）", items.single().failure!!.stopped)
    }

    @Test
    fun `正常收尾不产生任何行`() {
        // 别把「正常跑完」也画成一条 —— 那会让每个 turn 末尾多一行噪音。
        assertTrue(displayItems(turnEnd(11, """{"kind":"completed"}""")).isEmpty())
    }

    // ---------------------------------------------------------------- 不被折进过程组

    /**
     * `TRUNCATED` 必须作为**独立消息**出现在流里，不能被折进「执行了命令…」那种过程组。
     *
     * 内核的 `TURN_PROCESS_INDEPENDENT_KINDS` 把 `turn-max-tokens` 列为独立节点，
     * 理由很实际：折进摘要里，用户只会看到「AI 忽然不说话了」，
     * 完全不知道是自己的回答被截断了。
     */
    @Test
    fun `TRUNCATED 不会被折进过程组`() {
        val tool = ChatDisplayItem(id = "t1", seq = 1, kind = ChatItemKind.TOOL, title = "执行了命令")
        val trunc = ChatDisplayItem(id = "m1", seq = 2, kind = ChatItemKind.TRUNCATED)
        val tool2 = ChatDisplayItem(id = "t2", seq = 3, kind = ChatItemKind.TOOL, title = "执行了命令")

        val entries = groupChatDisplay(listOf(tool, trunc, tool2))

        val messages = entries.filterIsInstance<ChatDisplayEntry.Message>().map { it.item }
        assertEquals(
            "TRUNCATED 必须作为独立消息出现，实际 entries=$entries",
            1,
            messages.count { it.kind == ChatItemKind.TRUNCATED },
        )
    }
}
