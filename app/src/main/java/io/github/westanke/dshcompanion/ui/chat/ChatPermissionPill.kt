package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.PermissionSelect
import io.github.westanke.dshcompanion.ui.SemanticErrContainer
import io.github.westanke.dshcompanion.ui.SemanticInfoContainer
import io.github.westanke.dshcompanion.ui.SemanticWarnContainer
import io.github.westanke.dshcompanion.ui.OnSemanticErrContainer
import io.github.westanke.dshcompanion.ui.OnSemanticInfoContainer
import io.github.westanke.dshcompanion.ui.OnSemanticWarnContainer

/**
 * 权限档位的**语义色调**（2026-10-30 用户要求：三档权限分颜色、图标对应）。
 *
 * - 只读 = 信息蓝（Lock）：锁住但无害，蓝色是「知情」色；
 * - 工作区写入 = 警告琥珀（Edit）：能改文件，需要留意；
 * - 完整访问 = 危险红（WarningAmber）：无审批全权，红色是「刹车」色；
 * - 未知/自定义档位退回中性灰，不冒充认识它。
 *
 * 图标本身沿用既有的 [permissionIcon]（菜单与 Pill 同一套，避免两处漂移）。
 */
internal enum class PermissionTone { INFO, WARN, DANGER, NEUTRAL }

/** 纯函数：档位 value → 色调；UI 不可测的部分（取色）留在 @Composable 层。 */
internal fun permissionTone(value: String?): PermissionTone = when (value) {
    "read-only" -> PermissionTone.INFO
    "workspace-write" -> PermissionTone.WARN
    "danger-full-access" -> PermissionTone.DANGER
    else -> PermissionTone.NEUTRAL
}

/**
 * 发送区常驻的**权限档位 Pill**（对标官方 Web 的 PermissionIcon 系列与「档位 Pill」设计，
 * 《官方Web界面与APP功能对比 2026-10-18》第一梯队②）。
 *
 * 数据源与顶栏 ⋯ 菜单同源：[PermissionSelect]（`history.permissionSelect(catalog)`），
 * 切换动作也与顶栏同源（`selectPermissionPreset`，走 `/permission <preset>` 命令）——
 * Pill 只是**第三个入口**，不是第二套语义。完整访问仍必须过 [FullAccessConfirmDialog]。
 *
 * 位置：会话顶栏标题副行，「已连接/已断开」状态之后的小胶囊，点击弹档位菜单。
 *
 * ## 颜色与图标随档位走（2026-10-30）
 *
 * 三档各有语义色容器底 + 同色实心图标：只读蓝、工作区写入琥珀、完整访问红；
 * 未知档位保持中性灰。颜色取 App 统一语义色板（明暗双主题均已调校），不硬编码 hex。
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

    val container: Color
    val content: Color
    when (permissionTone(permission.currentValue)) {
        PermissionTone.INFO -> {
            container = SemanticInfoContainer
            content = OnSemanticInfoContainer
        }
        PermissionTone.WARN -> {
            container = SemanticWarnContainer
            content = OnSemanticWarnContainer
        }
        PermissionTone.DANGER -> {
            container = SemanticErrContainer
            content = OnSemanticErrContainer
        }
        PermissionTone.NEUTRAL -> {
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    // 禁用态（连接不可写/无候选项）只降内容色透明度，底色保留——档位仍一眼可辨。
    val tint = if (enabled) content else content.copy(alpha = 0.5f)

    Box(modifier) {
        Row(
            modifier = Modifier
                .background(container, RoundedCornerShape(999.dp))
                .clickable(enabled = enabled && switchable) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                permissionIcon(permission.currentValue),
                contentDescription = stringResource(R.string.tool_permission_pill),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(12.dp),
                tint = tint,
            )
            Text(
                label,
                color = tint,
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
