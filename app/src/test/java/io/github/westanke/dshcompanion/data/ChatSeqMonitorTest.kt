package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断档判定。两头的错法都不会崩：
 *
 * - **判太松**（漏判）→ 用户看到的历史永远缺一段，而且他不知道为什么；
 * - **判太紧**（误判）→ 每次重连都全量重取一次历史，白烧流量与电。
 *
 * 所以重复、倒退这些「看起来像异常」的情况必须明确判成 Continue。
 */
class ChatSeqMonitorTest {

    @Test
    fun `a consecutive event continues`() {
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = 10, incomingSeq = 11))
    }

    /** 严格跳号才是断档：从 10 直接到 12，说明 11 谁都没收到。 */
    @Test
    fun `a skipped number asks for a resync`() {
        assertEquals(SeqVerdict.Resync, seqVerdict(lastSeq = 10, incomingSeq = 12))
        assertEquals(SeqVerdict.Resync, seqVerdict(lastSeq = 10, incomingSeq = 99))
    }

    /** 重复与倒退交给去重处理 —— 判成断档会让每次重连都全量重取。 */
    @Test
    fun `repeats and regressions are not gaps`() {
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = 10, incomingSeq = 10))
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = 10, incomingSeq = 9))
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = 10, incomingSeq = 1))
    }

    /** 流式 delta 不再带 `seq`（ADR-0008），它不该参与判定。 */
    @Test
    fun `events without a sequence number never trigger a resync`() {
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = 10, incomingSeq = null))
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = null, incomingSeq = null))
    }

    /** 还没有起点（刚订阅 / 刚 resync 完）：先把它记成起点。 */
    @Test
    fun `the first event establishes the starting point`() {
        assertEquals(SeqVerdict.Continue, seqVerdict(lastSeq = null, incomingSeq = 42))
    }

    @Test
    fun `an unknown cursor is never treated as being behind`() {
        assertFalse(baselineBehind(localMaxSeq = 5, cursor = null))
        assertFalse(baselineBehind(localMaxSeq = null, cursor = null))
    }

    /** 服务端已经有内容、本地却是空的 —— 最该立刻拉一次的情况。 */
    @Test
    fun `empty local history behind a cursor is behind`() {
        assertTrue(baselineBehind(localMaxSeq = null, cursor = 140))
    }

    @Test
    fun `compares the local maximum against the cursor`() {
        assertTrue(baselineBehind(localMaxSeq = 100, cursor = 140))
        assertFalse(baselineBehind(localMaxSeq = 140, cursor = 140))
        // 本地比基线还新：服务端在订阅之后又落了事件，那不是落后。
        assertFalse(baselineBehind(localMaxSeq = 200, cursor = 140))
    }
}
