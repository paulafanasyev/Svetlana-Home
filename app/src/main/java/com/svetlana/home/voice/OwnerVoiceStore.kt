package com.svetlana.home.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Шаблон голоса владельца: только числа (24 средних + 24 разброса + порог),
 * в EncryptedSharedPreferences (ключ в Android Keystore). Аудио не хранится никогда.
 * Ошибка чтения/расшифровки = «не записан»: голосовые команды тогда не исполняются.
 */
object OwnerVoiceStore {
    private const val PREFS = "svetlana_owner_voice"
    private const val K_TEMPLATE = "template_v1"

    @Volatile
    private var cached: VoicePrint.Template? = null

    @Volatile
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): VoicePrint.Template? {
        if (loaded) return cached
        synchronized(this) {
            if (!loaded) {
                cached = runCatching { VoicePrint.decode(prefs(context).getString(K_TEMPLATE, null)) }.getOrNull()
                loaded = true
            }
            return cached
        }
    }

    fun isEnrolled(context: Context): Boolean = load(context) != null

    /** true: шаблон сохранён на диск. */
    fun save(context: Context, t: VoicePrint.Template): Boolean = synchronized(this) {
        val ok = runCatching { prefs(context).edit().putString(K_TEMPLATE, VoicePrint.encode(t)).commit() }.getOrDefault(false)
        if (ok) { cached = t; loaded = true }
        ok
    }

    fun delete(context: Context): Boolean = synchronized(this) {
        val ok = runCatching { prefs(context).edit().clear().commit() }.getOrDefault(false)
        cached = null
        loaded = ok
        OwnerVoiceGate.lockout.reset()
        ok
    }
}
