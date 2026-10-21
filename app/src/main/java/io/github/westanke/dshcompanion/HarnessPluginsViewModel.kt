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
 * 「设置」域里**插件与模型提供商**两块：插件清单读取 / 启停，提供商的增改查与模型发现。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出，成员函数改成 **[HarnessViewModel] 的扩展函数**。
 *
 * 三条规则值得单独说，都是踩过的：
 *
 * - **权限前置**：没有 `settings.read` 时整条路直接返回，而不是发一个必然 403 的请求；
 * - **启停必须重拉清单**：本地那份的 `loaded` 还是旧值，不重拉的话开关会弹回原位，
 *   用户会以为没生效（而实际上是生效了，只是要重启 DSH）；
 * - **版本检查单独一条路**：给每个已装包发一次 registry 请求，清单会从毫秒级变成好几秒。
 *   默认那条必须保持轻快，否则每次进页面都慢一下。
 */
internal fun HarnessViewModel.refreshProviderSettings() = withClient { api ->
    if ("settings.read" !in mutableState.value.device?.scopes.orEmpty()) return@withClient
    update { copy(providerSettings = api.providerSettings()) }
}

internal fun HarnessViewModel.refreshPluginInventory() = withClient { api ->
    if ("settings.read" !in mutableState.value.device?.scopes.orEmpty()) return@withClient
    update { copy(pluginInventory = api.pluginInventory()) }
}

/**
 * 带版本检查地刷新插件清单（用户主动点「检查更新」时）。
 *
 * 单独一条路而不是给 [refreshPluginInventory] 加参数：版本检查要给每个已装包
 * 发一次 registry 请求，清单会从毫秒级变成好几秒。默认那条必须保持轻快，
 * 否则每次进页面都慢一下 —— 而九成的情况用户只是想看「哪个没启用」。
 */
internal fun HarnessViewModel.refreshPluginInventoryWithUpdates() = withClient { api ->
    if ("settings.read" !in mutableState.value.device?.scopes.orEmpty()) return@withClient
    update { copy(pluginInventory = api.pluginInventoryWithUpdates()) }
}

/**
 * 启用 / 停用一个插件。
 *
 * 走 [launchOperation] 而不是自己起协程，图两件事：
 * - **全局 busy 把按钮禁掉**，重复点击不会连发两次写请求 —— 这是**改 profile 的
 *   package.json** 的操作，连点两次的后果是写两份备份、改两次 bundles；
 * - 失败统一落进既有错误通道（`messageOf` 已按界面语言本地化，服务端那些
 *   `PLUGIN_OFFICIAL_IMMUTABLE` / `PLUGIN_NOT_INSTALLED` 都有对应文案）。
 *
 * 成功后**必须重拉清单**：本地那份的 `loaded` 还是旧值，不重拉的话开关会弹回原位，
 * 用户会以为没生效（而实际上是生效了，只是要重启 DSH 才算数）。
 *
 * 成功提示也走同一个通道（`notify`）：服务端恒返回 `restartRequired: true`，
 * 这句话**必须说出来** —— 用户点完开关看到插件照旧在跑，只会以为功能坏了。
 */
internal fun HarnessViewModel.setPluginLoaded(name: String, loaded: Boolean) = withClient { api ->
    if ("settings.write" !in mutableState.value.device?.scopes.orEmpty()) {
        update { copy(error = appString(R.string.plugins_write_permission_required)) }
        return@withClient
    }
    val result = api.setPluginLoaded(name, loaded)
    // 重拉清单：本地那份的 loaded 还是旧值，不重拉开关会弹回原位，
    // 用户会以为没生效（实际生效了，只是要重启 DSH 才算数）。
    update { copy(pluginInventory = api.pluginInventory()) }
    if (result.restartRequired) notify(appString(R.string.plugins_toggle_saved))
}

/**
 * 更新供应商并**在成功时回传保存反馈**。
 *
 * [expectedRevision] 是编辑卡打开那一刻捕获的 revision（`revisionByNamespace` 里
 * 该供应商 settingsNs 的值）：写请求带上它，服务端发现文档已被别处改动会回
 * 409 `SETTINGS_CONFLICT`，UI 把卡片留在原值上让用户重新打开 —— 与官方 Web
 * 「conflict」文案的行为一致。成功则写 [HarnessState.providerSaved] 供 UI 关卡片，
 * 并 `notify` 一条「已保存 {provider}。」。
 */
internal fun HarnessViewModel.updateProvider(providerId: String, patch: ProviderPatch, expectedRevision: Int? = null, displayLabel: String = providerId) = withClient { api ->
    val settings = api.updateProvider(providerId, patch.copy(expectedRevision = expectedRevision ?: patch.expectedRevision))
    update { copy(providerSettings = settings, discoveredModels = emptyList(), providerSaved = providerId) }
    notify(appString(R.string.models_provider_saved, displayLabel))
}

internal fun HarnessViewModel.createCustomProvider(provider: CustomProviderCreate) = withClient { api ->
    val settings = api.createCustomProvider(provider)
    update { copy(providerSettings = settings, discoveredModels = emptyList(), providerSaved = provider.id) }
    notify(appString(R.string.models_provider_saved, provider.displayName?.takeIf { it.isNotBlank() } ?: provider.id))
}

/**
 * 删除供应商：bridge 会 unset 其配置段（凭据若被别处引用则保留，删除确认框里说明）。
 * 成功反馈同样走 providerSaved 通道（UI 关掉删除确认框并刷新）。
 */
internal fun HarnessViewModel.deleteProvider(providerId: String, expectedRevision: Int? = null) = withClient { api ->
    api.deleteProvider(providerId, expectedRevision)
    update { copy(providerSettings = api.providerSettings(), providerSaved = providerId) }
    notify(appString(R.string.models_provider_deleted, providerId))
}

/** 恢复供应商的适配器默认模型目录（wire `models: null`）。 */
internal fun HarnessViewModel.resetProviderModels(providerId: String, expectedRevision: Int? = null) = withClient { api ->
    update { copy(providerSettings = api.resetProviderModels(providerId, expectedRevision), providerSaved = providerId) }
}

internal fun HarnessViewModel.clearProviderSaved() = update { copy(providerSaved = null) }

internal fun HarnessViewModel.discoverModels(providerId: String, baseURL: String?, protocol: String?, apiKey: String?) = withClient { api ->
    update { copy(discoveredModels = api.discoverModels(providerId, baseURL, protocol, apiKey)) }
}

internal fun HarnessViewModel.clearDiscoveredModels() = update { copy(discoveredModels = emptyList()) }
