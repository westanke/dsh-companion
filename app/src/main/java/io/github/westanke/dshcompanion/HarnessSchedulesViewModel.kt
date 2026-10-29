package io.github.westanke.dshcompanion

import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.ScheduleItem
import io.github.westanke.dshcompanion.data.deleteSchedule
import io.github.westanke.dshcompanion.data.patchSchedule
import io.github.westanke.dshcompanion.data.schedules
import io.github.westanke.dshcompanion.data.isRouteMissing
import io.github.westanke.dshcompanion.data.createSchedule
import io.github.westanke.dshcompanion.data.classifyScheduleReadFailure
import io.github.westanke.dshcompanion.data.isScheduleEnded
import io.github.westanke.dshcompanion.data.parseCron
import io.github.westanke.dshcompanion.data.supportsSchedules
import io.github.westanke.dshcompanion.data.ScheduleReadFailure
import io.github.westanke.dshcompanion.debug.DiagLog
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定时任务面板（`ScheduleSheet`）的动作集。
 *
 * 制度与 `HarnessJobsViewModel` 一致：状态进 `HarnessState.schedulePanel`，
 * 动作是 `HarnessViewModel` 的扩展函数，网络调用走 `withClient`（它把「无连接丢弃留痕」
 * 与「代际失效连失败一起丢」都做掉了）。
 *
 * 与 jobs 面板的一处**刻意不同**：`loadSchedules` 遇到 404 不报错，而是置
 * [SchedulePanelState.unsupported] —— 老插件的这条路由本来就不存在，那是版本差不是故障。
 * 这也是契约末尾那条「新 App 连老桥必须正常」的落地：**藏入口、说清楚，不弹红**。
 */

/** 打开面板并立即读清单。[sessionId] 记进状态，新建时要用。 */
internal fun HarnessViewModel.openSchedulePanel(sessionId: String) {
    update { copy(schedulePanel = SchedulePanelState(sessionId = sessionId)) }
    loadSchedules()
}

internal fun HarnessViewModel.closeSchedulePanel() = update { copy(schedulePanel = null) }

internal fun HarnessViewModel.toggleScheduleForm(open: Boolean) = update {
    copy(schedulePanel = schedulePanel?.copy(creating = open, formError = null))
}

/** （重新）读清单。失败保留旧清单：一次刷新失败不该把屏幕打成空白。 */
internal fun HarnessViewModel.loadSchedules() = withClient { api ->
    val sessionId = mutableState.value.schedulePanel?.sessionId ?: return@withClient
    update { copy(schedulePanel = schedulePanel?.copy(loading = true, error = null)) }
    try {
        val items = withContext(Dispatchers.IO) { api.schedules(sessionId) }
        update {
            copy(schedulePanel = schedulePanel?.copy(loading = false, items = items, error = null, unsupported = false))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 404 有两种意思：插件没有这条路由（版本差），或**会话已经不存在**（桥对两者都回 404）。
        // 能力位才是「插件支不支持」的权威答案，所以用能力位而不是状态码来区分。
        val declared = supportsSchedules(mutableState.value.bridgeCapabilities)
        val routeMissing = error is DshApiException && error.isRouteMissing()
        when (classifyScheduleReadFailure(declared = declared, routeMissing = routeMissing)) {
            ScheduleReadFailure.UNSUPPORTED_PLUGIN -> {
                update { copy(schedulePanel = schedulePanel?.copy(loading = false, unsupported = true, error = null)) }
                DiagLog.w("SCHEDULE", "本机插件没有 /chat/schedules 路由，面板降级为「不支持」")
            }
            ScheduleReadFailure.SESSION_MISSING -> {
                val message = appString(R.string.schedule_session_missing)
                update { copy(schedulePanel = schedulePanel?.copy(loading = false, error = message)) }
                DiagLog.w("SCHEDULE", "会话已不存在，定时任务清单读取失败: ${error.message}")
            }
            null -> {
                update { copy(schedulePanel = schedulePanel?.copy(loading = false, error = messageOf(error))) }
                DiagLog.w("SCHEDULE", "loadSchedules 失败: ${error.message}")
            }
        }
    }
}

/**
 * 新建。**先本地校验**再发请求：cron 语法与 IANA 时区都能在本地判，
 * 让用户省一个来回（服务端参数错的 400 文案对普通用户没有指导性）。
 *
 * 校验失败写 [SchedulePanelState.formError] 而不是全局 `error`：表单保持展开、
 * 用户输的内容不丢，这是「错误就地提示」的既有约定（见供应商编辑卡）。
 */
internal fun HarnessViewModel.createSchedule(text: String, cron: String, timezone: String) {
    val panel = mutableState.value.schedulePanel ?: return
    val trimmedText = text.trim()
    val trimmedCron = cron.trim()
    val trimmedZone = timezone.trim()
    val localError = when {
        trimmedText.isEmpty() -> appString(R.string.schedule_error_text_empty)
        trimmedCron.isEmpty() -> appString(R.string.schedule_error_cron_empty)
        !parseCron(trimmedCron).valid -> appString(R.string.schedule_error_cron_invalid)
        !isIanaZone(trimmedZone) -> appString(R.string.schedule_error_timezone_invalid)
        else -> null
    }
    if (localError != null) {
        update { copy(schedulePanel = schedulePanel?.copy(formError = localError)) }
        return
    }
    val api = client ?: return
    update { copy(schedulePanel = schedulePanel?.copy(saving = true, formError = null)) }
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                api.createSchedule(panel.sessionId, trimmedText, trimmedCron, trimmedZone)
            }
            update { copy(schedulePanel = schedulePanel?.copy(saving = false, creating = false, formError = null)) }
            loadSchedules()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            update {
                copy(schedulePanel = schedulePanel?.copy(saving = false, formError = messageOf(error)))
            }
            DiagLog.w("SCHEDULE", "createSchedule 失败: ${error.message}")
        }
    }
}

/**
 * 打开开关 = `PATCH {"enabled":true}`。
 *
 * **关闭开关不走这里**：内核 `ScheduleRecord` 没有暂停操作，`PATCH {"enabled":false}` 会回
 * `400 ENABLED_UNSUPPORTED`（见 [scheduleToggle] 与 `data/SchedulePolicy.kt` 的说明）。
 * 关闭 = 删除，由 UI 先征求用户同意再调 [deleteSchedule]。
 *
 * 已结束的任务不能被恢复（桥回 `409` + `details.reason = SCHEDULE_ENDED`）：
 * 那时给一句「已结束，无法恢复」，而不是把 `CONFLICT` 原样丢给用户。
 */
internal fun HarnessViewModel.enableSchedule(item: ScheduleItem) = withClient { api ->
    update { copy(schedulePanel = schedulePanel?.copy(pendingId = item.id)) }
    try {
        withContext(Dispatchers.IO) { api.patchSchedule(item.id, enabled = true) }
        update { copy(schedulePanel = schedulePanel?.copy(pendingId = null)) }
        loadSchedules()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val message = if (isScheduleEnded(error)) {
            appString(R.string.schedule_ended)
        } else {
            messageOf(error)
        }
        update { copy(schedulePanel = schedulePanel?.copy(pendingId = null, error = message)) }
        DiagLog.w("SCHEDULE", "enableSchedule 失败 id=${item.id}: ${error.message}")
    }
}

/** 删除。UI 负责先弹确认框：确认文案要说清「删的是哪一条」，只有 UI 知道用户点了哪行。 */
internal fun HarnessViewModel.deleteSchedule(item: ScheduleItem) = withClient { api ->
    update { copy(schedulePanel = schedulePanel?.copy(pendingId = item.id)) }
    try {
        withContext(Dispatchers.IO) { api.deleteSchedule(item.id) }
        update { copy(schedulePanel = schedulePanel?.copy(pendingId = null)) }
        loadSchedules()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        update {
            copy(schedulePanel = schedulePanel?.copy(pendingId = null, error = messageOf(error)))
        }
        DiagLog.w("SCHEDULE", "deleteSchedule 失败 id=${item.id}: ${error.message}")
    }
}

/**
 * 时区是否合法：用 `ZoneId.of` 判，而不是 `TimeZone.getTimeZone` —— 后者对非法名
 * **不抛异常**，默默返回 GMT，于是「Asia/Shanghia」这种拼写错误会变成「时间全错但没报错」。
 * 后者也正是契约要求的「timezone 必须 IANA」的本地近似判据；权威判据仍在服务端。
 */
private fun isIanaZone(value: String): Boolean =
    value.isNotBlank() && runCatching { ZoneId.of(value) }.isSuccess
