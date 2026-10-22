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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.SettingsControl
import io.github.westanke.dshcompanion.data.SettingsFieldSpec
import io.github.westanke.dshcompanion.data.SettingsNamespace
import io.github.westanke.dshcompanion.data.SettingsOp
import io.github.westanke.dshcompanion.data.SettingsSchema
import io.github.westanke.dshcompanion.data.clampSettingsNumber
import io.github.westanke.dshcompanion.data.forNativeApp
import io.github.westanke.dshcompanion.data.canWriteSettingsNamespaces
import io.github.westanke.dshcompanion.data.humanizeFieldName
import io.github.westanke.dshcompanion.data.prettySettingsJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import io.github.westanke.dshcompanion.refreshSettingsNamespaces
import io.github.westanke.dshcompanion.updateSettingsNamespace

/*
 * ============================================================================
 * 「工作区设置（来自内核）」：按 schema 自动渲染的通用设置表单
 * ============================================================================
 *
 * 这里的每一个控件都是**运行时**由 `SettingsSchema.fields()` 读出来的，页面里没有
 * 任何一处写着「ui-chat 的 transcriptView 用下拉」。装上第十个插件、它的命名空间
 * 多三个字段，这个页面什么都不用改 —— 那正是本任务的核心要求。
 *
 * 四层降级（桥与 App 分批发版，任何一层都可能缺）：
 * 1. 没有 `settings.read` → 入口置灰 + 一行原因（不弹错误）；
 * 2. 老桥没有 `GET settings/namespaces`（404）→ `state.settingsNamespaces == null`，入口不显示；
 * 3. `hasDocument == false` → 入口可点，点开是空态文案，不报错；
 * 4. 命名空间/部署只读 → 照常展示当前值，但控件禁用 + 一行原因（`HIGH_RISK_NAMESPACE` / `HOST_READONLY`）。
 *
 * 密钥位（`secrets[].path` 命中或值被脱敏成 `***`）**只读展示「已设置/未设置」**：
 * 不给输入框、不参与提交 —— 表单回显密钥是明令禁止的，而且桥也不会接受回写。
 */

/** SettingsScreen 里「外观」与「WebView 聊天」之间的一段入口。 */
@Composable
internal fun SettingsNamespacesSection(
    state: HarnessState,
    viewModel: HarnessViewModel,
    modifier: Modifier = Modifier,
) {
    val scopes = state.device?.scopes.orEmpty()
    val list = state.settingsNamespaces
    var open by remember { mutableStateOf(false) }

    if ("settings.read" !in scopes) {
        // 没有 scope：入口**留着但置灰**，并说明为什么 —— 直接消失会让用户以为 App 没这功能。
        SettingsHeading(stringResource(R.string.settings_ns_heading))
        NamespacesEntry(
            subtitle = stringResource(R.string.settings_ns_permission_required),
            enabled = false,
            onClick = {},
            modifier = modifier,
        )
        return
    }

    // 能看不能存：配对默认拿到的五档 scope 里没有 settings.write，保存必 403 SCOPE_REQUIRED。
    // 与其摆一排「点进去改完点保存没反应」的表单，不如明说缺哪个权限（真机实测，2026-10-22）。
    if (!scopes.canWriteSettingsNamespaces()) {
        SettingsHeading(stringResource(R.string.settings_ns_heading))
        NamespacesEntry(
            subtitle = stringResource(R.string.settings_ns_write_permission_required),
            enabled = false,
            onClick = {},
            modifier = modifier,
        )
        return
    }

    // null = 未读取 / 老桥 404 / 读取失败：入口不显示，也不报错。
    if (list == null) return
    // 只留「在 App 里真起作用」的命名空间：纯 WebUI 外观键与纯只读页不上屏（判据见 data 层）。
    val namespaces = remember(list) { list.namespaces.forNativeApp() }
    // 过滤后一个不剩：没有可改的东西，不占位置。
    if (namespaces.isEmpty()) return

    SettingsHeading(stringResource(R.string.settings_ns_heading))
    NamespacesEntry(
        subtitle = stringResource(R.string.settings_ns_entry_count, namespaces.size),
        enabled = true,
        onClick = { open = true },
        modifier = modifier,
    )

    if (open) {
        SettingsNamespacesSheet(state, viewModel) { open = false }
    }
}

@Composable
private fun NamespacesEntry(
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }
        Icon(Icons.Outlined.Settings, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                stringResource(R.string.settings_ns_entry_title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsNamespacesSheet(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onClose: () -> Unit,
) {
    // 打开即刷新一次：用户可能刚在 WebUI 里改过，列表里的 revision 与值必须是新的，
    // 否则第一次保存必然撞 409（那不是用户的问题，是我们拿了旧快照）。
    LaunchedEffect(Unit) { viewModel.refreshSettingsNamespaces() }
    var detailNs by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                // 三键导航/手势条会盖住最后一行：底部保存键曾落在系统栏下，点击全被系统吃掉。
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            val visible = state.settingsNamespaces?.namespaces?.forNativeApp().orEmpty()
            val detail = visible.firstOrNull { it.ns == detailNs }
            if (detail != null) {
                NamespaceDetail(state, viewModel, detail) { detailNs = null }
            } else {
                NamespaceList(state) { detailNs = it }
            }
        }
    }
}

@Composable
private fun NamespaceList(state: HarnessState, onPick: (String) -> Unit) {
    Text(stringResource(R.string.settings_ns_sheet_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.settings_ns_sheet_intro),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    val list = state.settingsNamespaces
    val visible = remember(list) { list?.namespaces?.forNativeApp().orEmpty() }
    when {
        list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_ns_loading), style = MaterialTheme.typography.bodyMedium)
        }
        visible.isEmpty() -> Text(
            stringResource(if (list.hasDocument) R.string.settings_ns_empty else R.string.settings_ns_no_document),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> {
            if (!list.writable) {
                Text(
                    stringResource(R.string.settings_ns_readonly_deployment),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            visible.forEachIndexed { index, namespace ->
                NamespaceRow(namespace, list.writable, onClick = { onPick(namespace.ns) })
                if (index != visible.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun NamespaceRow(namespace: SettingsNamespace, deploymentWritable: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(namespaceTitle(namespace.ns), style = MaterialTheme.typography.bodyLarge)
            Text(
                namespace.applies?.takeIf { it.isNotBlank() } ?: namespace.ns,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(
                if (namespace.writable && deploymentWritable) {
                    R.string.settings_ns_editable
                } else {
                    R.string.settings_ns_readonly_tag
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

@Composable
private fun NamespaceDetail(
    state: HarnessState,
    viewModel: HarnessViewModel,
    namespace: SettingsNamespace,
    onBack: () -> Unit,
) {
    val deploymentWritable = state.settingsNamespaces?.writable == true
    val editable = namespace.writable && deploymentWritable
    val fields = remember(namespace.ns, namespace.revision, namespace.value) { SettingsSchema.fields(namespace) }
    // 编辑草稿按「命名空间 + revision」重置：保存成功后桥返回的 revision 变了，
    // 草稿要重新从服务端值长出来；反过来，若只按 ns 记，保存后旧草稿会覆盖新值。
    val identity = remember(namespace.ns, namespace.revision) { namespace.ns to namespace.revision }
    var texts by remember(identity) { mutableStateOf(emptyMap<String, String>()) }
    var bools by remember(identity) { mutableStateOf(emptyMap<String, Boolean>()) }

    fun textOf(field: SettingsFieldSpec): String =
        texts[field.key] ?: initialText(field)

    fun boolOf(field: SettingsFieldSpec): Boolean =
        bools[field.key] ?: (field.currentValue as? JsonPrimitive)?.booleanOrNull ?: false

    val numberInvalid = fields.any { field ->
        field.control == SettingsControl.Number &&
            textOf(field).isNotBlank() &&
            textOf(field).trim().toDoubleOrNull() == null
    }
    val ops = if (!editable || numberInvalid) emptyList() else buildOps(fields, ::textOf, ::boolOf)

    TextButton(onClick = onBack, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
        Text(stringResource(R.string.settings_ns_back), Modifier.padding(start = 4.dp))
    }
    Text(namespaceTitle(namespace.ns), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.settings_ns_detail_meta, namespace.ns, namespace.revision),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    if (!editable) {
        WarningBanner(
            reasonText(namespace.reason, deploymentWritable),
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    fields.forEach { field ->
        NamespaceField(
            namespace = namespace,
            field = field,
            enabled = editable,
            text = textOf(field),
            onTextChange = { texts = texts + (field.key to it) },
            checked = boolOf(field),
            onCheckedChange = { bools = bools + (field.key to it) },
            numberInvalid = field.control == SettingsControl.Number &&
                textOf(field).isNotBlank() &&
                textOf(field).trim().toDoubleOrNull() == null,
        )
    }

    if (fields.isEmpty()) {
        Text(
            stringResource(R.string.settings_ns_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Button(
        onClick = { viewModel.updateSettingsNamespace(namespace.ns, ops, namespace.revision) },
        enabled = editable && ops.isNotEmpty() && !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        if (state.busy) {
            CircularProgressIndicator(
                Modifier.padding(end = 8.dp).height(18.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(stringResource(R.string.settings_ns_save))
    }
}

/**
 * 把草稿折成 PATCH 的 ops。
 *
 * 三类字段**永远不提交**：密钥位（只读）、只读 JSON、以及值没变过的项 ——
 * 少发一条 op 就少一次「把用户没动过的值写回服务端」的机会。
 */
private fun buildOps(
    fields: List<SettingsFieldSpec>,
    textOf: (SettingsFieldSpec) -> String,
    boolOf: (SettingsFieldSpec) -> Boolean,
): List<SettingsOp> = fields.mapNotNull { field ->
    when (field.control) {
        SettingsControl.Enum, SettingsControl.Text -> {
            val text = textOf(field)
            if (text == initialText(field)) null else SettingsOp(path = listOf(field.key), value = JsonPrimitive(text))
        }
        SettingsControl.Number -> {
            val parsed = textOf(field).trim().toDoubleOrNull() ?: return@mapNotNull null
            val clamped = clampSettingsNumber(parsed, field.minimum, field.maximum)
            val element = if (field.integer) JsonPrimitive(clamped.toLong()) else JsonPrimitive(clamped)
            if (element == field.currentValue) null else SettingsOp(path = listOf(field.key), value = element)
        }
        SettingsControl.Boolean -> {
            val value = boolOf(field)
            if (value == (field.currentValue as? JsonPrimitive)?.booleanOrNull) {
                null
            } else {
                SettingsOp(path = listOf(field.key), value = JsonPrimitive(value))
            }
        }
        SettingsControl.Secret, SettingsControl.ReadOnly -> null
    }
}

@Composable
private fun NamespaceField(
    namespace: SettingsNamespace,
    field: SettingsFieldSpec,
    enabled: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    numberInvalid: Boolean,
) {
    val title = fieldTitle(namespace.ns, field)
    when (field.control) {
        SettingsControl.Enum -> {
            var menu by remember(field.key) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                field.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { menu = true },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            enumLabel(namespace.ns, field.key, text),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        field.enumValues.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(enumLabel(namespace.ns, field.key, value)) },
                                onClick = {
                                    onTextChange(value)
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        SettingsControl.Boolean -> Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                field.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        SettingsControl.Number, SettingsControl.Text -> OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(title) },
            isError = numberInvalid,
            supportingText = {
                when {
                    numberInvalid -> Text(stringResource(R.string.settings_ns_number_invalid))
                    field.description?.isNotBlank() == true -> Text(field.description.orEmpty())
                    field.control == SettingsControl.Number &&
                        (field.minimum != null || field.maximum != null) -> {
                        Text(
                            stringResource(
                                R.string.settings_ns_range,
                                field.minimum?.let(::formatNumber) ?: "—",
                                field.maximum?.let(::formatNumber) ?: "—",
                            ),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.control == SettingsControl.Number) {
                    KeyboardType.Decimal
                } else {
                    KeyboardType.Text
                },
            ),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        SettingsControl.Secret -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (field.secretSet) R.string.settings_ns_secret_set else R.string.settings_ns_secret_unset,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.settings_ns_secret_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsControl.ReadOnly -> ReadOnlyField(field, title)
    }
}

@Composable
private fun ReadOnlyField(field: SettingsFieldSpec, title: String) {
    var expanded by remember(field.key) { mutableStateOf(false) }
    val content = field.currentValue
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
                Text(
                    stringResource(if (expanded) R.string.settings_ns_collapse else R.string.settings_ns_expand),
                    Modifier.padding(start = 2.dp),
                )
            }
        }
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Text(
                content?.takeIf { it != JsonNull }?.let(::prettySettingsJson)
                    ?: stringResource(R.string.settings_ns_empty_value),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/** 数字的显示格式：整数不带 `.0`，避免「3.0」这种吓人的样子。 */
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun initialText(field: SettingsFieldSpec): String =
    (field.currentValue as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: field.currentValue?.let(::primitiveText)
        ?: (field.defaultValue as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: ""

private fun primitiveText(element: JsonElement): String =
    (element as? JsonPrimitive)?.content ?: element.toString()

@Composable
private fun reasonText(reason: String?, deploymentWritable: Boolean): String = when (reason) {
    "HIGH_RISK_NAMESPACE" -> stringResource(R.string.settings_ns_readonly_high_risk)
    "HOST_READONLY" -> stringResource(R.string.settings_ns_readonly_host)
    else -> stringResource(
        if (!deploymentWritable) R.string.settings_ns_readonly_deployment else R.string.settings_ns_readonly_generic,
    )
}

@Composable
private fun namespaceTitle(ns: String): String = when (ns) {
    "ui-chat" -> stringResource(R.string.settings_ns_name_ui_chat)
    "ui-theme" -> stringResource(R.string.settings_ns_name_ui_theme)
    "ui-conversation" -> stringResource(R.string.settings_ns_name_ui_conversation)
    else -> ns
}

@Composable
private fun fieldTitle(ns: String, field: SettingsFieldSpec): String {
    val override = settingsFieldLabelRes(ns, field.key)
    return if (override != null) {
        stringResource(override)
    } else {
        field.title?.takeIf { it.isNotBlank() } ?: humanizeFieldName(field.key)
    }
}

/** 已知字段的文案覆盖。**认不出的字段照常走 schema title / 人类化**。 */
private fun settingsFieldLabelRes(ns: String, key: String): Int? = when (ns to key) {
    "ui-chat" to "transcriptView" -> R.string.settings_ns_field_transcript_view
    "ui-chat" to "collapseTiming" -> R.string.settings_ns_field_collapse_timing
    "ui-chat" to "performanceUsage" -> R.string.settings_ns_field_performance_usage
    "ui-chat" to "linkOpening" -> R.string.settings_ns_field_link_opening
    "ui-theme" to "fontSize" -> R.string.settings_ns_field_font_size
    "ui-theme" to "preference" -> R.string.settings_ns_field_theme
    "ui-conversation" to "busyEnter" -> R.string.settings_ns_field_busy_enter
    else -> null
}

@Composable
private fun enumLabel(ns: String, key: String, value: String): String {
    val res = settingsEnumLabelRes(ns, key, value)
    return if (res != null) stringResource(res) else value
}

/**
 * 枚举值文案覆盖。**映射不到就显示原值**（英文原文也比「(未知)」有用）。
 *
 * `ui-conversation.busyEnter` 的取值没在桥的仓库里找到权威定义（配置是运行时的），
 * 刻意不猜：猜错会把「插话」显示成「排队」，比显示 `steer` 更误导。
 */
private fun settingsEnumLabelRes(ns: String, key: String, value: String): Int? = when {
    ns == "ui-chat" && key == "transcriptView" -> when (value) {
        "compact" -> R.string.settings_ns_enum_compact
        "standard" -> R.string.settings_ns_enum_standard
        "detailed" -> R.string.settings_ns_enum_detailed
        "verbose" -> R.string.settings_ns_enum_verbose
        else -> null
    }
    ns == "ui-chat" && key == "collapseTiming" -> when (value) {
        "completion" -> R.string.settings_ns_enum_completion
        "next-input" -> R.string.settings_ns_enum_next_input
        else -> null
    }
    ns == "ui-chat" && key == "performanceUsage" -> when (value) {
        "compact" -> R.string.settings_ns_enum_compact
        "detailed" -> R.string.settings_ns_enum_detailed
        else -> null
    }
    ns == "ui-chat" && key == "linkOpening" -> when (value) {
        "sidebar" -> R.string.settings_ns_enum_sidebar
        "new-tab" -> R.string.settings_ns_enum_new_tab
        else -> null
    }
    else -> null
}
