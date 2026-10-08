package io.github.hakunm.deepseekharness.data

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CLOSED_MESSAGE = "ConnectionCoordinator was closed"

/**
 * 该主机的**全部**地址都探活失败时抛出，携带每一个地址的失败原因。
 *
 * 为什么不把原因折叠成一句话：一台电脑的三个地址可能分别是「超时」「HTTP 502」「连接被拒」，
 * 诊断面板要逐条展示，只给用户一句「连接失败」等于什么都没说 —— 而这正是用户过去
 * 只能手填地址、却不知道填哪个才通的根因。顺序与 [Host.activeEndpoints] 一致。
 */
class DshConnectionException(
    val probes: List<EndpointProbe>,
    message: String = describeFailures(probes),
) : IOException(message)

private fun describeFailures(probes: List<EndpointProbe>): String =
    if (probes.isEmpty()) {
        "This host has no enabled endpoint to probe"
    } else {
        "All ${probes.size} endpoint(s) unreachable: " +
            probes.joinToString("; ") { probe -> "${probe.endpointId}: ${probe.error ?: "unknown failure"}" }
    }

private fun describeThrowable(error: Throwable): String {
    val name = error::class.java.simpleName.ifBlank { error.javaClass.name }
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail == null) name else "$name: $detail"
}

/**
 * 连接协调层：一台电脑有多个可达地址时，并发探活 → 按 [EndpointSelection.rank] 选路 → 失败自动回退。
 *
 * ## 为什么必须并发 + 单地址超时
 *
 * 用户的场景是「人在外面，家里局域网 192.168.x 不可达、虚拟网 100.64.x 可达、公网可能也行」。
 * 三个地址里必然有挂死的（不可达的网段会一直等到 TCP 超时）。若串行探测，一次连接要等
 * 挂死地址的完整超时；若整批共用一个超时，一个挂死地址会把其他可用地址一起拖死。
 * 所以：每个地址独立计时、独立超时，整批并发，总耗时接近「单个超时」而不是累加。
 *
 * ## 生命周期：调用方**必须** [close]
 *
 * 本类持有自己的协程作用域 [probeScope] 来跑阻塞探活，它不属于调用方的作用域树，
 * 所以调用方（`ViewModel.onCleared()`、切换主机后替换旧协调器时）必须显式 [close]：
 * 否则作用域连同被放弃的挂死调用一起泄漏。
 *
 * 本类不 import 任何 `android.*`，可直接在 JVM 单测里跑。
 */
class ConnectionCoordinator(
    private val clientFactory: (baseUrl: String, token: String?) -> DshClient = { url, token -> DshClient(url, token) },
    private val probeTimeoutMs: Long = 1500L,
) : Closeable {
    /**
     * 专门用来跑探活那一次真正的 HTTP 调用的后台 scope（[Dispatchers.IO]，`SupervisorJob` 保证
     * 单个地址失败不影响其他地址）。
     *
     * 为什么不能把 `withTimeoutOrNull` 直接套在 `client.health()` 外面：[DshClient.health] 是同步
     * 阻塞的 OkHttp 调用（内部 `Call.execute()`）。协程取消**无法**中断卡在 socket 读上的阻塞调用，
     * 超时到点后协程仍要等这次调用返回才会被唤醒 —— 最坏要等 OkHttp 自己的 45s 读超时，
     * 于是「一个挂死地址拖住整批」照样发生。
     *
     * 因此这里把调用交给本类自有的 scope 去跑，只对 `Deferred.await()` 设 deadline：超时后立刻
     * 放弃等待并返回失败，被放弃的调用留在后台自行结束（一次多余的健康检查请求，无副作用）。
     * 这也是唯一不必假设调用方注入了多少 HTTP 超时的做法 —— [clientFactory] 是外部注入的，
     * 本类必须自己拥有这个 deadline。
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [close] 的显式信号，用来叫醒正在等待的探活。
     *
     * 为什么不能只靠 `probeScope.cancel()`：探活等待的是 `Deferred.await()`，而 await 需要 Job
     * **真正完成**才会唤醒等待者。被取消的 Job 若其协程体正卡在阻塞的 OkHttp 调用里，它会一直停在
     * Cancelling 状态（协程取消打不断阻塞调用），await 也就一直不返回 —— 只取消 scope 等于拦不住
     * 在途探活。所以这里额外发一个信号，让等待方立刻收尾。
     */
    private val closedSignal = CompletableDeferred<Unit>()

    @Volatile
    private var closed = false

    /**
     * 关闭本协调器：叫醒所有在途探活、取消 [probeScope]，此后不再发起任何探活。
     *
     * 调用方（`ViewModel.onCleared()`、替换旧协调器时）必须调用它，否则作用域泄漏。
     *
     * ## 能做到什么、做不到什么
     *
     * - 做到：正在等待结果的 [probeAll] / [connect] **立刻**以 [CancellationException] 收尾
     *   （由 [closedSignal] 叫醒，不必等挂在途的地址走完自己的超时）；之后的新调用快速失败
     *   （`IllegalStateException`），不再新建客户端、不再发请求；[probeScope] 被取消，实例可被回收。
     * - 做不到：**打断已经在途的那次阻塞 HTTP 调用**。协程取消无法中断卡在 socket 读上的
     *   `Call.execute()`，而 [DshClient] 没有暴露 `Call.cancel()`（也就不在本类的写作用域内）。
     *   这些调用会一直占着 `Dispatchers.IO` 的线程，直到 OkHttp 自己的读超时（默认 45s）返回。
     *   因此 [close] 的作用是「立刻停止等待 + 不再新增泄漏」，而不是「立刻清空线程占用」；
     *   只要一个协调器实例服务一个界面生命周期并在结束时 close，线程就不会堆积。
     */
    override fun close() {
        closed = true
        closedSignal.complete(Unit)
        probeScope.cancel()
    }

    /**
     * 并发探测 [host] 的全部 [Host.activeEndpoints]，返回顺序与之一致的探活结果。
     *
     * 单个地址失败（连接被拒 / HTTP 非 2xx / 响应无法解析 / 超时）不会让整批失败，
     * 失败原因写进 [EndpointProbe.error] 供诊断面板展示。
     */
    suspend fun probeAll(host: Host): List<EndpointProbe> {
        check(!closed) { "ConnectionCoordinator.probeAll() called after close()" }
        val endpoints = host.activeEndpoints
        if (endpoints.isEmpty()) return emptyList()
        val token = host.credential?.secret
        return coroutineScope {
            endpoints.map { endpoint -> async { probe(endpoint, token) } }.awaitAll()
        }
    }

    /**
     * 探活全部地址并选出最合适的一个，返回可用于后续请求的客户端。
     *
     * 选路完全复用 [EndpointSelection.rank]（延迟优先、局域网近似即优先、并列时 preferred 优先），
     * 本类不另写一套排序。所有地址都不可达时返回
     * [Result.failure]，cause 为携带全部探活原因的 [DshConnectionException]。
     *
     * 注意：[ActiveConnection.host] 原样返回传入的 [host]，本类不改写主机的探活记录 ——
     * 落盘由 `HostStore` 负责（可用 [Host.withEndpointProbe] / [Host.withPreferred] 写回）。
     */
    suspend fun connect(host: Host): Result<ActiveConnection> {
        val probes = probeAll(host)
        val best = EndpointSelection.rank(probes, host.endpoints, host.preferredEndpointId).firstOrNull()
            ?: return Result.failure(DshConnectionException(probes))
        val endpoint = host.endpoint(best.endpointId)
            ?: return Result.failure(DshConnectionException(probes))
        val client = try {
            clientFactory(endpoint.baseUrl, host.credential?.secret)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return Result.success(
            ActiveConnection(host = host, endpoint = endpoint, client = client, probes = probes),
        )
    }

    /**
     * 单个地址的探活：独立计时 + 独立 [probeTimeoutMs] 上限。
     *
     * 这里刻意不吞 [CancellationException]：调用方（例如用户中途退出、切换主机）取消时，
     * 必须让协程正常取消，而不是伪装成「这个地址探活失败」。
     */
    private suspend fun probe(endpoint: Endpoint, token: String?): EndpointProbe {
        val client = try {
            clientFactory(endpoint.baseUrl, token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 例如 baseUrl 不合法（DshClient 构造时 require http(s):// 前缀）。
            return EndpointProbe.failure(endpoint.id, describeThrowable(error))
        }

        val startedAt = System.nanoTime()
        val call = probeScope.async { client.health() }
        val health = try {
            withTimeoutOrNull(probeTimeoutMs) { awaitHealth(call) }
        } catch (cancellation: CancellationException) {
            call.cancel()
            throw cancellation
        } catch (error: Throwable) {
            call.cancel()
            return EndpointProbe.failure(endpoint.id, describeThrowable(error))
        }
        if (health == null) {
            call.cancel()
            return EndpointProbe.failure(endpoint.id, "timeout after ${probeTimeoutMs}ms")
        }
        return EndpointProbe.success(endpoint.id, (System.nanoTime() - startedAt) / 1_000_000)
    }

    /**
     * 等 [call] 的探活结果，但**同时监听** [closedSignal]：谁先来用谁。
     *
     * 为什么需要这一层（两个坑，都是实测踩出来的）：
     * 1. `call.await()` 只在 Job **完成**时唤醒等待者。`call` 的协程体卡在阻塞的 OkHttp 调用里，
     *    [close] 取消 [probeScope] 后它会一直停在 Cancelling，await 也就一直不返回 —— 光取消 scope
     *    拦不住在途探活（实测会等满地址自身的超时）。
     * 2. 但也不能让监听者「抛一个 [CancellationException] 了事」：子协程抛出的 CE 会被
     *    `JobSupport.childCancelled` 视为「该子协程正常取消」而不上传给父协程，父协程照样继续
     *    等 `call.await()`（实测同样是等满超时）。必须**显式 cancel 掉正在等待的这一层** ——
     *    await 是真正可挂起、可取消的，只有 `call` 内部那次阻塞调用不可取消。
     */
    private suspend fun awaitHealth(call: Deferred<HealthView>): HealthView = coroutineScope {
        val awaiting = this
        val closedWatcher = launch {
            closedSignal.await()
            awaiting.cancel(CancellationException(CLOSED_MESSAGE))
        }
        try {
            call.await()
        } finally {
            closedWatcher.cancel()
        }
    }
}
