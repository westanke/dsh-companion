package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 * 数据源与顶栏 ⋯ 菜单同源：[PermissionSelect]（`history.permissionSelect(catalog)`），
 * 切换动作也与顶栏同源（`selectPermissionPreset`，走 `/permission <preset>` 命令）——
 * Pill 只是**第三个入口**，不是第二套语义。完整访问仍必须过 [FullAccessConfirmDialog]。
 *
 * 位置：输入药丸**上方**的一条窄带（与 ChatStatusBar 同层），点击弹档位菜单。
 *
 * ## 候选项为空时仍要渲染
 *
 * 此前这里有一句 `if (permission.options.isEmpty()) return`，把「拿不到候选项」
 * 升级成「整个入口不渲染」。而 v2.2.44 真机验收实测到的症状正是**这里永不显示**：
 * 当时 App 读的是投影里的 `permissions.options`，内核那条 wire 视图压根没有这个字段，
 * 于是恒为空 → 整条 Pill + ⋯ 菜单的权限项一起消失，而 PARITY 上还标着 `done`。
 *
 * 现在候选项来自独立的目录接口，老 bridge（没有该路由）时列表为空 ——
 * 那时只渲染一个**不可点**的档位标签：用户至少看得见自己当前在什么权限档位上，
 * 切不了是「服务端还没给这个能力」，而不是「界面出故障了」。
 */
@Composable
internal fun PermissionPill(
    permission: PermissionSelect?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (permission == null) return
    val switchable = permission.options.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }

    val current = permission.options.find { it.value == permission.currentValue }
    val label = permissionLabel(permission.currentValue, current?.name)

    // 紧凑形态：**不再独占一行**（原来 `.fillMaxWidth()` 让它在输入区上方占了整条，
    // 用户 2026-10-19 反馈「影响页面可视区域」）。现在它是贴在会话顶栏
    // 「已连接/已断开」后面的一个小胶囊，宽度自适应。
    Box(modifier) {
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(999.dp),
                )
                .clickable(enabled = enabled && switchable) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.tool_permission_pill),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(12.dp),
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
