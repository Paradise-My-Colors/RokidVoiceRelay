package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String,
)

object TelegramSecureStore {
    private const val PREFS = "voicerelay.telegram.secure.v1"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEY_DB_KEY = "database_key"
    private const val KEY_PHONE = "last_phone"
    private const val KEY_ALIAS = "voicerelay.telegram.aes.v1"

    fun hasCredentials(context: Context): Boolean = credentials(context) != null

    fun saveCredentials(context: Context, apiId: Int, apiHash: String) {
        require(apiId > 0)
        require(apiHash.isNotBlank())
        val raw = "$apiId\n${apiHash.trim()}"
        prefs(context).edit().putString(KEY_CREDENTIALS, encrypt(raw)).apply()
    }

    fun credentials(context: Context): TelegramCredentials? {
        val encrypted = prefs(context).getString(KEY_CREDENTIALS, null) ?: return null
        return runCatching {
            val parts = decrypt(encrypted).split('\n', limit = 2)
            val id = parts.first().toInt()
            val hash = parts.getOrNull(1).orEmpty()
            if (id <= 0 || hash.isBlank()) null else TelegramCredentials(id, hash)
        }.getOrNull()
    }

    fun savePhone(context: Context, phone: String) {
        prefs(context).edit().putString(KEY_PHONE, encrypt(phone.trim())).apply()
    }

    fun phone(context: Context): String? {
        val encrypted = prefs(context).getString(KEY_PHONE, null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** TDLib JSON represents bytes as Base64 strings. */
    fun databaseKeyBase64(context: Context): String {
        val existing = prefs(context).getString(KEY_DB_KEY, null)
        if (existing != null) {
            runCatching { decrypt(existing) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_DB_KEY, encrypt(value)).apply()
        return value
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > 12)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
