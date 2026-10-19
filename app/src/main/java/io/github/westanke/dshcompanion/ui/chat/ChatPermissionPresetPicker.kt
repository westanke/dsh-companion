package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.data.PermissionOption

/**
 * 创建会话抽屉里的**权限档位选择器**（从 `ChatSheets.kt` 拆出，行数预算）。
 *
 * 用户 2026-10-19 要求「创建会话时就可以改权限」：选好档位，会话建好后立刻用
 * `/permission` 命令切过去（与在会话中手动切是同一套机制，见
 * `HarnessSessionsViewModel.applyPermissionPreset`）。
 *
 * 与 `PermissionMenuItems`（会话页 ⋯ 菜单里那套）刻意不同形：这里是**表单控件**
 * （下拉选择，默认值可见），那边是**菜单项**（点击即切）。同一份档位数据，两种交互。
 *
 * @param options 为空时整个不渲染 —— 老 bridge 没有目录接口（404），
 *   显示一个点了没反应的下拉框比不显示更糟。
 */
@Composable
internal fun PermissionPresetPicker(
    selected: String,
    options: List<PermissionOption>,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.Lock, null)
            Text(
                permissionLabel(selected, options.find { it.value == selected }?.name),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(permissionLabel(option.value, option.name))
                            permissionDescription(option.value, option.description)?.takeIf(String::isNotBlank)?.let { description ->
                                Text(
                                    description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    onClick = { onSelect(option.value); menuOpen = false },
                )
            }
        }
    }
}
