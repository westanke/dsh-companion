package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonObject

/**
 * 工具调用的**实时一路**（bridge v2.12.0 起）。
 *
 * 此前这条路上什么都没有：内核的 `tool/call` / `tool/result` 在桥里落进兜底分支，
 * `event.data` 整段被丢，客户端只能靠防抖重拉历史才看得到工具行 ——
 * 「正在跑哪个工具」这件事在运行期是看不见的。
 *
 * bridge v2.12.0 把这两类内核事件原样透传成 `chat.tool.call` / `chat.tool.result`
 * （见 `dsh-remote-bridge/docs/project/API.md` §工具调用的实时事件），
 * 并给 `chat.message.delta` 加了两个 kind：
 * - `tool-call`：参数还在流式生成中（官方 WebUI 的 preparing 态），
 *   载荷 `{callId?, name?, argumentsDelta?}`，**没有 `text`**；
 * - `tool-call-end`：block-end，参数定稿（preparing → running 的边界），
 *   载荷 `{callId?, name?, arguments?}`，同样**没有 `text`**。
 *
 * 这个文件只放**纯逻辑**：解析 + 判据 + 构造展示项，不碰 Compose、不碰网络。
 * 界面层不直接认这些事件字面量（AGENTS.md §4.1：事件字面量归 data/ 层）。
 */

/**
 * 实时工具调用的阶段。
 *
 * 阶段口径照官方 WebUI（`@deepseek-ai/dsh-client-ui-chat`）：
 * `tool-call` 增量帧是 preparing，`chat.tool.call` 才是 running（工具真的开始跑了），
 * `chat.tool.result` 是 done。**只前进不倒退**：晚到的一帧不会把 running 打回 preparing。
 */
enum class ToolStreamPhase {
    /** 参数还在流式生成（官方 preparing 态）：只知道工具名与部分参数。 */
    PREPARING,

    /** 参数已定稿、工具正在执行（`chat.tool.call`）。 */
    RUNNING,

    /** 已拿到结果（`chat.tool.result`）。 */
    DONE,
}

/**
 * 一次实时工具调用的状态。
 *
 * 为什么要自己攒一份状态而不是每帧直接渲染：桥对同一次调用可能发好几帧
 * （`tool-call` 增量 → `tool-call-end` → `chat.tool.call` → `chat.tool.result`），
 * 帧是**增量**，行是**汇总**。每帧渲一行的话，用户会看到同一次调用出现四行。
 */
data class ToolStreamEntry(
    val callId: String,
    val name: String = "",
    val arguments: String = "",
    val phase: ToolStreamPhase = ToolStreamPhase.PREPARING,
    /** 失败标记：`chat.tool.result` 的 `message.isError`。 */
    val failed: Boolean = false,
    /** 结果正文；null = 还没拿到结果。 */
    val result: String? = null,
    /** 内核侧失败分类（`error.code`）：`ASK_CANCELLED` 等。null = 没失败。 */
    val errorCode: String? = null,
    /** 工具开始执行的时间（毫秒，来自帧的 `time`）；null = 还没到 running。 */
    val startedAt: Long? = null,
    /** 工具结束的时间（毫秒）；null = 还在跑。 */
    val finishedAt: Long? = null,
    /**
     * 本帧的 `arguments` 是**增量**而不是整份参数。
     *
     * 官方 WebUI 的 `applyDelta` 就是这么做的：参数还在流式生成时收到的是
     * `argumentsDelta`，逐段 `append`；`tool-call-end` 才给整份 `arguments` 并定稿。
     * 没有这个标记的话，增量帧会把已收到的参数**覆盖**掉只剩最后一段。
     */
    val argumentsAppend: Boolean = false,
) {
    /** 执行耗时；没起止时间或时钟倒退时为 null（宁可少一行，也不瞎编）。 */
    val durationMs: Long?
        get() {
            val start = startedAt ?: return null
            val end = finishedAt ?: return null
            val delta = end - start
            return if (delta < 0) null else delta
        }
}

/**
 * 一个会话的实时工具调用集合。
 *
 * 按 `callId` 配对：桥对同一次调用可能发好几帧，帧是增量、行是汇总。
 * 没有 `callId` 的帧（老内核 / 某些帧缺字段）直接丢弃 ——
 * 配不上结果帧的一行工具调用对用户没有意义（展开后是空的）。
 */
data class ToolStreamState(
    val sessionId: String = "",
    val entries: List<ToolStreamEntry> = emptyList(),
) {
    /**
     * 把一帧并进来。
     *
     * 幂等：同 `callId` 的后到帧**覆盖**对应字段，而不是追加新条目 ——
     * 这是 bridge 侧写明的去重口径（「按 `callId` 去重，同 id 的后到帧覆盖 preparing/running 态」）。
     */
    fun merge(entry: ToolStreamEntry): ToolStreamState {
        val index = entries.indexOfFirst { it.callId == entry.callId }
        if (index < 0) return copy(entries = entries + entry)
        val old = entries[index]
        val merged = ToolStreamEntry(
            callId = old.callId,
            name = entry.name.ifEmpty { old.name },
            // 参数增量在 preparing 阶段追加；定稿帧整份替换；
            // 定稿之后再来增量帧（乱序）就保持原样，不让半截参数盖掉整份。
            arguments = when {
                entry.argumentsAppend && old.phase == ToolStreamPhase.PREPARING ->
                    old.arguments + entry.arguments
                entry.argumentsAppend -> old.arguments
                else -> entry.arguments.ifEmpty { old.arguments }
            },
            // 阶段只前进不倒退。
            phase = if (entry.phase > old.phase) entry.phase else old.phase,
            failed = old.failed || entry.failed,
            result = entry.result ?: old.result,
            errorCode = entry.errorCode ?: old.errorCode,
            startedAt = old.startedAt ?: entry.startedAt,
            finishedAt = old.finishedAt ?: entry.finishedAt,
        )
        val next = entries.toMutableList()
        next[index] = merged
        return copy(entries = next)
    }

    /**
     * 清掉已经出结果、且历史里已经有同一 `callId` 的条目 —— 留着就是重复。
     *
     * 两个条件缺一不可：光有结果不够（结果刚到、历史还没刷回来，撤掉会让行闪一下），
     * 光有历史也不够（历史里的工具行还没有结果，撤掉实时行就丢了「已完成」这件事）。
     */
    fun pruneSettled(historyCallIds: Set<String> = emptySet()): ToolStreamState =
        copy(entries = entries.filterNot { it.phase == ToolStreamPhase.DONE && it.callId in historyCallIds })
}

/**
 * 历史里已经落库的工具调用 `callId`。
 *
 * 用途只有一个：实时行与历史行**去重**。同一次工具调用，实时区有一行、历史里也有一行，
 * 两行同时显示就是「同一次调用出现了两次」。
 *
 * 只认内核原名 `tool/call` —— 审批事件也带 `callId`，混进来会误伤。
 */
internal fun ChatHistory.toolCallIds(): Set<String> =
    events.asSequence()
        .filter { it.event.type == "tool/call" }
        .mapNotNull { (it.event.data as? JsonObject)?.stringValue("callId") }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * 历史里已经落库的**工具结果** `callId`。
 *
 * 这是「实时行让位给历史行」的判据：只有历史里**连结果都有**了，实时那一行才是多余的。
 * 历史里有调用但还没结果，说明这一次调用还没写完 —— 历史那一行此时是**残缺的**
 * （展开后没有输出），留着它就是给用户看半截。
 */
internal fun ChatHistory.toolResultCallIds(): Set<String> =
    events.asSequence()
        .filter { it.event.type == "tool/result" }
        .mapNotNull { entry ->
            val message = (entry.event.data as? JsonObject)?.objectValue("message") ?: return@mapNotNull null
            message.stringValue("toolCallId") ?: message.objectValue("source")?.stringValue("callId")
        }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * 把历史展示项里「已经有实时行顶着」的那些工具明细摘掉。
 *
 * 同一次调用只能出现一行：要么是历史行（已经写完），要么是实时行（正在跑）。
 * 两行都在就是「同一次调用出现了两次」。
 *
 * 摘掉之后如果这一行一条明细都不剩了，整行丢掉 —— 它整行就是在讲这一次调用。
 * 还剩别的明细就保留（那一次调用由实时行讲）。
 */
internal fun List<ChatDisplayItem>.withoutToolCalls(callIds: Set<String>): List<ChatDisplayItem> {
    if (callIds.isEmpty()) return this
    return mapNotNull { item ->
        if (item.kind != ChatItemKind.TOOL || item.tools.isEmpty()) return@mapNotNull item
        val kept = item.tools.filterNot { it.callId in callIds }
        when {
            kept.size == item.tools.size -> item
            kept.isEmpty() -> null
            else -> item.copy(tools = kept)
        }
    }
}

/**
 * 桥侧 `chat.tool.call` 的载荷 → 一次工具调用。
 *
 * 载荷是内核 `tool/call` 的 `event.data` **原样透传**（bridge v2.12.0 的口径）：
 * `{callId, name, arguments, turn, step}`。
 *
 * 没有 `callId` 时返回 null —— 没有它就没法跟 `chat.tool.result` 配对，
 * 而配不上的一行工具调用对用户没有意义（详情展开里是空的）。
 */
internal fun toolCallFromFrame(data: JsonObject?, time: Long): ToolStreamEntry? {
    val obj = data ?: return null
    val callId = obj.stringValue("callId")?.takeIf { it.isNotEmpty() } ?: return null
    return ToolStreamEntry(
        callId = callId,
        name = obj.stringValue("name").orEmpty(),
        arguments = obj.stringValue("arguments").orEmpty(),
        phase = ToolStreamPhase.RUNNING,
        startedAt = time,
    )
}

/**
 * 桥侧 `chat.tool.result` 的载荷 → 一次工具调用的结果。
 *
 * 载荷 `{turn, step, message, error?}`；`message` 是**持久化的工具消息**
 * （`role:'tool'` / `toolCallId` / `isError` / `content[{type,text}]`）。
 *
 * 配对键取 `message.toolCallId`；取不到时退回 `message.source.callId`
 * （两个字段实测完全相等，见 `ChatToolPresentation.kt` 的说明）。
 */
internal fun toolResultFromFrame(data: JsonObject?, time: Long): ToolStreamEntry? {
    val obj = data ?: return null
    val message = obj.objectValue("message") ?: return null
    val callId = message.stringValue("toolCallId")
        ?: message.objectValue("source")?.stringValue("callId")
        ?: return null
    val error = obj.objectValue("error")
    return ToolStreamEntry(
        callId = callId,
        phase = ToolStreamPhase.DONE,
        failed = message.booleanValue("isError") == true || error != null,
        result = toolResultText(message),
        errorCode = error?.stringValue("code"),
        finishedAt = time,
    )
}

/**
 * delta 帧里的 `tool-call` / `tool-call-end` → 一次工具调用的参数增量。
 *
 * - `tool-call`：参数还在流式生成（官方 preparing 态），带 `argumentsDelta` **增量**；
 * - `tool-call-end`：参数定稿，带完整 `arguments`。
 *
 * 这两种帧**没有 `text`** —— 所以旧的 `chatDelta()` 会因为 `text` 必填而整帧丢掉。
 * 这里单独解析，不去动老的正文路径。
 *
 * 没有 `callId` 的帧返回 null：配不上结果帧的一行，对用户没有意义。
 */
internal fun toolCallDeltaFromFrame(data: JsonObject?): ToolStreamEntry? {
    val obj = data ?: return null
    val kind = obj.stringValue("kind") ?: return null
    if (kind != "tool-call" && kind != "tool-call-end") return null
    val callId = obj.stringValue("callId")?.takeIf { it.isNotEmpty() } ?: return null
    return ToolStreamEntry(
        callId = callId,
        name = obj.stringValue("name").orEmpty(),
        arguments = when (kind) {
            "tool-call" -> obj.stringValue("argumentsDelta").orEmpty()
            else -> obj.stringValue("arguments").orEmpty()
        },
        phase = if (kind == "tool-call-end") ToolStreamPhase.RUNNING else ToolStreamPhase.PREPARING,
        argumentsAppend = kind == "tool-call",
    )
}

/**
 * 事件类型 → 一次实时工具调用的增量帧。
 *
 * 事件字面量集中在这里（AGENTS.md §4.1：事件字面量归 data/ 层），
 * 上层只按「这一帧属于哪一类」来分派，不用认字面量。
 *
 * 认不出来的类型返回 null —— 老的正文 delta（text / reasoning）不该走这条路。
 */
internal fun toolStreamEntryFromFrame(type: String, data: JsonObject?, time: Long): ToolStreamEntry? =
    when (type) {
        "chat.tool.call" -> toolCallFromFrame(data, time)
        "chat.tool.result" -> toolResultFromFrame(data, time)
        "chat.message.delta" -> toolCallDeltaFromFrame(data)
        else -> null
    }

/**
 * 一次实时工具调用 → 一条展示项。
 *
 * 复用历史那条路的**同一套**措辞与渲染：[toolActionsFor] 给动作短语（官方口径），
 * `ToolCallDetail` 给可展开的明细（输入 / 输出 / 成败 / 耗时）。
 *
 * 于是实时行与历史行**长得一样** —— 用户不会以为跑的是两套东西。
 *
 * @param stageLabel 阶段文案（官方口径「准备调用工具」/「正在调用工具」）。
 *   null = 不带阶段前缀（已经出结果的行用动作短语，跟历史行一致）。
 */
internal fun toolStreamDisplayItem(entry: ToolStreamEntry, stageLabel: String? = null): ChatDisplayItem {
    val actions = toolActionsFor(entry.name, entry.arguments)
    val action = actions.joinToString("，").ifEmpty { entry.name }
    val title = stageLabel?.takeIf { it.isNotBlank() }
        ?.let { if (action.isBlank()) it else "$it · $action" }
        ?: action
    val detail = ToolCallDetail(
        callId = entry.callId,
        name = entry.name,
        arguments = entry.arguments,
        result = entry.result,
        failed = entry.failed,
        durationMs = entry.durationMs,
    )
    return ChatDisplayItem(
        id = "live-tool-${entry.callId}",
        seq = Int.MAX_VALUE,
        kind = ChatItemKind.TOOL,
        title = title,
        body = argumentsSummary(entry.arguments) ?: entry.name,
        // 还在跑就标 streaming —— 渲染层据此显示「进行中」的观感。
        streaming = entry.phase != ToolStreamPhase.DONE,
        actions = actions,
        tools = listOf(detail),
    )
}

/**
 * `chat.message.committed` 的 `hasReplyContent` → 这条 assistant 消息是不是**最终回答**。
 *
 * 判据与官方 WebUI 的 `hasAssistantReplyContent` 逐字同义（bridge v2.12.0 的口径）：
 * `reasoning` 与 `tool-call` 块不算正文，`text` 块按 `trim()` 判空，其余块类型算正文。
 *
 * **返回 null 表示「桥没说」**（老桥没这个字段）—— 调用方据此**保持旧行为**，
 * 而不是当成 false。老桥的行为是「每条 assistant 消息都当结束」，
 * 改成 false 会让老桥上的回合永远不收尾 —— 那是把兼容做反了。
 */
internal fun hasReplyContentOf(data: JsonObject?): Boolean? {
    val obj = data ?: return null
    return obj.booleanValue("hasReplyContent")
}
