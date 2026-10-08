package io.github.hakunm.deepseekharness.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostStore] 的单测。
 *
 * 全部依赖都是内存实现，不触碰 SharedPreferences / AndroidKeystore / 网络，
 * 因此可以在普通 JVM 单测里直接跑。
 */
class HostStoreTest {

    // ---------- 内存替身 ----------

    private class InMemoryKeyValueStore : KeyValueStore {
        private val values = LinkedHashMap<String, String>()

        var clearCount = 0

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() {
            clearCount++
            values.clear()
        }

        fun keys(): Set<String> = values.keys.toSet()
    }

    /** 可逆 XOR：用于验证「落盘的是密文」，不需要真加密算法。 */
    private class XorSecretBox(private val key: Byte = 0x5A) : SecretBox {
        var encryptCount = 0

        /** 置为 true 后 decrypt 抛异常，模拟 Keystore 密钥失效。 */
        var failDecrypt = false

        override fun encrypt(plain: ByteArray): ByteArray {
            encryptCount++
            return ByteArray(plain.size) { index -> (plain[index].toInt() xor key.toInt()).toByte() }
        }

        override fun decrypt(cipher: ByteArray): ByteArray {
            if (failDecrypt) throw IllegalStateException("simulated keystore failure")
            return ByteArray(cipher.size) { index -> (cipher[index].toInt() xor key.toInt()).toByte() }
        }
    }

    // ---------- 测试夹具 ----------

    private fun endpoint(id: String, baseUrl: String): Endpoint =
        Endpoint(id = id, label = "地址 $id", baseUrl = baseUrl, kind = EndpointKind.infer(baseUrl))

    private fun sampleHost(
        id: String = "host-1",
        createdAt: Long = 100L,
        updatedAt: Long = 100L,
    ): Host = Host(
        id = id,
        displayName = "客厅台式机",
        endpoints = listOf(
            endpoint("ep-lan", "http://192.168.1.126:3090"),
            endpoint("ep-vnet", "http://100.64.0.5:3090"),
        ),
        preferredEndpointId = "ep-lan",
        credential = Credential(
            kind = CredentialKind.DEVICE_TOKEN,
            secret = "tok-super-secret",
            deviceId = "dev-1",
            deviceName = "Pixel",
            scopes = listOf("files:read"),
            issuedAt = 50L,
        ),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---------- 空存储 ----------

    @Test
    fun emptyStorageReturnsEmptyList() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox())
        assertEquals(emptyList<Host>(), store.loadHosts())
        assertNull(store.host("host-1"))
    }

    @Test
    fun blankStoredValueReturnsEmptyList() {
        val keyValueStore = InMemoryKeyValueStore()
        keyValueStore.putString(HostStore.KEY_HOSTS, "   ")
        val store = HostStore(keyValueStore, XorSecretBox())
        assertEquals(emptyList<Host>(), store.loadHosts())
    }

    @Test
    fun savedEmptyListLoadsAsEmptyList() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox())
        store.saveHosts(emptyList())
        assertEquals(emptyList<Host>(), store.loadHosts())
    }

    // ---------- 存取往返 ----------

    @Test
    fun saveAndLoadRoundTripsEveryField() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox())
        val host = sampleHost().withEndpointProbe("ep-lan", latencyMs = 12L, error = null, checkedAt = 777L)

        store.saveHosts(listOf(host))

        val loaded = store.loadHosts()
        assertEquals(listOf(host), loaded)
        val credential = loaded.single().credential!!
        assertEquals(CredentialKind.DEVICE_TOKEN, credential.kind)
        assertEquals("tok-super-secret", credential.secret)
        assertEquals("dev-1", credential.deviceId)
        assertEquals(listOf("files:read"), credential.scopes)
        assertEquals(50L, credential.issuedAt)
        assertEquals(12L, loaded.single().endpoints.first().lastLatencyMs)
        assertEquals(777L, loaded.single().endpoints.first().lastCheckedAt)
    }

    @Test
    fun persistedValueIsEncryptedBase64NotPlaintextJson() {
        val keyValueStore = InMemoryKeyValueStore()
        val secretBox = XorSecretBox()
        val store = HostStore(keyValueStore, secretBox)

        store.saveHosts(listOf(sampleHost()))

        assertEquals(1, secretBox.encryptCount)
        val raw = requireNotNull(keyValueStore.getString(HostStore.KEY_HOSTS))
        assertNotNull(raw)
        assertFalse("明文令牌不能落盘", raw.contains("tok-super-secret"))
        assertFalse("明文 JSON 不能落盘", raw.contains("endpoints"))
        assertFalse(raw.startsWith("["))
        assertFalse(raw.startsWith("{"))
        // 同时确认它确实是合法 Base64（能被解码回密文）。
        assertTrue(Base64.getDecoder().decode(raw).isNotEmpty())
    }

    // ---------- 损坏容错 ----------

    @Test
    fun corruptedCiphertextReturnsEmptyListAndKeepsRawValue() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox())
        store.saveHosts(listOf(sampleHost()))

        // 合法 Base64，但解密后是垃圾字节 → JSON 解析必然失败。
        val corrupt = Base64.getEncoder().encodeToString(ByteArray(96))
        keyValueStore.putString(HostStore.KEY_HOSTS, corrupt)

        assertEquals(emptyList<Host>(), store.loadHosts())
        assertEquals("损坏数据必须原样保留，不能静默清空", corrupt, keyValueStore.getString(HostStore.KEY_HOSTS))
        assertEquals(corrupt, keyValueStore.getString(HostStore.KEY_HOSTS_CORRUPT))
    }

    @Test
    fun invalidBase64ReturnsEmptyListAndKeepsRawValue() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox())
        val corrupt = "!!! 这不是 Base64 !!!"
        keyValueStore.putString(HostStore.KEY_HOSTS, corrupt)

        assertEquals(emptyList<Host>(), store.loadHosts())
        assertEquals(corrupt, keyValueStore.getString(HostStore.KEY_HOSTS))
        assertEquals(corrupt, keyValueStore.getString(HostStore.KEY_HOSTS_CORRUPT))
    }

    @Test
    fun decryptFailureReturnsEmptyListInsteadOfThrowing() {
        val keyValueStore = InMemoryKeyValueStore()
        val secretBox = XorSecretBox()
        val store = HostStore(keyValueStore, secretBox)
        store.saveHosts(listOf(sampleHost()))
        val raw = keyValueStore.getString(HostStore.KEY_HOSTS)

        secretBox.failDecrypt = true

        assertEquals(emptyList<Host>(), store.loadHosts())
        assertEquals(raw, keyValueStore.getString(HostStore.KEY_HOSTS))
    }

    // ---------- upsert ----------

    @Test
    fun upsertInsertsNewHostAndFillsTimestamps() {
        var now = 1_000L
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { now }

        val inserted = store.upsert(sampleHost(createdAt = 0L, updatedAt = 0L)).single()

        assertEquals(1_000L, inserted.createdAt)
        assertEquals(1_000L, inserted.updatedAt)
        assertEquals(1, store.loadHosts().size)
    }

    @Test
    fun upsertPreservesCreatedAtAndRefreshesUpdatedAt() {
        var now = 1_000L
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { now }
        store.upsert(sampleHost(createdAt = 500L, updatedAt = 500L))

        now = 2_000L
        val updated = store.upsert(
            sampleHost(createdAt = 999_999L, updatedAt = 999_999L).copy(displayName = "改过名了"),
        ).single()

        assertEquals("已存在同 id 时必须保留原 createdAt", 500L, updated.createdAt)
        assertEquals("updatedAt 必须刷新为 clock()", 2_000L, updated.updatedAt)
        assertEquals("改过名了", updated.displayName)
        assertEquals(1, store.loadHosts().size)
    }

    @Test
    fun upsertKeepsInsertionOrderAndOtherHosts() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))
        store.upsert(sampleHost(id = "host-2").copy(displayName = "笔记本"))
        store.upsert(sampleHost(id = "host-3").copy(displayName = "公司机器"))

        store.upsert(sampleHost(id = "host-2").copy(displayName = "笔记本（改）"))

        assertEquals(listOf("host-1", "host-2", "host-3"), store.loadHosts().map { it.id })
        assertEquals("笔记本（改）", store.host("host-2")!!.displayName)
    }

    // ---------- remove / host ----------

    @Test
    fun removeDeletesOnlyTargetHost() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))
        store.upsert(sampleHost(id = "host-2"))

        val remaining = store.remove("host-1")

        assertEquals(listOf("host-2"), remaining.map { it.id })
        assertEquals(listOf("host-2"), store.loadHosts().map { it.id })
        assertNull(store.host("host-1"))
        assertNotNull(store.host("host-2"))
    }

    @Test
    fun removeUnknownHostIsNoOp() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))

        val remaining = store.remove("does-not-exist")

        assertEquals(listOf("host-1"), remaining.map { it.id })
    }

    // ---------- markPreferred ----------

    @Test
    fun markPreferredUpdatesOnlyTargetHost() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))
        store.upsert(sampleHost(id = "host-2"))
        val updatedAtBefore = store.host("host-2")!!.updatedAt

        store.markPreferred("host-2", "ep-vnet")

        assertEquals("ep-lan", store.host("host-1")!!.preferredEndpointId)
        assertEquals("ep-vnet", store.host("host-2")!!.preferredEndpointId)
        // 契约里的 withPreferred 不改 updatedAt，这里保持一致。
        assertEquals(updatedAtBefore, store.host("host-2")!!.updatedAt)
    }

    @Test
    fun markPreferredAcceptsNullToClear() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))

        store.markPreferred("host-1", null)

        assertNull(store.host("host-1")!!.preferredEndpointId)
    }

    @Test
    fun markPreferredOnUnknownHostIsNoOp() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }
        store.upsert(sampleHost(id = "host-1"))

        store.markPreferred("does-not-exist", "ep-vnet")

        assertEquals(listOf("host-1"), store.loadHosts().map { it.id })
    }

    // ---------- 多主机并存 ----------

    @Test
    fun multipleHostsCoexistAcrossStoreInstances() {
        val keyValueStore = InMemoryKeyValueStore()
        val first = HostStore(keyValueStore, XorSecretBox()) { 1_000L }
        first.upsert(sampleHost(id = "host-1").copy(displayName = "家里"))
        first.upsert(sampleHost(id = "host-2").copy(displayName = "公司"))

        // 换一个 HostStore 实例（模拟重启），数据仍在。
        val second = HostStore(keyValueStore, XorSecretBox()) { 2_000L }
        assertEquals(listOf("host-1", "host-2"), second.loadHosts().map { it.id })
        second.upsert(sampleHost(id = "host-3").copy(displayName = "备用机"))
        second.markPreferred("host-1", "ep-vnet")

        assertEquals(3, second.loadHosts().size)
        assertEquals("ep-vnet", second.host("host-1")!!.preferredEndpointId)
        assertEquals("tok-super-secret", second.host("host-3")!!.credential!!.secret)
    }

    // ---------- clear ----------

    @Test
    fun clearRemovesHostsAndCorruptBackup() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox()) { 1_000L }
        store.saveHosts(listOf(sampleHost()))
        keyValueStore.putString(HostStore.KEY_HOSTS_CORRUPT, "垃圾数据")

        store.clear()

        assertEquals(emptySet<String>(), keyValueStore.keys())
        assertEquals(emptyList<Host>(), store.loadHosts())
        assertEquals(0, keyValueStore.clearCount)
    }

    // ---------- migrateLegacy ----------

    @Test
    fun migrateLegacyWithoutDataReturnsNullAndWritesNothing() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox()) { 1_000L }

        assertNull(store.migrateLegacy(null, "tok"))
        assertNull(store.migrateLegacy("", "tok"))
        assertNull(store.migrateLegacy("   ", "tok"))
        assertNull(store.migrateLegacy(null, null))

        assertNull(keyValueStore.getString(HostStore.KEY_HOSTS))
        assertEquals(emptyList<Host>(), store.loadHosts())
    }

    @Test
    fun migrateLegacyWithDataBuildsSingleEndpointHost() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox()) { 1_000L }

        val host = store.migrateLegacy("http://192.168.1.126:3090", "legacy-token")

        assertNotNull(host)
        assertEquals("192.168.1.126", host!!.displayName)
        assertEquals(1, host.endpoints.size)
        val endpoint = host.endpoints.single()
        assertEquals(EndpointKind.LAN, endpoint.kind)
        assertEquals("http://192.168.1.126:3090", endpoint.baseUrl)
        assertEquals(endpoint.id, host.preferredEndpointId)
        val credential = requireNotNull(host.credential)
        assertEquals(CredentialKind.DEVICE_TOKEN, credential.kind)
        assertEquals("legacy-token", credential.secret)
        assertEquals(emptyList<String>(), credential.scopes)
        assertEquals(1_000L, host.createdAt)
        assertEquals(1_000L, host.updatedAt)
        assertEquals(listOf(host), store.loadHosts())
    }

    @Test
    fun migrateLegacyIsIdempotentAndKeepsStableId() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox()) { 1_000L }

        val first = store.migrateLegacy("http://192.168.1.126:3090/", "legacy-token")!!
        val second = store.migrateLegacy("http://192.168.1.126:3090", "legacy-token")!!

        assertEquals("hostId 必须是稳定派生值", first.id, second.id)
        assertEquals(first.id, store.migrateLegacy("http://192.168.1.126:3090", "别的令牌")!!.id)
        assertEquals(1, store.loadHosts().size)

        // 换实例重来：id 仍一致（稳定派生，不依赖内存状态）。
        val reopened = HostStore(keyValueStore, XorSecretBox()) { 3_000L }
        assertEquals(first.id, reopened.migrateLegacy("http://192.168.1.126:3090", "legacy-token")!!.id)
        assertEquals(1, reopened.loadHosts().size)
    }

    @Test
    fun migrateLegacyDoesNotDisturbExistingHosts() {
        var now = 1_000L
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { now }
        store.upsert(sampleHost(id = "host-existing"))

        now = 2_000L
        val migrated = store.migrateLegacy("http://100.64.0.5:3090", "legacy-token")!!

        assertEquals(2, store.loadHosts().size)
        assertEquals("host-existing", store.loadHosts().first().id)
        assertEquals("100.64.0.5", migrated.displayName)
        assertEquals(EndpointKind.VIRTUAL_NET, migrated.endpoints.single().kind)
    }

    @Test
    fun migrateLegacyStripsApiV1Suffix() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }

        val host = store.migrateLegacy("http://192.168.1.126:3090/api/v1/", "legacy-token")!!

        assertEquals("http://192.168.1.126:3090", host.endpoints.single().baseUrl)
        assertEquals(EndpointKind.LAN, host.endpoints.single().kind)
    }

    @Test
    fun migrateLegacyKeepsAddressWhenTokenIsMissing() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }

        val host = store.migrateLegacy("http://192.168.1.126:3090", null)

        assertNotNull("地址仍是有效信息，不应整个丢弃", host)
        assertEquals("192.168.1.126", host!!.displayName)
        assertNull(host.credential)
        assertEquals("http://192.168.1.126:3090", host.endpoints.single().baseUrl)
        assertEquals(1, store.loadHosts().size)
    }

    @Test
    fun migrateLegacyHandlesAddressWithoutScheme() {
        val store = HostStore(InMemoryKeyValueStore(), XorSecretBox()) { 1_000L }

        val host = store.migrateLegacy("192.168.1.126:3090", "legacy-token")!!

        assertEquals("http://192.168.1.126:3090", host.endpoints.single().baseUrl)
        assertEquals("192.168.1.126", host.displayName)
    }

    @Test
    fun migratedCredentialSurvivesReopenAndIsEncrypted() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = HostStore(keyValueStore, XorSecretBox()) { 1_000L }
        store.migrateLegacy("http://192.168.1.126:3090", "legacy-token")

        val raw = keyValueStore.getString(HostStore.KEY_HOSTS)!!
        assertFalse(raw.contains("legacy-token"))

        val reopened = HostStore(keyValueStore, XorSecretBox()) { 2_000L }
        assertEquals("legacy-token", reopened.loadHosts().single().credential!!.secret)
        assertNotEquals(0, reopened.loadHosts().single().endpoints.size)
    }
}
