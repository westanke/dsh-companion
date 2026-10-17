package io.github.westanke.dshcompanion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.westanke.dshcompanion.R

/**
 * 「源码 / 预览」两个切换键。
 *
 * 抽出来是因为 Markdown 与 HTML 各要一对、两对逻辑一模一样 —— 留在
 * `FilesScreen.kt` 里会让那个文件越过 800 行预算（它是本项目行数上限的硬门），
 * 而预算被突破就只能进债务台账，为两个图标不值得。
 */
@Composable
internal fun PreviewToggleButtons(
    showPreview: Boolean,
    onShowSource: () -> Unit,
    onShowPreview: () -> Unit,
) {
    IconButton(onClick = onShowSource) {
        Icon(
            Icons.Outlined.Code,
            stringResource(R.string.markdown_source),
            tint = if (!showPreview) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    IconButton(onClick = onShowPreview) {
        Icon(
            Icons.Outlined.Visibility,
            stringResource(R.string.markdown_preview),
            tint = if (showPreview) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
