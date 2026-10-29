package io.github.westanke.dshcompanion.data.ws

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Retry-After` 的解析与重连等待合成（`data/ws/RetryAfter.kt`）。
 *
 * 为什么值得单测：这条逻辑的输入来自网络、输出决定「多久之后才能恢复」，
 * 而真机上要复现「第 33 条连接被 429」得先凑够 33 条连接 —— 成本高到实际上不会有人测。
 * 所以把「解析」和「与退避取较大者」都做成纯函数，在这里把边界一次性钉死。
 */
class RetryAfterTest {

    private fun headers(vararg pairs: Pair<String, String>): Headers {
        val builder = Headers.Builder()
        pairs.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    @Test
    fun `没有响应头时当作服务端没给`() {
        assertNull(retryAfterMillis(null))
    }

    @Test
    fun `有响应头但没有 Retry-After 时返回 null`() {
        assertNull(retryAfterMillis(headers("Content-Length" to "0")))
    }

    @Test
    fun `秒数换成毫秒`() {
        assertEquals(5_000L, retryAfterMillis(headers("Retry-After" to "5")))
    }

    @Test
    fun `两边空白不影响解析`() {
        assertEquals(3_000L, retryAfterMillis(headers("Retry-After" to "  3  ")))
    }

    @Test
    fun `零秒夹到下限`() {
        // 服务端说「马上」：真的立刻重连等于自己制造重连风暴。
        assertEquals(MIN_RETRY_AFTER_MILLIS, retryAfterMillis(headers("Retry-After" to "0")))
    }

    @Test
    fun `荒唐的大值夹到上限`() {
        // 不夹的话，一个写错的反代能让 App 永久停在「已断开」，用户只能重启。
        assertEquals(MAX_RETRY_AFTER_MILLIS, retryAfterMillis(headers("Retry-After" to "86400")))
    }

    @Test
    fun `负数当作不可用`() {
        assertNull(retryAfterMillis(headers("Retry-After" to "-3")))
    }

    @Test
    fun `非数字当作不可用`() {
        assertNull(retryAfterMillis(headers("Retry-After" to "abc")))
    }

    @Test
    fun `HTTP-date 形式不支持，按没给处理`() {
        // RFC 7231 允许 HTTP-date，但解析它要依赖设备时钟 —— 而这个场景里手机与宿主的时钟
        // 恰恰是最不可信的东西。宁可退回自己的退避，也不拿一个可能差几分钟的时间点去算等待。
        assertNull(retryAfterMillis(headers("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")))
    }

    @Test
    fun `服务端要求比退避长时听服务端的`() {
        assertEquals(5_000L, reconnectWaitMillis(backoffMillis = 1_000L, retryAfterMillis = 5_000L))
    }

    @Test
    fun `自己已经退到更久时不缩回去`() {
        // 退避保护的是服务端别被打崩；缩回 5 秒等于打自己脸。
        assertEquals(16_000L, reconnectWaitMillis(backoffMillis = 16_000L, retryAfterMillis = 5_000L))
    }

    @Test
    fun `服务端没给时就是退避本身`() {
        assertEquals(2_000L, reconnectWaitMillis(backoffMillis = 2_000L, retryAfterMillis = null))
    }
}
