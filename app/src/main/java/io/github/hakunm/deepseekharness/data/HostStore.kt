package io.github.hakunm.deepseekharness.data

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 极小的键值存储抽象。
 *
 * 抽象存在的唯一理由是**可测试**：生产实现是 SharedPreferences
 * （见 `AndroidConnectionStorage.kt` 的 `SharedPreferencesKeyValueStore`），
 * 单测里换成内存 Map，于是本模块不需要 Robolectric、不需要真机。
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String?)

    fun remove(key: String)

    fun clear()
}

/**
 * 对称加解密抽象。
 *
 * 生产实现是 Android Keystore 的 AES-GCM（`KeystoreSecretBox`），
 * 单测里换成任意可逆变换（例如 XOR），从而不碰 AndroidKeystore。
 */
interface SecretBox {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * 多主机持久化。
 *
 * ## 存储格式
 *
 * 整个 `List<Host>`（含 [Credential].secret）序列化为 JSON，**先经 [SecretBox] 加密、
 * 再 Base64** 落盘到键 [KEY_HOSTS]。也就是说：SharedPreferences 里永远是密文，
 * 明文 JSON 绝不落盘，凭据也不会有单独的明文键。
 *
 * ## 损坏容错
 *
 * [loadHosts] 遇到「Base64 不合法 / 解密失败 / JSON 损坏」一律返回空列表，**绝不抛异常** ——
 * 一条坏数据不该把用户永久锁进启动崩溃循环。同时原始值会被原样保留在
 * [KEY_HOSTS]（供排查），并另存一份到 [KEY_HOSTS_CORRUPT]，不会被静默清空。
 *
 * ## 时间
 *
 * [clock] 可注入，便于单测断言 createdAt/updatedAt 而不依赖真实时钟。
 *
 * 本类不 import 任何 `android.*`，平台能力全部经 [KeyValueStore] / [SecretBox] 注入。
 */
class HostStore(
    private val storage: KeyValueStore,
    private val secretBox: SecretBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 读取全部主机。空存储、损坏数据、非法 Base64 都返回空列表而不是抛异常。 */
    fun loadHosts(): List<Host> {
        val encoded = storage.getString(KEY_HOSTS) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val plain = secretBox.decrypt(Base64.getDecoder().decode(encoded))
            json.decodeFromString(ListSerializer(Host.serializer()), plain.toString(Charsets.UTF_8))
        }.getOrElse {
            // 保留原值（不 remove、不清空），并额外留一份副本，方便日后排查。
            quarantineCorrupt(encoded)
            emptyList()
        }
    }

    /** 覆盖式写入整个列表（加密后 Base64）。 */
    fun saveHosts(hosts: List<Host>) {
        val plain = json.encodeToString(ListSerializer(Host.serializer()), hosts)
        val encrypted = secretBox.encrypt(plain.toByteArray(Charsets.UTF_8))
        storage.putString(KEY_HOSTS, Base64.getEncoder().encodeToString(encrypted))
    }

    /**
     * 插入或替换单台主机，返回写入后的完整列表。
     *
     * 已存在同 id 时保留**原有** createdAt（创建时间不该被一次编辑重置），
     * 并把 updatedAt 刷新为 [clock]；不存在时若传入 createdAt 为 0，则用当前时间补齐。
     */
    fun upsert(host: Host): List<Host> {
        val now = clock()
        val current = loadHosts()
        val existing = current.firstOrNull { it.id == host.id }
        val merged = host.copy(
            createdAt = existing?.createdAt ?: host.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        val next = when {
            existing == null -> current + merged
            else -> current.map { if (it.id == merged.id) merged else it }
        }
        saveHosts(next)
        return next
    }

    /** 删除指定主机，返回删除后的完整列表。id 不存在时不报错。 */
    fun remove(hostId: String): List<Host> {
        val next = loadHosts().filterNot { it.id == hostId }
        saveHosts(next)
        return next
    }

    /** 按 id 取单台主机。 */
    fun host(id: String): Host? = loadHosts().firstOrNull { it.id == id }

    /**
     * 记住某主机上次成功使用的地址。传入 null 表示清空。
     *
     * 只改选路偏好，不改 updatedAt —— 与契约里的 [Host.withPreferred] 保持一致。
     */
    fun markPreferred(hostId: String, endpointId: String?) {
        val current = loadHosts()
        if (current.none { it.id == hostId }) return
        saveHosts(current.map { if (it.id == hostId) it.withPreferred(endpointId) else it })
    }

    /**
     * 把旧版「单地址 + 单令牌」迁移成一台 [Host]。
     *
     * - 无旧数据（[legacyEndpoint] 为 null/空白）返回 null，且**不写任何东西**；
     * - hostId / endpoint 的 id 都由地址的 SHA-256 派生出稳定值，所以重复调用不会产生重复主机；
     * - displayName 取地址的主机名（如 `192.168.1.126`）；
     * - 只建一个 [Endpoint]，kind 由 [EndpointKind.infer] 推断；
     * - [legacyToken] 非空时建 [CredentialKind.DEVICE_TOKEN] 凭据，scopes 留空；
     *   旧数据里令牌缺失（例如解密失败）时仍保留地址，只是没有凭据，用户可重新配对。
     */
    fun migrateLegacy(legacyEndpoint: String?, legacyToken: String?): Host? {
        val rawEndpoint = legacyEndpoint?.trim().orEmpty()
        if (rawEndpoint.isBlank()) return null
        val baseUrl = normalizeBaseUrl(rawEndpoint)
        if (baseUrl.isBlank()) return null

        val now = clock()
        val hostId = stableId(HOST_ID_PREFIX, baseUrl)
        val endpointId = stableId(ENDPOINT_ID_PREFIX, baseUrl)
        val displayName = hostNameOf(baseUrl)
        val token = legacyToken?.takeIf { it.isNotBlank() }
        // 复用已有凭据的签发时间，避免迁移重复执行时把 issuedAt 刷新掉。
        val existing = loadHosts().firstOrNull { it.id == hostId }
        val existingIssuedAt = existing?.credential?.issuedAt?.takeIf { it > 0L }

        val host = Host(
            id = hostId,
            displayName = displayName,
            endpoints = listOf(Endpoint.create(label = displayName, baseUrl = baseUrl, id = endpointId)),
            preferredEndpointId = endpointId,
            credential = token?.let {
                Credential(
                    kind = CredentialKind.DEVICE_TOKEN,
                    secret = it,
                    issuedAt = existingIssuedAt ?: now,
                )
            },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        return upsert(host).firstOrNull { it.id == hostId }
    }

    /** 清空本存储拥有的全部键（含损坏数据副本）。 */
    fun clear() {
        storage.remove(KEY_HOSTS)
        storage.remove(KEY_HOSTS_CORRUPT)
    }

    private fun quarantineCorrupt(raw: String) {
        // 任何异常都不能从 loadHosts 里漏出去；副本写失败也不影响返回空列表。
        runCatching {
            if (storage.getString(KEY_HOSTS_CORRUPT) != raw) {
                storage.putString(KEY_HOSTS_CORRUPT, raw)
            }
        }
    }

    private fun stableId(prefix: String, seed: String): String = prefix + sha256Hex(seed).take(16)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** 归一成「无 `/api/v1` 后缀」的基地址；缺协议时补 `http://`，异常时退回裁剪后的原文。 */
    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return runCatching { DshClient.normalizeEndpoint(withScheme).removeSuffix("/api/v1") }
            .getOrElse { withScheme }
    }

    /** 从基地址里取主机名：去掉协议、路径、查询串与端口，IPv6 去掉方括号。 */
    private fun hostNameOf(baseUrl: String): String {
        val authority = baseUrl.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
        return host.ifBlank { baseUrl.trim() }
    }

    companion object {
        /** 新版多主机列表的落盘键。 */
        const val KEY_HOSTS = "hosts_v1"

        /** 解密/解析失败时保留的原始值副本，仅用于排查。 */
        const val KEY_HOSTS_CORRUPT = "hosts_v1_corrupt"

        private const val HOST_ID_PREFIX = "legacy-"
        private const val ENDPOINT_ID_PREFIX = "legacy-ep-"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
