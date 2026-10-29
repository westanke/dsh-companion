package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话投影里**只有内核才知道**的那几块：Token 用量、上下文压力、内核统计、目标、智能体团队。
 *
 * ## 为什么从 `projections` 读，而不是新开一条传输层
 *
 * 桥 v2.6 起在 `/ws/mobile` 上开了 `session-stats` / `context-usage` / `goal` / `agent-team`
 * 等帧，看起来像是「要新接 WebSocket 才能拿到」。实际上这些帧只是把内核投影
 * **换个壳再发一遍**：`src/host/ws-goals.ts` 读的就是会话投影里的同名键，
 * 而 REST `GET /chat/sessions/{id}/history` 的响应体本来就带整份 `projections`
 * （`src/host/chat-adapter.ts:221`、`:716` 的 `projections?: unknown`）。
 *
 * companion 已经在读其中两个键（`todos`、`permissions`，见 [ChatHistory.todoItems] 与
 * [ChatHistory.permissionSelect]），本文件只是把**同一份 JSON 里另外五个键**读出来。
 * 于是「桥新增的统计能力」在 App 侧不需要新增连接、不需要新端点、不需要动网络层。
 *
 * ## 字段形状的出处
 *
 * 键名与结构取自桥的合约测试 `tests/mobile-gateway-ws.spec.ts:1219-1233`（假内核直接塞进
 * 投影的那份 fixture），这是本仓**可证**的唯一来源：
 *
 * ```json
 * "tokenUsage":      { "totals": { "inputTokens": 10, "outputTokens": 5,
 *                                  "cacheReadTokens": 1, "cacheWriteTokens": 0,
 *                                  "reasoningTokens": 0 } }
 * "contextPressure": { "contextWindow": 128000, "pressureTokens": 1500, "surfaceTokens": 2000 }
 * "sessionStats":    { "turns": 6, "steps": 69, "llmMs": 2280000, "decodeTokens": 385 }
 * "agentTeam":       { "members": [...], "tasks": [...] }
 * "subagentCatalog": [ ... ]
 * "goal":            { "goal": { "id": "goal-1", "revision": 7,
 *                                "objective": "...", "phase": "active", "maxGoalRounds": 12 } }
 * ```
 *
 * `goal` 是最容易变形的一个：fixture 里是**嵌套**的 `{goal:{...}}`，但扁平形状同样合理。
 * [goalInfoOf] 两种都认 —— 认不出就返回 null，不编造。
 */

/** Token 用量（内核 `tokenUsage.totals`）。 */
internal data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val reasoningTokens: Int,
) {
    /**
     * 缓存命中率：命中读取 / (命中读取 + 未命中输入)。
     *
     * 分母为 0（还没发过请求）时返回 null —— 0/0 显示成 0% 会让人以为「缓存完全失效」。
     */
    val cacheHitRate: Float?
        get() {
            val total = cacheReadTokens.toLong() + inputTokens.toLong()
            if (total <= 0L) return null
            return cacheReadTokens.toFloat() / total
        }
}

/** 上下文压力（内核 `contextPressure`）。 */
internal data class ContextPressure(
    val contextWindow: Int?,
    val pressureTokens: Int?,
    val surfaceTokens: Int?,
) {
    /**
     * 压力占窗口的比例（0..1）。
     *
     * 窗口未知或为 0 时返回 null。**不拿 surfaceTokens 兜底**：那是「贴到模型表面的量」，
     * 与「还剩多少余量」不是一回事，混用会把余量算多。
     */
    val fraction: Float?
        get() {
            val window = contextWindow?.takeIf { it > 0 } ?: return null
            val used = pressureTokens ?: return null
            return (used.toFloat() / window).coerceIn(0f, 1f)
        }
}

/** 内核给的内核侧执行统计（`sessionStats`）——与客户端数事件的 [SessionStats] 是两套口径。 */
internal data class KernelStats(
    val turns: Int?,
    val steps: Int?,
    val llmMs: Long?,
    val decodeTokens: Int?,
)

/** 目标（内核 `goal`）。 */
internal data class GoalInfo(
    val objective: String,
    val phase: String?,
    val revision: Int?,
    val maxGoalRounds: Int?,
    /**
     * 内核的目标 id（`values.goal.goal.id`），写操作的表单引用 `ref.id` 用它。
     *
     * 放在最后且带默认值：本类的构造点只有 [goalInfoOf] 一处，但没有默认值会让
     * 后续加字段时所有调用点一起改 —— 而 id 在**老内核**的投影里可能没有，
     * 缺了它只是不能写（[io.github.westanke.dshcompanion.data.ws.GoalError.REF_MISSING]），
     * 读端展示一点都不受影响。
     */
    val id: String? = null,
)

/** 智能体团队（内核 `agentTeam`）。成员/任务的**条数**是稳定信息，内容形状随内核演化。 */
internal data class AgentTeamInfo(
    val memberCount: Int,
    val taskCount: Int,
)

/** 会话投影里全部「内核侧」分析的打包结果；缺哪块就是 null，绝不补零。 */
internal data class SessionAnalytics(
    val tokenUsage: TokenUsage?,
    val contextPressure: ContextPressure?,
    val kernelStats: KernelStats?,
    val goal: GoalInfo?,
    val agentTeam: AgentTeamInfo?,
    val subagentCount: Int?,
) {
    /** 有没有**任何**用量类数据 —— 决定统计面板还要不要显示「拿不到」那段说明。 */
    val hasUsage: Boolean get() = tokenUsage != null || contextPressure != null

    /** 全是 null 时面板只显示执行维度，不必渲染空分节。 */
    val isEmpty: Boolean
        get() = tokenUsage == null && contextPressure == null && kernelStats == null &&
            goal == null && agentTeam == null && subagentCount == null
}

/** 一次把五个键读出来。历史还没加载（`null`）时返回全空，与 [sessionStatsOf] 一致。 */
internal fun sessionAnalyticsOf(history: ChatHistory?): SessionAnalytics {
    val values = history?.projectionValues()
    if (values == null) return EMPTY_ANALYTICS
    return SessionAnalytics(
        tokenUsage = tokenUsageOf(values),
        contextPressure = contextPressureOf(values),
        kernelStats = kernelStatsOf(values),
        goal = goalInfoOf(values),
        agentTeam = agentTeamOf(values),
        subagentCount = (values["subagentCatalog"] as? JsonArray)?.size,
    )
}

private val EMPTY_ANALYTICS = SessionAnalytics(null, null, null, null, null, null)

/** `projections.values`；没有 projections 或形状不对时返回 null。 */
internal fun ChatHistory.projectionValues(): JsonObject? =
    (projections as? JsonObject)?.objectValue("values")

internal fun tokenUsageOf(values: JsonObject): TokenUsage? {
    // 容错：个别内核版本把 totals 直接平铺在 tokenUsage 上。
    val totals = values.objectValue("tokenUsage")?.let { it.objectValue("totals") ?: it } ?: return null
    val input = totals.intValue("inputTokens") ?: 0
    val output = totals.intValue("outputTokens") ?: 0
    val cacheRead = totals.intValue("cacheReadTokens") ?: 0
    val cacheWrite = totals.intValue("cacheWriteTokens") ?: 0
    val reasoning = totals.intValue("reasoningTokens") ?: 0
    // 五个全 0 且键都不存在 → 视为没有数据，别显示一排 0 骗人。
    if (input == 0 && output == 0 && cacheRead == 0 && cacheWrite == 0 && reasoning == 0 &&
        totals["inputTokens"] == null && totals["outputTokens"] == null
    ) {
        return null
    }
    return TokenUsage(input, output, cacheRead, cacheWrite, reasoning)
}

internal fun contextPressureOf(values: JsonObject): ContextPressure? {
    val pressure = values.objectValue("contextPressure") ?: return null
    val window = pressure.intValue("contextWindow")
    val used = pressure.intValue("pressureTokens")
    val surface = pressure.intValue("surfaceTokens")
    if (window == null && used == null && surface == null) return null
    return ContextPressure(window, used, surface)
}

internal fun kernelStatsOf(values: JsonObject): KernelStats? {
    val stats = values.objectValue("sessionStats") ?: return null
    val turns = stats.intValue("turns")
    val steps = stats.intValue("steps")
    val llmMs = (stats["llmMs"] as? JsonPrimitive)?.content?.toLongOrNull()
    val decode = stats.intValue("decodeTokens")
    if (turns == null && steps == null && llmMs == null && decode == null) return null
    return KernelStats(turns, steps, llmMs, decode)
}

internal fun goalInfoOf(values: JsonObject): GoalInfo? {
    val raw = values.objectValue("goal") ?: return null
    // 嵌套形状 `{goal:{...}}` 优先；否则按扁平对象读。
    val goal = raw.objectValue("goal") ?: raw
    val objective = goal.stringValue("objective")?.takeIf(String::isNotBlank) ?: return null
    return GoalInfo(
        objective = objective,
        phase = goal.stringValue("phase"),
        revision = goal.intValue("revision"),
        maxGoalRounds = goal.intValue("maxGoalRounds"),
        id = goal.stringValue("id")?.takeIf(String::isNotBlank),
    )
}

internal fun agentTeamOf(values: JsonObject): AgentTeamInfo? {
    val team = values.objectValue("agentTeam") ?: return null
    val members = (team["members"] as? JsonArray)?.size ?: 0
    val tasks = (team["tasks"] as? JsonArray)?.size ?: 0
    if (members == 0 && tasks == 0) return null
    return AgentTeamInfo(members, tasks)
}

/** 把毫秒压成 `2m18s` / `950ms` 这样的短标签（`llmMs` 实测能到 228 万毫秒）。 */
internal fun formatDuration(ms: Long): String {
    if (ms < 1000L) return "${ms}ms"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes >= 60L -> {
            val hours = minutes / 60L
            val restMinutes = minutes % 60L
            "${hours}h${restMinutes}m"
        }
        minutes > 0L -> "${minutes}m${seconds}s"
        else -> "${seconds}s"
    }
}

/** 百分比短标签：`62%`（整数，不装精确）。 */
internal fun formatPercent(fraction: Float): String = "${(fraction * 100f).toInt()}%"
