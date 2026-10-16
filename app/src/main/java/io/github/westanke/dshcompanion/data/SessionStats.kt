package io.github.westanke.dshcompanion.data

/**
 * 会话的**执行统计** —— 全部来自历史事件，不需要内核额外提供任何东西。
 *
 * ## 为什么只有这些（以及与竞品统计表的差别）
 *
 * 竞品的全屏统计表分三块：**执行**（轮次 / 步骤 / 工具调用）、**Token 用量**
 * （输入 / 输出 / 缓存读取 / 缓存写入 / 推理 / 缓存命中）、**上下文**（当前压力 / 预计用量 / 窗口）。
 *
 * 后两块需要内核给出 usage 与上下文占用。**本仓不可证**：我方 bridge 原样透传内核事件，
 * 而「历史事件里有没有 usage 字段」从本仓库查不到（`node_modules` 下没有内核包）。
 * 按 `PITFALLS.md` §空结果的来源要验证，只做能证明的那部分，并把它当**事实**呈现。
 *
 * ## 为什么不做「按字符数估算 token」
 *
 * 误差可能成倍（不同模型 tokenizer 不同，且系统提示词与工具定义客户端根本看不到），
 * 而用户会拿它当真实占用做决策（「我还有多少余量」）。**一个不可靠的数字比没有数字更坏** ——
 * 它把「不知道」伪装成「知道」。
 *
 * ## 字段都是数出来的，不是猜出来的
 *
 * 事件类型名取自 `ChatHistoryMapping.kt` 的既有映射规则（那里已经在按这些类型分派渲染），
 * 所以这里的判据与渲染口径**同源**。
 */
internal data class SessionStats(
    /** 用户轮次（`user/message` 条数）。 */
    val turns: Int = 0,
    /** 助手正文条数（`assistant/message`）。 */
    val assistantMessages: Int = 0,
    /** 工具调用（`tool/call`）。 */
    val toolCalls: Int = 0,
    /** 这次拿到的历史事件总数（分页时只是当前页）。 */
    val events: Int = 0,
    /** 最新事件序号 —— 竞品的「数据截至事件 #N」就是这个，用来判断统计的新鲜度。 */
    val lastSeq: Int? = null,
) {
    val isEmpty: Boolean get() = events == 0
}

/**
 * 从历史里数出执行统计。
 *
 * `history == null`（还没加载 / 加载失败）时返回空统计而不是抛错：面板显示「暂无数据」
 * 比弹一个错误合理 —— 用户只是点了下菜单。
 */
internal fun sessionStatsOf(history: ChatHistory?): SessionStats {
    val events = history?.events.orEmpty()
    if (events.isEmpty()) return SessionStats()
    var turns = 0
    var assistants = 0
    var tools = 0
    for (entry in events) {
        when (entry.event.type) {
            "user/message" -> turns += 1
            "assistant/message" -> assistants += 1
            "tool/call" -> tools += 1
        }
    }
    return SessionStats(
        turns = turns,
        assistantMessages = assistants,
        toolCalls = tools,
        events = events.size,
        lastSeq = events.maxOfOrNull { it.event.seq },
    )
}
