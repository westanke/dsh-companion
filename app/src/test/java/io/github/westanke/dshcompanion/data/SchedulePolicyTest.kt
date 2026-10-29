package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务策略层的钉子。
 *
 * 这里每一条断言都对应一个**跨仓事实**，改之前先看 `data/SchedulePolicy.kt` 顶部的依据：
 * 内核没有暂停语义（停用 = 删除）、`createdAt` 恒 null（不能排序）、
 * 桥对「路由不存在」与「会话不存在」都回 404（要靠能力位区分）。
 */
class SchedulePolicyTest {

    @Test
    fun turningTheSwitchOffIsADeleteNotAPatch() {
        // 关掉开关发 `PATCH {"enabled":false}` 会被桥回 400 ENABLED_UNSUPPORTED。
        assertEquals(ScheduleToggle.DisableByDelete, scheduleToggle(false))
        assertEquals(ScheduleToggle.Enable, scheduleToggle(true))
    }

    @Test
    fun endedScheduleIsRecognisedByReasonNotByCode() {
        val ended = DshApiException(
            status = 409,
            code = "CONFLICT",
            message = "The schedule has already ended and cannot be resumed.",
            details = buildJsonObject { put("reason", "SCHEDULE_ENDED") },
        )
        assertTrue(isScheduleEnded(ended))

        // 同一个 code（CONFLICT）但 reason 不同 —— 不能都当成「已结束」。
        val otherConflict = DshApiException(
            status = 409,
            code = "CONFLICT",
            message = "conflict",
            details = buildJsonObject { put("reason", "SCHEDULE_UNREPRESENTABLE") },
        )
        assertFalse(isScheduleEnded(otherConflict))

        // 没有 details 的 CONFLICT、以及非 DshApiException，都不该命中。
        assertFalse(isScheduleEnded(DshApiException(409, "CONFLICT", "conflict")))
        assertFalse(isScheduleEnded(IllegalStateException("boom")))
    }

    @Test
    fun pauseRejectionIsReadableEvenThoughTheClientNoLongerSendsIt() {
        // 客户端不再发 enabled:false，但诊断日志要能读懂老客户端/别人的请求被拒的原因。
        val rejected = DshApiException(
            status = 400,
            code = "BODY_INVALID",
            message = "no pause operation",
            details = buildJsonObject { put("reason", "ENABLED_UNSUPPORTED") },
        )
        assertEquals(REASON_ENABLED_UNSUPPORTED, scheduleErrorReason(rejected))
        assertFalse(isScheduleEnded(rejected))
    }

    @Test
    fun listFailureSplitsIntoPluginTooOldVersusSessionGone() {
        // 桥说支持（能力位在），却回 404 → 那是会话没了，不是插件旧。
        assertEquals(
            ScheduleReadFailure.SESSION_MISSING,
            classifyScheduleReadFailure(declared = true, routeMissing = true),
        )
        // 能力位不在 → 老插件没有这条路由，降级为「请升级」。
        assertEquals(
            ScheduleReadFailure.UNSUPPORTED_PLUGIN,
            classifyScheduleReadFailure(declared = false, routeMissing = true),
        )
        // 不是 404 的失败交给通用错误文案。
        assertNull(classifyScheduleReadFailure(declared = true, routeMissing = false))
        assertNull(classifyScheduleReadFailure(declared = false, routeMissing = false))
    }

    @Test
    fun displayOrderPutsEnabledFirstThenEarliestNextRun() {
        val items = listOf(
            item(id = "c", enabled = false, nextRunAt = 100),
            item(id = "b", enabled = true, nextRunAt = 900),
            item(id = "a", enabled = true, nextRunAt = 500),
        )
        assertEquals(listOf("a", "b", "c"), displayOrder(items).map { it.id })
    }

    @Test
    fun displayOrderPutsUnknownNextRunLastAndStaysStable() {
        val items = listOf(
            item(id = "unknown-2", enabled = true, nextRunAt = null),
            item(id = "unknown-1", enabled = true, nextRunAt = null),
            item(id = "known", enabled = true, nextRunAt = 42),
        )
        // null 排最后；同为 null 时按 id 兜底，避免每次刷新行序跳动。
        assertEquals(listOf("known", "unknown-1", "unknown-2"), displayOrder(items).map { it.id })
    }

    @Test
    fun createdAtIsNullableBecauseTheBridgeAlwaysSendsNull() {
        // 声明成非空 Long 时，桥发 `"createdAt":null` 会让整条响应解码失败 ——
        // 这是「内核没有创建时间字段」的直接后果，必须留一条钉子。
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ScheduleItem.serializer(), """{"id":"s","createdAt":null}""")
        assertNull(decoded.createdAt)
        assertNull(displayOrder(listOf(decoded)).first().createdAt)
    }

    private fun item(id: String, enabled: Boolean, nextRunAt: Long?): ScheduleItem = ScheduleItem(
        id = id,
        sessionId = "s-1",
        text = "任务 $id",
        cron = "0 8 * * *",
        timezone = "Asia/Shanghai",
        enabled = enabled,
        nextRunAt = nextRunAt,
    )
}
