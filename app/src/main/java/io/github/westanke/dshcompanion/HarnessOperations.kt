package io.github.westanke.dshcompanion

import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.debug.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ============================================================================
 * 请求调度：把「发一个请求、回来写状态」这件事收在两个函数里
 * ============================================================================
 *
 * 从 `HarnessViewModel.kt` 搬出来，**只是搬家**（成员函数改成扩展函数，调用点写法不变）。
 *
 * 为什么现在搬：给这两个函数补诊断日志之后，`HarnessViewModel.kt` 涨到 817 行，
 * 越过了 `docs/project/TECH-DEBT.md` 的 800 行预算，被 `ArchitectureGuardTest` 抓住。
 * 按 `AGENTS.md` §4.2，「拆到预算内」优先于「登记成债」。
 *
 * 为什么是这两个：它们是**所有**网络动作的公共出口 —— 「点了没反应」这类问题
 * 十有八九就落在这里的某条早退分支上，单独成文件后翻起来最快。
 */

/**
 * 拿当前活跃连接跑一段请求；**没有连接时不再静默返回**。
 *
 * 这里的 `?: return` 曾经一声不吭，而它正是「点了没反应、也没有任何提示」的
 * 头号嫌疑点 —— 真机验收 F2（新建文件「静默失败」）就卡在「不知道请求到底发出去没有」。
 * 现在至少留下一条诊断日志：有没有连接、这一下有没有被丢弃，事后查得到。
 */
internal fun HarnessViewModel.withClient(block: suspend (DshClient) -> Unit) {
    val api = client
    if (api == null) {
        DiagLog.w("SEND", "无活动连接：操作被丢弃，未发出任何请求")
        return
    }
    launchOperation { block(api) }
}

/**
 * 跑一个异步操作，回来时先过**代际检查**。
 *
 * 领号必须在这一行（调用点），**不能**挪进协程里：协程启动本身有延迟，
 * 若在延迟之后才领号，「发出请求 → 用户立刻切换」这段窗口里领到的会是**新**代际，
 * 结果就被当成有效写回去了 —— 而它恰恰是我们要挡的那种情况。
 *
 * 换代之后 `busy = false` 也一并跳过：那个标志属于**新**请求，
 * 旧请求清它会让转圈提前消失（用户以为加载完了，其实新请求还在跑）。
 */
internal fun HarnessViewModel.launchOperation(block: suspend () -> Unit) {
    val token = requestGate.current
    viewModelScope.launch {
        update { copy(busy = true, error = null) }
        val result = runCatching { withContext(Dispatchers.IO) { block() } }
        // 代际失效时**连失败一起丢**（不写 error、不清 busy）—— 设计如此，
        // 但它同时也是「错误凭空消失」的第二个嫌疑点，所以留一条痕。
        if (!requestGate.isCurrent(token)) {
            DiagLog.w("SEND", "代际失效：结果与错误一并丢弃（界面不会有任何提示）")
            return@launch
        }
        result.onFailure {
            // 任何请求失败都留痕：真机上没有 logcat，这里是唯一能事后追的地方。
            // 记「异常类型 + 原文」，不记 messageOf 的本地化句子 —— 排查要的是原文。
            DiagLog.w("SEND", "请求失败：${it::class.java.simpleName}: ${it.message}")
            update { copy(error = messageOf(it)) }
        }
        update { copy(busy = false) }
    }
}