package io.github.hakunm.deepseekharness.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.data.PluginEntry
import io.github.hakunm.deepseekharness.data.PluginInventory

/**
 * 服务端约定的四种插件状态。
 *
 * `runtime-provided` 是官方包随 DSH 运行时自带的正常状态（它们不在 profile 的
 * node_modules 里），服务端已把它排除在 problemCount 之外；UI 若不认识它，
 * 一台健康的机器会冒出一排「状态未知」。未知取值走兜底，不在解码层抛错。
 */
private const val STATE_LOADED = "loaded"
private const val STATE_RUNTIME_PROVIDED = "runtime-provided"
private const val STATE_INSTALLED_NOT_LOADED = "installed-not-loaded"
private const val STATE_DECLARED_MISSING = "declared-missing"

/**
 * 插件清单页。
 *
 * 「声明了要加载、实际却缺失」（declared-missing）是这里最需要提醒用户的异常状态：
 * 对应的功能会静默不可用，而插件是否加载只由服务器决定，App 无法代劳。
 * 因此问题条数用警告色 + 提示条单独顶出来，而不是混在普通列表里。
 */
@Composable
fun PluginsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    val canRead = "settings.read" in state.device?.scopes.orEmpty()
    val inventory = state.pluginInventory
    // 连接时的快照通常已经带来清单；只有拿不到（首次进入此页、上一次读取失败）时才补一次请求。
    LaunchedEffect(canRead, inventory) {
        if (canRead && inventory == null) viewModel.refreshPluginInventory()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugins), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.plugins_subtitle),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = viewModel::refreshPluginInventory, enabled = canRead && !state.busy) {
                    Icon(Icons.Outlined.Refresh, null)
                    Text(stringResource(R.string.refresh), Modifier.padding(start = 5.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            when {
                !canRead -> WarningBanner(stringResource(R.string.plugins_permission_required))
                inventory == null -> LoadingInventory()
                !inventory.available -> UnavailableInventory(inventory)
                else -> InventoryContent(inventory)
            }
        }
    }
}

@Composable
private fun LoadingInventory() {
    Row(
        Modifier.fillMaxWidth().height(92.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) { CircularProgressIndicator(strokeWidth = 2.dp) }
}

/**
 * `available: false`：服务器给不出清单，界面必须解释原因，而不是留一片空白。
 */
@Composable
private fun UnavailableInventory(inventory: PluginInventory) {
    WarningBanner(unavailableReason(inventory.reason))
    inventory.profile?.takeIf { it.isNotBlank() }?.let { profile ->
        Text(
            stringResource(R.string.plugins_profile, profile),
            modifier = Modifier.padding(top = 14.dp, start = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun unavailableReason(reason: String?): String = when (reason) {
    "PROFILE_UNKNOWN" -> stringResource(R.string.plugins_unavailable_profile_unknown)
    "MANIFEST_UNREADABLE" -> stringResource(R.string.plugins_unavailable_manifest_unreadable)
    else -> stringResource(R.string.plugins_unavailable_generic, reason ?: "UNKNOWN")
}

@Composable
private fun InventoryContent(inventory: PluginInventory) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            inventory.profile?.takeIf { it.isNotBlank() }?.let { profile ->
                Text(stringResource(R.string.plugins_profile, profile), style = MaterialTheme.typography.titleMedium)
            }
            inventory.profilePath?.takeIf { it.isNotBlank() }?.let { profilePath ->
                Text(
                    profilePath,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                InventoryStat(stringResource(R.string.plugins_loaded_stat), inventory.loadedCount, alert = false)
                InventoryStat(stringResource(R.string.plugins_problem_stat), inventory.problemCount, alert = inventory.problemCount > 0)
            }
        }
    }
    if (inventory.problemCount > 0) {
        Spacer(Modifier.height(14.dp))
        WarningBanner(stringResource(R.string.plugins_problem_banner, inventory.problemCount))
    }
    Spacer(Modifier.height(22.dp))
    SectionPanel(title = R.string.plugins_list_title) {
        if (inventory.items.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                inventory.items.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PluginRow(entry)
                }
            }
        }
    }
}

@Composable
private fun InventoryStat(label: String, value: Int, alert: Boolean) {
    Column {
        Text(
            value.toString(),
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            label,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PluginRow(entry: PluginEntry) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (entry.official) {
                    Spacer(Modifier.width(8.dp))
                    OfficialBadge()
                }
            }
            val rawInstalled = entry.installed
            val installed = when {
                rawInstalled != null -> stringResource(R.string.plugins_installed_version, rawInstalled)
                // 官方包不在 profile 的 node_modules 里，报「版本未知」会制造假警报。
                entry.state == STATE_RUNTIME_PROVIDED -> stringResource(R.string.plugins_version_runtime_provided)
                else -> stringResource(R.string.plugins_installed_version, stringResource(R.string.plugins_version_unknown))
            }
            Text(
                installed,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            entry.declared?.let { declared ->
                Text(
                    stringResource(R.string.plugins_declared, declared),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        PluginStateBadge(entry.state)
    }
}

@Composable
private fun OfficialBadge() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(R.string.plugins_official),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PluginStateBadge(state: String) {
    val (label, container, content) = when (state) {
        STATE_LOADED -> Triple(
            R.string.plugins_state_loaded,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        STATE_INSTALLED_NOT_LOADED -> Triple(
            R.string.plugins_state_installed_not_loaded,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        STATE_RUNTIME_PROVIDED -> Triple(
            R.string.plugins_state_runtime_provided,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        STATE_DECLARED_MISSING -> Triple(
            R.string.plugins_state_declared_missing,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> Triple(
            R.string.plugins_state_unknown,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(label),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = content,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
