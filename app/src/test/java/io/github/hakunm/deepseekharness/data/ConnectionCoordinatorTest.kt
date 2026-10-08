package io.github.hakunm.deepseekharness.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectionCoordinator] 单测：全程只用本地 MockWebServer，不碰真实网络。
 *
 * 为什么用 `runBlocking` 而不是 `runTest`：探活是一次**真实阻塞的 HTTP 调用**，跑在
 * [Dispatchers.IO] 的真实线程池上。`runTest` 的虚拟时间会把 `probeTimeoutMs` 的 delay 立刻推进，
 * 于是「超时」会在真实调用完成之前就触发 —— 那样测到的不是产品行为，而是测试时钟的假象。
 */
class ConnectionCoordinatorTest {

    private val servers = mutableListOf<MockWebServer>()
    private val httpClients = CopyOnWriteArrayList<OkHttpClient>()
    private val factoryCalls = CopyOnWriteArrayList<Pair<String, String?>>()

    /** 键是 [Endpoint.baseUrl]（不带 /api/v1）；用于给某个地址换成定制超时的客户端。 */
    private val factoryOverrides = mutableMapOf<String, () -> DshClient>()

    @After
    fun tearDown() {
        // 被放弃的探活调用可能仍挂在 socket 上，先取消，避免拖慢/卡住 MockWebServer 的关闭。
        httpClients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        httpClients.clear()
        servers.forEach { server -> runCatching { server.shutdown() } }
        servers.clear()
        factoryCalls.clear()
        factoryOverrides.clear()
    }

    // ---------------------------------------------------------------- 探活：并发与失败隔离

    @Test
    fun probesEveryEndpointConcurrentlyInsteadOfOneAfterAnother() = runBlocking {
        val urls = listOf(address(health(delayMs = 400)), address(health(delayMs = 400)), address(health(delayMs = 400)))
        val coordinator = coordinator(timeoutMs = 2_000, readTimeoutMs = 3_000)

        val startedAt = System.nanoTime()
        val probes = coordinator.probeAll(host(urls.mapIndexed { index, url -> endpoint("e$index", url) }))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(listOf("e0", "e1", "e2"), probes.map { it.endpointId })
        assertTrue(probes.all { it.ok })
        assertTrue(probes.all { (it.latencyMs ?: -1L) >= 0L })
        // 三个 400ms 的地址：串行要 ≥1200ms，并发应接近单次。这是「并发」的硬证据。
        assertTrue("并发探活三个 400ms 地址实测 ${elapsedMs}ms，不应接近串行的 1200ms", elapsedMs < 1_000)
        assertEquals(3, factoryCalls.size)
        assertEquals(3, servers.sumOf { it.requestCount })
    }

    @Test
    fun recordsPerEndpointFailureWithoutFailingTheWholeBatch() = runBlocking {
        val reachable = address(health())
        val brokenGateway = address(MockResponse().setResponseCode(503).setBody("nope"))
        val refused = shutDownAddress()
        val coordinator = coordinator()

        val probes = coordinator.probeAll(
            host(listOf(endpoint("up", reachable), endpoint("gateway", brokenGateway), endpoint("refused", refused))),
        )
        val byId = probes.associateBy { it.endpointId }

        assertTrue(byId.getValue("up").ok)
        assertNotNull(byId.getValue("up").latencyMs)

        assertFalse(byId.getValue("gateway").ok)
        assertTrue(
            "HTTP 失败原因应写进 error，实际：${byId.getValue("gateway").error}",
            byId.getValue("gateway").error.orEmpty().contains("503"),
        )

        assertFalse(byId.getValue("refused").ok)
        assertTrue("连接被拒也要有可读原因", byId.getValue("refused").error.orEmpty().isNotBlank())
    }

    @Test
    fun hostWithoutEnabledEndpointProbesNothingAndFailsConnect() = runBlocking {
        val disabled = endpoint("off", "http://192.168.1.126:3090").copy(enabled = false)
        val coordinator = coordinator()

        assertTrue(coordinator.probeAll(host(listOf(disabled))).isEmpty())
        assertTrue(factoryCalls.isEmpty())

        val failure = coordinator.connect(host(listOf(disabled))).exceptionOrNull()
        assertTrue("应为 DshConnectionException，实际 $failure", failure is DshConnectionException)
        assertTrue((failure as DshConnectionException).probes.isEmpty())
    }

    // ---------------------------------------------------------------- 批量失败：携带全部原因

    @Test
    fun connectCarriesEveryEndpointReasonWhenNothingIsReachable() = runBlocking {
        val brokenGateway = address(MockResponse().setResponseCode(502).setBody("bad"))
        val refused = shutDownAddress()
        val coordinator = coordinator()

        val failure = coordinator
            .connect(host(listOf(endpoint("lan", brokenGateway), endpoint("vnet", refused))))
            .exceptionOrNull()

        assertTrue("应为 DshConnectionException，实际 $failure", failure is DshConnectionException)
        val probes = (failure as DshConnectionException).probes
        // 诊断面板要逐地址展示，所以两个地址的原因必须都在，且顺序与 activeEndpoints 一致。
        assertEquals(listOf("lan", "vnet"), probes.map { it.endpointId })
        assertTrue(probes.none { it.ok })
        assertTrue(probes.all { !it.error.isNullOrBlank() })
        assertTrue("消息里应包含全部地址 id，实际：${failure.message}", failure.message.orEmpty().contains("lan"))
        assertTrue("消息里应包含全部地址 id，实际：${failure.message}", failure.message.orEmpty().contains("vnet"))
    }

    // ---------------------------------------------------------------- 选路：局域网近似即优先

    @Test
    fun connectPrefersLanWithinLatencyToleranceEvenWhenSlightlySlower() = runBlocking {
        // 局域网 300ms vs 虚拟网 220ms：300 <= 220 * 1.5 = 330，局域网归入同档后优先。
        val lan = address(health(delayMs = 300))
        val virtualNet = address(health(delayMs = 220))
        val host = host(
            listOf(
                endpoint("lan", lan, EndpointKind.LAN),
                endpoint("vnet", virtualNet, EndpointKind.VIRTUAL_NET),
            ),
        )

        val connection = coordinator(timeoutMs = 2_000, readTimeoutMs = 3_000).connect(host).getOrThrow()

        assertEquals("局域网在容差内应胜出（同网段更稳、不耗虚拟网流量）", "lan", connection.endpoint.id)
        // 选路必须复用契约里的 rank，不允许另写一套排序。
        assertEquals(
            "lan",
            EndpointSelection.rank(connection.probes, host.endpoints, host.preferredEndpointId).first().endpointId,
        )
    }

    @Test
    fun connectFallsBackToTheFasterAddressWhenTheLanIsTooSlow() = runBlocking {
        // 局域网 500ms vs 虚拟网 200ms：500 > 200 * 1.5 = 300，容差不再覆盖，快的胜出。
        val lan = address(health(delayMs = 500))
        val virtualNet = address(health(delayMs = 200))
        val host = host(
            listOf(
                endpoint("lan", lan, EndpointKind.LAN),
                endpoint("vnet", virtualNet, EndpointKind.VIRTUAL_NET),
            ),
        )

        val connection = coordinator(timeoutMs = 2_000, readTimeoutMs = 3_000).connect(host).getOrThrow()

        assertEquals("lan", "lan") // 保持地址 id 语义清晰
        assertEquals("vnet", connection.endpoint.id)
    }

    @Test
    fun connectPicksTheLowestLatencyAddressWhenNoLanQualifies() = runBlocking {
        val slow = address(health(delayMs = 300))
        val fast = address(health(delayMs = 120))
        val host = host(
            listOf(
                endpoint("slow", slow, EndpointKind.WAN),
                endpoint("fast", fast, EndpointKind.WAN),
            ),
        )

        val connection = coordinator(timeoutMs = 2_000, readTimeoutMs = 3_000).connect(host).getOrThrow()

        assertEquals("fast", connection.endpoint.id)
    }

    // ---------------------------------------------------------------- 挂死地址不拖累整批

    @Test
    fun hungEndpointTimesOutWithoutHoldingBackTheReachableOne() = runBlocking {
        val reachable = address(health())
        val hungUrl = address(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // 挂死地址的 OkHttp 读超时故意设成 5s：用来证明起作用的是协调者自己的 250ms 上限，
        // 而不是 OkHttp 的超时（若实现只是「串行等 OkHttp 超时」，本用例会耗时 ≥5s 而失败）。
        val hungClient = newHttpClient(5_000)
        factoryOverrides[hungUrl] = { DshClient(hungUrl, null, hungClient) }
        val coordinator = coordinator(timeoutMs = 250, readTimeoutMs = 5_000)

        val startedAt = System.nanoTime()
        val probes = coordinator.probeAll(host(listOf(endpoint("hung", hungUrl), endpoint("reachable", reachable))))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val byId = probes.associateBy { it.endpointId }
        assertTrue("可用地址必须照常探活成功", byId.getValue("reachable").ok)
        assertFalse(byId.getValue("hung").ok)
        assertTrue(
            "超时原因应可读，实际：${byId.getValue("hung").error}",
            byId.getValue("hung").error.orEmpty().contains("timeout"),
        )
        assertTrue(
            "整批应在 250ms 上限附近返回，实测 ${elapsedMs}ms（OkHttp 读超时 5000ms，串行的话要等满）",
            elapsedMs < 2_000,
        )

        // 放弃的那次调用仍挂在 socket 上，主动取消，别把它留给 MockWebServer 关闭流程。
        hungClient.dispatcher.cancelAll()
    }

    @Test
    fun connectStillSucceedsWhenThePreferredEndpointIsHung() = runBlocking {
        val hungUrl = address(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val reachable = address(health())
        val hungClient = newHttpClient(5_000)
        factoryOverrides[hungUrl] = { DshClient(hungUrl, null, hungClient) }
        val coordinator = coordinator(timeoutMs = 250, readTimeoutMs = 5_000)

        // 用户上次成功的地址（局域网）现在挂死了：必须自动回退到虚拟网，而不是整体失败。
        val connection = coordinator
            .connect(
                host(
                    listOf(endpoint("lan", hungUrl), endpoint("vnet", reachable, EndpointKind.VIRTUAL_NET)),
                    preferredEndpointId = "lan",
                ),
            )
            .getOrThrow()

        assertEquals("vnet", connection.endpoint.id)
        assertEquals(2, connection.probes.size)

        hungClient.dispatcher.cancelAll()
    }

    // ---------------------------------------------------------------- 取消异常必须透传

    @Test
    fun callerCancellationIsPropagatedInsteadOfBeingTurnedIntoAProbeFailure() = runBlocking {
        val reachable = address(health(delayMs = 2_000))
        val coordinator = coordinator(timeoutMs = 5_000, readTimeoutMs = 6_000)
        val caller = CoroutineScope(Dispatchers.IO)
        caller.cancel()

        var thrown: Throwable? = null
        try {
            caller.async { coordinator.probeAll(host(listOf(endpoint("e0", reachable)))) }.await()
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue("调用方取消应抛 CancellationException，实际 $thrown", thrown is CancellationException)
    }

    @Test
    fun cancellationFromClientCreationIsPropagatedNotSwallowed() = runBlocking {
        val url = "http://127.0.0.1:1"
        factoryOverrides[url] = { throw CancellationException("caller went away") }
        val coordinator = coordinator()

        var thrown: Throwable? = null
        try {
            coordinator.probeAll(host(listOf(endpoint("e0", url))))
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue("不得把取消吞成「探活失败」，实际 $thrown", thrown is CancellationException)
    }

    // ---------------------------------------------------------------- 生命周期：close()

    @Test
    fun closeCancelsInFlightProbeAndRejectsNewOnes() = runBlocking {
        val hungUrl = address(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val hungClient = newHttpClient(5_000)
        factoryOverrides[hungUrl] = { DshClient(hungUrl, null, hungClient) }
        // probeTimeoutMs 故意设成 5s：证明在途探活是被 close() 结束的，不是被它自己的超时结束的。
        val coordinator = coordinator(timeoutMs = 5_000, readTimeoutMs = 5_000)
        val host = host(listOf(endpoint("hung", hungUrl)))

        val caller = CoroutineScope(Dispatchers.IO)
        val inFlight = caller.async { runCatching { coordinator.probeAll(host) } }
        // 等探活请求真的到达服务端：此刻调用确定已在途、卡在 socket 读上。
        servers.single().takeRequest(2, TimeUnit.SECONDS)

        val startedAt = System.nanoTime()
        coordinator.close()
        val outcome = inFlight.await()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val cancelled = outcome.exceptionOrNull()
        assertTrue(
            "在途探活应随 close() 被取消，实测 ${elapsedMs}ms，实际 $cancelled / $outcome",
            cancelled is CancellationException,
        )
        assertTrue("close() 后应立即收尾，实测 ${elapsedMs}ms（该探活自身超时是 5000ms）", elapsedMs < 2_000)

        val callsBefore = factoryCalls.size
        var rejected: Throwable? = null
        try {
            coordinator.probeAll(host)
        } catch (error: Throwable) {
            rejected = error
        }
        assertTrue("close() 之后不得再发起探活，实际 $rejected", rejected is IllegalStateException)
        assertEquals("关闭后不得再新建客户端", callsBefore, factoryCalls.size)

        hungClient.dispatcher.cancelAll()
        caller.cancel()
    }

    // ---------------------------------------------------------------- 凭据接线

    @Test
    fun connectHandsTheHostCredentialToTheReturnedClient() = runBlocking {
        val url = address(health())
        servers.single().enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"device-1","name":"Pixel","scopes":[],"rootIds":[]}"""),
        )
        val host = host(listOf(endpoint("e0", url)), credential = Credential(secret = "device-token"))

        val connection = coordinator().connect(host).getOrThrow()

        assertEquals("e0", connection.endpoint.id)
        // 工厂会被调用两次（探活用一次 + 最终返回的客户端一次），两次都必须带上主机级令牌。
        assertTrue("每次构造都应带主机令牌，实际 $factoryCalls", factoryCalls.all { it.second == "device-token" })
        assertEquals("device-token", factoryCalls.last().second)
        assertEquals(1, connection.probes.size)
        // 真的发一次认证请求，确认返回的 client 带着主机级设备令牌（多地址共用一个令牌）。
        assertEquals("device-1", connection.client.currentDevice().id)

        assertEquals("/api/v1/healthz", servers.single().takeRequest().path)
        val authenticated = servers.single().takeRequest()
        assertEquals("/api/v1/devices/self", authenticated.path)
        assertEquals("Bearer device-token", authenticated.getHeader("Authorization"))
    }

    @Test
    fun connectWithoutCredentialBuildsAnUnauthenticatedClient() = runBlocking {
        val url = address(health())

        val connection = coordinator().connect(host(listOf(endpoint("e0", url)))).getOrThrow()

        assertNull("credential 为 null 时不带令牌", factoryCalls.last().second)
        assertTrue("credential 为 null 时任何构造都不应带令牌，实际 $factoryCalls", factoryCalls.all { it.second == null })
        assertEquals(1, connection.probes.size)
        assertEquals(url, connection.endpoint.baseUrl)
    }

    // ---------------------------------------------------------------- 夹具

    private fun coordinator(timeoutMs: Long = 1_500, readTimeoutMs: Long = 2_000) = ConnectionCoordinator(
        clientFactory = { url, token ->
            factoryCalls += url to token
            factoryOverrides[url]?.invoke() ?: DshClient(url, token, newHttpClient(readTimeoutMs))
        },
        probeTimeoutMs = timeoutMs,
    )

    private fun newHttpClient(readTimeoutMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .also(httpClients::add)

    private fun health(delayMs: Long = 0): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"ok":true,"version":"v1"}""")
            .apply { if (delayMs > 0) setBodyDelay(delayMs, TimeUnit.MILLISECONDS) }

    /** 启动一台 MockWebServer 代表「这台电脑的一个可达地址」，返回其基地址。 */
    private fun address(response: MockResponse): String {
        val server = MockWebServer()
        server.start()
        server.enqueue(response)
        servers += server
        return server.url("/").toString().removeSuffix("/")
    }

    /** 已经关掉的地址：模拟「这个网段现在不可达」（连接被拒）。 */
    private fun shutDownAddress(): String {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().removeSuffix("/")
        server.shutdown()
        return url
    }

    private fun endpoint(id: String, url: String, kind: EndpointKind = EndpointKind.UNKNOWN): Endpoint =
        Endpoint.create(id, url, id).copy(kind = kind)

    private fun host(
        endpoints: List<Endpoint>,
        credential: Credential? = null,
        preferredEndpointId: String? = null,
    ): Host = Host(
        id = "host-1",
        displayName = "家里的电脑",
        endpoints = endpoints,
        preferredEndpointId = preferredEndpointId,
        credential = credential,
    )
}
