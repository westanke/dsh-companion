package io.github.hakunm.deepseekharness.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.BuildConfig
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.data.CustomProviderCreate
import io.github.hakunm.deepseekharness.data.ProviderModel
import io.github.hakunm.deepseekharness.data.ProviderPatch
import io.github.hakunm.deepseekharness.data.ProviderView

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    var editedProvider by remember { mutableStateOf<ProviderView?>(null) }
    var creatingProvider by remember { mutableStateOf(false) }
    val scopes = state.device?.scopes.orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.settings_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsHeading(stringResource(R.string.model_providers), Modifier.weight(1f))
                if (state.providerSettings?.customProvider?.available == true) {
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

            SettingsHeading(stringResource(R.string.connection))
            val activeHost = state.hosts.firstOrNull { it.id == state.activeHostId }
            SettingRow(
                Icons.Outlined.CloudDone,
                activeHost?.displayName ?: stringResource(R.string.connection),
                state.endpoint,
            ) {
                StatusLabel(
                    if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                    state.eventsConnected,
                )
            }
            // 把当前这台电脑的全部地址列出来，并标出此刻实际走的是哪一个。
            // 用户换了网络（局域网 → 虚拟网）之后最想知道的就是这件事。
            activeHost?.let { host ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    host.endpoints.forEach { endpoint ->
                        val inUse = endpoint.id == state.activeEndpointId
                        Surface(
                            shape = CircleShape,
                            color = if (inUse) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ) {
                            Text(
                                buildString {
                                    append(endpointKindLabel(endpoint.kind))
                                    append(" · ")
                                    append(endpoint.baseUrl)
                                    if (inUse) {
                                        append(" · ")
                                        append(stringResource(R.string.active))
                                    }
                                },
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (state.endpoint.startsWith("http://")) {
                WarningBanner(stringResource(R.string.http_warning), Modifier.padding(top = 8.dp, bottom = 22.dp))
            } else Spacer(Modifier.height(22.dp))

            SettingsHeading(stringResource(R.string.device))
            SettingRow(Icons.Outlined.PhoneAndroid, state.device?.name.orEmpty(), state.device?.id.orEmpty())
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                scopes.forEach { scope ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(scope, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            SettingsHeading(stringResource(R.string.language))
            val english = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                SegmentedButton(
                    selected = !english,
                    onClick = { if (english) toggleLanguage() },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.chinese)) }
                SegmentedButton(
                    selected = english,
                    onClick = { if (!english) toggleLanguage() },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.english)) }
            }

            // 智能体运行时的发送行为。放在这里是因为它不是「聊天页的临时开关」，
            // 而是一条会影响每次输入的长期习惯，用户需要能一眼看到自己设成了什么。
            SettingsHeading(stringResource(R.string.busy_send_mode))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.busySendMode == BusySendMode.QUEUE,
                    onClick = { viewModel.setBusySendMode(BusySendMode.QUEUE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                ) { Text(stringResource(R.string.busy_send_mode_queue)) }
                SegmentedButton(
                    selected = state.busySendMode == BusySendMode.STEER,
                    onClick = { viewModel.setBusySendMode(BusySendMode.STEER) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                ) { Text(stringResource(R.string.busy_send_mode_steer)) }
            }
            Text(
                stringResource(
                    if (state.busySendMode == BusySendMode.QUEUE) {
                        R.string.busy_send_mode_queue_desc
                    } else {
                        R.string.busy_send_mode_steer_desc
                    },
                ),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.busy_send_mode_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            // dsh-ui 诊断开关。放在「关于」正上方，因为它服务的正是「关于版本」那一类问题：
            // 用户报「卡片没出来」时，让他自己打开就能拿到证据，不必等我们出新包。
            // 默认关 —— 诊断行是灰字噪音，不该打扰正常使用。
            SettingsHeading(stringResource(R.string.gen_ui_debug_heading))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gen_ui_debug_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.gen_ui_debug_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.genUiDebug, onCheckedChange = viewModel::setGenUiDebug)
            }
            Text(
                stringResource(R.string.gen_ui_debug_footnote),
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            SettingsHeading(stringResource(R.string.about))
            SettingRow(
                Icons.Outlined.Code,
                stringResource(R.string.author),
                stringResource(R.string.author_value),
            )
            SettingRow(
                Icons.Outlined.CheckCircle,
                stringResource(R.string.version),
                "v${BuildConfig.VERSION_NAME}",
                Modifier.padding(bottom = 18.dp),
            )

            OutlinedButton(
                onClick = viewModel::disconnect,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = CircleShape,
            ) {
                Icon(Icons.Outlined.DeleteForever, null)
                Text(stringResource(R.string.disconnect), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    editedProvider?.let { provider ->
        ProviderEditor(state, viewModel, provider) { editedProvider = null }
    }
    if (creatingProvider) {
        CustomProviderEditor(state, viewModel) { creatingProvider = false }
    }
}

@Composable
private fun SettingsHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
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
