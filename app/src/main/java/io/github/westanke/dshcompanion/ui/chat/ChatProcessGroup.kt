package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.data.ChatDisplayEntry

/**
 * LazyColumn 的稳定 key。
 *
 * 过程组的 key 必须带前缀：组 id 取的是**组内首项的 id**，而那一项在展开时
 * 也会作为普通消息出现（单项平铺分支），两者若共用同一个 key，LazyColumn 会
 * 认为是同一个条目 —— 展开/收起的瞬间会看到内容错位或闪动。
 */
internal fun displayEntryKey(entry: ChatDisplayEntry): String = when (entry) {
    is ChatDisplayEntry.Message -> entry.item.id
    is ChatDisplayEntry.ProcessGroup -> "process-${entry.id}"
}

/**
 * LazyColumn 的 `contentType`：告诉 Compose 哪些条目的**视觉结构**相同。
 *
 * 「消息」与「过程组」的构成完全不同（消息是整块正文 + 附件 + 卡片；
 * 过程组是一行可折叠摘要 + 展开后的列表）。不标的话，滚动时 Compose 会把
 * 消息的 composition 槽位复用给过程组 —— 官方文档明确要求「异质列表必须给
 * contentType」，否则每次复用都要丢掉旧结构、重建新结构，滚动会抖。
 *
 * 纯性能参数：不改视觉、不改布局、不改交互。
 */
internal fun displayEntryContentType(entry: ChatDisplayEntry): String = when (entry) {
    is ChatDisplayEntry.Message -> "message"
    is ChatDisplayEntry.ProcessGroup -> "process"
}

/**
 * 会话列表的一行：**消息**直接渲染，**过程组**渲染成一行可折叠的摘要。
 *
 * ## 为什么要分组
 *
 * 一次工具密集的回合会在会话里留下十几行「运行了代码 / 读取了文件 / 修改了文件」，
 * 把助手正文冲得七零八落。对比对象 Whaleness 的做法是把上下文 / 思考 / 工具调用
 * 收进一个过程组，组标题只留一行摘要（「耗时 1 分 12 秒 · 3 项上下文 · 5 次工具调用」），
 * 正文因此始终是主角。分组算法在 [io.github.westanke.dshcompanion.data.groupChatDisplay]，
 * 这里只管怎么画。
 *
 * ## 为什么单项目不成组
 *
 * 组里只有一行时，折叠它只是让用户**多一次点击**才能看到本来就直接可见的内容。
 * 所以单项走平铺分支 —— 判据放在这里而不是数据层，因为「值不值得折」是观感问题，
 * 而数据层该保持可测的纯粹规则。
 *
 * @param images 附件图片字节（与 [ChatItem] 同一份来源，见 `HarnessState.attachmentImages`）
 */
@Composable
internal fun ChatDisplayEntryRow(
    entry: ChatDisplayEntry,
    images: Map<String, ByteArray>,
    failures: Map<String, String>,
    onLoadImage: (String) -> Unit,
    onAnswer: (String) -> Unit,
    genUiDebug: Boolean,
    onRetry: (() -> Unit)? = null,
    onForkAt: ((Int) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    when (entry) {
        is ChatDisplayEntry.Message -> ChatItem(
            item = entry.item,
            images = images,
            failures = failures,
            onLoadImage = onLoadImage,
            onAnswer = onAnswer,
            genUiDebug = genUiDebug,
            onRetry = onRetry,
            onForkAt = onForkAt,
            onOpenFile = onOpenFile,
        )

        is ChatDisplayEntry.ProcessGroup -> {
            val items = entry.items
            if (items.size <= 1) {
                items.firstOrNull()?.let {
                    ChatItem(
                        item = it,
                        images = images,
                        failures = failures,
                        onLoadImage = onLoadImage,
                        onAnswer = onAnswer,
                        genUiDebug = genUiDebug,
                        onRetry = onRetry,
                        onForkAt = onForkAt,
                        onOpenFile = onOpenFile,
                    )
                }
            } else {
                ProcessGroupRow(entry, images, failures, onLoadImage, onAnswer, genUiDebug, onRetry)
            }
        }
    }
}

/**
 * 过程组的折叠头 + 展开区。
 *
 * 折叠态用 `rememberSaveable` 而不是 `remember`：这张列表会被 LazyColumn 反复回收，
 * 用 `remember` 的话用户展开一组、往下滚两屏再滚回来，它会自己合上 ——
 * 对比对象就踩了这个坑（日程卡用 saveable、思考块用 remember，同一 App 两套口径）。
 * key 用组 id（组内首项 id），保证「同一个组的展开态」在回收前后是同一条。
 */
@Composable
private fun ProcessGroupRow(
    group: ChatDisplayEntry.ProcessGroup,
    images: Map<String, ByteArray>,
    failures: Map<String, String>,
    onLoadImage: (String) -> Unit,
    onAnswer: (String) -> Unit,
    genUiDebug: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    // 箭头跟着展开态转 180°，省掉第二个图标资源的依赖。
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "process-group-arrow",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                // 7dp：5dp 时折叠头（含「耗时 1 分 12 秒 · 3 项上下文」那段摘要）与上下
                // 内容都贴得太近 —— 真机反馈「时间重叠」指的其实是这里，
                // 前几版一直在改消息流与 footer 的间距，找错了对象。
                .padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                group.summary.title(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                group.items.forEach { item ->
                    ChatItem(
                        item = item,
                        images = images,
                        failures = failures,
                        onLoadImage = onLoadImage,
                        onAnswer = onAnswer,
                        genUiDebug = genUiDebug,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}
