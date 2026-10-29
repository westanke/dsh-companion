package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话投影里的内核侧分析（Token 用量 / 上下文压力 / 内核统计 / 目标 / 智能体团队）。
 *
 * 数据形状取自桥的合约测试 `tests/mobile-gateway-ws.spec.ts:1219-1233` —— 那是本仓
 * 唯一**可证**的形状来源。测试里刻意用了同一份 fixture 数字，便于对照。
 */
class SessionAnalyticsTest {

    private fun historyOf(projections: String?): ChatHistory {
        val element = projections?.let { Json.parseToJsonElement(it) }
        return ChatHistory(events = emptyList(), hasMore = false, projections = element)
    }

    @Test
    fun `reads every projection key from the bridge fixture`() {
        val history = historyOf(
            """
            {"values":{
              "tokenUsage":{"totals":{"inputTokens":10,"outputTokens":5,
                                      "cacheReadTokens":1,"cacheWriteTokens":0,"reasoningTokens":0}},
              "contextPressure":{"contextWindow":128000,"pressureTokens":1500,"surfaceTokens":2000},
              "sessionStats":{"turns":6,"steps":69,"llmMs":2280000,"decodeTokens":385},
              "agentTeam":{"members":[{"id":"lead"}],"tasks":[{"id":"t1"}]},
              "subagentCatalog":[{"sessionId":"sub-1"}],
              "goal":{"goal":{"id":"goal-1","revision":7,"objective":"初始化一个 Android app",
                              "phase":"active","maxGoalRounds":12}}
            }}
            """.trimIndent(),
        )

        val analytics = sessionAnalyticsOf(history)
        assertTrue(analytics.hasUsage)
        assertEquals(10, analytics.tokenUsage?.inputTokens)
        assertEquals(5, analytics.tokenUsage?.outputTokens)
        assertEquals(1, analytics.tokenUsage?.cacheReadTokens)
        assertEquals(128000, analytics.contextPressure?.contextWindow)
        assertEquals(1500, analytics.contextPressure?.pressureTokens)
        assertEquals(6, analytics.kernelStats?.turns)
        assertEquals(69, analytics.kernelStats?.steps)
        assertEquals(2280000L, analytics.kernelStats?.llmMs)
        assertEquals(385, analytics.kernelStats?.decodeTokens)
        assertEquals(1, analytics.agentTeam?.memberCount)
        assertEquals(1, analytics.agentTeam?.taskCount)
        assertEquals(1, analytics.subagentCount)
        assertEquals("初始化一个 Android app", analytics.goal?.objective)
        assertEquals("active", analytics.goal?.phase)
        assertEquals(7, analytics.goal?.revision)
        assertEquals(12, analytics.goal?.maxGoalRounds)
    }

    /** 没有 projections（老桥、或历史还没加载）时全空，面板只显示客户端统计。 */
    @Test
    fun `missing projections yields an empty analytics`() {
        val analytics = sessionAnalyticsOf(historyOf(null))
        assertTrue(analytics.isEmpty)
        assertTrue(!analytics.hasUsage)
        assertNull(analytics.goal)
        assertNull(analytics.agentTeam)
        assertNull(analytics.subagentCount)
    }

    @Test
    fun `null history is empty rather than a crash`() {
        assertTrue(sessionAnalyticsOf(null).isEmpty)
    }

    /** `goal` 两种形状都要认：fixture 是嵌套 `{goal:{...}}`，扁平对象同样合理。 */
    @Test
    fun `goal parses both nested and flat shapes`() {
        val nested = sessionAnalyticsOf(
            historyOf("""{"values":{"goal":{"goal":{"objective":"A","phase":"active"}}}}"""),
        )
        val flat = sessionAnalyticsOf(
            historyOf("""{"values":{"goal":{"objective":"A","phase":"active"}}}"""),
        )
        assertEquals("A", nested.goal?.objective)
        assertEquals("A", flat.goal?.objective)
        assertEquals("active", flat.goal?.phase)
    }

    /** 没有 objective 的 goal 一律当没有 —— 显示一个空标题比不显示更让人困惑。 */
    @Test
    fun `goal without an objective is ignored`() {
        assertNull(sessionAnalyticsOf(historyOf("""{"values":{"goal":{"goal":{"phase":"active"}}}}""")).goal)
    }

    /** 五个字段都缺的 tokenUsage 视为没数据，而不是显示一排 0。 */
    @Test
    fun `empty tokenUsage is treated as absent`() {
        assertNull(sessionAnalyticsOf(historyOf("""{"values":{"tokenUsage":{"totals":{}}}}""")).tokenUsage)
        assertNull(sessionAnalyticsOf(historyOf("""{"values":{}}""")).tokenUsage)
    }

    /** 全 0 但字段存在（真的一次都没跑）是有效数据，要显示。 */
    @Test
    fun `explicit zeros are still data`() {
        val usage = sessionAnalyticsOf(
            historyOf("""{"values":{"tokenUsage":{"totals":{"inputTokens":0,"outputTokens":0}}}}"""),
        ).tokenUsage
        assertNotNull(usage)
        assertEquals(0, usage?.inputTokens)
    }

    @Test
    fun `cache hit rate guards against a zero denominator`() {
        assertEquals(1f / 11f, TokenUsage(10, 0, 1, 0, 0).cacheHitRate!!, 0.0001f)
        assertNull(TokenUsage(0, 0, 0, 0, 0).cacheHitRate)
    }

    @Test
    fun `context fraction needs a positive window`() {
        assertEquals(1500f / 128000f, ContextPressure(128000, 1500, 2000).fraction!!, 0.0001f)
        assertNull(ContextPressure(0, 1500, 2000).fraction)
        assertNull(ContextPressure(128000, null, 2000).fraction)
    }

    /** 空团队当成没有：`{members:[],tasks:[]}` 不该占一个分节。 */
    @Test
    fun `empty agent team is ignored`() {
        assertNull(
            sessionAnalyticsOf(historyOf("""{"values":{"agentTeam":{"members":[],"tasks":[]}}}""")).agentTeam,
        )
    }

    @Test
    fun `duration formatting spans ms seconds minutes hours`() {
        assertEquals("950ms", formatDuration(950))
        assertEquals("45s", formatDuration(45_000))
        assertEquals("2m18s", formatDuration(138_000))
        assertEquals("38m0s", formatDuration(2_280_000))
        assertEquals("2h5m", formatDuration(7_500_000))
    }

    @Test
    fun `percent is truncated not rounded up`() {
        assertEquals("1%", formatPercent(0.0117f))
        assertEquals("99%", formatPercent(0.9999f))
        assertEquals("0%", formatPercent(0f))
    }
}
