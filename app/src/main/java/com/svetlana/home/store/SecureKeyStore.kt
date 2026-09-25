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
 *
 * ПОЛИТИКА БЕЗОПАСНОСТИ: если защищённое хранилище недоступно, мы НЕ падаем
 * на обычный SharedPreferences для секретов — операция завершается ошибкой,
 * и вызывающая сторона сообщает пользователю, что секрет сохранить нельзя.
 * Исключение составляет только непустой уже сохранённый секрет, который
 * невозможно прочитать (хранилище повреждено) — такой секрет не возвращается.
 */
class SecureKeyStore private constructor(
    private val prefs: SharedPreferences,
    val isSecure: Boolean
) {

    fun put(key: String, value: String): Boolean {
        if (!isSecure) {
            android.util.Log.e(TAG, "Попытка сохранить секрет в небезопасное хранилище отклонена")
            return false
        }
        return try {
            prefs.edit().putString(key, value).apply()
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Не удалось сохранить секрет", t)
            false
        }
    }

    fun get(key: String): String? {
        if (!isSecure) return null
        return try {
            prefs.getString(key, null)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Не удалось прочитать секрет", t)
            null
        }
    }

    fun remove(key: String) {
        if (!isSecure) return
        try { prefs.edit().remove(key).apply() } catch (_: Throwable) {}
    }

    fun has(key: String): Boolean {
        if (!isSecure) return false
        return try { prefs.contains(key) } catch (_: Throwable) { false }
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val FILE_NAME = "svetlana_secrets"
        const val PROVIDER_KEY_PREFIX = "provider_key_"
        const val SERVER_TOKEN = "server_token"

        fun create(context: Context): SecureKeyStore {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                SecureKeyStore(prefs, isSecure = true)
            } catch (t: Throwable) {
                // Небезопасный fallback НЕ хранит секреты. Вызывающая сторона
                // видит isSecure=false и отказывается сохранять ключи.
                android.util.Log.e(TAG, "Защищённое хранилище недоступно — секреты не сохраняются", t)
                SecureKeyStore(EmptyPrefs, isSecure = false)
            }
        }
    }
}

/**
 * Пустое хранилище-заглушка для небезопасного режима: ничего не хранит.
 */
private object EmptyPrefs : SharedPreferences {
    override fun getAll(): MutableMap<String, Any?> = mutableMapOf()
    override fun getString(key: String?, defValue: String?): String? = null
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun edit(): SharedPreferences.Editor = EmptyEditor
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private object EmptyEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
    override fun remove(key: String?): SharedPreferences.Editor = this
    override fun clear(): SharedPreferences.Editor = this
    @Suppress("UNCHECKED_CAST")
    override fun commit(): Boolean = false
    override fun apply() {}
}
