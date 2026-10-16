package io.github.westanke.dshcompanion.data

/**
 * 历史里**该显示**的那部分：滤掉思考，并按开关决定是否显示上下文注入。
 *
 * ## 为什么要单独一个函数
 *
 * 这段规则原来内联在 `ChatDetail` 的 Composable 体里，有两个后果：
 * 1. **测不了**（它需要 Android 环境才能跑到），于是「思考不进消息流」这条口径
 *    只能靠肉眼守；
 * 2. 每次重组都会重跑一遍 —— 而 `displayItems()` 是**逐事件映射的纯函数**，
 *    流式期间每秒几十帧，每帧都把几百条历史事件重新映射一遍再跑两次 filter，
 *    纯粹白烧 CPU。
 *
 * 抽出来之后：规则可测（[visibleItemsOf]），而上层只剩一个 `remember(history, showContext)`
 * 包住它 —— `history` 是整份替换的对象（只有刷新才变），引用相等就是「没变」的可靠判据。
 *
 * ## 两条过滤口径（与 WebView 页对齐）
 *
 * - **思考永不显示**：翻历史的人不读它；流式那一段也不进列表，由底部思考条
 *   （`ChatThinkBar`）承载 —— 它是「运行状态」而不是「会话内容」。
 * - **上下文注入默认隐藏**：系统每轮塞进来的运行/时间上下文与后台任务通知，
 *   会在每条消息之间插一排折叠块，把正文挤得七零八落。开关在设置页。
 */
internal fun visibleHistoryItems(history: ChatHistory?, showContext: Boolean): List<ChatDisplayItem> =
    visibleItemsOf(history?.displayItems().orEmpty(), showContext)

/** 过滤核心（不碰 history，因此测试可以直接塞一串展示项）。 */
internal fun visibleItemsOf(items: List<ChatDisplayItem>, showContext: Boolean): List<ChatDisplayItem> =
    items
        .filter { it.kind != ChatItemKind.REASONING }
        .filter { showContext || it.kind != ChatItemKind.CONTEXT }
