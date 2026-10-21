package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ProviderView
import io.github.westanke.dshcompanion.clearDiscoveredModels
import io.github.westanke.dshcompanion.discoverModels
import io.github.westanke.dshcompanion.clearProviderSaved
import io.github.westanke.dshcompanion.createCustomProvider
import io.github.westanke.dshcompanion.deleteProvider
import io.github.westanke.dshcompanion.resetProviderModels
import io.github.westanke.dshcompanion.updateProvider

/** 添加卡的两个模式，与官方 Web 的 SegmentedControl 对齐。 */
private enum class AddMode { Catalog, Custom }

/**
 * 「模型」页：模型供应商配置，对照官方 Web `dsh-client-ui-settings-models` 调整。
 *
 * 与官方对齐的三处：
 * 1. 标题/简介用官方文案（「模型」+「填入各提供商的 API 密钥即可使用其模型。」）；
 * 2. 设置文档只读时显示官方「当前部署的设置文档为只读。」横幅；
 * 3. 「添加模型提供商」打开双模式添加卡（目录 / 自定义），见 [AddProviderSheet]。
 *
 * 布局沿用拆页约定：垂直滚动 + 720dp 宽度上限 + 20/28dp 内边距，与设置页左边缘对齐。
 */
@Composable
fun ModelsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    var editedProvider by remember { mutableStateOf<ProviderView?>(null) }
    var addingProvider by remember { mutableStateOf(false) }
    val scopes = state.device?.scopes.orEmpty()
    // 官方行为：保存/删除成功后卡片才关闭（失败留在原值上）。providerSaved 由
    // ViewModel 在成功时写入 —— 这里统一关掉所有卡片并消费掉反馈。
    LaunchedEffect(state.providerSaved) {
        if (state.providerSaved != null) {
            editedProvider = null
            addingProvider = false
            viewModel.clearProviderSaved()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.models_intro),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 官方 readOnly 提示：部署把设置文档设为只读时，任何写入都会被拒。
            if ("settings.read" in scopes && state.providerSettings?.writable == false) {
                WarningBanner(stringResource(R.string.models_read_only), Modifier.padding(bottom = 18.dp))
            }
            if ("settings.read" !in scopes) {
                WarningBanner(stringResource(R.string.settings_permission_required), Modifier.padding(bottom = 22.dp))
            } else if (state.providerSettings == null) {
                Row(
                    Modifier.fillMaxWidth().height(92.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { CircularProgressIndicator(strokeWidth = 2.dp) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            viewModel.clearDiscoveredModels()
                            addingProvider = true
                        },
                        enabled = "settings.write" in scopes && state.providerSettings.writable,
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text(stringResource(R.string.models_add_provider), Modifier.padding(start = 5.dp))
                    }
                }
                state.providerSettings.providers.forEachIndexed { index, provider ->
                    ProviderRow(
                        provider = provider,
                        canEdit = "settings.write" in scopes && state.providerSettings.writable && provider.configurable,
                        onClick = {
                            viewModel.clearDiscoveredModels()
                            editedProvider = provider
                        },
                    )
                    if (index != state.providerSettings.providers.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Text(
                    stringResource(R.string.provider_secret_note),
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    editedProvider?.let { provider ->
        EditProviderSheet(state, viewModel, provider) { editedProvider = null }
    }
    if (addingProvider) {
        AddProviderSheet(state, viewModel) { addingProvider = false }
    }
}

/**
 * 添加卡：官方 Web 是「第三方模型提供商 / 自定义模型 API」双模式。
 * 目录模式从设置里还没配置的休眠目录条目里挑（[ProviderView.settingsNs] 为
 * `llm-pi-ai` 的才可选 —— bridge 的 createProvider 只会写这个命名空间），
 * 选中后复用与编辑同一张表单，并自动向适配器询问可用模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderSheet(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onClose: () -> Unit,
) {
    val settings = state.providerSettings ?: return
    var mode by remember { mutableIntStateOf(AddMode.Catalog.ordinal) }
    // 目录候选 = 目录里尚未配置的可配置条目；都配过了就显示官方「目录中的提供商都已添加。」
    val candidates = remember(settings) {
        settings.providers.filter { it.configurable && !it.configured && it.settingsNs == "llm-pi-ai" }
    }
    // 目录元数据（settingsNs）是 bridge 2.5.0 起才有的：老 bridge 上一个字段都没有，
    // 此时候选必然为空 —— 那不是「都添加过了」，要如实说是服务端太旧。
    val metadataSupported = remember(settings) { settings.providers.any { it.settingsNs != null } }
    var picked by remember { mutableStateOf<ProviderView?>(null) }
    // 官方在选中目录供应商后会自动询问可用模型；休眠条目无需地址即可询问。
    LaunchedEffect(picked?.id) {
        picked?.let { viewModel.discoverModels(it.id, null, null, null) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = 22.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(stringResource(R.string.models_add_provider), style = MaterialTheme.typography.headlineSmall)
            TabRow(selectedTabIndex = mode) {
                Tab(selected = mode == AddMode.Catalog.ordinal, onClick = { mode = AddMode.Catalog.ordinal }, text = {
                    Text(stringResource(R.string.models_tab_catalog), maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
                Tab(selected = mode == AddMode.Custom.ordinal, onClick = { mode = AddMode.Custom.ordinal }, text = {
                    Text(stringResource(R.string.models_tab_custom), maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
            }
            if (mode == AddMode.Catalog.ordinal) {
                if (!metadataSupported) {
                    Text(
                        stringResource(R.string.models_catalog_needs_bridge),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.models_catalog_exhausted),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.models_catalog_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box(Modifier.fillMaxWidth()) {
                        var menu by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Text(
                                picked?.displayName ?: stringResource(R.string.models_pick_provider),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            candidates.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.displayName) },
                                    onClick = {
                                        picked = entry
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                    picked?.let { entry ->
                        ProviderEditorForm(state, viewModel, provider = null, catalogEntry = entry)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.models_custom_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                ProviderEditorForm(state, viewModel, provider = null, catalogEntry = null)
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderView, canEdit: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEdit, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (provider.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Icon(
                Icons.Outlined.ModelTraining,
                null,
                modifier = Modifier.padding(10.dp),
                tint = if (provider.active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (provider.active) {
                    Text(
                        stringResource(R.string.active),
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                if (provider.credential.configured) stringResource(R.string.credential_configured)
                else stringResource(R.string.credential_missing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (provider.credential.configured) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
        }
        if (canEdit) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
