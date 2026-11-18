package io.github.westanke.dshcompanion.ui

import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.ui.chat.SessionGroupKey
import io.github.westanke.dshcompanion.ui.chat.SessionListEntry
import io.github.westanke.dshcompanion.ui.chat.buildSessionListEntries
import io.github.westanke.dshcompanion.ui.chat.clusterRows
import io.github.westanke.dshcompanion.ui.chat.dayBucketOf
import io.github.westanke.dshcompanion.ui.chat.groupSessionsByTime
import io.github.westanke.dshcompanion.ui.chat.visibleRowsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 会话列表的**时间分组 + 工作区聚簇**规则。
 *
 * 为什么值得测：这三件事在界面上只看得出「好像对了」——
 * 分桶边界写错（把昨天算进今天）要等到晚上八点看列表才看得见；
 * 聚簇顺序写错（工作区之间没按最近活跃排）看起来也像那么回事；
 * 「更早」默认收起这件事，一展开就露馅。
 *
 * 断言逐条对齐官方 dsh-Chat 的 `app/test/session_groups_test.dart`（138 行），
 * 两边看到的是同一批会话，规则必须一致 —— 官方改了这边不改，用户就会说「手机端和
 * 电脑端排得不一样」。
 */
class SessionGroupsTest {

    // 固定「现在」：2026-11-16 12:00 本地时间。
    private val now: Long = LocalDate.of(2026, 11, 16)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun ms(daysAgo: Long, hour: Int): Long = LocalDate.of(2026, 11, 16)
        .minusDays(daysAgo)
        .atTime(hour, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun session(
        id: String,
        updatedAt: Long,
        workspaceTitle: String? = null,
    ) = ChatSession(
        id = id,
        rootId = "root-1",
        cwd = "",
        updatedAt = updatedAt,
        running = false,
        blank = false,
        title = null,
        workspaceTitle = workspaceTitle,
    )

    // ── dayBucketOf：按本地日历天分桶 ──

    @Test
    fun todayAndYesterdayAndEarlierAreBucketedByLocalCalendarDay() {
        assertEquals(0, dayBucketOf(ms(0, 9), now))
        assertEquals(0, dayBucketOf(ms(0, 0), now))
        assertEquals(1, dayBucketOf(ms(1, 23), now))
        assertEquals(2, dayBucketOf(ms(2, 0), now))
        assertEquals(2, dayBucketOf(ms(30, 8), now))
    }

    @Test
    fun unknownTimestampsFallIntoTheEarlierBucket() {
        // 0 与负数都是「时间未知」，不能因此把会话顶到今天最前面。
        assertEquals(2, dayBucketOf(0L, now))
        assertEquals(2, dayBucketOf(-5L, now))
    }

    // ── 分组：顺序固定、空桶不产出 ──

    @Test
    fun groupsKeepTheOfficialOrderAndSkipEmptyBuckets() {
        val sessions = listOf(
            session("t1", ms(0, 10)),
            session("y1", ms(1, 10)),
            session("e1", ms(5, 10)),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        assertEquals(
            listOf(SessionGroupKey.TODAY, SessionGroupKey.YESTERDAY, SessionGroupKey.EARLIER),
            groups.map { it.key },
        )
    }

    @Test
    fun aBucketWithNoSessionsProducesNoGroup() {
        val groups = groupSessionsByTime(listOf(session("t1", ms(0, 10))), nowMillis = now)
        assertEquals(1, groups.size)
        assertEquals(SessionGroupKey.TODAY, groups[0].key)
    }

    // ── 桶内排序：时间倒序 ──

    @Test
    fun rowsInsideABucketAreSortedByTimeDescending() {
        val sessions = listOf(
            session("t1", ms(0, 8)),
            session("t2", ms(0, 11)),
            session("t3", ms(0, 9)),
        )
        val rows = groupSessionsByTime(sessions, nowMillis = now)[0].rows
        assertEquals(listOf("t2", "t3", "t1"), rows.map { it.session.id })
    }

    @Test
    fun sessionsWithTheSameTimestampAreOrderedByWorkspaceName() {
        val same = ms(0, 10)
        val sessions = listOf(
            session("z", same, workspaceTitle = "z-ws"),
            session("a", same, workspaceTitle = "a-ws"),
        )
        val rows = groupSessionsByTime(sessions, nowMillis = now)[0].rows
        assertEquals(listOf("a", "z"), rows.map { it.session.id })
    }

    // ── 聚簇：同工作区相邻，小节标题只出现一次 ──

    @Test
    fun rowsAreClusteredByWorkspaceWithOneSectionLabelEach() {
        // 官方用例的等价形态：a1(11) / b1(10) / a2(9) / a3(8)
        // → 小节标题序列 ['ws-a', null, null, 'ws-b']、会话序列 ['a1','a2','a3','b1']。
        val sessions = listOf(
            session("a1", ms(0, 11), workspaceTitle = "ws-a"),
            session("b1", ms(0, 10), workspaceTitle = "ws-b"),
            session("a2", ms(0, 9), workspaceTitle = "ws-a"),
            session("a3", ms(0, 8), workspaceTitle = "ws-a"),
        )
        val rows = groupSessionsByTime(sessions, nowMillis = now)[0].rows
        assertEquals(listOf("a1", "a2", "a3", "b1"), rows.map { it.session.id })
        assertEquals(
            listOf("ws-a", null, null, "ws-b"),
            rows.map { it.sectionLabel },
        )
    }

    @Test
    fun sessionsWithoutWorkspaceTitleLandInTheUngroupedSection() {
        val sessions = listOf(
            session("a", ms(0, 11)),
            session("b", ms(0, 10), workspaceTitle = "w"),
        )
        val rows = groupSessionsByTime(sessions, ungroupedLabel = "未分组", nowMillis = now)[0].rows
        assertEquals(listOf("未分组", "w"), rows.map { it.sectionLabel })
    }

    @Test
    fun clusterRowsKeepsTheFirstSeenWorkspaceOrder() {
        val bucket = listOf(
            session("a1", ms(0, 11), workspaceTitle = "ws-a"),
            session("b1", ms(0, 10), workspaceTitle = "ws-b"),
            session("a2", ms(0, 9), workspaceTitle = "ws-a"),
        )
        val rows = clusterRows(bucket, ungroupedLabel = "未分组")
        assertEquals(listOf("a1", "a2", "b1"), rows.map { it.session.id })
        assertEquals(listOf("ws-a", null, "ws-b"), rows.map { it.sectionLabel })
    }

    // ── 折叠：只有「更早」可折叠，默认收起 ──

    @Test
    fun theEarlierGroupIsCollapsibleAndTheOthersAreNot() {
        val sessions = listOf(
            session("t1", ms(0, 10)),
            session("e1", ms(5, 10)),
            session("e2", ms(6, 10)),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        val today = groups.first { it.key == SessionGroupKey.TODAY }
        val earlier = groups.first { it.key == SessionGroupKey.EARLIER }
        assertTrue(!today.collapsible)
        assertTrue(earlier.collapsible)
        assertEquals(2, earlier.count)
    }

    @Test
    fun aCollapsedGroupShowsNoRowsUntilItIsExpanded() {
        val sessions = listOf(
            session("t1", ms(0, 10)),
            session("e1", ms(5, 10)),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        val earlier = groups.first { it.key == SessionGroupKey.EARLIER }
        val today = groups.first { it.key == SessionGroupKey.TODAY }
        assertTrue(visibleRowsOf(earlier, expandedEarlier = false).isEmpty())
        assertEquals(1, visibleRowsOf(earlier, expandedEarlier = true).size)
        // 非折叠分组始终显示全部行。
        assertEquals(1, visibleRowsOf(today, expandedEarlier = false).size)
    }

    // ── 条目流：分组标题 / 小节标题 / 会话行 ──

    @Test
    fun theEntryStreamKeepsHeadersSectionsAndSessionsInOrder() {
        val sessions = listOf(
            session("a1", ms(0, 11), workspaceTitle = "ws-a"),
            session("a2", ms(0, 9), workspaceTitle = "ws-a"),
            session("e1", ms(5, 10), workspaceTitle = "ws-b"),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        val entries = buildSessionListEntries(groups, expandedEarlier = false)
        val kinds = entries.map {
            when (it) {
                is SessionListEntry.GroupHeader -> "header"
                is SessionListEntry.SectionLabel -> "section"
                is SessionListEntry.SessionEntry -> "session"
            }
        }
        // 「更早」收起：它的标题行仍在（可点展开），但行不出。
        assertEquals(listOf("header", "section", "session", "session", "header"), kinds)
    }

    @Test
    fun expandingTheEarlierGroupAddsItsRows() {
        val sessions = listOf(
            session("a1", ms(0, 11), workspaceTitle = "ws-a"),
            session("e1", ms(5, 10), workspaceTitle = "ws-b"),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        val expanded = buildSessionListEntries(groups, expandedEarlier = true)
        // 今天：标题 + 小节标题 + 1 行；更早：标题 + 小节标题 + 1 行。
        assertEquals(6, expanded.size)
        val headers = expanded.filterIsInstance<SessionListEntry.GroupHeader>()
        assertEquals(2, headers.size)
        // 展开后第二个分组标记为已展开，界面据此显示「N 条 · 收起」。
        assertTrue(headers[1].expanded)
    }

    @Test
    fun entryKeysAreUniqueSoLazyColumnDoesNotCrash() {
        val sessions = listOf(
            session("a1", ms(0, 11), workspaceTitle = "ws-a"),
            session("a2", ms(0, 9), workspaceTitle = "ws-a"),
            session("b1", ms(0, 8), workspaceTitle = "ws-b"),
            session("e1", ms(5, 10), workspaceTitle = "ws-b"),
        )
        val groups = groupSessionsByTime(sessions, nowMillis = now)
        val entries = buildSessionListEntries(groups, expandedEarlier = true)
        assertEquals(entries.size, entries.map { it.key }.distinct().size)
    }
}
