package io.github.westanke.dshcompanion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("dsh_connection", Context.MODE_PRIVATE)

    fun save(endpoint: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString("endpoint", endpoint)
            putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("token_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    fun load(): StoredConnection? {
        val endpoint = preferences.getString("endpoint", null) ?: return null
        val iv = preferences.getString("token_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encrypted = preferences.getString("token_ciphertext", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            StoredConnection(endpoint, cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        }.getOrNull()
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "deepseek-harness-device-token-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
