package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import io.github.westanke.dshcompanion.data.isTurnMaxTokens

/*
 * ============================================================================
 * 「回答被截断」提示的落点与清理
 * ============================================================================
 *
 * 从 `HarnessViewModel.kt` 拆出（行数预算 800/文件，由 `ArchitectureGuardTest` 守着）。
 *
 * ## 这条提示为什么必须由客户端自己记
 *
 * 模型撞到输出 token 上限时，内核发的是
 * `turn/end { reason: { kind: 'max-tokens' } }` —— **不是**失败，
 * 所以插件不会发 `chat.turn.failed`（那一帧只给 error / 登出）。
 *
 * 而 `sessions.history` **不返回 `turn/end`**（2026-10-18 真机实测：
 * 一次取 293 条历史事件，`turn/end` 零条），所以重进会话时历史里也翻不到它。
 *
 * 两条路都断 ⇒ 唯一能拿到它的地方就是**实时帧**。于是这里做两件事：
 * 收到就把提示落进状态（跨历史刷新存活），下一轮开始时清掉。
 *
 * ## 真机症状
 *
 * 电脑端 WebUI 有那条黄色提示，App 里整条消失，用户只看到回答戛然而止。
 */

/**
 * 处理一轮的边界事件：落「回答被截断」提示，或在新一轮开始时清掉上一轮的。
 *
 * @param event 实时帧（`chat.turn.end` / `chat.turn.start`）
 * @param sessionId 该帧所属会话；null 时什么都不做（帧缺 sessionId 是畸形数据）
 */
internal fun HarnessViewModel.noteTurnBoundary(event: WorkspaceEvent, sessionId: String?) {
    if (sessionId == null) return
    // 截断：记下所属会话 + 提示本身。
    if (event.type == "chat.turn.end" && event.isTurnMaxTokens()) {
        update {
            copy(
                truncatedNoticeSession = sessionId,
                truncatedNotice = ChatDisplayItem(
                    id = "$sessionId-turn-max-tokens",
                    // seq 用 0：这条提示没有持久位置（历史里不存在），
                    // 给个伪 seq 只会让日志/轨迹里出现一个查无此号的值。
                    seq = 0,
                    kind = ChatItemKind.TRUNCATED,
                ),
            )
        }
        return
    }
    // 新的一轮开始：上一条截断提示属于**上一轮**，留着只会误导。
    if (event.type == "chat.turn.start" && mutableState.value.truncatedNoticeSession == sessionId) {
        update { copy(truncatedNoticeSession = null, truncatedNotice = null) }
    }
}

/**
 * 当前会话该显示的截断提示（会话对不上就空）。
 *
 * 抽成一行调用是为了 `ChatDetail.kt` 的行数预算；**语义**是「提示归属于某一轮」——
 * 切到别的会话时 sessionId 对不上，自然不显示，不需要在每个切换入口记得清一次。
 */
internal fun HarnessState.truncationItems(sessionId: String?): List<ChatDisplayItem> =
    if (sessionId != null && truncatedNoticeSession == sessionId) {
        listOfNotNull(truncatedNotice)
    } else {
        emptyList()
    }
