package io.github.hakunm.deepseekharness
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hakunm.deepseekharness.data.QUESTION_STATE_CONTINUED
import io.github.hakunm.deepseekharness.data.ChatContentParts
import io.github.hakunm.deepseekharness.data.ChatHistory
import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.data.ChatWorkspace
import io.github.hakunm.deepseekharness.data.CommandDescriptor
import io.github.hakunm.deepseekharness.data.CustomProviderCreate
import io.github.hakunm.deepseekharness.data.localizedErrorRes
import io.github.hakunm.deepseekharness.data.DeviceView
import io.github.hakunm.deepseekharness.data.DirectoryPage
import io.github.hakunm.deepseekharness.data.DshApiException
import io.github.hakunm.deepseekharness.data.DshClient
import io.github.hakunm.deepseekharness.data.DshConnectionException
import io.github.hakunm.deepseekharness.data.FileEntry
import io.github.hakunm.deepseekharness.data.handoffPathOf
import io.github.hakunm.deepseekharness.data.LiveChatState
import io.github.hakunm.deepseekharness.data.RootView
import io.github.hakunm.deepseekharness.data.AgentPreset
import io.github.hakunm.deepseekharness.data.ModelSelection
import io.github.hakunm.deepseekharness.data.PendingApproval
import io.github.hakunm.deepseekharness.data.PendingQuestionSet
import io.github.hakunm.deepseekharness.data.PluginInventory
import io.github.hakunm.deepseekharness.data.ProviderModel
import io.github.hakunm.deepseekharness.data.ProviderPatch
import io.github.hakunm.deepseekharness.data.ProviderSettings
import io.github.hakunm.deepseekharness.data.PromptPart
import io.github.hakunm.deepseekharness.data.QuestionAnswer
import io.github.hakunm.deepseekharness.data.ResolvedPath
import io.github.hakunm.deepseekharness.data.SessionModels
import io.github.hakunm.deepseekharness.data.ActiveConnection
import io.github.hakunm.deepseekharness.data.AppPreferences
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.data.ComposerSendPolicy
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
import io.github.hakunm.deepseekharness.data.chatTurnFailure
import io.github.hakunm.deepseekharness.data.PreviewKind
import io.github.hakunm.deepseekharness.data.binaryPreviewByteLimit
import io.github.hakunm.deepseekharness.data.previewKind
import io.github.hakunm.deepseekharness.data.previewKindOf
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

internal fun HarnessViewModel.updateProvider(providerId: String, patch: ProviderPatch) = withClient { api ->
    update { copy(providerSettings = api.updateProvider(providerId, patch), discoveredModels = emptyList()) }
}

internal fun HarnessViewModel.createCustomProvider(provider: CustomProviderCreate) = withClient { api ->
    update { copy(providerSettings = api.createCustomProvider(provider), discoveredModels = emptyList()) }
}

internal fun HarnessViewModel.discoverModels(providerId: String, baseURL: String?, protocol: String?, apiKey: String?) = withClient { api ->
    update { copy(discoveredModels = api.discoverModels(providerId, baseURL, protocol, apiKey)) }
}

internal fun HarnessViewModel.clearDiscoveredModels() = update { copy(discoveredModels = emptyList()) }
