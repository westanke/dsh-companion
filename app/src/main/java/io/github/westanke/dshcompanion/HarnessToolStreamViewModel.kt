package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ToolStreamEntry
import io.github.westanke.dshcompanion.data.ToolStreamPhase
import io.github.westanke.dshcompanion.data.ToolStreamState
import io.github.westanke.dshcompanion.data.toolResultCallIds
import io.github.westanke.dshcompanion.data.toolStreamDisplayItem
import io.github.westanke.dshcompanion.data.withoutToolCalls

/**
 * 实时工具行 → 会话详情页的展示项。
 *
 * 形状照 [HarnessState.truncationItems]（同一类「状态 → 展示项」的扩展），
 * 差别有两处：
 *
 * 1. **让位判据**：历史里**连结果都有**了（`tool/call` + `tool/result`），
 *    历史那一行才是完整的，实时行就撤掉。历史里只有调用、还没结果时，
 *    实时行继续顶着 —— 它带着结果与「正在跑」的状态。
 * 2. **阶段文案**：还在跑的调用带一句「准备调用工具」/「正在调用工具」，
 *    跟官方 WebUI 的口径一致（`message.stepProcess.prepare.tools` /
 *    `message.stepProcess.tools`）。已经出结果的行不带阶段前缀 ——
 *    它的措辞跟历史里的工具行一样。
 *
 * @param stageLabel 阶段 → 文案。返回 null = 这一阶段不加前缀。
 */
internal fun HarnessState.toolStreamItems(
    sessionId: String?,
    stageLabel: (ToolStreamPhase) -> String?,
): List<ChatDisplayItem> {
    val stream = toolStream ?: return emptyList()
    if (sessionId == null || stream.sessionId != sessionId) return emptyList()
    val covered = history?.toolResultCallIds().orEmpty()
    return stream.entries
        .filterNot { it.callId in covered }
        .map { toolStreamDisplayItem(it, stageLabel(it.phase)) }
}

/**
 * 会话详情页的完整消息列表 = 历史 + 实时工具行 + 流式正文 + 截断提示。
 *
 * 同一次工具调用**只能出现一行**：实时行在跑的时候，历史里那一条（还没结果）
 * 会把同一 `callId` 的明细摘掉；等历史里连结果都齐了，实时行自己撤掉，
 * 历史那一行接管。摘完明细一条不剩的行整行丢掉 —— 它整行就是在讲这次调用。
 */
internal fun HarnessState.chatItems(
    viewModel: HarnessViewModel,
    sessionId: String?,
    historyItems: List<ChatDisplayItem>,
    liveItems: List<ChatDisplayItem>,
): List<ChatDisplayItem> {
    val live = toolStreamItems(sessionId) { viewModel.toolStreamLabel(it) }
    val liveCallIds = live.flatMap { item -> item.tools.map { it.callId } }.toSet()
    return historyItems.withoutToolCalls(liveCallIds) + live + liveItems + truncationItems(sessionId)
}

/**
 * 实时工具调用的阶段文案。
 *
 * 走 [HarnessViewModel.appString] 而不是 `stringResource`：这一层不在 Compose 里，
 * 而阶段文案要跟着**界面语言**走（AGENTS.md §4.1：语言是本地的事）。
 */
internal fun HarnessViewModel.toolStreamLabel(phase: ToolStreamPhase): String? = when (phase) {
    ToolStreamPhase.PREPARING -> appString(R.string.tool_preparing)
    ToolStreamPhase.RUNNING -> appString(R.string.tool_running)
    ToolStreamPhase.DONE -> null
}

/**
 * 把一帧实时工具事件并进状态。
 *
 * 只收**当前会话**的帧：状态里只放一份工具流（带 sessionId），
 * 切会话时清空（见 `HarnessSessionsViewModel.selectSession`），
 * 别的会话的帧进来只会把这份状态冲掉。
 */
internal fun HarnessViewModel.applyToolStreamEntry(sessionId: String?, entry: ToolStreamEntry?) {
    if (entry == null || sessionId == null) return
    if (sessionId != mutableState.value.selectedSessionId) return
    update {
        val current = toolStream
        val base = if (current == null || current.sessionId != sessionId) {
            ToolStreamState(sessionId = sessionId)
        } else {
            current
        }
        copy(toolStream = base.merge(entry))
    }
}
