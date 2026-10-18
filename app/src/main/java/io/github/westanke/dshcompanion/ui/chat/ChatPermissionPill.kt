package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.PermissionSelect

/**
 * 发送区常驻的**权限档位 Pill**（对标官方 Web 的 PermissionIcon 系列与「档位 Pill」设计，
 * 《官方Web界面与APP功能对比 2026-10-18》第一梯队②）。
 *
 * 数据源与顶栏 ⋯ 菜单同源：[PermissionSelect]（`history.permissionSelect()` 投影），
 * 切换动作也与顶栏同源（`selectPermissionPreset`，走 `/permission <preset>` 命令）——
 * Pill 只是**第三个入口**，不是第二套语义。完整访问仍必须过 [FullAccessConfirmDialog]。
 *
 * 位置：输入药丸**上方**的一条窄带（与 ChatStatusBar 同层），点击弹档位菜单。
 * 没有权限投影（无历史 / options 空）时整条不渲染 —— 不显示占位，不留一行空白。
 */
@Composable
internal fun PermissionPill(
    permission: PermissionSelect?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (permission == null || permission.options.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }

    val current = permission.options.find { it.value == permission.currentValue }
    val label = permissionLabel(permission.currentValue, current?.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(999.dp),
                )
                .clickable(enabled = enabled) { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.tool_permission_pill),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            PermissionMenuItems(
                value = permission,
                enabled = enabled,
                onSelect = { selected ->
                    menuOpen = false
                    onSelect(selected)
                },
                onRequestFullAccess = {
                    menuOpen = false
                    confirmFullAccess = true
                },
            )
        }
    }
    if (confirmFullAccess) {
        FullAccessConfirmDialog(
            onDismiss = { confirmFullAccess = false },
            onConfirm = {
                confirmFullAccess = false
                onSelect("danger-full-access")
            },
        )
    }
}
