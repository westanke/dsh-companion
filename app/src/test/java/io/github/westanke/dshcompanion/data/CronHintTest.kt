package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cron 人话预览：常见形态要给得出人话，扩展形态**不许拦人**（权威判据在服务端）。
 */
class CronHintTest {

    @Test
    fun everyMinuteIsRecognised() {
        assertTrue(parseCron("* * * * *").hint is CronHint.EveryMinute)
    }

    @Test
    fun stepMinutesAndStepHours() {
        assertEquals(CronHint.EveryMinutes(15), parseCron("*/15 * * * *").hint)
        assertEquals(CronHint.EveryHours(hours = 2, minute = 0), parseCron("0 */2 * * *").hint)
    }

    @Test
    fun fixedMinuteWithWildcardHourIsHourly() {
        assertEquals(CronHint.Hourly(minute = 30), parseCron("30 * * * *").hint)
    }

    @Test
    fun dailyWeeklyMonthly() {
        assertEquals(CronHint.Daily(hour = 8, minute = 0), parseCron("0 8 * * *").hint)
        assertEquals(CronHint.Weekly(days = listOf(1, 3, 5), hour = 9, minute = 30), parseCron("30 9 * * 1,3,5").hint)
        assertEquals(CronHint.Monthly(dayOfMonth = 1, hour = 0, minute = 0), parseCron("0 0 1 * *").hint)
    }

    @Test
    fun rangeSyntaxIsFlattenedIntoTheSameHint() {
        assertEquals(CronHint.Weekly(days = listOf(1, 2, 3, 4, 5), hour = 9, minute = 0), parseCron("0 9 * * 1-5").hint)
    }

    @Test
    fun sundayAsSevenIsNormalised() {
        val hint = parseCron("0 9 * * 7").hint
        assertEquals(CronHint.Weekly(days = listOf(0), hour = 9, minute = 0), hint)
    }

    @Test
    fun unknownButPlausibleSyntaxIsCustomNotAnError() {
        // `L` / `#` 这类扩展这里不认，但**不拦人**：服务端才是权威。
        val validation = parseCron("0 8 L * *")
        assertTrue(validation.valid)
        assertFalse(validation.error != null)
        assertEquals(CronHint.Custom, validation.hint)
    }

    @Test
    fun wrongFieldCountIsRejectedLocally() {
        val validation = parseCron("0 8 * *")
        assertFalse(validation.valid)
        assertEquals(CronHintError.FIELD_COUNT, validation.error)
    }

    @Test
    fun garbageFieldIsRejected() {
        val validation = parseCron("0 8 * * mon")
        assertFalse(validation.valid)
        assertEquals(CronHintError.BAD_FIELD, validation.error)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        assertEquals(CronHintError.OUT_OF_RANGE, parseCron("60 8 * * *").error)
        assertEquals(CronHintError.OUT_OF_RANGE, parseCron("0 24 * * *").error)
        assertEquals(CronHintError.OUT_OF_RANGE, parseCron("0 8 0 * *").error)
        assertEquals(CronHintError.OUT_OF_RANGE, parseCron("0 8 * 13 *").error)
        assertEquals(CronHintError.OUT_OF_RANGE, parseCron("0 8 * * 8").error)
    }

    @Test
    fun validExpressionsCarryAHint() {
        assertNotNull(parseCron("0 8 * * *").hint)
        assertTrue(parseCron("0 8 * * *").valid)
    }
}
