package io.github.hakunm.deepseekharness.ui

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.data.Credential
import io.github.hakunm.deepseekharness.data.Endpoint
import io.github.hakunm.deepseekharness.data.EndpointProbe
import io.github.hakunm.deepseekharness.data.Host

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
 */
@Composable
fun ConnectScreen(state: HarnessState, viewModel: HarnessViewModel) {
    var endpoint by remember { mutableStateOf(state.endpointDraft) }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL.ifBlank { "Android" }) }
    var configText by remember { mutableStateOf("") }
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
                    onConnect = viewModel::connectHost,
                    onRemoveRequest = { hostPendingRemoval = it },
                )

                ImportConfigPanel(
                    text = configText,
                    onTextChange = { configText = it },
                    enabled = !state.busy && !state.connecting,
                    onImport = { viewModel.importConfiguration(configText) },
                )

                ManualPairingPanel(
                    expanded = manualExpanded,
                    onToggle = { manualExpanded = !manualExpanded },
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it; viewModel.setEndpointDraft(it) },
                    code = code,
                    onCodeChange = { code = it.uppercase() },
                    deviceName = deviceName,
                    onDeviceNameChange = { deviceName = it },
                    busy = state.busy,
                    healthOk = state.healthOk,
                    onTest = { viewModel.testConnection(endpoint) },
                    onPair = { viewModel.pair(endpoint, code, deviceName) },
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
                Text(
                    stringResource(R.string.no_hosts_yet),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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

            Button(
                onClick = onConnect,
                enabled = enabled && host.endpoints.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Outlined.Link, null)
                Text(
                    if (isActive) stringResource(R.string.refresh) else stringResource(R.string.connect_this_computer),
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
 * 配置导入面板 —— 取代扫码的主路径。
 *
 * 文案刻意把「为什么要有这个」说清楚：用户看到「粘贴一段文本」时第一反应是
 * 「为什么不扫码更简单」，必须当场回答这个疑问，否则他还是会觉得别扭。
 */
@Composable
private fun ImportConfigPanel(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.import_from_computer),
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
            Text(
                stringResource(R.string.manual_pairing),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn() + slideInVertically { it / 3 }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.manual_pairing_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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
    val next = if (current.startsWith("en")) "zh-CN" else "en"
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
}
