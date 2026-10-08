package io.github.hakunm.deepseekharness

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hakunm.deepseekharness.data.ChatHistory
import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.data.ChatWorkspace
import io.github.hakunm.deepseekharness.data.CommandDescriptor
import io.github.hakunm.deepseekharness.data.CustomProviderCreate
import io.github.hakunm.deepseekharness.data.DeviceView
import io.github.hakunm.deepseekharness.data.DirectoryPage
import io.github.hakunm.deepseekharness.data.DshApiException
import io.github.hakunm.deepseekharness.data.DshClient
import io.github.hakunm.deepseekharness.data.DshConnectionException
import io.github.hakunm.deepseekharness.data.FileEntry
import io.github.hakunm.deepseekharness.data.LiveChatState
import io.github.hakunm.deepseekharness.data.RootView
import io.github.hakunm.deepseekharness.data.AgentPreset
import io.github.hakunm.deepseekharness.data.ModelSelection
import io.github.hakunm.deepseekharness.data.PendingApproval
import io.github.hakunm.deepseekharness.data.ProviderModel
import io.github.hakunm.deepseekharness.data.ProviderPatch
import io.github.hakunm.deepseekharness.data.ProviderSettings
import io.github.hakunm.deepseekharness.data.SessionModels
import io.github.hakunm.deepseekharness.data.ActiveConnection
import io.github.hakunm.deepseekharness.data.ConnectionCoordinator
import io.github.hakunm.deepseekharness.data.ConnectionShare
import io.github.hakunm.deepseekharness.data.Credential
import io.github.hakunm.deepseekharness.data.CredentialKind
import io.github.hakunm.deepseekharness.data.Endpoint
import io.github.hakunm.deepseekharness.data.EndpointKind
import io.github.hakunm.deepseekharness.data.EndpointProbe
import io.github.hakunm.deepseekharness.data.Host
import io.github.hakunm.deepseekharness.data.HostStore
import io.github.hakunm.deepseekharness.data.KeystoreSecretBox
import io.github.hakunm.deepseekharness.data.LegacyConnectionMigration
import io.github.hakunm.deepseekharness.data.SharedPreferencesKeyValueStore
import io.github.hakunm.deepseekharness.data.TrashEntry
import io.github.hakunm.deepseekharness.data.WorkspaceEvent
import io.github.hakunm.deepseekharness.data.chatDelta
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

data class OpenDocument(
    val entry: FileEntry,
    val text: String? = null,
    val etag: String = "",
    val hasBom: Boolean = false,
    val lineEnding: String = "\n",
    val contentType: String? = null,
)

sealed interface ApprovalUiState {
    data object None : ApprovalUiState
    data object Loading : ApprovalUiState
    data class Pending(val items: List<PendingApproval>) : ApprovalUiState
    data class Deciding(val item: PendingApproval, val items: List<PendingApproval>) : ApprovalUiState
}

data class HarnessState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val endpointDraft: String = "http://192.168.1.20:3090",
    val healthOk: Boolean = false,
    val connected: Boolean = false,
    val endpoint: String = "",
    // ——— 多主机 / 多地址连接层（新） ———
    /** 已保存的全部电脑。 */
    val hosts: List<Host> = emptyList(),
    /** 当前连上的电脑 id。 */
    val activeHostId: String? = null,
    /** 当前实际使用的地址 id（多地址里的胜出者）。 */
    val activeEndpointId: String? = null,
    /** 最近一次探活的逐地址结果，用于诊断面板。 */
    val endpointProbes: List<EndpointProbe> = emptyList(),
    /** 正在并发探活/建连。 */
    val connecting: Boolean = false,
    /** 界面上被选中查看/编辑的电脑（不一定是当前连上的那台）。 */
    val selectedHostId: String? = null,
    val device: DeviceView? = null,
    val roots: List<RootView> = emptyList(),
    val sessions: List<ChatSession> = emptyList(),
    val chatWorkspaces: List<ChatWorkspace> = emptyList(),
    val agentPresets: List<AgentPreset> = emptyList(),
    val workspacePickerRootId: String? = null,
    val workspacePickerPath: String = "",
    val workspacePickerDirectory: DirectoryPage? = null,
    val sessionModels: SessionModels? = null,
    val commands: List<CommandDescriptor> = emptyList(),
    val providerSettings: ProviderSettings? = null,
    val discoveredModels: List<ProviderModel> = emptyList(),
    val selectedSessionId: String? = null,
    val history: ChatHistory? = null,
    val liveChat: LiveChatState? = null,
    val approvalState: ApprovalUiState = ApprovalUiState.None,
    val eventsConnected: Boolean = false,
    val selectedRootId: String? = null,
    val currentPath: String = "",
    val directory: DirectoryPage? = null,
    val document: OpenDocument? = null,
    val trash: List<TrashEntry> = emptyList(),
    val error: String? = null,
)

class HarnessViewModel(application: Application) : AndroidViewModel(application) {
    private val keyValueStore = SharedPreferencesKeyValueStore(application)
    private val hostStore = HostStore(keyValueStore, KeystoreSecretBox())
    private val coordinator = ConnectionCoordinator()
    /** 旧版令牌用的是另一个 Keystore 别名，迁移时必须用它解密。 */
    private val legacySecretBox = KeystoreSecretBox(KeystoreSecretBox.LEGACY_DEVICE_TOKEN_ALIAS)
    private val mutableState = MutableStateFlow(HarnessState())
    val state: StateFlow<HarnessState> = mutableState.asStateFlow()

    /**
     * 当前活跃连接。null 表示未连接。
     *
     * 旧实现是一个可变的 `client` 字段；改成「活跃连接」是因为现在一个主机可能有多个地址，
     * 光有 client 无法回答「我现在连的是哪台电脑的哪个地址」—— 而这正是诊断面板和
     * 后续自动切换都需要的。
     */
    private var active: ActiveConnection? = null
    private val client: DshClient? get() = active?.client
    private var eventSubscription: Closeable? = null
    private var chatRefreshJob: Job? = null
    @Volatile private var chatRefreshPending = false
    @Volatile private var chatSessionRefreshPending = false
    @Volatile private var liveFinalizeTarget: LiveKey? = null

    init {
        restoreConnection()
    }

    fun setEndpointDraft(value: String) = update { copy(endpointDraft = value, healthOk = false, error = null) }

    fun testConnection(endpoint: String) = launchOperation {
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
    fun pair(endpoint: String, code: String, deviceName: String) = launchOperation {
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
    fun importConfiguration(text: String) = launchOperation {
        val payload = ConnectionShare.decode(text)
            ?: throw IllegalArgumentException("CONFIG_INVALID")
        val host = ConnectionShare.buildHost(payload)
        hostStore.upsert(host)
        update { copy(hosts = hostStore.loadHosts(), selectedHostId = host.id) }
        connectHostInternal(host)
    }

    /** 连接已保存的某台电脑：并发探活其全部地址并自动选路。 */
    fun connectHost(hostId: String) = launchOperation {
        val host = hostStore.host(hostId) ?: return@launchOperation
        update { copy(selectedHostId = hostId) }
        connectHostInternal(host)
    }

    /** 给某台电脑追加一个可达地址（例如新增虚拟网地址）。 */
    fun addEndpoint(hostId: String, label: String, baseUrl: String) = launchOperation {
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
    fun removeEndpoint(hostId: String, endpointId: String) = launchOperation {
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
    fun renameHost(hostId: String, displayName: String) = launchOperation {
        val host = hostStore.host(hostId) ?: return@launchOperation
        hostStore.upsert(host.copy(displayName = displayName.trim(), updatedAt = System.currentTimeMillis()))
        update { copy(hosts = hostStore.loadHosts()) }
    }

    /**
     * 删除一台电脑（含其凭据）。
     *
     * 与 [disconnect] 分开：断开只是断连，删除才是抹掉令牌。
     */
    fun removeHost(hostId: String) {
        if (mutableState.value.activeHostId == hostId) disconnect()
        hostStore.remove(hostId)
        update { copy(hosts = hostStore.loadHosts(), selectedHostId = selectedHostId?.takeIf { it != hostId }) }
    }

    fun selectHost(hostId: String?) = update { copy(selectedHostId = hostId) }

    /** 把当前连接状态落回主机记录：探活结果 + 实际选中的地址。 */
    private suspend fun connectHostInternal(host: Host, device: DeviceView? = null) {
        update { copy(connecting = true, endpointProbes = emptyList(), error = null) }
        val connection = coordinator.connect(host).getOrElse { failure ->
            val probes = (failure as? DshConnectionException)?.probes.orEmpty()
            if (probes.isNotEmpty()) hostStore.upsert(applyProbes(host, probes))
            update {
                copy(
                    connecting = false,
                    hosts = hostStore.loadHosts(),
                    endpointProbes = probes,
                    error = messageOf(failure),
                )
            }
            return
        }
        val recorded = applyProbes(connection.host, connection.probes).withPreferred(connection.endpoint.id)
        hostStore.upsert(recorded)
        active = connection
        val resolvedDevice = device ?: connection.client.currentDevice()
        val snapshot = loadSnapshot(connection.client, resolvedDevice)
        update {
            snapshot.copy(
                restoring = false,
                busy = true,
                connecting = false,
                connected = true,
                healthOk = true,
                hosts = hostStore.loadHosts(),
                activeHostId = recorded.id,
                activeEndpointId = connection.endpoint.id,
                endpointProbes = connection.probes,
            )
        }
        connectEvents(connection.client)
    }

    private fun applyProbes(host: Host, probes: List<EndpointProbe>): Host =
        probes.fold(host) { acc, probe -> acc.withEndpointProbe(probe.endpointId, probe.latencyMs, probe.error) }

    private fun hostFromPairing(
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

    private fun nextId(): String = java.util.UUID.randomUUID().toString().take(8)

    /**
     * 断开当前连接。
     *
     * 注意**不再清除已保存的主机**：旧实现把「断开」和「删除令牌」绑成一个动作，
     * 于是网络抖一下、手点错一次，用户就得重新配对 —— 而配对又要求电脑在身边。
     * 现在断开只断连；删主机是独立的 [removeHost]。
     */
    fun disconnect() {
        eventSubscription?.close()
        eventSubscription = null
        active = null
        mutableState.value = HarnessState(restoring = false, hosts = hostStore.loadHosts())
    }

    fun clearError() = update { copy(error = null) }

    fun refreshDashboard() = withClient { api ->
        val device = api.currentDevice()
        val snapshot = loadSnapshot(api, device)
        update {
            snapshot.copy(
                restoring = false,
                busy = true,
                selectedSessionId = selectedSessionId?.takeIf { id -> snapshot.sessions.any { it.id == id } },
                selectedRootId = selectedRootId?.takeIf { id -> snapshot.roots.any { it.id == id } }
                    ?: snapshot.roots.firstOrNull()?.id,
            )
        }
    }

    fun selectSession(id: String?) {
        update {
            copy(
                selectedSessionId = id,
                history = null,
                liveChat = null,
                sessionModels = null,
                commands = emptyList(),
                approvalState = if (id == null) ApprovalUiState.None else ApprovalUiState.Loading,
            )
        }
        if (id != null) {
            refreshHistory()
            refreshSessionModels()
            refreshPendingApprovals()
            refreshCommands()
        }
    }

    fun refreshHistory() = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        val history = api.history(id)
        update { copy(history = history) }
    }

    fun loadOlderHistory() = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        val current = mutableState.value.history ?: return@withClient
        if (!current.hasMore) return@withClient
        val beforeSeq = current.events.minOfOrNull { it.event.seq } ?: return@withClient
        val older = api.history(id, beforeSeq)
        update {
            copy(history = current.copy(
                events = (older.events + current.events).distinctBy { it.event.seq },
                hasMore = older.hasMore,
                projections = current.projections ?: older.projections,
            ))
        }
    }

    fun createSession(workspaceId: String, preset: String?) = withClient { api ->
        val created = api.createSession(workspaceId, preset?.takeIf(String::isNotBlank))
        val sessions = api.sessions()
        val history = api.history(created.id)
        val models = api.sessionModels(created.id)
        val commands = api.sessionCommands(created.id)
        update {
            copy(
                sessions = sessions,
                selectedSessionId = created.id,
                history = history,
                sessionModels = models,
                commands = commands,
                approvalState = ApprovalUiState.None,
            )
        }
    }

    fun prepareWorkspacePicker() {
        val rootId = mutableState.value.workspacePickerRootId
            ?.takeIf { id -> mutableState.value.roots.any { it.id == id } }
            ?: mutableState.value.selectedRootId
            ?: mutableState.value.roots.firstOrNull()?.id
        update { copy(workspacePickerRootId = rootId, workspacePickerPath = "", workspacePickerDirectory = null) }
        if (rootId != null) loadWorkspacePickerDirectory("")
    }

    fun selectWorkspacePickerRoot(rootId: String) {
        update { copy(workspacePickerRootId = rootId, workspacePickerPath = "", workspacePickerDirectory = null) }
        loadWorkspacePickerDirectory("")
    }

    fun loadWorkspacePickerDirectory(path: String) = withClient { api ->
        val rootId = mutableState.value.workspacePickerRootId ?: return@withClient
        val normalized = path.trim('/')
        val page = api.entries(rootId, normalized)
        update { copy(workspacePickerPath = normalized, workspacePickerDirectory = page) }
    }

    fun createWorkspaceAndSession(rootId: String, path: String, preset: String?) = withClient { api ->
        val workspace = api.createChatWorkspace(rootId, path.trim('/'))
        val created = api.createSession(workspace.id, preset?.takeIf(String::isNotBlank))
        val sessions = api.sessions()
        val workspaces = api.chatWorkspaces()
        update {
            copy(
                chatWorkspaces = workspaces,
                sessions = sessions,
                selectedSessionId = created.id,
                history = api.history(created.id),
                sessionModels = api.sessionModels(created.id),
                commands = api.sessionCommands(created.id),
                approvalState = ApprovalUiState.None,
            )
        }
    }

    fun renameWorkspace(workspaceId: String, title: String) = withClient { api ->
        api.renameChatWorkspace(workspaceId, title)
        update { copy(chatWorkspaces = api.chatWorkspaces(), sessions = api.sessions()) }
    }

    fun deleteWorkspace(workspaceId: String) = withClient { api ->
        api.deleteChatWorkspace(workspaceId)
        update { copy(chatWorkspaces = api.chatWorkspaces(), sessions = api.sessions()) }
    }

    fun renameSession(sessionId: String, title: String) = withClient { api ->
        api.renameSession(sessionId, title)
        update { copy(sessions = api.sessions()) }
    }

    fun forkSession(sessionId: String) = withClient { api ->
        val forkedId = api.forkSession(sessionId)
        update {
            copy(
                sessions = api.sessions(),
                selectedSessionId = forkedId,
                history = api.history(forkedId),
                sessionModels = api.sessionModels(forkedId),
                commands = api.sessionCommands(forkedId),
                approvalState = ApprovalUiState.None,
                liveChat = null,
            )
        }
    }

    fun archiveSession(sessionId: String) = withClient { api ->
        api.archiveSession(sessionId)
        val sessions = api.sessions()
        update {
            if (selectedSessionId == sessionId) {
                copy(
                    sessions = sessions,
                    selectedSessionId = null,
                    history = null,
                    liveChat = null,
                    sessionModels = null,
                    commands = emptyList(),
                    approvalState = ApprovalUiState.None,
                )
            } else copy(sessions = sessions)
        }
    }

    fun sendMessage(text: String, steer: Boolean) = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        if (text.isBlank()) return@withClient
        val line = text.trimEnd()
        if (line.startsWith('/')) {
            val execution = api.executeCommand(id, line)
            if (execution.result.kind == "error") {
                throw DshApiException(409, "COMMAND_FAILED", execution.result.text ?: "Command failed.")
            }
            update { copy(history = api.history(id), commands = api.sessionCommands(id)) }
            return@withClient
        }
        api.sendMessage(id, text, steer)
        chatSessionRefreshPending = true
        scheduleChatRefresh()
    }

    fun refreshCommands() = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        update { copy(commands = api.sessionCommands(id)) }
    }

    fun selectPermissionPreset(preset: String) = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        val execution = api.executeCommand(id, "/permission $preset")
        if (execution.result.kind == "error") {
            throw DshApiException(409, "COMMAND_FAILED", execution.result.text ?: "Permission change failed.")
        }
        update { copy(history = api.history(id), commands = api.sessionCommands(id)) }
    }

    fun refreshSessionModels() = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        update { copy(sessionModels = api.sessionModels(id)) }
    }

    fun selectModel(selection: ModelSelection) = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        api.selectModel(id, selection)
        update { copy(sessionModels = api.sessionModels(id)) }
    }

    fun selectAgentPreset(agentPreset: String) = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        api.selectAgentPreset(id, agentPreset)
        val sessions = api.sessions()
        val models = api.sessionModels(id)
        val commands = api.sessionCommands(id)
        update { copy(sessions = sessions, sessionModels = models, commands = commands) }
    }

    fun refreshProviderSettings() = withClient { api ->
        if ("settings.read" !in mutableState.value.device?.scopes.orEmpty()) return@withClient
        update { copy(providerSettings = api.providerSettings()) }
    }

    fun updateProvider(providerId: String, patch: ProviderPatch) = withClient { api ->
        update { copy(providerSettings = api.updateProvider(providerId, patch), discoveredModels = emptyList()) }
    }

    fun createCustomProvider(provider: CustomProviderCreate) = withClient { api ->
        update { copy(providerSettings = api.createCustomProvider(provider), discoveredModels = emptyList()) }
    }

    fun discoverModels(providerId: String, baseURL: String?, protocol: String?, apiKey: String?) = withClient { api ->
        update { copy(discoveredModels = api.discoverModels(providerId, baseURL, protocol, apiKey)) }
    }

    fun clearDiscoveredModels() = update { copy(discoveredModels = emptyList()) }

    fun cancelRun() = withClient { api ->
        val id = mutableState.value.selectedSessionId ?: return@withClient
        api.cancel(id)
        chatSessionRefreshPending = true
        scheduleChatRefresh()
    }

    fun refreshPendingApprovals() {
        val api = client ?: return
        val id = mutableState.value.selectedSessionId ?: return
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { api.pendingApprovals(id) } }
            if (mutableState.value.selectedSessionId != id) return@launch
            result
                .onSuccess { approvals -> update { copy(approvalState = approvals.toApprovalState()) } }
                .onFailure { error ->
                    update {
                        copy(
                            approvalState = ApprovalUiState.None,
                            error = if (error is DshApiException && error.code == "RATE_LIMITED") null else messageOf(error),
                        )
                    }
                }
        }
    }

    fun decideApproval(approval: PendingApproval, allowOnce: Boolean) {
        val api = client ?: return
        val id = mutableState.value.selectedSessionId ?: return
        if (approval.sessionId != id) return
        val items = when (val current = mutableState.value.approvalState) {
            is ApprovalUiState.Pending -> current.items
            is ApprovalUiState.Deciding -> current.items
            else -> listOf(approval)
        }
        viewModelScope.launch {
            update { copy(approvalState = ApprovalUiState.Deciding(approval, items), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    api.decideApproval(id, approval.id, allowOnce)
                    api.pendingApprovals(id) to api.sessions()
                }
            }.onSuccess { (approvals, sessions) ->
                if (mutableState.value.selectedSessionId == id) {
                    update { copy(approvalState = approvals.toApprovalState(), sessions = sessions) }
                    chatSessionRefreshPending = true
                    scheduleChatRefresh()
                }
            }.onFailure { error ->
                if (mutableState.value.selectedSessionId == id) {
                    update { copy(approvalState = ApprovalUiState.Pending(items), error = messageOf(error)) }
                }
            }
        }
    }

    fun selectRoot(id: String) {
        update { copy(selectedRootId = id, currentPath = "", document = null) }
        loadDirectory("")
    }

    fun loadDirectory(path: String = mutableState.value.currentPath) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val normalized = path.trim('/')
        val page = api.entries(root, normalized)
        update { copy(currentPath = normalized, directory = page, document = null) }
    }

    fun loadNextDirectoryPage() = withClient { api ->
        val current = mutableState.value.directory ?: return@withClient
        val cursor = current.nextCursor ?: return@withClient
        val root = mutableState.value.selectedRootId ?: return@withClient
        val next = api.entries(root, current.path, cursor)
        update {
            copy(directory = current.copy(
                entries = (current.entries + next.entries).distinctBy { it.path },
                nextCursor = next.nextCursor,
            ))
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.kind == "directory") {
            loadDirectory(entry.path)
            return
        }
        withClient { api ->
            val root = mutableState.value.selectedRootId ?: return@withClient
            if (entry.size > MAX_EDIT_BYTES) {
                update { copy(document = OpenDocument(entry), error = "FILE_TOO_LARGE") }
                return@withClient
            }
            val content = api.readFile(root, entry.path)
            val decoded = decodeUtf8(content.bytes)
            update {
                copy(
                    document = OpenDocument(
                        entry = entry,
                        text = decoded?.text,
                        etag = content.etag,
                        hasBom = decoded?.hasBom == true,
                        lineEnding = decoded?.lineEnding ?: "\n",
                        contentType = content.contentType,
                    ),
                    error = if (decoded == null) "BINARY_FILE" else null,
                )
            }
        }
    }

    fun selectEntryForAction(entry: FileEntry) = update {
        copy(document = OpenDocument(entry))
    }

    fun updateDocument(text: String) = update {
        copy(document = document?.copy(text = text))
    }

    fun saveDocument() = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val document = mutableState.value.document ?: return@withClient
        val text = document.text ?: return@withClient
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val encoded = when (document.lineEnding) {
            "\r\n" -> normalized.replace("\n", "\r\n")
            "\r" -> normalized.replace("\n", "\r")
            else -> normalized
        }
            .toByteArray(Charsets.UTF_8)
        val bytes = if (document.hasBom) UTF8_BOM + encoded else encoded
        val etag = api.writeFile(root, document.entry.path, bytes, document.etag)
        update { copy(document = document.copy(etag = etag)) }
        val page = api.entries(root, mutableState.value.currentPath)
        update { copy(directory = page) }
    }

    fun createEntry(name: String, directory: Boolean) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val path = childPath(mutableState.value.currentPath, name)
        api.createEntry(root, path, directory)
        update { copy(directory = api.entries(root, mutableState.value.currentPath)) }
    }

    fun moveSelected(destination: String) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val document = mutableState.value.document ?: return@withClient
        api.moveEntry(root, document.entry.path, destination.trim('/'))
        update { copy(document = null, directory = api.entries(root, mutableState.value.currentPath)) }
    }

    fun trashSelected() = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val document = mutableState.value.document ?: return@withClient
        api.trashEntry(root, document.entry.path)
        update {
            copy(document = null, directory = api.entries(root, currentPath), trash = api.trash())
        }
    }

    fun upload(name: String, length: Long, openInput: () -> InputStream) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        api.writeFile(root, childPath(mutableState.value.currentPath, name), length, openInput)
        update { copy(directory = api.entries(root, mutableState.value.currentPath)) }
    }

    fun replaceSelected(length: Long, openInput: () -> InputStream) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val document = mutableState.value.document ?: return@withClient
        val etag = document.etag.ifBlank { api.readFile(root, document.entry.path).etag }
        api.writeFile(root, document.entry.path, length, openInput, etag)
        openEntry(document.entry.copy(size = length))
    }

    fun downloadSelected(openOutput: () -> OutputStream) = withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        val entry = mutableState.value.document?.entry ?: return@withClient
        api.downloadFile(root, entry.path, openOutput())
    }

    fun loadTrash() = withClient { api -> update { copy(trash = api.trash()) } }

    fun restoreTrash(id: String) = withClient { api ->
        api.restoreTrash(id)
        update { copy(trash = api.trash()) }
        loadDirectory()
    }

    /**
     * 启动时恢复连接：读取已保存的主机（必要时先迁移旧版数据），再自动尝试最近用过的那台。
     *
     * 与旧实现的一个关键差别：旧版在恢复失败时执行 `store.clear()`，把令牌一并删掉。
     * 那意味着「此刻网络不通」会被误判成「凭据失效」—— 用户回到家才发现要重新配对，
     * 而重新配对又要求他真的在电脑前。现在连不上只保留未连接状态，主机记录不动，
     * 由用户决定重试还是删除。
     */
    private fun restoreConnection() {
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
    private fun loadOrMigrateHosts(): List<Host> {
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

    private fun connectEvents(api: DshClient) {
        eventSubscription?.close()
        eventSubscription = api.events(
            listener = { event ->
                val delta = event.chatDelta()
                if (delta != null) {
                    if (mutableState.value.selectedSessionId == delta.sessionId) {
                        update { copy(liveChat = (liveChat ?: LiveChatState(delta.sessionId, delta.turn, delta.step)).append(delta)) }
                    }
                } else if (event.type.startsWith("chat.")) {
                    val sessionId = event.dataString("sessionId")
                    when (event.type) {
                        "chat.message.committed" -> {
                            if (sessionId != null && sessionId == mutableState.value.selectedSessionId) {
                                if (event.dataString("role") == "assistant") {
                                    val turn = event.dataInt("turn")
                                    val step = event.dataInt("step")
                                    if (turn != null && step != null) liveFinalizeTarget = LiveKey(sessionId, turn, step)
                                }
                                scheduleChatRefresh()
                            }
                        }
                        "chat.turn.start", "chat.turn.end", "chat.session.status" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                        }
                        "chat.agent-preset.selected" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                            if (sessionId == mutableState.value.selectedSessionId) refreshSessionModels()
                        }
                        "chat.approval.requested", "chat.approval.resolved" -> {
                            chatSessionRefreshPending = true
                            scheduleChatRefresh()
                            if (sessionId == mutableState.value.selectedSessionId) refreshPendingApprovals()
                        }
                        else -> if (sessionId == mutableState.value.selectedSessionId) scheduleChatRefresh()
                    }
                }
                if (event.type.startsWith("file.")) loadDirectory()
            },
            connection = { connected ->
                update { copy(eventsConnected = connected) }
                if (connected && mutableState.value.selectedSessionId != null) {
                    scheduleChatRefresh()
                    refreshPendingApprovals()
                    refreshCommands()
                }
            },
        )
    }

    private fun loadSnapshot(api: DshClient, device: DeviceView): HarnessState {
        val roots = if ("files.read" in device.scopes) api.roots() else emptyList()
        val canReadChat = "chat.read" in device.scopes
        val sessions = if (canReadChat) api.sessions() else emptyList()
        val chatWorkspaces = if (canReadChat) api.chatWorkspaces() else emptyList()
        val agentPresets = if (canReadChat) api.agentPresets() else emptyList()
        val canReadSettings = "settings.read" in device.scopes
        val selectedRoot = roots.firstOrNull()?.id
        val directory = selectedRoot?.let { api.entries(it) }
        return HarnessState(
            restoring = false,
            connected = true,
            endpoint = api.endpoint,
            endpointDraft = api.endpoint.removeSuffix("/api/v1"),
            healthOk = true,
            device = device,
            roots = roots,
            sessions = sessions,
            chatWorkspaces = chatWorkspaces,
            agentPresets = agentPresets,
            providerSettings = if (canReadSettings) api.providerSettings() else null,
            selectedRootId = selectedRoot,
            directory = directory,
            trash = if ("files.read" in device.scopes) api.trash() else emptyList(),
        )
    }

    private fun scheduleChatRefresh() {
        chatRefreshPending = true
        if (chatRefreshJob?.isActive == true) return
        chatRefreshJob = viewModelScope.launch {
            delay(250)
            while (chatRefreshPending || chatSessionRefreshPending) {
                chatRefreshPending = false
                val refreshSessions = chatSessionRefreshPending
                chatSessionRefreshPending = false
                val finalizeTarget = liveFinalizeTarget
                liveFinalizeTarget = null
                val api = client ?: break
                val id = mutableState.value.selectedSessionId
                if (id != null) {
                    runCatching { withContext(Dispatchers.IO) { api.history(id) } }
                        .onSuccess { history ->
                            if (mutableState.value.selectedSessionId == id) update {
                                val clearLive = finalizeTarget != null && liveChat?.let {
                                    it.sessionId == finalizeTarget.sessionId && it.turn == finalizeTarget.turn && it.step == finalizeTarget.step
                                } == true
                                copy(history = history, liveChat = if (clearLive) null else liveChat)
                            }
                        }
                        .onFailure { error ->
                            if (error !is DshApiException || error.code != "RATE_LIMITED") {
                                update { copy(error = messageOf(error)) }
                            } else {
                                if (finalizeTarget != null) liveFinalizeTarget = finalizeTarget
                                chatRefreshPending = true
                            }
                        }
                }
                if (refreshSessions) {
                    runCatching { withContext(Dispatchers.IO) { api.sessions() } }
                        .onSuccess { sessions -> update { copy(sessions = sessions) } }
                        .onFailure { error ->
                            if (error is DshApiException && error.code == "RATE_LIMITED") {
                                chatSessionRefreshPending = true
                            } else {
                                update { copy(error = messageOf(error)) }
                            }
                        }
                }
                delay(1_000)
            }
        }
    }

    private fun withClient(block: suspend (DshClient) -> Unit) {
        val api = client ?: return
        launchOperation { block(api) }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            update { copy(busy = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { update { copy(error = messageOf(it)) } }
            update { copy(busy = false) }
        }
    }

    private fun update(transform: HarnessState.() -> HarnessState) {
        mutableState.update { current -> current.transform() }
    }

    override fun onCleared() {
        chatRefreshJob?.cancel()
        eventSubscription?.close()
        // 协调层自带后台 scope 来跑阻塞式健康检查（协程取消打不断 OkHttp 的阻塞调用），
        // 不显式关闭的话，探活期间挂死的调用会一直占着 IO 线程直到 OkHttp 自身超时。
        coordinator.close()
        super.onCleared()
    }

    private data class DecodedText(val text: String, val hasBom: Boolean, val lineEnding: String)
    private data class LiveKey(val sessionId: String, val turn: Int, val step: Int)

    private fun decodeUtf8(bytes: ByteArray): DecodedText? {
        val hasBom = bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)
        val content = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(content)).toString() }.getOrNull() ?: return null
        if ('\u0000' in text) return null
        val lineEnding = when {
            "\r\n" in text -> "\r\n"
            '\r' in text -> "\r"
            else -> "\n"
        }
        return DecodedText(text, hasBom, lineEnding)
    }

    private fun childPath(parent: String, name: String): String =
        if (parent.isBlank()) name.trim('/') else "$parent/${name.trim('/')}"

    private fun messageOf(error: Throwable): String = when (error) {
        is DshApiException -> "${error.code}: ${error.message}"
        else -> error.message ?: error::class.java.simpleName
    }

    private companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

private fun WorkspaceEvent.dataString(key: String): String? =
    ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

private fun WorkspaceEvent.dataInt(key: String): Int? =
    ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

private fun List<PendingApproval>.toApprovalState(): ApprovalUiState =
    if (isEmpty()) ApprovalUiState.None else ApprovalUiState.Pending(this)
