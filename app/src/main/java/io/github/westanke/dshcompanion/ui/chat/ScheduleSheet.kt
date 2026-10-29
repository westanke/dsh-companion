package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.CronHint
import io.github.westanke.dshcompanion.data.CronHintError
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.SchedulePanelState
import io.github.westanke.dshcompanion.closeSchedulePanel
import io.github.westanke.dshcompanion.createSchedule
import io.github.westanke.dshcompanion.data.ScheduleItem
import io.github.westanke.dshcompanion.data.parseCron
import io.github.westanke.dshcompanion.deleteSchedule
import io.github.westanke.dshcompanion.enableSchedule
import io.github.westanke.dshcompanion.data.displayOrder
import io.github.westanke.dshcompanion.toggleScheduleForm
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * 定时任务面板：本会话的清单 + 新建表单。
 *
 * 形状照抄 `ChatJobsSheet`（同为会话级、底部面板、状态在 `HarnessState`），刻意保持一致：
 * 两个面板出现在同一个 `⋯` 菜单里，交互长相差太远会让人以为点错了东西。
 *
 * 三件「必须给到位」的事：
 * - cron **给一句人话预览**（「每天 08:00」），原始表达式只是给熟手看的备选；
 * - 时间字段一律 epoch 毫秒传输、**按本地时区展示**（服务端跑在另一台机器上）；
 * - 老插件缺路由时给一条**可操作**的说明（升级插件），不是红条 `NOT_FOUND`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onClose: () -> Unit,
) {
    val panel = state.schedulePanel ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by remember { mutableStateOf<ScheduleItem?>(null) }
    // 同一个确认框承担两种入口：垃圾桶 = 删除，「关掉开关」= 停用（协议上也是删除）。
    // 文案必须分开说清楚，否则用户以为只是不再触发、实际上任务已经没了。
    var confirmIsDisable by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = {
            viewModel.closeSchedulePanel()
            onClose()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.schedule_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.toggleScheduleForm(!panel.creating) }) {
                    Text(
                        stringResource(
                            if (panel.creating) R.string.cancel else R.string.schedule_new_action,
                        ),
                    )
                }
            }
            ScheduleBody(
                panel = panel,
                viewModel = viewModel,
                onDelete = {
                    confirming = it
                    confirmIsDisable = false
                },
                onDisable = {
                    confirming = it
                    confirmIsDisable = true
                },
            )
            if (panel.creating) ScheduleForm(panel, viewModel)
            // 底部留白：贴边会让最后一张卡片看起来被裁掉了。
            Column(Modifier.padding(bottom = 12.dp)) {}
        }
    }

    confirming?.let { item ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    stringResource(
                        if (confirmIsDisable) {
                            R.string.schedule_disable_confirm_title
                        } else {
                            R.string.schedule_delete_confirm_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (confirmIsDisable) {
                            R.string.schedule_disable_confirm_body
                        } else {
                            R.string.schedule_delete_confirm_body
                        },
                        item.text.take(80),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    // 两条路都调 deleteSchedule：内核没有暂停语义，停用就是删除。
                    viewModel.deleteSchedule(item)
                }) {
                    Text(
                        stringResource(
                            if (confirmIsDisable) R.string.schedule_disable else R.string.schedule_delete,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ScheduleBody(
    panel: SchedulePanelState,
    viewModel: HarnessViewModel,
    onDelete: (ScheduleItem) -> Unit,
    onDisable: (ScheduleItem) -> Unit,
) {
    when {
        panel.unsupported -> Text(
            stringResource(R.string.schedule_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        panel.loading && panel.items.isEmpty() -> CircularProgressIndicator(Modifier.padding(8.dp))
        panel.items.isEmpty() -> Text(
            stringResource(R.string.schedule_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 展示顺序由 `displayOrder` 定（启用在前、nextRunAt 由近到远）：桥返回的顺序按目录扫描，
        // 跨会话查询时不稳定；`createdAt` 恒为 null，不能拿来排。
        else -> displayOrder(panel.items).forEachIndexed { index, item ->
            ScheduleCard(item, panel, viewModel, onDelete, onDisable)
            if (index != panel.items.lastIndex) HorizontalDivider()
        }
    }
    panel.error?.let { message ->
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ScheduleCard(
    item: ScheduleItem,
    panel: SchedulePanelState,
    viewModel: HarnessViewModel,
    onDelete: (ScheduleItem) -> Unit,
    onDisable: (ScheduleItem) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = {
                    Text(item.cron, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                })
                AssistChip(onClick = {}, label = { Text(item.timezone, style = MaterialTheme.typography.labelSmall) })
            }
            Text(
                cronPreview(item.cron),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nextRunLabel(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (panel.pendingId == item.id) {
                    CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (item.enabled) R.string.schedule_enabled else R.string.schedule_paused),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = item.enabled,
                        // 打开 = PATCH enabled:true；关闭 = 删除（先确认）。
                        onCheckedChange = { checked ->
                            if (checked) viewModel.enableSchedule(item) else onDisable(item)
                        },
                    )
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.schedule_delete),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleForm(panel: SchedulePanelState, viewModel: HarnessViewModel) {
    var text by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("0 8 * * *") }
    var timezone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    val validation = parseCron(cron)
    val hint = validation.hint?.let { cronHintText(it) }

    HorizontalDivider()
    Text(stringResource(R.string.schedule_new), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.schedule_text_label)) },
        placeholder = { Text(stringResource(R.string.schedule_text_hint)) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = cron,
        onValueChange = { cron = it },
        label = { Text(stringResource(R.string.schedule_cron_label)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    // 人话预览：cron 是给机器看的，括号里的说明是给写它的人确认的。
    if (cron.isNotBlank()) {
        Text(
            text = when {
                !validation.valid -> cronErrorText(validation.error)
                hint != null -> hint
                else -> stringResource(R.string.cron_hint_custom)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (validation.valid) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    OutlinedTextField(
        value = timezone,
        onValueChange = { timezone = it },
        label = { Text(stringResource(R.string.schedule_timezone_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.schedule_timezone_hint, ZoneId.systemDefault().id),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    panel.formError?.let { message ->
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.createSchedule(text, cron, timezone) },
            enabled = !panel.saving,
        ) {
            if (panel.saving) {
                CircularProgressIndicator(Modifier.padding(end = 6.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(if (panel.saving) R.string.schedule_saving else R.string.schedule_save))
        }
        TextButton(onClick = { viewModel.toggleScheduleForm(false) }) {
            Text(stringResource(R.string.cancel))
        }
    }
    HorizontalDivider()
}

/** cron → 人话。**不认识的语法不拦人**（服务端才是权威），只是没有预览。 */
@Composable
internal fun cronPreview(expression: String): String {
    val validation = parseCron(expression)
    if (!validation.valid) return cronErrorText(validation.error)
    val hint = validation.hint ?: return stringResource(R.string.cron_hint_custom)
    return cronHintText(hint)
}

@Composable
private fun cronHintText(hint: CronHint): String = when (hint) {
    CronHint.EveryMinute -> stringResource(R.string.cron_hint_every_minute)
    is CronHint.EveryMinutes -> stringResource(R.string.cron_hint_every_minutes, hint.minutes)
    is CronHint.Hourly -> stringResource(R.string.cron_hint_hourly, hint.minute)
    is CronHint.EveryHours -> stringResource(R.string.cron_hint_every_hours, hint.hours, hint.minute)
    is CronHint.Daily -> stringResource(R.string.cron_hint_daily, clock(hint.hour, hint.minute))
    is CronHint.Weekly -> stringResource(
        R.string.cron_hint_weekly,
        weekdayList(hint.days),
        clock(hint.hour, hint.minute),
    )
    is CronHint.Monthly -> stringResource(R.string.cron_hint_monthly, hint.dayOfMonth, clock(hint.hour, hint.minute))
    CronHint.Custom -> stringResource(R.string.cron_hint_custom)
}

@Composable
private fun cronErrorText(error: CronHintError?): String = when (error) {
    CronHintError.FIELD_COUNT -> stringResource(R.string.schedule_error_cron_fields)
    CronHintError.BAD_FIELD -> stringResource(R.string.schedule_error_cron_syntax)
    CronHintError.OUT_OF_RANGE -> stringResource(R.string.schedule_error_cron_range)
    else -> stringResource(R.string.schedule_error_cron_invalid)
}

private fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun weekdayList(days: List<Int>): String =
    days.joinToString(stringResourceSeparator()) { day ->
        // cron 里 0 = 周日；ISO 的 DayOfWeek 是 1(周一)..7(周日)。
        val iso = if (day == 0) 7 else day.coerceIn(1, 7)
        DayOfWeek.of(iso).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

/** 列表分隔符按语言走：中文是「、」，英文是「, 」。 */
private fun stringResourceSeparator(): String =
    if (Locale.getDefault().language.startsWith("zh")) "、" else ", "

@Composable
private fun nextRunLabel(item: ScheduleItem): String {
    val next = item.nextRunAt
    return when {
        !item.enabled -> stringResource(R.string.schedule_paused)
        next == null || next <= 0 -> stringResource(R.string.schedule_next_unknown)
        else -> stringResource(R.string.schedule_next_run, formatEpoch(next))
    }
}

private fun formatEpoch(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}
