package io.github.westanke.dshcompanion.data

/**
 * 「重试」要重发的文本：历史里**最后一条有正文的用户消息**。
 *
 * ## 为什么是「最后一条」而不是「这条失败行对应的那条」
 *
 * `ChatTurnFailure` 里没有 turn / seq / 消息 id —— 内核只给了失败摘要
 * （`message` / `code` / `stopped`），拿不到精确归属。而实际使用中，失败行永远
 * 紧跟在**触发它的那条用户消息**之后（那一轮里用户不会再插话，插话会被算成新的一轮）。
 * 所以「最后一条用户消息」就是它，而且这个判据不需要任何额外字段。
 *
 * ## 两个刻意的排除
 *
 * - **正文为空的消息不算**：只发图片/附件的那条消息 body 是空的，
 *   重发它会变成一条空消息。重试功能宁可什么都不做，也不要发出一条空消息。
 * - 取不到（历史上没有用户消息）时返回 null，调用方**静默不动** ——
 *   这是用户主动点的按钮，报一个他看不懂的错反而更糟。
 */
internal fun retryableUserText(items: List<ChatDisplayItem>): String? =
    items.lastOrNull { it.kind == ChatItemKind.USER && it.body.isNotBlank() }?.body
