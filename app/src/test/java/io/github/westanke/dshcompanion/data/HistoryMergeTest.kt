package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.mergeRefreshedHistory
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新历史时**不能丢掉已经加载出来的更早内容**。
 *
 * 真机实测的现象：点「加载更早消息」→ 内容出现 → 一秒后整屏变空白。
 * 根因是刷新拿回的是最后一页，直接替换会把更早的那几页抹掉，列表从 5 条缩回 2 条，
 * 而视口还停在原来的像素偏移上，于是落在空白区。
 */
class HistoryMergeTest {

    private fun entry(seq: Int) = HistoryEntry(
        event = SessionEvent("assistant/message", seq, seq.toLong(), JsonPrimitive("m$seq")),
    )

    private fun page(vararg seqs: Int, hasMore: Boolean = true) =
        ChatHistory(events = seqs.map(::entry), hasMore = hasMore)

    @Test
    fun refreshKeepsOlderPagesAlreadyLoaded() {
        val previous = page(1, 2, 3, 4, 5)
        val fresh = page(10, 11, 12)

        val merged = mergeRefreshedHistory(previous, fresh)

        assertEquals(listOf(1, 2, 3, 4, 5, 10, 11, 12), merged.events.map { it.event.seq })
        // 更早的历史还在，所以「还能往前翻」的结论要在。
        assertTrue(merged.hasMore)
    }

    @Test
    fun refreshWithoutLoadedHistoryJustTakesTheFreshPage() {
        val fresh = page(10, 11)

        assertEquals(fresh, mergeRefreshedHistory(null, fresh))
        assertEquals(fresh, mergeRefreshedHistory(page(), fresh))
    }

    @Test
    fun refreshOverlappingWhatWeAlreadyHaveDoesNotDuplicate() {
        val previous = page(1, 2, 10, 11)
        val fresh = page(10, 11, 12, hasMore = false)

        val merged = mergeRefreshedHistory(previous, fresh)

        assertEquals(listOf(1, 2, 10, 11, 12), merged.events.map { it.event.seq })
        // 列表第一项是 seq=1，而它之前还有更早的（上一页报的是 hasMore=true），结论要延续。
        assertTrue(merged.hasMore)
    }

    @Test
    fun refreshDropsNothingWhenPreviousIsTheSameTail() {
        val previous = page(10, 11, 12)
        val fresh = page(10, 11, 12, hasMore = false)

        val merged = mergeRefreshedHistory(previous, fresh)

        assertEquals(listOf(10, 11, 12), merged.events.map { it.event.seq })
        assertFalse(merged.hasMore)
    }

    /**
     * 回归（真机反馈，2026-10-30）：模型正在输出时点「加载更早消息」，
     * 流式事件会在安静 400ms 后触发一次自动刷新，拿回的只是**最后一页**。
     * 这一刷新若整页替换，刚加载出来的更早历史「出现一瞬就消失」。
     * 自动刷新必须与手动刷新走同一个合并口：早先加载的 seq 保留、尾页新内容接上、
     * 「还能往前翻」沿用用户那一页的 hasMore。
     */
    @Test
    fun autoRefreshDuringStreamingKeepsOlderLoadedPage() {
        // 用户已加载更早页（1-3）+ 当前尾页（10-11），更早还有内容。
        val previous = page(1, 2, 3, 10, 11)
        // 输出期间自动刷新只回最后一页：尾部新增了刚落盘的 12，尾页自身 hasMore=false。
        val fresh = page(10, 11, 12, hasMore = false)

        val merged = mergeRefreshedHistory(previous, fresh)

        assertEquals(listOf(1, 2, 3, 10, 11, 12), merged.events.map { it.event.seq })
        // 尾页说「我前面没有了」是因为它只知道自己那一页；用户明明还停在更早的分页上，
        // hasMore 必须沿用 previous，不能被尾页结论打回 false。
        assertTrue(merged.hasMore)
    }
}
