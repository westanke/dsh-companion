package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 定时任务在客户端这一侧的**策略层**：开关该走哪个动词、404/409 该怎么解释、列表该怎么排。
 *
 * 为什么单拉一个文件：这些判断全是纯函数，能在 JVM 单测里钉死；而它们描述的是
 * **内核能力缺口**（没有暂停语义、没有创建时间），是最容易被下一个人「顺手改成正常样子」的地方 ——
 * 把结论写进代码 + 单测，比写进注释更抗时间。
 *
 * 依据（桥侧 `src/host/bff-schedule.ts` 实测，2026-10-29）：
 * - `PATCH {"enabled":false}` → `400 BODY_INVALID` + `details.reason = "ENABLED_UNSUPPORTED"`；
 * - `PATCH {"enabled":true}` 对已结束任务 → `409 CONFLICT` + `details.reason = "SCHEDULE_ENDED"`；
 * - `GET /chat/schedules?sessionId=<不存在>` → `404 NOT_FOUND`（**不是空列表**）；
 * - `createdAt` 恒为 `null`（内核记录没有该字段）。
 */

/**
 * 拨动开关时**实际要发的请求**。
 *
 * 内核没有暂停操作，所以「关掉开关」在协议上只能是删除。写成类型而不是一个 if：
 * 让「关闭 = 删除」这个反直觉的结论在调用点显式可见，也避免哪天有人把 PATCH 加回来。
 */
internal sealed interface ScheduleToggle {
    /** `PATCH {"enabled":true}` —— 只对未结束的任务有效。 */
    data object Enable : ScheduleToggle

    /** `DELETE` —— 内核没有暂停语义，停用只能删。 */
    data object DisableByDelete : ScheduleToggle
}

// @patch
// id: schedule-pause-as-delete
// for: bridge 内核 ScheduleRecord 没有暂停操作 —— `PATCH {"enabled":false}` 回 400 ENABLED_UNSUPPORTED（src/host/bff-schedule.ts，2026-10-29）
// why: 契约把 `enabled:false` 描述成暂停，但实现拒绝；若不把「关掉开关」映射成 DELETE，「停用」这个动作在协议上根本发不出去
// until: 内核补上 pause/resume 语义、`PATCH enabled:false` 成为合法操作（届时这里改回 Enable/DisableByPatch 两个分支）
// test: SchedulePolicyTest（turningTheSwitchOffIsADeleteNotAPatch）
internal fun scheduleToggle(enabled: Boolean): ScheduleToggle =
    if (enabled) ScheduleToggle.Enable else ScheduleToggle.DisableByDelete

/** 从 `error.details` 里读 `reason`。缺 `details`、缺 `reason`、`reason` 不是字符串一律 null。 */
internal fun scheduleErrorReason(error: Throwable): String? {
    val api = error as? DshApiException ?: return null
    val details = api.details as? JsonObject ?: return null
    val reason = details["reason"] as? JsonPrimitive ?: return null
    // `JsonNull` 也是 JsonPrimitive，且 `content` 会是字符串 "null" —— isString 这一道不能省。
    return reason.takeIf { it.isString }?.content
}

/**
 * 409 且 `reason = SCHEDULE_ENDED`：任务已结束，开关打不开。
 *
 * 注意判据是 `details.reason` 而不是 `code`：桥侧这里回的是 `CONFLICT`，
 * 只认 code 会把所有冲突（含未定为具体原因的）当成同一件事。
 */
internal fun isScheduleEnded(error: Throwable): Boolean =
    scheduleErrorReason(error) == REASON_SCHEDULE_ENDED

/** 读清单失败时**该给用户看什么**。 */
internal enum class ScheduleReadFailure {
    /** 本机插件没有这条路由（版本差，不是故障）→ 面板降级为「请升级插件」。 */
    UNSUPPORTED_PLUGIN,

    /** 插件声明支持，但会话已经不存在了 → 这是真错误，不能说成「插件太旧」。 */
    SESSION_MISSING,
}

/**
 * 把「读清单失败」翻译成用户能理解的两类。
 *
 * [declared] 是能力位判据（`supportsSchedules`）的结果，它才是「插件支不支持」的权威答案；
 * 光看 404 分不清「路由不存在」与「会话没了」—— 桥对后者也回 404 NOT_FOUND。
 * 非 404 返回 null，交给通用的 `messageOf`。
 */
internal fun classifyScheduleReadFailure(declared: Boolean, routeMissing: Boolean): ScheduleReadFailure? =
    when {
        !routeMissing -> null
        declared -> ScheduleReadFailure.SESSION_MISSING
        else -> ScheduleReadFailure.UNSUPPORTED_PLUGIN
    }

/**
 * 列表展示顺序：**启用的在前**，同组按 [ScheduleItem.nextRunAt] 由近到远，`null` 排最后。
 *
 * 为什么不用 `createdAt`：它恒为 null（内核没这个字段）。桥返回的顺序来自目录扫描，
 * 跨会话查询时并不稳定，所以客户端自己定一个稳定序；同刻用 id 兜底，避免刷新后行序跳动。
 */
internal fun displayOrder(items: List<ScheduleItem>): List<ScheduleItem> = items.sortedWith { a, b ->
    when {
        a.enabled != b.enabled -> if (a.enabled) -1 else 1
        else -> {
            val left = a.nextRunAt
            val right = b.nextRunAt
            when {
                left == null && right == null -> a.id.compareTo(b.id)
                left == null -> 1
                right == null -> -1
                left != right -> left.compareTo(right)
                else -> a.id.compareTo(b.id)
            }
        }
    }
}

/** 桥侧错误体里的 `details.reason` 取值（跨仓契约，改动需同步 `bff-schedule.ts`）。 */
internal const val REASON_SCHEDULE_ENDED: String = "SCHEDULE_ENDED"

/** `PATCH {"enabled":false}` 被拒时的 reason；客户端不再发这个请求，留着是为了诊断能读懂。 */
internal const val REASON_ENABLED_UNSUPPORTED: String = "ENABLED_UNSUPPORTED"
