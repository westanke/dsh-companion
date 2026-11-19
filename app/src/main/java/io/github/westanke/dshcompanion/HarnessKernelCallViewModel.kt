package io.github.westanke.dshcompanion

import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.supportsKernelCall
import io.github.westanke.dshcompanion.data.ws.WsFrames
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 通用内核调用面的动作层（桥侧 issue #26 阶段 5）。
 *
 * 这一层只做三件事：**能力位再确认**、把界面里的 args 文本解成 JSON 对象、把结果/错误写回
 * [HarnessState]。帧的编解码在 [WsFrames]，pending 与超时在
 * [io.github.westanke.dshcompanion.data.ws.KernelCallClient]。
 *
 * 为什么动作里还要再查一次能力位：入口虽然在设置页按能力位隐藏（缺位不组合），
 * 但弹层可能在「能力位消失 / 换连接」的瞬间还开着 —— 发出去的帧要么被老桥忽略、
 * 要么落到另一条连接上，都会表现成一条无从归属的 error。宁可在这里挡掉。
 */
internal fun HarnessViewModel.runKernelCall(endpoint: String, argsText: String) {
    if (!supportsKernelCall(mutableState.value.bridgeCapabilities)) {
        update { copy(kernelCallError = appString(R.string.kernel_call_error_not_available)) }
        return
    }
    if (mutableState.value.kernelCallInFlight != null) return

    val args = parseKernelArgs(argsText)
    if (args == null) {
        update { copy(kernelCallError = appString(R.string.kernel_call_error_args)) }
        return
    }
    val client = kernelCallClient
    if (client == null) {
        update { copy(kernelCallError = appString(R.string.kernel_call_error_no_channel)) }
        return
    }

    update { copy(kernelCallInFlight = endpoint, kernelCallError = null, kernelCallResult = null) }
    viewModelScope.launch {
        runCatching { client.call(endpoint, args) }
            .onSuccess { result ->
                update { copy(kernelCallInFlight = null, kernelCallResult = result) }
            }
            .onFailure { error ->
                update {
                    copy(
                        kernelCallInFlight = null,
                        kernelCallError = error.message
                            ?: appString(R.string.kernel_call_error_unknown),
                    )
                }
            }
    }
}

/**
 * 界面里的 args 文本 → JSON 对象。
 *
 * 空文本按 `{}` 处理（无参端点直接可调）；数组 / 标量 / 非法 JSON 一律返回 null，
 * 由调用方转成「参数必须是 JSON 对象」。**不按端点硬编码键名** —— 允许的键集合
 * 来自目录帧的 `wires`，界面只负责展示。
 */
private fun parseKernelArgs(text: String): JsonObject? {
    val trimmed = text.trim().ifEmpty { "{}" }
    return runCatching { WsFrames.json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
}
