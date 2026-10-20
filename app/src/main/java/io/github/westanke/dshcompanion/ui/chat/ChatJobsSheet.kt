package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.JobsPanelState
import io.github.westanke.dshcompanion.closeJobsPanel
import io.github.westanke.dshcompanion.loadJobs
import io.github.westanke.dshcompanion.toggleJobOutput
import io.github.westanke.dshcompanion.killJob
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.JobStatus
import io.github.westanke.dshcompanion.data.JobView
import io.github.westanke.dshcompanion.data.isLive

/**
 * 后台任务面板（会话 ⋯ 菜单 →「后台任务」，bridge ≥ 2.4.3）。
 *
 * ## 为什么要有它
 *
 * 远程场景的真焦虑：在手机上让 agent 跑个长任务（构建、批量处理），然后切走或
 * 放下手机 —— 此时没有任何地方能看到「它还在跑吗」，更没有地方能「让它停下」。
 * 任务跑飞了只能干等，或者回到电脑前。
 *
 * ## 与 ChatStatsSheet 的差异
 *
 * Stats 是纯计算（从已拉取的 history 里数数），这里是**网络面板**：打开时拉清单、
 * 终止要确认、输出按需读。数据进 [HarnessState.jobsPanel]（不留在 Sheet 局部），
 * 动作走 [HarnessViewModel] 的扩展 —— Compose 层不发请求，是本项目的架构纪律。
 *
 * ## 手动刷新，不做实时推送
 *
 * 服务端就是 REST 只读一帧（内核 roster 流首帧即全量），v1 用按钮刷新 +
 * 终止后自动刷新；实时推送等 WS 事件通道设计好再说，不要为了「看起来实时」
 * 在手机上挂一个轮询。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatJobsSheet(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val panel = state.jobsPanel
    // 关闭即清状态：面板是临时的，残留一份旧会话的任务清单只会误导。
    ModalBottomSheet(onDismissRequest = { viewModel.closeJobsPanel(); onClose() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.jobs_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { viewModel.loadJobs() }, enabled = panel != null && !panel.loading) {
                    Text(stringResource(R.string.jobs_refresh))
                }
            }
            when {
                panel == null -> Unit // 关闭动画进行中，不渲染
                panel.loading && panel.jobs.isEmpty() -> LoadingRow()
                panel.error != null && panel.jobs.isEmpty() -> Text(
                    panel.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                panel.jobs.isEmpty() -> Text(
                    stringResource(R.string.jobs_empty),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    panel.error?.let { error ->
                        // 有清单也有错误（刷新失败）：旧清单照常显示，错误单独亮一行。
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    panel.killNotice?.let { notice ->
                        Text(
                            notice,
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    panel.jobs.forEach { job ->
                        JobCard(panel, job, viewModel)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.padding(end = 12.dp))
        Text(stringResource(R.string.jobs_title), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun JobCard(panel: JobsPanelState, job: JobView, viewModel: HarnessViewModel) {
    val expanded = panel.expandedJobId == job.id
    var confirming by remember(job.id) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // label 是生产者给的一行说明（命令行、阶段名），是列表里的主要信息；
                // kind 目前只有 bash，展示价值低，塞进状态徽标的语义描述里。
                Text(
                    job.label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                )
                StatusChip(job.status)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    runDurationOf(job),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                job.progress?.takeIf { job.status.isLive }?.let { progress ->
                    Text(
                        progress,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            job.detail?.let { detail ->
                Text(
                    detail,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.toggleJobOutput(job.id) }) {
                    Text(stringResource(if (expanded) R.string.jobs_hide_output else R.string.jobs_view_output))
                }
                if (job.status.isLive) {
                    TextButton(
                        onClick = { confirming = true },
                        enabled = panel.killingJobId == null,
                    ) {
                        Text(
                            stringResource(R.string.jobs_kill),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (expanded) {
                OutputBlock(panel, job)
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.jobs_kill_confirm_title)) },
            text = { Text(stringResource(R.string.jobs_kill_confirm_body, job.label)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; viewModel.killJob(job.id) }) {
                    Text(stringResource(R.string.jobs_kill_confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.jobs_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusChip(status: JobStatus) {
    val res = when (status) {
        JobStatus.RUNNING -> R.string.jobs_status_running
        JobStatus.STOPPING -> R.string.jobs_status_stopping
        JobStatus.COMPLETED -> R.string.jobs_status_completed
        JobStatus.KILLED -> R.string.jobs_status_killed
        JobStatus.FAILED -> R.string.jobs_status_failed
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(res)) },
        // AssistChip 没有「纯徽标」形态，禁用态颜色正好把它压成只读展示；
        // onClick 留空是它 API 要求的，不是给用户的交互。
        modifier = Modifier.semantics { contentDescription = "job status" },
    )
}

@Composable
private fun OutputBlock(panel: JobsPanelState, job: JobView) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.jobs_output_title), style = MaterialTheme.typography.labelLarge)
        // lossy 必须显式说出来：把一段不完整的输出当成全部展示，
        // 用户会拿它判断任务成败 —— 那是拿假数据做真决策。
        if (panel.expandedOutput?.lossy == true) {
            Text(
                stringResource(R.string.jobs_output_lossy),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            panel.outputLoading -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.jobs_output_title), style = MaterialTheme.typography.bodySmall)
            }
            panel.expandedOutput?.text?.isEmpty() == true -> Text(
                stringResource(R.string.jobs_output_empty),
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> panel.expandedOutput?.text?.let { text ->
                Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 「已运行 3 分 12 秒」或「运行 2 分 · 终态又过了 5 秒」——远程场景里「跑了多久」比绝对时刻有用。 */
private fun runDurationOf(job: JobView): String {
    val end = job.finishedAt ?: System.currentTimeMillis()
    val seconds = ((end - job.startedAt) / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min ${seconds % 60} s"
        else -> "$seconds s"
    }
}
