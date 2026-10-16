package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 时间戳分档。
 *
 * 这类逻辑错了不会崩，只会让人**误判时间**（把上个月的当成今天的），
 * 而且单测跑在哪个时区都会飘 —— 所以时间与地区全部走参数，断言用固定时刻。
 */
class ChatTimeLabelTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val locale = Locale.US

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `same day shows the clock time only`() {
        val now = at(2026, 10, 17, 9, 5)
        assertEquals("14:03", chatTimeLabel(at(2026, 10, 17, 14, 3), now, zone, locale))
    }

    /** 同一天但跨零点边界：23:59 与次日 00:01 不能算同一天。 */
    @Test
    fun `respects the day boundary`() {
        val now = at(2026, 10, 17, 0, 1)
        val label = chatTimeLabel(at(2026, 10, 16, 23, 59), now, zone, locale)
        assertEquals("Oct 16 23:59", label)
    }

    @Test
    fun `same year shows month and day`() {
        val now = at(2026, 10, 17, 9, 5)
        assertEquals("Oct 3 08:30", chatTimeLabel(at(2026, 10, 3, 8, 30), now, zone, locale))
    }

    @Test
    fun `older years include the year`() {
        val now = at(2026, 10, 17, 9, 5)
        assertEquals("2025 Oct 3 08:30", chatTimeLabel(at(2025, 10, 3, 8, 30), now, zone, locale))
    }

    /** 本地化交给 SimpleDateFormat：中文下月份自带「月」，日号补「日」（见实现里的 pattern 分支）。 */
    @Test
    fun `localizes through the given locale`() {
        val now = at(2026, 10, 17, 9, 5)
        assertEquals("10月3日 08:30", chatTimeLabel(at(2026, 10, 3, 8, 30), now, zone, Locale.CHINA))
        assertEquals("2025年10月3日 08:30", chatTimeLabel(at(2025, 10, 3, 8, 30), now, zone, Locale.CHINA))
    }

    /**
     * 时区必须真的用参数里的那个 —— 而且**换时区不只换时刻，还会换「哪一天」**：
     * 上海 10-17 00:30 在 UTC 还是 10-16，于是分档从「今天」掉到「今年」。
     * 这条断言把这个真实行为钉住（第一次写测试时我正是猜错了它）。
     */
    @Test
    fun `uses the supplied time zone`() {
        val now = at(2026, 10, 17, 9, 5)
        val instant = at(2026, 10, 17, 0, 30)
        assertEquals("00:30", chatTimeLabel(instant, now, zone, locale))
        assertEquals("Oct 16 16:30", chatTimeLabel(instant, now, TimeZone.getTimeZone("UTC"), locale))
    }
}
