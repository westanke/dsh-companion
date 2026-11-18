package io.github.westanke.dshcompanion.ui.chat

import io.github.westanke.dshcompanion.data.ChatSession
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 会话列表的**时间分组 + 工作区聚簇**（纯逻辑，不依赖 Compose）。
 *
 * 口径照抄官方 dsh-Chat 的 `app/lib/ui/session_groups.dart`（131 行），因为两边
 * 看到的是同一批会话：官方抽屉是「今天 / 昨天 / 更早」三段，每段里按工作区聚簇，
 * 手机端若还是一列平铺，同一件事两种排法，用户会以为看到的不是同一个东西。
 *
 * 刻意做成**纯函数**（无 Compose、无 Context、无资源引用），这样分组规则能被单测
 * 钉住：分桶边界、聚簇顺序、折叠行为这三件事在界面上只看得出「好像对了」，
 * 边界写错（比如把昨天算进今天）要等真机才看得见。
 *
 * 唯一带「文案」的地方是 [groupSessionsByTime] 的 `ungroupedLabel` 参数：
 * 「未分组」是要翻译的，官方 Dart 直接写死中文，这里把它交给调用方传进来。
 */
internal const val SESSION_GROUP_UNGROUPED_DEFAULT = "未分组"

/** 分组键：与官方 `SessionGroup.key` 同名同序（today → yesterday → earlier）。 */
internal enum class SessionGroupKey(val id: String) {
    TODAY("today"),
    YESTERDAY("yesterday"),
    EARLIER("earlier"),
}

/**
 * 分组里的一行。
 *
 * [sectionLabel] 为 null 表示这一行上方**不再**重复画工作区小节标题
 * （同一个工作区的第二、三行都靠它压掉重复标题）。
 */
internal data class SessionGroupRow(
    val session: ChatSession,
    val sectionLabel: String?,
)

/**
 * 一个时间分组。
 *
 * [collapsible] 为 true 的分组（「更早」）默认收起，[visibleRowsOf] 会返回空列表。
 */
internal data class SessionGroup(
    val key: SessionGroupKey,
    val rows: List<SessionGroupRow>,
    val collapsible: Boolean = false,
) {
    val count: Int get() = rows.size
}

/**
 * 会话落到哪个时间桶：0 = 今天、1 = 昨天、2 = 更早。
 *
 * 按**本地日历天**算，跟官方 `dayBucketOf` 一致：跨时区时「今天」是用户所在时区的今天，
 * 不是 UTC 的今天。官方用 `((nowDay - thenDay) / dayMs).round()`，
 * 这里直接用 `ChronoUnit.DAYS.between(thenDate, todayDate)` —— 对本地日期而言两者等价，
 * 但后者没有除法截断的坑。
 *
 * `updatedAt <= 0` 归「更早」：内核给的时间戳可能是 0（未知），不能因此把它顶到最前面。
 */
internal fun dayBucketOf(updatedAt: Long, nowMillis: Long = System.currentTimeMillis()): Int {
    if (updatedAt <= 0L) return 2
    val zone = ZoneId.systemDefault()
    val thenDate = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val todayDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val diffDays = ChronoUnit.DAYS.between(thenDate, todayDate)
    return when {
        diffDays <= 0L -> 0
        diffDays == 1L -> 1
        else -> 2
    }
}

/**
 * 会话所属工作区的小节标题；`workspaceTitle` 为空则返回 null（调用方补「未分组」）。
 */
internal fun workspaceLabelOf(session: ChatSession): String? =
    session.workspaceTitle?.takeIf { it.isNotEmpty() }

/**
 * 桶内排序：时间倒序；时间完全相同则按工作区名升序。
 *
 * 第二条是为了**结果稳定** —— 同一毫秒的两条会话顺序不能每次刷新都换。
 */
private fun compareSessions(a: ChatSession, b: ChatSession, ungroupedLabel: String): Int {
    val byTime = b.updatedAt.compareTo(a.updatedAt)
    if (byTime != 0) return byTime
    val labelA = workspaceLabelOf(a) ?: ungroupedLabel
    val labelB = workspaceLabelOf(b) ?: ungroupedLabel
    return labelA.compareTo(labelB)
}

/**
 * 桶内按**工作区聚簇**。
 *
 * 规则（官方 `_clusterRows`）：工作区按首次出现顺序排列 —— 由于桶已按时间倒序排过，
 * 「首次出现」= 该工作区在这个桶里最近的那条会话，所以工作区之间是按最近活跃时间排的；
 * 同一工作区内部保持时间倒序；每个工作区的第一行带上小节标题，其余不带。
 */
internal fun clusterRows(bucket: List<ChatSession>, ungroupedLabel: String): List<SessionGroupRow> {
    val order = LinkedHashMap<String, MutableList<ChatSession>>()
    for (session in bucket) {
        val label = workspaceLabelOf(session) ?: ungroupedLabel
        order.getOrPut(label) { mutableListOf() }.add(session)
    }
    return order.entries.flatMap { (label, sessions) ->
        sessions.mapIndexed { index, session ->
            SessionGroupRow(session = session, sectionLabel = if (index == 0) label else null)
        }
    }
}

/**
 * 把一批会话分成「今天 / 昨天 / 更早」三组。
 *
 * 空桶**不产出**分组（官方同款：今天没有会话时列表里就不该有一个空标题）。
 * 「更早」那组 `collapsible = true`，默认收起。
 */
internal fun groupSessionsByTime(
    sessions: List<ChatSession>,
    ungroupedLabel: String = SESSION_GROUP_UNGROUPED_DEFAULT,
    nowMillis: Long = System.currentTimeMillis(),
): List<SessionGroup> {
    val today = mutableListOf<ChatSession>()
    val yesterday = mutableListOf<ChatSession>()
    val earlier = mutableListOf<ChatSession>()
    for (session in sessions) {
        when (dayBucketOf(session.updatedAt, nowMillis)) {
            0 -> today += session
            1 -> yesterday += session
            else -> earlier += session
        }
    }
    val comparator = Comparator<ChatSession> { a, b -> compareSessions(a, b, ungroupedLabel) }
    val groups = mutableListOf<SessionGroup>()
    if (today.isNotEmpty()) {
        groups += SessionGroup(
            key = SessionGroupKey.TODAY,
            rows = clusterRows(today.sortedWith(comparator), ungroupedLabel),
        )
    }
    if (yesterday.isNotEmpty()) {
        groups += SessionGroup(
            key = SessionGroupKey.YESTERDAY,
            rows = clusterRows(yesterday.sortedWith(comparator), ungroupedLabel),
        )
    }
    if (earlier.isNotEmpty()) {
        groups += SessionGroup(
            key = SessionGroupKey.EARLIER,
            rows = clusterRows(earlier.sortedWith(comparator), ungroupedLabel),
            collapsible = true,
        )
    }
    return groups
}

/**
 * 分组当前应该显示哪些行。
 *
 * 收起的「更早」返回空列表（官方 `visibleRowsOf` 同款）。
 */
internal fun visibleRowsOf(group: SessionGroup, expandedEarlier: Boolean): List<SessionGroupRow> =
    if (group.collapsible && !expandedEarlier) emptyList() else group.rows

/**
 * 列表要渲染的条目流（分组标题 / 工作区小节标题 / 会话行）。
 *
 * 做成扁平列表而不是嵌套分组列表：LazyColumn 里嵌套同向滚动容器会丢滚动位置，
 * 官方也是「一个列表 + 分组标题行」的扁平结构。
 */
internal sealed interface SessionListEntry {
    val key: String

    /** 时间分组标题行（「今天」「昨天」「更早」）。 */
    data class GroupHeader(
        val group: SessionGroup,
        val expanded: Boolean,
    ) : SessionListEntry {
        override val key: String get() = "group-${group.key.id}"
    }

    /** 工作区小节标题。 */
    data class SectionLabel(
        val groupKey: SessionGroupKey,
        val label: String,
        val index: Int,
    ) : SessionListEntry {
        override val key: String get() = "section-${groupKey.id}-$index-$label"
    }

    /** 一条会话。 */
    data class SessionEntry(val session: ChatSession) : SessionListEntry {
        override val key: String get() = "session-${session.id}"
    }
}

/**
 * 把分组摊平成条目流。
 *
 * 收起的分组**只留标题行、不出行**（官方 `_sectionWidgets` 同款：标题行始终渲染，
 * 这样「N 条 · 展开」可点；行按 `visibleRowsOf` 判断）。
 */
internal fun buildSessionListEntries(
    groups: List<SessionGroup>,
    expandedEarlier: Boolean,
): List<SessionListEntry> {
    val entries = mutableListOf<SessionListEntry>()
    for (group in groups) {
        entries += SessionListEntry.GroupHeader(group = group, expanded = expandedEarlier)
        val rows = visibleRowsOf(group, expandedEarlier)
        if (rows.isEmpty()) continue
        var sectionIndex = 0
        for (row in rows) {
            val label = row.sectionLabel
            if (label != null) {
                entries += SessionListEntry.SectionLabel(
                    groupKey = group.key,
                    label = label,
                    index = sectionIndex,
                )
                sectionIndex++
            }
            entries += SessionListEntry.SessionEntry(row.session)
        }
    }
    return entries
}
