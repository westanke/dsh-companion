package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.ProviderModel
import io.github.westanke.dshcompanion.data.ProviderPatch
import io.github.westanke.dshcompanion.data.ProviderView
import io.github.westanke.dshcompanion.createCustomProvider
import io.github.westanke.dshcompanion.deleteProvider
import io.github.westanke.dshcompanion.discoverModels
import io.github.westanke.dshcompanion.resetProviderModels
import io.github.westanke.dshcompanion.updateProvider

/**
 * 编辑既有供应商：官方 Web 的「编辑 {provider}」卡片。
 *
 * 表单见 [ProviderEditorForm]；这里只包 ModalBottomSheet 外壳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditProviderSheet(
    state: HarnessState,
    viewModel: HarnessViewModel,
    provider: ProviderView,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = 22.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            ProviderEditorForm(state, viewModel, provider = provider, catalogEntry = null)
        }
    }
}

/** 一行模型草稿：ID / 显示名称 / 上下文窗口 / 最大输出 token 数（后两者支持 131072、256K、1M）。 */
private data class ModelRowDraft(val id: String, val name: String, val context: String, val maxTokens: String)

private val ID_REGEX = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

/** 「131072」「256K」「1M」→ 数值；空串返回 null（未填），非法输入也返回 null（由调用方报错）。 */
private fun parseCapacity(raw: String): Long? {
    val text = raw.trim().uppercase()
    if (text.isEmpty()) return null
    val (digits, mult) = when {
        text.endsWith("K") -> text.dropLast(1) to 1024L
        text.endsWith("M") -> text.dropLast(1) to (1024L * 1024L)
        else -> text to 1L
    }
    val value = digits.toLongOrNull() ?: return null
    return if (value > 0) value * mult else null
}

private fun ModelRowDraft.toModel() = ProviderModel(
    id = id.trim(),
    name = name.trim().takeIf(String::isNotEmpty),
    contextWindow = parseCapacity(context),
    maxTokens = parseCapacity(maxTokens),
)

/**
 * 供应商表单 —— 编辑、目录添加、自定义添加三处共用（官方 Web 同一张 ProviderEditor）。
 *
 * 与官方对齐的行为：
 * - 打开卡片那一刻捕获 `revisionByNamespace[settingsNs]` 作为 expectedRevision；
 *   服务端发现文档被别处改动会回 409 SETTINGS_CONFLICT，错误走全局通道、卡片留在原值；
 * - 保存成功不在这里关卡片 —— ViewModel 写 providerSaved，由 ModelsScreen 统一关闭；
 * - API 协议是下拉（选项来自 customProvider.protocols，默认第一项，与官方一致）；
 * - API 密钥 placeholder 按凭据状态分档：已保存凭据 / 已检测到环境变量 / 输入密钥；
 * - 模型目录是结构化行（ID/名称/上下文窗口/最大输出），支持「恢复默认模型」；
 * - removable 的供应商显示「删除」，确认框文案按是否存有凭据分档。
 *
 * [provider] 非 null = 编辑模式；null 时按 [catalogEntry]（目录预填）或纯新建处理。
 */
@Composable
internal fun ProviderEditorForm(
    state: HarnessState,
    viewModel: HarnessViewModel,
    provider: ProviderView?,
    catalogEntry: ProviderView?,
) {
    val settings = state.providerSettings
    val capability = settings?.customProvider
    // 数据来源条目：编辑用 provider 本身；目录添加用目录条目（取 defaultBaseURL/baseURLEnv/nativeAuth）
    val entry = provider ?: catalogEntry
    val entryId = entry?.id ?: "custom-new"
    val settingsNs = entry?.settingsNs ?: "llm-pi-ai"
    val isNewCustom = provider == null && catalogEntry == null

    var id by remember(entryId) { mutableStateOf("") }
    var displayName by remember(entryId) {
        mutableStateOf(provider?.config?.displayName ?: provider?.displayName ?: catalogEntry?.displayName.orEmpty())
    }
    var baseUrl by remember(entryId) {
        mutableStateOf(provider?.config?.baseURL ?: catalogEntry?.defaultBaseURL.orEmpty())
    }
    var protocol by remember(entryId) {
        mutableStateOf(provider?.config?.api?.takeIf(String::isNotBlank) ?: capability?.protocols?.firstOrNull().orEmpty())
    }
    var apiKey by remember(entryId) { mutableStateOf("") }
    var revealKey by rememberSaveable(entryId) { mutableStateOf(false) }
    var rows by remember(entryId) {
        mutableStateOf(
            provider?.config?.models?.map {
                ModelRowDraft(it.id, it.name.orEmpty(), it.contextWindow?.toString().orEmpty(), it.maxTokens?.toString().orEmpty())
            }.orEmpty(),
        )
    }
    var confirmDelete by remember(entryId) { mutableStateOf(false) }
    // 官方 openedAt 语义：打开卡片那一刻的 revision，写请求带上它防并发改动。
    val openedAt = remember(entryId) { settings?.revisionByNamespace?.get(settingsNs) }

    // models:null（unset 恢复适配器默认目录）依赖 bridge 2.5.0；老 bridge 上没有 settingsNs，
    // 发 null 会被 validateModels 拒掉，所以这种情况不提供该入口。
    val metadataSupported = remember(settings) { settings?.providers?.any { it.settingsNs != null } == true }
    val discovered = state.discoveredModels
    val dupIds = rows.groupBy { it.id.trim() }.filterKeys(String::isNotEmpty).filterValues { it.size > 1 }.keys
    fun rowErrorRes(row: ModelRowDraft): Int? = when {
        row.id.isBlank() -> R.string.models_model_id_required
        row.id.trim() in dupIds -> R.string.models_model_id_duplicate
        row.context.isNotBlank() && parseCapacity(row.context) == null -> R.string.models_capacity_invalid
        row.maxTokens.isNotBlank() && parseCapacity(row.maxTokens) == null -> R.string.models_capacity_invalid
        else -> null
    }
    val trimmedUrl = baseUrl.trim()
    // 格式合法：空 = 未填（编辑模式等价 unset，回落适配器默认；新建模式由 urlRequired 拦下）
    val urlValid = trimmedUrl.isEmpty() || trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
    val urlRequired = provider == null
    val idValid = !isNewCustom || ID_REGEX.matches(id)
    val rowsValid = rows.all { rowErrorRes(it) == null }
    val ready = urlValid && (!urlRequired || trimmedUrl.isNotEmpty()) && protocol.isNotBlank() && rowsValid &&
        (provider != null || rows.isNotEmpty()) && idValid

    val nativeEnv = entry?.nativeAuth?.env.orEmpty()
    val keyPlaceholder = when {
        provider != null && provider.credential.configured -> stringResource(R.string.models_key_saved_placeholder)
        nativeEnv.isNotEmpty() -> stringResource(R.string.models_key_env_detected, nativeEnv.joinToString("、"))
        else -> stringResource(R.string.enter_api_key)
    }
    val urlPlaceholder = when {
        entry?.defaultBaseURL != null -> entry.defaultBaseURL
        !entry?.baseURLEnv.isNullOrEmpty() -> stringResource(R.string.models_base_url_env, entry.baseURLEnv.joinToString("、"))
        else -> stringResource(R.string.models_base_url_default)
    }

    // ---------- 标题区 ----------
    if (provider != null) {
        Text(stringResource(R.string.models_edit_provider, provider.displayName), style = MaterialTheme.typography.headlineSmall)
        Text(
            provider.id,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
    if (capability == null) {
        Text(
            stringResource(R.string.models_no_protocols),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // ---------- 基本字段 ----------
    if (isNewCustom) {
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.trim().lowercase() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.provider_id)) },
            supportingText = { Text(stringResource(R.string.provider_id_hint)) },
            isError = id.isNotEmpty() && !ID_REGEX.matches(id),
            singleLine = true,
        )
    }
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
        placeholder = { Text(urlPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Link, null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        isError = !urlValid || (urlRequired && trimmedUrl.isEmpty()),
        supportingText = {
            when {
                urlRequired && trimmedUrl.isEmpty() -> Text(stringResource(R.string.models_custom_needs_base_url))
                !urlValid -> Text(stringResource(R.string.models_base_url_invalid))
            }
        },
    )
    // API 协议：官方是下拉（选项 = 设置 schema 的 union），不再是自由文本。
    if (capability != null) {
        var protocolMenu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { protocolMenu = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text(
                    protocol.ifBlank { stringResource(R.string.provider_protocol) },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                capability.protocols.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = { protocol = choice; protocolMenu = false })
                }
            }
        }
    }
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.api_key)) },
        placeholder = { Text(keyPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Key, null) },
        trailingIcon = {
            IconButton(onClick = { revealKey = !revealKey }) {
                Icon(
                    if (revealKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (revealKey) R.string.models_key_hide else R.string.models_key_show),
                )
            }
        },
        visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
    )

    // ---------- 获取可用模型（仅编辑模式：bridge 的 discover 需要既有供应商上下文） ----------
    if (provider != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = {
                    viewModel.discoverModels(
                        provider.id,
                        trimmedUrl.takeIf(String::isNotEmpty),
                        protocol.trim().takeIf(String::isNotBlank),
                        apiKey.takeIf(String::isNotBlank),
                    )
                },
                enabled = !state.busy,
                shape = CircleShape,
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).height(18.dp), strokeWidth = 2.dp)
                Icon(Icons.Outlined.ModelTraining, null)
                Text(stringResource(R.string.discover_models), Modifier.padding(start = 7.dp))
            }
        }
    }

    // ---------- 模型目录 ----------
    Text(stringResource(R.string.models_models_catalog), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // 新建的目录条目还没有任何模型时，说「已自定义」会误导；按官方文案给「没有列出任何模型」。
            stringResource(
                when {
                    provider?.config?.modelsInherited == true -> R.string.models_models_inherited
                    rows.isEmpty() -> R.string.models_empty_hint
                    else -> R.string.models_models_customized
                },
            ),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (provider != null && metadataSupported && provider.config.modelsInherited == false) {
            TextButton(onClick = { viewModel.resetProviderModels(provider.id, openedAt) }, enabled = !state.busy) {
                Text(stringResource(R.string.models_reset_models))
            }
        }
    }
    rows.forEachIndexed { index, row ->
        ModelRowCard(
            row = row,
            duplicate = row.id.trim() in dupIds,
            errorRes = rowErrorRes(row),
            onUpdate = { next -> rows = rows.toMutableList().also { it[index] = next } },
            onRemove = { rows = rows.toMutableList().also { it.removeAt(index) } },
        )
    }
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.models_selector_empty_warning),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (provider == null) {
            Text(
                // 目录通道不是「自定义模型 API」，别套用那句专有文案。
                stringResource(if (catalogEntry != null) R.string.models_needs_models else R.string.models_custom_needs_models),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    TextButton(onClick = { rows = rows + ModelRowDraft("", "", "", "") }) {
        Icon(Icons.Outlined.Add, null)
        Text(stringResource(R.string.models_add_model), Modifier.padding(start = 5.dp))
    }

    // ---------- 候选模型（discover 结果） ----------
    if (discovered.isNotEmpty()) {
        CandidatesPanel(
            candidates = discovered,
            existing = rows.map { it.id.trim() }.toSet(),
            onApply = { picked ->
                val known = rows.map { it.id.trim() }.toSet()
                rows = rows + picked.filter { it.id !in known }.map {
                    ModelRowDraft(it.id, it.name.orEmpty(), it.contextWindow?.toString().orEmpty(), it.maxTokens?.toString().orEmpty())
                }
            },
        )
    }

    // ---------- 保存 ----------
    Button(
        onClick = {
            val models = rows.map { it.toModel() }
            if (provider != null) {
                viewModel.updateProvider(
                    provider.id,
                    ProviderPatch(
                        displayName = displayName.trim(),
                        baseURL = trimmedUrl,
                        api = protocol.trim(),
                        apiKey = apiKey.takeIf(String::isNotBlank),
                        models = models.takeUnless {
                            provider.config.modelsInherited && models.map { m -> m.id } == provider.config.models.map { m -> m.id }
                        },
                    ),
                    expectedRevision = openedAt,
                    displayLabel = provider.displayName,
                )
            } else {
                viewModel.createCustomProvider(
                    CustomProviderCreate(
                        id = catalogEntry?.id ?: id.trim(),
                        displayName = displayName.trim().takeIf(String::isNotEmpty),
                        baseURL = trimmedUrl,
                        api = protocol.trim(),
                        apiKey = apiKey.takeIf(String::isNotBlank),
                        models = models,
                        expectedRevision = openedAt ?: capability?.revision,
                    ),
                )
            }
        },
        enabled = ready && !state.busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
    ) {
        if (state.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
        Text(
            stringResource(if (provider != null) R.string.save_provider else R.string.create_provider),
            Modifier.padding(start = if (state.busy) 8.dp else 0.dp),
        )
    }

    // ---------- 删除 ----------
    if (provider != null && provider.removable) {
        OutlinedButton(
            onClick = { confirmDelete = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.DeleteOutline, null)
            Text(stringResource(R.string.models_delete), Modifier.padding(start = 6.dp))
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.models_delete_title, provider.displayName)) },
                text = {
                    Text(
                        stringResource(
                            if (provider.credential.configured) R.string.models_delete_with_credential
                            else R.string.models_delete_without_credential,
                            provider.displayName,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            viewModel.deleteProvider(provider.id, openedAt)
                        },
                    ) { Text(stringResource(R.string.models_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

/** 单行模型编辑卡：ID+名称一行，上下文窗口+最大输出一行，右侧删除。 */
@Composable
private fun ModelRowCard(
    row: ModelRowDraft,
    duplicate: Boolean,
    errorRes: Int?,
    onUpdate: (ModelRowDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = row.id,
                onValueChange = { onUpdate(row.copy(id = it.trim())) },
                modifier = Modifier.weight(1.2f),
                label = { Text(stringResource(R.string.model_ids)) },
                isError = row.id.isBlank() || duplicate,
                singleLine = true,
            )
            OutlinedTextField(
                value = row.name,
                onValueChange = { onUpdate(row.copy(name = it)) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.provider_display_name)) },
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.context,
                onValueChange = { onUpdate(row.copy(context = it)) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.models_context_window)) },
                isError = row.context.isNotBlank() && errorRes == R.string.models_capacity_invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
            )
            OutlinedTextField(
                value = row.maxTokens,
                onValueChange = { onUpdate(row.copy(maxTokens = it)) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.models_max_tokens)) },
                isError = row.maxTokens.isNotBlank() && errorRes == R.string.models_capacity_invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.models_remove_model))
            }
        }
        if (errorRes == R.string.models_model_id_required || errorRes == R.string.models_model_id_duplicate) {
            Text(
                stringResource(errorRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * discover 结果候选面板：搜索 + 全选/取消全选 + 添加所选（官方 CandidatePicker 精简版）。
 * [existing] 用于过滤已在目录里的模型，避免重复 ID。
 */
@Composable
private fun CandidatesPanel(
    candidates: List<ProviderModel>,
    existing: Set<String>,
    onApply: (List<ProviderModel>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(setOf<String>()) }
    val filtered = candidates.filter {
        query.isBlank() || it.id.contains(query, ignoreCase = true) || it.name?.contains(query, ignoreCase = true) == true
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.discovered_models, candidates.size),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.models_candidate_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { checked = filtered.map { it.id }.toSet() }) { Text(stringResource(R.string.models_candidate_all)) }
            TextButton(onClick = { checked = emptySet() }) { Text(stringResource(R.string.models_candidate_none)) }
            TextButton(
                onClick = {
                    onApply(filtered.filter { it.id in checked })
                    checked = emptySet()
                },
                enabled = checked.isNotEmpty(),
            ) { Text(stringResource(R.string.models_candidate_apply)) }
        }
        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.models_candidate_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            filtered.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        checked = if (model.id in checked) checked - model.id else checked + model.id
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = model.id in checked, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(model.id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        val meta = listOfNotNull(
                            model.name,
                            model.contextWindow?.toString(),
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
