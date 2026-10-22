package io.github.westanke.dshcompanion.data

/**
 * 会话列表的**显示分组**：把扁平的一串 [ChatDisplayItem] 按「过程 / 结论」二分，
 * 连续的过程项收成一个折叠组，组头用 [ProcessGroupSummary.title] 报一行摘要。
 *
 * ## 为什么要有这一层
 *
 * 现在页面是**扁平行列表**：一次跑 40 条工具调用就是一屏「执行了命令 / 读取了文件」，
 * 用户要往下翻很久才看到助手那句结论 —— 信息密度差。参考实现（Whaleness 的
 * `ConversationDisplayGroups.kt` 的 `makeConversationDisplayEntries`）用的是二分法：
 * 顺序扫一遍，遇到「会渲染出可见正文」的项就把当前过程组 flush 掉。
 * 这里照抄的就是那条**二分边界**，只是判定用我们自己的 [ChatItemKind]。
 *
 * 刻意只做**数据层**：分组、计数、摘要措辞。展开/折叠、动画、点击一律由 UI 决定
 * （组内只有 1 项时照样产出一个 [ChatDisplayEntry.ProcessGroup]，折不折是 UI 的事）。
 *
 * ## [ChatItemKind] 分类表（逐项给理由，**以 ChatModels.kt 的真实枚举为准**）
 *
 * | kind | 归哪类 | 理由 |
 * |---|---|---|
 * | `USER` | 消息 | 用户自己说的话，永远独立一行，折进去等于把提问藏了 |
 * | `ASSISTANT` | **看有没有正文** | 有正文（含 dsh-ui 围栏）或带图片才算消息；**空的算过程**。真实会话里绝大多数 `assistant/message` 只装 reasoning + tool-call、一个字都没有（本机某会话 46 条里 45 条），把它们当消息会把连续的工具调用劈成几十个单条过程组 —— 屏幕上就只剩一排「耗时 … · N 次工具调用」 |
 * | `FAILURE` | 消息 | 终局失败（限流 / 配额 / 密钥 / 登出停止）是用户唯一能看到「为什么失败」的地方，折进过程组等于把答案藏起来 |
 * | `CONTEXT` | 过程 | 上下文注入（记忆库、AGENTS.md、插件、技能唤起…）：用户想知道「注入了 N 项」，不是逐条读正文 |
 * | `REASONING` | 过程 | 思考。历史与流式两处都已把它挡在消息流外（改由思考条承载），漏进来也不该占正文的位置 |
 * | `TOOL` | 过程 | 工具调用**同时也承载工具结果**：`toolItem` 把 `tool/result` 的执行结果塞进 `tools[].result`，枚举里**没有**独立的 TOOL_RESULT —— 所以「工具调用 + 工具结果」在这里是同一个 kind |
 * | `DELIVERABLE` | 消息 | 交付物卡片（内核明说的显式交付）。折进过程组等于把「你给的东西」藏了 —— 桌面端也把它钉在轮次收尾的独立卡片上 |
 *
 * ## 空助手消息（2026-10-22 真机反馈补的一条）
 *
 * 用户原话：「对话窗口也存在 工具加载过多，不展开折叠的情况下，当前页面 只显示
 * 耗时 xxx x次工具调用」。
 *
 * 根因不是折叠逻辑坏了，而是**折叠被劈碎了**：真实会话里 `assistant/message` 绝大多数
 * 只装 `reasoning` + `tool-call`，`text` 一个字都没有（本机某会话实测 46 条里 45 条如此）。
 * 这类消息照样映射成 `ASSISTANT` 展示项、`body` 是空串 —— 以前一律当"消息"，于是
 * 每两条过程项之间就插进一条零高度的消息，`flushRun()` 被反复触发：
 *
 * > 组头 → （空消息，看不见）→ 组头 → （空消息）→ 组头 …
 *
 * 屏幕上就成了一整屏「耗时 8 分 33 秒 · 42 次工具调用」，用户以为对话内容丢了。
 *
 * 现在的判据是「**有没有读得出来的东西**」：有正文或带图片才算消息，空的折进过程组。
 * 这样一整轮连续的工具调用重新合成**一个**组头，正文该在的位置就只剩真正的正文。
 *
 * 兜底是 `else -> 消息`：将来内核加了新 kind 而没人回来改这张表时，
 * **宁可少折叠一个，也不能把正文折进去**（折叠错了用户是真的看不到内容）。
 *
 * ## 纯函数
 *
 * 无副作用、不碰 Android、**不读系统当前时间** —— 时间只从入参取，所以全部可断言。
 * 这是它被拆出来单测的全部意义：分组边界错了，真机上只表现为「有一段话不见了」，
 * 很难当场看出是哪一步错的。
 */

/** 会话列表的一行：要么是一条独立消息/卡片，要么是一个折叠的过程组。 */
internal sealed interface ChatDisplayEntry {
    data class Message(val item: ChatDisplayItem) : ChatDisplayEntry

    data class ProcessGroup(
        /** 稳定 key：取组内**首项**的稳定标识（列表重建时它就是同一组的锚点）。 */
        val id: String,
        val items: List<ChatDisplayItem>,
        val summary: ProcessGroupSummary,
    ) : ChatDisplayEntry
}

/** 过程组摘要：标题一行显示的内容。 */
internal data class ProcessGroupSummary(
    /**
     * 工具调用次数 —— **按真实调用条数计，不是按显示行数**。
     *
     * 为什么不能直接数 TOOL 行：[mergeToolRuns] 早就把**相邻**的调用合并成了一行，
     * 而那行里的 `tools` 列表每一条仍是一次真实调用。直接数行会把一次
     * 「连续 5 次 bash」报成「1 次工具调用」，摘要就废了。
     * 所以规则是：带逐条明细的行按明细条数计，没有明细的行（拿不到 tool/call 数据）算 1 次。
     */
    val toolCalls: Int,
    /** 上下文注入条数。 */
    val contextItems: Int,
    /** 组内最早时间（毫秒；无时间信息则 null）。 */
    val startedAt: Long?,
    /** 组内最晚时间（毫秒；无时间信息则 null）。 */
    val finishedAt: Long?,
) {
    /**
     * 组头的墙钟跨度（毫秒）；拿不到**可信**跨度时 null。
     *
     * 三种情况都返回 null 而不是 0 / 负数：
     * - 两头缺一（只有时间戳的一半是拿不到的）；
     * - `finishedAt < startedAt`：时钟倒退（机器休眠恢复、NTP 校时，实测日志里
     *   有一条 bash 差 9821 秒）—— 那是挂起时间不是执行时间。注意
     *   [groupChatDisplay] 取的是 min/max，它自己产不出倒序，这一条是给**直接构造**
     *   摘要的调用方（UI 从别处拿时间）兜底的；
     * - 跨度为 0：同一毫秒内的事件区间等于没有信息。
     *
     * 与 `toolDurationMs` 同一条纪律：宁可少一段，也不要瞎编一个数。
     *
     * 已知边界：这是**墙钟**跨度，所以「中间熄屏/挂起 3 小时」会被如实算进耗时 ——
     * 组级的 min/max 看不出中间断过，而单条工具耗时（`ToolCallDetail.durationMs`）
     * 只覆盖调用本身。用户要的那个数本来就是「从开始到结束有多久」，所以这里不额外修正，
     * 只把事实写清楚。
     */
    fun durationMs(): Long? {
        val start = startedAt ?: return null
        val end = finishedAt ?: return null
        val span = end - start
        return if (span > 0) span else null
    }

    /**
     * 「耗时 1 分 12 秒 · 3 项上下文 · 5 次工具调用」；无耗时时省略耗时段。
     *
     * 三段按固定顺序拼，段间用 ` · `：这是**组标题的稳定版式**，
     * 顺序变了用户会觉得每一组都长得不一样。
     */
    fun title(): String {
        val segments = ArrayList<String>(3)
        durationMs()?.let { segments += "耗时 " + formatProcessSpan(it) }
        if (contextItems > 0) segments += "$contextItems 项上下文"
        if (toolCalls > 0) segments += "$toolCalls 次工具调用"
        // 计数全 0、又没有时间时也得有一行字：组头不能是空白，空白看起来像渲染坏了。
        return if (segments.isEmpty()) FALLBACK_TITLE else segments.joinToString(SEGMENTS_SEPARATOR)
    }
}

/**
 * 把展示项按「过程 / 结论」二分：过程项聚成组，遇到消息类项则 flush。
 *
 * 只合并**连续**的过程项（中间夹了消息就断开）—— 中间那条消息说明确实是两段工作，
 * 硬并成一组会让「这一组干了什么」失去意义。
 *
 * @param timeOf 时间来源的**接缝**。当前 [ChatDisplayItem] 上没有时间字段
 *   （模型只有 id / seq / kind / body / actions / tools），所以默认取不到时间，
 *   摘要里也就**不显示**「耗时」段。等 UI 侧把事件时间带进展示项、或从
 *   `ChatHistory.events` 里按 id 查出来，传一个 `{ item -> timeById[item.id] }`
 *   就能点亮这一段，本文件不必再改。
 *
 *   默认值是「取不到」，**不是**「假装有时间」：宁可少一段，也不要编一个耗时。
 *   这不是缺陷，是诚实 —— `displayItems()` 的逐事件映射（`ChatHistoryMapping.kt`）
 *   目前没有把 `SessionEvent.time` 放进 [ChatDisplayItem]，这是要 Lead 决策的缺口。
 */
internal fun groupChatDisplay(
    items: List<ChatDisplayItem>,
    timeOf: (ChatDisplayItem) -> Long? = { null },
): List<ChatDisplayEntry> {
    val entries = ArrayList<ChatDisplayEntry>(items.size)
    var run = ArrayList<ChatDisplayItem>()

    fun flushRun() {
        if (run.isEmpty()) return
        // 先取出当前批次再换新表：ProcessGroup 持有的是这批本身，之后 run 指向新实例，
        // 不会因为后续 append 而被改写。
        val group = run
        entries += ChatDisplayEntry.ProcessGroup(
            id = group.first().id,
            items = group,
            summary = summarizeProcessGroup(group, timeOf),
        )
        run = ArrayList()
    }

    items.forEach { item ->
        if (isProcessItem(item)) {
            run += item
        } else {
            flushRun()
            entries += ChatDisplayEntry.Message(item)
        }
    }
    // 收尾那次 flush：末尾是过程项时不会再有消息来触发它。
    flushRun()
    return entries
}

/**
 * 这一项算「过程」还是「消息」。
 *
 * 判定表与文件头那张表一一对应，理由也写在那儿；这里只留一条**额外的保险**：
 * 任何带 [ChatDisplayItem.failure] 的项一律当消息。当前只有 FAILURE 会带这个字段，
 * 但只要谁把它带上了，它就不能被折进过程组 —— 用户找的就是那句「为什么失败了」。
 */
private fun isProcessItem(item: ChatDisplayItem): Boolean {
    if (item.failure != null) return false
    return when (item.kind) {
        ChatItemKind.CONTEXT, ChatItemKind.REASONING, ChatItemKind.TOOL -> true
        // 空助手消息算**过程**，不是消息 —— 这条是真机实测补的，理由见文件头「空助手消息」一节。
        ChatItemKind.ASSISTANT -> !item.hasVisibleBody()
        // USER / FAILURE，以及将来新增的任何 kind：默认消息。
        else -> false
    }
}

/**
 * 这一项是否**有可读的东西**：有正文，或带了图片。
 *
 * 判据只认「读得出来」：空白串、纯换行也算没有 —— 渲染出来就是一条零高度的行，
 * 却会把过程组劈成两段。
 */
private fun ChatDisplayItem.hasVisibleBody(): Boolean =
    body.isNotBlank() || attachments.isNotEmpty()

/** 数一遍组内有什么，并把时间夹到最早/最晚。 */
private fun summarizeProcessGroup(
    items: List<ChatDisplayItem>,
    timeOf: (ChatDisplayItem) -> Long?,
): ProcessGroupSummary {
    var toolCalls = 0
    var contextItems = 0
    items.forEach { item ->
        when (item.kind) {
            // 有明细就按明细条数（合并行），没明细就算 1 次（拿不到 tool/call 数据）。
            ChatItemKind.TOOL -> toolCalls += if (item.tools.isEmpty()) 1 else item.tools.size
            ChatItemKind.CONTEXT -> contextItems += 1
            else -> Unit
        }
    }
    // 时间一律从 timeOf 取（默认取不到），本函数不读任何当前时间。
    val times = items.mapNotNull(timeOf)
    return ProcessGroupSummary(
        toolCalls = toolCalls,
        contextItems = contextItems,
        startedAt = times.minOrNull(),
        finishedAt = times.maxOrNull(),
    )
}

/**
 * 「耗时」的措辞：不足 1 秒 → 「不足 1 秒」；<60 秒 → 「12 秒」；
 * ≥60 秒 → 「1 分 12 秒」，秒为 0 时省掉秒段（「2 分」）。
 *
 * 为什么单列「不足 1 秒」这一档：整秒取整会把 300 毫秒的组印成「耗时 0 秒」，
 * 而 0 秒读起来就是「没有耗时」，与「整段省略」的表达撞车，用户分不清。
 *
 * 为什么只到「分」为止：标题只有一行，真跑几小时的组在会话页里罕见，
 * 而且那时用户关心的是「它还在跑」而不是精确到小时。秒级取整（向下）足够。
 */
private fun formatProcessSpan(ms: Long): String {
    if (ms < 1_000) return "不足 1 秒"
    val totalSeconds = ms / 1_000
    if (totalSeconds < 60) return "$totalSeconds 秒"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
}

/** 计数全 0、又没有可信时间时的组标题 —— 组头不能是空白。 */
private const val FALLBACK_TITLE = "过程"

/** 摘要段之间的分隔符。 */
private const val SEGMENTS_SEPARATOR = " · "
