package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshConnectionException
import io.github.westanke.dshcompanion.data.localizedErrorRes
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/*
 * ============================================================================
 * 错误 → 人话：HarnessViewModel 的两个扩展函数
 * ============================================================================
 *
 * ## 为什么拆出来
 *
 * 纯行数预算：`HarnessViewModel.kt` 已经贴着 800 行线
 * （`ArchitectureGuardTest` 第 1 条会红），而这两个函数的**分支注释比代码长** ——
 * 那段「子类必须在父类之前判断」的说明正是最容易在加新异常类型时被破坏的地方。
 *
 * 按 `docs/project/TECH-DEBT.md` 记过的老路数：成员函数搬到
 * `Harness*ViewModel.kt` 里写成扩展函数，**调用点写法完全不变**
 * （`viewModel.messageOf(e)` 照样成立），所以 `ui/` 下所有界面的行为一行都没改。
 */

/**
 * 把一次失败的调用换成用户能读的句子。
 *
 * 三档，顺序即优先级：
 * 1. `DshApiException` → 走错误码表（[localizedError]），服务端给了码就用它；
 * 2. 网络层异常 → 各给一句专属文案。这些异常池的 `message` 全是英文
 *    （`DshConnectionException` 的 "All N endpoint(s) unreachable: ..."、
 *    OkHttp 的 "failed to connect to ..."），中文界面下启动报错就是一整句英文。
 *    **子类必须在父类 [SocketException] / [IOException] 之前判断** ——
 *    超时/拒绝/解析失败都是它们的子类，父类写在前面会把它们全吞成同一句。
 * 3. 其余未知异常 → 保留原文：能搜、能贴给开发者（与错误码表「认出不硬猜」同一原则）。
 */
internal fun messageOfImpl(vm: HarnessViewModel, error: Throwable): String = when (error) {
    is DshApiException -> vm.localizedError(error.code, error.message)
    is DshConnectionException -> vm.appString(R.string.err_connect_all_unreachable)
    is SocketTimeoutException -> vm.appString(R.string.err_network_timeout)
    is UnknownHostException -> vm.appString(R.string.err_network_unreachable)
    is ConnectException -> vm.appString(R.string.err_network_refused)
    is SSLException -> vm.appString(R.string.err_network_reset)
    is SocketException -> vm.appString(R.string.err_network_reset)
    is EOFException -> vm.appString(R.string.err_network_reset)
    is IOException -> vm.appString(R.string.err_network_generic, error.message ?: error::class.java.simpleName)
    else -> error.message ?: error::class.java.simpleName
}

/**
 * 把服务端那句英文错误换成用户语言的句子（表在 `data/ErrorMessages.kt`）。
 *
 * 认不出的错误码**保留原文**：编一句像模像样的话只会把人带偏，而原文还能搜、能贴给开发者。
 * 以前这里一律拼成 `CODE: English message.`，于是中文界面下也全是英文。
 */
internal fun localizedErrorImpl(vm: HarnessViewModel, code: String?, raw: String): String {
    val res = localizedErrorRes(code) ?: return raw
    return if (res == R.string.err_request_generic) vm.appString(res, code.orEmpty()) else vm.appString(res)
}
