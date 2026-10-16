package io.github.westanke.dshcompanion.data

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 事件去抖闸门。
 *
 * 用**虚拟时间**（`runTest`）而不是真的 sleep：这样才能断言「窗口还差 1ms 时不许执行」
 * 这种边界，也不会让测试真的等上几百毫秒。
 *
 * 判错的后果是两头的：压得太狠会漏掉刷新（界面停在旧数据上），压得不够就是
 * 每个事件一次请求（本来要修的就是它）。两头都不会崩，只能靠这里钉住。
 */
class EventDebouncerTest {

    @Test
    fun `a burst collapses into a single run after the window`() = runTest {
        var runs = 0
        val debouncer = EventDebouncer(this, windowMillis = 400) { runs += 1 }

        debouncer.trigger()
        advanceTimeBy(100)
        debouncer.trigger()
        advanceTimeBy(100)
        debouncer.trigger()

        // 还没安静满 400ms：一次都不该执行。
        advanceTimeBy(399)
        runCurrent()
        assertEquals(0, runs)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, runs)
    }

    /** 事件持续到来时窗口一直被重置 —— 等它停下来才做那唯一一次。 */
    @Test
    fun `keeps resetting while events keep arriving`() = runTest {
        var runs = 0
        val debouncer = EventDebouncer(this, windowMillis = 400) { runs += 1 }

        repeat(10) {
            debouncer.trigger()
            advanceTimeBy(300)
            runCurrent()
        }
        assertEquals(0, runs)

        advanceTimeBy(401)
        runCurrent()
        assertEquals(1, runs)
    }

    /** 分得很开的事件各算各的：三批 → 三次。 */
    @Test
    fun `separate bursts each get their own run`() = runTest {
        var runs = 0
        val debouncer = EventDebouncer(this, windowMillis = 400) { runs += 1 }

        repeat(3) {
            debouncer.trigger()
            advanceTimeBy(500)
            runCurrent()
        }
        assertEquals(3, runs)
    }

    /** 执行期间来的事件要重新开一个窗口（不能吞掉）。 */
    @Test
    fun `an event during the run schedules another run`() = runTest {
        var runs = 0
        val debouncer = EventDebouncer(this, windowMillis = 400) { runs += 1 }

        debouncer.trigger()
        advanceTimeBy(401)
        runCurrent()
        assertEquals(1, runs)

        debouncer.trigger()
        advanceTimeBy(401)
        runCurrent()
        assertEquals(2, runs)
    }

    /**
     * 取消之后不许执行；且取消后的**下一次 trigger 仍要走完完整窗口**
     * （说明取消没有留下会让下一次提前触发的残渣）。
     */
    @Test
    fun `cancel stops the pending run`() = runTest {
        var runs = 0
        val debouncer = EventDebouncer(this, windowMillis = 400) { runs += 1 }

        debouncer.trigger()
        debouncer.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, runs)

        debouncer.trigger()
        advanceTimeBy(399)
        runCurrent()
        assertEquals(0, runs)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, runs)
    }
}
