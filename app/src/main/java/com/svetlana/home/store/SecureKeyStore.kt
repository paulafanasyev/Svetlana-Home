package com.svetlana.home.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное хранилище для API-ключей и секретов personal server.
 * Использует AndroidX Security (AES-256 + ключ в Android Keystore).
 *
 * ВАЖНО: никаких ключей в репозитории. Значения попадают сюда только через
 * явный ввод пользователем и не выгружаются наружу.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // Запасной вариант: приватное хранилище приложения (не рекомендуется для секретов,
        // но приложение должно работать даже на устройствах без поддерживаемого Keystore).
        android.util.Log.w(TAG, "EncryptedSharedPreferences недоступен, используется приватное хранилище", t)
        context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
    }

    fun put(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun has(key: String): Boolean = prefs.contains(key)

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val FILE_NAME = "svetlana_secrets"
        private const val FALLBACK_NAME = "svetlana_secrets_fallback"
        const val PROVIDER_KEY_PREFIX = "provider_key_"
        const val SERVER_TOKEN = "server_token"
    }
}
