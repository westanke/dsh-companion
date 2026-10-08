package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.Serializable

/**
 * 连接层数据模型 —— 本文件是「新 APP」的地基契约，实现方不得擅自改动签名。
 *
 * ## 为什么要有这一层
 *
 * 旧实现的模型是：
 * ```
 * data class StoredConnection(val endpoint: String, val token: String)
 * class DshClient(endpoint: String, token: String?)
 * ```
 * 它把「一台电脑」和「一个地址」焊成了同一个东西，于是：
 * - 只能保存一条路径（用户反馈第 3 条的直接根因）；
 * - 出门在外换了网络（局域网 → 虚拟网 → 公网）就必须重填地址；
 * - 令牌与地址绑死，换地址要重新配对（用户反馈第 1 条配对码痛的放大器）。
 *
 * 这里把两件事拆开：
 * - [Host] 表示「一台电脑」，是用户视角的实体（有名字、有凭据）；
 * - [Endpoint] 表示「这台电脑的一个可达地址」，一台电脑可以有任意多个；
 * - [Credential] 挂在 [Host] 上而不是地址上。
 *
 * 这个拆分**不需要服务端任何改动**：DSH 的 `devices` 表结构是
 * `id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at`，
 * 根本没有地址列 —— 设备令牌天然是主机级的，同一个令牌在任何地址上都有效。
 * 所以「多地址」是客户端本来就能做到、只是过去没做的事。
 */

/** 地址类型，用于选路加权与界面标注。 */
@Serializable
enum class EndpointKind {
    /** 局域网直连，通常最快、最省流量。 */
    LAN,

    /** 虚拟网（Tailscale / ZeroTier / BeyondTunnel 等），出门在外的主力。 */
    VIRTUAL_NET,

    /** 公网地址或域名。 */
    WAN,

    /** 无法判断（例如域名、非常规网段）。 */
    UNKNOWN;

    companion object {
        /**
         * 按地址猜类型。只做粗判，不允许抛异常 —— 猜错的代价仅是排序加权略有偏差，
         * 真实可用性始终由探活结果决定。
         */
        fun infer(baseUrl: String): EndpointKind {
            val host = runCatching {
                baseUrl.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore('/')
                    .substringBefore(':')
            }.getOrNull()?.lowercase().orEmpty()
            if (host.isBlank()) return UNKNOWN
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return LAN
            if (host.endsWith(".local")) return LAN
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return UNKNOWN
            val (a, b) = octets[0] to octets[1]
            return when {
                a == 10 -> LAN
                a == 192 && b == 168 -> LAN
                a == 172 && b in 16..31 -> LAN
                // 100.64.0.0/10 是运营商级 NAT 网段，Tailscale 与 BeyondTunnel 都在用。
                a == 100 && b in 64..127 -> VIRTUAL_NET
                a == 169 && b == 254 -> LAN
                else -> WAN
            }
        }
    }
}

/** 一台电脑的一个可达地址。 */
@Serializable
data class Endpoint(
    val id: String,
    /** 用户可读的标签，例如「家里局域网」「公司虚拟网」。 */
    val label: String,
    /** 完整基地址，例如 `http://192.168.1.126:3090`；不含 `/api/v1` 后缀。 */
    val baseUrl: String,
    val kind: EndpointKind = EndpointKind.UNKNOWN,
    val enabled: Boolean = true,
    /** 最近一次探活往返耗时；null 表示从未探测。 */
    val lastLatencyMs: Long? = null,
    val lastCheckedAt: Long? = null,
    /** 最近一次失败原因，成功时清空。 */
    val lastError: String? = null,
) {
    companion object {
        fun create(label: String, baseUrl: String, id: String): Endpoint {
            val normalized = DshClient.normalizeEndpoint(baseUrl).removeSuffix("/api/v1")
            return Endpoint(
                id = id,
                label = label.ifBlank { normalized },
                baseUrl = normalized,
                kind = EndpointKind.infer(normalized),
            )
        }
    }
}

/** 凭据种类。当前实现主要使用 [DEVICE_TOKEN]；其余为后续身份方式预留。 */
@Serializable
enum class CredentialKind {
    /** 配对或配置导入换来的设备令牌，DSH 的 `devices` 记录。 */
    DEVICE_TOKEN,

    /** 密码直连（服务端开启密码认证时）。 */
    PASSWORD,

    /** 自定义密码；局域网与公网可分别设置，对应 DSH 的 `lanPinCustom` / `publicPinCustom`。 */
    CUSTOM_PASSWORD,
}

/**
 * 主机凭据。
 *
 * 安全约定：本对象**整体**参与序列化，但序列化结果必须经 [HostStore] 用
 * Android Keystore 加密后才允许落盘；明文 JSON 一律不得写入磁盘或日志。
 */
@Serializable
data class Credential(
    val kind: CredentialKind = CredentialKind.DEVICE_TOKEN,
    /** 秘密本体（设备令牌或密码）。 */
    val secret: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val scopes: List<String> = emptyList(),
    val issuedAt: Long = 0L,
    /** 过期时刻；null 表示不过期。 */
    val expiresAt: Long? = null,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now
}

/** 一台电脑。 */
@Serializable
data class Host(
    val id: String,
    val displayName: String,
    val endpoints: List<Endpoint> = emptyList(),
    /** 上次成功连接的地址；启动时优先试它，但失败会立刻回退。 */
    val preferredEndpointId: String? = null,
    val credential: Credential? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun endpoint(id: String?): Endpoint? = endpoints.firstOrNull { it.id == id }

    /** 参与探活的地址：启用中且非空。 */
    val activeEndpoints: List<Endpoint> get() = endpoints.filter { it.enabled && it.baseUrl.isNotBlank() }

    fun withEndpointProbe(
        endpointId: String,
        latencyMs: Long?,
        error: String?,
        checkedAt: Long = System.currentTimeMillis(),
    ): Host = copy(
        endpoints = endpoints.map { endpoint ->
            if (endpoint.id != endpointId) endpoint
            else endpoint.copy(lastLatencyMs = latencyMs, lastError = error, lastCheckedAt = checkedAt)
        },
        updatedAt = checkedAt,
    )

    fun withPreferred(endpointId: String?): Host = copy(preferredEndpointId = endpointId)
}

/** 一次探活的结果。 */
data class EndpointProbe(
    val endpointId: String,
    val ok: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun success(endpointId: String, latencyMs: Long) = EndpointProbe(endpointId, true, latencyMs)
        fun failure(endpointId: String, error: String) = EndpointProbe(endpointId, false, null, error)
    }
}

/** 选路结果：选中的地址 + 可用于请求的客户端。 */
data class ActiveConnection(
    val host: Host,
    val endpoint: Endpoint,
    val client: DshClient,
    val probes: List<EndpointProbe> = emptyList(),
)

/**
 * 选路规则（[ConnectionCoordinator] 与测试必须一致）：
 *
 * 1. 只有探活成功的地址参与排序；全部失败则整体连接失败，并返回全部失败原因供诊断面板展示。
 * 2. 主排序键是延迟。
 * 3. 但局域网享有「近似即优先」：若某个 [EndpointKind.LAN] 地址的延迟不超过
 *    当前最快地址的 [LAN_LATENCY_TOLERANCE] 倍，则它排在前面 —— 同网段直连更稳定、
 *    不消耗虚拟网/公网流量，为此容忍一点延迟差是划算的。
 * 4. 并列时，[Host.preferredEndpointId] 优先，其次是[Endpoint.lastCheckedAt]（null 视为最旧）。
 */
object EndpointSelection {
    const val LAN_LATENCY_TOLERANCE: Double = 1.5

    fun rank(probes: List<EndpointProbe>, endpoints: List<Endpoint>, preferredId: String?): List<EndpointProbe> {
        val ok = probes.filter { it.ok && it.latencyMs != null }
        if (ok.isEmpty()) return emptyList()
        val fastest = ok.minOf { it.latencyMs!! }
        val byId = endpoints.associateBy { it.id }
        return ok.sortedWith(
            compareBy(
                // 局域网近似即优先 → 归入同一档
                { probe ->
                    val kind = byId[probe.endpointId]?.kind
                    if (kind == EndpointKind.LAN && probe.latencyMs!! <= fastest * LAN_LATENCY_TOLERANCE) 0 else 1
                },
                { probe -> probe.latencyMs },
                { probe -> if (probe.endpointId == preferredId) 0 else 1 },
                { probe -> byId[probe.endpointId]?.lastCheckedAt ?: Long.MIN_VALUE },
            ),
        )
    }
}
