package io.github.westanke.dshcompanion.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 消息时间戳的展示文案。
 *
 * 三档粒度，按「离现在多远」选：
 * - **今天** → 只给时刻（`14:03`）—— 同一天里日期是废话；
 * - **今年** → 月日 + 时刻（`10月16日 14:03`）；
 * - **更早** → 年月日 + 时刻（`2024年10月16日 14:03`）。
 *
 * 为什么要分档：一律只显示时刻的话，翻到上周的会话会看到一串 `14:03`，
 * 用户无法判断那是今天还是上个月 —— 时间戳的作用正是「知道这条是什么时候说的」。
 *
 * `pattern` 交给 [SimpleDateFormat] 按 `locale` 本地化（`MMM` 在中文下就是「10月」），
 * 所以这里不写死月份/日期的排列顺序，也不维护一份自己的月份表。
 *
 * 纯函数（时间与地区都从参数来）——因此可以拿固定时刻做断言，
 * 不必依赖真实时钟，也不会因为测试跑在哪个时区而飘。
 */
internal fun chatTimeLabel(
    millis: Long,
    now: Long,
    zone: TimeZone,
    locale: Locale,
): String {
    val clock = "HH:mm"
    val pattern = when {
        sameDay(millis, now, zone) -> clock
        sameYear(millis, now, zone) -> "${monthDayPattern(locale)} $clock"
        else -> "${yearMonthDayPattern(locale)} $clock"
    }
    return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(millis))
}

/**
 * 月日 / 年月日的 pattern，中文单独一支。
 *
 * 为什么要分：`SimpleDateFormat` 的 `MMM d` 在中文下渲染成 **「10月 3」**
 * （月名自带「月」、日号是裸数字，中间还夹一个空格），而中文读者预期的是「10月3日」。
 * —— 这不是猜的，是单测第一次跑出来的实际输出（`10月[ 3]`）。
 *
 * Android 官方的解法是 `DateFormat.getBestDateTimePattern(locale, skeleton)`，
 * 但它是 **Android API**：在没有 Robolectric 的 JVM 单测里会抛 "not mocked"。
 * 这里手工分一支，代价是只覆盖中英两种形态，收益是这条逻辑**能被单测真跑到**。
 */
private fun monthDayPattern(locale: Locale): String =
    if (locale.language == "zh") "M月d日" else "MMM d"

private fun yearMonthDayPattern(locale: Locale): String =
    if (locale.language == "zh") "yyyy年M月d日" else "yyyy MMM d"

private fun calendarOf(millis: Long, zone: TimeZone): Calendar =
    Calendar.getInstance(zone).apply { timeInMillis = millis }

private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
    val left = calendarOf(a, zone)
    val right = calendarOf(b, zone)
    return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
}

private fun sameYear(a: Long, b: Long, zone: TimeZone): Boolean =
    calendarOf(a, zone).get(Calendar.YEAR) == calendarOf(b, zone).get(Calendar.YEAR)
