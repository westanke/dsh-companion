package io.github.westanke.dshcompanion.data.ws

import okhttp3.Headers

/**
 * `Retry-After` 的读法与重连等待的合成。
 *
 * 为什么单独一个文件：这段逻辑是**纯算术 + 纯解析**，不该和 [MobileWsTransport] 的
 * 线程模型纠缠在一起 —— 它唯一要保证的事是「服务端说等多久就至少等多久，但别被荒唐值挂死」，
 * 而这种保证只有用单测钉住才算数（真机上要造一个「第 33 条连接被 429」的场景既慢又难复现）。
 *
 * 背景：桥从 2026-10-29 起对 `/ws/mobile` 真的执行连接上限（默认 32），第 33 条在
 * Upgrade 阶段收到 `HTTP 429` + `Retry-After: 5`（见桥 `src/host/server.ts` 的
 * `handleUpgrade` 与 `docs/project/API.md` 的「连接数」行）。客户端如果不认这个头，
 * 就会用自己那套 `1,2,4,8,16,30` 秒的退避去撞 —— 退避本身没错，但它是对**故障**设计的，
 * 而 429 是服务端在说「我知道你来了，请晚点再来」，两者应该分开对待。
 */

/** 服务端说「马上」也不能立刻重连：一毫秒的重连风暴跟不守规矩没有区别。 */
internal const val MIN_RETRY_AFTER_MILLIS: Long = 1_000

/**
 * 上限：`Retry-After` 是服务端（或中间某个反代）写的，一个荒唐的大值会把重连永久挂起，
 * 表现为「App 一直显示已断开，重启才好」。60 秒之后仍会重试，但不再无限等。
 */
internal const val MAX_RETRY_AFTER_MILLIS: Long = 60_000

/**
 * 从响应头里算出「下次重连最早该等多久（毫秒）」。
 *
 * 只认 RFC 7231 的 **delta-seconds** 形式（`Retry-After: 5`）—— 桥发的就是这个。
 * 另一种合法形式是 HTTP-date（`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`），这里**不支持**：
 * 解析它要依赖设备时钟，而手机时钟与宿主时钟的偏差恰恰是这个场景里最不可信的东西。
 * 认不出来的值一律返回 `null`（当作「服务端没给」），由退避兜底，绝不猜。
 *
 * @return 毫秒数（已夹到 [MIN_RETRY_AFTER_MILLIS]..[MAX_RETRY_AFTER_MILLIS]）；
 *   头缺失、空、非数字、负数时返回 `null`。
 */
internal fun retryAfterMillis(headers: Headers?): Long? {
    val raw = headers?.get("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return (seconds * 1_000).coerceIn(MIN_RETRY_AFTER_MILLIS, MAX_RETRY_AFTER_MILLIS)
}

/**
 * 这次重连实际等多久 = `max(自己的退避, 服务端要求的最短等待)`。
 *
 * 用 `max` 而不是「有 `Retry-After` 就替代退避」，是因为两者保护的东西不同：
 * 退避保护的是**服务端**（别把它打崩），`Retry-After` 是服务端**告诉我们**它想要的节奏。
 * 取较长的那个，两条约束同时满足；任何一方单独说了算都会破坏另一方 ——
 * 比如服务端说 5 秒而我们已经退到 30 秒，此时缩回 5 秒等于自己打自己的退避。
 */
internal fun reconnectWaitMillis(backoffMillis: Long, retryAfterMillis: Long?): Long =
    maxOf(backoffMillis, retryAfterMillis ?: 0L)
