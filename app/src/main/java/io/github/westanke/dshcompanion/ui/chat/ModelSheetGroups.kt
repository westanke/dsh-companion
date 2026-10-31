package io.github.westanke.dshcompanion.ui.chat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.ModelView
import io.github.westanke.dshcompanion.data.formatContextWindow

/**
 * 模型弹窗里「按供应商折叠」的那一半：[ModelGroupExpansion] 记住谁展开，
 * [ModelProviderHeader] 画折叠头，[ModelRow] 画单个模型。
 *
 * 拆成独立文件是因为 `ChatSheets.kt` 已贴着 800 行硬预算，而折叠逻辑本身
 * （默认展开谁、点了怎么切、当前选中在哪家）与工作区管理、新建会话两个弹层毫无关系。
 */

/**
 * 供应商展开态。
 *
 * 用 `mutableStateMapOf` 而不是 `Map` 是因为折叠头要逐行订阅自己那一位——
 * 展开一家不该让另外几十家一起重组；这里刻意**不**做成 `State<Map>` 整包替换。
 */
@Stable
internal class ModelGroupExpansion {
    private val open = mutableStateMapOf<String, Boolean>()

    fun isOpen(groupId: String): Boolean = open[groupId] == true

    fun toggle(groupId: String) {
        open[groupId] = !isOpen(groupId)
    }

    /** 只留一家展开；[groupId] 为空时全部收起。 */
    fun openOnly(groupId: String?) {
        open.clear()
        if (groupId != null) open[groupId] = true
    }
}

/**
 * 展开态只在**当前选中模型的供应商变了**时重置。
 *
 * 不能用 `remember(groups)`：每次切模型 ViewModel 都会重拉 sessionModels，
 * `groups` 是新 List 实例，那样用户手动展开的供应商会被服务端的一次刷新悄悄收起来。
 * 而用户只是展开浏览（没选模型）时 `currentProvider` 不变，折叠态也就不动。
 */
@Composable
internal fun rememberModelGroupExpansion(currentProvider: String?): ModelGroupExpansion {
    val expansion = remember { ModelGroupExpansion() }
    LaunchedEffect(currentProvider) { expansion.openOnly(currentProvider) }
    return expansion
}

/**
 * 一个供应商的折叠头：名字 · 模型数 ·（若当前选中藏在这家）「当前」标记 · 展开箭头。
 *
 * 收起后仍要能看出选的是哪家，否则用户折起来就"失忆"了——所以 [holdsCurrent]
 * 的标记画在头上而不是只靠展开后的行高亮。
 */
@Composable
internal fun ModelProviderHeader(
    name: String,
    modelCount: Int,
    expanded: Boolean,
    holdsCurrent: Boolean,
    onToggle: () -> Unit,
) {
    // 没有模型可展开的供应商：头照画（不然用户会以为这家消失了），但点不动。
    val clickable = modelCount > 0
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "model-group-arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = clickable, onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            if (modelCount > 0) stringResource(R.string.model_group_count, modelCount)
            else stringResource(R.string.model_group_empty),
            modifier = Modifier.padding(start = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        if (holdsCurrent) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                stringResource(R.string.model_group_current),
                modifier = Modifier.padding(start = 2.dp),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.model_group_collapse)
            else stringResource(R.string.model_group_expand),
            modifier = Modifier.size(20.dp).rotate(arrow),
            tint = if (clickable) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

/**
 * 单个模型行。
 *
 * 与改造前的区别只有两点：不再自己拿 `viewModel` 发请求（选择动作交给
 * [ModelSheet][ModelSheet] 统一做防抖与乐观高亮），以及 [pending] 时行尾用
 * 转圈代替对勾——让「点下去了但服务端还没确认」这件事在界面上有交代。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelRow(
    providerId: String,
    model: ModelView,
    selected: Boolean,
    current: ModelSelection,
    pending: Boolean,
    onSelect: (ModelSelection) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSelect(ModelSelection(providerId, model.id, model.reasoning?.defaultEffort)) }
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                model.description?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                model.contextWindow?.let {
                    Text(
                        stringResource(R.string.model_context_window, formatContextWindow(it)),
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            when {
                pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                selected -> Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.secondary)
            }
        }
        if (selected && !model.reasoning?.efforts.isNullOrEmpty()) {
            Text(
                stringResource(R.string.reasoning_effort),
                modifier = Modifier.padding(top = 12.dp, bottom = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                model.reasoning.efforts.forEach { effort ->
                    val active = current.reasoningEffort == effort.id
                    Surface(
                        onClick = { onSelect(ModelSelection(providerId, model.id, effort.id)) },
                        shape = CircleShape,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(effort.name, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}
