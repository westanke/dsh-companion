package io.github.westanke.dshcompanion.data

/**
 * 子会话（团队成员）历史的读取入口。
 *
 * 为什么是**独立文件里的扩展函数**而不是 `DshClient` 的成员：`DshClient.kt` 已贴着
 * 800 行预算（`ArchitectureGuardTest` 硬门），这条窄口又是「桥 2.9.0 才有的新面」，
 * 放进去只为了让预算爆掉不值当。`pathUrl` / `get` 都是 `internal`，同模块内可用。
 *
 * ## 为什么不能复用 [DshClient.history]
 *
 * 那条路走 `GET /chat/sessions/{id}/messages`，而子会话被桥的会话列表**刻意滤掉**了
 * （内核拒绝把子会话当普通会话激活，暴露出来只会得到「点开就崩」的行），于是
 * `requireSession` 判定它不可见 —— 任何子会话 id 打进去必然 404。
 *
 * 桥为此开了一条受成员关系校验的只读窄口：路径带上 **Lead 会话 id**，桥用
 * 「Lead 对这台设备可见」+「内核投影背书 subagentId 属于该 Lead」两步授权，
 * 都过了才直读历史。所以这里必须同时传 `leadSessionId` 与 `subagentId`，少一个都拿不到。
 *
 * ## 只读
 *
 * 桥侧非 GET 一律 405。App 也不该有写子会话的入口 —— 写会让「谁在指挥谁」含混，
 * 官方 Web 端同样只允许经 Lead 的模型工具去写。
 */
internal fun DshClient.subagentHistory(
    leadSessionId: String,
    subagentId: String,
    beforeSeq: Int? = null,
    maxMessages: Int? = null,
): ChatHistory {
    val url = pathUrl("chat", "sessions", leadSessionId, "subagents", subagentId, "messages")
        .newBuilder().apply {
            beforeSeq?.let { addQueryParameter("beforeSeq", it.toString()) }
            maxMessages?.let { addQueryParameter("maxMessages", it.toString()) }
        }.build()
    return get(url, ChatHistory.serializer())
}
