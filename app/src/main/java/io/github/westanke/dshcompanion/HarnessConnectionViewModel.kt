package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.data.DeviceView
import io.github.westanke.dshcompanion.data.DirectoryPage
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.DshConnectionException
import io.github.westanke.dshcompanion.data.FileEntry
import io.github.westanke.dshcompanion.data.handoffPathOf
import io.github.westanke.dshcompanion.data.LiveChatState
import io.github.westanke.dshcompanion.data.RootView
import io.github.westanke.dshcompanion.data.AgentPreset
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.PendingApproval
import io.github.westanke.dshcompanion.data.PendingQuestionSet
import io.github.westanke.dshcompanion.data.PluginInventory
import io.github.westanke.dshcompanion.data.ProviderModel
import io.github.westanke.dshcompanion.data.ProviderPatch
import io.github.westanke.dshcompanion.data.ProviderSettings
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.ResolvedPath
import io.github.westanke.dshcompanion.data.SessionModels
import io.github.westanke.dshcompanion.data.ActiveConnection
import io.github.westanke.dshcompanion.data.AppPreferences
import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.ComposerSendPolicy
import io.github.westanke.dshcompanion.data.ConnectionCoordinator
import io.github.westanke.dshcompanion.data.ConnectionShare
import io.github.westanke.dshcompanion.data.Credential
import io.github.westanke.dshcompanion.data.CredentialKind
import io.github.westanke.dshcompanion.data.Endpoint
import io.github.westanke.dshcompanion.data.EndpointKind
import io.github.westanke.dshcompanion.data.EndpointProbe
import io.github.westanke.dshcompanion.data.Host
import io.github.westanke.dshcompanion.data.HostStore
import io.github.westanke.dshcompanion.data.KeystoreSecretBox
import io.github.westanke.dshcompanion.data.LegacyConnectionMigration
import io.github.westanke.dshcompanion.data.SharedPreferencesKeyValueStore
import io.github.westanke.dshcompanion.data.TrashEntry
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import io.github.westanke.dshcompanion.data.chatDelta
import io.github.westanke.dshcompanion.data.chatTurnFailure
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.binaryPreviewByteLimit
import io.github.westanke.dshcompanion.data.previewKind
import io.github.westanke.dshcompanion.data.previewKindOf
import io.github.westanke.dshcompanion.data.shouldRecheckActiveConnection
import io.github.westanke.dshcompanion.debug.DiagLog
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 「连接」域：多主机 / 多地址的配对、导入、探活选路与断开。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出。这些函数原本是 ViewModel 的成员，现在改成
 * **[HarnessViewModel] 的扩展函数** —— 调用点写法完全不变，只是实现搬到了按域划分的文件里。
 *
 * 三条设计约束是这块的骨架，改之前先读懂：
 *
 * 1. **一个主机可以有多个地址**（局域网 / 虚拟网 / 公网），连接时并发探活全部地址并自动选路。
 *    光有 `client` 回答不了「我现在连的是哪台电脑的哪个地址」—— 那正是诊断面板要的。
 * 2. **断开 ≠ 删除**。旧实现把两者绑成一个动作，于是网络抖一下、手点错一次，
 *    用户就得重新配对 —— 而配对又要求电脑在身边。现在断开只断连；删主机是独立的 `removeHost`。
 * 3. **连不上只保留未连接状态**，主机记录（含令牌）不动，由用户决定重试还是删除。
 */
internal fun HarnessViewModel.setEndpointDraft(value: String) = update { copy(endpointDraft = value, healthOk = false, error = null) }

internal fun HarnessViewModel.testConnection(endpoint: String) = launchOperation {
    val normalized = DshClient.normalizeEndpoint(endpoint)
    DshClient(normalized).health()
    update { copy(endpointDraft = normalized.removeSuffix("/api/v1"), healthOk = true) }
}

/**
 * 用一次性配对码把一台电脑加进来并连接。
 *
 * 配对码适合「电脑就在手边」的场合（同一局域网，或你正坐在电脑前）。
 * 人已经出门在外、看不到电脑屏幕时请改用 [importConfiguration] ——
 * 旧版只有配对码这一条路，这正是用户放弃它的直接原因。
 */
internal fun HarnessViewModel.pair(endpoint: String, code: String, deviceName: String) = launchOperation {
    require(code.isNotBlank()) { "Pairing code is required." }
    require(deviceName.isNotBlank()) { "Device name is required." }
    val unauthenticated = DshClient(endpoint)
    unauthenticated.health()
    val pairing = unauthenticated.pair(code, deviceName)
    val host = hostFromPairing(unauthenticated.endpoint, pairing.token, pairing.device, deviceName)
    hostStore.upsert(host)
    update { copy(hosts = hostStore.loadHosts(), selectedHostId = host.id) }
    connectHostInternal(host, pairing.device)
}

/**
 * 导入一段配置文本（形如 `DSH1:...`），把一台电脑加进来并连接。
 *
 * 这是取代扫码的主路径：配置文本可以在电脑上生成后发给自己（微信/邮件），
 * 到了外面再粘贴。全程不需要电脑在旁边，也不需要摄像头。
 */
internal fun HarnessViewModel.importConfiguration(text: String) = launchOperation {
    val payload = ConnectionShare.decode(text)
        ?: throw IllegalArgumentException("CONFIG_INVALID")
    val host = ConnectionShare.buildHost(payload)
    hostStore.upsert(host)
    update { copy(hosts = hostStore.loadHosts(), selectedHostId = host.id) }
    connectHostInternal(host)
}

/** 连接已保存的某台电脑：并发探活其全部地址并自动选路。 */
internal fun HarnessViewModel.connectHost(hostId: String) = launchOperation {
    val host = hostStore.host(hostId) ?: return@launchOperation
    update { copy(selectedHostId = hostId) }
    connectHostInternal(host)
}

/** 给某台电脑追加一个可达地址（例如新增虚拟网地址）。 */
internal fun HarnessViewModel.addEndpoint(hostId: String, label: String, baseUrl: String) = launchOperation {
    val host = hostStore.host(hostId) ?: return@launchOperation
    val normalized = DshClient.normalizeEndpoint(baseUrl).removeSuffix("/api/v1")
    if (host.endpoints.any { it.baseUrl == normalized }) return@launchOperation
    val endpoint = Endpoint.create(label = label, baseUrl = normalized, id = "ep-${nextId()}")
    hostStore.upsert(
        host.copy(
            endpoints = host.endpoints + endpoint,
            updatedAt = System.currentTimeMillis(),
        ),
    )
    update { copy(hosts = hostStore.loadHosts()) }
}

/** 删除某台电脑的一个地址。保留最后一个地址的行为由界面负责拦截。 */
internal fun HarnessViewModel.removeEndpoint(hostId: String, endpointId: String) = launchOperation {
    val host = hostStore.host(hostId) ?: return@launchOperation
    if (host.endpoints.size <= 1) throw IllegalArgumentException("LAST_ENDPOINT")
    val endpoints = host.endpoints.filterNot { it.id == endpointId }
    hostStore.upsert(
        host.copy(
            endpoints = endpoints,
            preferredEndpointId = host.preferredEndpointId?.takeIf { id -> endpoints.any { it.id == id } },
            updatedAt = System.currentTimeMillis(),
        ),
    )
    update { copy(hosts = hostStore.loadHosts()) }
}

/** 重命名一台电脑。 */
internal fun HarnessViewModel.renameHost(hostId: String, displayName: String) = launchOperation {
    val host = hostStore.host(hostId) ?: return@launchOperation
    hostStore.upsert(host.copy(displayName = displayName.trim(), updatedAt = System.currentTimeMillis()))
    update { copy(hosts = hostStore.loadHosts()) }
}

/**
 * 删除一台电脑（含其凭据）。
 *
 * 与 [disconnect] 分开：断开只是断连，删除才是抹掉令牌。
 */
internal fun HarnessViewModel.removeHost(hostId: String) {
    if (mutableState.value.activeHostId == hostId) disconnect()
    hostStore.remove(hostId)
    update { copy(hosts = hostStore.loadHosts(), selectedHostId = selectedHostId?.takeIf { it != hostId }) }
}

internal fun HarnessViewModel.selectHost(hostId: String?) = update { copy(selectedHostId = hostId) }

/** 把当前连接状态落回主机记录：探活结果 + 实际选中的地址。 */
internal suspend fun HarnessViewModel.connectHostInternal(host: Host, device: DeviceView? = null) {
    // 连到另一台电脑是彻底的上下文切换：上一台的响应一条都不该再写进来。
    requestGate.invalidate()
    update { copy(connecting = true, endpointProbes = emptyList(), error = null) }
    val connection = coordinator.connect(host).getOrElse { failure ->
        val probes = (failure as? DshConnectionException)?.probes.orEmpty()
        if (probes.isNotEmpty()) hostStore.upsert(applyProbes(host, probes))
        update {
            copy(
                connecting = false,
                // 冷启动的自动重连失败走的正是这条路，而它是**正常返回**（不抛异常），
                // 所以 restoreConnection 的 onFailure 兜不住 —— 不在这里复位，
                // restoring 会一直停在 true，「恢复中」从此变成一句永久的谎话。
                restoring = false,
                hosts = hostStore.loadHosts(),
                endpointProbes = probes,
                error = messageOf(failure),
            )
        }
        return
    }
    // 网络（currentDevice 与 loadSnapshot 的一串 REST 调用）和磁盘落盘
    // 必须落在 IO 线程：restoreConnection 从主线程协程直接调进来，这里不切换，
    // 冷启动自动重连第一发就是 NetworkOnMainThreadException（真机启动必现）。
    // 在被调函数内部切换，比逐个约束调用方更稳 —— 手动连接走 launchOperation
    // 已是 IO，重复 withContext 只是无害空转。
    val (recorded, snapshot) = withContext(Dispatchers.IO) {
        val recorded = applyProbes(connection.host, connection.probes).withPreferred(connection.endpoint.id)
        hostStore.upsert(recorded)
        val resolved = device ?: connection.client.currentDevice()
        recorded to loadSnapshot(connection.client, resolved)
    }
    active = connection
    update {
        snapshot.copy(
            restoring = false,
            // 这里**不能**置 busy=true。这条路径有两个调用方，语义不同：
            //   · 手动连接（connectHost/pair/importConfiguration）走 launchOperation，
            //     它自己会置 busy 并在结束时清掉；
            //   · 冷启动自动重连（restoreConnection）直达这里，**没有** launchOperation 兜底 ——
            //     置 true 就再也没人清，于是连接页的按钮一律灰掉（真机 2026-10-20 复现：
            //     断网被复核纠正后，按钮显示「连接」但点不动）。
            // 后台正在跑的那几个请求由 App 顶栏自己的转圈表达，不该借用这个字段。
            busy = false,
            connecting = false,
            connected = true,
            healthOk = true,
            hosts = hostStore.loadHosts(),
            activeHostId = recorded.id,
            activeEndpointId = connection.endpoint.id,
            endpointProbes = connection.probes,
        )
    }
    // 权限档位清单是**进程级**的（内核 `permissionPresets.catalog()`），连上时取一次就够。
    //
    // 取不到不算连接失败：老 bridge（< 2.4.2）没有这条路由，返回 404。
    // 那时权限入口退化成「只显示当前档位、不能切换」，而这远好过整个入口消失。
    // 所以这里**不**走 launchOperation（它会把异常写进全局 error → 弹 snackbar，
    // 等于每次连接老 bridge 都弹一句看不懂的错），而是自己吞掉。
    val catalog = withContext(Dispatchers.IO) {
        runCatching { connection.client.permissionPresets() }.getOrNull()
    }
    update { copy(permissionCatalog = catalog ?: permissionCatalog) }
    connectEvents(connection.client)
}

internal fun HarnessViewModel.applyProbes(host: Host, probes: List<EndpointProbe>): Host =
    probes.fold(host) { acc, probe -> acc.withEndpointProbe(probe.endpointId, probe.latencyMs, probe.error) }

internal fun HarnessViewModel.hostFromPairing(
    endpoint: String,
    token: String,
    device: DeviceView,
    displayName: String,
): Host {
    val now = System.currentTimeMillis()
    val baseUrl = DshClient.normalizeEndpoint(endpoint).removeSuffix("/api/v1")
    val endpointId = "ep-paired"
    val existing = hostStore.loadHosts().firstOrNull { it.id == "host-${device.id}" }
    return Host(
        id = "host-${device.id}",
        displayName = displayName.trim().ifBlank { device.name },
        endpoints = listOf(Endpoint.create(label = EndpointKind.infer(baseUrl).name, baseUrl = baseUrl, id = endpointId)),
        preferredEndpointId = endpointId,
        credential = Credential(
            kind = CredentialKind.DEVICE_TOKEN,
            secret = token,
            deviceId = device.id,
            deviceName = device.name,
            scopes = device.scopes,
            issuedAt = existing?.credential?.issuedAt?.takeIf { it > 0L } ?: now,
        ),
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
    )
}

internal fun HarnessViewModel.nextId(): String = java.util.UUID.randomUUID().toString().take(8)

/**
 * 断开当前连接。
 *
 * 注意**不再清除已保存的主机**：旧实现把「断开」和「删除令牌」绑成一个动作，
 * 于是网络抖一下、手点错一次，用户就得重新配对 —— 而配对又要求电脑在身边。
 * 现在断开只断连；删主机是独立的 [removeHost]。
 */
internal fun HarnessViewModel.disconnect() {
    // 断开同样作废在飞请求：否则断线后回来的响应会往「已断开」的状态里填内容。
    requestGate.invalidate()
    eventSubscription?.close()
    eventSubscription = null
    // WS 传输也要收掉：它自带退避重连，留着会在「已断开」的状态里继续拨号。
    closeWsTransport()
    // 手动断开要清掉熄屏暂停标志：否则下次亮屏 resumeEvents 会把
    // 用户刚刚主动断开的连接又接回去 —— 那是最容易让人以为「断开没用」的 bug。
    eventsPaused = false
    active = null
    mutableState.value = HarnessState(restoring = false, hosts = hostStore.loadHosts()).withPreferences()
}

/**
 * 回前台时复核「当前这条连接是不是**此刻**还通」。
 *
 * ## 为什么要有这一步（2026-10-20 真机验证）
 *
 * [`HarnessState.connected`] 只在**连接成功那一刻**置 true（`connectHostInternal` 与
 * `loadSnapshot` 两处），而全仓**没有任何代码把它置回 false** —— 它是「曾经连上过」，
 * 不是「此刻还连着」。于是这三种日常情形里连接页会说假话：
 *
 * | 情形 | 界面 | 用户点下去的实际后果 |
 * |---|---|---|
 * | 连着的时候断网 / 换网 | 仍显示「已连接」+「进入应用」 | 放行了又立刻被门闩弹回，表现是「点了没反应」 |
 * | 电脑休眠或服务端重启 | 同上 | 同上 |
 * | 手机锁屏几小时后回来 | 同上 | 同上 |
 *
 * 那个「点了没反应」的连带缺陷就在 `ConnectScreen` 的短路条件
 * （`if (!(state.connected && hostId == state.activeHostId)) viewModel.connectHost(hostId)`）：
 * 它看到历史值 true，判定「这台已经连上了，直接进去」，于是既不重连也不探活。
 * **根因是同一个**：状态位记的是历史，不是现状。本函数就是把它按现状纠正回来。
 *
 * ## 为什么只在**连接页**复核
 *
 * 进了 App 之后有事件流盯着链路（`eventsConnected`，断线即 false 并显示「已断开」），
 * 再发健康检查是重复的。而连接页是**每一台设备的必经入口**，用户回来看的就是
 * 「现在通不通」—— 这一次探活的代价完全值当。
 *
 * 判据因此是「门闩还没放行 **或** 状态已经是未连接」：前者是还停在连接页，
 * 后者是上一次复核已经把它标成断了（再探一次没意义，等用户点「连接」即可）。
 *
 * ## 为什么不走 [launchOperation]
 *
 * 那个公共出口失败会写 `error`（App 层弹 snackbar）并置 `busy`（按钮变灰）。
 * 而这里只是「回前台顺手确认一下」，网络抖一下本来就常见 —— 每次回前台都弹一句红字、
 * 还把按钮压灰，比不做这件事更吵。所以自己起协程、自己吞错，只留一条诊断日志。
 */
internal fun HarnessViewModel.recheckConnectionIfOnConnectScreen() {
    val connection = active
    // 判据见 `data/ConnectionLiveness.kt`（纯函数，有单测）：这里不再就地猜一遍。
    val shouldCheck = shouldRecheckActiveConnection(
        connectGatePassed = connectGatePassed.value,
        connected = mutableState.value.connected,
        hasActiveConnection = connection != null,
        connecting = mutableState.value.connecting,
    )
    if (!shouldCheck || connection == null) return

    viewModelScope.launch {
        val reachable = withContext(Dispatchers.IO) {
            runCatching { connection.client.health() }.isSuccess
        }
        if (reachable) return@launch

        DiagLog.w(DiagLog.Cat.CONNECT, "回前台复核：这条连接已经不通，把「已连接」纠正为未连接")
        // 失效的客户端不能继续留着：后续请求会打到一条已经断的链路上，
        // 而且 `activeConnection()` 是「这台是不是还握着一条活连接」的唯一判据
        // （见 `data/ConnectionLiveness.shouldReconnectOnTap`）—— 留着它，
        // 用户点「重新连接」会被误判成「已连上，直接进去」，又变回那个哑巴按钮。
        active = null
        update {
            copy(
                // connected=false + **保留** activeHostId：三态里的 DISCONNECTED 判据是
                // `hostId == activeHostId && !connected`（`data/ConnectionLiveness.hostCardState`）。
                // 之前把 activeHostId 一并清空，卡片就落进了「从来没连过」那一态，
                // 屏幕上没有任何状态点 —— 与修复前一样看不出「这台断过」。
                connected = false,
                healthOk = false,
            )
        }
    }
}

/**
 * 启动时恢复连接：读取已保存的主机（必要时先迁移旧版数据），再自动尝试最近用过的那台。
 *
 * 与旧实现的一个关键差别：旧版在恢复失败时执行 `store.clear()`，把令牌一并删掉。
 * 那意味着「此刻网络不通」会被误判成「凭据失效」—— 用户回到家才发现要重新配对，
 * 而重新配对又要求他真的在电脑前。现在连不上只保留未连接状态，主机记录不动，
 * 由用户决定重试还是删除。
 */
internal fun HarnessViewModel.restoreConnection() {
    viewModelScope.launch {
        val hosts = withContext(Dispatchers.IO) { loadOrMigrateHosts() }
        if (hosts.isEmpty()) {
            update { copy(restoring = false) }
            return@launch
        }
        // updatedAt 会在每次探活后刷新，所以「最近活动过」近似等于「上次用过的」。
        val target = hosts.maxByOrNull { it.updatedAt } ?: hosts.first()
        update {
            copy(
                hosts = hosts,
                selectedHostId = target.id,
                endpointDraft = target.endpoints.firstOrNull()?.baseUrl.orEmpty(),
                restoring = true,
            )
        }
        runCatching { connectHostInternal(target) }
            .onFailure { update { copy(restoring = false, connecting = false, error = messageOf(it)) } }
    }
}

/** 读取主机列表；为空时尝试把旧版单连接数据迁进来。迁移是幂等的。 */
internal fun HarnessViewModel.loadOrMigrateHosts(): List<Host> {
    val existing = hostStore.loadHosts()
    if (existing.isNotEmpty()) return existing
    return runCatching {
        LegacyConnectionMigration.migrate(
            storage = keyValueStore,
            legacySecretBox = legacySecretBox,
            hostStore = hostStore,
        )
        hostStore.loadHosts()
    }.getOrDefault(emptyList())
}
