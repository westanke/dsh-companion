package io.github.hakunm.deepseekharness.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把 [KeyValueStore] 落到 SharedPreferences 上。
 *
 * 注意：这里存的值**已经**是密文（由 [HostStore] 用 [SecretBox] 加密后再交过来）。
 * 本类不做任何加密，也绝不允许调用方把明文塞进来。
 */
class SharedPreferencesKeyValueStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : KeyValueStore {

    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        // putString(key, null) 语义与 remove 等价，统一交给 Editor 处理。
        preferences.edit { putString(key, value) }
    }

    override fun remove(key: String) {
        preferences.edit { remove(key) }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    companion object {
        /** 沿用旧版本已使用的偏好文件名，这样旧数据迁移时无需跨文件搬运。 */
        const val PREFERENCES_NAME = "dsh_connection"
    }
}

/**
 * 基于 Android Keystore 的 AES-GCM 加解密。
 *
 * 输出格式为 `IV(12 字节) || 密文+认证标签`，因此调用方只需保存一个 Base64 串，
 * 不必像旧实现那样额外维护一个 iv 字段 —— 少一个字段就少一处对不齐的机会。
 *
 * [alias] 可切换，用于读取历史密钥加密的数据：旧版本用
 * [LEGACY_DEVICE_TOKEN_ALIAS] 加密设备令牌，迁移时必须用同一别名解密。
 */
class KeystoreSecretBox(
    private val alias: String = DEFAULT_ALIAS,
) : SecretBox {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size > IV_LENGTH) { "Ciphertext is too short to contain an IV and payload." }
        val iv = cipher.copyOfRange(0, IV_LENGTH)
        val payload = cipher.copyOfRange(IV_LENGTH, cipher.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(payload)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        /** 新版：加密整个 Host 列表。 */
        const val DEFAULT_ALIAS = "dsh-companion-hosts-v1"

        /** 旧版：单连接时代用来加密设备令牌，仅为迁移而保留。 */
        const val LEGACY_DEVICE_TOKEN_ALIAS = "deepseek-harness-device-token-v1"

        /** 旧版存放密文与 IV 的偏好键，仅供迁移读取。 */
        const val LEGACY_ENDPOINT_KEY = "endpoint"
        const val LEGACY_TOKEN_IV_KEY = "token_iv"
        const val LEGACY_TOKEN_CIPHERTEXT_KEY = "token_ciphertext"
    }
}

/**
 * 一次性把旧版「单地址 + 单令牌」迁移成新版的一个 [Host]。
 *
 * 旧版把 IV 与密文分开存（`token_iv` / `token_ciphertext`），新版把 IV 拼在密文头部，
 * 所以这里要手工重组后再解密。
 *
 * 迁移是**幂等**的：只要旧键仍然存在且新库为空才会动作；成功后旧键会被清掉。
 * 任何一步失败都不抛异常（迁移失败不应该让 App 起不来），只是返回 null 让调用方
 * 继续走「未连接」流程，用户仍可手工添加地址。
 */
object LegacyConnectionMigration {

    data class Migrated(val host: Host, val deviceName: String?)

    fun migrate(
        storage: KeyValueStore,
        legacySecretBox: SecretBox,
        hostStore: HostStore,
        idFactory: () -> String = { "legacy-host" },
    ): Migrated? {
        val endpoint = storage.getString(KeystoreSecretBox.LEGACY_ENDPOINT_KEY)?.takeIf { it.isNotBlank() }
            ?: return null
        val iv = storage.getString(KeystoreSecretBox.LEGACY_TOKEN_IV_KEY)?.let(::decodeBase64)
        val cipherText = storage.getString(KeystoreSecretBox.LEGACY_TOKEN_CIPHERTEXT_KEY)?.let(::decodeBase64)

        val token = if (iv != null && cipherText != null) {
            runCatching { legacySecretBox.decrypt(iv + cipherText).toString(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }

        val host = runCatching { hostStore.migrateLegacy(endpoint, token) }.getOrNull() ?: return null

        // 迁移成功后清掉旧键，避免每次启动重复迁移；同时避免旧密文长期滞留在偏好里。
        storage.remove(KeystoreSecretBox.LEGACY_TOKEN_IV_KEY)
        storage.remove(KeystoreSecretBox.LEGACY_TOKEN_CIPHERTEXT_KEY)
        // 旧版把地址明文存在 endpoint 键；新库已持有该地址，这里一并清理。
        storage.remove(KeystoreSecretBox.LEGACY_ENDPOINT_KEY)

        return Migrated(host, null)
    }

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { android.util.Base64.decode(value, android.util.Base64.NO_WRAP) }.getOrNull()
}
