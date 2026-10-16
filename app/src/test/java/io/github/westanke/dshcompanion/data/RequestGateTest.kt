package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 代际闸门。
 *
 * 判错的后果是**静默的状态覆盖**（旧会话的内容写进新会话的界面）—— 不崩、不报错，
 * 只在特定时序下出现，所以只能靠这里的断言把语义钉死。
 */
class RequestGateTest {

    @Test
    fun `a fresh token is current`() {
        val gate = RequestGate()
        assertTrue(gate.isCurrent(gate.current))
    }

    @Test
    fun `invalidate retires every token issued before it`() {
        val gate = RequestGate()
        val stale = gate.current
        val fresh = gate.invalidate()
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(fresh))
        assertTrue(gate.isCurrent(gate.current))
    }

    /** 连续切换上下文（连点两个会话）时，只有最后一个号算数。 */
    @Test
    fun `only the latest generation survives repeated switches`() {
        val gate = RequestGate()
        val first = gate.current
        gate.invalidate()
        gate.invalidate()
        val latest = gate.invalidate()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(latest))
        assertEquals(latest, gate.current)
    }

    /**
     * 并发领号不能出现「两个任务拿到同一个号」——那会让两个上下文都以为自己是当前的。
     * 用 8 线程各领 500 次，断言所有号互不相同。
     */
    @Test
    fun `concurrent invalidations never hand out the same number twice`() {
        val gate = RequestGate()
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { seen.add(gate.invalidate()) }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(threads * perThread, seen.size)
    }
}
