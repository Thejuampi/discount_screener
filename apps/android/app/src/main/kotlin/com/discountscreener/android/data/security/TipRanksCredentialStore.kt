package com.discountscreener.android.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, backup-excluded AES-GCM credential envelope. The key itself never leaves AndroidKeyStore.
 * Callers receive only a nullable key and must not log or render it.
 */
class TipRanksCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "TipRanks API key cannot be blank." }
        val key = keyStoreKey(create = true) ?: error("Android Keystore key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putInt(FORMAT_VERSION_KEY, FORMAT_VERSION)
            .putString(KEY_ALIAS_KEY, KEY_ALIAS)
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    fun load(): String? {
        val alias = prefs.getString(KEY_ALIAS_KEY, null) ?: return null
        val iv = prefs.getString(IV_KEY, null) ?: return removeAndReturnNull(alias)
        val ciphertext = prefs.getString(CIPHERTEXT_KEY, null) ?: return removeAndReturnNull(alias)
        return runCatching {
            val key = keyStoreKey(alias, create = false) ?: error("Keystore entry missing")
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            }
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrElse { removeAndReturnNull(alias) }
    }

    /** Removes the envelope and the AndroidKeyStore alias; public forecast cache is intentionally retained. */
    fun remove() {
        val alias = prefs.getString(KEY_ALIAS_KEY, KEY_ALIAS) ?: KEY_ALIAS
        prefs.edit().clear().commit()
        deleteAlias(alias)
    }

    fun isConfigured(): Boolean = load() != null

    private fun removeAndReturnNull(alias: String): Nothing? {
        prefs.edit().clear().commit()
        deleteAlias(alias)
        return null
    }

    private fun keyStoreKey(alias: String = KEY_ALIAS, create: Boolean): javax.crypto.SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun deleteAlias(alias: String) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(alias)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "tipranks_credential_private"
        const val FORMAT_VERSION_KEY = "format_version"
        const val KEY_ALIAS_KEY = "key_alias"
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val FORMAT_VERSION = 1
        const val KEY_ALIAS = "discount_screener_tipranks_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
