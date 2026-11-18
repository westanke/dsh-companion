package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.ui.theme.DshSpacing
import io.github.westanke.dshcompanion.ui.theme.DshTypography
import io.github.westanke.dshcompanion.ui.theme.LocalDshColors

/**
 * 设置页的**官方卡片式**构件。
 *
 * 与 dsh-Chat 的 `app/lib/ui/settings_widgets.dart` 里的 `_Section` / `_InfoRow` /
 * `_SwitchRow` / `_SegmentedRow` / `_ScopeChip` 一一对应，量值取自
 * `DshSpacing` 与 `DshTypography`（官方 token），不写裸数字。
 *
 * ## 为什么要单独一个文件
 *
 * 设置页的卡片观感是「一块填充色 + 14dp 圆角 + 行内 16/6dp 内距」，
 * 这套节奏一旦散落到 `SettingsScreen.kt` 里逐行写死，下次改版式就得整页翻。
 * 抽到这里之后，版式只有一处定义；`SettingsNamespacesScreen.kt` 也在用同一批
 * `SettingsHeading`，改标题口径时两处不会漂移。
 */

/**
 * 一个分组：标题（次级色小标题）+ 一张卡片（纯填充色，无边框）。
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dsh = LocalDshColors.current
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = dsh.labelSecondary,
            style = DshTypography.smallStrong,
        )
        Surface(
            shape = RoundedCornerShape(DshSpacing.settingsCardRadius),
            color = dsh.settingsSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * 卡片里的一行「图标 + 标题 + 副标题」。
 *
 * 官方口径：图标 20dp 品牌蓝，图标与文字间距 [DshSpacing.settingsIconGap]，
 * 标题 base（16/28）主色，副标题 small（14/24）三级色，最多两行。
 */
@Composable
internal fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dsh = LocalDshColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = DshSpacing.settingsCardPadH,
                vertical = DshSpacing.settingsCardPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = dsh.brand,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(DshSpacing.settingsIconGap))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = dsh.labelPrimary,
                style = DshTypography.base,
            )
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = dsh.labelTertiary,
                style = DshTypography.small,
            )
        }
    }
}

/**
 * 卡片里的一行「标题 + 说明 + 开关」。
 *
 * 官方口径：标题 base 主色，说明 caption 三级色，开关品牌蓝。
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsh = LocalDshColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = DshSpacing.settingsCardPadH,
                vertical = DshSpacing.settingsCardPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = dsh.labelPrimary, style = DshTypography.base)
            Text(
                description,
                color = dsh.labelTertiary,
                style = DshTypography.caption,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = dsh.onBrand,
                checkedTrackColor = dsh.brand,
            ),
        )
    }
}

/**
 * 卡片里的一行「标题 + 说明 + 分段选择」。
 *
 * 官方口径：选中段用 chipSelectedBg 底 + chipSelectedFg 字，未选中段用 settingsField 底；
 * 分段控件横向可滚动，档位多了也不换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> SettingsSegmentedRow(
    label: String,
    description: String?,
    values: List<T>,
    selected: T,
    labels: List<String>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsh = LocalDshColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = DshSpacing.settingsCardPadH,
                vertical = DshSpacing.settingsCardPadV,
            ),
    ) {
        Text(label, color = dsh.labelPrimary, style = DshTypography.base)
        if (description != null) {
            Text(description, color = dsh.labelTertiary, style = DshTypography.caption)
        }
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            values.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, values.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = dsh.chipSelectedBg,
                        activeContentColor = dsh.chipSelectedFg,
                        inactiveContainerColor = dsh.settingsField,
                        inactiveContentColor = dsh.labelPrimary,
                    ),
                ) { Text(labels[index], style = DshTypography.small) }
            }
        }
    }
}

/**
 * 卡片里的一组小药丸（设备授权 scope 等）。
 *
 * 官方口径：圆角 16dp、settingsField 底色、caption 二级色文字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsChipGroup(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val dsh = LocalDshColors.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.settingsChipGap),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.settingsChipGap),
    ) {
        labels.forEach { label ->
            Surface(
                shape = RoundedCornerShape(DshSpacing.settingsChipRadius),
                color = dsh.settingsField,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(
                        horizontal = DshSpacing.settingsChipPadH,
                        vertical = DshSpacing.settingsChipPadV,
                    ),
                    color = dsh.labelSecondary,
                    style = DshTypography.caption,
                )
            }
        }
    }
}

/**
 * 卡片里的官方警告条。
 *
 * 对应 dsh-Chat `app/lib/ui/settings_widgets.dart` 的 `_WarningBanner`：
 * 一块 settingsWarnBg 的填充色块 + 6dp 圆角（`DsSpacing.inlineCodeRadius`），
 * 文字用 settingsWarnFg 的 small 号，**不带图标**，上下左右内距 12/10dp。
 *
 * ## 为什么不用 `Components.kt` 里的 `WarningBanner`
 *
 * 那个是旧版通用横幅（带 `WarningAmber` 图标、走语义色 warnContainer），
 * 被 PluginsScreen / ConnectScreen / ModelsScreen 等九处复用，改它会波及设置页之外的
 * 观感。官方设置页的警告条是**卡片内、无图标**的小色块，所以单独实现一份。
 *
 * 官方卡片内**没有分隔线**（`grep -rn "Divider" app/lib/ui/settings_*.dart` 无命中），
 * 因此这里不再提供 `SettingsDivider`。
 */
@Composable
internal fun SettingsWarningBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    val dsh = LocalDshColors.current
    Box(
        modifier
            .padding(
                horizontal = DshSpacing.settingsCardPadH,
                vertical = DshSpacing.settingsCardPadV,
            )
            .fillMaxWidth()
            .background(
                color = dsh.settingsWarnBg,
                shape = RoundedCornerShape(DshSpacing.inlineCodeRadius),
            )
            .padding(
                horizontal = DshSpacing.settingsWarnPadH,
                vertical = DshSpacing.settingsWarnPadV,
            ),
    ) {
        Text(
            message,
            color = dsh.settingsWarnFg,
            style = DshTypography.small,
        )
    }
}
