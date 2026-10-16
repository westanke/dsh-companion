package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「轨迹」页的数据：把历史事件按**轮次**分组，组内是按时序排列的节点。
 *
 * ## 它与会话流（`displayItems`）的区别
 *
 * 会话流是**给人读的**：只留正文、推理摘要、工具摘要，其余全部折叠或丢弃。
 * 轨迹是**给人查的**：每一轮里到底发生了什么、各花了多久、哪些是工具调用 ——
 * 排查「这一步为什么慢」「它到底调了什么」时看的是这个页面。
 *
 * 所以这里**不做过滤**：不认的事件也留成 [TrajectoryNodeKind.Other] 节点 ——
 * 轨迹页的价值恰恰在于「把当时发生的都列出来」，隐藏它等于让它失去意义。
 *
 * ## 数据全部来自历史事件，不需要服务端额外配合
 *
 * 判据与渲染同源：事件类型名取自 `ChatHistoryMapping.kt` 的既有映射规则，
 * `turn` 取自事件自己的 `data.turn`（规范化时由插件写入，见 `chat-adapter.ts`）。
 */
internal enum class TrajectoryNodeKind {
    User,
    Assistant,
    Tool,
    Deliverable,
    TurnEnd,
    /** 认得的历史事件类型之外的一切 —— 刻意保留，见上面类文档。 */
    Other,
}

/** 时间轴上的一个节点。 */
internal data class TrajectoryNode(
    val seq: Int,
    val time: Long,
    val kind: TrajectoryNodeKind,
    /** 短标签（工具名之类）；没有就为 null，UI 用 [kind] 的本地化名兜底。 */
    val label: String? = null,
    /**
     * 事件的**原始 `data`**，供详情里的「原始」一栏展示。
     *
     * 刻意存原样而不是解析后的字段：轨迹页是排查工具，用户要能看到「当时真正收到了什么」——
     * 只给解析结果等于把「我们不认识的字段」永久藏起来。
     */
    val raw: JsonObject? = null,
)

/** 一轮（一个 `turn`）及其节点。 */
internal data class TrajectoryTurn(
    val turn: Int,
    val nodes: List<TrajectoryNode>,
) {
    /** 按序号排好的节点（起止与耗时都以**序号顺序**为准，不以数组顺序）。 */
    private val ordered: List<TrajectoryNode> get() = nodes.sortedBy { it.seq }

    /** 这一轮第一个节点的时间；没有节点时为 null。 */
    val startedAt: Long? get() = ordered.firstOrNull()?.time

    /** 最后一个节点的时间。 */
    val endedAt: Long? get() = ordered.lastOrNull()?.time

    /**
     * 这一轮的墙钟跨度。
     *
     * 三个「不给值」的情形，每一个都比显示一个数字更诚实：
     * - **时间戳不随序号单调**（乱序）：说明这份历史的 `time` 不可信（时钟回拨、上游只填了部分、
     *   或事件数组顺序本身乱了）。此时算出来的只是「最晚减最早」，与「这一轮花了多久」不是一回事。
     * - **跨度为零**（全部同一时刻，常见于时间戳缺失被填 0）：显示 `0 秒` 会让人以为「瞬间完成」。
     * - 节点不足两个。
     */
    val durationMillis: Long?
        get() {
            val sequence = ordered
            if (sequence.size < 2) return null
            var previous = sequence.first().time
            for (node in sequence.drop(1)) {
                if (node.time < previous) return null
                previous = node.time
            }
            val end = sequence.last().time
            val start = sequence.first().time
            return if (end > start) end - start else null
        }

    val toolCalls: Int get() = nodes.count { it.kind == TrajectoryNodeKind.Tool }
}

/**
 * 把历史折成轨迹。
 *
 * - 轮次按 `turn` 升序，组内按 `seq` 升序（事件在数组里不一定有序，不能依赖顺序）；
 * - `turn` 取不到的事件归入 [UNKNOWN_TURN]（0）——**不丢弃**，它们在「发生过什么」这件事上
 *   仍然是证据，只是归属不明；
 * - `history == null`（还没加载）返回空列表。
 */
internal fun trajectoryOf(history: ChatHistory?): List<TrajectoryTurn> {
    val events = history?.events.orEmpty()
    if (events.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<Int, MutableList<TrajectoryNode>>()
    for (entry in events) {
        val event = entry.event
        val data = event.data as? JsonObject
        val turn = (data?.get("turn") as? JsonPrimitive)?.content?.toIntOrNull() ?: UNKNOWN_TURN
        grouped.getOrPut(turn) { mutableListOf() }.add(
            TrajectoryNode(
                seq = event.seq,
                time = event.time,
                kind = trajectoryKindOf(event.type),
                label = toolLabelOf(event.type, data),
                raw = data,
            ),
        )
    }
    return grouped.entries
        .sortedBy { it.key }
        .map { (turn, nodes) -> TrajectoryTurn(turn, nodes.sortedBy { it.seq }) }
}

/** `turn` 取不到时的归属。用 0 而不是 -1：负数会在排序与展示上制造新的特例。 */
internal const val UNKNOWN_TURN = 0

/**
 * 轨迹页用的**短耗时**：不足一分钟给 `12.3s`，否则给 `3:05`。
 *
 * 为什么不用 `strings.xml` 的模板：时间数字在各语言里的写法基本一致，
 * 而把它做成资源会让这里的纯函数失去可测性（要 Android 环境才能取字符串）。
 * 单位符号 `s` 与 `m:ss` 都是国际通用写法。
 */
internal fun shortDuration(millis: Long): String {
    if (millis < 60_000L) {
        val tenths = millis / 100L
        return "${tenths / 10}.${tenths % 10}s"
    }
    val totalSeconds = millis / 1_000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun trajectoryKindOf(type: String): TrajectoryNodeKind = when (type) {
    "user/message" -> TrajectoryNodeKind.User
    "assistant/message" -> TrajectoryNodeKind.Assistant
    "tool/call" -> TrajectoryNodeKind.Tool
    "deliverables/presented" -> TrajectoryNodeKind.Deliverable
    "turn/end" -> TrajectoryNodeKind.TurnEnd
    else -> TrajectoryNodeKind.Other
}

/**
 * 工具的标签取 `data.name`；内核若把它放在别处就返回 null（UI 用类型名兜底）。
 * 刻意**不**去猜多个候选字段名 —— 猜错会显示一个假的工具名，而「不知道」是可接受的展示。
 */
private fun toolLabelOf(type: String, data: JsonObject?): String? {
    if (type != "tool/call") return null
    val name = (data?.get("name") as? JsonPrimitive)?.content ?: return null
    return name.takeIf { it.isNotBlank() }
}
