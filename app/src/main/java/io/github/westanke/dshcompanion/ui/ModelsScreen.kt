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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.ProviderModel
import io.github.westanke.dshcompanion.data.ProviderPatch
import io.github.westanke.dshcompanion.data.ProviderView
import io.github.westanke.dshcompanion.clearDiscoveredModels
import io.github.westanke.dshcompanion.createCustomProvider
import io.github.westanke.dshcompanion.discoverModels
import io.github.westanke.dshcompanion.updateProvider

/**
 * 「模型」页：从设置页拆出来的模型供应商配置。
 *
 * 为什么拆：设置页把「模型供应商」放在最顶部，用户每次想改模型都要先进设置 ——
 * 而「换模型」是高频动作，「改语言/看版本」是低频动作，两者不该抢同一个入口。
 *
 * 这里只搬了**模型供应商**这一块：供应商列表、自定义供应商新建、单条编辑
 * （[CustomProviderEditor] / [ProviderRow] / [ProviderEditor]）。设置页保留
 * 设备、语言、发送模式、聊天实现、调试与关于。
 *
 * 布局刻意与设置页一致：垂直滚动 + 720dp 宽度上限 + 20/28dp 内边距，
 * 两页来回切换时标题与列表的左边缘对得上，不会一页宽一页窄。
 */
@Composable
fun ModelsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    var editedProvider by remember { mutableStateOf<ProviderView?>(null) }
    var creatingProvider by remember { mutableStateOf(false) }
    val scopes = state.device?.scopes.orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            Text(stringResource(R.string.model_providers), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.models_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            // 大标题已经用了 model_providers，这里就不再重复一遍同名的小标题
            // （原来设置页这一行左边是 SettingsHeading(「模型供应商」)）——
            // 只保留右侧的「添加自定义供应商」。宿主不支持自定义供应商时整行不渲染。
            if (state.providerSettings?.customProvider?.available == true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { creatingProvider = true },
                        enabled = "settings.write" in scopes && state.providerSettings.writable,
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text(stringResource(R.string.add_custom_provider), Modifier.padding(start = 5.dp))
                    }
                }
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
        ProviderEditor(state, viewModel, provider) { editedProvider = null }
    }
    if (creatingProvider) {
        CustomProviderEditor(state, viewModel) { creatingProvider = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomProviderEditor(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onClose: () -> Unit,
) {
    val capability = state.providerSettings?.customProvider ?: return
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var protocol by remember(capability.protocols) { mutableStateOf(capability.protocols.firstOrNull().orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var modelIds by remember { mutableStateOf("") }
    var protocolMenu by remember { mutableStateOf(false) }
    val models = parseModelIds(modelIds)
    val routeValid = id.matches(Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"))
    val ready = routeValid && baseUrl.isNotBlank() && protocol.isNotBlank() && models.isNotEmpty()
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
            Text(stringResource(R.string.add_custom_provider), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.custom_provider_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = id,
                onValueChange = { id = it.trim().lowercase() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_id)) },
                supportingText = { Text(stringResource(R.string.provider_id_hint)) },
                isError = id.isNotEmpty() && !routeValid,
                singleLine = true,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_display_name)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.base_url)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { protocolMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        protocol.ifBlank { stringResource(R.string.provider_protocol) },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
                DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                    capability.protocols.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = { protocol = choice; protocolMenu = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key_optional)) },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelIds,
                onValueChange = { modelIds = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                label = { Text(stringResource(R.string.model_ids)) },
                supportingText = { Text(stringResource(R.string.model_ids_required_hint)) },
            )
            Button(
                onClick = {
                    viewModel.createCustomProvider(
                        CustomProviderCreate(
                            id = id,
                            displayName = displayName.trim().takeIf(String::isNotEmpty),
                            baseURL = baseUrl.trim(),
                            api = protocol,
                            apiKey = apiKey.trim().takeIf(String::isNotEmpty),
                            models = models,
                            expectedRevision = capability.revision,
                        ),
                    )
                    onClose()
                },
                enabled = ready && !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) { Text(stringResource(R.string.create_provider)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
    state: HarnessState,
    viewModel: HarnessViewModel,
    provider: ProviderView,
    onClose: () -> Unit,
) {
    var displayName by remember(provider.id) { mutableStateOf(provider.config.displayName ?: provider.displayName) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.config.baseURL.orEmpty()) }
    var protocol by remember(provider.id) { mutableStateOf(provider.config.api.orEmpty()) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var modelIds by remember(provider.id) { mutableStateOf(provider.config.models.joinToString("\n") { it.id }) }
    val discovered = state.discoveredModels
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(provider.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(
                provider.id,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (provider.credential.configured) {
                    stringResource(R.string.credential_reference, provider.credential.ref)
                } else {
                    stringResource(R.string.credential_missing)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_display_name)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.server_default)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            OutlinedTextField(
                value = protocol,
                onValueChange = { protocol = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.provider_protocol)) },
                placeholder = { Text(stringResource(R.string.server_default)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                placeholder = {
                    Text(
                        if (provider.credential.configured) stringResource(R.string.keep_existing_secret)
                        else stringResource(R.string.enter_api_key),
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { viewModel.discoverModels(provider.id, baseUrl, protocol, apiKey) },
                    enabled = !state.busy,
                    shape = CircleShape,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).height(18.dp), strokeWidth = 2.dp)
                    Icon(Icons.Outlined.ModelTraining, null)
                    Text(stringResource(R.string.discover_models), Modifier.padding(start = 7.dp))
                }
            }
            if (discovered.isNotEmpty()) {
                Text(
                    stringResource(R.string.discovered_models, discovered.size),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = { modelIds = discovered.joinToString("\n") { it.id } }) {
                    Text(stringResource(R.string.use_discovered_models))
                }
            }
            OutlinedTextField(
                value = modelIds,
                onValueChange = { modelIds = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                label = { Text(stringResource(R.string.model_ids)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (provider.config.modelsInherited) R.string.inherited_model_ids_hint
                            else R.string.model_ids_hint,
                        ),
                    )
                },
            )
            Button(
                onClick = {
                    viewModel.updateProvider(
                        provider.id,
                        ProviderPatch(
                            displayName = displayName.trim(),
                            baseURL = baseUrl.trim(),
                            api = protocol.trim(),
                            apiKey = apiKey.takeIf { it.isNotBlank() },
                            models = parseModelIds(modelIds).takeUnless { models ->
                                provider.config.modelsInherited &&
                                    models.map { it.id } == provider.config.models.map { it.id }
                            },
                        ),
                    )
                    onClose()
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) { Text(stringResource(R.string.save_provider)) }
        }
    }
}

private fun parseModelIds(value: String): List<ProviderModel> = value
    .split('\n', ',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .map(::ProviderModel)
