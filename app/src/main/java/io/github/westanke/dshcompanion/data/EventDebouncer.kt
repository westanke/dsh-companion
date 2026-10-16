package io.github.westanke.dshcompanion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 事件驱动的副作用闸门：把「每来一帧就干一次」压成「安静一段时间后干一次」。
 *
 * ## 为什么需要它
 *
 * 服务端推来的事件是**逐个**到达的，而客户端对某些事件的反应是**发一次网络请求**
 * （最典型的是 `file.*` → 重新拉目录）。内核在批量改写文件时（构建产物、格式化多个文件）
 * 会连着发很多帧，于是一个「事件驱动的界面」就变成了**事件驱动的轮询风暴** ——
 * 手机这头连发几十个请求，服务端逐个应答，电量与流量都白烧。
 *
 * ## 语义（与 `scheduleChatRefresh` 同一套，但这里可测）
 *
 * - 窗口内多次 [trigger] 会**不断重置**窗口，直到**安静满 `windowMillis`** 才执行**一次** [action]；
 * - 同一个闸门**同时最多一个**在飞任务。
 *
 * 注意这是**去抖（trailing）不是节流（throttle）**：事件风暴期间一次都不执行，等它停下来
 * 才做那唯一一次。节流会在风暴中每 400ms 干一次 —— 那正是要消除的请求风暴。
 *
 * > 已知取舍：事件**永不停歇**时 [action] 会被饿死。这与既有的 `scheduleChatRefresh` 同样取舍，
 * > 换来的是「风暴中零请求」；真要封顶得再加一个 maxWait 参数，目前没有这个需求。
 *
 * 抽成独立小类而不是写在 ViewModel 里，是因为那样根本测不了（ViewModel 需要 Android 环境）。
 * 这里给一个 `CoroutineScope` + 窗口毫秒数就能用虚拟时间把语义钉死。
 */
internal class EventDebouncer(
    private val scope: CoroutineScope,
    private val windowMillis: Long,
    private val action: suspend () -> Unit,
) {
    private var job: Job? = null

    /** 记一次「有事发生」，并把等待窗口重新计时。 */
    fun trigger() {
        // 重置 = 取消上一个还没到点的窗口再起一个。取消即丢弃，所以不需要额外的 pending 标志
        // （标志与 job 是同一件事的两种表达，留两个反而会出现「标志说该跑、job 已被取消」的分裂状态）。
        job?.cancel()
        job = scope.launch {
            delay(windowMillis)
            action()
        }
    }

    /** 取消待执行的动作（ViewModel 清理时调用）。 */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
