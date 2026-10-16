package io.github.westanke.dshcompanion.data

/**
 * 这条展示项能不能作为分叉点？能就返回要传给 `forkSession` 的 `atSeq`。
 *
 * ## 为什么只给**用户消息**
 *
 * 从任意一条消息分叉在技术上都可以（内核按事件序号切），但界面上不能每条都挂一个按钮 ——
 * 那会让消息流变得毛糙，而分叉的真实使用场景几乎总是「这次提问的走向不对，从这儿重来」。
 * 所以只给用户消息，按钮藏在时间戳那一行里（次要动作放次要位置）。
 *
 * ## 为什么要求 `seq > 0`
 *
 * `seq` 是内核的事件序号，0 不是有效的分叉点（历史项才会有正 seq；
 * 流式项与本地占位项没有）。给内核传一个它不认识的分叉点只会得到一个报错弹窗，
 * 而用户完全不知道自己做错了什么 —— 宁可不显示入口。
 */
internal fun forkPointOf(item: ChatDisplayItem): Int? =
    if (item.kind == ChatItemKind.USER && item.seq > 0) item.seq else null
