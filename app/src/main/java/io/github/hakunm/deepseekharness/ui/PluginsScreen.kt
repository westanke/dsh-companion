package io.github.hakunm.deepseekharness.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val canWrite = "settings.write" in state.device?.scopes.orEmpty()
    val inventory = state.pluginInventory
    val context = LocalContext.current
    // 连接时的快照通常已经带来清单；只有拿不到（首次进入此页、上一次读取失败）时才补一次请求。
    LaunchedEffect(canRead, inventory) {
        if (canRead && inventory == null) viewModel.refreshPluginInventory()
    }
    // 待确认的启停操作。**改 profile 的 package.json 是有后果的操作**（写坏了 dsh
    // 下次起不来），所以不在开关上直接执行，先让用户看清要动的是哪个插件。
    var pendingToggle by remember { mutableStateOf<PendingPluginToggle?>(null) }
    pendingToggle?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingToggle = null },
            title = {
                Text(
                    stringResource(
                        R.string.plugins_toggle_confirm_title,
                        stringResource(if (pending.enable) R.string.plugins_toggle_enable else R.string.plugins_toggle_disable),
                        pending.name,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (pending.enable) R.string.plugins_toggle_confirm_enable
                        else R.string.plugins_toggle_confirm_disable,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingToggle = null
                    viewModel.setPluginLoaded(pending.name, pending.enable)
                }) {
                    Text(stringResource(if (pending.enable) R.string.plugins_toggle_enable else R.string.plugins_toggle_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingToggle = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
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
                else -> InventoryContent(
                    inventory = inventory,
                    canWrite = canWrite,
                    busy = state.busy,
                    context = context,
                    onToggle = { name, enable -> pendingToggle = PendingPluginToggle(name, enable) },
                    onCheckUpdates = viewModel::refreshPluginInventoryWithUpdates,
                )
            }
        }
    }
}

/** 待确认的一次启停。 */
private data class PendingPluginToggle(val name: String, val enable: Boolean)

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
private fun InventoryContent(
    inventory: PluginInventory,
    canWrite: Boolean,
    busy: Boolean,
    context: Context,
    onToggle: (String, Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
) {
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
            // 「检查更新」放在这里而不是顶栏：它是**重活**（每个包一次 registry 请求，
            // 清单会从毫秒级变成好几秒），与顶栏那个「刷新」不是一类动作，并列会让人
            // 以为两个按钮一样快。点之前也先用一句话说清代价。
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCheckUpdates, enabled = !busy) {
                Text(stringResource(R.string.plugins_check_updates))
            }
        }
    }
    if (inventory.problemCount > 0) {
        Spacer(Modifier.height(14.dp))
        WarningBanner(stringResource(R.string.plugins_problem_banner, inventory.problemCount))
    }
    // 有可启停的插件时才提「要重启」——一个只读清单页说这句话是多余的。
    if (inventory.items.any { it.toggleable }) {
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.plugins_restart_hint),
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
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
                    PluginRow(
                        entry = entry,
                        canWrite = canWrite,
                        busy = busy,
                        context = context,
                        onToggle = onToggle,
                    )
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
private fun PluginRow(
    entry: PluginEntry,
    canWrite: Boolean,
    busy: Boolean,
    context: Context,
    onToggle: (String, Boolean) -> Unit,
) {
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
            // 简介：手机上只有一串 @scope/name@1.2.3 时，用户没法判断「这个能不能停用」，
            // 描述就是那个判断的主要依据 —— 官方清单页也把它放在这一行。
            entry.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
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
            // 更新提示只在**确实有新版本**时出现；`updateAvailable == null`（没查）
            // 什么都不显示 —— 显示「已是最新」是撒谎，显示「未知」是噪音。
            if (entry.updateAvailable == true && entry.latestVersion != null) {
                Text(
                    stringResource(R.string.plugins_update_available, entry.latestVersion),
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entry.homepage?.takeIf { it.isNotBlank() }?.let { homepage ->
                Text(
                    stringResource(R.string.plugins_homepage),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { openUrl(context, homepage) },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 不能启停的原因要说出来，否则用户会去找一个根本不存在的开关。
            if (!entry.toggleable) {
                Text(
                    when {
                        entry.official -> stringResource(R.string.plugins_toggle_official_locked)
                        entry.state == STATE_DECLARED_MISSING -> stringResource(R.string.plugins_toggle_missing_locked)
                        else -> stringResource(R.string.plugins_toggle_not_installed_locked)
                    },
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            PluginStateBadge(entry.state)
            // 开关只在「能启停 + 有写权限」时出现。没权限时**不画一个点了会失败的开关**，
            // 而是在页面顶部由 canRead 那条横幅说明缺什么 —— 两处都提示反而啰嗦。
            if (entry.toggleable && canWrite) {
                Spacer(Modifier.height(3.dp))
                Switch(
                    checked = entry.loaded,
                    onCheckedChange = { next -> onToggle(entry.name, next) },
                    enabled = !busy,
                )
            }
        }
    }
}

/**
 * 用系统浏览器打开插件主页。
 *
 * 放进 `runCatching`：主页字段来自第三方包的 package.json，值可能是系统不认的
 * scheme、或这台设备上没有浏览器 —— 为一次跳转把页面崩掉不值得。
 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
