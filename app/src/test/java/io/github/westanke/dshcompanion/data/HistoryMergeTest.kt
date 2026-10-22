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
}
