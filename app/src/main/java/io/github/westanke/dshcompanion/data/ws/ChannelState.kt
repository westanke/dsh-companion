package io.github.westanke.dshcompanion.data.ws

/**
 * `/ws/mobile` 通道的**唯一**降级状态机（`docs/project/WS-FIRST-DESIGN.md` §3.2）。
 *
 * 为什么必须只有这一处：降级判据一旦散落在调用点，必然漂 —— 同一个「WS 到底还能不能用」
 * 在不同页面得到不同答案，用户看到的就是「有时候能发、有时候点了没反应」。
 * 所以这里只放**状态**与**纯函数判据**，不放网络代码；判据本身可单测（[decideAfterHello] 等）。
 *
 * 四个状态的语义边界（别混用）：
 *
 * | 状态 | 触发 | 数据走哪 |
 * |---|---|---|
 * | [WsReady] | `hello` 已收、所需能力位齐 | WS |
 * | [WsDegraded] | `hello` 已收但**缺能力位** | 缺的那几个域走 REST，其余仍走 WS |
 * | [WsDown] | 连不上 / 已建连接断开（重连中） | 全部 REST |
 * | [RestOnly] | upgrade 被拒（404 等）或子协议被拒 | 全部 REST，**且不再重试 WS** |
 *
 * `WsDegraded` 与 `WsDown` 的区别是**有没有连上**：前者是「连上了但服务端能力不足」，
 * 后者是「压根没连上/断了」。把两者混成一个状态会让「重连中」把「能力不足」也一起重试，
 * 白打一轮网络还得不到新能力位。
 */
sealed interface ChannelState {

    /** `hello` 已收、所需能力位齐 —— 该域可以走 WS。 */
    data object WsReady : ChannelState

    /** 连上了但缺能力位：`reason` 里带缺的位名，方便诊断直接显示。 */
    data class WsDegraded(val reason: String) : ChannelState

    /** 连不上 / 已建连接断开（后台按退避重连中）。 */
    data class WsDown(val reason: String) : ChannelState

    /** 老桥没有 `/ws/mobile`（或子协议被拒）—— 别再重试，它不会突然长出通道。 */
    data object RestOnly : ChannelState

    /** 该域此刻能不能走 WS。`WsDegraded` 也算「能」：它只影响缺位的域。 */
    val usesWs: Boolean
        get() = this is WsReady || this is WsDegraded

    /** 是否已判定为终态（不再重试）。 */
    val isTerminal: Boolean
        get() = this is RestOnly
}

/** `hello.protocol` 的最低可接受修订版；低于它的服务端按「能力不足」处理，不硬用。 */
internal const val MIN_WS_PROTOCOL: Int = 3

/**
 * `hello` 到达之后的状态判定（**唯一真理源**）。
 *
 * 抽成纯函数而不是写在 `WebSocketListener` 里，是为了让「缺能力位」这条分支能脱离网络单测 ——
 * 它恰恰是最难在真机上复现的一条（要一台「能连上但少能力位」的老桥）。
 *
 * @param protocol 服务端自报的协议修订版。
 * @param capabilities 服务端自报的能力位全集。
 * @param required 本阶段**必需**的能力位（见 [REQUIRED_CAPABILITIES_FOR_WS]）。
 */
internal fun decideAfterHello(
    protocol: Int,
    capabilities: Set<String>,
    required: Set<String>,
): ChannelState {
    if (protocol < MIN_WS_PROTOCOL) {
        return ChannelState.WsDegraded("协议版本 $protocol < $MIN_WS_PROTOCOL")
    }
    val missing = required - capabilities
    return if (missing.isEmpty()) {
        ChannelState.WsReady
    } else {
        ChannelState.WsDegraded("缺少能力位：" + missing.sorted().joinToString(","))
    }
}

/**
 * 这个 HTTP 状态码是否意味着「**这条通道根本不存在**」。
 *
 * 判据只认「服务端用 HTTP 语义明确答复了、而且答的不是 101」：404（没有这条路由）、
 * 400/405/501/426（不支持 upgrade）。这些是老桥/反代的**确定性**答复，重试一万次也一样。
 *
 * **刻意不含 401/403**：那是鉴权失败，令牌可能被换掉后恢复，属于「重连能好」的范畴。
 * **也刻意不含 5xx**：服务端可能在重启，退避重连是对的。
 */
internal fun isDefinitiveUpgradeRejection(statusCode: Int): Boolean =
    statusCode == 404 || statusCode == 400 || statusCode == 405 || statusCode == 501 || statusCode == 426

/**
 * 是否是「子协议被拒」。
 *
 * 桥的判据在 `src/host/ws-handler.ts:233-238`：升级完成后发现 `webSocket.protocol`
 * 不是 `dsh-mobile-v1` 就 `close(4001, 'AUTH_REQUIRED')`。**注意这是升级之后才关的**，
 * 所以只能从 close code 认，拿不到 HTTP 状态码。
 *
 * 只在 `hello` 之前认 4001：连上之后服务端也可能因为令牌被撤销而 4001，
 * 那种情况属于「断线重连」，不是「通道不存在」。
 */
internal fun isProtocolRejected(closeCode: Int, helloSeen: Boolean): Boolean =
    !helloSeen && closeCode == 4001
