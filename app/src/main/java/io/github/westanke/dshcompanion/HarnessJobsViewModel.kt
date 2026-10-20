package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.JobOutput
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.debug.DiagLog
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台任务面板（`ChatJobsSheet`）的动作集。
 *
 * 为什么是 `HarnessViewModel` 的扩展函数而不是独立 ViewModel 类：与
 * `HarnessSessionsViewModel` / `HarnessFilesViewModel` 同一套制度 ——
 * 状态集中在 `HarnessState`（[JobsPanelState] 是其中一个字段，定义在
 * `HarnessModels.kt`），动作按功能拆文件。`withClient` 已把「无连接丢弃留痕」
 * 「代际失效连失败一起丢」这两件公共事项做掉，这里不必也不该重写。
 *
 * 动作共五件：开/关面板、读清单、终止（确认框在 UI 层）、展开/收起输出、
 * 输出读取。v1 不做「加载更多」：任务输出是环形缓冲，超限的旧字节内核
 * 本来就不留了，与其给一个按不动的按钮，不如把 `lossy` 标记亮出来明说
 * 「这段不完整」；增量续读的偏移（[JobOutput.next]）留在数据层，真要分段
 * 加载时接口是现成的。
 *
 * 注意 `update` 的 transform 是 **receiver lambda**（`HarnessState.() -> HarnessState`）：
 * 里面直接读字段、直接 `copy(...)`，没有 `it` 也没有参数名。嵌套取局部变量
 * （`val current = jobsPanel ?: return@update this`）来避免 receiver 上的可空拷贝。
 */

/** 打开面板并立即读清单。[sessionId] 记进状态，终止与输出读取都靠它。 */
internal fun HarnessViewModel.openJobsPanel(sessionId: String) {
    update { copy(jobsPanel = JobsPanelState(sessionId = sessionId)) }
    loadJobs()
}

internal fun HarnessViewModel.closeJobsPanel() = update { copy(jobsPanel = null) }

/** （重新）读清单。刷新后展开状态要存活：expandedJobId 不动，输出按需重读。 */
internal fun HarnessViewModel.loadJobs() = withClient { api ->
    val sessionId = mutableState.value.jobsPanel?.sessionId ?: return@withClient
    update {
        copy(jobsPanel = jobsPanel?.copy(loading = true, error = null))
    }
    try {
        val jobs = withContext(Dispatchers.IO) { api.sessionJobs(sessionId) }
        update {
            copy(jobsPanel = jobsPanel?.copy(loading = false, jobs = jobs, error = null))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 清单读失败不能把面板打成空白页：保留旧清单（如果有），只把错误亮出来。
        update {
            copy(jobsPanel = jobsPanel?.copy(loading = false, error = messageOf(error)))
        }
        DiagLog.w("JOBS", "loadJobs 失败: ${error.message}")
    }
}

/**
 * 终止一条任务。**调用方（UI）负责先弹确认框** —— 这里不做二次确认，
 * 因为确认文案要写清楚对象是谁，只有 UI 层知道用户点的是哪一条。
 *
 * 成功后立刻刷新清单：状态徽标从「运行中」翻到终态要靠下一次拉取。
 * `already-finished` 与 `requested` 的反馈文案不同（「它已经结束了」vs
 * 「已终止」），经 [JobsPanelState.killNotice] 亮给用户。
 */
internal fun HarnessViewModel.killJob(jobId: String) = withClient { api ->
    val sessionId = mutableState.value.jobsPanel?.sessionId ?: return@withClient
    update {
        copy(jobsPanel = jobsPanel?.copy(killingJobId = jobId))
    }
    try {
        val result = withContext(Dispatchers.IO) { api.killJob(sessionId, jobId) }
        val notice = when (result.outcome) {
            // 「它已经结束了」不是「已终止」的成功说法：用户在远程场景下看到
            // 这句才知道不用再管了。
            "already-finished" -> appString(R.string.jobs_kill_already_finished)
            else -> appString(R.string.jobs_kill_requested)
        }
        update {
            copy(jobsPanel = jobsPanel?.copy(killingJobId = null, killNotice = notice))
        }
        loadJobs()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        update {
            copy(jobsPanel = jobsPanel?.copy(killingJobId = null, killNotice = messageOf(error)))
        }
        DiagLog.w("JOBS", "killJob 失败 job=$jobId: ${error.message}")
    }
}

/** 展开/收起一条任务的输出；收起时顺手清掉已读的那份，避免残留上一条的内容。 */
internal fun HarnessViewModel.toggleJobOutput(jobId: String) {
    val panel = mutableState.value.jobsPanel ?: return
    if (panel.expandedJobId == jobId) {
        update {
            copy(jobsPanel = jobsPanel?.copy(expandedJobId = null, expandedOutput = null, outputLoading = false))
        }
        return
    }
    update {
        copy(jobsPanel = jobsPanel?.copy(expandedJobId = jobId, expandedOutput = null, outputLoading = true))
    }
    val api = client ?: return
    viewModelScope.launch {
        try {
            val output = withContext(Dispatchers.IO) { api.jobOutput(panel.sessionId, jobId) }
            update {
                val current = jobsPanel ?: return@update this
                if (current.expandedJobId != jobId) return@update this // 用户已收起或换了一条
                copy(jobsPanel = current.copy(outputLoading = false, expandedOutput = output))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            update {
                val current = jobsPanel ?: return@update this
                if (current.expandedJobId != jobId) return@update this
                copy(
                    jobsPanel = current.copy(
                        outputLoading = false,
                        expandedOutput = JobOutput(
                            jobId = jobId,
                            text = messageOf(error),
                            next = 0,
                            lossy = false,
                            status = null,
                        ),
                    ),
                )
            }
            DiagLog.w("JOBS", "jobOutput 失败 job=$jobId: ${error.message}")
        }
    }
}
