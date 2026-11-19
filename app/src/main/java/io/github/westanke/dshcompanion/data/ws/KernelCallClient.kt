package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * 通用内核调用面的客户端（桥侧 issue #26 阶段 5）。
 *
 * 为什么不套 [WsRequestRouter]：`kernel-call-result` 在桥的
 * `REQUEST_ID_ECHO_EXCLUDED_KINDS` 里，**没有 requestId 回显**，router 的
 * 「按 requestId 认领」路径根本走不到；硬塞进 router 只能退化成按 kind 串行匹配
 * （router 每个 kind 一把锁），一条慢调用会把同 kind 的其他调用全堵住，
 * 更做不了取消。这里的场景很简单：自铸 `callId`、自持 pending 表、结果帧按 callId 认领。
 *
 * **幂等**：同一个 `callId` 重试在桥侧按 `(principal, callId)` 回放 24h，所以
 * 需要重试时调用方应复用 [call] 返回/内部使用的 callId（当前实现每次 [call] 新铸一个，
 * UI 的「再调一次」是新调用，不是重试）。
 *
 * 只由装配层在 WS 通道建立时构造；[onFrame] 接在 [MobileWsTransport] 的推送出口上，
 * 返回 false 表示「这条帧不归我」，交回 `WsRequestRouter`。
 */
internal class KernelCallClient(
    /** 把一帧交给 WS 发送；返回 false 表示通道不可用（未连接 / 已 stale）。 */
    private val sender: (JsonObject) -> Boolean,
    /** 铸 callId；测试可注入固定值。 */
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    /** 目录更新回调（装配层用来写进 [io.github.westanke.dshcompanion.HarnessState]）。 */
    private val onEndpoints: (List<KernelEndpoint>) -> Unit = {},
    private val timeoutMillis: Long = CALL_TIMEOUT_MS,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<KernelCallResultFrame>>()
    private val _endpoints = MutableStateFlow<List<KernelEndpoint>>(emptyList())

    /** 最近一次 `kernel-endpoints` 快照；空列表 = 还没收到（或这次连接还没鉴权完成）。 */
    val endpoints: StateFlow<List<KernelEndpoint>> = _endpoints.asStateFlow()

    /**
     * 吃一条服务端推送帧。
     *
     * @return true 表示这条帧属于内核调用面（已处理，别再交给 router）；
     * false 表示不归这里管（router 继续按原逻辑分发）。
     */
    fun onFrame(frame: JsonObject): Boolean = when (WsFrames.kindOf(frame)) {
        WsServerFrame.KERNEL_CALL_RESULT -> {
            // 形状不对就丢弃：真正等结果的调用会在超时后落到 WS_TIMEOUT，
            // 比拿半条帧去 complete 更可诊断。
            WsFrames.parseKernelCallResult(frame)?.let { result ->
                pending.remove(result.callId)?.complete(result)
            }
            true
        }

        WsServerFrame.KERNEL_ENDPOINTS -> {
            WsFrames.parseKernelEndpoints(frame)?.let { list ->
                _endpoints.value = list
                onEndpoints(list)
            }
            true
        }

        else -> false
    }

    /**
     * 调一个内核端点并等结果帧。
     *
     * 业务失败（`ok:false`）**不抛异常**：结果帧本身就是答案，错误码在
     * [KernelCallResultFrame.error] 里（`METHOD_NOT_ALLOWED` 与端点不存在同形，
     * 不要据此推断端点存在）。只有「帧没发出去 / 等不到结果帧」才抛 [DshApiException]。
     */
    suspend fun call(
        endpoint: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): KernelCallResultFrame {
        val callId = newCallId()
        val deferred = CompletableDeferred<KernelCallResultFrame>()
        pending[callId] = deferred
        if (!sender(WsFrames.kernelCall(callId, endpoint, args))) {
            pending.remove(callId)
            throw DshApiException(0, "WS_NOT_READY", "内核调用帧没发出去：WS 通道不可用")
        }
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // 和 router 的超时保持同一套语义：status=408、code=WS_TIMEOUT。
            throw DshApiException(408, "WS_TIMEOUT", "内核调用超时（${timeoutMillis / 1000}s）：$endpoint")
        } finally {
            pending.remove(callId)
        }
    }

    /**
     * 请求取消一次还在跑的调用（best-effort）。
     *
     * 返回 false 只表示「取消帧没发出去」，不代表调用没被取消；结果帧永远以
     * `kernel-call-result` 为准（已结算的调用照样会收到，调用方按 callId 去重）。
     */
    fun cancel(callId: String): Boolean = sender(WsFrames.kernelCallCancel(callId))

    /** 通道断开时收掉所有等待中的调用，避免调用方干等到超时。 */
    fun failAll(code: String, message: String) {
        pending.keys.toList().forEach { callId ->
            pending.remove(callId)?.completeExceptionally(DshApiException(0, code, message))
        }
    }

    /** 等待中的调用数（诊断 / 测试用）。 */
    fun pendingCount(): Int = pending.size

    companion object {
        /**
         * 内核端点虽然都是 unary，但可能真的跑得久（终端命令、文件操作），
         * 15s 的通道默认超时不够；30s 与 REST 侧的长请求口径接近。
         */
        const val CALL_TIMEOUT_MS: Long = 30_000
    }
}
