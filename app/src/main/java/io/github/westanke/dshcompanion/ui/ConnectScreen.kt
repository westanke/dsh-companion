package io.github.westanke.dshcompanion.ui

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.Credential
import io.github.westanke.dshcompanion.data.Endpoint
import io.github.westanke.dshcompanion.data.EndpointProbe
import io.github.westanke.dshcompanion.data.Host
import io.github.westanke.dshcompanion.connectHost
import io.github.westanke.dshcompanion.importConfiguration
import io.github.westanke.dshcompanion.pair
import io.github.westanke.dshcompanion.removeHost
import io.github.westanke.dshcompanion.setEndpointDraft
import io.github.westanke.dshcompanion.testConnection

/**
 * 未连接时的首屏。
 *
 * ## 相对旧版的三个结构性改动
 *
 * 1. **多了「已保存的电脑」列表。** 旧版没有这个概念 —— 它只有「一个地址 + 一个令牌」，
 *    所以用户反馈「只能添加一条路径」。现在一台电脑可以有任意多个地址，列表里能看到。
 * 2. **「从电脑导入配置」取代扫码成为主路径。** 用户的真实场景是人已经出门在外、
 *    电脑留在家里，扫码和念配对码都必须要求电脑在旁，因此不能作为主路径。
 * 3. **手动配对降为可折叠的次要入口。** 它仍有用（你正坐在电脑前时最省事），
 *    但不再占据首屏最重要的位置，并且明确标注了它的前置条件。
 *
 * ## 后来补的两条（用户原话驱动）
 *
 * 4. **启动无论怎样，第一屏都是这里。** 此前「记住过主机」的冷启动会被自动重连直接送进聊天页，
 *    用户要改地址就得先找到断开的入口。现在放行只认用户的主动动作（[onEnterApp]），
 *    自动重连成功也仍然停在本页 —— 判据不能是「连没连上」。
 * 5. **两种连接方式默认都收起**，只留一行入口（标题 + 一句话说明 + 箭头），点哪个展开哪个，
 *    且互斥。它们是**并列的两条路**，不是「一个表单 + 一个按钮」；形态一致才不会让人以为
 *    只有一个入口。已保存的电脑列表不在此列 —— 那不是「连接方式」，是「连过的机器」。
 */
@Composable
fun ConnectScreen(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onEnterApp: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(state.endpointDraft) }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL.ifBlank { "Android" }) }
    var configText by remember { mutableStateOf("") }
    // 两种连接方式默认都**收起**。用户的原话是「默认显示两个不同的连接方式，不要展开，
    // 我需要那个，我自己点击」。互斥：展开一个就把另一个收起来 —— 一次只开一个，
    // 屏幕上不会同时铺两份表单。
    var importExpanded by remember { mutableStateOf(false) }
    var manualExpanded by remember { mutableStateOf(false) }
    var hostPendingRemoval by remember { mutableStateOf<Host?>(null) }
    LaunchedEffect(state.endpointDraft) { endpoint = state.endpointDraft }

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = ::toggleLanguage,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Icon(Icons.Outlined.Language, stringResource(R.string.language)) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            BrandMark()
            Text(
                stringResource(R.string.connect_title),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                stringResource(R.string.connect_subtitle),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )

            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(top = 26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SavedComputersPanel(
                    state = state,
                    onConnect = { hostId ->
                        // 用户主动的一个动作 —— 放行连接页（失败时 connected 仍为 false，
                        // 界面自然还停在这里，见 App.kt 的判据）。
                        onEnterApp()
                        // 这台已经连上了（冷启动自动重连成功就会停在这个状态）：直接进去，
                        // 不必再探活一遍。没连上的才走 connectHost（并发探活 + 自动选路）。
                        if (!(state.connected && hostId == state.activeHostId)) viewModel.connectHost(hostId)
                    },
                    onRemoveRequest = { hostPendingRemoval = it },
                )

                // 顺序：导入配置在前（主路径 —— 人已经出门时配对码无处可取），手动配对在后。
                // 理由原本就写在 ManualPairingPanel 的 KDoc 里，保留。
                ImportConfigPanel(
                    expanded = importExpanded,
                    onToggle = {
                        importExpanded = !importExpanded
                        if (importExpanded) manualExpanded = false
                    },
                    text = configText,
                    onTextChange = { configText = it },
                    enabled = !state.busy && !state.connecting,
                    onImport = {
                        onEnterApp()
                        viewModel.importConfiguration(configText)
                    },
                )

                ManualPairingPanel(
                    expanded = manualExpanded,
                    onToggle = {
                        manualExpanded = !manualExpanded
                        if (manualExpanded) importExpanded = false
                    },
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it; viewModel.setEndpointDraft(it) },
                    code = code,
                    onCodeChange = { code = it.uppercase() },
                    deviceName = deviceName,
                    onDeviceNameChange = { deviceName = it },
                    busy = state.busy,
                    healthOk = state.healthOk,
                    // 测试连通性只是探活，不建立连接 —— 刻意**不**放行连接页。
                    onTest = { viewModel.testConnection(endpoint) },
                    onPair = {
                        onEnterApp()
                        viewModel.pair(endpoint, code, deviceName)
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    hostPendingRemoval?.let { host ->
        RemoveHostDialog(
            host = host,
            onDismiss = { hostPendingRemoval = null },
            onConfirm = {
                viewModel.removeHost(host.id)
                hostPendingRemoval = null
            },
        )
    }
}

/**
 * 已保存的电脑。
 *
 * 每台电脑下会显示它的全部地址及其类型（局域网/虚拟网/公网），这一行的存在本身就是
 * 对用户反馈第 3 条的回应：他出去了可能是局域网、也可能是虚拟网，地址不止一个。
 */
@Composable
private fun SavedComputersPanel(
    state: HarnessState,
    onConnect: (String) -> Unit,
    onRemoveRequest: (Host) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.saved_computers),
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.hosts.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
            ) {
                // 冷启动时主机列表是**异步**读出来的（restoreConnection 要过磁盘与密钥库）。
                // 那段窗口里 hosts 是空的，直接渲染「还没有保存的电脑」等于在首屏闪一句假话 ——
                // 连接页现在是每一台设备的必经入口，第一眼就自相矛盾尤其难看。
                if (state.restoring) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.loading_saved_computers),
                            modifier = Modifier.padding(start = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.no_hosts_yet),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        state.hosts.forEach { host ->
            HostCard(
                host = host,
                isActive = host.id == state.activeHostId && state.connected,
                isSelected = host.id == state.selectedHostId,
                connecting = state.connecting && host.id == state.selectedHostId,
                probes = if (host.id == state.selectedHostId) state.endpointProbes else emptyList(),
                activeEndpointId = if (host.id == state.activeHostId) state.activeEndpointId else null,
                enabled = !state.busy && !state.connecting,
                onConnect = { onConnect(host.id) },
                onRemove = { onRemoveRequest(host) },
            )
        }
    }
}

@Composable
private fun HostCard(
    host: Host,
    isActive: Boolean,
    isSelected: Boolean,
    connecting: Boolean,
    probes: List<EndpointProbe>,
    activeEndpointId: String?,
    enabled: Boolean,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val credential = host.credential
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(host.displayName, style = MaterialTheme.typography.titleMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.endpoint_count, host.endpoints.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (isActive) {
                            StatusLabel(stringResource(R.string.connected), true)
                        }
                    }
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Outlined.Delete,
                        stringResource(R.string.remove_computer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 凭据状态：过期/缺失时明确告知，而不是等用户点了连接才报一个看不懂的错。
            if (credential == null) {
                Text(
                    stringResource(R.string.no_credentials),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (isExpired(credential)) {
                WarningBanner(stringResource(R.string.credentials_expired))
            }

            // 每个地址一行：类型 + 地址 + 探活结果。
            host.endpoints.forEach { endpoint ->
                EndpointRow(
                    endpoint = endpoint,
                    active = endpoint.id == activeEndpointId,
                    probe = probes.firstOrNull { it.endpointId == endpoint.id },
                )
            }

            if (connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.connecting_endpoints, host.endpoints.size),
                        modifier = Modifier.padding(start = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 只有探测过、且存在失败地址时才给诊断 —— 全部可达时没必要增加噪音。
            val failed = probes.count { !it.ok }
            if (probes.isNotEmpty() && failed > 0) {
                DiagnosticPanel(probes = probes, endpoints = host.endpoints)
            }

            /*
             * 启用条件分两种，**不能混用同一个**。
             *
             * 「连接这台电脑」要做网络动作（并发探活 + 自动选路），忙的时候确实该禁掉。
             * 但「进入应用」是**纯本地跳转** —— 不碰网络、不建连接。
             * 此前两者共用 `enabled`，于是自动重连成功后只要后台还有一次请求在跑
             * （loadSnapshot、会话刷新都算 `busy`），按钮就是灰的：
             * 连接页进不去、也没有第二个入口 —— 真机截图里那个灰掉的「进入应用」就是这个。
             */
            Button(
                onClick = onConnect,
                enabled = if (isActive) host.endpoints.isNotEmpty() else enabled && host.endpoints.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Outlined.Link, null)
                // 已经连上的那台，按钮必须是「进入应用」而不是「刷新」：
                // 连接页现在是**每一台**设备的必经入口（连上以后也停在这里等用户点头），
                // 写「刷新」的话，用户找不到「进去」这件事该点哪里。
                Text(
                    if (isActive) stringResource(R.string.enter_app) else stringResource(R.string.connect_this_computer),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: Endpoint, active: Boolean, probe: EndpointProbe?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            endpointKindLabel(endpoint.kind),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Column(Modifier.weight(1f)) {
            Text(endpoint.label, style = MaterialTheme.typography.bodySmall)
            Text(
                endpoint.baseUrl,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        when {
            active -> Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(R.string.connected),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            probe?.ok == true -> Text(
                stringResource(R.string.endpoint_reachable, probe.latencyMs ?: 0L),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            probe != null -> Text(
                stringResource(R.string.endpoint_unreachable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 地址诊断：逐条列出每个地址为什么连不上。
 *
 * 这是「用户过去只能手填地址、却不知道填哪个才通」的直接解法 —— 与其只说一句
 * 「连接失败」，不如把三个地址各自的结果摆出来。
 */
@Composable
private fun DiagnosticPanel(probes: List<EndpointProbe>, endpoints: List<Endpoint>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.endpoint_diagnostics),
                style = MaterialTheme.typography.labelMedium,
            )
            probes.filterNot { it.ok }.forEach { probe ->
                val label = endpoints.firstOrNull { it.id == probe.endpointId }?.let { it.label + " · " + it.baseUrl }
                    ?: probe.endpointId
                Text(
                    "$label — ${probe.error ?: stringResource(R.string.endpoint_unreachable)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 两条连接方式共用的入口行。
 *
 * 刻意做成**同一个组件**：此前导入配置是直接铺开的表单、手动配对是一个底部小字按钮，
 * 两者长得完全不像同类东西，用户根本认不出「这里其实有两条路」。
 * 现在统一为「图标 + 标题 + 一句说明 + 箭头」，收起/展开只是箭头反过来。
 *
 * 行本身**始终**渲染（展开时也不消失），所以说明文字放这里就够了，
 * 展开区里不必再重复一遍。
 */
@Composable
private fun ConnectionEntryRow(
    expanded: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 配置导入面板 —— 取代扫码的主路径。
 *
 * 与手动配对同款：平时只显示一行入口，点开才铺表单（默认收起，用户要那个才点）。
 *
 * 展开区里的文案刻意把「为什么要有这个」说清楚：用户看到「粘贴一段文本」时第一反应是
 * 「为什么不扫码更简单」，必须当场回答这个疑问，否则他还是会觉得别扭。
 */
@Composable
private fun ImportConfigPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionEntryRow(
            expanded = expanded,
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.import_from_computer),
            subtitle = stringResource(R.string.import_entry_hint),
            onToggle = onToggle,
        )
        AnimatedVisibility(visible = expanded, enter = fadeIn() + slideInVertically { it / 3 }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.import_explanation),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.import_hint)) },
                        minLines = 2,
                        maxLines = 5,
                        shape = MaterialTheme.shapes.medium,
                    )
                    WarningBanner(stringResource(R.string.import_security_note))
                    Button(
                        onClick = onImport,
                        enabled = enabled && text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Outlined.Link, null)
                        Text(stringResource(R.string.import_and_connect), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 手动配对：保留旧能力，但降到折叠区并标注前置条件。
 *
 * 它不是没用了 —— 你正坐在电脑前时，配对码比复制粘贴一段长文本更省事。
 * 问题只在于它**不能当主路径**：用户已经出门时，配对码无处可取。
 */
@Composable
private fun ManualPairingPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    busy: Boolean,
    healthOk: Boolean,
    onTest: () -> Unit,
    onPair: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionEntryRow(
            expanded = expanded,
            icon = Icons.Outlined.Key,
            title = stringResource(R.string.manual_pairing),
            subtitle = stringResource(R.string.manual_pairing_hint),
            onToggle = onToggle,
        )
        AnimatedVisibility(visible = expanded, enter = fadeIn() + slideInVertically { it / 3 }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 说明文字已经常驻在入口行上（入口行不随展开消失），这里不再重复一遍。
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) },
                    placeholder = { Text("http://192.168.1.20:3090") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.medium,
                )
                if (endpoint.trim().startsWith("http://")) WarningBanner(stringResource(R.string.http_warning))
                OutlinedButton(
                    onClick = onTest,
                    enabled = !busy && endpoint.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.WifiFind, null)
                    Text(stringResource(R.string.test_connection), modifier = Modifier.padding(start = 8.dp))
                }
                AnimatedVisibility(visible = healthOk, enter = fadeIn() + slideInVertically { it / 2 }) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                            Text(
                                stringResource(R.string.connection_ok),
                                modifier = Modifier.padding(start = 10.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pairing_code)) },
                    leadingIcon = { Icon(Icons.Outlined.Key, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.device_name)) },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Button(
                    onClick = onPair,
                    enabled = !busy && endpoint.isNotBlank() && code.isNotBlank() && deviceName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.Link, null)
                    Text(stringResource(R.string.connect), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun RemoveHostDialog(host: Host, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_computer_title, host.displayName)) },
        text = { Text(stringResource(R.string.remove_computer_warning)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}

private fun isExpired(credential: Credential): Boolean = credential.isExpired()

fun toggleLanguage() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    setAppLanguage(if (current.startsWith("en")) "zh-CN" else "en")
}

/**
 * 切换应用语言；[tag] 为 null 表示**跟随系统**。
 *
 * 抽出来是因为设置页的语言改成三档（跟随系统 / 中文 / English），
 * 而原来只有一个二选一的 [toggleLanguage]。跟随系统用「空 LocaleList」表达 ——
 * 这是 AppCompatDelegate 的约定，注意它与「从没设置过」在 API 上无法区分，
 * 所以冷启动是否兜底中文要看 `AppPreferences.languageChosen` 那个标志。
 */
fun setAppLanguage(tag: String?) {
    AppCompatDelegate.setApplicationLocales(
        if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    )
}
