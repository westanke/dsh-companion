package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.westanke.dshcompanion.R

/**
 * 列表顶部的「加载更早消息」按钮：负责**加载态**与**视口锚定**两件事。
 *
 * ## 为什么需要锚定（2026-10-22 真机反馈）
 *
 * 用户原话：「点击加载更早消息，如果历史消息中有过多的工具调用，导致历史对话消息
 * 加载不出来，历史工具调用加载了，但是观看感知差了」。
 *
 * 症状不是"没加载"，而是**加载完视口跳到了别处**：`LazyColumn` 的滚动位置是
 * **按 index** 记的，往前面插入 N 条之后，同一个 index 现在指向更早的内容，
 * 于是整屏内容被顶走 —— 用户落在一片折叠的过程组（「耗时 … · N 次工具调用」）里，
 * 正文在屏幕之外，看起来就像"对话没加载出来，只加载了工具调用"。
 *
 * 做法：点击时记下当前第一条可见项的 index / 像素偏移 / 条目总数；
 * 数据变长后把 index 加回增量再 `scrollToItem`，用户视线原地不动，
 * 新增内容出现在**上方**（与 Telegram / Twitter 载入更早内容的行为一致），
 * 想读继续往上滑即可。
 *
 * ## 为什么锚点是 [entryCount] 而不是完整列表
 *
 * 渲染用的是**分组后**的条目（消息 + 折叠过程组），一条消息进来可能让组内的
 * 工具计数变化而不改变条目数 —— 那不影响定位；我们只需要「插进来多少条」。
 * 拿列表做 diff 反而会在流式期间白算一遍。
 *
 * ## 加载态为什么不用 `state.busy`
 *
 * `busy` 是全局的（发消息、刷历史都会置真），拿它当按钮文案会让「正在加载更早消息」
 * 在别处操作时乱闪。这里用一个随会话重置的局部标志，只描述这一次点击。
 */
@Composable
internal fun OlderMessagesButton(
    listState: LazyListState,
    entryCount: Int,
    sessionId: String,
    onLoad: () -> Unit,
) {
    var anchor by remember(sessionId) { mutableStateOf<OlderAnchor?>(null) }
    var loading by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(entryCount) {
        val current = anchor ?: return@LaunchedEffect
        anchor = null
        loading = false
        val added = entryCount - current.entryCount
        // added <= 0：这一拍没拉到新内容（到底了、请求失败、或只有元数据变化）——
        // 保持原位，绝不能把正在读历史的用户弹走。
        if (added > 0) listState.scrollToItem(current.index + added, current.offset)
    }

    TextButton(
        onClick = {
            anchor = OlderAnchor(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                entryCount = entryCount,
            )
            loading = true
            onLoad()
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (loading) R.string.older_messages_loading else R.string.older_messages,
            ),
        )
    }
}

/** 点击「加载更早消息」那一刻的视口锚：第一条可见项的 index / 像素偏移 + 当时条目总数。 */
private data class OlderAnchor(val index: Int, val offset: Int, val entryCount: Int)
